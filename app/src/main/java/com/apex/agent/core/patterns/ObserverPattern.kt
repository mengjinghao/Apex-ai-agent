package com.apex.agent.core.patterns

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

class Subscription
interface Observable
class ObservableImpl
object Initialized {
    fun init() { }
}
data class MetricsUpdated(val data: String = "")
object Shutdown {
    fun init() { }
}
interface AgentStateObserver
class AgentStateObservable
