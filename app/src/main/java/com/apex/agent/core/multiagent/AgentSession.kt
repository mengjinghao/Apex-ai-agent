package com.apex.agent.core.multiagent

class AgentSession(
    val id: String,
    val taskId: String,
    val messages: List<AgentMessage> = emptyList(),
    val startTime: Long = System.currentTimeMillis(),
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val status: CollaborationTask.Status = CollaborationTask.Status.PENDING
)
