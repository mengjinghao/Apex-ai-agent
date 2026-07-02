package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionLog(
    val id: String,
    val taskId: String,
    val skillId: String,
    val level: LogLevel,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}
