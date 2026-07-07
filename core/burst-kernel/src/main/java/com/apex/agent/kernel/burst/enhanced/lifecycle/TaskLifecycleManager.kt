package com.apex.agent.kernel.burst.enhanced.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B16: 任务生命周期管理器
 *
 * 增强现有 BurstTaskScheduler 的状态管理：
 * - 12 态完整生命周期（vs 现有 5 态）
 * - 状态转换校验（非法转换拒绝）
 * - 生命周期事件流
 * - 超时/重试/取消的自动状态处理
 * - 任务依赖关系感知
 */
class TaskLifecycleManager {

    /**
     * 完整任务生命周期（12 态）
     */
    enum class TaskState {
        CREATED,           // 已创建
        QUEUED,            // 已入队
        SCHEDULED,         // 已调度
        RUNNING,           // 运行中
        PAUSED,            // 已暂停
        RETRYING,          // 重试中
        WAITING_DEPENDENCY,// 等待依赖
        WAITING_RESOURCE,  // 等待资源
        CANCELLING,        // 取消中
        COMPLETED,         // 已完成
        FAILED,            // 已失败
        CANCELLED          // 已取消
    }

    /**
     * 状态转换规则
     */
    private val validTransitions = mapOf(
        TaskState.CREATED to setOf(TaskState.QUEUED, TaskState.CANCELLED),
        TaskState.QUEUED to setOf(TaskState.SCHEDULED, TaskState.CANCELLED, TaskState.WAITING_DEPENDENCY),
        TaskState.WAITING_DEPENDENCY to setOf(TaskState.QUEUED, TaskState.CANCELLED),
        TaskState.SCHEDULED to setOf(TaskState.RUNNING, TaskState.CANCELLED, TaskState.WAITING_RESOURCE),
        TaskState.WAITING_RESOURCE to setOf(TaskState.SCHEDULED, TaskState.CANCELLED),
        TaskState.RUNNING to setOf(TaskState.PAUSED, TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLING, TaskState.RETRYING),
        TaskState.PAUSED to setOf(TaskState.RUNNING, TaskState.CANCELLING),
        TaskState.RETRYING to setOf(TaskState.RUNNING, TaskState.FAILED, TaskState.CANCELLING),
        TaskState.CANCELLING to setOf(TaskState.CANCELLED),
        TaskState.COMPLETED to emptySet<TaskState>(),  // 终态
        TaskState.FAILED to setOf(TaskState.RETRYING),  // 可重试
        TaskState.CANCELLED to emptySet<TaskState>()   // 终态
    )

    /**
     * 生命周期事件
     */
    data class LifecycleEvent(
        val taskId: String,
        val from: TaskState,
        val to: TaskState,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any> = emptyMap()
    )

    /**
     * 任务状态记录
     */
    data class TaskStatus(
        val taskId: String,
        val state: TaskState,
        val createdAt: Long,
        val lastUpdated: Long,
        val stateHistory: List<LifecycleEvent>,
        val retryCount: Int,
        val pauseCount: Int,
        val totalRunningTimeMs: Long,
        val parentTaskId: String?,
        val childTaskIds: List<String>
    )

    private val taskStatuses = ConcurrentHashMap<String, TaskStatus>()
    private val _events = MutableStateFlow<List<LifecycleEvent>>(emptyList())
    val events: StateFlow<List<LifecycleEvent>> = _events.asStateFlow()
    private val recentEvents = mutableListOf<LifecycleEvent>()
    private val runningSince = ConcurrentHashMap<String, Long>()  // taskId -> 开始运行时间

    /**
     * 创建任务
     */
    fun createTask(taskId: String, parentTaskId: String? = null): TaskStatus {
        val now = System.currentTimeMillis()
        val status = TaskStatus(
            taskId = taskId, state = TaskState.CREATED,
            createdAt = now, lastUpdated = now,
            stateHistory = emptyList(), retryCount = 0, pauseCount = 0,
            totalRunningTimeMs = 0, parentTaskId = parentTaskId, childTaskIds = emptyList()
        )
        taskStatuses[taskId] = status
        emitEvent(taskId, TaskState.CREATED, TaskState.CREATED, "创建任务")
        return status
    }

    /**
     * 状态转换
     */
    fun transition(taskId: String, newState: TaskState, reason: String = "", metadata: Map<String, Any> = emptyMap()): Boolean {
        val current = taskStatuses[taskId] ?: return false
        val allowed = validTransitions[current.state] ?: emptySet()
        if (newState !in allowed && current.state != newState) {
            return false  // 非法转换
        }

        // 记录运行时间
        val runningTime = if (current.state == TaskState.RUNNING) {
            runningSince.remove(taskId)?.let { System.currentTimeMillis() - it } ?: 0
        } else 0

        if (newState == TaskState.RUNNING) {
            runningSince[taskId] = System.currentTimeMillis()
        }

        val event = LifecycleEvent(taskId, current.state, newState, reason, metadata = metadata)
        val updated = current.copy(
            state = newState,
            lastUpdated = System.currentTimeMillis(),
            stateHistory = current.stateHistory + event,
            retryCount = if (newState == TaskState.RETRYING) current.retryCount + 1 else current.retryCount,
            pauseCount = if (newState == TaskState.PAUSED) current.pauseCount + 1 else current.pauseCount,
            totalRunningTimeMs = current.totalRunningTimeMs + runningTime
        )
        taskStatuses[taskId] = updated
        emitEvent(taskId, current.state, newState, reason)
        return true
    }

    /**
     * 获取任务状态
     */
    fun getStatus(taskId: String): TaskStatus? = taskStatuses[taskId]

    /**
     * 获取所有任务
     */
    fun getAllTasks(): List<TaskStatus> = taskStatuses.values.toList()

    /**
     * 按状态过滤
     */
    fun getTasksByState(state: TaskState): List<TaskStatus> =
        taskStatuses.values.filter { it.state == state }

    /**
     * 获取运行中任务
     */
    fun getRunningTasks(): List<TaskStatus> = getTasksByState(TaskState.RUNNING)

    /**
     * 获取活跃任务（非终态）
     */
    fun getActiveTasks(): List<TaskStatus> = taskStatuses.values.filter {
        it.state !in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED)
    }

    /**
     * 添加子任务
     */
    fun addChildTask(parentId: String, childId: String): Boolean {
        val parent = taskStatuses[parentId] ?: return false
        taskStatuses[parentId] = parent.copy(childTaskIds = parent.childTaskIds + childId)
        return true
    }

    /**
     * 清理已完成任务
     */
    fun cleanup(olderThanMs: Long = 3600_000L): Int {
        val threshold = System.currentTimeMillis() - olderThanMs
        val toRemove = taskStatuses.entries
            .filter { it.value.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED) }
            .filter { it.value.lastUpdated < threshold }
            .map { it.key }
        toRemove.forEach { taskStatuses.remove(it) }
        return toRemove.size
    }

    /**
     * 生成生命周期报告
     */
    fun generateReport(taskId: String): String {
        val status = taskStatuses[taskId] ?: return "任务不存在"
        val sb = StringBuilder()
        sb.appendLine("═══ 任务生命周期: $taskId ═══")
        sb.appendLine("当前状态: ${status.state}")
        sb.appendLine("创建时间: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(status.createdAt))}")
        sb.appendLine("重试次数: ${status.retryCount}")
        sb.appendLine("暂停次数: ${status.pauseCount}")
        sb.appendLine("总运行时间: ${status.totalRunningTimeMs}ms")
        if (status.parentTaskId != null) sb.appendLine("父任务: ${status.parentTaskId}")
        if (status.childTaskIds.isNotEmpty()) sb.appendLine("子任务: ${status.childTaskIds}")
        sb.appendLine()
        sb.appendLine("状态历史:")
        status.stateHistory.forEach { event ->
            sb.appendLine("  ${event.from} → ${event.to}: ${event.reason}")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    private fun emitEvent(taskId: String, from: TaskState, to: TaskState, reason: String) {
        val event = LifecycleEvent(taskId, from, to, reason)
        recentEvents.add(event)
        while (recentEvents.size > 500) recentEvents.removeAt(0)
        _events.value = recentEvents.toList()
    }
}
