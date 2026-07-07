package com.apex.agent.plugins.burst.base

import com.apex.agent.domain.model.ExecutionLog
import com.apex.agent.domain.model.LogLevel

/**
 * 技能执行结果
 */
data class BurstSkillResult(
    val success: Boolean,
    val output: String? = null,
    val logs: List<ExecutionLog> = emptyList(),
    val errorMessage: String? = null,
    val metrics: SkillMetrics? = null
)

/**
 * 技能执行指标
 */
data class SkillMetrics(
    val executionTimeMs: Long,
    val memoryUsedMb: Int = 0,
    val tokensProcessed: Int = 0,
    val stepsCompleted: Int = 0
)