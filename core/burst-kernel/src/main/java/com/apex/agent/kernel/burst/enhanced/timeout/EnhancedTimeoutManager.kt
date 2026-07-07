package com.apex.agent.kernel.burst.enhanced.timeout

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B27: 超时管理增强
 *
 * 增强现有 TimeoutManager：
 * - 分级超时（硬/软/弹性）
 * - 超时预警
 * - 自适应超时
 * - 超时级联取消
 */
class EnhancedTimeoutManager {

    enum class TimeoutType {
        HARD,       // 硬超时：立即取消
        SOFT,       // 软超时：预警但不取消
        ELASTIC,    // 弹性超时：可延长
        CASCADING   // 级联超时：取消子任务
    }

    data class TimeoutRule(
        val key: String,
        val type: TimeoutType,
        val baseTimeoutMs: Long,
        val maxTimeoutMs: Long = baseTimeoutMs * 3,
        val warningThreshold: Float = 0.8f,  // 80% 预警
        val canExtend: Boolean = type == TimeoutType.ELASTIC
    )

    data class TimeoutContext(
        val taskId: String,
        val rule: TimeoutRule,
        val startedAt: Long,
        val deadline: Long,
        val warningAt: Long,
        val extendedCount: Int = 0,
        val isWarningSent: Boolean = false
    )

    data class TimeoutEvent(
        val taskId: String,
        val type: TimeoutEventType,
        val elapsedMs: Long,
        val timeoutMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class TimeoutEventType { WARNING, TIMEOUT, EXTENDED, CANCELLED }

    private val rules = ConcurrentHashMap<String, TimeoutRule>()
    private val activeTimeouts = ConcurrentHashMap<String, TimeoutContext>()
    private val eventHistory = mutableListOf<TimeoutEvent>()
    private val executionTimes = ConcurrentHashMap<String, MutableList<Long>>()  // key -> 历史耗时
    private var checkJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val onTimeout: (String) -> Unit = {}
    private val onWarning: (String) -> Unit = {}

    /**
     * 注册超时规则
     */
    fun registerRule(rule: TimeoutRule) {
        rules[rule.key] = rule
    }

    /**
     * 开始超时计时
     */
    fun startTimeout(taskId: String, ruleKey: String): TimeoutContext? {
        val rule = rules[ruleKey] ?: return null
        val now = System.currentTimeMillis()
        val adaptedTimeout = adaptTimeout(ruleKey, rule.baseTimeoutMs)
        val context = TimeoutContext(
            taskId = taskId, rule = rule.copy(baseTimeoutMs = adaptedTimeout),
            startedAt = now, deadline = now + adaptedTimeout,
            warningAt = now + (adaptedTimeout * rule.warningThreshold).toLong()
        )
        activeTimeouts[taskId] = context
        return context
    }

    /**
     * 延长超时
     */
    fun extendTimeout(taskId: String, extendMs: Long): Boolean {
        val context = activeTimeouts[taskId] ?: return false
        if (!context.rule.canExtend) return false
        if (context.extendedCount >= 3) return false  // 最多延长 3 次

        val newDeadline = context.deadline + extendMs
        if (newDeadline > context.startedAt + context.rule.maxTimeoutMs) return false

        activeTimeouts[taskId] = context.copy(
            deadline = newDeadline,
            warningAt = newDeadline - (context.rule.baseTimeoutMs * (1 - context.rule.warningThreshold)).toLong(),
            extendedCount = context.extendedCount + 1
        )
        emitEvent(taskId, TimeoutEventType.EXTENDED, System.currentTimeMillis() - context.startedAt, context.rule.baseTimeoutMs)
        return true
    }

    /**
     * 完成超时计时
     */
    fun finishTimeout(taskId: String, ruleKey: String) {
        val context = activeTimeouts.remove(taskId) ?: return
        val elapsed = System.currentTimeMillis() - context.startedAt
        executionTimes.computeIfAbsent(ruleKey) { mutableListOf() }.apply {
            add(elapsed)
            while (size > 100) removeAt(0)
        }
    }

    /**
     * 取消超时计时
     */
    fun cancelTimeout(taskId: String) {
        activeTimeouts.remove(taskId)
        emitEvent(taskId, TimeoutEventType.CANCELLED, 0, 0)
    }

    /**
     * 检查超时
     */
    fun checkTimeouts(): List<TimeoutEvent> {
        val now = System.currentTimeMillis()
        val events = mutableListOf<TimeoutEvent>()

        for ((taskId, context) in activeTimeouts) {
            val elapsed = now - context.startedAt

            // 预警
            if (!context.isWarningSent && now >= context.warningAt) {
                activeTimeouts[taskId] = context.copy(isWarningSent = true)
                events.add(TimeoutEvent(taskId, TimeoutEventType.WARNING, elapsed, context.rule.baseTimeoutMs))
                emitEvent(taskId, TimeoutEventType.WARNING, elapsed, context.rule.baseTimeoutMs)
            }

            // 超时
            if (now >= context.deadline) {
                events.add(TimeoutEvent(taskId, TimeoutEventType.TIMEOUT, elapsed, context.rule.baseTimeoutMs))
                emitEvent(taskId, TimeoutEventType.TIMEOUT, elapsed, context.rule.baseTimeoutMs)
                activeTimeouts.remove(taskId)

                // 级联取消
                if (context.rule.type == TimeoutType.CASCADING) {
                    // 取消所有子任务（简化：取消所有活跃任务）
                    // 实际应通过依赖图确定子任务
                }
            }
        }

        return events
    }

    /**
     * 获取剩余时间
     */
    fun getRemainingTime(taskId: String): Long? {
        val context = activeTimeouts[taskId] ?: return null
        return (context.deadline - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /**
     * 自适应超时
     */
    private fun adaptTimeout(ruleKey: String, baseTimeout: Long): Long {
        val history = executionTimes[ruleKey] ?: return baseTimeout
        if (history.size < 5) return baseTimeout

        val avg = history.average()
        val p95 = history.sorted().drop(history.size * 95 / 100).firstOrNull() ?: avg

        // 超时 = P95 * 1.5，但不超过 maxTimeout
        val adapted = (p95 * 1.5).toLong()
        val rule = rules[ruleKey]
        val maxTimeout = rule?.maxTimeoutMs ?: baseTimeout * 3
        return adapted.coerceIn(baseTimeout, maxTimeout)
    }

    /**
     * 获取统计
     */
    fun getStats(): TimeoutStats {
        val history = executionTimes.flatMap { it.value }
        return TimeoutStats(
            activeTimeouts = activeTimeouts.size,
            totalRules = rules.size,
            avgExecutionTime = if (history.isNotEmpty()) history.average().toLong() else 0,
            totalEvents = eventHistory.size,
            timeoutCount = eventHistory.count { it.type == TimeoutEventType.TIMEOUT },
            warningCount = eventHistory.count { it.type == TimeoutEventType.WARNING }
        )
    }

    data class TimeoutStats(
        val activeTimeouts: Int,
        val totalRules: Int,
        val avgExecutionTime: Long,
        val totalEvents: Int,
        val timeoutCount: Int,
        val warningCount: Int
    )

    private fun emitEvent(taskId: String, type: TimeoutEventType, elapsedMs: Long, timeoutMs: Long) {
        eventHistory.add(TimeoutEvent(taskId, type, elapsedMs, timeoutMs))
        while (eventHistory.size > 500) eventHistory.removeAt(0)
    }

    /**
     * 启动检查循环
     */
    fun startChecking(intervalMs: Long = 1000L) {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                checkTimeouts()
                delay(intervalMs)
            }
        }
    }

    fun stopChecking() {
        checkJob?.cancel()
    }
}
