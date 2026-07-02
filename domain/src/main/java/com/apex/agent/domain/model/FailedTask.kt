package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class FailedTask(
    val id: String,
    val task: BurstTask,
    val error: String,
    val failedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val stackTrace: String? = null
)
