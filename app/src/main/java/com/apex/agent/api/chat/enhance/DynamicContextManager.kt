package com.apex.api.chat.enhance

import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.util.AppLogger

/**
 * 动态上下文管理?* 智能压缩历史对话，提取核心信息，解决长对话上下文溢出问题
 */
object DynamicContextManager {
    private const val TAG = "DynamicContextManager"
    
    // 最大窗口大小：保留最，轮对象    private const val MAX_WINDOW_SIZE = 8
    
    // 核心信息最大长度（字符?   private const val MAX_CORE_INFO_LENGTH = 300
    
    // 会话级核心信息存?   private val sessionCoreInfo = mutableMapOf<String, String>()

    /**
     * 处理聊天上下?    * @param sessionId 当前会话ID
     * @param fullHistory 完整对话历史
     * @return 处理后的模型可用上下?    */
    fun processChatContext(sessionId: String, fullHistory: List<PromptTurn>): List<PromptTurn> {
        // 如果对话轮次很少，直接返回全量历?       val userTurns = fullHistory.count { it.kind == PromptTurnKind.USER }
        if (userTurns <= MAX_WINDOW_SIZE) {
            AppLogger.d(TAG, "${userTurns} <= ${MAX_WINDOW_SIZE}，直接返回全量历?
            return fullHistory
        }

        AppLogger.d(TAG, "${userTurns} > ${MAX_WINDOW_SIZE}，开始压缩处理）
        
        // 分割历史：旧历史 + 最近N?       val splitIndex = findSplitIndex(fullHistory)
        val oldHistory = fullHistory.subList(0, splitIndex)
        val recentHistory = fullHistory.subList(splitIndex, fullHistory.size)

        // 提取历史对话的核心信?       val coreInfo = extractCoreInfo(oldHistory)
        
        // 存储核心信息
        sessionCoreInfo[sessionId] = coreInfo
        AppLogger.d(TAG, "提取核心信息，长?${coreInfo.length}")

        // 构建最终上下文：核心信? 最近对?       return buildContextWithCoreInfo(coreInfo, recentHistory)
    }

    /**
     * 找到分割点，确保recentHistory包含足够的用户轮?    */
    private fun findSplitIndex(fullHistory: List<PromptTurn>): Int {
        var userTurnCount = 0
        var splitIndex = fullHistory.size
        
        // 从后往前数，找到包含MAX_WINDOW_SIZE用户轮次的位?       for (i in fullHistory.size - 1 downTo 0) {
            if (fullHistory[i].kind == PromptTurnKind.USER) {
                userTurnCount++
                if (userTurnCount >= MAX_WINDOW_SIZE) {
                    splitIndex = i
                    break
                }
            }
        }
        
        return splitIndex
    }

    /**
     * 提取核心信息（MVP版本：提取用户输入的核心关键词和需求）
     * @param oldHistory 需要压缩的旧历?    * @return 核心信息摘要
     */
    private fun extractCoreInfo(oldHistory: List<PromptTurn>): String {
        // 提取所有用户输?       val userInputs = oldHistory
            .filter { it.kind == PromptTurnKind.USER }
            .map { it.content }
        
        // 简单的核心信息提取策略
        val coreInfo = buildString {
            append("对话核心要点：\n")
            
            userInputs.take(5).forEachIndexed { index, input ->
                val shortInput = input.take(100)
                append("${index + 1}. ${shortInput}\n")
            }
            
            if (userInputs.size > 5) {
                append("... 还有${userInputs.size - 5}条消?
            }
        }
        
        // 限制最大长?       return coreInfo.take(MAX_CORE_INFO_LENGTH)
    }

    /**
     * 构建包含核心信息的上下文
     * @param coreInfo 核心信息摘要
     * @param recentHistory 最近对话历?    * @return 最终的上下文列?    */
    private fun buildContextWithCoreInfo(
        coreInfo: String,
        recentHistory: List<PromptTurn>
    ): List<PromptTurn> {
        // 创建核心信息的系统提?       val coreInfoPrompt = """
            【以下是之前对话的核心摘要的            ${coreInfo}
            
            【重要提示，            请牢记这些核心信息，在后续回答中保持一致性，            如果用户提到之前讨论过的内容，请根据核心摘要来理解上下文?       """.trimIndent()

        // 构建最终上下文
        return mutableListOf<PromptTurn>().apply {
            // 查找是否已存在系统提?           val existingSystemTurn = recentHistory.find { it.kind == PromptTurnKind.SYSTEM }
            
            if (existingSystemTurn != null) {
                // 如果有系统提示，保留它，然后添加核心信息
                add(existingSystemTurn)
                add(PromptTurn(kind = PromptTurnKind.SYSTEM, content = coreInfoPrompt))
                // 添加其余对话（排除原始系统提示）
                addAll(recentHistory.filter { it.kind != PromptTurnKind.SYSTEM })
            } else {
                // 如果没有系统提示，添加核心信?               add(PromptTurn(kind = PromptTurnKind.SYSTEM, content = coreInfoPrompt))
                addAll(recentHistory)
            }
        }
    }

    /**
     * 清除会话的核心信息（会话结束时调用）
     * @param sessionId 会话ID
     */
    fun clearSessionContext(sessionId: String) {
        sessionCoreInfo.remove(sessionId)
        AppLogger.d(TAG, "清除会话?{sessionId的核心信}?
    }

    /**
     * 获取会话的核心信息（调试用）
     * @param sessionId 会话ID
     * @return 核心信息（如果存在）
     */
    fun getSessionCoreInfo(sessionId: String): String? {
        return sessionCoreInfo[sessionId]
    }

    /**
     * 检查是否需要压缩上下文
     * @param history 对话历史
     * @return 是否需要压?    */
    fun needsCompression(history: List<PromptTurn>): Boolean {
        val userTurns = history.count { it.kind == PromptTurnKind.USER }
        return userTurns > MAX_WINDOW_SIZE
    }
}
