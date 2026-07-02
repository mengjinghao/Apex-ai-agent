package com.apex.agent.domain.entity

data class AgentMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val timestamp: Long,
    val taskId: String = ""
)
