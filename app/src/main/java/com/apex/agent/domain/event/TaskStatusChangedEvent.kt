package com.apex.agent.domain.event

data class TaskStatusChangedEvent(
    val taskId: String,
    val previousStatus: String,
    val newStatus: String,
    val timestamp: Long
)
