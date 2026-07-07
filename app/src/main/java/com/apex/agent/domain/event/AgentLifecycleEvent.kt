package com.apex.agent.domain.event

data class AgentLifecycleEvent(
    val agentId: String,
    val state: String,
    val timestamp: Long
)
