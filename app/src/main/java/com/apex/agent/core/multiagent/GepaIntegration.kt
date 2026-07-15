package com.apex.agent.core.multiagent

// STUBBED: had 22 errors
class GepaIntegration
sealed class GepaState
object Analyzing
object Matching
object UsingDefaultStrategy
data class ReadyToExecute(val placeholder: String = "")
object Executing
data class ExtractionComplete(val placeholder: String = "")
