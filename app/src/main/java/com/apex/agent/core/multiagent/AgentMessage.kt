package com.apex.agent.core.multiagent

class AgentMessage(
    val sender: String,
    val content: String,
    val timestamp: Long,
    val type: Type
) {
    enum class Type {
        USER,
        AGENT,
        SYSTEM
    }
}
