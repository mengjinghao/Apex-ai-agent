package com.ai.assistance.aiterminal.terminal.agent.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "TaskScheduler"

/**
 * 任务调度器 - 专门负责 WorkManager 和 AlarmManager 的调度逻辑
 * 
 * 职责：
 * 1. 使用 WorkManager 调度一次性任务
 * 2. 使用 AlarmManager 调度重复任务
 * 3. 取消已调度的任务
 * 4. 查询任务状态
 * 5. 支持任务优先级和任务组管理
 */
class TaskScheduler(
    private val context: Context
) {
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    
    private val workManager: WorkManager by lazy {
        WorkManager.getInstance(context)
    }
    
    /**
     * 任务优先级枚举
     */
    enum class TaskPriority {
        HIGH,
        MEDIUM,
        LOW
    }
    
    /**
     * 任务状态枚举
     */
    enum class TaskStatus {
        SCHEDULED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        UNKNOWN
    }
    
    /**
     * 调度一次性任务
     */
    fun scheduleOneTimeTask(
        config: ScheduledTaskConfig,
        constraints: Constraints,
        priority: TaskPriority = TaskPriority.MEDIUM
    ) {
        val delayMillis = config.triggerTime - System.currentTimeMillis()
        
        if (delayMillis <= 0) {
            Log.i(TAG, "Trigger time is in the past, task will be executed immediately by TaskExecutionCoordinator")
            return
        }
        
        val workRequest = OneTimeWorkRequest.Builder(TaskWorker::class.java)
            .setConstraints(constraints)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                getBackoffDelay(priority),
                TimeUnit.MILLISECONDS
            )
            .addTag(config.taskId)
            .addTag(getPriorityTag(priority))
            .addTag(config.groupId ?: "default_group")
            .setInputData(createInputData(config))
            .build()
        
        workManager.enqueueUniqueWork(
            config.taskId,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        Log.i(TAG, "One-time task scheduled: ${config.taskName}, delay: $delayMillis ms, priority: $priority")
    }
    
    /**
     * 调度重复任务
     */
    fun scheduleRepeatingTask(
        config: ScheduledTaskConfig,
        priority: TaskPriority = TaskPriority.MEDIUM
    ) {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = "com.ai.assistance.aiterminal.TASK_TRIGGER"
            putExtra("taskId", config.taskId)
            putExtra("priority", priority.name)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            config.taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val interval = config.repeatInterval ?: when (priority) {
            TaskPriority.HIGH -> AlarmManager.INTERVAL_HALF_HOUR
            TaskPriority.MEDIUM -> AlarmManager.INTERVAL_HOUR
            TaskPriority.LOW -> AlarmManager.INTERVAL_HOUR * 4
        }
        
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            config.triggerTime,
            interval,
            pendingIntent
        )
        
        Log.i(TAG, "Repeating task scheduled: ${config.taskName}, interval: $interval ms")
    }
    
    /**
     * 取消任务调度
     */
    fun cancelScheduledTask(taskId: String): Operation {
        Log.i(TAG, "Cancelling task: $taskId")
        
        // 取消 WorkManager 任务
        val operation = workManager.cancelAllWorkByTag(taskId)
        
        // 取消 AlarmManager 任务
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = "com.ai.assistance.aiterminal.TASK_TRIGGER"
            putExtra("taskId", taskId)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        
        Log.i(TAG, "AlarmManager task cancelled: $taskId")
        return operation
    }
    
    /**
     * 取消任务组中的所有任务
     */
    fun cancelTasksByGroup(groupId: String): Operation {
        Log.i(TAG, "Cancelling all tasks in group: $groupId")
        return workManager.cancelAllWorkByTag(groupId)
    }
    
    /**
     * 取消指定优先级的所有任务
     */
    fun cancelTasksByPriority(priority: TaskPriority): Operation {
        Log.i(TAG, "Cancelling all tasks with priority: $priority")
        return workManager.cancelAllWorkByTag(getPriorityTag(priority))
    }
    
    /**
     * 查询任务状态
     */
    suspend fun getTaskStatus(taskId: String): TaskStatus {
        return withContext(Dispatchers.IO) {
            try {
                val workInfo = workManager.getWorkInfosByTag(taskId).get()
                
                if (workInfo.isEmpty()) {
                    // 检查 AlarmManager
                    return@withContext checkAlarmTaskStatus(taskId)
                }
                
                when (workInfo.first().state) {
                    WorkInfo.State.ENQUEUED -> TaskStatus.SCHEDULED
                    WorkInfo.State.RUNNING -> TaskStatus.RUNNING
                    WorkInfo.State.SUCCEEDED -> TaskStatus.SUCCEEDED
                    WorkInfo.State.FAILED -> TaskStatus.FAILED
                    WorkInfo.State.CANCELLED -> TaskStatus.CANCELLED
                    else -> TaskStatus.UNKNOWN
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get task status", e)
                TaskStatus.UNKNOWN
            }
        }
    }
    
    /**
     * 获取任务组中所有任务的状态
     */
    suspend fun getTasksByGroup(groupId: String): List<TaskStatusInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val workInfos = workManager.getWorkInfosByTag(groupId).get()
                workInfos.map { workInfo ->
                    TaskStatusInfo(
                        taskId = workInfo.tags.firstOrNull { !it.startsWith("priority_") && it != groupId } ?: "unknown",
                        status = when (workInfo.state) {
                            WorkInfo.State.ENQUEUED -> TaskStatus.SCHEDULED
                            WorkInfo.State.RUNNING -> TaskStatus.RUNNING
                            WorkInfo.State.SUCCEEDED -> TaskStatus.SUCCEEDED
                            WorkInfo.State.FAILED -> TaskStatus.FAILED
                            WorkInfo.State.CANCELLED -> TaskStatus.CANCELLED
                            else -> TaskStatus.UNKNOWN
                        },
                        progress = workInfo.progress,
                        outputData = workInfo.outputData
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get tasks by group", e)
                emptyList()
            }
        }
    }
    
    /**
     * 获取所有任务状态
     */
    suspend fun getAllTasks(): List<TaskStatusInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val statuses = mutableListOf<TaskStatusInfo>()
                WorkInfo.State.values().forEach { state ->
                    val workInfos = workManager.getWorkInfosByTag(state.name).get()
                    statuses.addAll(workInfos.map { workInfo ->
                        TaskStatusInfo(
                            taskId = workInfo.tags.firstOrNull { !it.startsWith("priority_") && it != "default_group" } ?: "unknown",
                            status = when (workInfo.state) {
                                WorkInfo.State.ENQUEUED -> TaskStatus.SCHEDULED
                                WorkInfo.State.RUNNING -> TaskStatus.RUNNING
                                WorkInfo.State.SUCCEEDED -> TaskStatus.SUCCEEDED
                                WorkInfo.State.FAILED -> TaskStatus.FAILED
                                WorkInfo.State.CANCELLED -> TaskStatus.CANCELLED
                                else -> TaskStatus.UNKNOWN
                            },
                            progress = workInfo.progress,
                            outputData = workInfo.outputData
                        )
                    })
                }
                statuses
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get all tasks", e)
                emptyList()
            }
        }
    }
    
    /**
     * 检查 AlarmManager 任务状态
     */
    private fun checkAlarmTaskStatus(taskId: String): TaskStatus {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = "com.ai.assistance.aiterminal.TASK_TRIGGER"
            putExtra("taskId", taskId)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        
        return if (pendingIntent != null) {
            TaskStatus.SCHEDULED
        } else {
            TaskStatus.UNKNOWN
        }
    }
    
    /**
     * 根据优先级获取退避延迟
     */
    private fun getBackoffDelay(priority: TaskPriority): Long {
        return when (priority) {
            TaskPriority.HIGH -> WorkRequest.MIN_BACKOFF_MILLIS
            TaskPriority.MEDIUM -> WorkRequest.MIN_BACKOFF_MILLIS * 2
            TaskPriority.LOW -> WorkRequest.MIN_BACKOFF_MILLIS * 4
        }
    }
    
    /**
     * 获取优先级标签
     */
    private fun getPriorityTag(priority: TaskPriority): String {
        return "priority_${priority.name.lowercase()}"
    }
    
    /**
     * 创建输入数据
     */
    private fun createInputData(config: ScheduledTaskConfig): Data {
        return Data.Builder()
            .putString("taskId", config.taskId)
            .putString("taskName", config.taskName)
            .putString("taskDescription", config.taskDescription)
            .build()
    }
    
    /**
     * 任务状态信息
     */
    data class TaskStatusInfo(
        val taskId: String,
        val status: TaskStatus,
        val progress: Data? = null,
        val outputData: Data? = null
    )
}