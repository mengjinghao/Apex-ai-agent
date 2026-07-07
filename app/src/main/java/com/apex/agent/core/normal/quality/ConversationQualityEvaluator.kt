package com.apex.agent.core.normal.quality

import java.util.concurrent.ConcurrentHashMap

/**
 * F21: 对话质量评估器
 *
 * 评估单次对话和整体对话质量：
 * - 回答质量（相关性/准确性/完整性/清晰度）
 * - 用户体验（响应时间/满意度/采纳率）
 * - 对话流畅度（连贯性/上下文一致性）
 * - 改进建议
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 关注协作效率
 * - 狂暴关注执行成功率
 * - 本功能关注**单 Agent 对话体验质量**，帮助持续优化
 */

/**
 * 对话质量评分
 */
data class ConversationQuality(
    val chatId: String,
    val overallScore: Int,           // 0-100
    val qualityLevel: QualityLevel,
    val dimensions: Map<QualityDimension, Int>,
    val metrics: QualityMetrics,
    val issues: List<QualityIssue>,
    val recommendations: List<String>,
    val evaluatedAt: Long = System.currentTimeMillis()
)

enum class QualityLevel {
    EXCELLENT,  // 90-100
    GOOD,       // 75-89
    ACCEPTABLE, // 60-74
    POOR,       // 40-59
    BAD         // 0-39
}

enum class QualityDimension {
    RELEVANCE,        // 相关性
    ACCURACY,         // 准确性
    COMPLETENESS,     // 完整性
    CLARITY,          // 清晰度
    RESPONSIVENESS,   // 响应性
    COHERENCE,        // 连贯性
    HELPFULNESS,      // 有用性
    ENGAGEMENT        // 参与度
}

/**
 * 质量指标
 */
data class QualityMetrics(
    val totalRounds: Int,
    val avgResponseTimeMs: Long,
    val totalTokensUsed: Long,
    val tokensPerRound: Float,
    val userEditRate: Float,        // 用户编辑 AI 回复的比例
    val userFollowupRate: Float,    // 用户追问的比例
    val toolCallSuccessRate: Float,
    val clarificationRate: Float,   // 澄清次数/总轮数
    val contextEfficiency: Float,   // 上下文利用率
    val errorRecoveryRate: Float    // 错误恢复成功率
)

/**
 * 质量问题
 */
data class QualityIssue(
    val type: IssueType,
    val severity: IssueSeverity,
    val description: String,
    val affectedRounds: List<Int>,
    val suggestion: String
)

enum class IssueType {
    SLOW_RESPONSE,          // 响应慢
    IRRELEVANT_ANSWER,      // 不相关
    INCOMPLETE_ANSWER,      // 不完整
    UNCLEAR_EXPLANATION,    // 不清晰
    CONTEXT_LOSS,           // 上下文丢失
    REPETITIVE_RESPONSE,    // 重复
    TOOL_FAILURE,           // 工具失败
    EXCESSIVE_CLARIFICATION,// 过多澄清
    HIGH_TOKEN_USAGE,       // token 消耗高
    LOW_ENGAGEMENT          // 参与度低
}

enum class IssueSeverity { CRITICAL, MAJOR, MINOR, INFO }

/**
 * 单轮对话质量
 */
data class RoundQuality(
    val roundIndex: Int,
    val userMessage: String,
    val assistantResponse: String,
    val responseTimeMs: Long,
    val tokensUsed: Long,
    val scores: Map<QualityDimension, Int>,
    val issues: List<QualityIssue>,
    val userEdited: Boolean,
    val userFollowedUp: Boolean,
    val toolCalls: Int,
    val toolSuccesses: Int
)

/**
 * 质量评估器
 */
class ConversationQualityEvaluator {

    private val roundHistory = ConcurrentHashMap<String, MutableList<RoundQuality>>()

    /**
     * 评估单轮对话
     */
    fun evaluateRound(
        chatId: String,
        roundIndex: Int,
        userMessage: String,
        assistantResponse: String,
        responseTimeMs: Long,
        tokensUsed: Long,
        toolCalls: Int = 0,
        toolSuccesses: Int = 0,
        userEdited: Boolean = false,
        userFollowedUp: Boolean = false,
        previousRounds: List<RoundQuality> = emptyList()
    ): RoundQuality {
        val scores = mutableMapOf<QualityDimension, Int>()

        // 相关性：检查回答是否包含用户问题的关键词
        scores[QualityDimension.RELEVANCE] = evaluateRelevance(userMessage, assistantResponse)

        // 完整性：检查回答长度和结构
        scores[QualityDimension.COMPLETENESS] = evaluateCompleteness(userMessage, assistantResponse)

        // 清晰度：检查格式化（标题/列表/代码块）
        scores[QualityDimension.CLARITY] = evaluateClarity(assistantResponse)

        // 响应性：基于响应时间
        scores[QualityDimension.RESPONSIVENESS] = evaluateResponsiveness(responseTimeMs)

        // 连贯性：与之前轮次的相关性
        scores[QualityDimension.COHERENCE] = evaluateCoherence(userMessage, assistantResponse, previousRounds)

        // 有用性：是否包含可执行信息
        scores[QualityDimension.HELPFULNESS] = evaluateHelpfulness(userMessage, assistantResponse)

        // 参与度：回答是否引导用户继续
        scores[QualityDimension.ENGAGEMENT] = evaluateEngagement(assistantResponse, userFollowedUp)

        // 准确性（占位：需 LLM 评估）
        scores[QualityDimension.ACCURACY] = 80  // 默认

        // 检测问题
        val issues = detectIssues(
            roundIndex, userMessage, assistantResponse, responseTimeMs,
            tokensUsed, scores, toolCalls, toolSuccesses, userFollowedUp
        )

        val round = RoundQuality(
            roundIndex = roundIndex,
            userMessage = userMessage,
            assistantResponse = assistantResponse,
            responseTimeMs = responseTimeMs,
            tokensUsed = tokensUsed,
            scores = scores,
            issues = issues,
            userEdited = userEdited,
            userFollowedUp = userFollowedUp,
            toolCalls = toolCalls,
            toolSuccesses = toolSuccesses
        )

        roundHistory.computeIfAbsent(chatId) { mutableListOf() }.add(round)
        return round
    }

    /**
     * 评估整个对话
     */
    fun evaluateConversation(chatId: String): ConversationQuality {
        val rounds = roundHistory[chatId]?.toList() ?: emptyList()
        if (rounds.isEmpty()) {
            return ConversationQuality(
                chatId = chatId,
                overallScore = 0,
                qualityLevel = QualityLevel.BAD,
                dimensions = emptyMap(),
                metrics = QualityMetrics(0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                issues = emptyList(),
                recommendations = listOf("尚无对话数据")
            )
        }

        // 各维度平均分
        val avgDimensions = QualityDimension.values().associateWith { dim ->
            rounds.mapNotNull { it.scores[dim] }.average().toInt()
        }

        // 计算指标
        val totalRounds = rounds.size
        val avgResponseTime = rounds.map { it.responseTimeMs }.average().toLong()
        val totalTokens = rounds.sumOf { it.tokensUsed }
        val tokensPerRound = totalTokens.toFloat() / totalRounds
        val editRate = rounds.count { it.userEdited }.toFloat() / totalRounds
        val followupRate = rounds.count { it.userFollowedUp }.toFloat() / totalRounds
        val totalToolCalls = rounds.sumOf { it.toolCalls }
        val toolSuccessRate = if (totalToolCalls > 0) rounds.sumOf { it.toolSuccesses }.toFloat() / totalToolCalls else 1f
        val clarificationRate = rounds.count { it.issues.any { i -> i.type == IssueType.EXCESSIVE_CLARIFICATION } }.toFloat() / totalRounds
        val contextEfficiency = if (totalTokens > 0) (rounds.last().tokensUsed.toFloat() / totalTokens).coerceIn(0f, 1f) else 0f
        val errorRecoveryRate = if (rounds.any { it.toolCalls > 0 }) {
            rounds.filter { it.toolCalls > 0 }.count { it.toolSuccesses > 0 }.toFloat() / rounds.count { it.toolCalls > 0 }
        } else 1f

        val metrics = QualityMetrics(
            totalRounds = totalRounds,
            avgResponseTimeMs = avgResponseTime,
            totalTokensUsed = totalTokens,
            tokensPerRound = tokensPerRound,
            userEditRate = editRate,
            userFollowupRate = followupRate,
            toolCallSuccessRate = toolSuccessRate,
            clarificationRate = clarificationRate,
            contextEfficiency = contextEfficiency,
            errorRecoveryRate = errorRecoveryRate
        )

        // 汇总问题
        val allIssues = rounds.flatMap { r -> r.issues.map { it to r.roundIndex } }
            .groupBy { it.first.type }
            .map { (type, list) ->
                val mostSevere = list.maxByOrNull { it.first.severity.ordinal }!!.first
                QualityIssue(
                    type = type,
                    severity = mostSevere.severity,
                    description = mostSevere.description,
                    affectedRounds = list.map { it.second },
                    suggestion = mostSevere.suggestion
                )
            }

        // 整体评分（加权平均）
        val weights = mapOf(
            QualityDimension.RELEVANCE to 0.2,
            QualityDimension.ACCURACY to 0.2,
            QualityDimension.COMPLETENESS to 0.15,
            QualityDimension.CLARITY to 0.1,
            QualityDimension.RESPONSIVENESS to 0.1,
            QualityDimension.COHERENCE to 0.1,
            QualityDimension.HELPFULNESS to 0.1,
            QualityDimension.ENGAGEMENT to 0.05
        )
        val overallScore = avgDimensions.entries
            .sumOf { (dim, score) -> score * (weights[dim] ?: 0.0) }
            .toInt()
            .coerceIn(0, 100)

        val qualityLevel = when {
            overallScore >= 90 -> QualityLevel.EXCELLENT
            overallScore >= 75 -> QualityLevel.GOOD
            overallScore >= 60 -> QualityLevel.ACCEPTABLE
            overallScore >= 40 -> QualityLevel.POOR
            else -> QualityLevel.BAD
        }

        // 生成建议
        val recommendations = generateRecommendations(avgDimensions, metrics, allIssues)

        return ConversationQuality(
            chatId = chatId,
            overallScore = overallScore,
            qualityLevel = qualityLevel,
            dimensions = avgDimensions,
            metrics = metrics,
            issues = allIssues,
            recommendations = recommendations
        )
    }

    // ============ 评估方法 ============

    private fun evaluateRelevance(userMsg: String, response: String): Int {
        val userKeywords = extractKeywords(userMsg)
        if (userKeywords.isEmpty()) return 70

        val responseLower = response.lowercase()
        val matched = userKeywords.count { responseLower.contains(it.lowercase()) }
        val ratio = matched.toFloat() / userKeywords.size

        return when {
            ratio > 0.8 -> 95
            ratio > 0.6 -> 85
            ratio > 0.4 -> 70
            ratio > 0.2 -> 50
            else -> 30
        }
    }

    private fun evaluateCompleteness(userMsg: String, response: String): Int {
        val questionWords = listOf("如何", "怎么", "为什么", "什么", "哪些", "how", "why", "what", "which")
        val isQuestion = questionWords.any { userMsg.contains(it, ignoreCase = true) }

        return when {
            !isQuestion && response.length > 50 -> 85
            !isQuestion -> 60
            response.length < 50 -> 30
            response.length < 200 -> 60
            response.contains(Regex("\\d+[.、)]")) -> 90  // 有分点
            response.contains("```") -> 85  // 有代码
            response.length > 500 -> 80
            else -> 70
        }
    }

    private fun evaluateClarity(response: String): Int {
        var score = 60

        // 有标题
        if (response.contains(Regex("^#+\\s", RegexOption.MULTILINE))) score += 10
        // 有列表
        if (response.contains(Regex("^[-*+]\\s", RegexOption.MULTILINE))) score += 10
        // 有代码块
        if (response.contains("```")) score += 10
        // 有加粗
        if (response.contains("**") || response.contains("__")) score += 5
        // 有分段
        if (response.split("\n\n").size > 1) score += 5

        return score.coerceIn(0, 100)
    }

    private fun evaluateResponsiveness(responseTimeMs: Long): Int {
        return when {
            responseTimeMs < 1000 -> 100
            responseTimeMs < 3000 -> 90
            responseTimeMs < 5000 -> 75
            responseTimeMs < 10000 -> 60
            responseTimeMs < 20000 -> 40
            else -> 20
        }
    }

    private fun evaluateCoherence(userMsg: String, response: String, previous: List<RoundQuality>): Int {
        if (previous.isEmpty()) return 85

        val lastResponse = previous.last().assistantResponse
        val lastKeywords = extractKeywords(lastResponse).toSet()
        val currentUserKeywords = extractKeywords(userMsg).toSet()
        val responseKeywords = extractKeywords(response).toSet()

        // 检查回答是否承接了之前的话题
        val overlapWithLast = lastKeywords.intersect(responseKeywords).size
        val overlapWithUser = currentUserKeywords.intersect(responseKeywords).size

        return when {
            overlapWithUser >= 2 -> 90
            overlapWithLast >= 2 -> 80
            overlapWithUser >= 1 -> 75
            else -> 60
        }
    }

    private fun evaluateHelpfulness(userMsg: String, response: String): Int {
        val actionIndicators = listOf("步骤", "方法", "建议", "可以", "应该", "需要", "step", "method", "should", "need")
        val hasActionable = actionIndicators.any { response.contains(it, ignoreCase = true) }
        val hasExample = response.contains("例如") || response.contains("比如") || response.contains("example") || response.contains("```")
        val hasExplanation = response.length > 100

        var score = 50
        if (hasActionable) score += 20
        if (hasExample) score += 15
        if (hasExplanation) score += 15

        return score.coerceIn(0, 100)
    }

    private fun evaluateEngagement(response: String, userFollowedUp: Boolean): Int {
        return if (userFollowedUp) 90 else 60
    }

    private fun detectIssues(
        roundIndex: Int,
        userMsg: String,
        response: String,
        responseTimeMs: Long,
        tokensUsed: Long,
        scores: Map<QualityDimension, Int>,
        toolCalls: Int,
        toolSuccesses: Int,
        userFollowedUp: Boolean
    ): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()

        if (responseTimeMs > 10000) {
            issues.add(QualityIssue(
                type = IssueType.SLOW_RESPONSE,
                severity = if (responseTimeMs > 20000) IssueSeverity.MAJOR else IssueSeverity.MINOR,
                description = "响应时间 ${responseTimeMs}ms 过长",
                affectedRounds = listOf(roundIndex),
                suggestion = "考虑切换更快的模型或简化请求"
            ))
        }

        if ((scores[QualityDimension.RELEVANCE] ?: 100) < 50) {
            issues.add(QualityIssue(
                type = IssueType.IRRELEVANT_ANSWER,
                severity = IssueSeverity.MAJOR,
                description = "回答与问题相关性低",
                affectedRounds = listOf(roundIndex),
                suggestion = "检查 prompt 是否清晰，补充上下文"
            ))
        }

        if ((scores[QualityDimension.COMPLETENESS] ?: 100) < 50) {
            issues.add(QualityIssue(
                type = IssueType.INCOMPLETE_ANSWER,
                severity = IssueSeverity.MINOR,
                description = "回答可能不完整",
                affectedRounds = listOf(roundIndex),
                suggestion = "增加回答深度或追问细节"
            ))
        }

        if (toolCalls > 0 && toolSuccesses < toolCalls) {
            issues.add(QualityIssue(
                type = IssueType.TOOL_FAILURE,
                severity = if (toolSuccesses == 0) IssueSeverity.CRITICAL else IssueSeverity.MAJOR,
                description = "$toolSuccesses/$toolCalls 工具调用成功",
                affectedRounds = listOf(roundIndex),
                suggestion = "检查工具权限和参数"
            ))
        }

        if (tokensUsed > 4000) {
            issues.add(QualityIssue(
                type = IssueType.HIGH_TOKEN_USAGE,
                severity = IssueSeverity.INFO,
                description = "单轮 token 消耗 $tokensUsed",
                affectedRounds = listOf(roundIndex),
                suggestion = "考虑压缩上下文或简化请求"
            ))
        }

        if (response.length < 20 && userMsg.length > 20) {
            issues.add(QualityIssue(
                type = IssueType.INCOMPLETE_ANSWER,
                severity = IssueSeverity.MINOR,
                description = "回答过短（${response.length} 字符）",
                affectedRounds = listOf(roundIndex),
                suggestion = "可能需要更详细的回答"
            ))
        }

        return issues
    }

    private fun generateRecommendations(
        dimensions: Map<QualityDimension, Int>,
        metrics: QualityMetrics,
        issues: List<QualityIssue>
    ): List<String> {
        val recs = mutableListOf<String>()

        // 基于维度
        dimensions.filter { it.value < 60 }.forEach { (dim, score) ->
            recs.add("提升${dim.name}评分（当前 $score）")
        }

        // 基于指标
        if (metrics.userEditRate > 0.3f) {
            recs.add("用户编辑率高（${(metrics.userEditRate * 100).toInt()}%），建议调整回答风格")
        }
        if (metrics.toolCallSuccessRate < 0.8f) {
            recs.add("工具调用成功率低（${(metrics.toolCallSuccessRate * 100).toInt()}%），检查权限配置")
        }
        if (metrics.avgResponseTimeMs > 5000) {
            recs.add("平均响应时间长（${metrics.avgResponseTimeMs}ms），考虑优化")
        }

        // 基于问题
        issues.filter { it.severity == IssueSeverity.CRITICAL || it.severity == IssueSeverity.MAJOR }.forEach { issue ->
            recs.add(issue.suggestion)
        }

        if (recs.isEmpty()) {
            recs.add("对话质量良好，继续保持")
        }

        return recs.distinct().take(5)
    }

    private fun extractKeywords(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】\\n]+"))
            .filter { it.length >= 2 }
            .filter { it.lowercase() !in STOP_WORDS }
            .take(10)
    }

    private val STOP_WORDS = setOf(
        "的", "了", "是", "在", "我", "你", "他", "这", "那", "什么", "怎么",
        "the", "a", "an", "is", "are", "was", "were", "be", "what", "how", "why"
    )

    /**
     * 获取对话历史
     */
    fun getHistory(chatId: String): List<RoundQuality> = roundHistory[chatId]?.toList() ?: emptyList()

    /**
     * 重置
     */
    fun reset(chatId: String) {
        roundHistory.remove(chatId)
    }
}

/**
 * 质量报告格式化
 */
fun ConversationQuality.format(): String {
    val sb = StringBuilder()
    sb.appendLine("═══ 对话质量评估 ═══")
    sb.appendLine("总评分: $overallScore/100 (${qualityLevel})")
    sb.appendLine()
    sb.appendLine("维度评分:")
    dimensions.forEach { (dim, score) ->
        val bar = "█".repeat(score / 10) + "░".repeat(10 - score / 10)
        sb.appendLine("  ${dim.name.padEnd(16)}: $bar $score")
    }
    sb.appendLine()
    sb.appendLine("指标:")
    sb.appendLine("  总轮次: ${metrics.totalRounds}")
    sb.appendLine("  平均响应: ${metrics.avgResponseTimeMs}ms")
    sb.appendLine("  总 Token: ${metrics.totalTokensUsed}")
    sb.appendLine("  用户编辑率: ${(metrics.userEditRate * 100).toInt()}%")
    sb.appendLine("  工具成功率: ${(metrics.toolCallSuccessRate * 100).toInt()}%")
    sb.appendLine()
    if (issues.isNotEmpty()) {
        sb.appendLine("问题 (${issues.size}):")
        issues.forEach { issue ->
            sb.appendLine("  [${issue.severity}] ${issue.type}: ${issue.description}")
            sb.appendLine("         → ${issue.suggestion}")
        }
        sb.appendLine()
    }
    sb.appendLine("建议:")
    recommendations.forEach { sb.appendLine("  • $it") }
    sb.appendLine("═══════════════════")
    return sb.toString()
}
