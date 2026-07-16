package com.apex.agent.core.multiagent

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class BackgroundTaskManager {
    companion object {
        private const val TAG = "BackgroundTaskManager"
    }

    private val executorService: ExecutorService = Executors.newCachedThreadPool()
    private val tasks = ConcurrentHashMap<String, TaskInfo>()

    fun submitTask(taskId: String, task: () -> Unit): Future<*> {
        val future = executorService.submit {
            try {
                tasks[taskId] = TaskInfo(taskId, TaskStatus.RUNNING)
                task()
                tasks[taskId] = TaskInfo(taskId, TaskStatus.COMPLETED)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Task $taskId failed", e)
                tasks[taskId] = TaskInfo(taskId, TaskStatus.FAILED, e.message)
            }
        }

        return future
    }

    fun cancelTask(taskId: String): Boolean {
        val taskInfo = tasks[taskId]
        if (taskInfo == null) {
            return false
        }

        // 这里可以添加取消任务的逻辑
        tasks[taskId] = TaskInfo(taskId, TaskStatus.CANCELLED)
        return true
    }

    fun getTaskStatus(taskId: String): TaskStatus {
        return tasks[taskId]?.status ?: TaskStatus.NOT_FOUND
    }

    fun getAllTasks(): List<TaskInfo> {
        return tasks.values.toList()
    }

    fun shutdown() {
        executorService.shutdown()
    }


