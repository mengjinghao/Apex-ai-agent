package com.apex.agent.core.normal.visualization

import com.apex.agent.core.normal.context.ConversationMessage
import com.apex.agent.core.normal.context.SmartContextCompressor

/**
 * F28: 上下文窗口可视化
 *
 * 可视化展示当前对话的上下文窗口使用情况：
 * - Token 使用分布
 * - 消息层级（原文/摘要/关键事实/丢弃）
 * - 上下文压力指示
 * - 优化建议
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 独立上下文
 * - 狂暴用无限上下文
 * - 本功能让**用户看见上下文状态**，是单 Agent 透明度的体现
 */

/**
 * 上下文窗口状态
 */
data class ContextWindowState(
    val totalTokens: Int,
    val maxTokens: Int,
    val usageRatio: Float,
    val pressure: ContextPressure,
    val layers: List<ContextLayer>,
    val messageBreakdown: MessageBreakdown,
    val tokenDistribution: TokenDistribution,
    val recommendations: List<String>
)

enum class ContextPressure {
    SAFE,       // < 50%
    MODERATE,   // 50-70%
    HIGH,       // 70-85%
    CRITICAL,   // 85-95%
    OVERFLOW    // > 95%
}

/**
 * 上下文层级
 */
data class ContextLayer(
    val tier: com.apex.agent.core.normal.context.CompressionTier,
    val messageCount: Int,
    val tokenCount: Int,
    val ratio: Float,
    val description: String
)

/**
 * 消息分类统计
 */
data class MessageBreakdown(
    val userMessages: Int,
    val assistantMessages: Int,
    val systemMessages: Int,
    val totalMessages: Int,
    val avgTokensPerMessage: Float,
    val longestMessage: Int,
    val shortestMessage: Int
)

/**
 * Token 分布
 */
data class TokenDistribution(
    val systemPromptTokens: Int,
    val userContentTokens: Int,
    val assistantContentTokens: Int,
    val toolCallTokens: Int,
    val summaryTokens: Int,
    val otherTokens: Int
) {
    val total: Int get() = systemPromptTokens + userContentTokens + assistantContentTokens + toolCallTokens + summaryTokens + otherTokens
}

/**
 * 上下文可视化器
 */
class ContextWindowVisualizer(
    private val compressor: SmartContextCompressor = SmartContextCompressor()
) {

    /**
     * 分析上下文窗口状态
     */
    fun analyze(
        messages: List<ConversationMessage>,
        systemPromptTokens: Int = 0,
        maxTokens: Int = 32_000
    ): ContextWindowState {
        val totalTokens = messages.sumOf { it.tokenCount } + systemPromptTokens
        val usageRatio = totalTokens.toFloat() / maxTokens
        val pressure = when {
            usageRatio < 0.5f -> ContextPressure.SAFE
            usageRatio < 0.7f -> ContextPressure.MODERATE
            usageRatio < 0.85f -> ContextPressure.HIGH
            usageRatio < 0.95f -> ContextPressure.CRITICAL
            else -> ContextPressure.OVERFLOW
        }

        // 分层统计
    val compression = compressor.compress(messages)
        val layers = buildLayers(messages, compression)

        // 消息分类
    val breakdown = buildMessageBreakdown(messages)

        // Token 分布
    val distribution = buildTokenDistribution(messages, systemPromptTokens)

        // 建议
    val recommendations = generateRecommendations(pressure, breakdown, distribution, messages)
        return ContextWindowState(
            totalTokens = totalTokens,
            maxTokens = maxTokens,
            usageRatio = usageRatio,
            pressure = pressure,
            layers = layers,
            messageBreakdown = breakdown,
            tokenDistribution = distribution,
            recommendations = recommendations
        )
    }

    /**
     * 生成可视化文本
     */
    fun visualize(state: ContextWindowState): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 上下文窗口 ═══")

        // 压力条
    val barLength = 30
        val filled = (state.usageRatio * barLength).toInt().coerceIn(0, barLength)
        val bar = "█".repeat(filled) + "░".repeat(barLength - filled)
        val percentage = (state.usageRatio * 100).toInt()
        val pressureIcon = when (state.pressure) {
            ContextPressure.SAFE -> "🟢"
            ContextPressure.MODERATE -> "🟡"
            ContextPressure.HIGH -> "🟠"
            ContextPressure.CRITICAL -> "🔴"
            ContextPressure.OVERFLOW -> "⛔"
        }
        sb.appendLine("$pressureIcon [$bar] $percentage% (${state.totalTokens}/${state.maxTokens} tokens)")
        sb.appendLine()

        // 层级
        sb.appendLine("消息层级:")
        state.layers.forEach { layer ->
            val layerBar = "▓".repeat((layer.ratio * 20).toInt().coerceIn(0, 20))
            sb.appendLine("  ${layer.tier.name.padEnd(12)} $layerBar ${layer.messageCount}条 ${layer.tokenCount}tokens")
        }
        sb.appendLine()

        // 消息分类
        sb.appendLine("消息分类:")
        with(state.messageBreakdown) {
            sb.appendLine("  用户: $userMessages | 助手: $assistantMessages | 系统: $systemMessages")
            sb.appendLine("  平均: ${avgTokensPerMessage.toInt()} tokens/条")
            sb.appendLine("  最长: $longestMessage | 最短: $shortestMessage")
        }
        sb.appendLine()

        // Token 分布
        sb.appendLine("Token 分布:")
        with(state.tokenDistribution) {
            sb.appendLine("  系统提示: $systemPromptTokens (${(systemPromptTokens.toFloat() / total * 100).toInt()}%)")
            sb.appendLine("  用户内容: $userContentTokens (${(userContentTokens.toFloat() / total * 100).toInt()}%)")
            sb.appendLine("  助手内容: $assistantContentTokens (${(assistantContentTokens.toFloat() / total * 100).toInt()}%)")
            sb.appendLine("  工具调用: $toolCallTokens (${(toolCallTokens.toFloat() / total * 100).toInt()}%)")
            sb.appendLine("  摘要: $summaryTokens (${(summaryTokens.toFloat() / total * 100).toInt()}%)")
        }
        sb.appendLine()

        // 建议
    if (state.recommendations.isNotEmpty()) {
            sb.appendLine("建议:")
            state.recommendations.forEach { sb.appendLine("  • $it") }
        }
        sb.appendLine("═══════════════")
        return sb.toString()
    }

    /**
     * 生成 ASCII 时间轴
     */
    fun visualizeTimeline(messages: List<ConversationMessage>, maxTokens: Int = 32_000): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 对话时间轴 ═══")
        sb.appendLine()
        val maxBarLength = 50
        val maxTokenCount = messages.maxOfOrNull { it.tokenCount } ?: 1

        messages.forEach { msg ->
            val roleIcon = when (msg.role) {
                ConversationMessage.Role.USER -> "👤"
                ConversationMessage.Role.ASSISTANT -> "🤖"
                ConversationMessage.Role.SYSTEM -> "⚙️"
            }
        val barLength = (msg.tokenCount.toFloat() / maxTokenCount * maxBarLength).toInt().coerceIn(1, maxBarLength)
        val bar = "█".repeat(barLength)
        val importanceBar = "★".repeat((msg.importance * 5).toInt().coerceIn(0, 5))

            sb.append("$roleIcon ")
            sb.append(bar)
            sb.append(" ${msg.tokenCount}t ")
            sb.append(importanceBar)
            sb.append(" ${msg.content.take(30).replace("\n", " ")}")
            sb.appendLine()
        }

        sb.appendLine()
        val totalTokens = messages.sumOf { it.tokenCount }
        sb.appendLine("总计: ${messages.size} 条消息, $totalTokens tokens (上限 $maxTokens)")
        sb.appendLine("═══════════════")
        return sb.toString()
    }

    // ============ 内部方法 ============
    private fun buildLayers(
        messages: List<ConversationMessage>,
        compression: com.apex.agent.core.normal.context.CompressionResult
    ): List<ContextLayer> {
        val grouped = compression.tiers.values.groupBy { it }
        val totalTokens = messages.sumOf { it.tokenCount }.coerceAtLeast(1)
        return com.apex.agent.core.normal.context.CompressionTier.values().map { tier ->
            val tierMessages = grouped[tier] ?: emptyList()
        val tierTokens = tierMessages.sumOf { id ->
                messages.find { it.id == id }?.tokenCount ?: 0
            }
            ContextLayer(
                tier = tier,
                messageCount = tierMessages.size,
                tokenCount = tierTokens,
                ratio = tierTokens.toFloat() / totalTokens,
                description = when (tier) {
                    com.apex.agent.core.normal.context.CompressionTier.FULL -> "保留原文"
                    com.apex.agent.core.normal.context.CompressionTier.SUMMARY -> "保留摘要"
                    com.apex.agent.core.normal.context.CompressionTier.FACTS_ONLY -> "仅关键事实"
                    com.apex.agent.core.normal.context.CompressionTier.DISCARD -> "丢弃"
                }
            )
        }
    }
        private fun buildMessageBreakdown(messages: List<ConversationMessage>): MessageBreakdown {
        val user = messages.count { it.role == ConversationMessage.Role.USER }
        val assistant = messages.count { it.role == ConversationMessage.Role.ASSISTANT }
        val system = messages.count { it.role == ConversationMessage.Role.SYSTEM }
        val total = messages.size
        val avgTokens = if (total > 0) messages.sumOf { it.tokenCount }.toFloat() / total else 0f
        val longest = messages.maxOfOrNull { it.tokenCount } ?: 0
        val shortest = messages.minOfOrNull { it.tokenCount } ?: 0

        return MessageBreakdown(user, assistant, system, total, avgTokens, longest, shortest)
    }
        private fun buildTokenDistribution(messages: List<ConversationMessage>, systemTokens: Int): TokenDistribution {
        var userTokens = 0
        var assistantTokens = 0
        var toolTokens = 0
        var summaryTokens = 0

        messages.forEach { msg ->
            when (msg.role) {
                ConversationMessage.Role.USER -> userTokens += msg.tokenCount
                ConversationMessage.Role.ASSISTANT -> {
                    if (msg.summary != null) summaryTokens += msg.tokenCount
                    else assistantTokens += msg.tokenCount
                }
                ConversationMessage.Role.SYSTEM -> {
                    // 系统消息中包含工具调用结果
    if (msg.content.contains("[tool_result]")) toolTokens += msg.tokenCount
                }
            }
        }
        return TokenDistribution(
            systemPromptTokens = systemTokens,
            userContentTokens = userTokens,
            assistantContentTokens = assistantTokens,
            toolCallTokens = toolTokens,
            summaryTokens = summaryTokens,
            otherTokens = 0
        )
    }
        private fun generateRecommendations(
        pressure: ContextPressure,
        breakdown: MessageBreakdown,
        distribution: TokenDistribution,
        messages: List<ConversationMessage>
    ): List<String> {
        val recs = mutableListOf<String>()
        when (pressure) {
            ContextPressure.SAFE -> {}
            ContextPressure.MODERATE -> recs.add("上下文使用中等，可继续对话")
            ContextPressure.HIGH -> recs.add("上下文压力较高，建议压缩历史或开启新会话")
            ContextPressure.CRITICAL -> recs.add("上下文即将超限，建议立即总结历史或开启新会话")
            ContextPressure.OVERFLOW -> recs.add("上下文已超限！请清理历史或开启新会话")
        }
        if (breakdown.avgTokensPerMessage > 500) {
            recs.add("平均消息较长，可考虑精简输入")
        }
        if (distribution.systemPromptTokens.toFloat() / distribution.total > 0.3f) {
            recs.add("系统提示占比较高，可优化 prompt")
        }
        if (breakdown.assistantMessages > 0 && breakdown.userMessages.toFloat() / breakdown.assistantMessages > 3f) {
            recs.add("用户消息远多于助手，可能需要更多互动")
        }
        val oldMessages = messages.count { System.currentTimeMillis() - it.timestamp > 24 * 60 * 60_000L }
        if (oldMessages > 10) {
            recs.add("有 $oldMessages 条超过 24 小时的消息，可考虑归档")
        }
        if (recs.isEmpty()) {
            recs.add("上下文状态良好")
        }
        return recs
    }
}
