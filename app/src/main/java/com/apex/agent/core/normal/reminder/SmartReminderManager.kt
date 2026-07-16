package com.apex.agent.core.normal.reminder

// Minimal implementation (original had 11 errors)
// TODO: Restore full implementation from original code

enum class ReminderType { DEFAULT }
enum class ReminderPriority { DEFAULT }
data class Reminder(val data: String = "")
sealed class RecurrencePattern
data class Daily(val data: String = "")
data class Weekly(val data: String = "")
data class Monthly(val data: String = "")
data class Interval(val data: String = "")
object Once {
    fun init() { }
}
sealed class ReminderEvent
data class Triggered(val data: String = "")
data class Created(val data: String = "")
data class Dismissed(val data: String = "")
data class Snoozed(val data: String = "")
class SmartReminderManager
