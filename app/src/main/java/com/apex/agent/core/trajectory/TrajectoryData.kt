package com.apex.agent.core.trajectory

import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind

/**
 * 轨迹数据 - 表示一个完整的对话轨迹
 * 
 * 用于强化学习训练和轨迹压缩系? */
data class TrajectoryData(
    val id: String,
    val sessionId: String,
    val turns: List<TrajectoryTurn>,
    val totalTokens: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * ?PromptTurn 列表创建 TrajectoryData
         */
        fun fromPromptTurns(
            sessionId: String,
            turns: List<PromptTurn>,
            tokenCounter: (String) -> Int = { it.length / 4 }
        ): TrajectoryData {
            val trajectoryTurns = turns.mapIndexed { index, turn ->
                TrajectoryTurn(
                    index = index,
                    kind = turn.kind,
                    content = turn.content,
                    toolName = turn.toolName,
                    metadata = turn.metadata,
                    tokenCount = tokenCounter(turn.content)
                )
            }
            val totalTokens = trajectoryTurns.sumOf { it.tokenCount }
            return TrajectoryData(
                id = "traj_${System.currentTimeMillis()}_${(0..9999).random()}",
                sessionId = sessionId,
                turns = trajectoryTurns,
                totalTokens = totalTokens
            )
        }
    }

    /**
     * 转换?PromptTurn 列表
     */
    fun toPromptTurns(): List<PromptTurn> {
        return turns.map { turn ->
            PromptTurn(
                kind = turn.kind,
                content = turn.content,
                toolName = turn.toolName,
                metadata = turn.metadata
            )
        }
    }
}

/**
 * 轨迹轮次 - 表示轨迹中的单个轮次
 */
data class TrajectoryTurn(
    val index: Int,
    val kind: PromptTurnKind,
    val content: String,
    val toolName: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val tokenCount: Int = 0
) {
    /**
     * 是否是工具调用轮?     */
    val isToolCall: Boolean
        get() = kind == PromptTurnKind.TOOL_CALL

    /**
     * 是否是工具结果轮?     */
    val isToolResult: Boolean
        get() = kind == PromptTurnKind.TOOL_RESULT

    /**
     * 是否是系统轮?     */
    val isSystem: Boolean
        get() = kind == PromptTurnKind.SYSTEM

    /**
     * 是否是人类轮?     */
    val isHuman: Boolean
        get() = kind == PromptTurnKind.USER
}

/**
 * 压缩后的轨迹区域
 */
data class CompressedRegion(
    val startIndex: Int,
    val endIndex: Int,
    val originalTurns: List<TrajectoryTurn>,
    val summaryTurn: TrajectoryTurn,
    val savedTokens: Int
)

/**
 * 轨迹压缩结果
 */
data class CompressionResult(
    val originalTrajectory: TrajectoryData,
    val compressedTrajectory: TrajectoryData,
    val compressedRegions: List<CompressedRegion>,
    val originalTokens: Int,
    val compressedTokens: Int,
    val compressionRatio: Double
) {
    val savedTokens: Int
        get() = originalTokens - compressedTokens

    val savedRatio: Double
        get() = if (originalTokens > 0) savedTokens.toDouble() / originalTokens else 0.0
}

/**
 * 轨迹分区 - 将轨迹分为头部、中间、尾? */
data class TrajectoryPartition(
    val headTurns: List<TrajectoryTurn>,
    val middleTurns: List<TrajectoryTurn>,
    val tailTurns: List<TrajectoryTurn>
) {
    val allTurns: List<TrajectoryTurn>
        get() = headTurns + middleTurns + tailTurns

    val isEmpty: Boolean
        get() = allTurns.isEmpty()
}

/**
 * Token 预算配置
 */
data class TokenBudget(
    val maxTokens: Int,
    val headProtectionTokens: Int = 500,
    val tailProtectionTokens: Int = 500,
    val minMiddleTokens: Int = 100
) {
    val availableForMiddle: Int
        get() = (maxTokens - headProtectionTokens - tailProtectionTokens).coerceAtLeast(minMiddleTokens)
}

/**
 * 工具调用配对
 */
data class ToolCallPair(
    val toolCall: TrajectoryTurn,
    val toolResult: TrajectoryTurn?
) {
    val isComplete: Boolean
        get() = toolResult != null

    val totalTokens: Int
        get() = toolCall.tokenCount + (toolResult?.tokenCount ?: 0)
}

/**
 * 轨迹统计信息
 */
data class TrajectoryStats(
    val totalTurns: Int,
    val totalTokens: Int,
    val toolCallPairs: Int,
    val systemTurns: Int,
    val humanTurns: Int,
    val assistantTurns: Int,
    val toolCallTurns: Int,
    val toolResultTurns: Int,
    val summaryTurns: Int
)
