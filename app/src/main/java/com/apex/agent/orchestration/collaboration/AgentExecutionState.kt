package com.apex.agent.orchestration.collaboration

import com.apex.agent.domain.entity.AgentMessage

data class AgentExecutionState(
    val agentId: String,
    val status: AgentStatus,
    val currentTask: String?,
    val progress: Float,
    val lastUpdateTime: Long,
    val messages: List<AgentMessage> = emptyList()
)
