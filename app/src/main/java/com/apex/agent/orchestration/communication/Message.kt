package com.apex.agent.orchestration.communication

import com.apex.agent.domain.entity.AgentMessage

enum class CommunicationChannel {
    TEXT, VOICE, NOTIFICATION, PUSH, WEBHOOK
}

data class Message(
    val channel: CommunicationChannel,
    val agentMessage: AgentMessage,
    val metadata: Map<String, Any> = emptyMap()
)
