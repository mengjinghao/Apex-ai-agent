package com.apex.agent.kernel.burst.enhanced.rage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * B1: 暴怒值/能量系统（Rage Meter & Energy System）
 *
 * 狂暴模式的核心特征 —— 让 AI "愤怒"起来：
 * - 任务失败/超时/取消时积累暴怒值
 * - 暴怒值达阈值触发"狂暴状态"（maxConcurrency×2 + 关闭熔断 + 启用推测执行）
 * - 能量系统约束狂暴持续时间（避免 OOM）
 * - 暴怒状态自动衰减（冷静机制）
 *
 * 状态转换：CALM → AGITATED → BERSERK → EXHAUSTED → CALM
 */
class RageMeter(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val decayIntervalMs: Long = 10_000L,      // 10 秒衰减一次
    private val berserkerThreshold: Int = 70,          // 暴怒值 ≥ 70 进入狂暴
    private val agitatedThreshold: Int = 40,           // 暴怒值 ≥ 40 进入激动
    private val exhaustionThreshold: Int = 10,         // 能量 ≤ 10 进入力竭
    private val maxRage: Int = 100,
    private val maxEnergy: Int = 1000,
    private val berserkEnergyCostPerSec: Int = 5,      // 狂暴每秒消耗 5 能量
    private val calmEnergyRegenPerSec: Int = 2,        // 平静每秒恢复 2 能量
    private val berserkDurationMs: Long = 30_000L      // 狂暴持续 30 秒
) {

    /**
     * 狂暴状态
     */
    enum class BerserkState {
        CALM,        // 平静：正常执行
        AGITATED,    // 激动：提升并发
        BERSERK,     // 狂暴：全速执行，关闭熔断
        EXHAUSTED    // 力竭：降速恢复
    }

    /**
     * 暴怒事件
     */
    data class RageEvent(
        val type: RageEventType,
        val oldState: BerserkState,
        val newState: BerserkState,
        val rage: Int,
        val energy: Int,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class RageEventType {
        RAGE_INCREASED, RAGE_DECREASED,
        STATE_CHANGED, BERSERK_TRIGGERED, BERSERK_EXPIRED,
        ENERGY_DEPLETED, ENERGY_RECOVERED
    }

    /**
     * 暴怒增益（狂暴状态下的执行增强）
     */
    data class RageBoost(
        val concurrencyMultiplier: Float,    // 并发倍率
        val retryMultiplier: Int,            // 重试倍率
        val disableCircuitBreaker: Boolean,  // 关闭熔断
        val enableSpeculative: Boolean,      // 启用推测执行
        val timeoutMultiplier: Float,        // 超时倍率
        val reason: String
    ) {
        companion object {
            val CALM = RageBoost(1.0f, 1, false, false, 1.0f, "平静")
            val AGITATED = RageBoost(1.5f, 2, false, true, 1.2f, "激动")
            val BERSERK = RageBoost(2.0f, 5, true, true, 1.5f, "狂暴")
            val EXHAUSTED = RageBoost(0.5f, 1, false, false, 0.7f, "力竭")
        }
    }

    // ============ 状态 ============

    private val _rage = MutableStateFlow(0)
    val rage: StateFlow<Int> = _rage.asStateFlow()

    private val _energy = MutableStateFlow(maxEnergy)
    val energy: StateFlow<Int> = _energy.asStateFlow()

    private val _state = MutableStateFlow(BerserkState.CALM)
    val state: StateFlow<BerserkState> = _state.asStateFlow()

    private val _boost = MutableStateFlow(RageBoost.CALM)
    val boost: StateFlow<RageBoost> = _boost.asStateFlow()

    private val _events = MutableStateFlow<List<RageEvent>>(emptyList())
    val events: StateFlow<List<RageEvent>> = _events.asStateFlow()

    private val recentEvents = ConcurrentLinkedQueue<RageEvent>()
    private val berserkerSince = AtomicInteger(0)  // 狂暴开始时间
    private var decayJob: Job? = null
    private var energyJob: Job? = null

    init {
        startDecayLoop()
        startEnergyLoop()
    }

    // ============ 公共 API ============

    /**
     * 任务失败时调用 —— 增加暴怒值
     */
    fun onTaskFailed(severity: FailureSeverity, reason: String = "") {
        val rageGain = when (severity) {
            FailureSeverity.MINOR -> 5       // 小失败 +5
            FailureSeverity.MODERATE -> 15   // 中等失败 +15
            FailureSeverity.MAJOR -> 30      // 大失败 +30
            FailureSeverity.CRITICAL -> 50   // 严重失败 +50
        }
        increaseRage(rageGain, "任务失败($severity): $reason")
    }

    /**
     * 任务超时时调用 —— 增加暴怒值
     */
    fun onTaskTimeout(taskId: String) {
        increaseRage(20, "任务超时: $taskId")
    }

    /**
     * 任务被取消时调用 —— 增加暴怒值
     */
    fun onTaskCancelled(taskId: String, reason: String = "") {
        increaseRage(10, "任务取消: $taskId ($reason)")
    }

    /**
     * 任务成功时调用 —— 降低暴怒值
     */
    fun onTaskSucceeded(quality: Float = 1.0f) {
        val rageDecrease = (10 * quality).toInt()
        decreaseRage(rageDecrease, "任务成功(质量=$quality)")
    }

    /**
     * 手动触发狂暴模式
     */
    fun enterBerserkMode(reason: String = "手动触发") {
        _rage.value = maxRage
        transitionTo(BerserkState.BERSERK, "手动触发: $reason")
    }

    /**
     * 手动退出狂暴模式
     */
    fun exitBerserkMode(reason: String = "手动退出") {
        _rage.value = 0
        transitionTo(BerserkState.CALM, "手动退出: $reason")
    }

    /**
     * 是否处于狂暴状态
     */
    fun isBerserk(): Boolean = _state.value == BerserkState.BERSERK

    /**
     * 获取当前暴怒增益
     */
    fun currentBoost(): RageBoost = _boost.value

    /**
     * 获取状态摘要
     */
    fun getStatusSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 暴怒值系统 ═══")
        sb.appendLine("状态: ${_state.value}")
        sb.appendLine("暴怒值: ${_rage.value}/$maxRage ${rageBar(_rage.value)}")
        sb.appendLine("能量: ${_energy.value}/$maxEnergy ${energyBar(_energy.value)}")
        sb.appendLine("增益: ${_boost.value.reason}")
        sb.append("  并发×${_boost.value.concurrencyMultiplier} | 重试×${_boost.value.retryMultiplier} | 超时×${_boost.value.timeoutMultiplier}")
        if (_boost.value.disableCircuitBreaker) sb.append(" | 熔断关闭")
        if (_boost.value.enableSpeculative) sb.append(" | 推测执行")
        return sb.toString()
    }

    /**
     * 重置
     */
    fun reset() {
        _rage.value = 0
        _energy.value = maxEnergy
        transitionTo(BerserkState.CALM, "重置")
    }

    /**
     * 关闭
     */
    fun shutdown() {
        decayJob?.cancel()
        energyJob?.cancel()
        scope.cancel()
    }

    // ============ 内部方法 ============

    private fun increaseRage(amount: Int, reason: String) {
        val oldRage = _rage.value
        val newRage = (oldRage + amount).coerceAtMost(maxRage)
        _rage.value = newRage
        emitEvent(RageEventType.RAGE_INCREASED, reason, oldRage, newRage)
        evaluateStateTransition(reason)
    }

    private fun decreaseRage(amount: Int, reason: String) {
        val oldRage = _rage.value
        val newRage = (oldRage - amount).coerceAtLeast(0)
        _rage.value = newRage
        emitEvent(RageEventType.RAGE_DECREASED, reason, oldRage, newRage)
        evaluateStateTransition(reason)
    }

    private fun evaluateStateTransition(reason: String) {
        val rage = _rage.value
        val energy = _energy.value
        val currentState = _state.value

        val newState = when {
            rage >= berserkerThreshold && energy > exhaustionThreshold -> BerserkState.BERSERK
            rage >= agitatedThreshold && energy > exhaustionThreshold -> BerserkState.AGITATED
            energy <= exhaustionThreshold -> BerserkState.EXHAUSTED
            rage < agitatedThreshold -> BerserkState.CALM
            else -> currentState
        }

        if (newState != currentState) {
            transitionTo(newState, reason)
        }
    }

    private fun transitionTo(newState: BerserkState, reason: String) {
        val oldState = _state.value
        _state.value = newState
        _boost.value = when (newState) {
            BerserkState.CALM -> RageBoost.CALM
            BerserkState.AGITATED -> RageBoost.AGITATED
            BerserkState.BERSERK -> RageBoost.BERSERK
            BerserkState.EXHAUSTED -> RageBoost.EXHAUSTED
        }

        if (newState == BerserkState.BERSERK) {
            berserkerSince.set(System.currentTimeMillis().toInt())
            emitEvent(RageEventType.BERSERK_TRIGGERED, reason, oldState, newState)
        } else if (oldState == BerserkState.BERSERK) {
            emitEvent(RageEventType.BERSERK_EXPIRED, reason, oldState, newState)
        } else {
            emitEvent(RageEventType.STATE_CHANGED, reason, oldState, newState)
        }
    }

    private fun emitEvent(type: RageEventType, reason: String, oldRage: Int, newRage: Int) {
        val event = RageEvent(type, _state.value, _state.value, newRage, _energy.value, reason)
        recentEvents.add(event)
        while (recentEvents.size > 100) recentEvents.poll()
        _events.value = recentEvents.toList().reversed()
    }

    private fun startDecayLoop() {
        decayJob = scope.launch {
            while (true) {
                delay(decayIntervalMs)
                // 平静状态下暴怒值衰减更快
                val decayAmount = when (_state.value) {
                    BerserkState.CALM -> 5
                    BerserkState.AGITATED -> 3
                    BerserkState.BERSERK -> 1
                    BerserkState.EXHAUSTED -> 8
                }
                if (_rage.value > 0) {
                    decreaseRage(decayAmount, "自然衰减")
                }

                // 检查狂暴是否超时
                if (_state.value == BerserkState.BERSERK) {
                    val since = berserkerSince.get().toLong()
                    if (System.currentTimeMillis() - since > berserkDurationMs) {
                        transitionTo(BerserkState.AGITATED, "狂暴超时")
                    }
                }
            }
        }
    }

    private fun startEnergyLoop() {
        energyJob = scope.launch {
            while (true) {
                delay(1000)  // 每秒更新能量
                val currentState = _state.value
                val delta = when (currentState) {
                    BerserkState.BERSERK -> -berserkEnergyCostPerSec
                    BerserkState.EXHAUSTED -> calmEnergyRegenPerSec * 2  // 力竭时双倍恢复
                    BerserkState.AGITATED -> -1
                    BerserkState.CALM -> calmEnergyRegenPerSec
                }
                val oldEnergy = _energy.value
                val newEnergy = (oldEnergy + delta).coerceIn(0, maxEnergy)
                _energy.value = newEnergy

                // 能量耗尽
                if (oldEnergy > 0 && newEnergy == 0 && currentState == BerserkState.BERSERK) {
                    transitionTo(BerserkState.EXHAUSTED, "能量耗尽")
                }
                // 能量恢复
                if (currentState == BerserkState.EXHAUSTED && newEnergy > maxEnergy / 2) {
                    transitionTo(BerserkState.CALM, "能量恢复")
                }
            }
        }
    }

    private fun rageBar(value: Int): String {
        val filled = value / 10
        return "[" + "█".repeat(filled) + "░".repeat(10 - filled) + "]"
    }

    private fun energyBar(value: Int): String {
        val filled = value * 10 / maxEnergy
        return "[" + "█".repeat(filled) + "░".repeat(10 - filled) + "]"
    }

    enum class FailureSeverity { MINOR, MODERATE, MAJOR, CRITICAL }
}
