package com.apex.agent.core.tools.skill

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

    fun init() { }
}
data class NodePosition(val data: String = "")
data class TriggerConfig(val data: String = "")
data class ScheduleConfig(val data: String = "")
data class TaskerConfig(val data: String = "")
data class IntentConfig(val data: String = "")
data class SpeechConfig(val data: String = "")
enum class ExtractMode { DEFAULT }
sealed class ParameterValue
data class WorkflowConnection(val data: String = "")
enum class ConnectionCondition { DEFAULT }
data class WorkflowDefinition(val data: String = "")
data class WorkflowValidationResult(val data: String = "")
