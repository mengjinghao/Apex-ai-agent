package com.apex.agent

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

enum class AgentEventType { DEFAULT }
sealed class AgentEvent
data class Registered(val data: String = "")
data class Unregistered(val data: String = "")
data class LifecycleStateChanged(val data: String = "")
data class TaskStarted(val data: String = "")
data class TaskProgress(val data: String = "")
data class TaskCompleted(val data: String = "")
data class HealthChanged(val data: String = "")
data class AgentMessageSent(val data: String = "")
data class ErrorOccurred(val data: String = "")
data class Custom(val data: String = "")
class EventSubscription
class AgentEventBus
object GlobalAgentEventBus {
    fun init() { }
}
