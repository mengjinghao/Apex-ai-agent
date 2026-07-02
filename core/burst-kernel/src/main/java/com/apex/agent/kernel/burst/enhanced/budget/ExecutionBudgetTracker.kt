package com.apex.agent.kernel.burst.enhanced.budget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B44: 执行预算追踪器
 *
 * 每个任务的资源预算追踪：
 * - Token 预算
 * - 时间预算
 * - API 调用次数预算
 * - 超预算处理
 */
class ExecutionBudgetTracker {

    data class Budget(
        val taskId: String,
        val maxTokens: Long = 100_000L,
        val maxDurationMs: Long = 300_000L,
        val maxApiCalls: Int = 50,
        val maxRetries: Int = 10,
        val maxMemoryMb: Int = 256
    )

    data class BudgetUsage(
        val taskId: String,
        val tokensUsed: Long,
        val durationMs: Long,
        val apiCallsMade: Int,
        val retriesUsed: Int,
        val memoryUsedMb: Int
    )

    data class BudgetStatus(
        val taskId: String,
        val budget: Budget,
        val usage: BudgetUsage,
        val tokenUsagePercent: Float,
        val timeUsagePercent: Float,
        val apiUsagePercent: Float,
        val isExceeded: Boolean,
        val exceededDimensions: Set<String>,
        val warningLevel: WarningLevel
    )

    enum class WarningLevel { SAFE, WARNING, CRITICAL, EXCEEDED }

    enum class ExceedAction { ABORT, THROTTLE, WARN_CONTINUE, REQUEST_EXTENSION }

    private val budgets = ConcurrentHashMap<String, Budget>()
    private val usage = ConcurrentHashMap<String, BudgetUsage>()
    private val exceedActions = ConcurrentHashMap<String, ExceedAction>()
    private val startTimes = ConcurrentHashMap<String, Long>()
    private val tokenUsage = ConcurrentHashMap<String, AtomicLong>()
    private val apiCallCounts = ConcurrentHashMap<String, AtomicLong>()
    private val retryCounts = ConcurrentHashMap<String, AtomicLong>()
    private val _exceededTasks = MutableStateFlow<Set<String>>(emptySet())
    val exceededTasks: StateFlow<Set<String>> = _exceededTasks.asStateFlow()

    fun setBudget(taskId: String, budget: Budget) {
        budgets[taskId] = budget
        usage[taskId] = BudgetUsage(taskId, 0, 0, 0, 0, 0)
        startTimes[taskId] = System.currentTimeMillis()
        tokenUsage[taskId] = AtomicLong(0)
        apiCallCounts[taskId] = AtomicLong(0)
        retryCounts[taskId] = AtomicLong(0)
    }

    fun setExceedAction(taskId: String, action: ExceedAction) {
        exceedActions[taskId] = action
    }

    fun recordTokens(taskId: String, tokens: Long): Boolean {
        val budget = budgets[taskId] ?: return true
        val used = tokenUsage[taskId]?.addAndGet(tokens) ?: tokens
        updateUsage(taskId)
        return used <= budget.maxTokens
    }

    fun recordApiCall(taskId: String): Boolean {
        val budget = budgets[taskId] ?: return true
        val count = apiCallCounts[taskId]?.incrementAndGet() ?: 1
        updateUsage(taskId)
        return count <= budget.maxApiCalls
    }

    fun recordRetry(taskId: String): Boolean {
        val budget = budgets[taskId] ?: return true
        val count = retryCounts[taskId]?.incrementAndGet() ?: 1
        updateUsage(taskId)
        return count <= budget.maxRetries
    }

    fun recordMemory(taskId: String, memoryMb: Int) {
        val current = usage[taskId] ?: return
        usage[taskId] = current.copy(memoryUsedMb = memoryMb)
    }

    fun checkBudget(taskId: String): BudgetStatus? {
        val budget = budgets[taskId] ?: return null
        val start = startTimes[taskId] ?: return null
        val used = usage[taskId] ?: BudgetUsage(taskId, 0, 0, 0, 0, 0)

        val elapsed = System.currentTimeMillis() - start
        val actualUsage = used.copy(
            tokensUsed = tokenUsage[taskId]?.get() ?: 0,
            durationMs = elapsed,
            apiCallsMade = (apiCallCounts[taskId]?.get() ?: 0).toInt(),
            retriesUsed = (retryCounts[taskId]?.get() ?: 0).toInt()
        )

        val tokenPct = if (budget.maxTokens > 0) actualUsage.tokensUsed.toFloat() / budget.maxTokens else 0f
        val timePct = if (budget.maxDurationMs > 0) actualUsage.durationMs.toFloat() / budget.maxDurationMs else 0f
        val apiPct = if (budget.maxApiCalls > 0) actualUsage.apiCallsMade.toFloat() / budget.maxApiCalls else 0f

        val exceeded = mutableSetOf<String>()
        if (actualUsage.tokensUsed > budget.maxTokens) exceeded.add("tokens")
        if (elapsed > budget.maxDurationMs) exceeded.add("time")
        if (actualUsage.apiCallsMade > budget.maxApiCalls) exceeded.add("apiCalls")
        if (actualUsage.retriesUsed > budget.maxRetries) exceeded.add("retries")
        if (actualUsage.memoryUsedMb > budget.maxMemoryMb) exceeded.add("memory")

        val maxPct = maxOf(tokenPct, timePct, apiPct)
        val warning = when {
            exceeded.isNotEmpty() -> WarningLevel.EXCEEDED
            maxPct > 0.9f -> WarningLevel.CRITICAL
            maxPct > 0.7f -> WarningLevel.WARNING
            else -> WarningLevel.SAFE
        }

        if (exceeded.isNotEmpty()) {
            val current = _exceededTasks.value
            if (taskId !in current) _exceededTasks.value = current + taskId
        }

        return BudgetStatus(taskId, budget, actualUsage, tokenPct, timePct, apiPct, exceeded.isNotEmpty(), exceeded, warning)
    }

    fun shouldAbort(taskId: String): Boolean {
        val status = checkBudget(taskId) ?: return false
        if (!status.isExceeded) return false
        val action = exceedActions[taskId] ?: ExceedAction.WARN_CONTINUE
        return action == ExceedAction.ABORT
    }

    fun extendBudget(taskId: String, extraTokens: Long = 0, extraDurationMs: Long = 0, extraApiCalls: Int = 0): Boolean {
        val current = budgets[taskId] ?: return false
        budgets[taskId] = current.copy(
            maxTokens = current.maxTokens + extraTokens,
            maxDurationMs = current.maxDurationMs + extraDurationMs,
            maxApiCalls = current.maxApiCalls + extraApiCalls
        )
        val exceeded = _exceededTasks.value - taskId
        _exceededTasks.value = exceeded
        return true
    }

    fun clear(taskId: String) {
        budgets.remove(taskId)
        usage.remove(taskId)
        startTimes.remove(taskId)
        tokenUsage.remove(taskId)
        apiCallCounts.remove(taskId)
        retryCounts.remove(taskId)
        _exceededTasks.value = _exceededTasks.value - taskId
    }

    private fun updateUsage(taskId: String) {
        val current = usage[taskId] ?: return
        usage[taskId] = current.copy(
            tokensUsed = tokenUsage[taskId]?.get() ?: 0,
            apiCallsMade = (apiCallCounts[taskId]?.get() ?: 0).toInt(),
            retriesUsed = (retryCounts[taskId]?.get() ?: 0).toInt()
        )
    }
}
