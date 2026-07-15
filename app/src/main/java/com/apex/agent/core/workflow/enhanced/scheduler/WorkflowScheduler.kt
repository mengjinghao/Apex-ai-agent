package com.apex.agent.core.workflow.enhanced.scheduler

// STUBBED: had 5 errors
data class ScheduledJob(val placeholder: String = "")
enum class MisfirePolicy { DEFAULT }
sealed class ScheduleEvent
data class JobRegistered(val placeholder: String = "")
data class JobUnregistered(val placeholder: String = "")
data class JobTriggered(val placeholder: String = "")
data class JobSucceeded(val placeholder: String = "")
data class JobFailed(val placeholder: String = "")
data class JobMisfired(val placeholder: String = "")
data class JobPaused(val placeholder: String = "")
data class JobResumed(val placeholder: String = "")
interface SchedulePersistor
class InMemorySchedulePersistor
