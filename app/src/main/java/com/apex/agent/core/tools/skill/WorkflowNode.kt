package com.apex.agent.core.tools.skill

// STUBBED: had 5 errors
data class NodePosition(val placeholder: String = "")
data class NodeConfig(val placeholder: String = "")
data class TriggerConfig(val placeholder: String = "")
enum class TriggerType { DEFAULT }
data class ScheduleConfig(val placeholder: String = "")
data class TaskerConfig(val placeholder: String = "")
data class IntentConfig(val placeholder: String = "")
data class SpeechConfig(val placeholder: String = "")
enum class ExtractMode { DEFAULT }
sealed class ParameterValue
data class StaticValue(val placeholder: String = "")
data class NodeReference(val placeholder: String = "")
data class WorkflowConnection(val placeholder: String = "")
enum class ConnectionCondition { DEFAULT }
data class WorkflowDefinition(val placeholder: String = "")
data class WorkflowValidationResult(val placeholder: String = "")
