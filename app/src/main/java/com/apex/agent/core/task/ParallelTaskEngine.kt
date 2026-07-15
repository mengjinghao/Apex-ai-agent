package com.apex.agent.core.task

// STUBBED
sealed class TaskStatus
object Paused
data class Task(val placeholder: String = "")
data class TaskProgress(val placeholder: String = "")
data class TaskExecutionResult(val placeholder: String = "")
class ParallelTaskEngine
data class EngineStats(val placeholder: String = "")
