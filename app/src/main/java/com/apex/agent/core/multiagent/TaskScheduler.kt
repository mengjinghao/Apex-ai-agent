package com.apex.agent.core.multiagent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.apex.agent.core.performance.TimeoutException

class TaskScheduler(private val context: Context) {

    private val tasksDir: File
        get() = File(context.filesDir, "scheduler_tasks").also {
            if (!it.exists()) it.mkdirs()
        }
        private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
        val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()
        private val _taskQueue = MutableStateFlow<List<ScheduledTask>>(emptyList())
        val taskQueue: StateFlow<List<ScheduledTask>> = _taskQueue.asStateFlow()
        private val _runningTasks = MutableStateFlow<Set<String>>(emptySet())
        val runningTasks: StateFlow<Set<String>> = _runningTasks.asStateFlow()
        private val taskExecutors = ConcurrentHashMap<String, TaskExecutor>()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val _taskEvents = MutableSharedFlow<TaskEvent>()
        val taskEvents: SharedFlow<TaskEvent> = _taskEvents.asSharedFlow()

    init {
        loadTasks()
    }
        private fun loadTasks() {
        scope.launch {
            val loadedTasks = withContext(Dispatchers.IO) {
                tasksDir.listFiles { _, name -> name.endsWith(".json") }
                    ?.mapNotNull { file ->
                        try {
                            parseTask(JSONObject(file.readText()))
                        } catch (e: Exception) {
                            null
                        }
                    }
                    ?: emptyList()
            }
            _tasks.value = loadedTasks
            updateTaskQueue()
        }
    }

    suspend fun scheduleTask(
        title: String,
        description: String,
        priority: TaskPriority,
        assignedAgentId: String?,
        dependencies: List<String> = emptyList(),
        scheduledTime: Long? = null
    ): Result<ScheduledTask> = withContext(Dispatchers.IO) {
        try {
            val task = ScheduledTask(
                id = UUID.randomUUID().toString(),
                title = title,
                description = description,
                priority = priority,
                status = TaskState.PENDING,
                assignedAgentId = assignedAgentId,
                dependencies = dependencies,
                createdAt = System.currentTimeMillis(),
                scheduledTime = scheduledTime
            )

            saveTask(task)
            _tasks.value = _tasks.value + task
            updateTaskQueue()

            _taskEvents.emit(TaskEvent.TaskScheduled(task))
            Result.success(task)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeTask(taskId: String): Result<TaskResult> = withContext(Dispatchers.Default) {
        try {
            val task = _tasks.value.find { it.id == taskId }
                ?: return@withContext Result.failure(IllegalArgumentException("任务不存�?))
        if (!canExecuteTask(task)) {
                return@withContext Result.failure(IllegalStateException("依赖任务未完�?))
            }

            _runningTasks.value = _runningTasks.value + taskId
            updateTaskState(taskId, TaskState.IN_PROGRESS)

            _taskEvents.emit(TaskEvent.TaskStarted(taskId))
        val startTime = System.currentTimeMillis()

            try {
                val result = executeTaskWithTimeout(task)
        val duration = System.currentTimeMillis() - startTime

                val completedTask = task.copy(
                    status = TaskState.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    duration = duration
                )
                updateTask(completedTask)

                _runningTasks.value = _runningTasks.value - taskId
                _taskEvents.emit(TaskEvent.TaskCompleted(taskId, result))
                updateTaskQueue()

                Result.success(result)
            } catch (e: Exception) {
                val failedTask = task.copy(
                    status = TaskState.FAILED,
                    error = e.message
                )
                updateTask(failedTask)

                _runningTasks.value = _runningTasks.value - taskId
                _taskEvents.emit(TaskEvent.TaskFailed(taskId, e.message ?: "未知错误"))

                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
        private suspend fun executeTaskWithTimeout(task: ScheduledTask): TaskResult {
        val executor = taskExecutors.getOrPut(task.id) {
            TaskExecutor(task, scope)
        }
        return withTimeoutOrNull(task.timeoutSeconds * 1000L) {
            executor.execute()
        } ?: throw TimeoutException("任务执行超时")
    }

    suspend fun cancelTask(taskId: String): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            taskExecutors[taskId]?.cancel()
            taskExecutors.remove(taskId)

            _runningTasks.value = _runningTasks.value - taskId

            val task = _tasks.value.find { it.id == taskId }
        if (task != null) {
                val cancelledTask = task.copy(status = TaskState.CANCELLED)
                updateTask(cancelledTask)
            }

            _taskEvents.emit(TaskEvent.TaskCancelled(taskId))
            updateTaskQueue()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reassignTask(taskId: String, newAgentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val task = _tasks.value.find { it.id == taskId }
                ?: return@withContext Result.failure(IllegalArgumentException("任务不存�?))
        val updatedTask = task.copy(assignedAgentId = newAgentId)
            updateTask(updatedTask)

            _taskEvents.emit(TaskEvent.TaskReassigned(taskId, newAgentId))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun retryTask(taskId: String): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            val task = _tasks.value.find { it.id == taskId }
                ?: return@withContext Result.failure(IllegalArgumentException("任务不存�?))
        val retriedTask = task.copy(
                status = TaskState.PENDING,
                retryCount = task.retryCount + 1,
                error = null
            )
            updateTask(retriedTask)

            _taskEvents.emit(TaskEvent.TaskRetried(taskId))
            updateTaskQueue()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
        private fun canExecuteTask(task: ScheduledTask): Boolean {
        if (task.dependencies.isEmpty()) return true

        return task.dependencies.all { depId ->
            val depTask = _tasks.value.find { it.id == depId }
            depTask?.status == TaskState.COMPLETED
        }
    }
        private fun updateTaskQueue() {
        val pendingTasks = _tasks.value
            .filter { it.status == TaskState.PENDING && canExecuteTask(it) }
            .sortedWith(compareBy({ -it.priority.ordinal }, { it.createdAt }))

        _taskQueue.value = pendingTasks
    }
        private suspend fun updateTaskState(taskId: String, state: TaskState) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        val updatedTask = task.copy(status = state)
        updateTask(updatedTask)
    }
        private suspend fun updateTask(task: ScheduledTask) {
        _tasks.value = _tasks.value.map { 
            if (it.id == task.id) task else it 
        }
        saveTask(task)
    }
        private suspend fun saveTask(task: ScheduledTask) = withContext(Dispatchers.IO) {
        val taskFile = File(tasksDir, "${task.id}.json")
        val json = createTaskJson(task)
        taskFile.writeText(json.toString(2))
    }
        private fun createTaskJson(task: ScheduledTask): JSONObject {
        return JSONObject().apply {
            put("id", task.id)
            put("title", task.title)
            put("description", task.description)
            put("priority", task.priority.name)
            put("status", task.status.name)
            put("assignedAgentId", task.assignedAgentId ?: JSONObject.NULL)
            put("dependencies", JSONArray(task.dependencies))
            put("createdAt", task.createdAt)
            put("scheduledTime", task.scheduledTime ?: JSONObject.NULL)
            put("startedAt", task.startedAt ?: JSONObject.NULL)
            put("completedAt", task.completedAt ?: JSONObject.NULL)
            put("duration", task.duration ?: JSONObject.NULL)
            put("error", task.error ?: JSONObject.NULL)
            put("retryCount", task.retryCount)
            put("timeoutSeconds", task.timeoutSeconds)
            put("progress", task.progress)
        }
    }
        private fun parseTask(json: JSONObject): ScheduledTask {
        return ScheduledTask(
            id = json.getString("id"),
            title = json.getString("title"),
            description = json.getString("description"),
            priority = TaskPriority.valueOf(json.getString("priority")),
            status = TaskState.valueOf(json.getString("status")),
            assignedAgentId = if (json.isNull("assignedAgentId")) null else json.getString("assignedAgentId"),
            dependencies = (0 until json.getJSONArray("dependencies").length())
                .map { json.getJSONArray("dependencies").getString(it) },
            createdAt = json.getLong("createdAt"),
            scheduledTime = if (json.isNull("scheduledTime")) null else json.getLong("scheduledTime"),
            startedAt = if (json.isNull("startedAt")) null else json.getLong("startedAt"),
            completedAt = if (json.isNull("completedAt")) null else json.getLong("completedAt"),
            duration = if (json.isNull("duration")) null else json.getLong("duration"),
            error = if (json.isNull("error")) null else json.getString("error"),
            retryCount = json.optInt("retryCount", 0),
            timeoutSeconds = json.optInt("timeoutSeconds", 300),
            progress = json.optDouble("progress", 0.0).toFloat()
        )
    }
        fun cleanup() {
        scope.cancel()
        taskExecutors.values.forEach { it.cancel() }
        taskExecutors.clear()
    }
}

data class ScheduledTask(
    val id: String,
    val title: String,
    val description: String,
    val priority: TaskPriority,
    val status: TaskState,
    val assignedAgentId: String?,
    val dependencies: List<String> = emptyList(),
    val createdAt: Long,
    val scheduledTime: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val duration: Long? = null,
    val error: String? = null,
    val retryCount: Int = 0,
    val timeoutSeconds: Int = 300,
    val progress: Float = 0f
)

enum class TaskPriority {
    CRITICAL, HIGH, MEDIUM, LOW
}

enum class TaskState {
    PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, BLOCKED
}

data class TaskResult(
    val taskId: String,
    val success: Boolean,
    val output: String,
    val metadata: Map<String, Any> = emptyMap()
)

class TaskExecutor(
    private val task: ScheduledTask,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    suspend fun execute(): TaskResult = coroutineScope {
        val result = async {
            delay((1000..3000).random().toLong())
        if (task.title.contains("模拟失败", ignoreCase = true)) {
                throw Exception("模拟任务失败")
            }

            TaskResult(
                taskId = task.id,
                success = true,
                output = "任务执行成功",
                metadata = mapOf(
                    "executedBy" to "Agent",
                    "duration" to (500..2000).random()
                )
            )
        }.await()

        result
    }
        fun cancel() {
        job?.cancel()
    }
}

sealed class TaskEvent {
    data class TaskScheduled(val task: ScheduledTask) : TaskEvent()
    data class TaskStarted(val taskId: String) : TaskEvent()
    data class TaskCompleted(val taskId: String, val result: TaskResult) : TaskEvent()
    data class TaskFailed(val taskId: String, val error: String) : TaskEvent()
    data class TaskCancelled(val taskId: String) : TaskEvent()
    data class TaskRetried(val taskId: String) : TaskEvent()
    data class TaskReassigned(val taskId: String, val newAgentId: String) : TaskEvent()
}
