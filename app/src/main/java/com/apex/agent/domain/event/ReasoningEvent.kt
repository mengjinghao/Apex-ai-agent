package com.apex.agent.domain.event

/**
 * 推理开始事件
 */
data class ReasoningStartedEvent(
    val taskId: String,
    val strategyName: String,
    val complexity: Double,
    val startTime: Long
)

/**
 * 推理完成事件
 */
data class ReasoningCompletedEvent(
    val taskId: String,
    val strategyName: String,
    val duration: Long,
    val quality: Double,
    val tokensUsed: Int
)

/**
 * 推理失败事件
 */
data class ReasoningFailedEvent(
    val taskId: String,
    val strategyName: String,
    val error: String,
    val duration: Long,
    val attempt: Int
)

/**
 * 策略选择事件
 */
data class StrategySelectedEvent(
    val taskId: String,
    val selectedStrategy: String,
    val reason: String,
    val alternatives: List<String>
)

/**
 * 推理降级事件
 */
data class ReasoningFallbackEvent(
    val taskId: String,
    val primaryStrategy: String,
    val fallbackStrategy: String,
    val reason: String
)

/**
 * 推理里程碑事件
 */
data class ReasoningMilestoneEvent(
    val taskId: String,
    val strategyName: String,
    val milestone: String,
    val progressPercent: Double,
    val intermediateResult: String? = null
)

/**
 * 推理缓存使用事件
 */
data class ReasoningCacheUsedEvent(
    val taskId: String,
    val cacheHit: Boolean,
    val strategyName: String,
    val savedTime: Long
)
