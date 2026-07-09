package com.apex.agent.kernel.burst.enhanced.eventbus

class EnhancedSkillEventBus

class BurstEvent

data class SkillLoaded(val placeholder: String = "")

data class SkillUnloaded(val placeholder: String = "")

data class SkillInitialized(val placeholder: String = "")

data class TaskStarted(val placeholder: String = "")

data class TaskProgress(val placeholder: String = "")

data class TaskSucceeded(val placeholder: String = "")

data class TaskFailed(val placeholder: String = "")

data class TaskCancelled(val placeholder: String = "")

data class TaskRetrying(val placeholder: String = "")

data class TaskPaused(val placeholder: String = "")

data class TaskResumed(val placeholder: String = "")

data class ResourceWarning(val placeholder: String = "")

data class ResourceCritical(val placeholder: String = "")

data class StateChanged(val placeholder: String = "")

data class ConfigChanged(val placeholder: String = "")

data class HealthCheckPassed(val placeholder: String = "")

data class HealthCheckFailed(val placeholder: String = "")

data class Custom(val placeholder: String = "")

enum class EventPriority { DEFAULT }

data class DeadLetterEntry(val placeholder: String = "")

data class EventBusStats(val placeholder: String = "")
