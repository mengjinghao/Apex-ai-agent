package com.apex.agent.domain.event

data class WorkflowExecutedEvent(
    val workflowId: String,
    val taskId: String,
    val success: Boolean,
    val timestamp: Long
)
