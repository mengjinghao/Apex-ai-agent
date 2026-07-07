package com.apex.agent.domain.event

data class MessageReceivedEvent(
    val messageId: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long
)
