
package com.apex.agent.core.task

import com.apex.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class TaskStatus {
    object Pending : TaskStatus()
    object Running : TaskStatus()
    object Paused : TaskStatus()
    object Cancelled : TaskStatus()
    data class Completed(val result: Any) : TaskStatus()
    data class Failed(val error: Throwable) : TaskStatus()
}

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val dependencies: List&lt;String&gt; = emptyList(),
    val timeout: Long? = null,
    val executor: suspend () -&gt; Any?
)

enum class TaskPriority {
    LOW, NORMAL, HIGH, URGENT
}

data class TaskProgress(
    val taskId: String,
    val progress: Float = 0f,
    val message: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 1
)

data class TaskExecutionResult(
    val taskId: String,
    val status: TaskStatus,
    val executionTime: Long,
    val result: Any? = null,
    val error: Throwable? = null
)

class ParallelTaskEngine(
    private val maxConcurrentTasks: Int = 4,
    private val defaultScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _tasks = MutableStateFlow&lt;Map&lt;String, Task&gt;&gt;(emptyMap())
    val tasks: StateFlow&lt;Map&lt;String, Task&gt;&gt; = _tasks.asStateFlow()

    private val _taskStatus = MutableStateFlow&lt;Map&lt;String, TaskStatus&gt;&gt;(emptyMap())
    val taskStatus: StateFlow&lt;Map&lt;String, TaskStatus&gt;&gt; = _taskStatus.asStateFlow()

    private val _taskProgress = MutableStateFlow&lt;Map&lt;String, TaskProgress&gt;&gt;(emptyMap())
    val taskProgress: StateFlow&lt;Map&lt;String, TaskProgress&gt;&gt; = _taskProgress.asStateFlow()

    private val _executionResults = MutableStateFlow&lt;List&lt;TaskExecutionResult&gt;&gt;(emptyList())
    val executionResults: StateFlow&lt;List&lt;TaskExecutionResult&gt;&gt; = _executionResults.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow&lt;Boolean&gt; = _isEnabled.asStateFlow()

    private val activeTasks = AtomicInteger(0)
    private val taskJobs = ConcurrentHashMap&lt;String, Job&gt;()
    private val executionHistory = mutableListOf&lt;TaskExecutionResult&gt;()

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    fun addTask(task: Task): String {
        _tasks.update { it + (task.id to task) }
        _taskStatus.update { it + (task.id to TaskStatus.Pending) }
        _taskProgress.update { it + (task.id to TaskProgress(taskId = task.id)) }
        return task.id
    }

    fun addTasks(tasks: List&lt;Task&gt;): List&lt;String&gt; {
        return tasks.map { addTask(it) }
    }

    fun removeTask(taskId: String) {
        cancelTask(taskId)
        _tasks.update { it - taskId }
        _taskStatus.update { it - taskId }
        _taskProgress.update { it - taskId }
    }

    fun clearTasks() {
        _tasks.value.keys.forEach { cancelTask(it) }
        _tasks.value = emptyMap()
        _taskStatus.value = emptyMap()
        _taskProgress.value = emptyMap()
    }

    suspend fun executeAll(): List&lt;TaskExecutionResult&gt; = coroutineScope {
        if (!_isEnabled.value) {
            AppLogger.w("ParallelTaskEngine", "Task engine is disabled, executing sequentially")
            return@coroutineScope executeSequential()
        }

        val tasksToExecute = _tasks.value.values.toList()
        val results = mutableListOf&lt;TaskExecutionResult&gt;()

        val dependencyGraph = buildDependencyGraph(tasksToExecute)
        val executionOrder = topologicalSort(dependencyGraph)

        val sortedTasks = executionOrder.mapNotNull { taskId -&gt;
            tasksToExecute.find { it.id == taskId }
        }

        val priorityGroups = sortedTasks.groupBy { it.priority }
        val orderedByPriority = listOf(
            priorityGroups[TaskPriority.URGENT],
            priorityGroups[TaskPriority.HIGH],
            priorityGroups[TaskPriority.NORMAL],
            priorityGroups[TaskPriority.LOW]
        ).flatten().filterNotNull()

        val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrentTasks)

        orderedByPriority.map { task -&gt;
            async {
                semaphore.withPermit {
                    executeSingleTask(task)
                }
            }
        }.awaitAll().forEach { result -&gt;
            results.add(result)
            _executionResults.update { it + result }
        }

        return@coroutineScope results
    }

    private suspend fun executeSequential(): List&lt;TaskExecutionResult&gt; = coroutineScope {
        val tasksToExecute = _tasks.value.values.toList()
        val results = mutableListOf&lt;TaskExecutionResult&gt;()

        for (task in tasksToExecute) {
            val result = executeSingleTask(task)
            results.add(result)
            _executionResults.update { it + result }
        }

        return@coroutineScope results
    }

    fun startTask(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        val currentStatus = _taskStatus.value[taskId]

        if (currentStatus != TaskStatus.Pending &amp;&amp; currentStatus != TaskStatus.Paused) {
            return
        }

        defaultScope.launch {
            executeSingleTask(task)
        }
    }

    fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
        taskJobs.remove(taskId)
        _taskStatus.update { it + (taskId to TaskStatus.Cancelled) }
    }

    fun pauseTask(taskId: String) {
        val currentStatus = _taskStatus.value[taskId]
        if (currentStatus == TaskStatus.Running) {
            _taskStatus.update { it + (taskId to TaskStatus.Paused) }
        }
    }

    fun updateProgress(taskId: String, progress: Float, message: String = "", currentStep: Int = 0, totalSteps: Int = 1) {
        _taskProgress.update {
            it + (taskId to TaskProgress(taskId, progress, message, currentStep, totalSteps))
        }
    }

    fun getTaskStatus(taskId: String): TaskStatus? {
        return _taskStatus.value[taskId]
    }

    fun getTaskProgress(taskId: String): TaskProgress? {
        return _taskProgress.value[taskId]
    }

    fun getExecutionHistory(): List&lt;TaskExecutionResult&gt; {
        return executionHistory.toList()
    }

    fun clearExecutionHistory() {
        executionHistory.clear()
        _executionResults.value = emptyList()
    }

    private suspend fun executeSingleTask(task: Task): TaskExecutionResult {
        val startTime = System.currentTimeMillis()

        _taskStatus.update { it + (task.id to TaskStatus.Running) }
        activeTasks.incrementAndGet()

        val job = defaultScope.launch {
            try {
                if (task.timeout != null) {
                    withTimeout(task.timeout) {
                        val result = task.executor()
                        _taskStatus.update { it + (task.id to TaskStatus.Completed(result)) }
                        TaskExecutionResult(
                            taskId = task.id,
                            status = TaskStatus.Completed(result),
                            executionTime = System.currentTimeMillis() - startTime,
                            result = result
                        )
                    }
                } else {
                    val result = task.executor()
                    _taskStatus.update { it + (task.id to TaskStatus.Completed(result)) }
                    TaskExecutionResult(
                        taskId = task.id,
                        status = TaskStatus.Completed(result),
                        executionTime = System.currentTimeMillis() - startTime,
                        result = result
                    )
                }
            } catch (e: CancellationException) {
                _taskStatus.update { it + (task.id to TaskStatus.Cancelled) }
                TaskExecutionResult(
                    taskId = task.id,
                    status = TaskStatus.Cancelled,
                    executionTime = System.currentTimeMillis() - startTime
                )
            } catch (e: Throwable) {
                _taskStatus.update { it + (task.id to TaskStatus.Failed(e)) }
                AppLogger.e("ParallelTaskEngine", "Task ${task.name} failed", e)
                TaskExecutionResult(
                    taskId = task.id,
                    status = TaskStatus.Failed(e),
                    executionTime = System.currentTimeMillis() - startTime,
                    error = e
                )
            } finally {
                activeTasks.decrementAndGet()
                taskJobs.remove(task.id)
            }
        }

        taskJobs[task.id] = job
        return job.join().let {
            val finalStatus = _taskStatus.value[task.id] ?: TaskStatus.Failed(Exception("Unknown status"))
            val result = _executionResults.value.findLast { it.taskId == task.id }
            result ?: TaskExecutionResult(
                taskId = task.id,
                status = finalStatus,
                executionTime = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun buildDependencyGraph(tasks: List&lt;Task&gt;): Map&lt;String, List&lt;String&gt;&gt; {
        val graph = mutableMapOf&lt;String, MutableList&lt;String&gt;&gt;()

        tasks.forEach { task -&gt;
            graph[task.id] = task.dependencies.toMutableList()
        }

        return graph
    }

    private fun topologicalSort(graph: Map&lt;String, List&lt;String&gt;&gt;): List&lt;String&gt; {
        val inDegree = mutableMapOf&lt;String, Int&gt;()
        graph.keys.forEach { inDegree[it] = 0 }

        graph.values.forEach { dependencies -&gt;
            dependencies.forEach { dependency -&gt;
                inDegree[dependency] = (inDegree[dependency] ?: 0) + 1
            }
        }

        val queue = LinkedList&lt;String&gt;()
        inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

        val result = mutableListOf&lt;String&gt;()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)

            graph.forEach { (taskId, dependencies) -&gt;
                if (dependencies.contains(node)) {
                    inDegree[taskId] = (inDegree[taskId] ?: 0) - 1
                    if (inDegree[taskId] == 0) {
                        queue.add(taskId)
                    }
                }
            }
        }

        if (result.size != graph.size) {
            AppLogger.w("ParallelTaskEngine", "Circular dependency detected in task graph")
        }

        return result.reversed()
    }

    fun getStats(): EngineStats {
        val statusCounts = _taskStatus.value.values.groupBy { it }.mapValues { it.value.size }
        val completedCount = executionResults.value.count {
            it.status is TaskStatus.Completed || it.status is TaskStatus.Failed
        }
        val successCount = executionResults.value.count { it.status is TaskStatus.Completed }

        return EngineStats(
            totalTasks = _tasks.value.size,
            activeTasks = activeTasks.get(),
            completedTasks = completedCount,
            successRate = if (completedCount &gt; 0) successCount.toFloat() / completedCount else 0f,
            statusCounts = statusCounts,
            isEnabled = _isEnabled.value
        )
    }

    companion object {
        @Volatile
        private var instance: ParallelTaskEngine? = null

        fun getInstance(maxConcurrentTasks: Int = 4): ParallelTaskEngine {
            return instance ?: synchronized(this) {
                instance ?: ParallelTaskEngine(maxConcurrentTasks).also { instance = it }
            }
        }
    }
}

data class EngineStats(
    val totalTasks: Int,
    val activeTasks: Int,
    val completedTasks: Int,
    val successRate: Float,
    val statusCounts: Map&lt;TaskStatus, Int&gt;,
    val isEnabled: Boolean
)
