package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class BurstTask(
    val id: String,
    val name: String,
    val description: String,
    val input: BurstInput,
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val progress: Float = 0f,
    val checkpoint: Int = 0,
    val maxRetries: Int = 3,
    val currentRetries: Int = 0,
    val timeout: Long? = null,
    val skillId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class BurstInput(
    val text: String? = null,
    val files: List<BurstFileRef> = emptyList(),
    val parameters: Map<String, String> = emptyMap()
)

@Serializable
data class BurstFileRef(
    val path: String,
    val name: String,
    val size: Long,
    val mimeType: String
)
