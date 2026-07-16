package com.apex.agent.core.trajectory

import com.apex.agent.core.chat.hooks.PromptTurnKind

/**
 * 压缩策略 - 定义轨迹首尾保护策略
 * 
 * 首轮保护：system、human、首工具调用必须保留
 * 末轮保护：最终动作和结论必须保留
 */
class CompressionStrategy(
    val headProtectionTurns: Int = 3,
    val tailProtectionTurns: Int = 2,
    val preserveFirstToolCall: Boolean = true,
    val preserveLastToolCall: Boolean = true
) {
    companion object {
        val DEFAULT = CompressionStrategy()
        val AGGRESSIVE = CompressionStrategy(headProtectionTurns = 2, tailProtectionTurns = 1)
        val CONSERVATIVE = CompressionStrategy(headProtectionTurns = 5, tailProtectionTurns = 3)
    }

    /**
     * 分析轨迹并确定哪些轮次需要保�?     */
    fun analyzeProtection(trajectory: TrajectoryData): ProtectionPlan {
        val turns = trajectory.turns
        if (turns.isEmpty()) {
            return ProtectionPlan(
                headProtectedIndices = emptySet(),
                tailProtectedIndices = emptySet(),
                middleStartIndex = 0,
                middleEndIndex = 0
            )
        }

        val protectedIndices = mutableSetOf<Int>()

        // 保护首轮：system、human
        var headEnd = 0
        for (i in turns.indices) {
            val turn = turns[i]
            when {
                turn.isSystem -> {
                    protectedIndices.add(i)
                    headEnd = i
                }
                turn.isHuman -> {
                    protectedIndices.add(i)
                    headEnd = i
                    break
                }
            }
        }

        // 如果没有找到 human，找到第一�?assistant
        if (protectedIndices.isEmpty() && turns.isNotEmpty()) {
            protectedIndices.add(0)
            headEnd = 0
        }

        // 保护第一个工具调用（如果启用�?        if (preserveFirstToolCall) {
            val firstToolCallIndex = turns.indexOfFirst { it.isToolCall }
            if (firstToolCallIndex >= 0 && firstToolCallIndex <= headEnd + 2) {
                protectedIndices.add(firstToolCallIndex)
                // 同时保护对应�?tool result
                val toolResultIndex = findMatchingToolResult(turns, firstToolCallIndex)
                if (toolResultIndex != null) {
                    protectedIndices.add(toolResultIndex)
                }
            }
        }

        // 保护末轮：assistant（结论）、最后的工具调用
        var tailStart = turns.size - 1
        for (i in turns.indices.reversed()) {
            val turn = turns[i]
            when {
                turn.kind == PromptTurnKind.ASSISTANT -> {
                    protectedIndices.add(i)
                    tailStart = i
                }
                turn.isToolCall || turn.isToolResult -> {
                    if (preserveLastToolCall) {
                        protectedIndices.add(i)
                        tailStart = minOf(tailStart, i)
                    }
                }
            }
            // 找到至少一�?assistant 就停�?            if (turn.kind == PromptTurnKind.ASSISTANT && i < turns.size - 1) {
                break
            }
        }

        // 确保头部保护数量
        val actualHeadEnd = minOf(headEnd, headProtectionTurns - 1)
        for (i in 0..actualHeadEnd) {
            protectedIndices.add(i)
        }

        // 确保尾部保护数量
        val actualTailStart = maxOf(tailStart, turns.size - tailProtectionTurns)
        for (i in actualTailStart until turns.size) {
            protectedIndices.add(i)
        }

        // 计算中间区域的边�?        val sortedProtected = protectedIndices.sorted()
        val middleStart = if (sortedProtected.isNotEmpty()) sortedProtected.last() + 1 else 0
        val middleEnd = if (sortedProtected.isNotEmpty()) sortedProtected.first() - 1 else turns.size - 1

        return ProtectionPlan(
            headProtectedIndices = protectedIndices.filter { it < (sortedProtected.lastOrNull() ?: headProtectionTurns) }.toSet(),
            tailProtectedIndices = protectedIndices.filter { it >= (sortedProtected.firstOrNull() ?: (turns.size - tailProtectionTurns)) }.toSet(),
            middleStartIndex = middleStart.coerceIn(0, turns.size - 1),
            middleEndIndex = middleEnd.coerceIn(0, turns.size - 1)
        )
    }

    /**
     * 查找匹配�?tool result
     */
    private fun findMatchingToolResult(turns: List<TrajectoryTurn>, toolCallIndex: Int): Int? {
        if (toolCallIndex >= turns.size - 1) return null
        val nextIndex = toolCallIndex + 1
        return if (turns[nextIndex].isToolResult) nextIndex else null
    }

    /**
     * 执行分区
     */
    fun partition(trajectory: TrajectoryData): TrajectoryPartition {
        val plan = analyzeProtection(trajectory)
        val turns = trajectory.turns

        if (turns.isEmpty()) {
            return TrajectoryPartition(emptyList(), emptyList(), emptyList())
        }

        // 找到分界�?        val headEnd = plan.headProtectedIndices.maxOrNull() ?: 0
        val tailStart = plan.tailProtectedIndices.minOrNull() ?: (turns.size - 1)

        val headTurns = if (headEnd >= 0) turns.subList(0, headEnd + 1) else emptyList()
        val middleTurns = if (tailStart > headEnd + 1) {
            turns.subList(headEnd + 1, tailStart)
        } else if (tailStart > 0 && headEnd < turns.size - 1) {
            // 如果中间区域太小，尝试获取一些轮�?            val midStart = minOf(headEnd + 1, turns.size - 1)
            val midEnd = maxOf(tailStart, midStart + 1)
            if (midEnd <= turns.size) turns.subList(midStart, midEnd) else emptyList()
        } else {
            emptyList()
        }
        val tailTurns = if (tailStart < turns.size) turns.subList(tailStart, turns.size) else emptyList()

        return TrajectoryPartition(
            headTurns = headTurns,
            middleTurns = middleTurns,
            tailTurns = tailTurns
        )
    }

    /**
     * 计算保护�?token 数量
     */
    fun calculateProtectedTokens(trajectory: TrajectoryData): Int {
        val plan = analyzeProtection(trajectory)
        val turns = trajectory.turns
        
        return plan.headProtectedIndices.sumOf { turns.getOrNull(it)?.tokenCount ?: 0 } +
                plan.tailProtectedIndices.sumOf { turns.getOrNull(it)?.tokenCount ?: 0 }
    }
}

/**
 * 保护计划 - 描述哪些轮次需要被保护
 */
data class ProtectionPlan(
    val headProtectedIndices: Set<Int>,
    val tailProtectedIndices: Set<Int>,
    val middleStartIndex: Int,
    val middleEndIndex: Int
) {
    val protectedIndices: Set<Int>
        get() = headProtectedIndices + tailProtectedIndices

    val hasMiddleRegion: Boolean
        get() = middleEndIndex >= middleStartIndex

    fun isProtected(index: Int): Boolean = index in protectedIndices

    fun isInMiddleRegion(index: Int): Boolean = index in middleStartIndex..middleEndIndex
}

/**
 * 压缩策略配置
 */
enum class StrategyPreset {
    DEFAULT,
    AGGRESSIVE,
    CONSERVATIVE,
    BALANCED
}

fun getStrategyForPreset(preset: StrategyPreset): CompressionStrategy {
    return when (preset) {
        StrategyPreset.DEFAULT -> CompressionStrategy.DEFAULT
        StrategyPreset.AGGRESSIVE -> CompressionStrategy.AGGRESSIVE
        StrategyPreset.CONSERVATIVE -> CompressionStrategy.CONSERVATIVE
        StrategyPreset.BALANCED -> CompressionStrategy(
            headProtectionTurns = 3,
            tailProtectionTurns = 2,
            preserveFirstToolCall = true,
            preserveLastToolCall = false
        )
    }
}
