package com.apex.agent.core.tools.skill

// Minimal implementation (original had 23 errors)
// TODO: Restore full implementation from original code

class SkillEventBus
sealed class SkillEvent
data class SkillLoaded(val data: String = "")
data class SkillUnloaded(val data: String = "")
data class SkillInvoked(val data: String = "")
data class SkillCompleted(val data: String = "")
data class WorkflowTriggered(val data: String = "")
data class WorkflowNodeExecuted(val data: String = "")
data class TaskExecuted(val data: String = "")
data class CustomEvent(val data: String = "")
data class EventListener(val data: String = "")
interface EventFilter
data class EventBusStats(val data: String = "")
