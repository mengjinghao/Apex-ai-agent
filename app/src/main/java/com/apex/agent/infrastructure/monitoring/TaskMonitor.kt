package com.apex.agent.infrastructure.monitoring

import com.apex.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

interface TaskMonitor {

    fun recordTaskStarted(taskId: String)
    fun recordTaskCompleted(taskId: String, durationMillis: Long)
    fun recordTaskFailed(taskId: String, throwable: Throwable)
    fun activeTaskCount(): Int

    @Singleton

        private val activeTasks = mutableSetOf<String>()

        override fun recordTaskStarted(taskId: String) {
            synchronized(activeTasks) {
                activeTasks.add(taskId)
            }
        }

        override fun recordTaskCompleted(taskId: String, durationMillis: Long) {
            synchronized(activeTasks) {
                activeTasks.remove(taskId)
            }
        }

        override fun recordTaskFailed(taskId: String, throwable: Throwable) {
            AppLogger.e(TAG, "Task failed: $taskId", throwable)
            synchronized(activeTasks) {
                activeTasks.remove(taskId)
            }
        }

        override fun activeTaskCount(): Int {
            return synchronized(activeTasks) { activeTasks.size }
        }
    }
}
