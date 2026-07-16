package com.apex.agent

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

sealed class TaskExecutionState
object Pending {
    fun init() { }
}
data class Running(val data: String = "")
data class Progress(val data: String = "")
data class Completed(val data: String = "")
data class TaskExecutionConfig(val data: String = "")
class AgentTaskExecutor
data class TaskHandle(val data: String = "")
