package com.apex.agent

// STUBBED: had 2 errors
enum class AgentEventType { DEFAULT }
sealed class AgentEvent
data class Registered(val placeholder: String = "")
data class Unregistered(val placeholder: String = "")
data class LifecycleStateChanged(val placeholder: String = "")
data class HealthChanged(val placeholder: String = "")
data class AgentMessageSent(val placeholder: String = "")
data class Custom(val placeholder: String = "")
class AgentEventBus
object GlobalAgentEventBus
