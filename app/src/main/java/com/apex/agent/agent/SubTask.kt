package com.apex.agent

data class SubTask(
    val taskId: String,
    val taskType: String,
    val description: String,
    val inputData: Map<String, Any> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val priority: Int = 0,
    val estimatedTime: Long = 0
)

data class SubTaskResult(
    val taskId: String,
    val success: Boolean,
    val outputData: Map<String, Any> = emptyMap(),
    val executionTime: Long,
    val errorMessage: String? = null,
    val errorStack: String? = null
)

data class MainTask(
    val taskId: String,
    val taskType: String,
    val description: String,
    val inputData: Map<String, Any> = emptyMap(),
    val priority: Int = 0
)

data class TaskResult(
    val success: Boolean,
    val subtaskResults: List<SubTaskResult>,
    val totalExecutionTime: Long,
    val taskId: String = ""
)

sealed class TaskState {
    object Idle : TaskState()
    object Decomposing : TaskState()
    data class Executing(val completed: Int, val total: Int) : TaskState()
    data class Failed(val error: String) : TaskState()
    object Completed : TaskState()
}

interface SubtaskDecompositionStrategy {
    fun decompose(mainTask: MainTask): List<SubTask>
}
