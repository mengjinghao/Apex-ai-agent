package com.apex.agent.core.memory.unified

import com.apex.agent.data.burstmode.memory.BurstMemoryItem
import com.apex.agent.kernel.interaction.awareness.ContextFact
import com.apex.agent.kernel.interaction.awareness.FactCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedDeque

class CrossModeMemoryBridge(
    private val unifiedManager: UnifiedMemoryManager,
    private val sharedPool: SharedMemoryPool
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val transferHistory = ConcurrentLinkedDeque<MemoryTransferEvent>()
        private val importanceThreshold = 0.6f
    private val batchWindowMs = 200L

    private val _transferEvents = MutableSharedFlow<MemoryTransferEvent>(replay = 10)
        val transferEvents: SharedFlow<MemoryTransferEvent> = _transferEvents.asSharedFlow()

    suspend fun burstToSingleAgent(burstItem: BurstMemoryItem) {
        if (burstItem.confidence < importanceThreshold) return

        val unifiedItem = UnifiedMemoryItem(
            id = burstItem.id,
            content = burstItem.content,
            sourceMode = AgentMode.BURST_MODE,
            importance = burstItem.confidence,
            priority = when {
                burstItem.priority >= 3 -> MemoryPriority.CRITICAL
                burstItem.priority >= 2 -> MemoryPriority.HIGH
                burstItem.priority >= 1 -> MemoryPriority.NORMAL
                else -> MemoryPriority.LOW
            },
            tags = setOf(burstItem.taskId, "burst_experience"),
            metadata = mapOf("chunkId" to burstItem.chunkId)
        )

        unifiedManager.store(unifiedItem, AgentMode.SINGLE_AGENT)
        val event = MemoryTransferEvent(
            item = unifiedItem,
            fromMode = AgentMode.BURST_MODE,
            toMode = AgentMode.SINGLE_AGENT
        )
        transferHistory.addFirst(event)
        if (transferHistory.size > 1000) transferHistory.removeLast()
        _transferEvents.emit(event)
    }

    suspend fun multiAgentToSingleAgent(entry: SharedMemoryEntry) {
        if (entry.priority < (importanceThreshold * 100).toInt()) return

        val unifiedItem = UnifiedMemoryItem(
            id = entry.entryId,
            content = entry.content,
            sourceMode = AgentMode.MULTI_AGENT,
            importance = entry.priority / 100f,
            priority = when {
                entry.priority >= 80 -> MemoryPriority.CRITICAL
                entry.priority >= 60 -> MemoryPriority.HIGH
                entry.priority >= 40 -> MemoryPriority.NORMAL
                else -> MemoryPriority.LOW
            },
            tags = setOf(entry.taskId, entry.agentRole, "collaboration"),
            metadata = mapOf("agentRole" to entry.agentRole)
        )

        unifiedManager.store(unifiedItem, AgentMode.SINGLE_AGENT)
        val event = MemoryTransferEvent(
            item = unifiedItem,
            fromMode = AgentMode.MULTI_AGENT,
            toMode = AgentMode.SINGLE_AGENT
        )
        transferHistory.addFirst(event)
        if (transferHistory.size > 1000) transferHistory.removeLast()
        _transferEvents.emit(event)
    }

    suspend fun singleAgentToBurst(fact: ContextFact) {
        if (fact.confidence < importanceThreshold) return

        val burstItem = BurstMemoryItem(
            id = fact.id,
            taskId = "context_transfer",
            content = fact.content,
            chunkId = "ctx_${fact.id.take(8)}",
            confidence = fact.confidence,
            priority = (fact.confidence * 5).toInt().coerceIn(0, 5),
            layer = 1
        )

        unifiedManager.getBurstMemory()?.let { mem ->
            mem.store(burstItem)
        }
        val unifiedItem = UnifiedMemoryItem(
            id = fact.id,
            content = fact.content,
            sourceMode = AgentMode.SINGLE_AGENT,
            importance = fact.confidence,
            tags = setOf(fact.category.name, "context_experience")
        )
        val event = MemoryTransferEvent(
            item = unifiedItem,
            fromMode = AgentMode.SINGLE_AGENT,
            toMode = AgentMode.BURST_MODE
        )
        transferHistory.addFirst(event)
        if (transferHistory.size > 1000) transferHistory.removeLast()
        _transferEvents.emit(event)
    }

    suspend fun singleAgentToMultiAgent(fact: ContextFact) {
        if (fact.confidence < importanceThreshold) return

        val entry = SharedMemoryEntry(
            entryId = fact.id,
            taskId = "context_shared",
            content = fact.content,
            agentRole = "context_bridge",
            priority = (fact.confidence * 100).toInt()
        )

        sharedPool.writeSharedMemory(entry)
        val event = MemoryTransferEvent(
            item = UnifiedMemoryItem(
                id = fact.id,
                content = fact.content,
                sourceMode = AgentMode.SINGLE_AGENT,
                tags = setOf(fact.category.name)
            ),
            fromMode = AgentMode.SINGLE_AGENT,
            toMode = AgentMode.MULTI_AGENT
        )
        _transferEvents.emit(event)
    }

    suspend fun burstToMultiAgent(burstItem: BurstMemoryItem) {
        if (burstItem.confidence < importanceThreshold) return

        val entry = SharedMemoryEntry(
            entryId = burstItem.id,
            taskId = burstItem.taskId,
            content = burstItem.content,
            agentRole = "burst_bridge",
            priority = (burstItem.confidence * 100).toInt()
        )

        sharedPool.writeSharedMemory(entry)
        val event = MemoryTransferEvent(
            item = UnifiedMemoryItem(
                id = burstItem.id,
                content = burstItem.content,
                sourceMode = AgentMode.BURST_MODE,
                tags = setOf(burstItem.taskId)
            ),
            fromMode = AgentMode.BURST_MODE,
            toMode = AgentMode.MULTI_AGENT
        )
        _transferEvents.emit(event)
    }

    suspend fun onModeSwitch(oldMode: AgentMode, newMode: AgentMode) {
        val pendingTransfers = transferHistory.toList()
            .filter { it.fromMode == oldMode && it.toMode == newMode }
        for (transfer in pendingTransfers.take(20)) {
            unifiedManager.store(transfer.item, newMode)
        }
        when {
            oldMode == AgentMode.BURST_MODE && newMode == AgentMode.SINGLE_AGENT -> {
                val burstMem = unifiedManager.getBurstMemory()
                burstMem?.let { mem ->
                    val allItems = mem.getL1Memory().values + mem.getL2Memory().values + mem.getL3Memory().values
                    for (item in allItems.take(50)) {
                        burstToSingleAgent(item)
                        delay(5)
                    }
                }
            }
            oldMode == AgentMode.BURST_MODE && newMode == AgentMode.MULTI_AGENT -> {
                val burstMem = unifiedManager.getBurstMemory()
                burstMem?.let { mem ->
                    val allItems = mem.getL1Memory().values + mem.getL2Memory().values
                    for (item in allItems.take(30)) {
                        burstToMultiAgent(item)
                        delay(5)
                    }
                }
            }
            oldMode == AgentMode.SINGLE_AGENT && newMode == AgentMode.MULTI_AGENT -> {
                val ctxMem = unifiedManager.getContextMemory()
                ctxMem?.let { ctx ->
                    val summary = ctx.getSummary()
        for (fact in summary.facts.take(20)) {
                        singleAgentToMultiAgent(fact)
                        delay(5)
                    }
                }
            }
            oldMode == AgentMode.SINGLE_AGENT && newMode == AgentMode.BURST_MODE -> {
                val ctxMem = unifiedManager.getContextMemory()
                ctxMem?.let { ctx ->
                    val summary = ctx.getSummary()
        for (fact in summary.facts.take(20)) {
                        singleAgentToBurst(fact)
                        delay(5)
                    }
                }
            }
            oldMode == AgentMode.MULTI_AGENT && newMode == AgentMode.SINGLE_AGENT -> {
                val allMemories = sharedPool.getAllMemories()
        for (entry in allMemories.take(20)) {
                    multiAgentToSingleAgent(entry)
                    delay(5)
                }
            }
            oldMode == AgentMode.MULTI_AGENT && newMode == AgentMode.BURST_MODE -> {
                val allMemories = sharedPool.getAllMemories()
        for (entry in allMemories.take(20)) {
                    val fact = ContextFact(
                        id = entry.entryId,
                        content = entry.content,
                        category = FactCategory.GENERAL,
                        confidence = entry.priority / 100f
                    )
                    singleAgentToBurst(fact)
                    delay(5)
                }
            }
        }
    }
        fun getTransferHistory(count: Int = 50): List<MemoryTransferEvent> {
        return transferHistory.toList().take(count)
    }
        fun clearTransferHistory() {
        transferHistory.clear()
    }
}
