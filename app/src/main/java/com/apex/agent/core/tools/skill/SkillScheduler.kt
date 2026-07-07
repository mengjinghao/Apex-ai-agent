package com.apex.agent.core.tools.skill

import com.apex.agent.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SkillScheduler private constructor() {

    companion object {
        private const val TAG = "SkillScheduler"
        private const val MAX_CONCURRENT_TASKS = 20
        private const val MAX_TASK_HISTORY = 500
        private const val CRON_SECOND = 0

        @Volatile private var INSTANCE: SkillScheduler? = null

        fun getInstance(): SkillScheduler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillScheduler().also { INSTANCE = it }
            }
        }
    }

    @Serializable
    data class ScheduledTask(
        val id: String = generateTaskId(),
        val name: String,
        val description: String = "",
        val targetWorkflowId: String? = null,
        val targetSkillName: String? = null,
        val targetAction: String? = null,
        val scheduleType: ScheduleType,
        val scheduleConfig: TaskScheduleConfig,
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis(),
        val lastExecutionTime: Long = 0,
        val nextExecutionTime: Long = 0,
        val executionCount: Long = 0,
        val successCount: Long = 0,
        val failureCount: Long = 0
    )

    @Serializable
    data class TaskScheduleConfig(
        val intervalMs: Long? = null,
        val specificTime: String? = null,
        val cronExpression: String? = null,
        val repeat: Boolean = true,
        val maxExecutions: Long? = null
    )

    enum class ScheduleType {
        INTERVAL,
        SPECIFIC_TIME,
        CRON,
        ONE_TIME
    }

    data class TaskExecution(
        val taskId: String,
        val taskName: String,
        val executionId: String,
        val startTime: Long,
        val endTime: Long = 0,
        val success: Boolean = false,
        val result: Any? = null,
        val error: String? = null
    )

    sealed class SchedulerEvent {
        data class TaskScheduled(val task: ScheduledTask) : SchedulerEvent()
        data class TaskUnscheduled(val taskId: String) : SchedulerEvent()
        data class TaskEnabled(val taskId: String) : SchedulerEvent()
        data class TaskDisabled(val taskId: String) : SchedulerEvent()
        data class TaskExecutionStarted(val taskId: String, val executionId: String) : SchedulerEvent()
        data class TaskExecutionCompleted(val taskId: String, val executionId: String, val success: Boolean) : SchedulerEvent()
        data class TaskExecutionFailed(val taskId: String, val executionId: String, val error: String) : SchedulerEvent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val tasks = ConcurrentHashMap<String, ScheduledTask>()
    private val runningTasks = ConcurrentHashMap<String, Job>()
    private val taskExecutionHistory = ConcurrentHashMap<String, MutableList<TaskExecution>>()

    private val _tasksFlow = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasksFlow: StateFlow<List<ScheduledTask>> = _tasksFlow.asStateFlow()

    private val _schedulerEvents = MutableSharedFlow<SchedulerEvent>()
    val schedulerEvents: SharedFlow<SchedulerEvent> = _schedulerEvents.asSharedFlow()

    private val _runningTasksCount = MutableStateFlow(0)
    val runningTasksCount: StateFlow<Int> = _runningTasksCount.asStateFlow()

    private val eventBus = SkillEventBus.getInstance()
    private val workflowEngine = WorkflowEngine.getInstance()

    private val json = Json { encodeDefaults = true }

    private val statsTotalScheduled = AtomicLong(0)
    private val statsTotalExecuted = AtomicLong(0)
    private val statsTotalSuccess = AtomicLong(0)
    private val statsTotalFailure = AtomicLong(0)

    fun scheduleTask(
        name: String,
        description: String = "",
        targetWorkflowId: String? = null,
        targetSkillName: String? = null,
        targetAction: String? = null,
        scheduleType: ScheduleType,
        scheduleConfig: TaskScheduleConfig
    ): ScheduledTask? {
        if (tasks.size >= MAX_CONCURRENT_TASKS) {
            AppLogger.w(TAG, "Max scheduled tasks limit reached")
            return null
        }

        val task = ScheduledTask(
            name = name,
            description = description,
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            targetAction = targetAction,
            scheduleType = scheduleType,
            scheduleConfig = scheduleConfig,
            nextExecutionTime = calculateNextExecutionTime(scheduleType, scheduleConfig)
        )

        tasks[task.id] = task
        statsTotalScheduled.incrementAndGet()

        if (task.enabled) {
            startTaskExecution(task)
        }

        updateTasksFlow()

        scope.launch {
            _schedulerEvents.emit(SchedulerEvent.TaskScheduled(task))
            eventBus.emit(SkillEventBus.SkillEvent.TaskScheduled(
                source = TAG,
                taskId = task.id,
                taskName = task.name,
                scheduleType = scheduleType.name,
                nextExecutionTime = task.nextExecutionTime
            ))
        }

        AppLogger.i(TAG, "Task scheduled: ${task.name} [${task.id}], next execution: ${task.nextExecutionTime}")
        return task
    }

    fun scheduleIntervalTask(
        name: String,
        intervalMs: Long,
        repeat: Boolean = true,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null,
        targetAction: String? = null
    ): ScheduledTask? {
        return scheduleTask(
            name = name,
            scheduleType = ScheduleType.INTERVAL,
            scheduleConfig = TaskScheduleConfig(intervalMs = intervalMs, repeat = repeat),
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            targetAction = targetAction
        )
    }

    fun scheduleCronTask(
        name: String,
        cronExpression: String,
        repeat: Boolean = true,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null,
        targetAction: String? = null
    ): ScheduledTask? {
        return scheduleTask(
            name = name,
            scheduleType = ScheduleType.CRON,
            scheduleConfig = TaskScheduleConfig(cronExpression = cronExpression, repeat = repeat),
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            targetAction = targetAction
        )
    }

    fun scheduleOneTimeTask(
        name: String,
        specificTime: String,
        targetWorkflowId: String? = null,
        targetSkillName: String? = null,
        targetAction: String? = null
    ): ScheduledTask? {
        return scheduleTask(
            name = name,
            scheduleType = ScheduleType.ONE_TIME,
            scheduleConfig = TaskScheduleConfig(specificTime = specificTime, repeat = false),
            targetWorkflowId = targetWorkflowId,
            targetSkillName = targetSkillName,
            targetAction = targetAction
        )
    }

    fun unscheduleTask(taskId: String): Boolean {
        val task = tasks.remove(taskId) ?: return false

        runningTasks[taskId]?.cancel()
        runningTasks.remove(taskId)

        updateTasksFlow()

        scope.launch {
            _schedulerEvents.emit(SchedulerEvent.TaskUnscheduled(taskId))
        }

        AppLogger.i(TAG, "Task unscheduled: ${task.name} [${taskId}]")
        return true
    }

    fun updateTask(taskId: String, updates: (TaskScheduleConfig) -> TaskScheduleConfig): ScheduledTask? {
        val task = tasks[taskId] ?: return null

        val updatedConfig = updates(task.scheduleConfig)
        val updatedTask = task.copy(
            scheduleConfig = updatedConfig,
            nextExecutionTime = calculateNextExecutionTime(task.scheduleType, updatedConfig)
        )

        tasks[taskId] = updatedTask

        if (!task.enabled && updatedTask.enabled) {
            startTaskExecution(updatedTask)
        } else if (task.enabled && !updatedTask.enabled) {
            runningTasks[taskId]?.cancel()
            runningTasks.remove(taskId)
        }

        updateTasksFlow()

        AppLogger.d(TAG, "Task updated: ${updatedTask.name} [${updatedTask.id}]")
        return updatedTask
    }

    fun enableTask(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        if (task.enabled) return true

        val updatedTask = task.copy(
            enabled = true,
            nextExecutionTime = calculateNextExecutionTime(task.scheduleType, task.scheduleConfig)
        )
        tasks[taskId] = updatedTask

        startTaskExecution(updatedTask)
        updateTasksFlow()

        scope.launch {
            _schedulerEvents.emit(SchedulerEvent.TaskEnabled(taskId))
        }

        AppLogger.i(TAG, "Task enabled: ${task.name} [${taskId}]")
        return true
    }

    fun disableTask(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        if (!task.enabled) return true

        val updatedTask = task.copy(enabled = false)
        tasks[taskId] = updatedTask

        runningTasks[taskId]?.cancel()
        runningTasks.remove(taskId)

        updateTasksFlow()

        scope.launch {
            _schedulerEvents.emit(SchedulerEvent.TaskDisabled(taskId))
        }

        AppLogger.i(TAG, "Task disabled: ${task.name} [${taskId}]")
        return true
    }

    fun getTask(taskId: String): ScheduledTask? = tasks[taskId]

    fun getAllTasks(): List<ScheduledTask> = tasks.values.toList()

    fun getEnabledTasks(): List<ScheduledTask> = tasks.values.filter { it.enabled }

    fun getTaskExecutionHistory(taskId: String): List<TaskExecution> =
        taskExecutionHistory[taskId]?.toList() ?: emptyList()

    private fun startTaskExecution(task: ScheduledTask) {
        if (runningTasks.containsKey(task.id)) return

        val job = scope.launch {
            while (isActive && task.enabled) {
                val now = System.currentTimeMillis()

                if (now >= task.nextExecutionTime) {
                    executeTask(task)
                }

                if (!task.scheduleConfig.repeat) {
                    break
                }

                val delayTime = calculateDelayUntilNextExecution(task)
                if (delayTime > 0) {
                    delay(delayTime)
                } else {
                    delay(1000)
                }
            }
        }

        runningTasks[task.id] = job
        _runningTasksCount.value = runningTasks.size
    }

    private suspend fun executeTask(task: ScheduledTask) {
        val executionId = "task_exec_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val startTime = System.currentTimeMillis()

        _schedulerEvents.emit(SchedulerEvent.TaskExecutionStarted(task.id, executionId))
        eventBus.emit(SkillEventBus.SkillEvent.TaskExecuted(
            source = TAG,
            taskId = task.id,
            taskName = task.name,
            success = true,
            executionTimeMs = 0
        ))

        var success = false
        var result: Any? = null
        var error: String? = null

        try {
            result = withContext(Dispatchers.Default) {
                when {
                    task.targetWorkflowId != null -> {
                        val executionResult = workflowEngine.executeWorkflow(
                            task.targetWorkflowId,
                            "scheduled"
                        )
                        success = executionResult?.success ?: false
                        executionResult
                    }
                    task.targetSkillName != null -> {
                        val skillLoader = SkillLoader.getInstance(android.app.Application())
                        val loadedSkill = skillLoader.loadSkill(task.targetSkillName, SkillManager.getInstance())

                        if (loadedSkill != null) {
                            success = true
                            "Skill executed: ${task.targetSkillName}"
                        } else {
                            success = false
                            "Skill not found: ${task.targetSkillName}"
                        }
                    }
                    else -> {
                        success = true
                        "Task executed: ${task.name}"
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message
            success = false
            AppLogger.e(TAG, "Task execution failed: ${task.id}", e)
        }

        val endTime = System.currentTimeMillis()
        val executionTime = endTime - startTime

        val execution = TaskExecution(
            taskId = task.id,
            taskName = task.name,
            executionId = executionId,
            startTime = startTime,
            endTime = endTime,
            success = success,
            result = result,
            error = error
        )

        val history = taskExecutionHistory.getOrPut(task.id) { mutableListOf() }
        history.add(execution)
        if (history.size > MAX_TASK_HISTORY) {
            history.removeAt(0)
        }

        val updatedTask = task.copy(
            lastExecutionTime = endTime,
            nextExecutionTime = calculateNextExecutionTime(task.scheduleType, task.scheduleConfig),
            executionCount = task.executionCount + 1,
            successCount = if (success) task.successCount + 1 else task.successCount,
            failureCount = if (!success) task.failureCount + 1 else task.failureCount
        )
        tasks[task.id] = updatedTask

        statsTotalExecuted.incrementAndGet()
        if (success) {
            statsTotalSuccess.incrementAndGet()
        } else {
            statsTotalFailure.incrementAndGet()
        }

        _schedulerEvents.emit(
            if (success) {
                SchedulerEvent.TaskExecutionCompleted(task.id, executionId, true)
            } else {
                SchedulerEvent.TaskExecutionFailed(task.id, executionId, error ?: "Unknown error")
            }
        )

        eventBus.emit(SkillEventBus.SkillEvent.TaskExecuted(
            source = TAG,
            taskId = task.id,
            taskName = task.name,
            success = success,
            executionTimeMs = executionTime
        ))

        if (!task.scheduleConfig.repeat || task.scheduleConfig.maxExecutions?.let { updatedTask.executionCount >= it } == true) {
            unscheduleTask(task.id)
        }

        updateTasksFlow()

        AppLogger.d(TAG, "Task executed: ${task.name} [${task.id}], success: ${success}, time: ${executionTime}ms")
    }

    private fun calculateNextExecutionTime(scheduleType: ScheduleType, config: TaskScheduleConfig): Long {
        val now = System.currentTimeMillis()

        return when (scheduleType) {
            ScheduleType.INTERVAL -> {
                now + (config.intervalMs ?: 60000L)
            }
            ScheduleType.SPECIFIC_TIME -> {
                parseSpecificTime(config.specificTime)?.time ?: (now + 60000L)
            }
            ScheduleType.CRON -> {
                parseCronExpression(config.cronExpression)?.let { nextTime ->
                    if (nextTime > now) nextTime else calculateNextCronTime(config.cronExpression)
                } ?: (now + 60000L)
            }
            ScheduleType.ONE_TIME -> {
                parseSpecificTime(config.specificTime)?.time ?: (now + 60000L)
            }
        }
    }

    private fun calculateDelayUntilNextExecution(task: ScheduledTask): Long {
        val now = System.currentTimeMillis()
        val nextTime = task.nextExecutionTime

        return if (nextTime > now) {
            nextTime - now
        } else {
            0
        }
    }

    private fun parseSpecificTime(timeStr: String): Date? {
        if (timeStr == null) return null

        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val dateTime = LocalDateTime.parse(timeStr, formatter)
            Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant())
        } catch (e: Exception) {
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                val dateTime = LocalDateTime.parse(timeStr, formatter)
                Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant())
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun parseCronExpression(cron: String): Long? {
        if (cron == null) return null

        val parts = cron.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null

        return try {
            val now = LocalDateTime.now()
            var next = now.plusMinutes(1).withSecond(CRON_SECOND).withNano(0)

            for (i in 0..59) {
                if (matchesCron(next, parts)) {
                    return Date.from(next.atZone(ZoneId.systemDefault()).toInstant()).time
                }
                next = next.plusMinutes(1)
            }
            null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse cron expression: ${cron}", e)
            null
        }
    }

    private fun matchesCron(dateTime: LocalDateTime, parts: List<String>): Boolean {
        if (parts.size < 5) return false

        val minute = parts[0]
        val hour = parts[1]
        val dayOfMonth = parts[2]
        val month = parts[3]
        val dayOfWeek = parts[4]

        return matchesCronField(dateTime.minute, minute) &&
                matchesCronField(dateTime.hour, hour) &&
                matchesCronField(dateTime.dayOfMonth, dayOfMonth) &&
                matchesCronField(dateTime.monthValue, month) &&
                matchesCronField(dateTime.dayOfWeek.value, dayOfWeek)
    }

    private fun matchesCronField(value: Int, field: String): Boolean {
        return when {
            field == "*" -> true
            field.contains(",") -> {
                field.split(",").any { matchesCronField(value, it.trim()) }
            }
            field.contains("-") -> {
                val range = field.split("-")
                if (range.size == 2) {
                    val start = range[0].toInt()
                    val end = range[1].toInt()
                    value in start..end
                } else false
            }
            field.contains("/") -> {
                val stepParts = field.split("/")
                if (stepParts.size == 2) {
                    val start = if (stepParts[0] == "*") 0 else stepParts[0].toInt()
                    val step = stepParts[1].toInt()
                    (value - start) % step == 0
                } else false
            }
            else -> {
                try {
                    value == field.toInt()
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun calculateNextCronTime(cron: String): Long {
        return parseCronExpression(cron) ?: (System.currentTimeMillis() + 60000L)
    }

    private fun updateTasksFlow() {
        _tasksFlow.value = tasks.values.toList()
    }

    fun cancelAllTasks() {
        runningTasks.values.forEach { it.cancel() }
        runningTasks.clear()
        _runningTasksCount.value = 0
        AppLogger.i(TAG, "All tasks cancelled")
    }

    fun getStats(): SchedulerStats {
        return SchedulerStats(
            totalScheduledTasks = tasks.size.toLong(),
            runningTasks = runningTasks.size,
            totalExecutions = statsTotalExecuted.get(),
            totalSuccess = statsTotalSuccess.get(),
            totalFailure = statsTotalFailure.get(),
            totalTasksCreated = statsTotalScheduled.get()
        )
    }

    data class SchedulerStats(
        val totalScheduledTasks: Long,
        val runningTasks: Int,
        val totalExecutions: Long,
        val totalSuccess: Long,
        val totalFailure: Long,
        val totalTasksCreated: Long
    )

    private fun generateTaskId(): String = "task_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    private class Date(val time: Long)
}
