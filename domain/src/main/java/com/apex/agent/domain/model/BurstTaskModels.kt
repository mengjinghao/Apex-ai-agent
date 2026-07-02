package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class BurstTaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
enum class BurstTaskPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
data class BurstTaskConstraints(
    val maxSteps: Int = 100,
    val maxDurationMs: Long = 3600000,
    val maxRetries: Int = 3,
    val allowedSkills: List<String> = emptyList(),
    val blockedSkills: List<String> = emptyList(),
    val resourceLimitMb: Long = 512,
    val requiresApproval: Boolean = false
)

@Serializable
data class BurstSnapshot(
    val snapshotId: String,
    val taskId: String,
    val checkpoint: Int,
    val state: Map<String, String>,
    val createdAt: Long,
    val memorySize: Long
)

@Serializable
data class ExecutionGraph(
    val graphId: String,
    val taskId: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val createdAt: Long
)

@Serializable
data class GraphNode(
    val nodeId: String,
    val type: String,
    val label: String,
    val status: String,
    val progress: Float,
    val estimatedTimeMs: Long
)

@Serializable
data class GraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val type: String
)
