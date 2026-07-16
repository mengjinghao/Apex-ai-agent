package com.apex.agent.core.trajectory

import com.apex.agent.core.chat.hooks.PromptTurn
import com.apex.agent.core.chat.hooks.PromptTurnKind

/**
 * 中间轮次压缩�?- 负责压缩中间区域的轮�? * 
 * 使用单一 human 摘要消息替换压缩区域
 */
class MiddleCompression(
    private val summarizer: MiddleSummarizer = DefaultMiddleSummarizer()
) {
    /**
     * 压缩中间区域
     * 
     * @param middleTurns 中间区域的轮�?     * @param targetTokens 目标 token 数量
     * @param toolPairPreserver 工具调用配对保持�?     * @return 压缩结果
     */
    fun compress(
        middleTurns: List<TrajectoryTurn>,
        targetTokens: Int,
        toolPairPreserver: ToolPairPreserver
    ): CompressionResult {
        if (middleTurns.isEmpty()) {
            return CompressionResult(
                compressedTurns = emptyList(),
                savedTokens = 0,
                summaryTurn = null
            )
        }

        val currentTokens = middleTurns.sumOf { it.tokenCount }
        if (currentTokens <= targetTokens) {
            return CompressionResult(
                compressedTurns = middleTurns,
                savedTokens = 0,
                summaryTurn = null
            )
        }

        // 保留工具调用配对
        val (preservedPairs, nonPairTurns) = toolPairPreserver.preservePairs(middleTurns)
        
        // 计算需要删除的 token
        val tokensToRemove = currentTokens - targetTokens
        val preservedTokens = preservedPairs.sumOf { it.totalTokens }
        val availableToRemove = tokensToRemove - preservedTokens
        
        // 压缩非配对轮�?        val (compressedNonPairs, removedTokens) = compressNonPairs(nonPairTurns, availableToRemove)
        
        // 生成摘要
        val allRemovedTurns = nonPairTurns.filter { it !in compressedNonPairs }
        val summaryTurn = summarizer.summarize(allRemovedTurns)
        
        // 重新组合：保留的工具�?+ 压缩的非配对轮次 + 摘要
        val finalTurns = buildList {
            addAll(preservedPairs.flatMap { listOf(it.toolCall, it.toolResult).filterNotNull() })
            addAll(compressedNonPairs)
            if (summaryTurn != null) {
                add(summaryTurn)
            }
        }

        return CompressionResult(
            compressedTurns = finalTurns,
            savedTokens = removedTokens + (summaryTurn?.tokenCount ?: 0),
            summaryTurn = summaryTurn
        )
    }

    /**
     * 压缩非配对轮�?     */
    private fun compressNonPairs(
        turns: List<TrajectoryTurn>,
        targetTokensToRemove: Int
    ): Pair<List<TrajectoryTurn>, Int> {
        if (turns.isEmpty() || targetTokensToRemove <= 0) {
            return turns to 0
        }

        val totalTokens = turns.sumOf { it.tokenCount }
        if (totalTokens <= targetTokensToRemove) {
            return emptyList() to totalTokens
        }

        // 按优先级排序：优先保�?assistant，然后是 user，最后是其他
        val sortedTurns = turns.sortedByDescending { turn ->
            when (turn.kind) {
                PromptTurnKind.ASSISTANT -> 3
                PromptTurnKind.USER -> 2
                else -> 1
            }
        }

        var remainingToRemove = targetTokensToRemove
        val keptTurns = mutableListOf<TrajectoryTurn>()
        var removedTokens = 0

        for (turn in sortedTurns) {
            if (remainingToRemove <= 0) {
                keptTurns.add(turn)
                continue
            }

            if (turn.tokenCount <= remainingToRemove) {
                remainingToRemove -= turn.tokenCount
                removedTokens += turn.tokenCount
            } else {
                keptTurns.add(turn)
            }
        }

        return keptTurns to removedTokens
    }

    /**
     * 计算中间区域需要的压缩�?     */
    fun calculateCompressionNeeded(
        middleTurns: List<TrajectoryTurn>,
        budget: TokenBudget
    ): Int {
        val currentTokens = middleTurns.sumOf { it.tokenCount }
        val available = budget.availableForMiddle
        return (currentTokens - available).coerceAtLeast(0)
    }
}

/**
 * 中间区域压缩结果
 */
data class CompressionResult(
    val compressedTurns: List<TrajectoryTurn>,
    val savedTokens: Int,
    val summaryTurn: TrajectoryTurn?
)

/**
 * 中间区域摘要器接�? */
interface MiddleSummarizer {
    /**
     * 生成中间区域的摘�?     */
    fun summarize(removedTurns: List<TrajectoryTurn>): TrajectoryTurn?
}

/**
 * 默认中间区域摘要�? */
class DefaultMiddleSummarizer : MiddleSummarizer {
    override fun summarize(removedTurns: List<TrajectoryTurn>): TrajectoryTurn? {
        if (removedTurns.isEmpty()) return null

        val totalTokens = removedTurns.sumOf { it.tokenCount }
        val turnCount = removedTurns.size
        val toolCallCount = removedTurns.count { it.isToolCall }
        val humanCount = removedTurns.count { it.isHuman }

        val summaryContent = buildString {
            append("[中间过程压缩摘要] ")
            append("�?${turnCount} 轮，")
            append("${totalTokens} tokens�?)
            append("${toolCallCount} 次工具调�?)
            if (humanCount > 0) {
                append("${humanCount} 次用户交�?)
            }
            append("�?)
        }

        return TrajectoryTurn(
            index = removedTurns.firstOrNull()?.index ?: 0,
            kind = PromptTurnKind.USER,
            content = summaryContent,
            tokenCount = estimateTokenCount(summaryContent)
        )
    }

    private fun estimateTokenCount(text: String): Int {
        // 粗略估算：中文约 2 字符/token，英文约 4 字符/token
        val chineseChars = text.count { it.codePointRangeContainsPoint(0x4E00.toInt(), it.codePoint) }
        val otherChars = text.length - chineseChars
        return (chineseChars / 2 + otherChars / 4).coerceAtLeast(10)
    }
}

/**
 * 基于 LLM 的摘要器（需要外�?LLM 服务�? */
class LLMSummarizer(
    private val llmSummarize: suspend (List<PromptTurn>) -> String
) : MiddleSummarizer {
    override fun summarize(removedTurns: List<TrajectoryTurn>): TrajectoryTurn? {
        if (removedTurns.isEmpty()) return null

        val promptTurns = removedTurns.map { turn ->
            PromptTurn(
                kind = turn.kind,
                content = turn.content,
                toolName = turn.toolName
            )
        }

        // 同步版本返回默认摘要
        // 实际 LLM 摘要需要异步调�?        return DefaultMiddleSummarizer().summarize(removedTurns)
    }

    /**
     * 异步生成 LLM 摘要
     */
    suspend fun summarizeAsync(removedTurns: List<TrajectoryTurn>): TrajectoryTurn? {
        if (removedTurns.isEmpty()) return null

        val promptTurns = removedTurns.map { turn ->
            PromptTurn(
                kind = turn.kind,
                content = turn.content,
                toolName = turn.toolName
            )
        }

        val summary = llmSummarize(promptTurns)
        return TrajectoryTurn(
            index = removedTurns.firstOrNull()?.index ?: 0,
            kind = PromptTurnKind.USER,
            content = "[LLM摘要] ${summary}",
            tokenCount = estimateTokenCount(summary)
        )
    }

    private fun estimateTokenCount(text: String): Int {
        val chineseChars = text.count { it.codePointRangeContainsPoint(0x4E00.toInt(), it.codePoint) }
        val otherChars = text.length - chineseChars
        return (chineseChars / 2 + otherChars / 4).coerceAtLeast(10)
    }
}
