package com.apex.agent.core.memory.unified

import com.apex.agent.data.burstmode.memory.BurstExclusiveMemory
import com.apex.agent.data.burstmode.memory.BurstMemoryItem
import com.apex.agent.data.burstmode.memory.HierarchicalMemory
import com.apex.agent.kernel.interaction.awareness.ContextFact
import com.apex.agent.kernel.interaction.awareness.ContextMemory
import com.apex.agent.kernel.interaction.awareness.FactCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class UnifiedMemoryManager private constructor() {

    companion object {
        @Volatile
        private var instance: UnifiedMemoryManager? = null

        fun getInstance(): UnifiedMemoryManager {
            return instance ?: synchronized(this) {
                instance ?: UnifiedMemoryManager().also { instance = it }
            }
        }
        fun resetInstance() {
            instance?.release()
            instance = null
        }
    }
        private var burstMemory: BurstExclusiveMemory? = null
    private var hierarchicalMemory: HierarchicalMemory? = null
    private var memoryRepository: MemoryRepository? = null
    private var contextMemory: ContextMemory? = null
    private var sharedMemoryPool: SharedMemoryPool? = null
    private var crossModeBridge: CrossModeMemoryBridge? = null
    private var compressor: SmartMemoryCompressor? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val _currentMode = MutableStateFlow(AgentMode.SINGLE_AGENT)
        val currentMode: StateFlow<AgentMode> = _currentMode.asStateFlow()
        private val config = ModeAwareMemoryConfig()
        private val _memoryEvents = MutableSharedFlow<MemoryTransferEvent>(replay = 0)
        val memoryEvents: SharedFlow<MemoryTransferEvent> = _memoryEvents.asSharedFlow()
        private val modeMemory = ConcurrentHashMap<AgentMode, MutableList<UnifiedMemoryItem>>()
        private var initialized = false

    fun initialize(
        burstMem: BurstExclusiveMemory? = null,
        hierMem: HierarchicalMemory? = null,
        repo: MemoryRepository? = null,
        ctxMem: ContextMemory? = null
    ) {
        if (initialized) return
        burstMemory = burstMem
        hierarchicalMemory = hierMem
        memoryRepository = repo
        contextMemory = ctxMem
        sharedMemoryPool = SharedMemoryPool()
        crossModeBridge = CrossModeMemoryBridge(this, sharedMemoryPool!!)
        compressor = SmartMemoryCompressor()
        initialized = true
    }
        fun release() {
        scope.cancel()
        modeMemory.clear()
        sharedMemoryPool?.clear()
        initialized = false
    }
        fun switchMode(newMode: AgentMode) {
        val oldMode = _currentMode.value
        if (oldMode == newMode) return

        scope.launch {
            crossModeBridge?.onModeSwitch(oldMode, newMode)
        }
        _currentMode.value = newMode
    }
        fun getConfigForMode(mode: AgentMode): MemoryModeConfig {
        return when (mode) {
            AgentMode.SINGLE_AGENT -> config.singleAgent
            AgentMode.MULTI_AGENT -> config.multiAgent
            AgentMode.BURST_MODE -> config.burstMode
        }
    }

    suspend fun store(item: UnifiedMemoryItem, mode: AgentMode = _currentMode.value): String {
        val modeItems = modeMemory.getOrPut(mode) { mutableListOf() }
        val modeConfig = getConfigForMode(mode)
        if (modeItems.size >= modeConfig.maxMemoryItems) {
            pruneModeMemory(mode, modeConfig)
        }

        modeItems.add(item)
        when (mode) {
            AgentMode.SINGLE_AGENT -> {
                contextMemory?.let { ctx ->
                    ctx.remember(
                        ContextFact(
                            id = item.id,
                            content = item.content,
                            category = FactCategory.GENERAL,
                            confidence = item.confidence
                        )
                    )
                }
            }
            AgentMode.MULTI_AGENT -> {
                sharedMemoryPool?.writeSharedMemory(
                    SharedMemoryEntry(
                        entryId = item.id,
                        taskId = item.tags.firstOrNull() ?: "default",
                        content = item.content,
                        agentRole = item.metadata["agentRole"] as? String ?: "system",
                        priority = item.priority.value
                    )
                )
            }
            AgentMode.BURST_MODE -> {
                burstMemory?.let { burst ->
                    burst.store(
                        BurstMemoryItem(
                            id = item.id,
                            taskId = item.tags.firstOrNull() ?: "burst",
                            content = item.content,
                            chunkId = item.metadata["chunkId"] as? String ?: "",
                            priority = item.priority.value,
                            layer = 1
                        )
                    )
                }
            }
        }

        _memoryEvents.emit(
            MemoryTransferEvent(
                item = item,
                fromMode = mode,
                toMode = mode
            )
        )
        return item.id
    }

    suspend fun retrieve(id: String, mode: AgentMode = _currentMode.value): UnifiedMemoryItem? {
        val modeItems = modeMemory[mode]
        val localItem = modeItems?.find { it.id == id }
        if (localItem != null) return localItem

        return when (mode) {
            AgentMode.SINGLE_AGENT -> {
                contextMemory?.let { ctx ->
                    val facts = ctx.recall("", 1)
                    facts.firstOrNull()?.let { fact ->
                        UnifiedMemoryItem(
                            id = fact.id,
                            content = fact.content,
                            sourceMode = AgentMode.SINGLE_AGENT,
                            importance = fact.confidence
                        )
                    }
                }
            }
            AgentMode.MULTI_AGENT -> {
                sharedMemoryPool?.let { pool ->
                    pool.getTaskSharedMemories("").firstOrNull { it.entryId == id }?.let { entry ->
                        UnifiedMemoryItem(
                            id = entry.entryId,
                            content = entry.content,
                            sourceMode = AgentMode.MULTI_AGENT
                        )
                    }
                }
            }
            AgentMode.BURST_MODE -> {
                burstMemory?.retrieveSync(id)?.let { burstItem ->
                    UnifiedMemoryItem(
                        id = burstItem.id,
                        content = burstItem.content,
                        sourceMode = AgentMode.BURST_MODE,
                        priority = when {
                            burstItem.priority >= 3 -> MemoryPriority.CRITICAL
                            burstItem.priority >= 2 -> MemoryPriority.HIGH
                            burstItem.priority >= 1 -> MemoryPriority.NORMAL
                            else -> MemoryPriority.LOW
                        },
                        importance = burstItem.confidence
                    )
                }
            }
        }
    }

    suspend fun search(
        query: String,
        mode: AgentMode = _currentMode.value,
        limit: Int = 10
    ): List<UnifiedMemoryItem> {
        val results = mutableListOf<UnifiedMemoryItem>()
        when (mode) {
            AgentMode.SINGLE_AGENT -> {
                contextMemory?.let { ctx ->
                    val facts = ctx.recall(query, limit)
                    results.addAll(facts.map { fact ->
                        UnifiedMemoryItem(
                            id = fact.id,
                            content = fact.content,
                            sourceMode = AgentMode.SINGLE_AGENT,
                            confidence = fact.confidence,
                            tags = setOf(fact.category.name)
                        )
                    })
                }
            }
            AgentMode.MULTI_AGENT -> {
                sharedMemoryPool?.let { pool ->
                    val allModes = AgentMode.values().toList()
        for (m in allModes) {
                        val modeItems = modeMemory[m] ?: continue
                        val matched = modeItems
                            .filter { it.content.contains(query, ignoreCase = true) }
                            .take(limit / max(1, allModes.size))
                        results.addAll(matched)
                    }
                }
            }
            AgentMode.BURST_MODE -> {
                burstMemory?.let { burst ->
                    val items = burst.search(query).take(limit)
                    results.addAll(items.map { item ->
                        UnifiedMemoryItem(
                            id = item.id,
                            content = item.content,
                            sourceMode = AgentMode.BURST_MODE,
                            priority = when {
                                item.priority >= 3 -> MemoryPriority.CRITICAL
                                item.priority >= 2 -> MemoryPriority.HIGH
                                item.priority >= 1 -> MemoryPriority.NORMAL
                                else -> MemoryPriority.LOW
                            },
                            importance = item.confidence
                        )
                    })
                }
                hierarchicalMemory?.let { hier ->
                    val taskId = query.split("\\s+".toRegex()).firstOrNull() ?: ""
        val items = hier.retrieveSync(taskId, query, limit)
                    results.addAll(items.map { item ->
                        UnifiedMemoryItem(
                            id = item.id,
                            content = item.content,
                            sourceMode = AgentMode.BURST_MODE,
                            tags = setOf(item.taskId)
                        )
                    })
                }
            }
        }
        val modeItems = modeMemory[mode] ?: return results.distinctBy { it.id }.take(limit)
        val localResults = modeItems
            .filter { it.content.contains(query, ignoreCase = true) }
            .take(limit)
        results.addAll(0, localResults)
        return results.distinctBy { it.id }.take(limit)
    }

    suspend fun searchCrossMode(query: String, limit: Int = 10): List<UnifiedMemoryItem> {
        val results = mutableListOf<UnifiedMemoryItem>()
        for (mode in AgentMode.values()) {
            val modeResults = search(query, mode, limit / AgentMode.values().size)
            results.addAll(modeResults)
        }
        return results.distinctBy { it.id }.take(limit)
    }

    suspend fun forget(id: String, mode: AgentMode = _currentMode.value): Boolean {
        modeMemory[mode]?.removeAll { it.id == id }
        return when (mode) {
            AgentMode.SINGLE_AGENT -> {
                contextMemory?.let {
                    it.forget(id)
                    true
                } ?: false
            }
            AgentMode.MULTI_AGENT -> true
            AgentMode.BURST_MODE -> {
                burstMemory?.let {
                    runBlocking { it.delete(id) }
                } ?: false
            }
        }
    }

    suspend fun consolidate(mode: AgentMode = _currentMode.value): CompressionReport {
        val modeConfig = getConfigForMode(mode)
        val items = modeMemory[mode] ?: return CompressionReport(mode, 0, 0)
        val before = items.size

        val report = compressor?.compress(this, mode) ?: CompressionReport(mode, before, before)
        val avgImpBefore = if (items.isNotEmpty()) items.sumOf { it.importance.toDouble() }.toFloat() / items.size else 0f
        val itemsAfter = modeMemory[mode]?.size ?: before
        val avgImpAfter = if (itemsAfter > 0) {
            val remaining = modeMemory[mode] ?: emptyList()
            remaining.sumOf { it.importance.toDouble() }.toFloat() / remaining.size
        } else 0f

        return CompressionReport(
            mode = mode,
            itemsBefore = before,
            itemsAfter = itemsAfter,
            avgImportanceBefore = avgImpBefore,
            avgImportanceRetained = avgImpAfter
        )
    }
        fun getStats(mode: AgentMode = _currentMode.value): ModeMemoryStats {
        val items = modeMemory[mode] ?: emptyList()
        val avgImp = if (items.isNotEmpty()) {
            items.sumOf { it.importance.toDouble() }.toFloat() / items.size
        } else 0f

        return ModeMemoryStats(
            mode = mode,
            totalItems = items.size,
            avgImportance = avgImp
        )
    }
        fun getAllStats(): List<ModeMemoryStats> {
        return AgentMode.values().map { getStats(it) }
    }
        fun getSharedMemoryPool(): SharedMemoryPool? = sharedMemoryPool

    fun getCrossModeBridge(): CrossModeMemoryBridge? = crossModeBridge

    fun getCompressor(): SmartMemoryCompressor? = compressor

    fun getBurstMemory(): BurstExclusiveMemory? = burstMemory

    fun getHierarchicalMemory(): HierarchicalMemory? = hierarchicalMemory

    fun getContextMemory(): ContextMemory? = contextMemory

    private fun pruneModeMemory(mode: AgentMode, modeConfig: MemoryModeConfig) {
        val items = modeMemory[mode] ?: return
        val targetSize = (modeConfig.maxMemoryItems * 0.75).toInt()
        if (items.size <= targetSize) return

        val sorted = items.sortedWith(
            compareByDescending<UnifiedMemoryItem> { it.importance }
                .thenByDescending { it.priority.value }
                .thenByDescending { it.timestamp }
        )
        modeMemory[mode] = sorted.take(targetSize).toMutableList()
    }
        fun clearMode(mode: AgentMode) {
        modeMemory[mode]?.clear()
    }
        fun clearAll() {
        modeMemory.clear()
        sharedMemoryPool?.clear()
    }
}
