package com.apex.agent.core.memory.unified

import com.apex.agent.data.burstmode.memory.BurstExclusiveMemory
import com.apex.agent.data.burstmode.memory.BurstMemoryItem
import com.apex.agent.data.burstmode.memory.HierarchicalMemory
import com.apex.agent.kernel.interaction.awareness.ContextMemory

class SmartMemoryCompressor {

    suspend fun compress(unifiedManager: UnifiedMemoryManager, mode: AgentMode): CompressionReport {
        return when (mode) {
            AgentMode.BURST_MODE -> compressBurstMode(unifiedManager)
            AgentMode.MULTI_AGENT -> compressMultiAgent(unifiedManager)
            AgentMode.SINGLE_AGENT -> compressSingleAgent(unifiedManager)
        }
    }

    suspend fun compressAll(unifiedManager: UnifiedMemoryManager): List<CompressionReport> {
        return AgentMode.values().map { compress(unifiedManager, it) }
    }

    private suspend fun compressBurstMode(unifiedManager: UnifiedMemoryManager): CompressionReport {
        val burstMem = unifiedManager.getBurstMemory()
        val hierMem = unifiedManager.getHierarchicalMemory()
        val itemsBefore = burstMem?.getStats()?.totalCount ?: 0

        burstMem?.let { mem ->
            val allTasks = setOf("burst") + mem.getL1Memory().values.map { it.taskId }.toSet()
            for (taskId in allTasks) {
                hierMem?.evictLowValueMemory(taskId)
            }

            val l1Items = mem.getL1Memory().values.toList()
            if (l1Items.size > 50) {
                val sortedByValue = l1Items.sortedWith(
                    compareByDescending<BurstMemoryItem> { it.priority }
                        .thenByDescending { it.accessCount }
                )
                val toKeep = sortedByValue.take(40)
                val toDemote = sortedByValue.drop(40)
                for (item in toDemote) {
                    mem.demoteToL2(item.id)
                }
            }

            val l2Items = mem.getL2Memory().values.toList()
            if (l2Items.size > 500) {
                val sortedByValue = l2Items.sortedWith(
                    compareByDescending<BurstMemoryItem> { it.priority }
                        .thenByDescending { it.accessCount }
                )
                val toKeep = sortedByValue.take(400)
                val toDemote = sortedByValue.drop(400)
                for (item in toDemote) {
                    mem.demoteToL3(item.id)
                }
            }
        }

        val itemsAfter = burstMem?.getStats()?.totalCount ?: 0
        return CompressionReport(
            mode = AgentMode.BURST_MODE,
            itemsBefore = itemsBefore,
            itemsAfter = itemsAfter
        )
    }

    private suspend fun compressMultiAgent(unifiedManager: UnifiedMemoryManager): CompressionReport {
        val pool = unifiedManager.getSharedMemoryPool()
        val allMemories = pool?.getAllMemories() ?: emptyList()
        val itemsBefore = allMemories.size

        val consensusMemories = allMemories.filter { entry ->
            entry.priority >= 50
        }

        val divergentMemories = allMemories.filter { entry ->
            entry.priority < 50 && !entry.isFinal
        }

        for (divergent in divergentMemories.take(divergentMemories.size / 2)) {
            pool?.clearTaskMemory(divergent.taskId)
        }

        val itemsAfter = pool?.getAllMemories()?.size ?: itemsBefore
        return CompressionReport(
            mode = AgentMode.MULTI_AGENT,
            itemsBefore = itemsBefore,
            itemsAfter = itemsAfter
        )
    }

    private suspend fun compressSingleAgent(unifiedManager: UnifiedMemoryManager): CompressionReport {
        val ctxMem = unifiedManager.getContextMemory()
        val itemsBefore = ctxMem?.getFactCount() ?: 0

        ctxMem?.let { ctx ->
            val config = unifiedManager.getConfigForMode(AgentMode.SINGLE_AGENT)
            val targetSize = (config.maxMemoryItems * 0.75).toInt()

            val summary = ctx.getSummary()
            val highConfidence = summary.facts.filter { it.confidence >= 0.7f }
            val lowConfidence = summary.facts.filter { it.confidence < 0.4f }

            for (fact in lowConfidence) {
                ctx.forget(fact.id)
            }

            val remaining = ctx.getFactCount()
            if (remaining > targetSize) {
                for (i in 0 until (remaining - targetSize)) {
                    val facts = ctx.recall("", 100)
                    val oldest = facts.minByOrNull { it.importance }
                    oldest?.let { ctx.forget(it.id) }
                }
            }
        }

        val itemsAfter = ctxMem?.getFactCount() ?: itemsBefore
        return CompressionReport(
            mode = AgentMode.SINGLE_AGENT,
            itemsBefore = itemsBefore,
            itemsAfter = itemsAfter
        )
    }
}
