package com.apex.agent.core.memory.unified

import java.util.UUID

data class UnifiedMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val sourceMode: AgentMode,
    val priority: MemoryPriority = MemoryPriority.NORMAL,
    val importance: Float = 0.5f,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, Any> = emptyMap(),
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UnifiedMemoryItem
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

data class ModeAwareMemoryConfig(
    val singleAgent: MemoryModeConfig = MemoryModeConfig(),
    val multiAgent: MemoryModeConfig = MemoryModeConfig(
        maxMemoryItems = 2000,
        sharedMemoryEnabled = true,
        contextInheritanceEnabled = true
    ),
    val burstMode: MemoryModeConfig = MemoryModeConfig(
        maxMemoryItems = 10000,
        priorityLevels = mapOf("execution" to 0.9, "reasoning" to 0.8, "observation" to 0.6),
        memoryDecayRate = 0.98,
        sharedMemoryEnabled = false,
        contextInheritanceEnabled = false
    )
)

data class MemoryModeConfig(
    val priorityLevels: Map<String, Double> = emptyMap(),
    val maxMemoryItems: Int = 1000,
    val memoryDecayRate: Double = 0.95,
    val sharedMemoryEnabled: Boolean = false,
    val contextInheritanceEnabled: Boolean = true,
    val l1Capacity: Int = 100,
    val l2Capacity: Int = 1000,
    val l3Capacity: Int = 10000,
    val similarityThreshold: Double = 0.3,
    val autoConsolidate: Boolean = false,
    val consolidateIntervalMs: Long = 5 * 60 * 1000L
)

data class MemoryTransferEvent(
    val item: UnifiedMemoryItem,
    val fromMode: AgentMode,
    val toMode: AgentMode,
    val timestamp: Long = System.currentTimeMillis()
)

data class ModeMemoryStats(
    val mode: AgentMode,
    val totalItems: Int,
    val l1Items: Int = 0,
    val l2Items: Int = 0,
    val l3Items: Int = 0,
    val avgImportance: Float = 0f,
    val totalTransfersIn: Int = 0,
    val totalTransfersOut: Int = 0
)

data class CompressionReport(
    val mode: AgentMode,
    val itemsBefore: Int,
    val itemsAfter: Int,
    val freedBytes: Long = 0L,
    val avgImportanceBefore: Float = 0f,
    val avgImportanceRetained: Float = 0f
)
