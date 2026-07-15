package com.apex.services.core

import android.content.Context
import com.apex.util.AppLogger
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.apex.agent.R
import com.apex.api.chat.EnhancedAIService
import com.apex.core.chat.AIMessageManager
import com.apex.core.chat.logMessageTiming
import com.apex.core.chat.messageTimingNow
import com.apex.core.tools.AIToolHandler
import com.apex.core.tools.agent.PhoneAgentJobRegistry
import com.apex.data.model.*
import com.apex.data.model.InputProcessingState as EnhancedInputProcessingState
import com.apex.data.model.PromptFunctionType
import com.apex.util.stream.SharedStream
import com.apex.util.stream.share
import com.apex.util.stream.shareRevisable
import com.apex.util.stream.TextStreamEventCarrier
import com.apex.util.stream.TextStreamEventType
import com.apex.util.stream.TextStreamRevisionTracker
import com.apex.util.WaifuMessageProcessor
import com.apex.data.preferences.ApiPreferences
import com.apex.data.preferences.WaifuPreferences
import com.apex.data.preferences.FunctionalConfigManager
import com.apex.data.preferences.ModelConfigManager
import com.apex.data.preferences.UserPreferencesManager
import com.apex.ui.floating.ui.fullscreen.XmlTextProcessor
import com.apex.ui.features.chat.webview.workspace.WorkspaceBackupManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.apex.core.tools.ToolProgressBus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import com.apex.util.TokenSavingManager
import com.apex.util.ChatUtils
import com.apex.util.Message
import com.apex.agent.core.security.InputSanitizer
import com.apex.agent.core.security.RiskLevel
import com.apex.agent.core.security.SanitizeResult
import com.apex.agent.core.tools.defaultTool.standard.name
import com.apex.agent.ui.screens.chat.ChatMessage
import com.apex.core.tools.javascript.not
import com.apex.services.core.ChatRuntime

/** 委托类，负责处理消息处理相关功能 */
class MessageProcessingDelegate(
        private val context: Context,
        private val coroutineScope: CoroutineScope,
        private val getEnhancedAiService: () -> EnhancedAIService?,
        private val getChatHistory: suspend (String) -> List<ChatMessage>,
        private val addMessageToChat: suspend (String, ChatMessage) -> Unit,
        private val saveCurrentChat: () -> Unit,
        private val showErrorMessage: (String) -> Unit,
        private val updateChatTitle: (chatId: String, title: String) -> Unit,
        private val onTurnComplete: (chatId: String?, service: EnhancedAIService, nextWindowSize: Int) -> Unit,
        private val onTokenLimitExceeded: suspend (
            chatId: String?,
            roleCardId: String?,
            isGroupOrchestrationTurn: Boolean,
            groupParticipantNamesText: String?
        ) -> Unit,
        // 添加自动朗读相关的回
    private val getIsAutoReadEnabled: () -> Boolean,
        private var speakMessageHandler: (String, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "MessageProcessingDelegate"
        private const val STREAM_SCROLL_THROTTLE_MS = 200L
        private const val STREAM_PERSIST_INTERVAL_MS = 1000L
    }

    // 模型配置管理
    private val modelConfigManager = ModelConfigManager(context)
    
    // 功能配置管理器，用于获取正确的模型配置ID
    private val functionalConfigManager = FunctionalConfigManager(context)

    // 省Token模式管理。
    private val tokenSavingManager = TokenSavingManager.getInstance(context)
        private val _userMessage = MutableStateFlow(TextFieldValue(""))
        val userMessage: StateFlow<TextFieldValue> = _userMessage.asStateFlow()
        private val _isLoading = MutableStateFlow(false)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
        private val _activeStreamingChatIds = MutableStateFlow<Set<String>>(emptySet())
        val activeStreamingChatIds: StateFlow<Set<String>> = _activeStreamingChatIds.asStateFlow()
        private val _inputProcessingStateByChatId =
        MutableStateFlow<Map<String, EnhancedInputProcessingState>>(emptyMap())
        val inputProcessingStateByChatId: StateFlow<Map<String, EnhancedInputProcessingState>> =
        _inputProcessingStateByChatId.asStateFlow()
        private val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val scrollToBottomEvent = _scrollToBottomEvent.asSharedFlow()
        private val _nonFatalErrorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val nonFatalErrorEvent = _nonFatalErrorEvent.asSharedFlow()
        private val _turnCompleteCounterByChatId = MutableStateFlow<Map<String, Long>>(emptyMap())
        val turnCompleteCounterByChatId: StateFlow<Map<String, Long>> =
        _turnCompleteCounterByChatId.asStateFlow()
        private val _currentTurnToolInvocationCountByChatId =
        MutableStateFlow<Map<String, Int>>(emptyMap())
        val currentTurnToolInvocationCountByChatId: StateFlow<Map<String, Int>> =
        _currentTurnToolInvocationCountByChatId.asStateFlow()

    // 输入安全告警状。
    data class SecurityAlert(
        val riskLevel: RiskLevel,
        val findings: List<String>,
        val originalMessage: String
    )
        private val _securityAlert = MutableStateFlow<SecurityAlert?>(null)
        val securityAlert: StateFlow<SecurityAlert?> = _securityAlert.asStateFlow()

    // 输入消毒器实。
    private val inputSanitizer = InputSanitizer()

    // 当前活跃的AI响应
    private data class ChatRuntime(
    var sendJob: Job? = null,
        var responseStream: SharedStream<String>? = null,
        var streamCollectionJob: Job? = null,
        var stateCollectionJob: Job? = null,
        val isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    )
        private val chatRuntimes = ConcurrentHashMap<String, ChatRuntime>()
        private val lastScrollEmitMsByChatKey = ConcurrentHashMap<String, AtomicLong>()
        private val suppressIdleCompletedStateByChatId = ConcurrentHashMap<String, Boolean>()
        private val pendingAsyncSummaryUiByChatId = ConcurrentHashMap<String, Boolean>()

    // 速率限制：每chatId最近一次发送时间
    private val lastSendTime = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 检查指定聊天的发送频率是否超过限制
     * @param chatId 聊天会话ID
     * @param minIntervalMs 最小发送间隔（毫秒），默认1000ms
     * @return true表示允许发送，false表示频率过高被限制
     */
    private fun checkRateLimit(chatId: String, minIntervalMs: Long = 1000L): Boolean {
        val now = System.currentTimeMillis()
        val last = lastSendTime.getOrPut(chatId) { AtomicLong(0L) }
        val prev = last.get()
        return if (now - prev >= minIntervalMs && last.compareAndSet(prev, now)) {
            true
        } else {
            AppLogger.w(TAG, "速率限制触发: chatId=$chatId, 距上次仅 ${now - prev}ms < ${minIntervalMs}ms")
            false
        }
    }

    /**
     * 添加或更新聊天消息（基于消息ID去重）
     * 如果消息已存在则更新，不存在则新增
     */
    private suspend fun addOrUpdateMessage(chatId: String, message: ChatMessage) {
        addMessageToChat(chatId, message)
    }
        private fun chatKey(chatId: String): String = chatId ?: "__DEFAULT_CHAT__"
        private fun tryEmitScrollToBottomThrottled(chatId: String) {
        val key = chatKey(chatId)
        val now = System.currentTimeMillis()
        val last = lastScrollEmitMsByChatKey.getOrPut(key) { AtomicLong(0L) }
        val prev = last.get()
        if (now - prev >= STREAM_SCROLL_THROTTLE_MS && last.compareAndSet(prev, now)) {
            _scrollToBottomEvent.tryEmit(Unit)
        }
    }
        private fun forceEmitScrollToBottom(chatId: String) {
        val key = chatKey(chatId)
        lastScrollEmitMsByChatKey.getOrPut(key) { AtomicLong(0L) }.set(System.currentTimeMillis())
        _scrollToBottomEvent.tryEmit(Unit)
    }
        private fun runtimeFor(chatId: String): ChatRuntime {
        val key = chatKey(chatId)
        return chatRuntimes[key] ?: ChatRuntime().also { chatRuntimes[key] = it }
    }
        private fun updateGlobalLoadingState() {
        val anyLoading = chatRuntimes.values.any { it.isLoading.value }
        val activeChatIds = chatRuntimes
            .filter { (_, runtime) -> runtime.isLoading.value }
            .keys
            .filter { it != "__DEFAULT_CHAT__" }
            .toSet()

        _activeStreamingChatIds.value = activeChatIds
        _isLoading.value = anyLoading
    }
        private fun isTerminalInputState(state: EnhancedInputProcessingState): Boolean {
        return state is EnhancedInputProcessingState.Idle ||
            state is EnhancedInputProcessingState.Completed
    }
        private fun setChatInputProcessingState(chatId: String?, state: EnhancedInputProcessingState) {
        if (chatId != null &&
            runtimeFor(chatId).isLoading.value &&
            isTerminalInputState(state)
        ) {
            return
        }
        if (chatId != null && suppressIdleCompletedStateByChatId.containsKey(chatId)) {
            if (isTerminalInputState(state)) {
                return
            }
        }
        if (state !is EnhancedInputProcessingState.ExecutingTool &&
            state !is EnhancedInputProcessingState.Summarizing
        ) {
            ToolProgressBus.clear()
        }
        val key = chatKey(chatId)
        val map = _inputProcessingStateByChatId.value.toMutableMap()
        map[key] = state
        _inputProcessingStateByChatId.value = map
    }
        fun setSuppressIdleCompletedStateForChat(chatId: String, suppress: Boolean) {
        if (suppress) {
            suppressIdleCompletedStateByChatId[chatId] = true
        } else {
            suppressIdleCompletedStateByChatId.remove(chatId)
        }
    }
        fun setPendingAsyncSummaryUiForChat(chatId: String, pending: Boolean) {
        if (pending) {
            pendingAsyncSummaryUiByChatId[chatId] = true
        } else {
            pendingAsyncSummaryUiByChatId.remove(chatId)
        }
    }
        fun setInputProcessingStateForChat(chatId: String, state: EnhancedInputProcessingState) {
        setChatInputProcessingState(chatId, state)
    }

    suspend fun buildUserMessageContentForGroupOrchestration(
        messageText: String,
        attachments: List<AttachmentInfo>,
        enableMemoryQuery: Boolean,
        enableWorkspaceAttachment: Boolean,
        workspacePath: String?,
        workspaceEnv: String?,
        replyToMessage: ChatMessage?,
        chatId: String? = null
    ): String {
        val totalStartTime = messageTimingNow()
        val configId = functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
        val currentModelConfig = modelConfigManager.getModelConfigFlow(configId).first()
        val enableDirectImageProcessing = currentModelConfig.enableDirectImageProcessing
        val enableDirectAudioProcessing = currentModelConfig.enableDirectAudioProcessing
        val enableDirectVideoProcessing = currentModelConfig.enableDirectVideoProcessing

        val finalMessageContent = AIMessageManager.buildUserMessageContent(
            messageText = messageText,
            attachments = attachments,
            enableMemoryQuery = enableMemoryQuery,
            enableWorkspaceAttachment = enableWorkspaceAttachment,
            workspacePath = workspacePath,
            workspaceEnv = workspaceEnv,
            replyToMessage = replyToMessage,
            enableDirectImageProcessing = enableDirectImageProcessing,
            enableDirectAudioProcessing = enableDirectAudioProcessing,
            enableDirectVideoProcessing = enableDirectVideoProcessing,
            chatId = chatId
        )
        logMessageTiming(
            stage = "delegate.groupOrchestration.buildUserMessageContent",
            startTimeMs = totalStartTime,
            details = "attachments=${attachments.size}, configId=${configId}, finalLength=${finalMessageContent.length}"
        )
        return finalMessageContent
    }
        fun getResponseStream(chatId: String): SharedStream<String>? {
        return chatRuntimes[chatKey(chatId)]?.responseStream
    }
        private fun resolveFinalContent(aiMessage: ChatMessage): String {
        val sharedStream = aiMessage.contentStream as? SharedStream<String>
        val replayChunks = sharedStream?.replayCache
        val eventCarrier = aiMessage.contentStream as? TextStreamEventCarrier

        return if (eventCarrier?.eventChannel?.replayCache?.isNotEmpty() == true) {
            aiMessage.content
        } else if (!replayChunks.isNullOrEmpty()) {
            replayChunks.joinToString(separator = "")
        } else {
            aiMessage.content
        }
    }
        private fun ChatMessage.withTurnMetrics(
        inputTokens: Int,
        outputTokens: Int,
        cachedInputTokens: Int,
        sentAt: Long,
        outputDurationMs: Long,
        waitDurationMs: Long
    ): ChatMessage {
        return copy(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            sentAt = sentAt,
            outputDurationMs = outputDurationMs,
            waitDurationMs = waitDurationMs
        )
    }
        private suspend fun detachStreamingAiMessage(chatId: String) {
        val streamingMessage =
            getChatHistory(chatId).lastOrNull { it.sender == "ai" && it.contentStream != null }
                ?: return
        val finalContent = resolveFinalContent(streamingMessage)
        streamingMessage.content = finalContent
        val finalMessage = streamingMessage.copy(content = finalContent, contentStream = null)
        withContext(Dispatchers.Main) {
            addMessageToChat(chatId, finalMessage)
        }
    }
        private suspend fun cancelMessageInternal(chatId: String, keepPartialResponse: Boolean) {
        val chatRuntime = runtimeFor(chatId)
        val jobsToCancel =
            linkedSetOf<Job>().apply {
                chatRuntime.sendJob?.let { add(it) }
                chatRuntime.stateCollectionJob?.let { add(it) }
                chatRuntime.streamCollectionJob?.let { add(it) }
            }

        clearCurrentTurnToolInvocationCount(chatId)
        AIMessageManager.cancelOperation(chatId)

        jobsToCancel.forEach { job -> job.cancel() }
        jobsToCancel.forEach { job ->
            try {
                job.join()
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }

        chatRuntime.sendJob = null
        chatRuntime.stateCollectionJob = null
        chatRuntime.streamCollectionJob = null

        if (keepPartialResponse) {
            detachStreamingAiMessage(chatId)
        }

        chatRuntime.responseStream = null
        chatRuntime.isLoading.value = false
        updateGlobalLoadingState()
        setChatInputProcessingState(chatId, EnhancedInputProcessingState.Idle)

        withContext(Dispatchers.IO) { saveCurrentChat() }
    }
        fun cancelMessage(chatId: String) {
        coroutineScope.launch {
            cancelMessageInternal(chatId, keepPartialResponse = true)
        }
    }

    suspend fun cancelMessageForDestructiveMutation(chatId: String) {
        cancelMessageInternal(chatId, keepPartialResponse = false)
    }

    init {
        AppLogger.d(TAG, "MessageProcessingDelegate初始化（创建滚动事件)
        coroutineScope.launch {
            tokenSavingManager.initialize()
        }
    }
        fun updateUserMessage(message: String) {
        _userMessage.value = TextFieldValue(message)
    }
        fun updateUserMessage(value: TextFieldValue) {
        _userMessage.value = value
    }
        fun scrollToBottom() {
        _scrollToBottomEvent.tryEmit(Unit)
    }
        fun getTurnCompleteCounter(chatId: String): Long {
        return _turnCompleteCounterByChatId.value[chatId] ?: 0L
    }
        fun isChatLoading(chatId: String): Boolean {
        return runtimeFor(chatId).isLoading.value
    }
        fun setSpeakMessageHandler(handler: (String, Boolean) -> Unit) {
        speakMessageHandler = handler
    }
        private fun resetCurrentTurnToolInvocationCount(chatId: String) {
        val updated = _currentTurnToolInvocationCountByChatId.value.toMutableMap()
        updated[chatId] = 0
        _currentTurnToolInvocationCountByChatId.value = updated
    }
        private fun incrementCurrentTurnToolInvocationCount(chatId: String) {
        val updated = _currentTurnToolInvocationCountByChatId.value.toMutableMap()
        updated[chatId] = (updated[chatId] ?: 0) + 1
        _currentTurnToolInvocationCountByChatId.value = updated
    }
        private fun clearCurrentTurnToolInvocationCount(chatId: String) {
        val updated = _currentTurnToolInvocationCountByChatId.value.toMutableMap()
        updated.remove(chatId)
        _currentTurnToolInvocationCountByChatId.value = updated
    }
        fun sendUserMessage(
            attachments: List<AttachmentInfo> = emptyList(),
            chatId: String,
            messageTextOverride: String? = null,
            proxySenderNameOverride: String? = null,
            workspacePath: String? = null,
            workspaceEnv: String? = null,
            promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
            roleCardId: String,
            enableThinking: Boolean = false,
            thinkingGuidance: Boolean = false,
            enableMemoryQuery: Boolean = true, // 新增参数
            enableWorkspaceAttachment: Boolean = false, // 新增工作区附着参数
            maxTokens: Int,
            tokenUsageThreshold: Double,
            replyToMessage: ChatMessage? = null, // 新增回复消息参数
            isAutoContinuation: Boolean = false, // 标识是否为自动续           enableSummary: Boolean = true,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            suppressUserMessageInHistory: Boolean = false,
            isGroupOrchestrationTurn: Boolean = false,
            groupParticipantNamesText: String? = null
    ) {
        val rawMessageText = messageTextOverride ?: _userMessage.value.text
        // 群组编排模式下，允许空消息（后续成员不需要用户消息）
    if (rawMessageText.isBlank() && attachments.isEmpty() && !isAutoContinuation && !isGroupOrchestrationTurn) {
            AppLogger.d(
                TAG,
                "sendUserMessage忽略: 空消息且无附chatId=${chatId}, autoContinuation=${isAutoContinuation}"
            )
        return
        }
        val chatRuntime = runtimeFor(chatId)
        if (chatRuntime.isLoading.value) {
            AppLogger.w(
                TAG,
                "sendUserMessage忽略: chat正在处理chatId=${chatId}, roleCardId=${roleCardId}, override=${!messageTextOverride.isNullOrBlank()}, suppressUserMessageInHistory=${suppressUserMessageInHistory}"
            )
        return
        }

        // 速率限制检查
    if (!isAutoContinuation && !checkRateLimit(chatId)) {
            AppLogger.w(TAG, "sendUserMessage被速率限制拦截: chatId=$chatId")
        return
        }
        val originalMessageText = rawMessageText.trim()
        var messageText = originalMessageText
        
        if (messageTextOverride == null) {
            _userMessage.value = TextFieldValue("")
        }
        resetCurrentTurnToolInvocationCount(chatId)
        chatRuntime.isLoading.value = true
        updateGlobalLoadingState()
        setChatInputProcessingState(chatId, EnhancedInputProcessingState.Processing(context.getString(R.string.message_processing)))
        val sendJob =
            coroutineScope.launch(Dispatchers.IO) {
            val sendUserMessageStartTime = messageTimingNow()
            // 检查这是否是聊天中的第一条用户消息（忽略AI的开场白
    val isFirstMessage = getChatHistory(chatId).none { it.sender == "user" }
        if (isFirstMessage && chatId != null) {
                val newTitle =
                    when {
                        originalMessageText.isNotBlank() -> originalMessageText
                        attachments.isNotEmpty() -> attachments.first().fileName
                        else -> context.getString(R.string.new_conversation)
                    }
                updateChatTitle(chatId, newTitle)
            }

            AppLogger.d(TAG, "开始处理用户消息：附件数量=${attachments.size}")

            // 获取当前模型配置以检查是否启用直接图片处
    val configId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
                ?: functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
        val loadModelConfigStartTime = messageTimingNow()
        val currentModelConfig = modelConfigManager.getModelConfigFlow(configId).first()
        val enableDirectImageProcessing = currentModelConfig.enableDirectImageProcessing
            val enableDirectAudioProcessing = currentModelConfig.enableDirectAudioProcessing
            val enableDirectVideoProcessing = currentModelConfig.enableDirectVideoProcessing
            AppLogger.d(TAG, "直接图片处理状${enableDirectImageProcessing} (配置ID: ${configId})")
            logMessageTiming(
                stage = "delegate.loadModelConfig",
                startTimeMs = loadModelConfigStartTime,
                details = "chatId=${chatId}, configId=${configId}"
            )

            // 1. 使用 AIMessageManager 构建最终消
    val buildUserMessageStartTime = messageTimingNow()
        val finalMessageContent = AIMessageManager.buildUserMessageContent(
                messageText,
                proxySenderNameOverride,
                attachments,
                enableMemoryQuery,
                enableWorkspaceAttachment,
                workspacePath,
                workspaceEnv,
                replyToMessage,
                enableDirectImageProcessing,
                enableDirectAudioProcessing,
                enableDirectVideoProcessing,
                chatId = chatId
            )
            logMessageTiming(
                stage = "delegate.buildUserMessageContent",
                startTimeMs = buildUserMessageStartTime,
                details = "chatId=${chatId}, attachments=${attachments.size}, finalLength=${finalMessageContent.length}"
            )

            // 自动继续且原本消息为空时，不添加到聊天历史（虽然会发送继续给AI           // 群组编排模式下，空消息也不添加到聊天历史
    val shouldAddUserMessageToChat =
                !suppressUserMessageInHistory &&
                !(isAutoContinuation &&
                        originalMessageText.isBlank() &&
                        attachments.isEmpty()) &&
                !(isGroupOrchestrationTurn &&
                        originalMessageText.isBlank() &&
                        attachments.isEmpty())
        var userMessageAdded = false
            // 1.5 输入安全检查：在发送给LLM之前对消息内容进行消。
    val userPreferencesManager = UserPreferencesManager.getInstance(context)
        val inputSanitizerEnabled = userPreferencesManager.inputSanitizerEnabled.first()
        var sanitizedMessageContent = finalMessageContent
            if (inputSanitizerEnabled) {
                val sanitizeStartTime = messageTimingNow()
                try {
                    val sanitizeResult = inputSanitizer.sanitize(finalMessageContent)
                    logMessageTiming(
                        stage = "delegate.inputSanitize",
                        startTimeMs = sanitizeStartTime,
                        details = "chatId=${chatId}, riskLevel=${sanitizeResult.riskLevel}, findings=${sanitizeResult.findings.size}"
                    )

                    // 检查是否为高风险或严重风险
    if (sanitizeResult.riskLevel == RiskLevel.HIGH || sanitizeResult.riskLevel == RiskLevel.CRITICAL) {
                        AppLogger.w(TAG, "输入安全检查检测到高风险内 riskLevel=${sanitizeResult.riskLevel}, findings=${sanitizeResult.findings.size}")
                        // 发布安全告警事件
                        _securityAlert.value = SecurityAlert(
                            riskLevel = sanitizeResult.riskLevel,
                            findings = sanitizeResult.findings.map { it.description },
                            originalMessage = finalMessageContent
                        )
                        // 暂停消息发。
                        chatRuntime.isLoading.value = false
                        updateGlobalLoadingState()
                        setChatInputProcessingState(chatId, EnhancedInputProcessingState.Idle)
                        withContext(Dispatchers.Main) {
                            showErrorMessage(context.getString(R.string.message_security_alert, sanitizeResult.riskLevel.name))
                        }
                        return@launch
                    }

                    // 使用消毒后的文本
                    sanitizedMessageContent = sanitizeResult.sanitizedText
                    if (sanitizeResult.findings.isNotEmpty()) {
                        AppLogger.d(TAG, "文档提取内容消毒完成: 发现${sanitizeResult.findings.size}个安全问�?)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "输入安全检查失败，使用原始内容", e)
                    logMessageTiming(
                        stage = "delegate.inputSanitize.error",
                        startTimeMs = sanitizeStartTime,
                        details = "chatId=${chatId}, error=${e.message}"
                    )
                    // 消毒失败时使用原始内容，不阻断流。
                }
            }
        var userMessage = ChatMessage(
                sender = "user",
                content = sanitizedMessageContent,
                roleName = context.getString(R.string.message_role_user) // 用户消息的角色名固定义用。
            )
        val toolHandler = AIToolHandler.getInstance(context)
        var workspaceToolHookSession: WorkspaceBackupManager.WorkspaceToolHookSession? = null

            // 在消息发送期间临时挂，workspace hook，结束后卸载
    if (!workspacePath.isNullOrBlank()) {
                val attachWorkspaceHookStartTime = messageTimingNow()
                try {
                    val session =
                        WorkspaceBackupManager.getInstance(context)
                            .createWorkspaceToolHookSession(
                                workspacePath = workspacePath,
                                workspaceEnv = workspaceEnv,
                                messageTimestamp = userMessage.timestamp,
                                chatId = chatId
                            )
                    workspaceToolHookSession = session
                    toolHandler.addToolHook(session)
                    AppLogger.d(
                        TAG,
                        "Workspace hook attached for timestamp=${userMessage.timestamp}, path=${workspacePath}"
                    )
                    logMessageTiming(
                        stage = "delegate.attachWorkspaceHook",
                        startTimeMs = attachWorkspaceHookStartTime,
                        details = "chatId=${chatId}, workspacePath=${workspacePath}"
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to attach workspace hook", e)
                    _nonFatalErrorEvent.emit(context.getString(R.string.message_workspace_sync_failed, e.message))
                }
            }
        if (shouldAddUserMessageToChat && chatId != null) {
                val addUserMessageStartTime = messageTimingNow()
                addOrUpdateMessage(chatId, userMessage)
                userMessageAdded = true
                logMessageTiming(
                    stage = "delegate.addUserMessageToChat",
                    startTimeMs = addUserMessageStartTime,
                    details = "chatId=${chatId}, contentLength=${userMessage.content.length}"
                )
            }

            lateinit var aiMessage: ChatMessage
            val activeChatId = chatId
            var serviceForTurnComplete: EnhancedAIService? = null
            var shouldNotifyTurnComplete = false
            var finalInputStateAfterSend: EnhancedInputProcessingState? = null
            var isWaifuModeEnabled = false
            var didStreamAutoRead = false
            val effectiveRoleCardId = roleCardId
            var requestSentAt = 0L
            var requestStartElapsed = 0L
            var firstResponseElapsed: Long? = null
            var turnInputTokens = 0
            var turnOutputTokens = 0
            var turnCachedInputTokens = 0
            var calculateNextWindowSize: (suspend () -> Int)? = null
            try {
                // if (!NetworkUtils.isNetworkAvailable(context)) {
                //     withContext(Dispatchers.Main) { showErrorMessage("网络连接不可的） }
                //     _isLoading.value = false
                //     setChatInputProcessingState(activeChatId, EnhancedInputProcessingState.Idle)
                //     return@launch
                // }
        val acquireServiceStartTime = messageTimingNow()
        val chatScopedService = EnhancedAIService.getChatInstance(context, activeChatId)
        val service =
                    (chatScopedService
                        ?: getEnhancedAiService())
                        ?: run {
                            withContext(Dispatchers.Main) { showErrorMessage(context.getString(R.string.message_ai_service_not_initialized)) }
                            chatRuntime.isLoading.value = false
                            updateGlobalLoadingState()
                            setChatInputProcessingState(activeChatId, EnhancedInputProcessingState.Idle)
                            return@launch
                        }
                logMessageTiming(
                    stage = "delegate.acquireService",
                    startTimeMs = acquireServiceStartTime,
                    details = "chatId=${activeChatId}, reusedChatInstance=${chatScopedService != null}"
                )
                serviceForTurnComplete = service

                // 清除上一次可能残留的 Error 状态，避免 StateFlow 重放导致新一轮发送立即再次触发弹               service.setInputProcessingState(EnhancedInputProcessingState.Processing(context.getString(R.string.message_processing)))

                // 监听了chat 对应，EnhancedAIService 状态，映射，per-chat state
                chatRuntime.stateCollectionJob?.cancel()
                chatRuntime.stateCollectionJob =
                    coroutineScope.launch {
                        var lastErrorMessage: String? = null
                        service.inputProcessingState.collect { state ->
                            setChatInputProcessingState(activeChatId, state)
        if (state is EnhancedInputProcessingState.Error) {
                                val msg = state.message
                                if (msg != lastErrorMessage) {
                                    lastErrorMessage = msg
                                    withContext(Dispatchers.Main) {
                                        showErrorMessage(msg)
                                    }
                                }
                            } else {
                                lastErrorMessage = null
                            }
                        }
                    }
        val responseStartTime = messageTimingNow()
        val deferred = CompletableDeferred<Unit>()
        val userPreferencesManager = UserPreferencesManager.getInstance(context)

                // 获取角色信息用于通知
    val loadRoleInfoStartTime = messageTimingNow()
        val (characterName, avatarUri) = try {
                    val roleCard = characterCardManager.getCharacterCardFlow(effectiveRoleCardId).first()
        val avatar =
                        userPreferencesManager.getAiAvatarForCharacterCardFlow(roleCard.id).first()
                    Pair(roleCard.name, avatar)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取角色信息失败: ${e.message}", e)
                    Pair(null, null)
                }
        val currentRoleName = characterName ?: "Apex"
                logMessageTiming(
                    stage = "delegate.loadRoleInfo",
                    startTimeMs = loadRoleInfoStartTime,
                    details = "chatId=${activeChatId}, roleCardId=${effectiveRoleCardId}, roleName=${currentRoleName}"
                )
                calculateNextWindowSize = {
                    runCatching {
                        AIMessageManager.calculateStableContextWindow(
                            enhancedAiService = service,
                            chatId = activeChatId,
                            messageContent = "",
                            chatHistory = getChatHistory(activeChatId),
                            workspacePath = workspacePath,
                            workspaceEnv = workspaceEnv,
                            promptFunctionType = promptFunctionType,
                            thinkingGuidance = thinkingGuidance,
                            enableMemoryQuery = enableMemoryQuery,
                            roleCardId = effectiveRoleCardId,
                            currentRoleName = currentRoleName,
                            splitHistoryByRole = true,
                            groupOrchestrationMode = isGroupOrchestrationTurn,
                            groupParticipantNamesText = groupParticipantNamesText,
                            chatModelConfigIdOverride = chatModelConfigIdOverride,
                            chatModelIndexOverride = chatModelIndexOverride,
                            publishEstimate = false
                        )
                    }.onFailure {
                        AppLogger.w(TAG, "回合结束后重算上下文窗口失败", it)
                    }.getOrNull()
                }
        val loadChatHistoryStartTime = messageTimingNow()
        val chatHistory = getChatHistory(activeChatId)
                logMessageTiming(
                    stage = "delegate.loadChatHistory",
                    startTimeMs = loadChatHistoryStartTime,
                    details = "chatId=${activeChatId}, size=${chatHistory.size}"
                )

                // 省Token模式优化
    val tokenSavingStartTime = messageTimingNow()
        val originalHistorySize = chatHistory.size
                val originalTokens = chatHistory.sumOf { ChatUtils.estimateTokenCount(it.content) }
        val messagesForOptimization = chatHistory.map { msg ->
                    Message(
                        role = if (msg.sender == "user") "user" else if (msg.sender == "ai") "assistant" else msg.roleName ?: "user",
                        content = msg.content
                    )
                }
        val optimizedMessages = tokenSavingManager.optimizeMessages(messagesForOptimization, finalMessageContent)
        val optimizedTokens = optimizedMessages.sumOf { ChatUtils.estimateTokenCount(it.content) }
        if (tokenSavingManager.isTokenSavingEnabled() && optimizedMessages.size < originalHistorySize) {
                    AppLogger.d(TAG, "省Token模式生效: 原大${originalHistorySize}, 优化${optimizedMessages.size}, 原tokens=${originalTokens}, 优化后tokens=${optimizedTokens}")
                }
                logMessageTiming(
                    stage = "delegate.tokenSavingOptimization",
                    startTimeMs = tokenSavingStartTime,
                    details = "enabled=${tokenSavingManager.isTokenSavingEnabled()}, originalSize=${originalHistorySize}, optimizedSize=${optimizedMessages.size}"
                )

                // 关闭总结时仍保留真实 limits，避免下游插件收/Infinity 这类无效 JSON 值，
    val effectiveMaxTokens = maxTokens
                val effectiveTokenUsageThreshold = if (enableSummary) tokenUsageThreshold else Double.MAX_VALUE
                val effectiveOnTokenLimitExceeded = if (enableSummary) {
                    suspend {
                        onTokenLimitExceeded(
                            activeChatId,
                            effectiveRoleCardId,
                            isGroupOrchestrationTurn,
                            groupParticipantNamesText
                        )
                    }
                } else {
                    null
                }

                // 2. 使用 AIMessageManager 发送消               // 群组编排模式下，只有当消息内容不为空时才添加 [From user] 前缀
    val requestMessageContent =
                    if (isGroupOrchestrationTurn &&
                        sanitizedMessageContent.trimStart().isNotEmpty() &&
                        !sanitizedMessageContent.trimStart().startsWith("[From user]")
                    ) {
                        "[From user]\n${sanitizedMessageContent}"
                    } else {
                        sanitizedMessageContent
                    }

                requestSentAt = System.currentTimeMillis()
                requestStartElapsed = messageTimingNow()
        if (userMessageAdded && chatId != null) {
                    userMessage = userMessage.copy(sentAt = requestSentAt)
                    addOrUpdateMessage(chatId, userMessage)
                }
        val prepareResponseStreamStartTime = messageTimingNow()
                // 使用省Token模式优化后的消息（如果启用）
    val historyForAI = if (tokenSavingManager.isTokenSavingEnabled()) {
                    optimizedMessages.map { msg ->
                        ChatMessage(
                            sender = when (msg.role.lowercase()) {
                                "user" -> "user"
                                "assistant" -> "ai"
                                else -> msg.role
                            },
                            content = msg.content
                        )
                    }
                } else {
                    chatHistory
                }
        val responseStream = AIMessageManager.sendMessage(
                    enhancedAiService = service,
                    chatId = activeChatId,
                    messageContent = requestMessageContent,
                    // 仅在群组编排中去掉当前用户消息，避免重复拼接                   chatHistory = if (isGroupOrchestrationTurn && userMessageAdded && historyForAI.isNotEmpty()) {
                        historyForAI.subList(0, historyForAI.size - 1)
                    } else {
                        historyForAI
                    },
                    workspacePath = workspacePath,
                    promptFunctionType = promptFunctionType,
                    enableThinking = enableThinking,
                    thinkingGuidance = thinkingGuidance,
                    enableMemoryQuery = enableMemoryQuery, // Pass it here
                    maxTokens = effectiveMaxTokens,
                    tokenUsageThreshold = effectiveTokenUsageThreshold,
                    onNonFatalError = { error ->
                        _nonFatalErrorEvent.emit(error)
                    },
                    onTokenLimitExceeded = effectiveOnTokenLimitExceeded,
                    characterName = characterName,
                    avatarUri = avatarUri,
                    roleCardId = effectiveRoleCardId,
                    currentRoleName = currentRoleName,
                    splitHistoryByRole = true,
                    groupOrchestrationMode = isGroupOrchestrationTurn,
                    groupParticipantNamesText = groupParticipantNamesText,
                    proxySenderName = proxySenderNameOverride,
                    onToolInvocation = {
                        incrementCurrentTurnToolInvocationCount(chatId)
                    },
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride
                )
                logMessageTiming(
                    stage = "delegate.prepareResponseStream",
                    startTimeMs = prepareResponseStreamStartTime,
                    details = "chatId=${activeChatId}, requestLength=${requestMessageContent.length}, history=${chatHistory.size}"
                )

                // 将字符串流共享，以便多个收集器可以使               // 关键修改：设置replay = Int.MAX_VALUE，确认UI 重组（重新订阅）时能收到所有历史字               // 文本数据占用内存极小，全量缓冲不会造成内存压力
    val shareResponseStreamStartTime = messageTimingNow()
        val sharedCharStream =
                    responseStream.shareRevisable(
                        scope = coroutineScope,
                        replay = Int.MAX_VALUE, 
                        onComplete = {
                            deferred.complete(Unit)
                            logMessageTiming(
                                stage = "delegate.sharedStreamComplete",
                                startTimeMs = responseStartTime,
                                details = "chatId=${activeChatId}"
                            )
                            chatRuntime.responseStream = null
                        }
                    )
                logMessageTiming(
                    stage = "delegate.shareResponseStream",
                    startTimeMs = shareResponseStreamStartTime,
                    details = "chatId=${activeChatId}"
                )

                // 更新当前响应流，使其可以被其他组件（如悬浮窗）访               chatRuntime.responseStream = sharedCharStream

                // 获取当前使用的provider和model信息
    val loadProviderModelStartTime = messageTimingNow()
        val (provider, modelName) = try {
                    service.getProviderAndModelForFunction(
                        functionType = com.apex.data.model.FunctionType.CHAT,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取provider和model信息失败: ${e.message}", e)
                    Pair("", "")
                }
                logMessageTiming(
                    stage = "delegate.loadProviderModel",
                    startTimeMs = loadProviderModelStartTime,
                    details = "chatId=${activeChatId}, provider=${provider}, model=${modelName}"
                )

                aiMessage = ChatMessage(
                    sender = "ai", 
                    contentStream = sharedCharStream,
                    timestamp = ChatMessageTimestampAllocator.next(),
                    roleName = currentRoleName,
                    provider = provider,
                    modelName = modelName,
                    sentAt = requestSentAt
                )
                AppLogger.d(
                    TAG,
                    "创建带流的AI消息, stream is null: ${aiMessage.contentStream == null}, timestamp: ${aiMessage.timestamp}"
                )

                // 检查是否启用waifu模式来决定是否显示流式过
    val waifuPreferences = WaifuPreferences.getInstance(context)
                isWaifuModeEnabled = waifuPreferences.enableWaifuModeFlow.first()
                
                // 只有在非waifu模式下才添加初始的AI消息
    if (!isWaifuModeEnabled) {
                    withContext(Dispatchers.Main) {
                        if (chatId != null) {
                            addMessageToChat(chatId, aiMessage)
                        }
                    }
                }
                
                // 启动一个独立的协程来收集流内容并持续更新数据库
    val streamCollectionResult = CompletableDeferred<Throwable?>()
                chatRuntime.streamCollectionJob =
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            var hasLoggedFirstChunk = false
                            var lastStreamingPersistAt = 0L
                            val revisionTracker = TextStreamRevisionTracker()
        val revisionMutex = Mutex()
        val autoReadBuffer = StringBuilder()
        var isFirstAutoReadSegment = true
                            // 流式自动朗读只在较强的句边界切分，逗号不参与断句，避免语气被打断，
    val endChars = ".!?;:。！？；：\n"
        val autoReadStream = XmlTextProcessor.processStreamToText(sharedCharStream)
        val revisableStream = sharedCharStream as? TextStreamEventCarrier

                            fun flushAutoReadSegment(segment: String, interrupt: Boolean) {
                                val trimmed = segment.trim()
        if (trimmed.isNotEmpty()) {
                                    didStreamAutoRead = true
                                    speakMessageHandler(trimmed, interrupt)
                                }
                            }
        fun findFirstEndCharIndex(text: CharSequence): Int {
                                for (i in 0 until text.length) {
                                    val c = text[i]
                                    if (endChars.indexOf(c) >= 0) return i
                                }
        return -1
                            }
        fun tryFlushAutoRead() {
                                if (!getIsAutoReadEnabled()) return
                                if (isWaifuModeEnabled) return
                                while (true) {
                                    val endIdx = findFirstEndCharIndex(autoReadBuffer)
        val shouldFlushByLen = endIdx < 0 && autoReadBuffer.length >= 50
                                    if (endIdx < 0 && !shouldFlushByLen) return

                                    val cutIdx = if (endIdx >= 0) endIdx + 1 else autoReadBuffer.length
                                    val seg = autoReadBuffer.substring(0, cutIdx)
                                    autoReadBuffer.delete(0, cutIdx)

                                    flushAutoReadSegment(seg, interrupt = isFirstAutoReadSegment)
                                    isFirstAutoReadSegment = false
                                }
                            }

                            suspend fun persistStreamingSnapshot(
                                contentSnapshot: String,
                                force: Boolean = false
                            ) {
                                if (isWaifuModeEnabled || chatId == null) return
                                val now = messageTimingNow()
        if (!force && now - lastStreamingPersistAt < STREAM_PERSIST_INTERVAL_MS) {
                                    return
                                }

                                addMessageToChat(chatId, aiMessage.copy(content = contentSnapshot))
                                lastStreamingPersistAt = now
                            }
        val autoReadJob = launch {
                                autoReadStream.collect { char ->
                                    autoReadBuffer.append(char)
                                    tryFlushAutoRead()
                                }
                            }
        val revisionJob =
                                revisableStream?.let { carrier ->
                                    launch {
                                        carrier.eventChannel.collect { event ->
                                            when (event.eventType) {
                                                TextStreamEventType.SAVEPOINT -> {
                                                    revisionMutex.withLock {
                                                        revisionTracker.savepoint(event.id)
                                                    }
                                                }

                                                TextStreamEventType.ROLLBACK -> {
                                                    val snapshot =
                                                        revisionMutex.withLock {
                                                            revisionTracker.rollback(event.id)
                                                        } ?: return@collect

                                                    aiMessage.content = snapshot

                                                    if (!isWaifuModeEnabled) {
                                                        persistStreamingSnapshot(snapshot)
                                                        tryEmitScrollToBottomThrottled(chatId)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                            sharedCharStream.collect { chunk ->
                                if (!hasLoggedFirstChunk) {
                                    hasLoggedFirstChunk = true
                                    if (firstResponseElapsed == null) {
                                        firstResponseElapsed = messageTimingNow()
                                    }
                                    logMessageTiming(
                                        stage = "delegate.firstResponseChunk",
                                        startTimeMs = responseStartTime,
                                        details = "chatId=${activeChatId}, firstChunkLength=${chunk.length}"
                                    )
                                }
        val content =
                                    revisionMutex.withLock {
                                        revisionTracker.append(chunk)
                                    }
                                // 防止后续读取不到
                                aiMessage.content = content
                                
                                // 流式内容，contentStream 实时渲染，这里仅按固定间隔同步快照，避免碎片 chunk 导致高频持久化，                                persistStreamingSnapshot(content)
        if (!isWaifuModeEnabled) {
                                    tryEmitScrollToBottomThrottled(chatId)
                                }
                            }

                            revisionJob?.cancelAndJoin()
                            autoReadJob.join()
        if (getIsAutoReadEnabled() && !isWaifuModeEnabled) {
                                val remaining = autoReadBuffer.toString()
                                autoReadBuffer.clear()
                                flushAutoReadSegment(remaining, interrupt = isFirstAutoReadSegment)
                            }
                        } catch (t: Throwable) {
                            if (!streamCollectionResult.isCompleted) {
                                streamCollectionResult.complete(t)
                            }
        throw t
                        } finally {
                            if (!streamCollectionResult.isCompleted) {
                                streamCollectionResult.complete(null)
                            }
                        }
                    }

                // 等待流完成，以便finally块可以正确执行来更新UI状               deferred.await()
        val streamCollectionError = streamCollectionResult.await()
        if (streamCollectionError != null) {
                    throw streamCollectionError
                }

                runCatching {
                    turnInputTokens = service.getCurrentInputTokenCount()
                    turnOutputTokens = service.getCurrentOutputTokenCount()
                    turnCachedInputTokens = service.getCurrentCachedInputTokenCount()
                }.onFailure {
                    AppLogger.w(TAG, "读取本轮 token 统计失败", it)
                }
        val waitDurationMs =
                    if (requestStartElapsed > 0L && firstResponseElapsed != null) {
                        (firstResponseElapsed!! - requestStartElapsed).coerceAtLeast(0L)
                    } else {
                        0L
                    }
        val outputDurationMs =
                    if (firstResponseElapsed != null) {
                        (messageTimingNow() - firstResponseElapsed!!).coerceAtLeast(0L)
                    } else {
                        0L
                    }
        if (requestSentAt > 0L) {
                    if (userMessageAdded && chatId != null) {
                        userMessage =
                            userMessage.withTurnMetrics(
                                inputTokens = turnInputTokens,
                                outputTokens = turnOutputTokens,
                                cachedInputTokens = turnCachedInputTokens,
                                sentAt = requestSentAt,
                                outputDurationMs = outputDurationMs,
                                waitDurationMs = waitDurationMs
                            )
                        addOrUpdateMessage(chatId, userMessage)
                    }

                    aiMessage =
                        aiMessage.withTurnMetrics(
                            inputTokens = turnInputTokens,
                            outputTokens = turnOutputTokens,
                            cachedInputTokens = turnCachedInputTokens,
                            sentAt = requestSentAt,
                            outputDurationMs = outputDurationMs,
                            waitDurationMs = waitDurationMs
                        )
                }
        val stateAfterStream =
                    _inputProcessingStateByChatId.value[chatKey(chatId)]
                if (stateAfterStream !is EnhancedInputProcessingState.Error) {
                    shouldNotifyTurnComplete = true
                    finalInputStateAfterSend = EnhancedInputProcessingState.Completed
                }
        if (pendingAsyncSummaryUiByChatId.containsKey(chatId)) {
                    setSuppressIdleCompletedStateForChat(chatId, true)
                    finalInputStateAfterSend =
                        EnhancedInputProcessingState.Summarizing(
                            context.getString(R.string.message_summarizing)
                        )
                }

                logMessageTiming(
                    stage = "delegate.responseProcessingComplete",
                    startTimeMs = responseStartTime,
                    details = "chatId=${activeChatId}, waifu=${isWaifuModeEnabled}, autoRead=${didStreamAutoRead}"
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    AppLogger.d(TAG, "消息发送被取消")
                    finalInputStateAfterSend = EnhancedInputProcessingState.Idle
                    shouldNotifyTurnComplete = false
                    throw e
                }
                AppLogger.e(TAG, "发送消息时出错", e)
                setChatInputProcessingState(
                    chatId,
                    EnhancedInputProcessingState.Error(context.getString(R.string.message_send_failed, e.message))
                )
                withContext(Dispatchers.Main) { showErrorMessage(context.getString(R.string.message_send_failed, e.message)) }
            } finally {
                val finalizeMessageStartTime = messageTimingNow()
        val deferTurnCompleteToAsyncJob =
                    finalizeMessageAndNotify(
                    chatId = chatId,
                    activeChatId = activeChatId,
                    aiMessageProvider = { aiMessage },
                    shouldNotifyTurnComplete = shouldNotifyTurnComplete,
                    serviceForTurnComplete = serviceForTurnComplete,
                    skipFinalAutoRead = didStreamAutoRead && !isWaifuModeEnabled,
                    roleCardId = effectiveRoleCardId,
                    calculateNextWindowSize = calculateNextWindowSize,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride
                )
                logMessageTiming(
                    stage = "delegate.finalizeMessage",
                    startTimeMs = finalizeMessageStartTime,
                    details = "chatId=${activeChatId}, notifyTurnComplete=${shouldNotifyTurnComplete}"
                )

                workspaceToolHookSession?.let { session ->
                    val cleanupWorkspaceHookStartTime = messageTimingNow()
                    runCatching { toolHandler.removeToolHook(session) }
                        .onFailure { AppLogger.w(TAG, "Failed to remove workspace hook", it) }
                    runCatching { session.close() }
                        .onFailure { AppLogger.w(TAG, "Failed to close workspace hook session", it) }
                    logMessageTiming(
                        stage = "delegate.cleanupWorkspaceHook",
                        startTimeMs = cleanupWorkspaceHookStartTime,
                        details = "chatId=${activeChatId}"
                    )
                }
        val cleanupRuntimeStartTime = messageTimingNow()
                cleanupRuntimeAfterSend(chatId, chatRuntime)
                logMessageTiming(
                    stage = "delegate.cleanupRuntime",
                    startTimeMs = cleanupRuntimeStartTime,
                    details = "chatId=${activeChatId}"
                )
        if (!deferTurnCompleteToAsyncJob) {
                    finalInputStateAfterSend?.let { terminalState ->
                        setChatInputProcessingState(chatId, terminalState)
                    }
                }
        if (shouldNotifyTurnComplete && !deferTurnCompleteToAsyncJob) {
                    val service = serviceForTurnComplete
                    if (service != null) {
                        notifyTurnComplete(
                            chatId,
                            activeChatId,
                            service,
                            calculateNextWindowSize
                        )
                    }
                }

                logMessageTiming(
                    stage = "delegate.sendUserMessage.total",
                    startTimeMs = sendUserMessageStartTime,
                    details = "chatId=${activeChatId}, addedUserMessage=${userMessageAdded}, enableSummary=${enableSummary}"
                )
                
                // 集成新功能：分析用户行为、情感、兴趣等
    if (!activeChatId.isNullOrBlank()) {
                    try {
                        val chatHistory = getChatHistory(activeChatId)
        if (chatHistory.isNotEmpty()) {
                            // 调用新功能集
    val chatViewModel = com.apex.agent.ui.features.chat.viewmodel.ChatViewModel.getInstance(context)
                            chatViewModel.integrateNewFeatures(activeChatId, chatHistory)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "集成新功能失败：${e.message})
                    }
                }
        val currentJob = coroutineContext[Job]
                if (currentJob != null && chatRuntime.sendJob === currentJob) {
                    chatRuntime.sendJob = null
                }
            }
        }
        chatRuntime.sendJob = sendJob
    }
        private suspend fun notifyTurnComplete(
        chatId: String?,
        activeChatId: String?,
        service: EnhancedAIService,
        calculateNextWindowSize: (suspend () -> Int)? = null
    ) {
        if (!chatId.isNullOrBlank()) {
            val updated = _turnCompleteCounterByChatId.value.toMutableMap()
            updated[chatId] = (updated[chatId] ?: 0L) + 1L
            _turnCompleteCounterByChatId.value = updated
        }
        val nextWindowSize = calculateNextWindowSize?.invoke()
        AppLogger.d(
            TAG,
            "回合完成: chatId=${activeChatId}, nextWindow=${nextWindowSize}, service=${service.javaClass.simpleName}"
        )
        onTurnComplete(activeChatId, service, nextWindowSize)
    }
        private suspend fun finalizeMessageAndNotify(
        chatId: String?,
        activeChatId: String?,
        aiMessageProvider: () -> ChatMessage,
        shouldNotifyTurnComplete: Boolean,
        serviceForTurnComplete: EnhancedAIService?,
        skipFinalAutoRead: Boolean,
        roleCardId: String,
        calculateNextWindowSize: (suspend () -> Int)? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ): Boolean {
        // 修改为使，try-catch 来检查变量是否已初始化，而不是使:var.isInitialized
    var deferTurnCompleteToAsyncJob = false
        try {
            val aiMessage = aiMessageProvider()
            // 优先使用共享流的全量重放缓存重建最终文本，避免完成信号早于收集协程处理尾部字符时丢字符
    val finalContent = resolveFinalContent(aiMessage)
            aiMessage.content = finalContent

            withContext(Dispatchers.IO) {
                val waifuPreferences = WaifuPreferences.getInstance(context)
        val isWaifuModeEnabled = waifuPreferences.enableWaifuModeFlow.first()
        if (isWaifuModeEnabled && WaifuMessageProcessor.shouldSplitMessage(finalContent)) {
                    deferTurnCompleteToAsyncJob = true
                    AppLogger.d(TAG, "Waifu模式已启用，开始创建独立消息，内容长度: ${finalContent.length}")

                    // 获取配置的字符延迟时间和标点符号设置
    val charDelay = waifuPreferences.waifuCharDelayFlow.first().toLong()
        val removePunctuation = waifuPreferences.waifuRemovePunctuationFlow.first()

                    // 获取当前角色
    val currentRoleName = try {
                        characterCardManager.getCharacterCardFlow(roleCardId).first().name
                    } catch (e: Exception) {
                        "Apex" // 默认角色                   }

                    // 获取当前使用的provider和model信息（在finally块内重新获取
    val (provider, modelName) = try {
                        getEnhancedAiService()?.getProviderAndModelForFunction(
                            functionType = com.apex.data.model.FunctionType.CHAT,
                            chatModelConfigIdOverride = chatModelConfigIdOverride,
                            chatModelIndexOverride = chatModelIndexOverride
                        )
                            ?: Pair("", "")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "获取provider和model信息失败: ${e.message}", e)
                        Pair("", "")
                    }

                    // 删除原始的空消息（因为在waifu模式下我们没有显示流式过程）
                    // 不需要显示空的AI消息
                    
                    // 启动一个协程来创建独立的句子消                   coroutineScope.launch(Dispatchers.IO) {
                        AppLogger.d(
                            TAG,
                            "开始Waifu独立消息创建，字符延${charDelay}ms/字符，移除标${removePunctuation}"
                        )

                        // 分割句子
    val sentences =
                            WaifuMessageProcessor.splitMessageBySentences(finalContent, removePunctuation)
                        AppLogger.d(TAG, "分割为{sentences.size}个句子）

                        // 为每个句子创建独立的消息
    for ((index, sentence) in sentences.withIndex()) {
                            // 根据当前句子字符数计算延迟（模拟说话时间
    val characterCount = sentence.length
                            val calculatedDelay =
                                WaifuMessageProcessor.calculateSentenceDelay(characterCount, charDelay)
        if (index > 0) {
                                // 如果不是第一句，先延迟再发                               AppLogger.d(TAG, "当前句字符数: ${characterCount}, 计算延迟: ${calculatedDelay}ms")
                                delay(calculatedDelay)
                            }

                            AppLogger.d(TAG, "创建，{index + 1}个独立消${sentence}")

                            // 创建独立的AI消息（使用外层已获取的provider和modelName
    val sentenceMessage = ChatMessage(
                                sender = "ai",
                                content = sentence,
                                contentStream = null,
                                timestamp = ChatMessageTimestampAllocator.next(),
                                roleName = currentRoleName,
                                provider = provider,
                                modelName = modelName,
                                inputTokens = aiMessage.inputTokens,
                                outputTokens = aiMessage.outputTokens,
                                cachedInputTokens = aiMessage.cachedInputTokens,
                                sentAt = aiMessage.sentAt,
                                outputDurationMs = aiMessage.outputDurationMs,
                                waitDurationMs = aiMessage.waitDurationMs
                            )

                            withContext(Dispatchers.Main) {
                                if (chatId != null) {
                                    addMessageToChat(chatId, sentenceMessage)
                                }
                                // 如果启用了自动朗读，则朗读当前句
    if (getIsAutoReadEnabled()) {
                                    speakMessageHandler(sentence, true)
                                }
        if (index == sentences.lastIndex) {
                                    forceEmitScrollToBottom(chatId)
                                } else {
                                    tryEmitScrollToBottomThrottled(chatId)
                                }
                            }
                        }

                        AppLogger.d(TAG, "Waifu独立消息创建完成")
        val terminalState =
                            if (chatId != null && pendingAsyncSummaryUiByChatId.containsKey(chatId)) {
                                setSuppressIdleCompletedStateForChat(chatId, true)
                                EnhancedInputProcessingState.Summarizing(
                                    context.getString(R.string.message_summarizing)
                                )
                            } else if (shouldNotifyTurnComplete) {
                                EnhancedInputProcessingState.Completed
                            } else {
                                null
                            }
                        terminalState?.let {
                            setChatInputProcessingState(chatId, it)
                        }
        if (shouldNotifyTurnComplete) {
                            val service = serviceForTurnComplete
                            if (service != null) {
                                notifyTurnComplete(
                                    chatId,
                                    activeChatId,
                                    service,
                                    calculateNextWindowSize
                                )
                            }
                        }
                    }
                } else {
                    // 普通模式，直接清理
    val finalMessage = aiMessage.copy(content = finalContent, contentStream = null)
                    withContext(Dispatchers.Main) {
                        if (chatId != null) {
                            addMessageToChat(chatId, finalMessage)
                        }
                        // 如果启用了自动朗读，则朗读完整消
    if (getIsAutoReadEnabled() && !skipFinalAutoRead) {
                            speakMessageHandler(finalContent, true)
                        }
                        forceEmitScrollToBottom(chatId)
                    }
                }
            }
        } catch (e: UninitializedPropertyAccessException) {
            AppLogger.d(TAG, "AI消息未初始化，跳过流清理步骤")
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.d(TAG, "消息收尾阶段被取消，跳过waifu收尾处理")
        throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理waifu模式时出 e)
            try {
                val aiMessage = aiMessageProvider()
        val finalContent = aiMessage.content
                val finalMessage = aiMessage.copy(content = finalContent, contentStream = null)
                withContext(Dispatchers.Main) {
                    if (chatId != null) {
                        addMessageToChat(chatId, finalMessage)
                    }
                }
            } catch (ex: Exception) {
                AppLogger.e(TAG, "回退到普通模式也失败", ex)
            }
        }
        return deferTurnCompleteToAsyncJob
    }
        private fun cleanupRuntimeAfterSend(chatId: String, chatRuntime: ChatRuntime) {
        chatRuntime.streamCollectionJob = null
        chatRuntime.stateCollectionJob?.cancel()
        chatRuntime.stateCollectionJob = null
        chatRuntime.isLoading.value = false

        updateGlobalLoadingState()
        clearCurrentTurnToolInvocationCount(chatId)
    }

    /**
     * 刷新聚合后的加载状态，     * 仅重新计算全局/按会话的加载派生值，不会直接改写具体 chat ，isLoading    */
    fun refreshGlobalLoadingState() {
        updateGlobalLoadingState()
    }
}
