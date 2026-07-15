package com.apex.agent.core.application

// STUBBED
class AppInitializer
sealed class InitializationPhase
object Critical
object Normal
object Low
data class InitializationTask(val placeholder: String = "")
object HealthCheckBridge
