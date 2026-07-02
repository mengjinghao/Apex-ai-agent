package com.apex.agent.domain.entity

data class AgentLog(
    val id: String,
    val agentId: String,
    val taskId: String,
    val level: String,
    val message: String,
    val timestamp: Long
)
