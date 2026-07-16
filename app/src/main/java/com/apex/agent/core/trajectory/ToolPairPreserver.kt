package com.apex.agent.core.trajectory

import com.apex.core.chat.hooks.PromptTurnKind

/**
 * 工具调用配对保持? * 
 * 确保 tool_call ?tool_result 配对完整? * 避免只保留其中一个导致训练信号质量下? */
class ToolPairPreserver(
    private val preserveAllPairs: Boolean = true,
    private val maxPairsToPreserve: Int = Int.MAX_VALUE
) {
    companion object {
        val DEFAULT = ToolPairPreserver()
    }

    /**
     * 分析轨迹中的工具调用配对
     */
    fun analyzePairs(turns: List<TrajectoryTurn>): List<ToolCallPair> {
        if (turns.isEmpty()) return emptyList()

        val pairs = mutableListOf<ToolCallPair>()
        var i = 0

        while (i < turns.size) {
            val turn = turns[i]
            if (turn.isToolCall) {
                val toolResult = if (i + 1 < turns.size && turns[i + 1].isToolResult) {
                    turns[i + 1]
                } else null

                pairs.add(ToolCallPair(
                    toolCall = turn,
                    toolResult = toolResult
                ))
                i += if (toolResult != null) 2 else 1
            } else {
                i++
            }
        }

        return pairs
    }

    /**
     * 保留工具调用配对，返回保留的配对和非配对轮次
     */
    fun preservePairs(turns: List<TrajectoryTurn>): Pair<List<ToolCallPair>, List<TrajectoryTurn>> {
        val pairs = analyzePairs(turns)
        val preservedPairs = pairs.take(maxPairsToPreserve)
        val preservedIndices = mutableSetOf<Int>()

        for (pair in preservedPairs) {
            preservedIndices.add(pair.toolCall.index)
            pair.toolResult?.let { preservedIndices.add(it.index) }
        }

        val nonPairTurns = turns.filter { it.index !in preservedIndices }

        return preservedPairs to nonPairTurns
    }

    /**
     * 检查并修复不完整的配对
     * 
     * 如果发现 tool_call 没有对应?tool_result，标记为警告
     */
    fun validatePairs(turns: List<TrajectoryTurn>): PairValidationResult {
        val pairs = analyzePairs(turns)
        val incompletePairs = pairs.filter { !it.isComplete }
        val orphanedToolResults = findOrphanedToolResults(turns, pairs)

        return PairValidationResult(
            totalPairs = pairs.size,
            completePairs = pairs.count { it.isComplete },
            incompletePairs = incompletePairs.size,
            incompleteDetails = incompletePairs,
            orphanedToolResults = orphanedToolResults,
            isValid = incompletePairs.isEmpty() && orphanedToolResults.isEmpty()
        )
    }

    /**
     * 查找孤立?tool_result（前面没有对应的 tool_call?     */
    private fun findOrphanedToolResults(
        turns: List<TrajectoryTurn>,
        pairs: List<ToolCallPair>
    ): List<TrajectoryTurn> {
        val pairedResultIndices = pairs.mapNotNull { it.toolResult?.index }.toSet()
        return turns.filter { turn ->
            turn.isToolResult && turn.index !in pairedResultIndices
        }
    }

    /**
     * 提取所有工具调?     */
    fun extractToolCalls(turns: List<TrajectoryTurn>): List<TrajectoryTurn> {
        return turns.filter { it.isToolCall }
    }

    /**
     * 提取所有工具结?     */
    fun extractToolResults(turns: List<TrajectoryTurn>): List<TrajectoryTurn> {
        return turns.filter { it.isToolResult }
    }

    /**
     * 计算配对保留?token 总数
     */
    fun calculatePreservedTokens(turns: List<TrajectoryTurn>): Int {
        val pairs = analyzePairs(turns)
        return pairs.sumOf { it.totalTokens }
    }

    /**
     * 获取配对信息统计
     */
    fun getPairStats(turns: List<TrajectoryTurn>): PairStats {
        val pairs = analyzePairs(turns)
        val toolCallTokens = pairs.sumOf { it.toolCall.tokenCount }
        val toolResultTokens = pairs.sumOf { it.toolResult?.tokenCount ?: 0 }

        return PairStats(
            totalPairs = pairs.size,
            completePairs = pairs.count { it.isComplete },
            incompletePairs = pairs.count { !it.isComplete },
            totalToolCallTokens = toolCallTokens,
            totalToolResultTokens = toolResultTokens,
            totalPairTokens = toolCallTokens + toolResultTokens
        )
    }
}

/**
 * 配对验证结果
 */
data class PairValidationResult(
    val totalPairs: Int,
    val completePairs: Int,
    val incompletePairs: Int,
    val incompleteDetails: List<ToolCallPair>,
    val orphanedToolResults: List<TrajectoryTurn>,
    val isValid: Boolean
)

/**
 * 配对统计信息
 */
data class PairStats(
    val totalPairs: Int,
    val completePairs: Int,
    val incompletePairs: Int,
    val totalToolCallTokens: Int,
    val totalToolResultTokens: Int,
    val totalPairTokens: Int
)
