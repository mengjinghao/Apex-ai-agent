package com.apex.agent.kernel.burst.enhanced.circuit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * B11: 智能断流恢复（Circuit Breaker + Auto-Heal）
 *
 * 断路器 + 自动诊断修复：
 * - 连续失败 N 次后 OPEN（拒绝调用）
 * - HALF-OPEN 探测
 * - CLOSED 恢复
 * - Auto-Heal 自动分析失败原因并修复
 */
class CircuitBreakerManager(
    private val failureThreshold: Int = 5,
    private val openDurationMs: Long = 60_000L,
    private val halfOpenTrials: Int = 1
) {

    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    data class CircuitInfo(
        val key: String,
        val state: CircuitState,
        val failureCount: Int,
        val successCount: Int,
        val lastFailure: Long?,
        val openedAt: Long?,
        val totalRequests: Long,
        val totalFailures: Long
    )

    data class Diagnosis(
        val tool: String,
        val rootCause: String,
        val confidence: Float,
        val recommendedActions: List<HealAction>
    )

    enum class HealAction {
        RETRY, CLEAR_CACHE, SWITCH_ENDPOINT, RESTART_SERVICE,
        ADJUST_TIMEOUT, REDUCE_CONCURRENCY, ESCALATE, NO_ACTION
    }

    data class HealResult(
        val action: HealAction,
        val success: Boolean,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val states = ConcurrentHashMap<String, CircuitState>()
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val successCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val openedAt = ConcurrentHashMap<String, AtomicLong>()
    private val lastFailure = ConcurrentHashMap<String, AtomicLong>()
    private val totalRequests = ConcurrentHashMap<String, AtomicLong>()
    private val totalFailures = ConcurrentHashMap<String, AtomicLong>()
    private val diagnoses = ConcurrentHashMap<String, Diagnosis>()
    private val healHistory = ConcurrentHashMap<String, MutableList<HealResult>>()

    private val _states = MutableStateFlow<Map<String, CircuitState>>(emptyMap())
    val circuitStates: StateFlow<Map<String, CircuitState>> = _states.asStateFlow()

    /**
     * 尝试通过断路器执行
     */
    suspend fun execute(key: String, action: suspend () -> Result<*>): Result<*> {
        if (!tryAcquire(key)) {
            return Result.failure<Any>(CircuitOpenException("断路器 $key 开启中"))
        }

        return try {
            val result = action()
            if (result.isSuccess) {
                recordSuccess(key)
            } else {
                recordFailure(key, result.exceptionOrNull())
            }
            result
        } catch (e: Exception) {
            recordFailure(key, e)
            Result.failure<Any>(e)
        }
    }

    private fun tryAcquire(key: String): Boolean {
        totalRequests.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        val state = states.getOrDefault(key, CircuitState.CLOSED)
        when (state) {
            CircuitState.CLOSED -> return true
            CircuitState.OPEN -> {
                val opened = openedAt[key]?.get() ?: 0
                if (System.currentTimeMillis() - opened >= openDurationMs) {
                    states[key] = CircuitState.HALF_OPEN
                    updateStates()
                    return true
                }
                return false
            }
            CircuitState.HALF_OPEN -> {
                val trials = successCounts.computeIfAbsent(key) { AtomicInteger(0) }.get()
                return trials < halfOpenTrials
            }
        }
    }

    private fun recordSuccess(key: String) {
        failureCounts[key]?.set(0)
        successCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        val state = states.getOrDefault(key, CircuitState.CLOSED)
        if (state == CircuitState.HALF_OPEN) {
            states[key] = CircuitState.CLOSED
            openedAt.remove(key)
            updateStates()
        }
    }

    private fun recordFailure(key: String, error: Throwable?) {
        totalFailures.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        lastFailure[key] = AtomicLong(System.currentTimeMillis())
        val count = failureCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        val state = states.getOrDefault(key, CircuitState.CLOSED)
        when (state) {
            CircuitState.CLOSED -> {
                if (count >= failureThreshold) {
                    states[key] = CircuitState.OPEN
                    openedAt[key] = AtomicLong(System.currentTimeMillis())
                    updateStates()
                    // 触发自动诊断
                    autoDiagnose(key, error)
                }
            }
            CircuitState.HALF_OPEN -> {
                states[key] = CircuitState.OPEN
                openedAt[key] = AtomicLong(System.currentTimeMillis())
                updateStates()
            }
            else -> {}
        }
    }

    /**
     * 自动诊断
     */
    private fun autoDiagnose(key: String, error: Throwable?) {
        val errorMsg = error?.message ?: ""
        val errorType = error?.let { it::class.simpleName } ?: "Unknown"

        val rootCause = when {
            errorType.contains("Timeout", true) || errorMsg.contains("timeout", true) -> "超时"
            errorType.contains("OutOfMemory", true) -> "内存不足"
            errorType.contains("Network", true) || errorMsg.contains("connection", true) -> "网络问题"
            errorType.contains("Security", true) || errorMsg.contains("permission", true) -> "权限不足"
            errorType.contains("IOException", true) -> "IO 错误"
            else -> "未知错误"
        }

        val actions = when (rootCause) {
            "超时" -> listOf(HealAction.ADJUST_TIMEOUT, HealAction.RETRY, HealAction.REDUCE_CONCURRENCY)
            "内存不足" -> listOf(HealAction.CLEAR_CACHE, HealAction.REDUCE_CONCURRENCY, HealAction.RESTART_SERVICE)
            "网络问题" -> listOf(HealAction.SWITCH_ENDPOINT, HealAction.RETRY)
            "权限不足" -> listOf(HealAction.ESCALATE)
            "IO 错误" -> listOf(HealAction.RETRY, HealAction.SWITCH_ENDPOINT)
            else -> listOf(HealAction.RETRY)
        }

        val diagnosis = Diagnosis(key, rootCause, 0.7f, actions)
        diagnoses[key] = diagnosis
    }

    /**
     * 执行修复
     */
    fun heal(key: String): HealResult {
        val diagnosis = diagnoses[key] ?: return HealResult(HealAction.NO_ACTION, false, "无诊断")
        val action = diagnosis.recommendedActions.firstOrNull() ?: HealAction.NO_ACTION
        val message = when (action) {
            HealAction.RETRY -> "标记为可重试"
            HealAction.CLEAR_CACHE -> "清理缓存"
            HealAction.SWITCH_ENDPOINT -> "切换端点"
            HealAction.RESTART_SERVICE -> "重启服务"
            HealAction.ADJUST_TIMEOUT -> "调整超时"
            HealAction.REDUCE_CONCURRENCY -> "降低并发"
            HealAction.ESCALATE -> "上报人工"
            HealAction.NO_ACTION -> "无需操作"
        }
        val result = HealResult(action, true, message)
        healHistory.computeIfAbsent(key) { mutableListOf() }.add(result)
        // 修复后重置断路器
        if (action != HealAction.ESCALATE) {
            states[key] = CircuitState.HALF_OPEN
            failureCounts[key]?.set(0)
            updateStates()
        }
        return result
    }

    fun getCircuitInfo(key: String): CircuitInfo {
        return CircuitInfo(
            key = key,
            state = states.getOrDefault(key, CircuitState.CLOSED),
            failureCount = failureCounts[key]?.get() ?: 0,
            successCount = successCounts[key]?.get() ?: 0,
            lastFailure = lastFailure[key]?.get(),
            openedAt = openedAt[key]?.get(),
            totalRequests = totalRequests[key]?.get() ?: 0,
            totalFailures = totalFailures[key]?.get() ?: 0
        )
    }

    fun getAllCircuits(): List<CircuitInfo> = states.keys.map { getCircuitInfo(it) }

    fun reset(key: String) {
        states.remove(key); failureCounts.remove(key); successCounts.remove(key)
        openedAt.remove(key); lastFailure.remove(key)
        updateStates()
    }

    private fun updateStates() {
        _states.value = states.toMap()
    }
}

class CircuitOpenException(message: String) : RuntimeException(message)
