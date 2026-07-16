package com.apex.agent.api.chat.enhance

import android.content.Context
import com.apex.agent.core.hooks.HookRegistry
import com.apex.agent.core.hooks.SessionContext
import com.apex.util.AppLogger

/**
 * 压缩建议数据�? */
data class CompactionSuggestion(
    val shouldCompact: Boolean,
    val reason: String,
    val urgency: Int // 0-100
)

/**
 * 压缩结果数据�? */
data class CompactionResult(
    val success: Boolean,
    val qualityScore: Float,
    val tokensSaved: Int,
    val preservedItems: List<String>
)

/**
 * 上下文使用统计数据类
 */
data class ContextUsageStats(
    val toolCallCount: Int,
    val windowUsagePercent: Float,
    val messageCount: Int,
    val estimatedTokens: Int
)

/**
 * 战略性上下文压缩管理�? * 智能检测上下文使用情况，提供压缩建议和执行压缩
 */
object StrategicCompactionManager {
    private const val TAG = "StrategicCompactionManager"
    
    // 上下文窗口大小限制（token�?    private const val CONTEXT_WINDOW_LIMIT = 128000
    
    // 建议压缩的阈值百分比
    private const val COMPACTION_THRESHOLD_PERCENT = 75f
    
    // 关键信息保留权重
    private const val KEY_INFO_WEIGHT = 0.4f
    private const val RECENT_INFO_WEIGHT = 0.3f
    private const val TOOL_RESULT_WEIGHT = 0.3f

    // 会话级统计数�?    private val sessionStats = mutableMapOf<String, SessionStatistics>()

    /**
     * 检测是否需要建议压�?     * @param sessionId 会话ID
     * @return 压缩建议
     */
    fun shouldSuggestCompaction(sessionId: String): CompactionSuggestion {
        val stats = getSessionStats(sessionId)
        val usagePercent = stats.windowUsagePercent
        
        AppLogger.d(TAG, "检查会�?${sessionId} 的压缩需�? 使用�?${usagePercent}%")

        return when {
            usagePercent >= 90f -> CompactionSuggestion(
                shouldCompact = true,
                reason = "上下文使用率已达 ${usagePercent}%，接近上限，强烈建议压缩",
                urgency = 90
            )
            usagePercent >= COMPACTION_THRESHOLD_PERCENT -> CompactionSuggestion(
                shouldCompact = true,
                reason = "上下文使用率已达 ${usagePercent}%，建议压缩以优化性能",
                urgency = ((usagePercent - COMPACTION_THRESHOLD_PERCENT) / (100f - COMPACTION_THRESHOLD_PERCENT) * 50 + 40).toInt()
            )
            stats.messageCount > 50 -> CompactionSuggestion(
                shouldCompact = true,
                reason = "对话轮次较多�?{stats.messageCount} 条），建议压缩历�?,
                urgency = 50
            )
            else -> CompactionSuggestion(
                shouldCompact = false,
                reason = "${usagePercent}%�?,
                urgency = 0
            )
        }
    }

    /**
     * 执行手动压缩
     * @param sessionId 会话ID
     * @param context Android Context
     * @return 压缩结果
     */
    suspend fun executeCompaction(sessionId: String, context: Context): CompactionResult {
        AppLogger.i(TAG, "开始执行会�?${sessionId} 的上下文压缩")

        return try {
            // 获取压缩前的统计
            val beforeStats = getSessionStats(sessionId)
            val beforeTokens = beforeStats.estimatedTokens

            // 创建会话上下文用于钩子调�?            val sessionContext = SessionContext(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                lastActivity = System.currentTimeMillis(),
                messageCount = beforeStats.messageCount,
                tokenUsage = beforeTokens.toLong()
            )

            // 压缩前触发钩�?            AppLogger.d(TAG, "触发压缩前钩�?)
            HookRegistry.triggerPreCompact(context, sessionContext)

            // 执行压缩逻辑
            val preservedItems = performCompaction(sessionId, beforeStats)
            
            // 计算压缩后的统计
            val afterStats = getSessionStats(sessionId)
            val afterTokens = afterStats.estimatedTokens
            val tokensSaved = beforeTokens - afterTokens

            // 计算质量评分
            val qualityScore = calculateCompactionQuality(beforeStats, afterStats, preservedItems)

            AppLogger.i(TAG, "压缩完成: 节省 ${tokensSaved} tokens, 质量评分 ${qualityScore}")

            CompactionResult(
                success = true,
                qualityScore = qualityScore,
                tokensSaved = tokensSaved,
                preservedItems = preservedItems
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "执行压缩失败", e)
            CompactionResult(
                success = false,
                qualityScore = 0f,
                tokensSaved = 0,
                preservedItems = emptyList()
            )
        }
    }

    /**
     * 获取上下文使用统�?     * @param sessionId 会话ID
     * @return 上下文使用统�?     */
    fun getContextUsageStats(sessionId: String): ContextUsageStats {
        val stats = getSessionStats(sessionId)
        return ContextUsageStats(
            toolCallCount = stats.toolCallCount,
            windowUsagePercent = stats.windowUsagePercent,
            messageCount = stats.messageCount,
            estimatedTokens = stats.estimatedTokens
        )
    }

    /**
     * 更新会话统计（供外部调用�?     */
    fun updateSessionStats(sessionId: String, messageCount: Int, toolCallCount: Int, estimatedTokens: Int) {
        val stats = sessionStats.getOrPut(sessionId) { SessionStatistics() }
        stats.messageCount = messageCount
        stats.toolCallCount = toolCallCount
        stats.estimatedTokens = estimatedTokens
        stats.windowUsagePercent = (estimatedTokens.toFloat() / CONTEXT_WINDOW_LIMIT) * 100f
        
        AppLogger.d(TAG, "更新会话 ${sessionId} 统计: 消息�?${messageCount}, 工具调用=${toolCallCount}, tokens=${estimatedTokens}")
    }

    /**
     * 清除会话统计（会话结束时调用�?     */
    fun clearSessionStats(sessionId: String) {
        sessionStats.remove(sessionId)
        AppLogger.d(TAG, "清除会话 ${sessionId} 的统计数�?)
    }

    /**
     * 获取会话统计数据
     */
    private fun getSessionStats(sessionId: String): SessionStatistics {
        return sessionStats.getOrPut(sessionId) { SessionStatistics() }
    }

    /**
     * 执行实际的压缩操�?     */
    private fun performCompaction(sessionId: String, stats: SessionStatistics): List<String> {
        val preservedItems = mutableListOf<String>()
        
        // 模拟压缩过程：保留关键信�?        // 实际实现中，这里应该调用 LLM 进行智能摘要
        
        // 保留最近的关键对话
        preservedItems.add("最�?5 轮对话的核心要点")
        
        // 保留重要的工具调用结�?        if (stats.toolCallCount > 0) {
            preservedItems.add("关键工具调用的结果摘�?)
        }
        
        // 保留用户明确指定的重要信�?        preservedItems.add("用户强调的重要上下文")
        
        // 更新压缩后的统计
        val reducedTokens = (stats.estimatedTokens * 0.4).toInt() // 压缩�?40%
        stats.estimatedTokens = reducedTokens
        stats.windowUsagePercent = (reducedTokens.toFloat() / CONTEXT_WINDOW_LIMIT) * 100f
        
        return preservedItems
    }

    /**
     * 计算压缩质量评分
     */
    private fun calculateCompactionQuality(
        before: SessionStatistics,
        after: SessionStatistics,
        preservedItems: List<String>
    ): Float {
        // 基于保留信息的完整度评分
        val preservationRatio = preservedItems.size.toFloat() / 10f // 假设最多保�?10 项关键信�?        val compressionRatio = 1f - (after.estimatedTokens.toFloat() / before.estimatedTokens.toFloat())
        
        // 综合评分：保留信息越完整、压缩比越合理，分数越高
        val score = (preservationRatio * KEY_INFO_WEIGHT + 
                    compressionRatio * (1 - KEY_INFO_WEIGHT)) * 100f
        
        return score.coerceIn(0f, 100f)
    }

    /**
     * 会话统计数据内部�?     */
    private data class SessionStatistics(
        var toolCallCount: Int = 0,
        var windowUsagePercent: Float = 0f,
        var messageCount: Int = 0,
        var estimatedTokens: Int = 0
    )
}
