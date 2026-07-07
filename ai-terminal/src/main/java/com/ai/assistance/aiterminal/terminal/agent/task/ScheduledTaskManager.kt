package com.ai.assistance.aiterminal.terminal.agent.task

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ScheduledTaskManager"

/**
 * 定时任务管理器 - 重构后的门面类
 * 
 * 职责：协调各个专门的管理器，提供统一的任务调度接口
 * 
 * 架构：
 * - TaskScheduler: 负责任务调度逻辑
 * - TaskNotificationManager: 负责通知管理
 * - TaskWakeLockManager: 负责 WakeLock 管理
 * - TaskConstraintsBuilder: 负责约束条件构建
 * - TaskExecutionCoordinator: 负责任务执行协调
 */
class ScheduledTaskManager(
    private val context: Context,
    private val taskExecutor: TaskExecutor,
    private val taskPersistence: TaskPersistence
) {
    // 专门的管理器
    private val taskScheduler: TaskScheduler by lazy {
        TaskScheduler(context)
    }
    
    private val taskNotificationManager: TaskNotificationManager by lazy {
        TaskNotificationManager(context)
    }
    
    private val taskWakeLockManager: TaskWakeLockManager by lazy {
        TaskWakeLockManager(context)
    }
    
    private val taskConstraintsBuilder: TaskConstraintsBuilder by lazy {
        TaskConstraintsBuilder()
    }
    
    private val taskExecutionCoordinator: TaskExecutionCoordinator by lazy {
        TaskExecutionCoordinator(
            taskExecutor,
            taskNotificationManager,
            taskWakeLockManager
        )
    }
    
    /**
     * 调度定时任务
     */
    suspend fun scheduleTask(config: ScheduledTaskConfig) {
        Log.i(TAG, "Scheduling task: ${config.taskName}")
        
        // 保存任务配置
        taskPersistence.saveScheduledTask(config)
        
        // 根据触发类型调度
        val constraints = taskConstraintsBuilder.buildFromConfig(config)
        
        when (config.triggerType) {
            TriggerType.ONE_TIME -> {
                val delayMillis = config.triggerTime - System.currentTimeMillis()
                if (delayMillis <= 0) {
                    Log.i(TAG, "Trigger time is in the past, executing immediately")
                    runTaskNow(config)
                } else {
                    taskScheduler.scheduleOneTimeTask(config, constraints)
                }
            }
            TriggerType.REPEATING,
            TriggerType.DAILY,
            TriggerType.WEEKLY -> {
                taskScheduler.scheduleRepeatingTask(config)
            }
            TriggerType.CUSTOM_CRON -> {
                taskScheduler.scheduleOneTimeTask(config, constraints)
            }
        }
        
        // 发送任务已调度的通知
        taskNotificationManager.showTaskScheduledNotification(
            config.taskName,
            formatTriggerTime(config.triggerTime)
        )
    }
    
    /**
     * 调度定时任务（带优先级）
     */
    suspend fun scheduleTaskWithPriority(
        config: ScheduledTaskConfig,
        priority: TaskScheduler.TaskPriority
    ) {
        Log.i(TAG, "Scheduling task with priority: ${config.taskName}, priority: $priority")
        
        // 保存任务配置
        taskPersistence.saveScheduledTask(config)
        
        // 根据触发类型调度
        val constraints = taskConstraintsBuilder.buildFromConfig(config)
        
        when (config.triggerType) {
            TriggerType.ONE_TIME -> {
                val delayMillis = config.triggerTime - System.currentTimeMillis()
                if (delayMillis <= 0) {
                    Log.i(TAG, "Trigger time is in the past, executing immediately")
                    runTaskNow(config)
                } else {
                    taskScheduler.scheduleOneTimeTask(config, constraints, priority)
                }
            }
            TriggerType.REPEATING,
            TriggerType.DAILY,
            TriggerType.WEEKLY -> {
                taskScheduler.scheduleRepeatingTask(config, priority)
            }
            TriggerType.CUSTOM_CRON -> {
                taskScheduler.scheduleOneTimeTask(config, constraints, priority)
            }
        }
        
        taskNotificationManager.showTaskScheduledNotification(
            config.taskName,
            formatTriggerTime(config.triggerTime)
        )
    }
    
    /**
     * 立即执行任务
     */
    suspend fun runTaskNow(config: ScheduledTaskConfig): TaskExecutionResult {
        Log.i(TAG, "Running task now: ${config.taskName}")
        
        val result = taskExecutionCoordinator.executeTask(config)
        
        // 处理一次性任务
        if (config.triggerType == TriggerType.ONE_TIME) {
            cancelTask(config.taskId)
        }
        
        return result
    }
    
    /**
     * 立即执行任务（带重试和超时）
     */
    suspend fun runTaskNowWithRetry(
        config: ScheduledTaskConfig,
        maxRetries: Int = 3,
        timeoutMs: Long = 0
    ): TaskExecutionResult {
        Log.i(TAG, "Running task now with retry: ${config.taskName}")
        
        val result = taskExecutionCoordinator.executeTaskWithRetry(
            config = config,
            maxRetries = maxRetries,
            timeoutMs = timeoutMs
        )
        
        if (config.triggerType == TriggerType.ONE_TIME) {
            cancelTask(config.taskId)
        }
        
        return result
    }
    
    /**
     * 取消任务
     */
    suspend fun cancelTask(taskId: String) {
        Log.i(TAG, "Cancelling task: $taskId")
        
        // 取消调度
        taskScheduler.cancelScheduledTask(taskId)
        
        // 删除持久化的任务
        taskPersistence.deleteScheduledTask(taskId)
    }
    
    /**
     * 取消任务组中的所有任务
     */
    suspend fun cancelTasksByGroup(groupId: String) {
        Log.i(TAG, "Cancelling all tasks in group: $groupId")
        
        // 取消调度
        taskScheduler.cancelTasksByGroup(groupId)
        
        // 删除持久化的任务
        taskPersistence.deleteScheduledTasksByGroup(groupId)
    }
    
    /**
     * 取消所有任务
     */
    suspend fun cancelAllTasks() {
        Log.i(TAG, "Cancelling all tasks")
        
        // 取消所有调度任务
        val allTasks = getAllScheduledTasks()
        allTasks.forEach { taskScheduler.cancelScheduledTask(it.taskId) }
        
        // 清空持久化
        taskPersistence.deleteAllScheduledTasks()
    }
    
    /**
     * 获取所有已调度的任务
     */
    suspend fun getAllScheduledTasks(): List<ScheduledTaskConfig> {
        return taskPersistence.getAllScheduledTasks()
    }
    
    /**
     * 根据任务组获取任务
     */
    suspend fun getTasksByGroup(groupId: String): List<ScheduledTaskConfig> {
        return taskPersistence.getScheduledTasksByGroup(groupId)
    }
    
    /**
     * 根据任务ID获取任务
     */
    suspend fun getTaskById(taskId: String): ScheduledTaskConfig? {
        return taskPersistence.getScheduledTask(taskId)
    }
    
    /**
     * 获取任务状态
     */
    suspend fun getTaskStatus(taskId: String): TaskScheduler.TaskStatus {
        return taskScheduler.getTaskStatus(taskId)
    }
    
    /**
     * 获取所有任务状态
     */
    suspend fun getAllTaskStatuses(): List<TaskStatusWithConfig> {
        return withContext(Dispatchers.IO) {
            val tasks = getAllScheduledTasks()
            tasks.map { task ->
                TaskStatusWithConfig(
                    config = task,
                    status = taskScheduler.getTaskStatus(task.taskId)
                )
            }
        }
    }
    
    /**
     * 批量调度任务
     */
    suspend fun scheduleTasks(configs: List<ScheduledTaskConfig>) {
        Log.i(TAG, "Scheduling ${configs.size} tasks")
        
        configs.forEach { scheduleTask(it) }
    }
    
    /**
     * 批量立即执行任务
     */
    suspend fun runTasksNow(configs: List<ScheduledTaskConfig>): List<TaskExecutionResult> {
        Log.i(TAG, "Running ${configs.size} tasks now")
        
        return taskExecutionCoordinator.executeTasks(configs, parallel = false)
    }
    
    /**
     * 并行执行任务
     */
    suspend fun runTasksParallel(configs: List<ScheduledTaskConfig>): List<TaskExecutionResult> {
        Log.i(TAG, "Running ${configs.size} tasks in parallel")
        
        return taskExecutionCoordinator.executeTasks(configs, parallel = true)
    }
    
    /**
     * 更新任务配置
     */
    suspend fun updateTask(config: ScheduledTaskConfig) {
        Log.i(TAG, "Updating task: ${config.taskId}")
        
        // 取消旧任务
        cancelTask(config.taskId)
        
        // 重新调度
        scheduleTask(config)
    }
    
    /**
     * 暂停任务
     */
    suspend fun pauseTask(taskId: String) {
        Log.i(TAG, "Pausing task: $taskId")
        
        taskScheduler.cancelScheduledTask(taskId)
    }
    
    /**
     * 恢复任务
     */
    suspend fun resumeTask(taskId: String) {
        Log.i(TAG, "Resuming task: $taskId")
        
        val config = taskPersistence.getScheduledTask(taskId)
        config?.let { scheduleTask(it) }
    }
    
    /**
     * 检查任务是否存在
     */
    suspend fun taskExists(taskId: String): Boolean {
        return taskPersistence.getScheduledTask(taskId) != null
    }
    
    /**
     * 获取任务数量
     */
    suspend fun getTaskCount(): Int {
        return getAllScheduledTasks().size
    }
    
    /**
     * 获取任务组数量
     */
    suspend fun getGroupCount(): Int {
        val tasks = getAllScheduledTasks()
        return tasks.map { it.groupId }.distinct().size
    }
    
    /**
     * 格式化触发时间
     */
    private fun formatTriggerTime(triggerTime: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(triggerTime))
    }
    
    /**
     * 任务状态与配置组合
     */
    data class TaskStatusWithConfig(
        val config: ScheduledTaskConfig,
        val status: TaskScheduler.TaskStatus
    )
}

// ==================== Worker 类 ====================

class TaskWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    
    override fun doWork(): Result {
        Log.i(TAG, "TaskWorker running")
        
        return Result.success()
    }
}

// ==================== AlarmReceiver 类 ====================

class TaskAlarmReceiver : android.content.BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("taskId") ?: return
        Log.i(TAG, "Alarm received for task: $taskId")
    }
}