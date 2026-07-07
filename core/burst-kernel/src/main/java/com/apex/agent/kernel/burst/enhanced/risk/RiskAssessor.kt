package com.apex.agent.kernel.burst.enhanced.risk

import java.util.concurrent.ConcurrentHashMap

/**
 * B14: 风险评估与降级（Risk Assessment & Graceful Degradation）
 *
 * 动态评估执行风险并自动降级：
 * - 基于任务复杂度 + 工具危险级 + 历史失败率 + 资源压力
 * - 高风险时自动降级（关闭推测/降低并发/要求确认）
 */
class RiskAssessor {

    data class RiskAssessment(
        val score: Float,             // 0..1
        val level: RiskLevel,
        val factors: List<RiskFactor>,
        val recommendation: DegradationStrategy
    )

    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    data class RiskFactor(
        val name: String,
        val weight: Float,
        val value: Float,
        val contribution: Float
    )

    enum class DegradationStrategy {
        NONE,                       // 无需降级
        REDUCE_CONCURRENCY,         // 降低并发
        DISABLE_SPECULATIVE,        // 关闭推测执行
        REQUIRE_HUMAN_CONFIRM,      // 要求人工确认
        SWITCH_TO_SAFE_PROFILE,     // 切换到安全配置
        ABORT                       // 中止
    }

    data class TaskContext(
        val taskComplexity: Int,       // 1-5
        val toolDangerLevel: Int,      // 1-5
        val historicalFailureRate: Float,
        val resourcePressure: Float,
        val isRootRequired: Boolean,
        val affectsUserData: Boolean,
        val isReversible: Boolean
    )

    private val assessmentHistory = mutableListOf<RiskAssessment>()
    private val factorWeights = ConcurrentHashMap<String, Float>()

    init {
        // 初始化权重
        factorWeights["complexity"] = 0.15f
        factorWeights["toolDanger"] = 0.25f
        factorWeights["failureRate"] = 0.20f
        factorWeights["resourcePressure"] = 0.15f
        factorWeights["rootRequired"] = 0.15f
        factorWeights["affectsUserData"] = 0.20f
        factorWeights["irreversible"] = 0.30f
    }

    /**
     * 评估风险
     */
    fun assess(context: TaskContext): RiskAssessment {
        val factors = mutableListOf<RiskFactor>()

        // 复杂度
        factors.add(RiskFactor(
            "complexity", factorWeights["complexity"]!!,
            context.taskComplexity / 5f,
            factorWeights["complexity"]!! * context.taskComplexity / 5f
        ))

        // 工具危险级
        factors.add(RiskFactor(
            "toolDanger", factorWeights["toolDanger"]!!,
            context.toolDangerLevel / 5f,
            factorWeights["toolDanger"]!! * context.toolDangerLevel / 5f
        ))

        // 历史失败率
        factors.add(RiskFactor(
            "failureRate", factorWeights["failureRate"]!!,
            context.historicalFailureRate,
            factorWeights["failureRate"]!! * context.historicalFailureRate
        ))

        // 资源压力
        factors.add(RiskFactor(
            "resourcePressure", factorWeights["resourcePressure"]!!,
            context.resourcePressure,
            factorWeights["resourcePressure"]!! * context.resourcePressure
        ))

        // Root 权限
        factors.add(RiskFactor(
            "rootRequired", factorWeights["rootRequired"]!!,
            if (context.isRootRequired) 1f else 0f,
            if (context.isRootRequired) factorWeights["rootRequired"]!! else 0f
        ))

        // 影响用户数据
        factors.add(RiskFactor(
            "affectsUserData", factorWeights["affectsUserData"]!!,
            if (context.affectsUserData) 1f else 0f,
            if (context.affectsUserData) factorWeights["affectsUserData"]!! else 0f
        ))

        // 不可逆
        factors.add(RiskFactor(
            "irreversible", factorWeights["irreversible"]!!,
            if (!context.isReversible) 1f else 0f,
            if (!context.isReversible) factorWeights["irreversible"]!! else 0f
        ))

        val score = factors.sumOf { it.contribution.toDouble() }.toFloat().coerceIn(0f, 1f)
        val level = when {
            score < 0.2f -> RiskLevel.SAFE
            score < 0.4f -> RiskLevel.LOW
            score < 0.6f -> RiskLevel.MEDIUM
            score < 0.8f -> RiskLevel.HIGH
            else -> RiskLevel.CRITICAL
        }

        val recommendation = when (level) {
            RiskLevel.SAFE -> DegradationStrategy.NONE
            RiskLevel.LOW -> DegradationStrategy.NONE
            RiskLevel.MEDIUM -> DegradationStrategy.REDUCE_CONCURRENCY
            RiskLevel.HIGH -> DegradationStrategy.DISABLE_SPECULATIVE
            RiskLevel.CRITICAL -> if (context.affectsUserData && !context.isReversible)
                DegradationStrategy.ABORT else DegradationStrategy.REQUIRE_HUMAN_CONFIRM
        }

        val assessment = RiskAssessment(score, level, factors, recommendation)
        assessmentHistory.add(assessment)
        while (assessmentHistory.size > 100) assessmentHistory.removeAt(0)

        return assessment
    }

    /**
     * 获取风险历史
     */
    fun getHistory(): List<RiskAssessment> = assessmentHistory.toList()

    /**
     * 获取风险统计
     */
    fun getStats(): RiskStats {
        val byLevel = assessmentHistory.groupingBy { it.level }.eachCount()
        return RiskStats(
            totalAssessments = assessmentHistory.size,
            byLevel = byLevel,
            avgScore = if (assessmentHistory.isNotEmpty())
                assessmentHistory.map { it.score }.average().toFloat() else 0f,
            highRiskCount = byLevel[RiskLevel.HIGH] ?: 0 + (byLevel[RiskLevel.CRITICAL] ?: 0)
        )
    }

    data class RiskStats(
        val totalAssessments: Int,
        val byLevel: Map<RiskLevel, Int>,
        val avgScore: Float,
        val highRiskCount: Int
    )

    /**
     * 生成风险报告
     */
    fun generateReport(assessment: RiskAssessment): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 风险评估 ═══")
        sb.appendLine("总评分: ${assessment.score} (${assessment.level})")
        sb.appendLine()
        sb.appendLine("风险因素:")
        assessment.factors.sortedByDescending { it.contribution }.forEach { f ->
            val bar = "█".repeat((f.value * 10).toInt()) + "░".repeat(10 - (f.value * 10).toInt())
            sb.appendLine("  ${f.name.padEnd(20)} $bar ${f.contribution}")
        }
        sb.appendLine()
        sb.appendLine("建议: ${assessment.recommendation}")
        sb.appendLine("═══════════════")
        return sb.toString()
    }
}
