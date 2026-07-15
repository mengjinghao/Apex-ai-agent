package com.apex.agent.core.multiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import com.apex.agent.core.tools.defaultTool.standard.name

class SessionStateManager {

    companion object {
        @Volatile
        private var instance: SessionStateManager? = null

        fun getInstance(): SessionStateManager {
            return instance ?: synchronized(this) {
                instance ?: SessionStateManager().also { instance = it }
            }
        }
    }
        private val sessionStates = ConcurrentHashMap<String, SessionState>()
        private val listeners = ConcurrentHashMap<String, SessionStateListener>()

    interface SessionStateListener {
        fun onSessionStateChanged(sessionId: String, state: SessionState)
        fun onSessionModeChanged(sessionId: String, mode: ModeSwitchBar.SessionMode)
        fun onSessionStatusChanged(sessionId: String, status: ModeSwitchBar.ExecutionStatus)
    }

    data class SessionState(
        val sessionId: String,
        var mode: ModeSwitchBar.SessionMode = ModeSwitchBar.SessionMode.NORMAL,
        var status: ModeSwitchBar.ExecutionStatus = ModeSwitchBar.ExecutionStatus.IDLE,
        var progress: Int = 0,
        var currentPhase: String? = null,
        var agentStates: Map<String, SanxingExecutionEngine.AgentExecutionState> = emptyMap(),
        var lastUpdateTime: Long = System.currentTimeMillis()
    )
        fun createSession(sessionId: String, mode: ModeSwitchBar.SessionMode = ModeSwitchBar.SessionMode.NORMAL): SessionState {
        val state = SessionState(sessionId = sessionId, mode = mode)
        sessionStates[sessionId] = state
        notifySessionStateChanged(sessionId, state)
        return state
    }
        fun getSession(sessionId: String): SessionState? {
        return sessionStates[sessionId]
    }
        fun updateSessionMode(sessionId: String, mode: ModeSwitchBar.SessionMode) {
        val state = sessionStates[sessionId] ?: createSession(sessionId, mode)
        state.mode = mode
        state.lastUpdateTime = System.currentTimeMillis()
        notifySessionModeChanged(sessionId, mode)
        notifySessionStateChanged(sessionId, state)
    }
        fun updateSessionStatus(sessionId: String, status: ModeSwitchBar.ExecutionStatus) {
        val state = sessionStates[sessionId] ?: return
        state.status = status
        state.lastUpdateTime = System.currentTimeMillis()
        notifySessionStatusChanged(sessionId, status)
        notifySessionStateChanged(sessionId, state)
    }
        fun updateSessionProgress(sessionId: String, progress: Int) {
        val state = sessionStates[sessionId] ?: return
        state.progress = progress
        state.lastUpdateTime = System.currentTimeMillis()
        notifySessionStateChanged(sessionId, state)
    }
        fun updateCurrentPhase(sessionId: String, phase: String) {
        val state = sessionStates[sessionId] ?: return
        state.currentPhase = phase
        state.lastUpdateTime = System.currentTimeMillis()
        notifySessionStateChanged(sessionId, state)
    }
        fun updateAgentStates(sessionId: String, agentStates: Map<String, SanxingExecutionEngine.AgentExecutionState>) {
        val state = sessionStates[sessionId] ?: return
        state.agentStates = agentStates
        state.lastUpdateTime = System.currentTimeMillis()
        notifySessionStateChanged(sessionId, state)
    }
        fun deleteSession(sessionId: String) {
        sessionStates.remove(sessionId)
    }

    /**
     * 应用协作规则到指定会�?     */
    fun applyRules(sessionId: String, rules: CollaborationRules) {
        val state = sessionStates[sessionId] ?: createSession(sessionId)
        // 根据规则更新会话模式
    val mode = when (rules.mode) {
            "supervisor", "serial", "parallel", "debate", "free" -> ModeSwitchBar.SessionMode.SANXING_LIUBU
            else -> ModeSwitchBar.SessionMode.NORMAL
        }
        updateSessionMode(sessionId, mode)
    }
        fun getAllSessions(): List<SessionState> {
        return sessionStates.values.toList()
    }
        fun getActiveSessions(): List<SessionState> {
        return sessionStates.values.filter {
            it.status == ModeSwitchBar.ExecutionStatus.RUNNING ||
            it.status == ModeSwitchBar.ExecutionStatus.PAUSED
        }
    }
        fun getActiveSessionCount(): Int {
        return getActiveSessions().size
    }
        fun addListener(sessionId: String, listener: SessionStateListener) {
        listeners[sessionId] = listener
    }
        fun removeListener(sessionId: String) {
        listeners.remove(sessionId)
    }
        fun saveState(sessionId: String): String? {
        val state = sessionStates[sessionId] ?: return null
        return serializeState(state)
    }
        fun restoreState(sessionId: String, serializedState: String): Boolean {
        return try {
            val state = deserializeState(serializedState)
            sessionStates[sessionId] = state
            notifySessionStateChanged(sessionId, state)
            true
        } catch (e: Exception) {
            false
        }
    }
        private fun serializeState(state: SessionState): String {
        return buildString {
            appendLine("sessionId:${state.sessionId}")
            appendLine("mode:${state.mode.name}")
            appendLine("status:${state.status.name}")
            appendLine("progress:${state.progress}")
            appendLine("currentPhase:${state.currentPhase ?: ""}")
            appendLine("lastUpdateTime:${state.lastUpdateTime}")
        }
    }
        private fun deserializeState(data: String): SessionState {
        val lines = data.lines()
        return SessionState(
            sessionId = lines.find { it.startsWith("sessionId:") }?.substringAfter(":") ?: "",
            mode = ModeSwitchBar.SessionMode.valueOf(lines.find { it.startsWith("mode:") }?.substringAfter(":") ?: "NORMAL"),
            status = ModeSwitchBar.ExecutionStatus.valueOf(lines.find { it.startsWith("status:") }?.substringAfter(":") ?: "IDLE"),
            progress = lines.find { it.startsWith("progress:") }?.substringAfter(":")?.toIntOrNull() ?: 0,
            currentPhase = lines.find { it.startsWith("currentPhase:") }?.substringAfter(":")?.takeIf { it.isNotEmpty() },
            lastUpdateTime = lines.find { it.startsWith("lastUpdateTime:") }?.substringAfter(":")?.toLongOrNull() ?: System.currentTimeMillis()
        )
    }
        private fun notifySessionStateChanged(sessionId: String, state: SessionState) {
        listeners[sessionId]?.onSessionStateChanged(sessionId, state)
    }
        private fun notifySessionModeChanged(sessionId: String, mode: ModeSwitchBar.SessionMode) {
        listeners[sessionId]?.onSessionModeChanged(sessionId, mode)
    }
        private fun notifySessionStatusChanged(sessionId: String, status: ModeSwitchBar.ExecutionStatus) {
        listeners[sessionId]?.onSessionStatusChanged(sessionId, status)
    }
}

class BackgroundTaskRunner {

    private val backgroundTasks = ConcurrentHashMap<String, BackgroundTask>()
        private val executor = java.util.concurrent.Executors.newScheduledThreadPool(4)

    interface BackgroundTask {
        fun run()
        fun cancel()
        fun isRunning(): Boolean
    }

    data class TaskInfo(
        val taskId: String,
        val sessionId: String,
        val description: String,
        val startTime: Long,
        var isRunning: Boolean = true
    ) : BackgroundTask {
        private var cancelled = false

        override fun run() {
            while (isRunning && !cancelled) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        override fun cancel() {
            cancelled = true
            isRunning = false
        }

        override fun isRunning(): Boolean = isRunning && !cancelled
    }
        fun startBackgroundTask(sessionId: String, task: BackgroundTask): String {
        val taskId = "task_${System.currentTimeMillis()}"
        val taskInfo = TaskInfo(
            taskId = taskId,
            sessionId = sessionId,
            description = "Background task for session",
            startTime = System.currentTimeMillis()
        )
        backgroundTasks[taskId] = taskInfo
        executor.execute {
            task.run()
        }
        return taskId
    }
        fun stopBackgroundTask(taskId: String): Boolean {
        val task = backgroundTasks[taskId]
        if (task != null) {
            task.cancel()
            backgroundTasks.remove(taskId)
        return true
        }
        return false
    }
        fun stopAllTasksForSession(sessionId: String) {
        backgroundTasks.entries.removeIf { (_, task) ->
            if (task is TaskInfo && task.sessionId == sessionId) {
                task.cancel()
                true
            } else {
                false
            }
        }
    }
        fun getActiveTaskCount(): Int {
        return backgroundTasks.count { it.value.isRunning() }
    }
        fun shutdown() {
        backgroundTasks.values.forEach { it.cancel() }
        backgroundTasks.clear()
        executor.shutdownNow()
    }
}

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "sanxing_multi_agent_channel"

    init {
        createNotificationChannel()
    }
        private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "，Agent 协作通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "，Agent 协作任务的进度和完成通知"
        }
        notificationManager.createNotificationChannel(channel)
    }
        fun showTaskRunningNotification(sessionName: String, progress: Int) {
        val notification = Notification.Builder(context, channelId)
            .setContentTitle("任务执行的）
            .setContentText("${sessionName} - ${progress}%")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        notificationManager.notify(sessionName.hashCode(), notification)
    }
        fun showTaskCompletedNotification(sessionName: String) {
        val notification = Notification.Builder(context, channelId)
            .setContentTitle("任务完成")
            .setContentText("${sessionName} 已完成执行）
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(sessionName.hashCode(), notification)
    }
        fun showTaskFailedNotification(sessionName: String, error: String) {
        val notification = Notification.Builder(context, channelId)
            .setContentTitle("任务执行失败")
            .setContentText("${sessionName} - ${error}")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(sessionName.hashCode(), notification)
    }
        fun showUserInterventionNotification(sessionName: String, message: String) {
        val notification = Notification.Builder(context, channelId)
            .setContentTitle("需要用户干�?
            .setContentText("${sessionName} - ${message}")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(sessionName.hashCode(), notification)
    }
        fun cancelNotification(sessionName: String) {
        notificationManager.cancel(sessionName.hashCode())
    }
}
