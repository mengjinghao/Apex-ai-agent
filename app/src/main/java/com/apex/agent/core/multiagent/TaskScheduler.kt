package com.apex.agent.core.multiagent

// Minimal implementation (original had 66 errors)
// TODO: Restore full implementation from original code

data class ScheduledTask(val data: String = "")
sealed class TaskEvent
data class TaskScheduled(val data: String = "")
data class TaskFailed(val data: String = "")
data class TaskCancelled(val data: String = "")
data class TaskRetried(val data: String = "")
data class TaskReassigned(val data: String = "")
