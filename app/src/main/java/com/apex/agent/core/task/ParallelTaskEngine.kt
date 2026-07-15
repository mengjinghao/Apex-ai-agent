package com.apex.agent.core.task

// STUBBED
sealed class TaskStatus
object Pending
object Running
object Paused
object Cancelled
data class Completed(val placeholder: String = "")
data class Failed(val placeholder: String = "")
data class Task(val placeholder: String = "")
enum class TaskPriority { DEFAULT }
data class TaskProgress(val placeholder: String = "")
data class TaskExecutionResult(val placeholder: String = "")
class ParallelTaskEngine
data class EngineStats(val placeholder: String = "")
