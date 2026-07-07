package com.apex.agent.burstmode.config

import kotlinx.serialization.Serializable

/**
 * 狂暴模式性能档位。
 *
 * 控制资源使用的激进程度，影响并发度、内存限制、超时等。
 *
 * @property AGGRESSIVE 激进档：最大并发、无超时限制、占用最多资源。适用于短时高吞吐场景。
 * @property BALANCED 平衡档（默认）：适中并发、合理超时。适用于大多数场景。
 * @property CONSERVATIVE 保守档：低并发、短超时、最少资源。适用于低端设备或后台运行。
 * @property POWER_SAVER 省电档：最低并发、严格超时、禁用非必要功能。适用于电池敏感场景。
 */
@Serializable
enum class BurstProfile(
    val maxConcurrency: Int,
    val defaultTimeoutMs: Long,
    val enableAdaptiveOptimization: Boolean,
    val enableMetricsCollection: Boolean,
    val memoryBudgetMb: Int
) {
    AGGRESSIVE(
        maxConcurrency = 16,
        defaultTimeoutMs = 300_000L,
        enableAdaptiveOptimization = true,
        enableMetricsCollection = true,
        memoryBudgetMb = 512
    ),
    BALANCED(
        maxConcurrency = 8,
        defaultTimeoutMs = 120_000L,
        enableAdaptiveOptimization = true,
        enableMetricsCollection = true,
        memoryBudgetMb = 256
    ),
    CONSERVATIVE(
        maxConcurrency = 4,
        defaultTimeoutMs = 60_000L,
        enableAdaptiveOptimization = false,
        enableMetricsCollection = true,
        memoryBudgetMb = 128
    ),
    POWER_SAVER(
        maxConcurrency = 2,
        defaultTimeoutMs = 30_000L,
        enableAdaptiveOptimization = false,
        enableMetricsCollection = false,
        memoryBudgetMb = 64
    );

    companion object {
        /** 默认档位。 */
        val DEFAULT = BALANCED
    }
}
