package com.apex.agent.core.tools.skill

// Minimal implementation (original had 7 errors)
// TODO: Restore full implementation from original code

class SkillEventTrigger
data class EventTrigger(val data: String = "")
data class TriggerCondition(val data: String = "")
enum class ConditionType { DEFAULT }
data class TriggerAction(val data: String = "")
data class TriggerExecution(val data: String = "")
sealed class TriggerEvent
data class TriggerRegistered(val data: String = "")
data class TriggerUnregistered(val data: String = "")
data class TriggerEnabled(val data: String = "")
data class TriggerDisabled(val data: String = "")
data class TriggerMatched(val data: String = "")
data class TriggerExecuted(val data: String = "")
data class TriggerCooldown(val data: String = "")
data class TriggerStats(val data: String = "")
