package com.apex.agent.core.evaluation

import com.apex.util.AppLogger

/**
 * 置信度级别枚? */
enum class ConfidenceLevel {
    HIGH,       // 高置信度
    MEDIUM,     // 中置信度
    LOW,        // 低置信度
    CRITICAL    // 临界/危险
}

/**
 * 质量门控结果数据? */
data class QualityGateResult(
    val passed: Boolean,
    val confidenceLevel: ConfidenceLevel,
    val suggestions: List<String>
)

/**
 * 质量门控
 * 基于 Pass@K 报告评估输出质量，决定是否通过或需要重? */
object QualityGate {
    private const val TAG = "QualityGate"
    
    // 质量阈?    private const val PASS_AT_1_THRESHOLD = 0.7f
    private const val HIGH_CONFIDENCE_THRESHOLD = 0.9f
    private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.7f
    private const val LOW_CONFIDENCE_THRESHOLD = 0.5f
    
    // 评分阈?    private const val HIGH_SCORE_THRESHOLD = 0.85f
    private const val MEDIUM_SCORE_THRESHOLD = 0.7f
    private const val LOW_SCORE_THRESHOLD = 0.5f

    /**
     * 评估质量
     * @param report Pass@K 报告
     * @return 质量门控结果
     */
    fun evaluate(report: PassKReport): QualityGateResult {
        AppLogger.d(TAG, "评估质量: pass@${report.k}=${report.passAtK}, 平均评分=${report.averageScore}")

        val confidenceLevel = getConfidenceLevel(report)
        val passed = shouldPass(report)
        val suggestions = generateSuggestions(report, confidenceLevel)

        AppLogger.i(TAG, "质量评估结果: ${if (passed) "通过" else "未通过"}, 置信?${confidenceLevel}")

        return QualityGateResult(
            passed = passed,
            confidenceLevel = confidenceLevel,
            suggestions = suggestions
        )
    }

    /**
     * 判断是否需要重?     * @param report Pass@K 报告
     * @return 是否需要重?     */
    fun shouldRetry(report: PassKReport): Boolean {
        val shouldRetry = report.passAtK < PASS_AT_1_THRESHOLD || report.averageScore < LOW_SCORE_THRESHOLD
        
        AppLogger.d(TAG, "判断是否重试: pass@${report.k}=${report.passAtK} < ${PASS_AT_1_THRESHOLD} || 平均评分=${report.averageScore} < ${LOW_SCORE_THRESHOLD} => ${shouldRetry}")
        
        return shouldRetry
    }

    /**
     * 获取置信度级?     * @param report Pass@K 报告
     * @return 置信度级?     */
    fun getConfidenceLevel(report: PassKReport): ConfidenceLevel {
        val passAtK = report.passAtK
        val averageScore = report.averageScore
        
        // 综合 pass@k 和平均评分判断置信度
        return when {
            passAtK >= HIGH_CONFIDENCE_THRESHOLD && averageScore >= HIGH_SCORE_THRESHOLD -> ConfidenceLevel.HIGH
            passAtK >= MEDIUM_CONFIDENCE_THRESHOLD && averageScore >= MEDIUM_SCORE_THRESHOLD -> ConfidenceLevel.MEDIUM
            passAtK >= LOW_CONFIDENCE_THRESHOLD && averageScore >= LOW_SCORE_THRESHOLD -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.CRITICAL
        }
    }

    /**
     * 判断是否应该通过
     */
    private fun shouldPass(report: PassKReport): Boolean {
        // pass@1 >= 0.7 为通过
        return report.passAtK >= PASS_AT_1_THRESHOLD
    }

    /**
     * 生成改进建议
     */
    private fun generateSuggestions(report: PassKReport, confidenceLevel: ConfidenceLevel): List<String> {
        val suggestions = mutableListOf<String>()

        when (confidenceLevel) {
            ConfidenceLevel.HIGH -> {
                suggestions.add("输出质量优秀，可以继续使用当前方?)
            }
            ConfidenceLevel.MEDIUM -> {
                suggestions.add("输出质量良好，建议进一步优化细?)
                if (report.averageScore < HIGH_SCORE_THRESHOLD) {
                    suggestions.add("尝试提升输出的详细程度和准确?)
                }
            }
            ConfidenceLevel.LOW -> {
                suggestions.add("输出质量偏低，建议重新审视需求并改进实现")
                if (report.passAtK < MEDIUM_CONFIDENCE_THRESHOLD) {
                    suggestions.add("增加验证次数以提高结果稳定?)
                }
            }
            ConfidenceLevel.CRITICAL -> {
                suggestions.add("输出质量不达标，强烈建议回退并重新实?)
                suggestions.add("仔细检查需求理解是否正?)
                suggestions.add("考虑分解任务为更小的子任?)
            }
        }

        // 基于具体指标的通用建议
        if (report.results.any { !it.pass }) {
            val failedCount = report.results.count { !it.pass }
            suggestions.add("?${failedCount}/${report.k} 次验证失败，分析失败原因并针对性修?)
        }

        return suggestions
    }
}
