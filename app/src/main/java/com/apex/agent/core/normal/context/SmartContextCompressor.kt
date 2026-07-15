package com.apex.agent.core.normal.context

/**
 * F3: 智能上下文压缩器
 *
 * 替代简单的 token 阈值总结，采用"分层压缩"：
 * - 最近 N 轮保留原文
 * - 中段保留摘要
 * - 远段保留关键事实（人名/数字/决策）
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 独立压缩
 * - 狂暴用无限上下文技能，不压缩
 * - 本功能专注**单 Agent 长对话**的智能压缩
 */

/**
 * 对话消息
 */
data class ConversationMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokenCount: Int = estimateTokens(content),
    val importance: Float = 1.0f,
    val summary: String? = null,
    val extractedFacts: List<String> = emptyList()
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/**
 * 压缩层级
 */
enum class CompressionTier {
    /** 保留原文 */
    FULL,
    /** 保留摘要 */
    SUMMARY,
    /** 仅保留关键事实 */
    FACTS_ONLY,
    /** 丢弃 */
    DISCARD
}

/**
 * 压缩结果
 */
data class CompressionResult(
    val messages: List<ConversationMessage>,
    val originalTokenCount: Int,
    val compressedTokenCount: Int,
    val compressionRatio: Float,
    val tiers: Map<String, CompressionTier>
)

/**
 * 智能上下文压缩器
 */
class SmartContextCompressor(
    private val maxTokens: Int = 32_000,
    private val recentKeepCount: Int = 6,
    private val summaryThreshold: Int = 20,
    private val factsThreshold: Int = 40
) {

    /**
     * 压缩对话历史
     */
    fun compress(history: List<ConversationMessage>): CompressionResult {
        if (history.isEmpty()) {
            return CompressionResult(emptyList(), 0, 0, 1.0f, emptyMap())
        }
        val originalTokens = history.sumOf { it.tokenCount }
        if (originalTokens <= maxTokens) {
            return CompressionResult(
                messages = history,
                originalTokenCount = originalTokens,
                compressedTokenCount = originalTokens,
                compressionRatio = 1.0f,
                tiers = history.associate { it.id to CompressionTier.FULL }
            )
        }
        val tiers = mutableMapOf<String, CompressionTier>()
        val total = history.size

        // 分层策略
    val result = mutableListOf<ConversationMessage>()

        history.forEachIndexed { index, msg ->
            val fromEnd = total - index
            val tier = when {
                fromEnd <= recentKeepCount -> CompressionTier.FULL
                fromEnd <= summaryThreshold -> CompressionTier.SUMMARY
                fromEnd <= factsThreshold -> CompressionTier.FACTS_ONLY
                else -> {
                    // 远段：按重要性决定
    if (msg.importance > 0.7f) CompressionTier.FACTS_ONLY
                    else CompressionTier.DISCARD
                }
            }
            tiers[msg.id] = tier

            when (tier) {
                CompressionTier.FULL -> result.add(msg)
                CompressionTier.SUMMARY -> {
                    val summary = msg.summary ?: generateSummary(msg)
                    result.add(msg.copy(
                        content = "[摘要] $summary",
                        tokenCount = estimateTokens(summary) + 10
                    ))
                }
                CompressionTier.FACTS_ONLY -> {
                    val facts = msg.extractedFacts.ifEmpty { extractFacts(msg.content) }
        if (facts.isNotEmpty()) {
                        val factsText = facts.joinToString("; ")
                        result.add(msg.copy(
                            content = "[关键事实] $factsText",
                            tokenCount = estimateTokens(factsText) + 15
                        ))
                    }
                }
                CompressionTier.DISCARD -> { /* 跳过 */ }
            }
        }
        val compressedTokens = result.sumOf { it.tokenCount }

        // 如果仍超限，递归压缩
    return if (compressedTokens > maxTokens && recentKeepCount > 2) {
            SmartContextCompressor(
                maxTokens = maxTokens,
                recentKeepCount = recentKeepCount - 2,
                summaryThreshold = (summaryThreshold - 5).coerceAtLeast(recentKeepCount),
                factsThreshold = (factsThreshold - 5).coerceAtLeast(summaryThreshold)
            ).compress(result).let { recursive ->
                CompressionResult(
                    messages = recursive.messages,
                    originalTokenCount = originalTokens,
                    compressedTokenCount = recursive.compressedTokenCount,
                    compressionRatio = recursive.compressedTokenCount.toFloat() / originalTokens,
                    tiers = tiers
                )
            }
        } else {
            CompressionResult(
                messages = result,
                originalTokenCount = originalTokens,
                compressedTokenCount = compressedTokens,
                compressionRatio = compressedTokens.toFloat() / originalTokens,
                tiers = tiers
            )
        }
    }

    /**
     * 评估消息重要性（0.0 - 1.0）
     */
    fun evaluateImportance(message: ConversationMessage): Float {
        var score = 0.5f

        // 包含数字/日期 → 重要
    if (Regex("\\d{4}|\\d+").containsMatchIn(message.content)) score += 0.15f

        // 包含决策性词汇 → 重要
    val decisionWords = listOf("决定", "同意", "拒绝", "选择", "decided", "agreed", "chose", "will")
        if (decisionWords.any { message.content.contains(it, ignoreCase = true) }) score += 0.2f

        // 包含人名/专有名词 → 重要
    if (Regex("[A-Z][a-z]+|[\\u4e00-\\u9fa5]{2,3}(说|表示|认为)").containsMatchIn(message.content)) score += 0.1f

        // 用户消息比 assistant 更重要
    if (message.role == ConversationMessage.Role.USER) score += 0.1f

        // 包含代码 → 重要
    if (message.content.contains("```") || message.content.contains("<code>")) score += 0.15f

        // 长消息可能包含更多信息
    if (message.tokenCount > 200) score += 0.1f

        return score.coerceIn(0.0f, 1.0f)
    }

    /**
     * 生成摘要
     */
    private fun generateSummary(message: ConversationMessage): String {
        val content = message.content
        // 简化：取前 100 字 + 后 50 字
    return when {
            content.length <= 150 -> content
            else -> content.take(100) + "..." + content.takeLast(50)
        }
    }

    /**
     * 提取关键事实
     */
    fun extractFacts(content: String): List<String> {
        val facts = mutableListOf<String>()

        // 提取数字/日期
        Regex("(\\d{4}年|\\d+月\\d+日|\\d+个|\\d+次|\\d+%|\\d+\\.\\d+)").findAll(content)
            .map { it.value }
            .take(3)
            .forEach { facts.add("数据:$it") }

        // 提取人名（简化：大写英文或中文姓名）
        Regex("([A-Z][a-z]+ [A-Z][a-z]+|[\\u4e00-\\u9fa5]{2,3})(说|表示|认为|提出|建议)".let {
            it
        }).findAll(content)
            .map { it.groupValues[1] }
            .distinct()
            .take(3)
            .forEach { facts.add("人物:$it") }

        // 提取决策
    val decisionPatterns = mapOf(
            "决定" to "决定", "同意" to "同意", "拒绝" to "拒绝", "选择" to "选择",
            "decided" to "decided", "agreed" to "agreed"
        )
        for ((pattern, label) in decisionPatterns) {
            if (content.contains(pattern, ignoreCase = true)) {
                facts.add("决策:$label")
                break
            }
        }
        return facts.take(5)
    }

    companion object {
        /**
         * 估算 token 数（简化：1 字 ≈ 1.5 token，1 英文单词 ≈ 1.3 token）
         */
        fun estimateTokens(text: String): Int {
            val chineseChars = text.count { it.code in 0x4e00..0x9fff }
        val englishWords = text.split(Regex("[\\s\\p{Punct}]+"))
                .filter { it.isNotEmpty() && it.all { c -> c.code !in 0x4e00..0x9fff } }
                .size
            return (chineseChars * 1.5 + englishWords * 1.3).toInt()
        }
    }
}
