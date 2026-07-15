package com.apex.agent.core.multiagent

// STUBBED: had 66 errors
class TaskScheduler
sealed class TaskEvent
data class TaskScheduled(val placeholder: String = "")
data class TaskFailed(val placeholder: String = "")
data class TaskCancelled(val placeholder: String = "")
data class TaskRetried(val placeholder: String = "")
data class TaskReassigned(val placeholder: String = "")
