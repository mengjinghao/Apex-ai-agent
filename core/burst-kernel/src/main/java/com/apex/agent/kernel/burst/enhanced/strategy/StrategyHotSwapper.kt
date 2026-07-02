package com.apex.agent.kernel.burst.enhanced.strategy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B7: 实时策略热切换（Hot Strategy Switching）
 *
 * 根据实时指标动态切换 ExecutionStrategy，无需重启：
 * - successRate 低 → 降级到 CONSERVATIVE
 * - successRate 高 + 资源充足 → 升级到 SPECULATIVE
 * - 平滑迁移，不中断运行中任务
 */
class StrategyHotSwapper(
    private val checkIntervalMs: Long = 5000,
    private val lowSuccessThreshold: Float = 0.5f,
    private val highSuccessThreshold: Float = 0.9f,
    private val minSwitchIntervalMs: Long = 30_000L  // 最短切换间隔
) {

    enum class ExecutionStrategy {
        SEQUENTIAL,     // 串行：最安全
        PIPELINED,      // 流水线
        PARALLEL,       // 并行
        SPECULATIVE,    // 推测执行：最激进
        ADAPTIVE        // 自适应（由系统决定）
    }

    /**
     * 策略配置
     */
    data class StrategyConfig(
        val strategy: ExecutionStrategy,
        val maxConcurrency: Int,
        val enableSpeculative: Boolean,
        val retryMultiplier: Int,
        val timeoutMultiplier: Float,
        val reason: String
    )

    /**
     * 切换事件
     */
    data class StrategySwitchEvent(
        val from: ExecutionStrategy,
        val to: ExecutionStrategy,
        val reason: String,
        val metrics: SwitchMetrics,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SwitchMetrics(
        val successRate: Float,
        val avgLatencyMs: Long,
        val resourceUsage: Float,
        val taskQueueSize: Int
    )

    // ============ 状态 ============

    private val _currentConfig = MutableStateFlow(
        StrategyConfig(ExecutionStrategy.ADAPTIVE, 4, false, 1, 1.0f, "初始")
    )
    val currentConfig: StateFlow<StrategyConfig> = _currentConfig.asStateFlow()

    private val _currentStrategy = MutableStateFlow(ExecutionStrategy.ADAPTIVE)
    val currentStrategy: StateFlow<ExecutionStrategy> = _currentStrategy.asStateFlow()

    private val _switchEvents = MutableStateFlow<List<StrategySwitchEvent>>(emptyList())
    val switchEvents: StateFlow<List<StrategySwitchEvent>> = _switchEvents.asStateFlow()

    private val recentEvents = mutableListOf<StrategySwitchEvent>()
    private var lastSwitchTime = 0L
    private val strategyStats = ConcurrentHashMap<ExecutionStrategy, StrategyStats>()

    data class StrategyStats(
        var totalExecutions: Int = 0,
        var successCount: Int = 0,
        var totalLatencyMs: Long = 0,
        var lastUsed: Long = 0
    ) {
        val successRate: Float get() = if (totalExecutions > 0) successCount.toFloat() / totalExecutions else 0f
        val avgLatency: Long get() = if (totalExecutions > 0) totalLatencyMs / totalExecutions else 0
    }

    // ============ 公共 API ============

    /**
     * 指标更新时调用
     */
    fun onMetricUpdate(
        successRate: Float,
        avgLatencyMs: Long,
        resourceUsage: Float,
        taskQueueSize: Int
    ) {
        val now = System.currentTimeMillis()
        if (now - lastSwitchTime < minSwitchIntervalMs) return

        val current = _currentStrategy.value
        val metrics = SwitchMetrics(successRate, avgLatencyMs, resourceUsage, taskQueueSize)

        val newStrategy = when {
            // 成功率低 → 降级
            successRate < lowSuccessThreshold && current != ExecutionStrategy.SEQUENTIAL -> {
                ExecutionStrategy.SEQUENTIAL
            }
            // 成功率中等 + 资源紧张 → 保守
            successRate < highSuccessThreshold && resourceUsage > 0.8f -> {
                ExecutionStrategy.PIPELINED
            }
            // 成功率高 + 资源充足 → 升级
            successRate > highSuccessThreshold && resourceUsage < 0.5f && current != ExecutionStrategy.SPECULATIVE -> {
                ExecutionStrategy.SPECULATIVE
            }
            // 默认保持
            else -> null
        }

        if (newStrategy != null && newStrategy != current) {
            switchTo(newStrategy, "自动切换 (success=$successRate, latency=${avgLatencyMs}ms, resource=$resourceUsage)", metrics)
        }
    }

    /**
     * 手动切换
     */
    fun switchTo(strategy: ExecutionStrategy, reason: String = "手动切换") {
        val metrics = SwitchMetrics(0f, 0, 0f, 0)
        switchTo(strategy, reason, metrics)
    }

    /**
     * 记录执行结果
     */
    fun recordExecution(strategy: ExecutionStrategy, success: Boolean, latencyMs: Long) {
        val stats = strategyStats.computeIfAbsent(strategy) { StrategyStats() }
        stats.totalExecutions++
        if (success) stats.successCount++
        stats.totalLatencyMs += latencyMs
        stats.lastUsed = System.currentTimeMillis()
    }

    /**
     * 获取策略统计
     */
    fun getStrategyStats(): Map<ExecutionStrategy, StrategyStats> = strategyStats.toMap()

    /**
     * 获取最佳策略（基于历史统计）
     */
    fun getBestStrategy(): ExecutionStrategy {
        return strategyStats.entries
            .filter { it.value.totalExecutions >= 3 }
            .maxByOrNull { it.value.successRate * 1000 - it.value.avgLatency / 100 }
            ?.key ?: ExecutionStrategy.ADAPTIVE
    }

    /**
     * 获取切换历史
     */
    fun getSwitchHistory(): List<StrategySwitchEvent> = recentEvents.toList()

    // ============ 内部方法 ============

    private fun switchTo(strategy: ExecutionStrategy, reason: String, metrics: SwitchMetrics) {
        val old = _currentStrategy.value
        if (old == strategy) return

        val config = when (strategy) {
            ExecutionStrategy.SEQUENTIAL -> StrategyConfig(strategy, 1, false, 1, 1.5f, reason)
            ExecutionStrategy.PIPELINED -> StrategyConfig(strategy, 2, false, 2, 1.2f, reason)
            ExecutionStrategy.PARALLEL -> StrategyConfig(strategy, 4, false, 2, 1.0f, reason)
            ExecutionStrategy.SPECULATIVE -> StrategyConfig(strategy, 8, true, 3, 0.8f, reason)
            ExecutionStrategy.ADAPTIVE -> StrategyConfig(strategy, 4, false, 2, 1.0f, reason)
        }

        _currentStrategy.value = strategy
        _currentConfig.value = config
        lastSwitchTime = System.currentTimeMillis()

        val event = StrategySwitchEvent(old, strategy, reason, metrics)
        recentEvents.add(event)
        while (recentEvents.size > 50) recentEvents.removeAt(0)
        _switchEvents.value = recentEvents.toList()
    }
}
