package com.ai.assistance.aiterminal.terminal.agent.task

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TaskExecutionCoordinator"

interface TaskProgressListener {
    fun onProgressUpdate(taskId: String, progress: Int, message: String)
    fun onStepStarted(taskId: String, stepName: String)
    fun onStepCompleted(taskId: String, stepName: String, success: Boolean)
    fun onTaskStarted(taskId: String)
    fun onTaskCompleted(taskId: String, success: Boolean, errorMessage: String?)
}

open class SimpleProgressListener(
    private val callback: ((Int, String) -> Unit)? = null
) : TaskProgressListener {
    override fun onProgressUpdate(taskId: String, progress: Int, message: String) {
        callback?.invoke(progress, message)
        Log.d(TAG, "Task $taskId progress: $progress% - $message")
    }

    override fun onStepStarted(taskId: String, stepName: String) {
        Log.d(TAG, "Task $taskId step started: $stepName")
    }

    override fun onStepCompleted(taskId: String, stepName: String, success: Boolean) {
        Log.d(TAG, "Task $taskId step completed: $stepName - success: $success")
    }

    override fun onTaskStarted(taskId: String) {
        Log.d(TAG, "Task $taskId started")
    }

    override fun onTaskCompleted(taskId: String, success: Boolean, errorMessage: String?) {
        Log.d(TAG, "Task $taskId completed - success: $success, error: $errorMessage")
    }
}

class TaskExecutionCoordinator(
    private val taskExecutor: TaskExecutor,
    private val taskNotificationManager: TaskNotificationManager,
    private val taskWakeLockManager: TaskWakeLockManager
) {
    
    private val progressListeners = mutableSetOf<TaskProgressListener>()
    
    fun addProgressListener(listener: TaskProgressListener) {
        progressListeners.add(listener)
    }
    
    fun removeProgressListener(listener: TaskProgressListener) {
        progressListeners.remove(listener)
    }
    
    fun clearProgressListeners() {
        progressListeners.clear()
    }
    
    private fun notifyProgressUpdate(taskId: String, progress: Int, message: String) {
        progressListeners.forEach { it.onProgressUpdate(taskId, progress, message) }
    }
    
    private fun notifyStepStarted(taskId: String, stepName: String) {
        progressListeners.forEach { it.onStepStarted(taskId, stepName) }
    }
    
    private fun notifyStepCompleted(taskId: String, stepName: String, success: Boolean) {
        progressListeners.forEach { it.onStepCompleted(taskId, stepName, success) }
    }
    
    private fun notifyTaskStarted(taskId: String) {
        progressListeners.forEach { it.onTaskStarted(taskId) }
    }
    
    private fun notifyTaskCompleted(taskId: String, success: Boolean, errorMessage: String?) {
        progressListeners.forEach { it.onTaskCompleted(taskId, success, errorMessage) }
    }

    suspend fun executeTask(config: ScheduledTaskConfig): TaskExecutionResult {
        return executeTaskWithRetry(
            config = config,
            maxRetries = 0,
            retryDelayMs = 0,
            timeoutMs = 0
        )
    }
    
    suspend fun executeTaskWithRetry(
        config: ScheduledTaskConfig,
        maxRetries: Int = 3,
        retryDelayMs: Long = 1000,
        timeoutMs: Long = 0
    ): TaskExecutionResult {
        Log.i(TAG, "Executing task: ${config.taskName}, maxRetries: $maxRetries, timeoutMs: $timeoutMs")
        
        var attempt = 0
        var lastError: Exception? = null
        
        while (attempt <= maxRetries) {
            try {
                val result = if (timeoutMs > 0) {
                    executeWithTimeout(config, timeoutMs)
                } else {
                    executeWithWakeLockAndProgress(config)
                }
                
                if (result.isSuccess) {
                    Log.i(TAG, "Task succeeded on attempt $attempt")
                    return result
                }
                
                if (attempt < maxRetries) {
                    Log.w(TAG, "Task failed on attempt $attempt, will retry in $retryDelayMs ms")
                    delay(retryDelayMs * (attempt + 1))
                }
                
            } catch (e: CancellationException) {
                Log.e(TAG, "Task cancelled")
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "Task execution error on attempt $attempt", e)
                
                if (attempt < maxRetries) {
                    Log.w(TAG, "Will retry in $retryDelayMs ms")
                    delay(retryDelayMs * (attempt + 1))
                }
            }
            
            attempt++
        }
        
        val errorMessage = lastError?.message ?: "任务执行失败，已重试 $maxRetries 次"
        Log.e(TAG, errorMessage)
        
        if (config.notificationEnabled) {
            taskNotificationManager.showTaskErrorNotification(errorMessage, config.taskName)
        }
        
        notifyTaskCompleted(config.taskPlan.id, false, errorMessage)
        
        return TaskExecutionResult(
            taskId = config.taskPlan.id,
            status = TaskStatus.FAILED,
            stepResults = emptyMap(),
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
    }
    
    private suspend fun executeWithTimeout(
        config: ScheduledTaskConfig,
        timeoutMs: Long
    ): TaskExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            executeWithWakeLockAndProgress(config)
        } ?: run {
            Log.e(TAG, "Task execution timed out after $timeoutMs ms")
            
            if (config.notificationEnabled) {
                taskNotificationManager.showTaskErrorNotification(
                    "任务执行超时（${timeoutMs / 1000}秒）",
                    config.taskName
                )
            }
            
            notifyTaskCompleted(config.taskPlan.id, false, "任务执行超时（${timeoutMs / 1000}秒）")
            
            TaskExecutionResult(
                taskId = config.taskPlan.id,
                status = TaskStatus.FAILED,
                stepResults = emptyMap(),
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                errorMessage = "任务执行超时（${timeoutMs / 1000}秒）"
            )
        }
    }
    
    private suspend fun executeWithWakeLock(config: ScheduledTaskConfig): TaskExecutionResult {
        return executeWithWakeLockAndProgress(config)
    }
    
    private suspend fun executeWithWakeLockAndProgress(config: ScheduledTaskConfig): TaskExecutionResult {
        return taskWakeLockManager.withWakeLock(
            type = if (config.requiresWakeLock) {
                TaskWakeLockManager.WakeLockType.PARTIAL
            } else {
                TaskWakeLockManager.WakeLockType.PARTIAL
            },
            timeoutMs = if (config.requiresWakeLock) 10 * 60 * 1000L else 0L
        ) {
            try {
                notifyTaskStarted(config.taskPlan.id)
                notifyProgressUpdate(config.taskPlan.id, 0, "任务开始执行")
                
                if (config.notificationEnabled) {
                    taskNotificationManager.showTaskStartNotification(
                        config.taskName,
                        config.taskDescription
                    )
                }
                val totalSteps = config.taskPlan.steps.size
                
                val stepResults = mutableMapOf<String, StepExecutionResult>()
                val execContext = com.ai.assistance.aiterminal.terminal.agent.task.TaskExecutionContext(
                    taskPlan = config.taskPlan,
                    stepResults = stepResults
                )
                
                config.taskPlan.steps.forEachIndexed { index, step ->
                    val stepProgress = ((index + 1).toFloat() / totalSteps * 100).toInt()
                    execContext.currentStep = index
                    
                    notifyStepStarted(config.taskPlan.id, step.name)
                    notifyProgressUpdate(config.taskPlan.id, stepProgress, "正在执行: ${step.name}")
                    
                    val stepResult = taskExecutor.executeStep(step, execContext)
                    stepResults[step.id] = stepResult
                    
                    notifyStepCompleted(config.taskPlan.id, step.name, stepResult.isSuccess)
                    
                    if (!stepResult.isSuccess && config.stopOnFirstError) {
                        throw Exception("步骤 ${step.name} 执行失败: ${stepResult.errorMessage}")
                    }
                }
                
                notifyProgressUpdate(config.taskPlan.id, 100, "任务执行完成")
                
                if (config.notificationEnabled) {
                    taskNotificationManager.showTaskCompleteNotification(config.taskName)
                }
                
                val result = TaskExecutionResult(
                    taskId = config.taskPlan.id,
                    status = TaskStatus.COMPLETED,
                    stepResults = stepResults,
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis()
                )
                
                notifyTaskCompleted(config.taskPlan.id, true, null)
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed", e)
                
                notifyProgressUpdate(config.taskPlan.id, 0, "任务执行失败: ${e.message}")
                
                if (config.notificationEnabled) {
                    taskNotificationManager.showTaskErrorNotification(
                        e.message ?: "任务执行异常",
                        config.taskName
                    )
                }
                
                notifyTaskCompleted(config.taskPlan.id, false, e.message)
                
                TaskExecutionResult(
                    taskId = config.taskPlan.id,
                    status = TaskStatus.FAILED,
                    stepResults = emptyMap(),
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis(),
                    errorMessage = e.message
                )
            }
        }
    }
    
    suspend fun executeTaskWithProgress(
        config: ScheduledTaskConfig,
        progressListener: ((Int, String) -> Unit)? = null
    ): TaskExecutionResult {
        Log.i(TAG, "Executing task with progress monitoring: ${config.taskName}")
        
        val listener = if (progressListener != null) {
            SimpleProgressListener(progressListener)
        } else {
            null
        }
        
        listener?.let { addProgressListener(it) }
        
        return try {
            executeWithWakeLockAndProgress(config)
        } finally {
            listener?.let { removeProgressListener(it) }
        }
    }
    
    suspend fun executeTaskSilently(config: ScheduledTaskConfig): TaskExecutionResult {
        Log.i(TAG, "Executing task silently: ${config.taskName}")
        
        val originalNotificationEnabled = config.notificationEnabled
        config.notificationEnabled = false
        
        try {
            return executeWithWakeLockAndProgress(config)
        } finally {
            config.notificationEnabled = originalNotificationEnabled
        }
    }
    
    suspend fun executeTasks(
        configs: List<ScheduledTaskConfig>,
        parallel: Boolean = false
    ): List<TaskExecutionResult> {
        Log.i(TAG, "Executing ${configs.size} tasks, parallel: $parallel")
        
        return configs.map { executeTask(it) }
    }
}