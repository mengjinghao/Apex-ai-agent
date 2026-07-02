package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskQueueItem(
    val id: String,
    val task: BurstTask,
    val priority: Int,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val scheduledAt: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null
)
