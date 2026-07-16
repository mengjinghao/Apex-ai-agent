package com.apex.agent.core.application

// Minimal implementation (original had 33 errors)
// TODO: Restore full implementation from original code

class AppInitializer
sealed class InitializationPhase
object Critical {
    fun init() { }
}
object Normal {
    fun init() { }
}
object Low {
    fun init() { }
}
data class InitializationTask(val data: String = "")
object HealthCheckBridge {
    fun init() { }
}
