package com.apex.agent.core.multiagent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: TaskType,
    val requiredCapabilities: List<String> = emptyList(),
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.PENDING,
    val payload: Any? = null,
    val assignedAgent: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val result: Any? = null,
    val error: String? = null
)




class AgentTaskScheduler(private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {

    companion object {
        private const val TAG = "AgentTaskScheduler"
    }

    private val pendingTasks = PriorityBlockingQueue<AgentTask>(100, compareByDescending { it.priority.ordinal })
    private val activeTasks = ConcurrentLinkedQueue<AgentTask>()
    private val completedTasks = mutableListOf<AgentTask>()
    private val maxHistorySize = 1000

    private val _schedulerState = MutableStateFlow(SchedulerState())
    val schedulerState: StateFlow<SchedulerState> = _schedulerState

    init {
        startSchedulerLoop()
    }

    data class SchedulerState(
        val pendingCount: Int = 0,
        val activeCount: Int = 0,
        val completedCount: Int = 0,
        val throughput: Float = 0f
    )

    fun submitTask(task: AgentTask): Boolean {
        val queuedTask = task.copy(status = TaskStatus.QUEUED)
        val added = pendingTasks.add(queuedTask)
        updateState()
        return added
    }

    fun createSimpleTask(name: String, type: TaskType = TaskType.ANALYSIS, priority: Priority = Priority.MEDIUM, requiredCapabilities: List<String> = emptyList()): String {
        val task = AgentTask(name = name, type = type, priority = priority, requiredCapabilities = requiredCapabilities)
        submitTask(task)
        return task.id
    }

    fun getTask(taskId: String): AgentTask? {
        return pendingTasks.find { it.id == taskId } ?: activeTasks.find { it.id == taskId } ?: completedTasks.find { it.id == taskId }
    }

    fun cancelTask(taskId: String): Boolean {
        val task = pendingTasks.find { it.id == taskId }
        if (task != null) {
            pendingTasks.remove(task)
            updateState()
            return true
        }
        return false
    }

    fun getPendingTasks(): List<AgentTask> = pendingTasks.toList()
    fun getActiveTasks(): List<AgentTask> = activeTasks.toList()
    fun getCompletedTasks(limit: Int = 100): List<AgentTask> = completedTasks.takeLast(limit)

    private fun startSchedulerLoop() {
        scope.launch {
            while (isActive) {
                scheduleNextTask()
                delay(100)
            }
        }
    }

    private suspend fun scheduleNextTask() {
        val task = pendingTasks.poll() ?: return
        activeTasks.add(task.copy(status = TaskStatus.IN_PROGRESS, startedAt = System.currentTimeMillis()))
        updateState()
        executeTask(task)
    }

    private suspend fun executeTask(task: AgentTask) {
        try {
            delay(500 + Random().nextInt(1500).toLong())
            val completed = task.copy(status = TaskStatus.COMPLETED, completedAt = System.currentTimeMillis(), result = "任务执行成功: ${task.name}")
            activeTasks.removeIf { it.id == task.id }
            completedTasks.add(completed)
        } catch (e: Exception) {
            val failed = task.copy(status = TaskStatus.FAILED, completedAt = System.currentTimeMillis(), error = e.message ?: "未知错误")
            activeTasks.removeIf { it.id == task.id }
            completedTasks.add(failed)
        }
        updateState()
    }

    private fun updateState() {
        _schedulerState.value = SchedulerState(pendingCount = pendingTasks.size, activeCount = activeTasks.size, completedCount = completedTasks.size, throughput = calculateThroughput())
    }

    private fun calculateThroughput(): Float {
        val oneMinuteAgo = System.currentTimeMillis() - 60000
        val recent = completedTasks.filter { it.completedAt ?: 0 >= oneMinuteAgo }
        return recent.size / 60f
    }

    fun getStats(): SchedulerStats {
        return SchedulerStats(pendingByPriority = pendingTasks.groupBy { it.priority }.mapValues { it.value.size }, completedByType = completedTasks.groupBy { it.type }.mapValues { it.value.size }, successRate = if (completedTasks.isNotEmpty()) completedTasks.count { it.status == TaskStatus.COMPLETED }.toFloat() / completedTasks.size else 1f, avgExecutionTimeMs = if (completedTasks.isNotEmpty()) completedTasks.mapNotNull { val start = it.startedAt; val end = it.completedAt; if (start != null && end != null) end - start else null }.average().toFloat() else 0f)
    }

    data class SchedulerStats(
        val pendingByPriority: Map<Priority, Int> = emptyMap(),
        val completedByType: Map<TaskType, Int> = emptyMap(),
        val successRate: Float = 1f,
        val avgExecutionTimeMs: Float = 0f
    )

    fun shutdown() {
        scope.cancel()
        pendingTasks.clear()
        activeTasks.clear()
    }
}
