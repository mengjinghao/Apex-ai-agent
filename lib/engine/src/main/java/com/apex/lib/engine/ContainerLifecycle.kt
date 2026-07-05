package com.apex.lib.engine

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 容器生命周期状态。
 *
 * 与 [ContainerStatusInfo.statusCode] 的映射：
 *   - [STOPPED] ← 0
 *   - [STARTING] ← 1
 *   - [RUNNING] ← 2
 *   - [ERROR] ← -1
 *
 * [STOPPING] 是 lib 层引入的瞬态，用于表达「停止请求已下发但底层尚未确认」。
 */
enum class ContainerState(val displayName: String) {
    STOPPED("已停止"),
    STARTING("启动中"),
    RUNNING("运行中"),
    STOPPING("停止中"),
    ERROR("异常")
}

/**
 * 容器生命周期管理动作。
 */
enum class ContainerAction(val displayName: String) {
    START("启动"),
    STOP("停止"),
    RESTART("重启")
}

/**
 * 容器生命周期事件。
 *
 * @property from 迁移前状态
 * @property to 迁移后状态
 * @property reason 迁移原因（人类可读）
 * @property timestamp 事件时间戳（毫秒）
 */
data class ContainerLifecycleEvent(
    val from: ContainerState,
    val to: ContainerState,
    val reason: String,
    val timestamp: Long
)

/**
 * 容器生命周期状态机。
 *
 * 职责：
 *   - 维护当前 [ContainerState]
 *   - 暴露状态查询 API（[isRunning] / [isStarting] / ...）
 *   - 暴露 [events] 流，供 UI / 日志订阅
 *   - 在 [EngineOrchestrator] 调用动作前/后驱动状态迁移
 *
 * 线程安全：内部使用 [MutableStateFlow] + [MutableSharedFlow]，可安全跨协程访问。
 */
class ContainerLifecycle {

    private val _state = MutableStateFlow(ContainerState.STOPPED)
    val state: StateFlow<ContainerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ContainerLifecycleEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ContainerLifecycleEvent> = _events.asSharedFlow()

    /** 当前状态。 */
    fun current(): ContainerState = _state.value

    /** 是否运行中。 */
    fun isRunning(): Boolean = _state.value == ContainerState.RUNNING

    /** 是否启动中。 */
    fun isStarting(): Boolean = _state.value == ContainerState.STARTING

    /** 是否已停止。 */
    fun isStopped(): Boolean = _state.value == ContainerState.STOPPED

    /** 是否停止中。 */
    fun isStopping(): Boolean = _state.value == ContainerState.STOPPING

    /** 是否异常。 */
    fun isError(): Boolean = _state.value == ContainerState.ERROR

    /**
     * 根据底层 [ContainerStatusInfo.statusCode] 同步当前状态。
     */
    fun syncFromStatusCode(code: Int) {
        val target = when (code) {
            0 -> ContainerState.STOPPED
            1 -> ContainerState.STARTING
            2 -> ContainerState.RUNNING
            -1 -> ContainerState.ERROR
            else -> {
                ApexLog.w(ApexSuite.ApkId.ENGINE, "[Lifecycle] unknown statusCode=$code, keep ${_state.value}")
                return
            }
        }
        transitionTo(target, "sync from statusCode=$code")
    }

    /** 启动动作：进入 STARTING。 */
    fun onActionStart() = transitionTo(ContainerState.STARTING, "start requested")

    /** 停止动作：进入 STOPPING（等待底层确认后回 STOPPED）。 */
    fun onActionStop() = transitionTo(ContainerState.STOPPING, "stop requested")

    /** 重启动作：进入 STARTING。 */
    fun onActionRestart() = transitionTo(ContainerState.STARTING, "restart requested")

    /** 异常：进入 ERROR。 */
    fun onActionError(reason: String) = transitionTo(ContainerState.ERROR, reason)

    /** 恢复：进入 STOPPED（用于停止成功后清理 STOPPING）。 */
    fun onActionRecovered() = transitionTo(ContainerState.STOPPED, "recovered")

    /** 强制重置到指定状态（仅供测试 / 异常恢复使用）。 */
    fun resetTo(state: ContainerState, reason: String = "manual reset") {
        transitionTo(state, reason)
    }

    private fun transitionTo(newState: ContainerState, reason: String) {
        val old = _state.value
        if (old == newState) return
        _state.value = newState
        val event = ContainerLifecycleEvent(old, newState, reason, System.currentTimeMillis())
        _events.tryEmit(event)
        ApexLog.i(ApexSuite.ApkId.ENGINE, "[Lifecycle] ${old.name} -> ${newState.name} ($reason)")
    }
}
