package com.ai.assistance.aiterminal.terminal.agent.task

enum class TaskStatus { DEFAULT }

enum class StepStatus { DEFAULT }

enum class FailureStrategy { DEFAULT }

data class ValidationRule(val placeholder: String = "")

enum class ValidationType { DEFAULT }

data class FailureHandler(val placeholder: String = "")

data class TaskStep(val placeholder: String = "")

data class TaskPlan(val placeholder: String = "")

data class StepExecutionResult(val placeholder: String = "")

data class TaskExecutionResult(val placeholder: String = "")

data class TaskSnapshot(val placeholder: String = "")

data class ErrorAnalysis(val placeholder: String = "")

enum class ErrorType { DEFAULT }

data class ScheduledTaskConfig(val placeholder: String = "")

enum class TriggerType { DEFAULT }

data class TaskNotificationConfig(val placeholder: String = "")
