package com.apex.services

import android.content.Context
import com.apex.util.AppLogger
import androidx.compose.ui.text.input.TextFieldValue
import com.apex.api.chat.EnhancedAIService
import com.apex.data.model.AttachmentInfo
import com.apex.data.model.ChatMessage
import com.apex.data.model.InputProcessingState
import com.apex.data.model.PromptFunctionType
import com.apex.services.core.ApiConfigDelegate
import com.apex.services.core.AttachmentDelegate
import com.apex.services.core.ChatSelectionMode
import com.apex.services.core.ChatHistoryDelegate
import com.apex.services.core.MessageCoordinationDelegate
import com.apex.services.core.MessageProcessingDelegate
import com.apex.services.core.TokenStatisticsDelegate
import com.apex.core.tools.AIToolHandler
import com.apex.ui.features.chat.viewmodel.UiStateDelegate
import com.apex.util.stream.SharedStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 聊天服务核心�?* 
 * 整合所有聊天业务逻辑，可的FloatingChatService ，ChatViewModel 使用
 * 生命周期独立，ViewModel，绑定到传入，CoroutineScope
 */
class ChatServiceCore(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val selectionMode: ChatSelectionMode = ChatSelectionMode.FOLLOW_GLOBAL
) {
    companion object {
        private const val TAG = "ChatServiceCore"
    }

    // EnhancedAIService 实例（全局单例�?   private var enhancedAiService: EnhancedAIService? = null

    // 委托实例
    private lateinit var messageProcessingDelegate: MessageProcessingDelegate
    private lateinit var chatHistoryDelegate: ChatHistoryDelegate
    private lateinit var apiConfigDelegate: ApiConfigDelegate
    private lateinit var tokenStatisticsDelegate: TokenStatisticsDelegate
    private lateinit var attachmentDelegate: AttachmentDelegate
    private lateinit var uiStateDelegate: UiStateDelegate
    private lateinit var messageCoordinationDelegate: MessageCoordinationDelegate

    // 初始化状�?   private var initialized = false

    // 回调：当 EnhancedAIService 初始化或更新�?   private var onEnhancedAiServiceReady: ((EnhancedAIService) -> Unit)? = null
    
    // 额外，onTurnComplete 回调（用于悬浮窗通知应用等场景）
    private var additionalOnTurnComplete: ((String?, Int, Int, Int) -> Unit)? = null
    private var uiBridge: ChatServiceUiBridge = EmptyChatServiceUiBridge

    init {
        AppLogger.d(TAG, "ChatServiceCore 初始�?
        initializeDelegates()
    }
    
    private fun initializeDelegates() {
        // 初始，UI 状态委�?       uiStateDelegate = UiStateDelegate()
        
        // 初始，API 配置委托
        apiConfigDelegate = ApiConfigDelegate(
            context = context,
            coroutineScope = coroutineScope,
            onConfigChanged = { service ->
                enhancedAiService = service
                // 当服务初始化后，设置 token 统计收集�?               tokenStatisticsDelegate.setupCollectors()
                // 通知外部监听�?               onEnhancedAiServiceReady?.invoke(service)
                AppLogger.d(TAG, "EnhancedAIService 已更新）
            }
        )

        // 初始，Token 统计委托
        tokenStatisticsDelegate = TokenStatisticsDelegate(
            coroutineScope = coroutineScope,
            getEnhancedAiService = { enhancedAiService }
        )

        // 初始化附件委�?       attachmentDelegate = AttachmentDelegate(
            context = context,
            toolHandler = AIToolHandler.getInstance(context)
        )

        // 初始化聊天历史委�?       chatHistoryDelegate = ChatHistoryDelegate(
            context = context,
            coroutineScope = coroutineScope,
            selectionMode = selectionMode,
            onTokenStatisticsLoaded = { chatId, inputTokens, outputTokens, windowSize ->
                tokenStatisticsDelegate.setActiveChatId(chatId)
                tokenStatisticsDelegate.setTokenCounts(chatId, inputTokens, outputTokens, windowSize)
            },
            getEnhancedAiService = { enhancedAiService },
            ensureAiServiceAvailable = {
                // 确保 AI 服务可用
                if (enhancedAiService == null) {
                    enhancedAiService = EnhancedAIService.getInstance(context)
                }
            },
            getChatStatistics = {
                val (inputTokens, outputTokens) = tokenStatisticsDelegate.getCumulativeTokenCounts()
                val windowSize = tokenStatisticsDelegate.getLastCurrentWindowSize()
                Triple(inputTokens, outputTokens, windowSize)
            },
            onScrollToBottom = {
                messageProcessingDelegate.scrollToBottom()
            }
        )

        coroutineScope.launch {
            chatHistoryDelegate.currentChatId.collect { chatId ->
                tokenStatisticsDelegate.setActiveChatId(chatId)
                if (chatId != null) {
                    tokenStatisticsDelegate.bindChatService(
                        chatId,
                        EnhancedAIService.getChatInstance(context, chatId)
                    )
                }
            }
        }

        // 初始化消息处理委�?       messageProcessingDelegate = MessageProcessingDelegate(
            context = context,
            coroutineScope = coroutineScope,
            getEnhancedAiService = { enhancedAiService },
            getChatHistory = { chatId -> chatHistoryDelegate.getChatHistory(chatId) },
            addMessageToChat = { chatId, message ->
                chatHistoryDelegate.addMessageToChat(message, chatId)
            },
            saveCurrentChat = {
                val (inputTokens, outputTokens) = tokenStatisticsDelegate.getCumulativeTokenCounts()
                val windowSize = tokenStatisticsDelegate.getLastCurrentWindowSize()
                chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens, windowSize)
            },
            showErrorMessage = { error ->
                AppLogger.e(TAG, "错误: ${error}")
                // 错误消息可以通过回调传递给 UI
            },
            updateChatTitle = { chatId, title ->
                chatHistoryDelegate.updateChatTitle(chatId, title)
            },
            onTurnComplete = { chatId, service, nextWindowSize ->
                tokenStatisticsDelegate.updateCumulativeStatistics(chatId, service)
                val (inputTokens, outputTokens) = tokenStatisticsDelegate.getCumulativeTokenCounts(chatId)
                val windowSize = nextWindowSize ?: tokenStatisticsDelegate.getLastCurrentWindowSize(chatId)
                tokenStatisticsDelegate.setTokenCounts(chatId, inputTokens, outputTokens, windowSize)
                chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens, windowSize, chatIdOverride = chatId)
                additionalOnTurnComplete?.invoke(chatId, inputTokens, outputTokens, windowSize)
            },
            getIsAutoReadEnabled = {
                apiConfigDelegate.enableAutoRead.value
            },
            speakMessageHandler = { text, _ ->
                AppLogger.d(TAG, "朗读消息: ${text}")
            },
            onTokenLimitExceeded = { chatId, roleCardId, isGroupOrchestrationTurn, groupParticipantNamesText ->
                messageCoordinationDelegate.handleTokenLimitExceeded(
                    chatId = chatId,
                    roleCardId = roleCardId,
                    isGroupOrchestrationTurn = isGroupOrchestrationTurn,
                    groupParticipantNamesText = groupParticipantNamesText
                )
            }
        )

        // 初始化消息协调委�?       messageCoordinationDelegate = MessageCoordinationDelegate(
            context = context,
            coroutineScope = coroutineScope,
            chatHistoryDelegate = chatHistoryDelegate,
            messageProcessingDelegate = messageProcessingDelegate,
            tokenStatsDelegate = tokenStatisticsDelegate,
            apiConfigDelegate = apiConfigDelegate,
            attachmentDelegate = attachmentDelegate,
            uiStateDelegate = uiStateDelegate,
            getEnhancedAiService = { enhancedAiService },
            uiBridge = uiBridge
        )

        chatHistoryDelegate.setBeforeDestructiveHistoryMutation { chatId ->
            messageCoordinationDelegate.cancelSummaryForDestructiveMutation(chatId)
            messageProcessingDelegate.cancelMessageForDestructiveMutation(chatId)
        }
        chatHistoryDelegate.setAfterDestructiveHistoryMutation { chatId ->
            messageCoordinationDelegate.refreshStableContextWindow(chatId = chatId)
        }

        initialized = true
        AppLogger.d(TAG, "所有委托已初始�?
    }

    // ========== 消息处理相关 ==========

    /** 发送用户消息（使用 MessageCoordinationDelegate，包含总结逻辑�?/
    fun sendUserMessage(
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        roleCardIdOverride: String? = null,
        chatIdOverride: String? = null,
        messageTextOverride: String? = null,
        proxySenderNameOverride: String? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ) {
        messageCoordinationDelegate.sendUserMessage(
            promptFunctionType = promptFunctionType,
            roleCardIdOverride = roleCardIdOverride,
            chatIdOverride = chatIdOverride,
            messageTextOverride = messageTextOverride,
            proxySenderNameOverride = proxySenderNameOverride,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
    }

    /** 取消当前消息 */
    fun cancelCurrentMessage() {
        // 先取消总结（如果正在进行）
        messageCoordinationDelegate.cancelSummary()
        // 然后取消“当前聊天”的消息处理
        val chatId = chatHistoryDelegate.currentChatId.value
        if (chatId != null) {
            messageProcessingDelegate.cancelMessage(chatId)
        }
    }

    fun cancelMessage(chatId: String) {
        messageCoordinationDelegate.cancelSummaryForChat(chatId)
        messageProcessingDelegate.cancelMessage(chatId)
    }

    /** 更新用户消息 */
    fun updateUserMessage(message: String) {
        messageProcessingDelegate.updateUserMessage(message)
    }

    fun getResponseStream(chatId: String): SharedStream<String>? {
        return messageProcessingDelegate.getResponseStream(chatId)
    }

    // ========== 聊天历史相关 ==========

    /** 创建新的聊天 */
    fun createNewChat(
        characterCardName: String? = null,
        group: String? = null,
        inheritGroupFromCurrent: Boolean = true,
        setAsCurrentChat: Boolean = true,
        characterCardId: String? = null
    ) {
        chatHistoryDelegate.createNewChat(
            characterCardName = characterCardName,
            group = group,
            inheritGroupFromCurrent = inheritGroupFromCurrent,
            setAsCurrentChat = setAsCurrentChat,
            characterCardId = characterCardId
        )
    }

    /** 切换聊天 */
    fun switchChat(chatId: String) {
        chatHistoryDelegate.switchChat(chatId)
    }

    /**
     * 切换聊天（仅切换本地状态，不写回全局 currentChatId），     * 悬浮窗可用此方法在窗口内切换会话，但不影响主界面�?    */
    fun switchChatLocal(chatId: String) {
        chatHistoryDelegate.switchChat(chatId, syncToGlobal = false)
    }

    /**
     * 将当前本，chatId 写回全局 currentChatId，用于“返回主应用”时同步�?    */
    fun syncCurrentChatIdToGlobal() {
        val chatId = chatHistoryDelegate.currentChatId.value ?: return
        chatHistoryDelegate.switchChat(chatId, syncToGlobal = true)
    }

    /** 删除聊天历史 */
    fun deleteChatHistory(chatId: String) {
        chatHistoryDelegate.deleteChatHistory(chatId)
    }

    /** 删除消息 */
    fun deleteMessage(index: Int) {
        chatHistoryDelegate.deleteMessage(index)
    }

    /** 清空当前聊天 */
    fun clearCurrentChat() {
        chatHistoryDelegate.clearCurrentChat()
    }

    /** 更新聊天标题 */
    fun updateChatTitle(chatId: String, title: String) {
        chatHistoryDelegate.updateChatTitle(chatId, title)
    }

    // ========== Token 统计相关 ==========

    /** 重置 token 统计 */
    fun resetTokenStatistics() {
        tokenStatisticsDelegate.resetTokenStatistics()
    }

    /** 更新累计统计 */
    fun updateCumulativeStatistics() {
        tokenStatisticsDelegate.updateCumulativeStatistics()
    }

    // ========== 附件管理相关 ==========

    /** 获取 AttachmentDelegate 实例 */
    fun getAttachmentDelegate(): AttachmentDelegate = attachmentDelegate

    /** 添加附件 */
    suspend fun handleAttachment(filePath: String) {
        attachmentDelegate.handleAttachment(filePath)
    }

    /** 移除附件 */
    fun removeAttachment(filePath: String) {
        attachmentDelegate.removeAttachment(filePath)
    }

    /** 清空所有附�?/
    fun clearAttachments() {
        attachmentDelegate.clearAttachments()
    }

    // ========== StateFlow 暴露 ==========

    // 消息处理相关
    val userMessage: StateFlow<TextFieldValue>
        get() = messageProcessingDelegate.userMessage

    val isLoading: StateFlow<Boolean>
        get() = messageProcessingDelegate.isLoading

    val activeStreamingChatIds: StateFlow<Set<String>>
        get() = messageProcessingDelegate.activeStreamingChatIds

    val inputProcessingStateByChatId: StateFlow<Map<String, InputProcessingState>>
        get() = messageProcessingDelegate.inputProcessingStateByChatId

    val currentTurnToolInvocationCountByChatId: StateFlow<Map<String, Int>>
        get() = messageProcessingDelegate.currentTurnToolInvocationCountByChatId

    val scrollToBottomEvent: SharedFlow<Unit>
        get() = messageProcessingDelegate.scrollToBottomEvent

    val nonFatalErrorEvent: SharedFlow<String>
        get() = messageProcessingDelegate.nonFatalErrorEvent

    val isSummarizing: StateFlow<Boolean>
        get() = messageCoordinationDelegate.isSummarizing

    // 聊天历史相关
    val chatHistory: StateFlow<List<ChatMessage>>
        get() = chatHistoryDelegate.chatHistory

    val currentChatId: StateFlow<String?>
        get() = chatHistoryDelegate.currentChatId

    val chatHistories: StateFlow<List<com.apex.data.model.ChatHistory>>
        get() = chatHistoryDelegate.chatHistories

    val showChatHistorySelector: StateFlow<Boolean>
        get() = chatHistoryDelegate.showChatHistorySelector

    // API 配置相关
    val enableThinkingMode: StateFlow<Boolean>
        get() = apiConfigDelegate.enableThinkingMode

    val enableThinkingGuidance: StateFlow<Boolean>
        get() = apiConfigDelegate.enableThinkingGuidance

    val enableMemoryQuery: StateFlow<Boolean>
        get() = apiConfigDelegate.enableMemoryQuery

    val enableAutoRead: StateFlow<Boolean>
        get() = apiConfigDelegate.enableAutoRead

    val contextLength: StateFlow<Float>
        get() = apiConfigDelegate.contextLength

    val summaryTokenThreshold: StateFlow<Float>
        get() = apiConfigDelegate.summaryTokenThreshold

    val enableSummary: StateFlow<Boolean>
        get() = apiConfigDelegate.enableSummary

    val enableTools: StateFlow<Boolean>
        get() = apiConfigDelegate.enableTools

    // Token 统计相关
    val cumulativeInputTokensFlow: StateFlow<Int>
        get() = tokenStatisticsDelegate.cumulativeInputTokensFlow

    val cumulativeOutputTokensFlow: StateFlow<Int>
        get() = tokenStatisticsDelegate.cumulativeOutputTokensFlow

    val currentWindowSizeFlow: StateFlow<Int>
        get() = tokenStatisticsDelegate.currentWindowSizeFlow

    val perRequestTokenCountFlow: StateFlow<Pair<Int, Int>?>
        get() = tokenStatisticsDelegate.perRequestTokenCountFlow

    // 附件相关
    val attachments: StateFlow<List<AttachmentInfo>>
        get() = attachmentDelegate.attachments

    val attachmentToastEvent: SharedFlow<String>
        get() = attachmentDelegate.toastEvent

    // ========== 其他方法 ==========

    /** 获取 UiStateDelegate 实例 */
    fun getUiStateDelegate(): UiStateDelegate = uiStateDelegate

    fun getApiConfigDelegate(): ApiConfigDelegate = apiConfigDelegate

    fun getTokenStatisticsDelegate(): TokenStatisticsDelegate = tokenStatisticsDelegate

    fun getChatHistoryDelegate(): ChatHistoryDelegate = chatHistoryDelegate

    fun getMessageProcessingDelegate(): MessageProcessingDelegate = messageProcessingDelegate

    fun getMessageCoordinationDelegate(): MessageCoordinationDelegate = messageCoordinationDelegate

    /** 获取 EnhancedAIService 实例 */
    fun getEnhancedAiService(): EnhancedAIService? = enhancedAiService

    /** 检查是否已初始�?/
    fun isInitialized(): Boolean = initialized
    
    /** 设置 EnhancedAIService 就绪回调 */
    fun setOnEnhancedAiServiceReady(callback: (EnhancedAIService) -> Unit) {
        onEnhancedAiServiceReady = callback
        // 如果已经初始化，立即调用回调
        enhancedAiService?.let { callback(it) }
    }
    
    /** 设置额外，onTurnComplete 回调（用于悬浮窗通知应用等场景） */
    fun setAdditionalOnTurnComplete(callback: ((chatId: String?, inputTokens: Int, outputTokens: Int, windowSize: Int) -> Unit)) {
        additionalOnTurnComplete = callback
    }

    fun setUiBridge(uiBridge: ChatServiceUiBridge) {
        this.uiBridge = uiBridge
        if (::messageCoordinationDelegate.isInitialized) {
            messageCoordinationDelegate.setUiBridge(uiBridge)
        }
    }

    fun setSpeakMessageHandler(handler: (String, Boolean) -> Unit) {
        if (::messageProcessingDelegate.isInitialized) {
            messageProcessingDelegate.setSpeakMessageHandler(handler)
        }
    }
    
    /** 重新加载聊天消息（智能合并） */
    suspend fun reloadChatMessagesSmart(chatId: String) {
        chatHistoryDelegate.reloadChatMessagesSmart(chatId)
    }
}

