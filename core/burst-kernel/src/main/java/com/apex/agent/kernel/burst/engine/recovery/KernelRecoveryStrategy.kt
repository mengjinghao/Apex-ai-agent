package com.apex.agent.kernel.burst.engine.recovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * E8: 内核恢复策略
 *
 * 崩溃恢复与自动重启：
 * - 崩溃检测
 * - 自动重启（指数退避）
 * - 状态恢复
 * - 恢复历史
 */
class KernelRecoveryStrategy(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxRestartAttempts: Int = 5,
    private val initialDelayMs: Long = 2000L,
    private val maxDelayMs: Long = 120_000L
) {

    enum class RecoveryState { IDLE, DETECTING, RECOVERING, RESTARTING, RECOVERED, FAILED }

    data class RecoveryEvent(
        val type: RecoveryEventType,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class RecoveryEventType {
        CRASH_DETECTED, RECOVERY_STARTED, RESTART_ATTEMPT,
        RESTART_SUCCESS, RESTART_FAILED, STATE_RESTORED, GIVE_UP
    }

    data class CrashRecord(
        val crashId: String,
        val timestamp: Long,
        val reason: String,
        val stackTrace: String?,
        val recoveryAction: String
    )

    private val _state = MutableStateFlow(RecoveryState.IDLE)
    val state: StateFlow<RecoveryState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<RecoveryEvent>>(emptyList())
    val events: StateFlow<List<RecoveryEvent>> = _events.asStateFlow()

    private val recentEvents = mutableListOf<RecoveryEvent>()
    private val crashHistory = mutableListOf<CrashRecord>()
    private val restartAttempts = AtomicInteger(0)
    private var watchdogJob: kotlinx.coroutines.Job? = null

    var restartHandler: (suspend () -> Boolean)? = null
    var stateSaveHandler: (() -> Map<String, Any>)? = null
    var stateRestoreHandler: ((Map<String, Any>) -> Unit)? = null

    private var lastKnownState: Map<String, Any> = emptyMap()

    /**
     * 启动看门狗
     */
    fun startWatchdog(checkIntervalMs: Long = 10_000L, healthCheck: () -> Boolean) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(checkIntervalMs)
                if (!healthCheck()) {
                    handleCrash("看门狗检测到内核不健康")
                }
            }
        }
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
    }

    /**
     * 处理崩溃
     */
    suspend fun handleCrash(reason: String, stackTrace: String? = null) {
        _state.value = RecoveryState.DETECTING
        emitEvent(RecoveryEventType.CRASH_DETECTED, "崩溃: $reason")

        val crashId = "crash_${System.currentTimeMillis()}"
        crashHistory.add(CrashRecord(crashId, System.currentTimeMillis(), reason, stackTrace, "自动恢复"))
        while (crashHistory.size > 20) crashHistory.removeAt(0)

        // 保存状态
        _state.value = RecoveryState.RECOVERING
        emitEvent(RecoveryEventType.RECOVERY_STARTED, "开始恢复")
        stateSaveHandler?.let { lastKnownState = it() }

        // 尝试重启
        _state.value = RecoveryState.RESTARTING
        val attempt = restartAttempts.incrementAndGet()
        if (attempt > maxRestartAttempts) {
            emitEvent(RecoveryEventType.GIVE_UP, "超过最大重启次数 $maxRestartAttempts")
            _state.value = RecoveryState.FAILED
            return
        }

        val delayMs = calculateBackoff(attempt)
        emitEvent(RecoveryEventType.RESTART_ATTEMPT, "第 $attempt 次重启（延迟 ${delayMs}ms）")
        delay(delayMs)

        val handler = restartHandler
        if (handler != null) {
            val success = try { handler() } catch (e: Exception) { false }
            if (success) {
                // 恢复状态
                stateRestoreHandler?.let { it(lastKnownState) }
                emitEvent(RecoveryEventType.STATE_RESTORED, "状态已恢复")
                emitEvent(RecoveryEventType.RESTART_SUCCESS, "重启成功")
                _state.value = RecoveryState.RECOVERED
                restartAttempts.set(0)
            } else {
                emitEvent(RecoveryEventType.RESTART_FAILED, "重启失败")
                handleCrash("重启失败，重试")
            }
        } else {
            emitEvent(RecoveryEventType.RESTART_FAILED, "无重启处理器")
            _state.value = RecoveryState.FAILED
        }
    }

    fun reset() {
        restartAttempts.set(0)
        _state.value = RecoveryState.IDLE
    }

    fun getCrashHistory(): List<CrashRecord> = crashHistory.toList()

    private fun calculateBackoff(attempt: Int): Long {
        val delay = initialDelayMs * (1L shl (attempt - 1))
        return delay.coerceAtMost(maxDelayMs)
    }

    private fun emitEvent(type: RecoveryEventType, message: String) {
        recentEvents.add(RecoveryEvent(type, message))
        while (recentEvents.size > 100) recentEvents.removeAt(0)
        _events.value = recentEvents.toList()
    }

    fun shutdown() {
        watchdogJob?.cancel()
        scope.cancel()
    }
}
