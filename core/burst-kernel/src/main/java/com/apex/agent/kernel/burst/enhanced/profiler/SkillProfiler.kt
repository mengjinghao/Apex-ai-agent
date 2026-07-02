package com.apex.agent.kernel.burst.enhanced.profiler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B50: 技能性能画像器
 *
 * 为每个技能构建详细的性能画像：
 * - 执行时间分布
 * - 成功率趋势
 * - 资源使用画像
 * - 调用频率分析
 * - 性能退化检测
 */
class SkillProfiler {

    data class SkillProfile(
        val skillId: String,
        val totalExecutions: Long,
        val successRate: Float,
        val avgDurationMs: Long,
        val medianDurationMs: Long,
        val p90DurationMs: Long,
        val p99DurationMs: Long,
        val minDurationMs: Long,
        val maxDurationMs: Long,
        val avgTokensUsed: Long,
        val avgMemoryMb: Int,
        val callFrequency: Float,           // 每小时调用次数
        val trend: PerformanceTrend,
        val degradationDetected: Boolean,
        val lastExecutionAt: Long,
        val performanceScore: Float         // 综合评分 0-1
    )

    enum class PerformanceTrend { IMPROVING, STABLE, DEGRADING, UNKNOWN }

    data class ExecutionSample(
        val skillId: String,
        val success: Boolean,
        val durationMs: Long,
        val tokensUsed: Long,
        val memoryMb: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val samples = ConcurrentHashMap<String, MutableList<ExecutionSample>>()
    private val maxSamplesPerSkill = 500

    /**
     * 记录执行
     */
    fun record(sample: ExecutionSample) {
        val list = samples.computeIfAbsent(sample.skillId) { mutableListOf() }
        list.add(sample)
        while (list.size > maxSamplesPerSkill) list.removeAt(0)
    }

    /**
     * 获取技能画像
     */
    fun getProfile(skillId: String): SkillProfile? {
        val list = samples[skillId]?.toList() ?: return null
        if (list.isEmpty()) return null

        val durations = list.map { it.durationMs }.sorted()
        val successes = list.count { it.success }
        val recentDurations = list.takeLast(20).map { it.durationMs }
        val olderDurations = if (list.size > 20) list.dropLast(20).takeLast(20).map { it.durationMs } else durations

        val trend = analyzeTrend(recentDurations, olderDurations)
        val degraded = detectDegradation(recentDurations, olderDurations)

        val performanceScore = computeScore(
            successRate = successes.toFloat() / list.size,
            avgDuration = durations.average().toLong(),
            trend = trend,
            degraded = degraded
        )

        return SkillProfile(
            skillId = skillId,
            totalExecutions = list.size.toLong(),
            successRate = successes.toFloat() / list.size,
            avgDurationMs = durations.average().toLong(),
            medianDurationMs = durations[durations.size / 2],
            p90DurationMs = percentile(durations, 90),
            p99DurationMs = percentile(durations, 99),
            minDurationMs = durations.first(),
            maxDurationMs = durations.last(),
            avgTokensUsed = list.map { it.tokensUsed }.average().toLong(),
            avgMemoryMb = list.map { it.memoryMb }.average().toInt(),
            callFrequency = computeFrequency(list),
            trend = trend,
            degradationDetected = degraded,
            lastExecutionAt = list.last().timestamp,
            performanceScore = performanceScore
        )
    }

    /**
     * 获取所有画像
     */
    fun getAllProfiles(): List<SkillProfile> = samples.keys.mapNotNull { getProfile(it) }.sortedByDescending { it.totalExecutions }

    /**
     * 获取性能最差的技能
     */
    fun getWorstPerformers(limit: Int = 5): List<SkillProfile> {
        return getAllProfiles().sortedBy { it.performanceScore }.take(limit)
    }

    /**
     * 获取性能最好的技能
     */
    fun getBestPerformers(limit: Int = 5): List<SkillProfile> {
        return getAllProfiles().sortedByDescending { it.performanceScore }.take(limit)
    }

    /**
     * 获取退化技能
     */
    fun getDegradedSkills(): List<SkillProfile> = getAllProfiles().filter { it.degradationDetected }

    /**
     * 生成画像报告
     */
    fun generateReport(): String {
        val profiles = getAllProfiles()
        val sb = StringBuilder()
        sb.appendLine("═══ 技能性能画像 ═══")
        sb.appendLine("已画像技能: ${profiles.size}")
        sb.appendLine()
        sb.appendLine("最佳表现:")
        getBestPerformers(5).forEach { p ->
            sb.appendLine("  ${p.skillId}: 评分=${(p.performanceScore * 100).toInt()}% 成功率=${(p.successRate * 100).toInt()}% 平均=${p.avgDurationMs}ms")
        }
        sb.appendLine()
        sb.appendLine("最差表现:")
        getWorstPerformers(5).forEach { p ->
            sb.appendLine("  ${p.skillId}: 评分=${(p.performanceScore * 100).toInt()}% 成功率=${(p.successRate * 100).toInt()}% 平均=${p.avgDurationMs}ms")
        }
        val degraded = getDegradedSkills()
        if (degraded.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("⚠️ 性能退化:")
            degraded.forEach { sb.appendLine("  ${it.skillId} (趋势: ${it.trend})") }
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun analyzeTrend(recent: List<Long>, older: List<Long>): PerformanceTrend {
        if (recent.size < 5 || older.size < 5) return PerformanceTrend.UNKNOWN
        val recentAvg = recent.average()
        val olderAvg = older.average()
        val diff = (recentAvg - olderAvg) / olderAvg.coerceAtLeast(1)
        return when {
            diff < -0.1 -> PerformanceTrend.IMPROVING
            diff > 0.2 -> PerformanceTrend.DEGRADING
            else -> PerformanceTrend.STABLE
        }
    }

    private fun detectDegradation(recent: List<Long>, older: List<Long>): Boolean {
        if (recent.size < 5 || older.size < 5) return false
        val recentAvg = recent.average()
        val olderAvg = older.average()
        return recentAvg > olderAvg * 1.5
    }

    private fun computeScore(successRate: Float, avgDuration: Long, trend: PerformanceTrend, degraded: Boolean): Float {
        var score = successRate * 0.5f
        if (avgDuration < 5000) score += 0.3f
        else if (avgDuration < 15000) score += 0.2f
        else if (avgDuration < 30000) score += 0.1f
        if (trend == PerformanceTrend.IMPROVING) score += 0.1f
        if (trend == PerformanceTrend.DEGRADING) score -= 0.1f
        if (degraded) score -= 0.2f
        return score.coerceIn(0f, 1f)
    }

    private fun computeFrequency(samples: List<ExecutionSample>): Float {
        if (samples.size < 2) return 0f
        val timeRangeMs = samples.last().timestamp - samples.first().timestamp
        if (timeRangeMs <= 0) return 0f
        return samples.size.toFloat() / (timeRangeMs / 3_600_000f)
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = (sorted.size * p / 100.0).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
