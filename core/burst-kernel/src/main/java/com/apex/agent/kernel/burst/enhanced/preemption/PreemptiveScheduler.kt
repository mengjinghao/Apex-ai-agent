package com.apex.agent.kernel.burst.enhanced.preemption

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B4: 任务优先级抢占（Preemptive Priority Scheduling）
 *
 * 修复现有 BurstTaskScheduler 不能抢占正在执行任务的缺陷：
 * - 新任务 priority 高于运行中任务时，暂停低优任务（保存检查点）
 * - 高优任务先执行
 * - 低优任务稍后自动恢复
 *
 * 抢占策略：
 * - PRIORITY_PREEMPT: 优先级差 ≥ 30 时抢占
 * - RAGE_PREEMPT: 狂暴状态下无条件抢占
 * - DEADLINE_PREEMPT: 临近 deadline 的任务抢占
 */
class PreemptiveScheduler(
    private val preemptionThreshold: Int = 30,
    private val checkIntervalMs: Long = 1000
) {

    /**
     * 运行中任务
     */
    data class RunningTask(
        val taskId: String,
        val priority: Int,
        val startedAt: Long,
        val deadline: Long?,
        val skillId: String,
        val isPreempted: Boolean = false,
        val preemptedAt: Long? = null,
        val checkpoint: String? = null  // 抢占时保存的检查点
    )

    /**
     * 抢占事件
     */
    data class PreemptionEvent(
        val preemptedTaskId: String,
        val preemptingTaskId: String,
        val reason: PreemptionReason,
        val priorityDiff: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class PreemptionReason {
        PRIORITY_PREEMPT,    // 优先级抢占
        RAGE_PREEMPT,        // 狂暴抢占
        DEADLINE_PREEMPT,    // 截止时间抢占
        MANUAL_PREEMPT       // 手动抢占
    }

    /**
     * 抢占策略
     */
    enum class PreemptionPolicy {
        AGGRESSIVE,   // 激进：阈值低，频繁抢占
        BALANCED,     // 平衡：默认
        CONSERVATIVE  // 保守：阈值高，少抢占
    }

    // ============ 状态 ============

    private val runningTasks = ConcurrentHashMap<String, RunningTask>()
    private val _runningPriority = MutableStateFlow(Int.MAX_VALUE)  // 当前最低优先级
    val runningPriority: StateFlow<Int> = _runningPriority.asStateFlow()

    private val _preemptionEvents = MutableStateFlow<List<PreemptionEvent>>(emptyList())
    val preemptionEvents: StateFlow<List<PreemptionEvent>> = _preemptionEvents.asStateFlow()

    private val recentEvents = mutableListOf<PreemptionEvent>()
    private var policy = PreemptionPolicy.BALANCED
    private var isBerserkMode = false

    // 检查点保存/恢复回调
    var checkpointSaver: (suspend (taskId: String) -> String)? = null
    var checkpointRestorer: (suspend (taskId: String, checkpoint: String) -> Unit)? = null
    var taskPauser: (suspend (taskId: String) -> Unit)? = null
    var taskResumer: (suspend (taskId: String) -> Unit)? = null

    // ============ 公共 API ============

    /**
     * 设置抢占策略
     */
    fun setPolicy(newPolicy: PreemptionPolicy) {
        policy = newPolicy
    }

    /**
     * 设置狂暴模式（影响抢占行为）
     */
    fun setBerserkMode(enabled: Boolean) {
        isBerserkMode = enabled
    }

    /**
     * 注册任务开始运行
     */
    fun onTaskStarted(taskId: String, priority: Int, skillId: String, deadline: Long? = null) {
        runningTasks[taskId] = RunningTask(
            taskId = taskId, priority = priority, startedAt = System.currentTimeMillis(),
            deadline = deadline, skillId = skillId
        )
        updateRunningPriority()
    }

    /**
     * 注册任务结束
     */
    fun onTaskFinished(taskId: String) {
        runningTasks.remove(taskId)
        updateRunningPriority()
    }

    /**
     * 检查是否应该抢占
     *
     * @param newTaskPriority 新任务的优先级
     * @param newTaskDeadline 新任务的截止时间
     * @return 需要被抢占的任务列表
     */
    suspend fun checkPreemption(
        newTaskId: String,
        newTaskPriority: Int,
        newTaskDeadline: Long? = null
    ): List<String> {
        if (runningTasks.isEmpty()) return emptyList()

        val threshold = when (policy) {
            PreemptionPolicy.AGGRESSIVE -> preemptionThreshold / 2
            PreemptionPolicy.BALANCED -> preemptionThreshold
            PreemptionPolicy.CONSERVATIVE -> preemptionThreshold * 2
        }

        val toPreempt = mutableListOf<String>()

        // 狂暴模式：抢占所有低优任务
        if (isBerserkMode) {
            runningTasks.values
                .filter { it.priority < newTaskPriority && !it.isPreempted }
                .sortedBy { it.priority }
                .forEach { task ->
                    if (preempt(task.taskId, newTaskId, PreemptionReason.RAGE_PREEMPT, newTaskPriority - task.priority)) {
                        toPreempt.add(task.taskId)
                    }
                }
            return toPreempt
        }

        // 优先级抢占
        runningTasks.values
            .filter { it.priority + threshold < newTaskPriority && !it.isPreempted }
            .sortedBy { it.priority }
            .forEach { task ->
                if (preempt(task.taskId, newTaskId, PreemptionReason.PRIORITY_PREEMPT, newTaskPriority - task.priority)) {
                    toPreempt.add(task.taskId)
                }
            }

        // 截止时间抢占
        if (newTaskDeadline != null) {
            val timeToDeadline = newTaskDeadline - System.currentTimeMillis()
            if (timeToDeadline < 30_000) {  // 30 秒内 deadline
                runningTasks.values
                    .filter { !it.isPreempted && it.deadline == null }
                    .sortedBy { it.priority }
                    .take(1)
                    .forEach { task ->
                        if (preempt(task.taskId, newTaskId, PreemptionReason.DEADLINE_PREEMPT, newTaskPriority - task.priority)) {
                            toPreempt.add(task.taskId)
                        }
                    }
            }
        }

        return toPreempt
    }

    /**
     * 手动抢占任务
     */
    suspend fun manualPreempt(taskId: String, byTaskId: String): Boolean {
        return preempt(taskId, byTaskId, PreemptionReason.MANUAL_PREEMPT, 0)
    }

    /**
     * 恢复被抢占的任务
     */
    suspend fun resumePreempted(taskId: String): Boolean {
        val task = runningTasks[taskId] ?: return false
        if (!task.isPreempted) return false

        // 恢复检查点
        val checkpoint = task.checkpoint
        if (checkpoint != null) {
            checkpointRestorer?.invoke(taskId, checkpoint)
        }
        // 恢复执行
        taskResumer?.invoke(taskId)

        runningTasks[taskId] = task.copy(isPreempted = false, preemptedAt = null, checkpoint = null)
        return true
    }

    /**
     * 恢复所有被抢占的任务
     */
    suspend fun resumeAllPreempted(): Int {
        val toResume = runningTasks.values.filter { it.isPreempted }.map { it.taskId }
        for (taskId in toResume) {
            resumePreempted(taskId)
        }
        return toResume.size
    }

    /**
     * 获取运行中任务
     */
    fun getRunningTasks(): List<RunningTask> = runningTasks.values.toList()

    /**
     * 获取被抢占的任务
     */
    fun getPreemptedTasks(): List<RunningTask> = runningTasks.values.filter { it.isPreempted }.toList()

    /**
     * 获取统计
     */
    fun getStats(): PreemptionStats {
        return PreemptionStats(
            totalRunning = runningTasks.size,
            totalPreempted = runningTasks.values.count { it.isPreempted },
            totalPreemptions = recentEvents.size,
            preemptionsByReason = recentEvents.groupingBy { it.reason }.eachCount()
        )
    }

    data class PreemptionStats(
        val totalRunning: Int,
        val totalPreempted: Int,
        val totalPreemptions: Int,
        val preemptionsByReason: Map<PreemptionReason, Int>
    )

    // ============ 内部方法 ============

    private suspend fun preempt(
        taskId: String,
        preemptingTaskId: String,
        reason: PreemptionReason,
        priorityDiff: Int
    ): Boolean {
        val task = runningTasks[taskId] ?: return false
        if (task.isPreempted) return false

        // 保存检查点
        val checkpoint = checkpointSaver?.invoke(taskId)

        // 暂停任务
        taskPauser?.invoke(taskId)

        runningTasks[taskId] = task.copy(
            isPreempted = true,
            preemptedAt = System.currentTimeMillis(),
            checkpoint = checkpoint
        )

        // 记录事件
        val event = PreemptionEvent(taskId, preemptingTaskId, reason, priorityDiff)
        recentEvents.add(event)
        while (recentEvents.size > 100) recentEvents.removeAt(0)
        _preemptionEvents.value = recentEvents.toList()

        return true
    }

    private fun updateRunningPriority() {
        _runningPriority.value = runningTasks.values.minOfOrNull { it.priority } ?: Int.MAX_VALUE
    }
}
