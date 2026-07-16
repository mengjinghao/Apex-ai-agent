package com.apex.agent.core.normal.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * F15: 对话健康度仪表盘
 *
 * 实时展示当前对话的"健康度"指标：
 * - 上下文利用率
 * - 工具调用成功率
 * - 用户采纳率（用户是否编辑 AI 回复）
 * - 平均响应时延
 * - token 消耗趋势
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 关注 Agent 协作健康
 * - 狂暴关注策略效果
 * - 本功能关注**单 Agent 对话体验健康度**
 */

/**
 * 对话健康度
 */
data class ConversationHealth(
    val chatId: String,
    val timestamp: Long = System.currentTimeMillis(),

    /** 上下文利用率 (0-1) */
    val contextUsage: Float,

    /** 工具调用成功率 (0-1) */
    val toolSuccessRate: Float,

    /** 用户采纳率 (0-1) - 用户未编辑 AI 回复的比例 */
    val userAcceptanceRate: Float,

    /** 平均响应时延 (ms) */
    val avgLatencyMs: Long,

    /** P95 时延 */
    val p95LatencyMs: Long,

    /** 总 token 消耗 */
    val totalTokens: Long,

    /** 输入 token */
    val inputTokens: Long,

    /** 输出 token */
    val outputTokens: Long,

    /** 澄清次数 */
    val clarificationCount: Int,

    /** 分支次数 */
    val branchCount: Int,

    /** 对话轮次 */
    val conversationRounds: Int,

    /** 健康度评分 (0-100) */
    val healthScore: Int,

    /** 健康度等级 */
    val healthLevel: HealthLevel,

    /** 各维度评分 */
    val dimensionScores: Map<String, Int>,

    /** 建议 */
    val recommendations: List<String>
)

enum class HealthLevel {
    EXCELLENT,  // 90-100
    GOOD,       // 75-89
    FAIR,       // 60-74
    POOR,       // 40-59
    CRITICAL    // 0-39
}

/**
 * 健康度收集器
 */
class ConversationHealthCollector {

    private val chatStats = ConcurrentHashMap<String, ChatStats>()
    private val latencyHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val _currentHealth = MutableStateFlow<ConversationHealth?>(null)
    val currentHealth: StateFlow<ConversationHealth?> = _currentHealth.asStateFlow()

    private data class ChatStats(
        val chatId: String,
        var totalResponses: Int = 0,
        var userEditedResponses: Int = 0,
        var totalLatencyMs: Long = 0,
        var toolCallsTotal: Int = 0,
        var toolCallsSuccess: Int = 0,
        var inputTokens: Long = 0,
        var outputTokens: Long = 0,
        var clarificationCount: Int = 0,
        var branchCount: Int = 0,
        var conversationRounds: Int = 0,
        var contextTokensUsed: Int = 0,
        var contextTokensMax: Int = 32_000
    )

    /**
     * 记录 AI 响应完成
     */
    fun onAssistantResponse(
        chatId: String,
        latencyMs: Long,
        inputTokens: Long,
        outputTokens: Long,
        contextTokensUsed: Int,
        contextTokensMax: Int = 32_000
    ) {
        val stats = chatStats.computeIfAbsent(chatId) { ChatStats(chatId) }
        stats.totalResponses++
        stats.totalLatencyMs += latencyMs
        stats.inputTokens += inputTokens
        stats.outputTokens += outputTokens
        stats.contextTokensUsed = contextTokensUsed
        stats.contextTokensMax = contextTokensMax
        stats.conversationRounds++

        latencyHistory.computeIfAbsent(chatId) { mutableListOf() }.apply {
            add(latencyMs)
            if (size > 100) removeAt(0)
        }

        refreshHealth(chatId)
    }

    /**
     * 记录用户编辑 AI 回复
     */
    fun onUserEditedResponse(chatId: String) {
        val stats = chatStats[chatId] ?: return
        stats.userEditedResponses++
        refreshHealth(chatId)
    }

    /**
     * 记录工具调用
     */
    fun onToolCall(chatId: String, success: Boolean) {
        val stats = chatStats.computeIfAbsent(chatId) { ChatStats(chatId) }
        stats.toolCallsTotal++
        if (success) stats.toolCallsSuccess++
        refreshHealth(chatId)
    }

    /**
     * 记录澄清
     */
    fun onClarification(chatId: String) {
        val stats = chatStats.computeIfAbsent(chatId) { ChatStats(chatId) }
        stats.clarificationCount++
        refreshHealth(chatId)
    }

    /**
     * 记录分支
     */
    fun onBranch(chatId: String) {
        val stats = chatStats.computeIfAbsent(chatId) { ChatStats(chatId) }
        stats.branchCount++
        refreshHealth(chatId)
    }

    /**
     * 获取当前健康度
     */
    fun getHealth(chatId: String): ConversationHealth? {
        val stats = chatStats[chatId] ?: return null
        return computeHealth(stats)
    }

    /**
     * 重置
     */
    fun reset(chatId: String) {
        chatStats.remove(chatId)
        latencyHistory.remove(chatId)
    }

    fun resetAll() {
        chatStats.clear()
        latencyHistory.clear()
    }

    // ============ 内部方法 ============

    private fun refreshHealth(chatId: String) {
        val stats = chatStats[chatId] ?: return
        val health = computeHealth(stats)
        _currentHealth.value = health
    }

    private fun computeHealth(stats: ChatStats): ConversationHealth {
        // 上下文利用率
        val contextUsage = if (stats.contextTokensMax > 0) {
            stats.contextTokensUsed.toFloat() / stats.contextTokensMax
        } else 0f

        // 工具调用成功率
        val toolSuccessRate = if (stats.toolCallsTotal > 0) {
            stats.toolCallsSuccess.toFloat() / stats.toolCallsTotal
        } else 1f

        // 用户采纳率
        val userAcceptanceRate = if (stats.totalResponses > 0) {
            1f - (stats.userEditedResponses.toFloat() / stats.totalResponses)
        } else 1f

        // 平均时延
        val avgLatency = if (stats.totalResponses > 0) {
            stats.totalLatencyMs / stats.totalResponses
        } else 0L

        // P95 时延
        val latencies = latencyHistory[stats.chatId]?.sorted() ?: emptyList()
        val p95 = if (latencies.isNotEmpty()) {
            latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)]
        } else 0L

        // 各维度评分（0-100）
        val dimensionScores = mutableMapOf<String, Int>()

        // 上下文维度（利用率越低越好，但太低说明没用）
        val contextScore = when {
            contextUsage > 0.9f -> 30  // 快超限了
            contextUsage > 0.7f -> 60
            contextUsage > 0.3f -> 90
            else -> 75  // 利用率太低可能上下文不够
        }
        dimensionScores["context"] = contextScore

        // 工具成功率维度
        val toolScore = (toolSuccessRate * 100).roundToInt()
        dimensionScores["tools"] = toolScore

        // 用户采纳维度
        val acceptanceScore = (userAcceptanceRate * 100).roundToInt()
        dimensionScores["acceptance"] = acceptanceScore

        // 时延维度
        val latencyScore = when {
            avgLatency < 1000 -> 100
            avgLatency < 3000 -> 85
            avgLatency < 5000 -> 70
            avgLatency < 10000 -> 50
            else -> 30
        }
        dimensionScores["latency"] = latencyScore

        // 澄清次数维度（过多说明理解有问题）
        val clarificationScore = when {
            stats.clarificationCount == 0 -> 100
            stats.clarificationCount <= 2 -> 80
            stats.clarificationCount <= 5 -> 60
            else -> 40
        }
        dimensionScores["clarification"] = clarificationScore

        // 综合评分（加权平均）
        val healthScore = (
            contextScore * 0.15 +
            toolScore * 0.20 +
            acceptanceScore * 0.25 +
            latencyScore * 0.25 +
            clarificationScore * 0.15
        ).roundToInt()

        val healthLevel = when {
            healthScore >= 90 -> HealthLevel.EXCELLENT
            healthScore >= 75 -> HealthLevel.GOOD
            healthScore >= 60 -> HealthLevel.FAIR
            healthScore >= 40 -> HealthLevel.POOR
            else -> HealthLevel.CRITICAL
        }

        // 生成建议
        val recommendations = mutableListOf<String>()
        if (contextUsage > 0.8f) {
            recommendations.add("上下文即将超限，建议总结历史或开启新对话")
        }
        if (toolSuccessRate < 0.8f && stats.toolCallsTotal > 3) {
            recommendations.add("工具调用失败率较高，检查权限或网络")
        }
        if (userAcceptanceRate < 0.7f && stats.totalResponses > 5) {
            recommendations.add("用户经常编辑回复，建议调整回答风格或深度")
        }
        if (avgLatency > 5000) {
            recommendations.add("响应时延较高，考虑切换更快的模型或简化请求")
        }
        if (stats.clarificationCount > 5) {
            recommendations.add("澄清频繁，可能需要更清晰的提问引导")
        }
        if (recommendations.isEmpty()) {
            recommendations.add("对话状态良好，继续保持")
        }

        return ConversationHealth(
            chatId = stats.chatId,
            contextUsage = contextUsage,
            toolSuccessRate = toolSuccessRate,
            userAcceptanceRate = userAcceptanceRate,
            avgLatencyMs = avgLatency,
            p95LatencyMs = p95,
            totalTokens = stats.inputTokens + stats.outputTokens,
            inputTokens = stats.inputTokens,
            outputTokens = stats.outputTokens,
            clarificationCount = stats.clarificationCount,
            branchCount = stats.branchCount,
            conversationRounds = stats.conversationRounds,
            healthScore = healthScore,
            healthLevel = healthLevel,
            dimensionScores = dimensionScores,
            recommendations = recommendations
        )
    }
}

/**
 * 健康度仪表盘格式化
 */
fun ConversationHealth.format(): String {
    val sb = StringBuilder()
    sb.appendLine("═══ 对话健康度 ═══")
    sb.appendLine("总评分: $healthScore/100 (${healthLevel})")
    sb.appendLine()
    sb.appendLine("维度评分:")
    dimensionScores.forEach { (dim, score) ->
        val bar = "█".repeat(score / 10) + "░".repeat(10 - score / 10)
        sb.appendLine("  $dim: $bar $score")
    }
    sb.appendLine()
    sb.appendLine("指标:")
    sb.appendLine("  上下文利用率: ${(contextUsage * 100).roundToInt()}%")
    sb.appendLine("  工具成功率: ${(toolSuccessRate * 100).roundToInt()}%")
    sb.appendLine("  用户采纳率: ${(userAcceptanceRate * 100).roundToInt()}%")
    sb.appendLine("  平均时延: ${avgLatencyMs}ms (P95: ${p95LatencyMs}ms)")
    sb.appendLine("  Token: 输入 $inputTokens / 输出 $outputTokens")
    sb.appendLine("  对话轮次: $conversationRounds")
    sb.appendLine("  澄清次数: $clarificationCount")
    sb.appendLine("  分支次数: $branchCount")
    sb.appendLine()
    sb.appendLine("建议:")
    recommendations.forEach { sb.appendLine("  • $it") }
    sb.appendLine("═════════════════")
    return sb.toString()
}
