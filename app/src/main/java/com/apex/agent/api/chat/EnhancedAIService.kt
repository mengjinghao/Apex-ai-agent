package com.apex.api.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import com.apex.util.AppLogger
import com.apex.util.ChatMarkupRegex
import com.apex.api.chat.enhance.ConversationMarkupManager
import com.apex.api.chat.enhance.ConversationRoundManager
import com.apex.api.chat.enhance.ConversationService
import com.apex.api.chat.enhance.FileBindingService
import com.apex.api.chat.enhance.MultiServiceManager
import com.apex.api.chat.enhance.ToolExecutionManager
import com.apex.api.chat.llmprovider.AIService
import com.apex.core.chat.logMessageTiming
import com.apex.core.chat.messageTimingNow
import com.apex.core.chat.hooks.PromptHookContext
import com.apex.core.chat.hooks.PromptHookRegistry
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.core.chat.hooks.appendUserTurnIfMissing
import com.apex.core.chat.hooks.toPromptTurns
import com.apex.core.chat.hooks.toRoleContentPairs
import com.apex.core.application.ActivityLifecycleManager
import com.apex.core.tools.AIToolHandler
import com.apex.core.tools.StringResultData
import com.apex.core.tools.packTool.PackageManager
import com.apex.data.model.FunctionType
import com.apex.data.model.InputProcessingState
import com.apex.data.model.PromptFunctionType
import com.apex.data.model.ToolInvocation
import com.apex.data.model.ToolResult
import com.apex.data.model.ModelConfigData
import com.apex.data.model.AITool
import com.apex.data.preferences.ApiPreferences
import com.apex.data.preferences.ExternalHttpApiPreferences
import com.apex.data.preferences.WakeWordPreferences
import com.apex.util.stream.MutableSharedStream
import com.apex.util.stream.Stream
import com.apex.util.stream.StreamCollector
import com.apex.util.stream.TextStreamEvent
import com.apex.util.stream.TextStreamEventCarrier
import com.apex.util.stream.TextStreamEventType
import com.apex.util.stream.TextStreamRevisionTracker
import com.apex.util.stream.withEventChannel
import com.apex.util.stream.plugins.StreamXmlPlugin
import com.apex.util.stream.splitBy
import com.apex.util.stream.stream
import com.apex.agent.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.apex.data.repository.CustomEmojiRepository
import com.apex.data.preferences.UserPreferencesManager
import com.apex.core.config.ModelConfigService
import com.apex.core.config.SystemToolPrompts
import com.apex.data.model.ToolPrompt
import com.apex.data.model.ToolParameterSchema
import com.apex.util.ChatUtils
import com.apex.util.LocaleUtils

/**
 * Enhanced AI service that provides advanced conversational capabilities by integrating various
 * components like tool execution, conversation management, user preferences, and problem library.
 */
class EnhancedAIService private constructor(private val context: Context) {
    companion object {
        private const val TAG = "EnhancedAIService"

        @Volatile private var INSTANCE: EnhancedAIService? = null

        private val CHAT_INSTANCES = ConcurrentHashMap<String, EnhancedAIService>()

        private val FOREGROUND_REF_COUNT = AtomicInteger(0)

        /**
         * 获取EnhancedAIService实例
         * @param context 应用上下�?        * @return EnhancedAIService�?
         */
        fun getInstance(context: Context): EnhancedAIService {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE
                                ?: EnhancedAIService(context.applicationContext).also {
                                    INSTANCE = it
                                }
                    }
        }

        fun getChatInstance(context: Context, chatId: String): EnhancedAIService {
            val appContext = context.applicationContext
            return CHAT_INSTANCES[chatId]
                ?: synchronized(CHAT_INSTANCES) {
                    CHAT_INSTANCES[chatId]
                        ?: EnhancedAIService(appContext).also { CHAT_INSTANCES[chatId] = it }
                }
        }

        fun releaseChatInstance(chatId: String) {
            val instance = CHAT_INSTANCES.remove(chatId) ?: return
            runCatching {
                instance.cancelConversation()
            }.onFailure { e ->
                AppLogger.e(TAG, "释放chat实例资源失败: chatId=${chatId}", e)
            }
        }

        /**
         * 获取指定功能类型，AIService 实例（非实例化方式）
         * @param context 应用上下�?        * @param functionType 功能类型
         * @return AIService 实例
         */
        suspend fun getAIServiceForFunction(
                context: Context,
                functionType: FunctionType
        ): AIService {
            return getInstance(context).multiServiceManager.getServiceForFunction(functionType)
        }

        suspend fun getModelConfigForFunction(
            context: Context,
            functionType: FunctionType
        ): ModelConfigData {
            return getInstance(context).multiServiceManager.getModelConfigForFunction(functionType)
        }

        /**
         * 刷新指定功能类型，AIService 实例（非实例化方式）
         * @param context 应用上下�?        * @param functionType 功能类型
         */
        suspend fun refreshServiceForFunction(context: Context, functionType: FunctionType) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach { it.multiServiceManager.refreshServiceForFunction(functionType) }
        }

        /**
         * 刷新所有AIService 实例（非实例化方式）
         * @param context 应用上下�?        */
        suspend fun refreshAllServices(context: Context) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach { it.multiServiceManager.refreshAllServices() }
        }

        /**
         * 获取指定功能类型的当前输入token计数（非实例化方式）
         * @param context 应用上下�?        * @param functionType 功能类型
         * @return 输入token计数
         */
        suspend fun getCurrentInputTokenCountForFunction(
                context: Context,
                functionType: FunctionType
        ): Int {
            return getInstance(context)
                    .multiServiceManager
                    .getServiceForFunction(functionType)
                    .inputTokenCount
        }

        /**
         * 获取指定功能类型的当前输出token计数（非实例化方式）
         * @param context 应用上下�?        * @param functionType 功能类型
         * @return 输出token计数
         */
        suspend fun getCurrentOutputTokenCountForFunction(
                context: Context,
                functionType: FunctionType
        ): Int {
            return getInstance(context)
                    .multiServiceManager
                    .getServiceForFunction(functionType)
                    .outputTokenCount
        }

        /**
         * 重置指定功能类型或所有功能类型的token计数器（非实例化方式�?        * @param context 应用上下�?        * @param functionType 功能类型，如果为null则重置所有功能类�?        */
        suspend fun resetTokenCountersForFunction(
                context: Context,
                functionType: FunctionType? = null
        ) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach {
                if (functionType == null) {
                    it.multiServiceManager.resetAllTokenCounters()
                } else {
                    it.multiServiceManager.resetTokenCountersForFunction(functionType)
                }
            }
        }

        fun resetTokenCounters(context: Context) {
            val appContext = context.applicationContext
            val allInstances = buildList {
                add(getInstance(appContext))
                addAll(CHAT_INSTANCES.values)
            }.distinct()

            allInstances.forEach { instance ->
                instance.initScope.launch {
                    runCatching {
                        instance.multiServiceManager.resetAllTokenCounters()
                    }.onFailure { e ->
                        AppLogger.e(TAG, "重置token计数器失败：${e.message})
                    }
                }
            }
        }

        /**
         * 处理文件绑定操作（非实例化方式）
         * @param context 应用上下�?        * @param originalContent 原始文件内容
         * @param aiGeneratedCode AI生成的代码（包含"//existing code"标记�?        * @return 混合后的文件内容
         */
        suspend fun applyFileBinding(
                context: Context,
                originalContent: String,
                aiGeneratedCode: String,
                onProgress: ((Float, String) -> Unit)? = null
        ): Pair<String, String> {
            // 获取EnhancedAIService实例
            val instance = getInstance(context)

            // 委托给FileBindingService处理
            return instance.fileBindingService.processFileBinding(
                    originalContent,
                    aiGeneratedCode,
                    onProgress
            )
        }

        suspend fun applyFileBindingOperations(
            context: Context,
            originalContent: String,
            operations: List<FileBindingService.StructuredEditOperation>,
            onProgress: ((Float, String) -> Unit)? = null
        ): Pair<String, String> {
            val instance = getInstance(context)
            return instance.fileBindingService.processFileBindingOperations(
                originalContent = originalContent,
                operations = operations,
                onProgress = onProgress
            )
        }

        /**
         * 自动生成工具包描述（非实例化方式�?        * @param context 应用上下�?        * @param pluginName 工具包名�?        * @param toolDescriptions 工具描述列表
         * @return 生成的工具包描述
         */
        suspend fun generatePackageDescription(
            context: Context,
            pluginName: String,
            toolDescriptions: List<String>
        ): String {
            return getInstance(context).generatePackageDescription(pluginName, toolDescriptions)
        }
    }

    // MultiServiceManager 管理不同功能�?AIService 实例
    private val multiServiceManager = MultiServiceManager(context)

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()
    @Volatile private var isServiceManagerInitialized = false

    // 添加ConversationService实例
    private val conversationService = ConversationService(context, CustomEmojiRepository.getInstance(context))

    // 添加FileBindingService实例
    private val fileBindingService = FileBindingService(context)

    // Tool handler for executing tools
    private val toolHandler = AIToolHandler.getInstance(context)

    private suspend fun ensureInitialized() {
        if (isServiceManagerInitialized) return
        initMutex.withLock {
            if (isServiceManagerInitialized) return
            withContext(Dispatchers.IO) {
                multiServiceManager.initialize()
            }
            isServiceManagerInitialized = true
        }
    }

    // State flows for UI updates
    private val _inputProcessingState =
            MutableStateFlow<InputProcessingState>(InputProcessingState.Idle)
    val inputProcessingState = _inputProcessingState.asStateFlow()

    /**
     * 设置当前的输入处理状�?    * @param newState 新的状�?    */
    fun setInputProcessingState(newState: com.apex.data.model.InputProcessingState) {
        _inputProcessingState.value = newState
    }

    // Per-request token counts
    private val _perRequestTokenCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    val perRequestTokenCounts: StateFlow<Pair<Int, Int>?> = _perRequestTokenCounts.asStateFlow()

    // Stable request window estimate for the next model hop.
    private val _requestWindowEstimate = MutableStateFlow<Int?>(null)
    val requestWindowEstimateFlow: StateFlow<Int?> = _requestWindowEstimate.asStateFlow()

    // Conversation management
    // private val streamBuffer = StringBuilder() // Moved to MessageExecutionContext
    // private val roundManager = ConversationRoundManager() // Moved to MessageExecutionContext
    // private val isConversationActive = AtomicBoolean(false) // Moved to MessageExecutionContext

    // Api Preferences for settings
    private val apiPreferences = ApiPreferences.getInstance(context)

    // Execution context for a single sendMessage call to achieve concurrency
    private data class MessageExecutionContext(
        val executionId: Int,
        val streamBuffer: StringBuilder = StringBuilder(),
        val roundManager: ConversationRoundManager = ConversationRoundManager(),
        val isConversationActive: AtomicBoolean = AtomicBoolean(true),
        val conversationHistory: MutableList<PromptTurn>,
        val eventChannel: MutableSharedStream<TextStreamEvent>,
    )

    private val activeExecutionContexts = ConcurrentHashMap<Int, MessageExecutionContext>()
    private val nextExecutionContextId = AtomicInteger(0)

    private fun registerExecutionContext(context: MessageExecutionContext) {
        activeExecutionContexts[context.executionId] = context
    }

    private fun unregisterExecutionContext(context: MessageExecutionContext) {
        activeExecutionContexts.remove(context.executionId, context)
    }

    private fun invalidateExecutionContext(context: MessageExecutionContext, reason: String) {
        if (context.isConversationActive.compareAndSet(true, false)) {
            AppLogger.d(TAG, "执行上下文已失效: id=${context.executionId}, reason=${reason}")
        }
    }

    private fun invalidateAllExecutionContexts(reason: String) {
        activeExecutionContexts.values.forEach { context ->
            invalidateExecutionContext(context, reason)
        }
    }

    private fun isExecutionContextActive(context: MessageExecutionContext): Boolean {
        return context.isConversationActive.get() &&
            activeExecutionContexts[context.executionId] === context
    }

    private suspend fun startAssistantResponseRound(context: MessageExecutionContext) {
        context.roundManager.startNewRound()
        context.streamBuffer.clear()
    }

    // Coroutine management
    private val toolProcessingScope = CoroutineScope(Dispatchers.IO)
    private val toolExecutionJobs = ConcurrentHashMap<String, Job>()
    // private val conversationHistory = mutableListOf<Pair<String, String>>() // Moved to MessageExecutionContext
    // private val conversationMutex = Mutex() // Moved to MessageExecutionContext

    private var accumulatedInputTokenCount = 0
    private var accumulatedOutputTokenCount = 0
    private var accumulatedCachedInputTokenCount = 0

    // Callbacks
    private var currentResponseCallback: ((content: String, thinking: String) -> Unit)? = null
    private var currentCompleteCallback: (() -> Unit)? = null

    // Package manager for handling tool packages
    private val packageManager = PackageManager.getInstance(context, toolHandler)

    // 存储最后的回复内容，用于通知
    private var lastReplyContent: String? = null

    init {
        com.apex.api.chat.library.ProblemLibrary.initialize(context)
        initScope.launch {
            runCatching {
                ensureInitialized()
            }.onFailure { e ->
                AppLogger.e(TAG, "MultiServiceManager初始化失败：${e.message})
            }
        }
        initScope.launch {
            runCatching {
                toolHandler.registerDefaultTools()
            }.onFailure { e ->
                AppLogger.e(TAG, "注册默认工具失败", e)
            }
        }
        // 监听模型配置变更
        initScope.launch {
            runCatching {
                val modelConfigService = ModelConfigService.getInstance(context)
                modelConfigService.configChangeEvent.collect {
                    when (it) {
                        is ModelConfigService.ConfigChangeEvent.ActiveConfigChanged -> {
                            AppLogger.d(TAG, "活跃配置变更，刷新所有服务实�?)
                            refreshAllServices()
                        }
                        is ModelConfigService.ConfigChangeEvent.ConfigUpdated -> {
                            AppLogger.d(TAG, "配置更新，刷新所有服务实�?)
                            refreshAllServices()
                        }
                    }
                }
            }.onFailure { e ->
                AppLogger.e(TAG, "监听模型配置变更失败", e)
            }
        }
    }

    /**
     * 获取指定功能类型，AIService 实例
     * @param functionType 功能类型
     * @return AIService 实例
     */
    suspend fun getAIServiceForFunction(functionType: FunctionType): AIService {
        ensureInitialized()
        return getAIServiceForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = null,
            chatModelIndexOverride = null
        )
    }

    suspend fun getAIServiceForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String?,
        chatModelIndexOverride: Int?
    ): AIService {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager.getServiceForConfig(
                configId = overrideConfigId,
                modelIndex = (chatModelIndexOverride ?: 0).coerceAtLeast(0)
            )
        } else {
            multiServiceManager.getServiceForFunction(functionType)
        }
    }

    /**
     * 获取指定功能类型的provider和model信息
     * @param functionType 功能类型
     * @return Pair<provider, modelName>，例，Pair("DEEPSEEK", "deepseek-chat")
     */
    suspend fun getProviderAndModelForFunction(functionType: FunctionType): Pair<String, String> {
        return getProviderAndModelForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = null,
            chatModelIndexOverride = null
        )
    }

    suspend fun getProviderAndModelForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String?,
        chatModelIndexOverride: Int?
    ): Pair<String, String> {
        val service = getAIServiceForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
        val providerModel = service.providerModel
        // providerModel格式，PROVIDER:modelName"，使用第一个冒号分�?       val colonIndex = providerModel.indexOf(":")
        return if (colonIndex > 0) {
            val provider = providerModel.substring(0, colonIndex)
            val modelName = providerModel.substring(colonIndex + 1)
            Pair(provider, modelName)
        } else {
            // 如果没有冒号，整个字符串作为provider，modelName为空
            Pair(providerModel, "")
        }
    }

    suspend fun getModelConfigForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ): ModelConfigData {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager.getModelConfigForConfig(overrideConfigId)
        } else {
            multiServiceManager.getModelConfigForFunction(functionType)
        }
    }

    /**
     * 刷新指定功能类型，AIService 实例 当配置发生更改时调用
     * @param functionType 功能类型
     */
    suspend fun refreshServiceForFunction(functionType: FunctionType) {
        ensureInitialized()
        multiServiceManager.refreshServiceForFunction(functionType)
    }

    /** 刷新所有AIService 实例 当全局配置发生更改时调�?/
    suspend fun refreshAllServices() {
        ensureInitialized()
        multiServiceManager.refreshAllServices()
    }

    private suspend fun getModelParametersForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ): List<com.apex.data.model.ModelParameter<*>> {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager.getModelParametersForConfig(overrideConfigId)
        } else {
            multiServiceManager.getModelParametersForFunction(functionType)
        }
    }

    private fun publishRequestWindowEstimate(windowSize: Int) {
        _requestWindowEstimate.value = windowSize
    }

    private suspend fun estimatePreparedRequestWindow(
        serviceForFunction: AIService,
        preparedHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?,
        publishEstimate: Boolean
    ): Int {
        val windowSize =
            serviceForFunction.calculateInputTokens(
                chatHistory = preparedHistory,
                availableTools = availableTools
            )
        if (publishEstimate) {
            publishRequestWindowEstimate(windowSize)
        }
        return windowSize
    }

    private fun applyPromptFinalizeHooks(
        initialContext: PromptHookContext,
        dispatchHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchPromptFinalizeHooks
    ): PromptHookContext {
        return dispatchHooks(initialContext)
    }

    private fun bypassPromptHooks(context: PromptHookContext): PromptHookContext = context

    private fun applyFinalizedCurrentUserTurn(
        preparedHistory: List<PromptTurn>,
        originalCurrentMessage: String,
        finalizedCurrentMessage: String
    ): List<PromptTurn> {
        if (finalizedCurrentMessage.isBlank()) {
            return preparedHistory
        }

        val lastTurn = preparedHistory.lastOrNull()
        return when {
            lastTurn?.kind == PromptTurnKind.USER &&
                lastTurn.content == finalizedCurrentMessage -> {
                preparedHistory
            }
            lastTurn?.kind == PromptTurnKind.USER &&
                lastTurn.content == originalCurrentMessage -> {
                preparedHistory.dropLast(1) + lastTurn.copy(content = finalizedCurrentMessage)
            }
            else -> {
                preparedHistory.appendUserTurnIfMissing(finalizedCurrentMessage)
            }
        }
    }

    suspend fun estimateRequestWindowFromMemory(
        message: String,
        chatHistory: List<PromptTurn>,
        chatId: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        functionType: FunctionType = FunctionType.CHAT,
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        enableThinking: Boolean = false,
        thinkingGuidance: Boolean = false,
        enableMemoryQuery: Boolean = true,
        customSystemPromptTemplate: String? = null,
        roleCardId: String? = null,
        enableGroupOrchestrationHint: Boolean = false,
        groupParticipantNamesText: String? = null,
        proxySenderName: String? = null,
        isSubTask: Boolean = false,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        stream: Boolean = true,
        publishEstimate: Boolean = true
    ): Int {
        val preparedHistory =
            prepareConversationHistory(
                chatHistory = chatHistory,
                processedInput = message,
                chatId = chatId,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                promptFunctionType = promptFunctionType,
                thinkingGuidance = thinkingGuidance,
                customSystemPromptTemplate = customSystemPromptTemplate,
                enableMemoryQuery = enableMemoryQuery,
                roleCardId = roleCardId,
                enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                groupParticipantNamesText = groupParticipantNamesText,
                proxySenderName = proxySenderName,
                isSubTask = isSubTask,
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride,
                dispatchHistoryHooks = PromptHookRegistry::dispatchPromptEstimateHistoryHooks,
                dispatchSystemPromptComposeHooks = ::bypassPromptHooks,
                dispatchToolPromptComposeHooks = ::bypassPromptHooks
            )

        val modelParameters =
            getModelParametersForFunction(
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )
        val serviceForFunction =
            getAIServiceForFunction(
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )
        val availableTools =
            getAvailableToolsForFunction(
                functionType = functionType,
                roleCardId = roleCardId,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )

        var finalProcessedInput = message
        var finalPreparedHistory = preparedHistory
        val beforeFinalizeContext =
            applyPromptFinalizeHooks(
                PromptHookContext(
                    stage = "before_finalize_prompt",
                    chatId = chatId,
                    functionType = functionType.name,
                    promptFunctionType = promptFunctionType.name,
                    rawInput = message,
                    processedInput = finalProcessedInput,
                    preparedHistory = finalPreparedHistory,
                    modelParameters = serializePromptHookModelParameters(modelParameters),
                    availableTools = serializePromptHookToolPrompts(availableTools),
                    metadata =
                        mapOf(
                            "workspacePath" to workspacePath,
                            "workspaceEnv" to workspaceEnv,
                            "enableThinking" to enableThinking,
                            "stream" to stream,
                            "isSubTask" to isSubTask
                        )
                ),
                dispatchHooks = PromptHookRegistry::dispatchPromptEstimateFinalizeHooks
            )
        finalProcessedInput = beforeFinalizeContext.processedInput ?: finalProcessedInput
        finalPreparedHistory = beforeFinalizeContext.preparedHistory
        val beforeSendContext =
            applyPromptFinalizeHooks(
                beforeFinalizeContext.copy(
                    stage = "before_send_to_model",
                    processedInput = finalProcessedInput,
                    preparedHistory = finalPreparedHistory
                ),
                dispatchHooks = PromptHookRegistry::dispatchPromptEstimateFinalizeHooks
            )
        finalProcessedInput = beforeSendContext.processedInput ?: finalProcessedInput
        finalPreparedHistory = beforeSendContext.preparedHistory
        if (!ChatUtils.isGeminiProviderModel(serviceForFunction.providerModel)) {
            finalProcessedInput = ChatUtils.stripGeminiThoughtSignatureMeta(finalProcessedInput)
            finalPreparedHistory = ChatUtils.stripGeminiThoughtSignatureMetaTurns(finalPreparedHistory)
        }

        val requestHistory =
            applyFinalizedCurrentUserTurn(
                preparedHistory = finalPreparedHistory,
                originalCurrentMessage = message,
                finalizedCurrentMessage = finalProcessedInput
            )

        return estimatePreparedRequestWindow(
            serviceForFunction = serviceForFunction,
            preparedHistory = requestHistory,
            availableTools = availableTools,
            publishEstimate = publishEstimate
        )
    }

    /** Send a message to the AI service */
    suspend fun sendMessage(
        message: String,
        chatId: String? = null,
        chatHistory: List<PromptTurn> = emptyList(),
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        functionType: FunctionType = FunctionType.CHAT,
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        enableThinking: Boolean = false,
        thinkingGuidance: Boolean = false,
        enableMemoryQuery: Boolean = true,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        onNonFatalError: suspend (error: String) -> Unit = {},
        onTokenLimitExceeded: (suspend () -> Unit)? = null,
        customSystemPromptTemplate: String? = null,
        isSubTask: Boolean = false,
        characterName: String? = null,
        avatarUri: String? = null,
        roleCardId: String? = null,
        enableGroupOrchestrationHint: Boolean = false,
        groupParticipantNamesText: String? = null,
        proxySenderName: String? = null,
        onToolInvocation: (suspend (String) -> Unit)? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        stream: Boolean = true
    ): Stream<String> {
        AppLogger.d(
                TAG,
                "sendMessage调用开�?功能类型=${functionType}, 提示词类型的${promptFunctionType}, 思考引，的${thinkingGuidance}"
        )
        accumulatedInputTokenCount = 0
        accumulatedOutputTokenCount = 0
        accumulatedCachedInputTokenCount = 0

        val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val wrappedStream = stream {
            val execContext =
                MessageExecutionContext(
                    executionId = nextExecutionContextId.incrementAndGet(),
                    conversationHistory = chatHistory.toMutableList(),
                    eventChannel = eventChannel
                )
            registerExecutionContext(execContext)
            var hadFatalError = false
            try {
                // 确保所有操作都在IO线程上执�?               withContext(Dispatchers.IO) {
                    // 仅当会话首次启动时开启服务，并更新前台通知为“运行中�?                   if (!isSubTask) {
                        startAiService(characterName, avatarUri)
                    }

                    // Update state to show we're processing
                    if (!isSubTask) {
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Processing(context.getString(R.string.enhanced_processing_message))
                        }
                    }

                    val startTime = messageTimingNow()

                    // Prepare conversation history with system prompt
                    val preparedHistory =
                            prepareConversationHistory(
                                    execContext.conversationHistory, // 始终使用内部历史记录
                                    message,
                                    chatId,
                                    workspacePath,
                                    workspaceEnv,
                                    promptFunctionType,
                                    thinkingGuidance,
                                    customSystemPromptTemplate,
                                    enableMemoryQuery,
                                    roleCardId,
                                    enableGroupOrchestrationHint,
                                    groupParticipantNamesText,
                                    proxySenderName,
                                    isSubTask,
                                    functionType,
                                    chatModelConfigIdOverride,
                                    chatModelIndexOverride
                            )
                    val tAfterPrepareHistory = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: prepareConversationHistory=${tAfterPrepareHistory - startTime}ms")
                    
                    // 关键修复：用准备好的历史记录（包含了系统提示）去同步更新内部，conversationHistory 状�?                   execContext.conversationHistory.clear()
                    execContext.conversationHistory.addAll(preparedHistory)

                    // Update UI state to connecting
                    if (!isSubTask) {
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Connecting(context.getString(R.string.enhanced_connecting_service))
                        }
                    }

                    // Get all model parameters from preferences (with enabled state)
                    val modelParameters = getModelParametersForFunction(
                        functionType = functionType,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride
                    )
                    val tAfterModelParams = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: getModelParametersForFunction=${tAfterModelParams - tAfterPrepareHistory}ms")

                    // 获取对应功能类型的AIService实例
                    val serviceForFunction = getAIServiceForFunction(
                        functionType = functionType,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride
                    )
                    val tAfterGetService = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: getAIServiceForFunction=${tAfterGetService - tAfterModelParams}ms")

                    // 清空之前的单次请求token计数
                    _perRequestTokenCounts.value = null

                    // 获取工具列表（如果启用Tool Call�?                   val availableTools = getAvailableToolsForFunction(
                        functionType = functionType,
                        roleCardId = roleCardId,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride
                    )
                    val tAfterGetTools = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: getAvailableToolsForFunction=${tAfterGetTools - tAfterGetService}ms")

                    var finalProcessedInput = message
                    var finalPreparedHistory = preparedHistory
                    val beforeFinalizeContext =
                        applyPromptFinalizeHooks(
                            PromptHookContext(
                                stage = "before_finalize_prompt",
                                chatId = chatId,
                                functionType = functionType.name,
                                promptFunctionType = promptFunctionType.name,
                                rawInput = message,
                                processedInput = finalProcessedInput,
                                preparedHistory = finalPreparedHistory,
                                modelParameters = serializePromptHookModelParameters(modelParameters),
                                availableTools = serializePromptHookToolPrompts(availableTools),
                                metadata =
                                    mapOf(
                                        "workspacePath" to workspacePath,
                                        "workspaceEnv" to workspaceEnv,
                                        "enableThinking" to enableThinking,
                                        "stream" to stream,
                                        "isSubTask" to isSubTask
                                    )
                            )
                        )
                    finalProcessedInput = beforeFinalizeContext.processedInput ?: finalProcessedInput
                    finalPreparedHistory = beforeFinalizeContext.preparedHistory
                    val beforeSendContext =
                        applyPromptFinalizeHooks(
                            beforeFinalizeContext.copy(
                                stage = "before_send_to_model",
                                processedInput = finalProcessedInput,
                                preparedHistory = finalPreparedHistory
                            )
                        )
                    finalProcessedInput = beforeSendContext.processedInput ?: finalProcessedInput
                    finalPreparedHistory = beforeSendContext.preparedHistory
                    if (!ChatUtils.isGeminiProviderModel(serviceForFunction.providerModel)) {
                        finalProcessedInput = ChatUtils.stripGeminiThoughtSignatureMeta(finalProcessedInput)
                        finalPreparedHistory = ChatUtils.stripGeminiThoughtSignatureMetaTurns(finalPreparedHistory)
                    }
                    val requestHistory =
                        applyFinalizedCurrentUserTurn(
                            preparedHistory = finalPreparedHistory,
                            originalCurrentMessage = message,
                            finalizedCurrentMessage = finalProcessedInput
                        )
                    execContext.conversationHistory.clear()
                    execContext.conversationHistory.addAll(requestHistory)
                    estimatePreparedRequestWindow(
                        serviceForFunction = serviceForFunction,
                        preparedHistory = requestHistory,
                        availableTools = availableTools,
                        publishEstimate = true
                    )
                    
                    // 使用新的Stream API
                    AppLogger.d(TAG, "sendMessage请求前准备耗时: ${tAfterGetTools - startTime}ms, 流式输出�?${stream}")
                    val requestStartTime = messageTimingNow()
                    val responseStream =
                            serviceForFunction.sendMessage(
                                    context = this@EnhancedAIService.context,
                                    chatHistory = requestHistory,
                                    modelParameters = modelParameters,
                                    enableThinking = enableThinking,
                                    stream = stream,
                                    availableTools = availableTools,
                                    onTokensUpdated = { input, cachedInput, output ->
                                        _perRequestTokenCounts.value = Pair(input, output)
                                    },
                                    onNonFatalError = onNonFatalError
                            )
                    val revisableStream = responseStream as? TextStreamEventCarrier

                    // 收到第一个响应，更新状�?                   var isFirstChunk = true

                    // 创建一个新的轮次来管理内容
                    startAssistantResponseRound(execContext)
                    val revisionTracker = TextStreamRevisionTracker()
                    val revisionMutex = Mutex()

                    // 从原始stream收集内容并处�?                   var chunkCount = 0
                    var totalChars = 0
                    var lastLogTime = messageTimingNow()

                    coroutineScope {
                        val revisionJob =
                            revisableStream?.let { carrier ->
                                launch {
                                    carrier.eventChannel.collect { event ->
                                        execContext.eventChannel.emit(event)
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
                                                execContext.streamBuffer.clear()
                                                execContext.streamBuffer.append(snapshot)
                                                execContext.roundManager.updateContent(snapshot)
                                            }
                                        }
                                    }
                                }
                            }

                        try {
                            responseStream.collect { content ->
                                // 第一次收到响应，更新状�?                               if (isFirstChunk) {
                                    if (!isSubTask) {
                                    withContext(Dispatchers.Main) {
                                        _inputProcessingState.value =
                                                InputProcessingState.Receiving(context.getString(R.string.enhanced_receiving_response))
                                        }
                                    }
                                    isFirstChunk = false
                                    logMessageTiming(
                                        stage = "enhanced.sendMessage.firstResponseChunk",
                                        startTimeMs = requestStartTime,
                                        details = "functionType=${functionType}, stream=${stream}"
                                    )
                                }

                                // 累计统计
                                chunkCount++
                                totalChars += content.length

                                // 周期性日�?                               val currentTime = messageTimingNow()
                                if (currentTime - lastLogTime > 5000) { // �?秒记录一次，                                    AppLogger.d(TAG, "已接收的${chunkCount} 个内容块，总计 ${totalChars} 个字符）
                                    lastLogTime = currentTime
                                }

                                revisionMutex.withLock {
                                    revisionTracker.append(content)
                                }

                                // 更新streamBuffer，保持与原有逻辑一�?                               execContext.streamBuffer.append(content)

                                // 更新内容到轮次管理器
                                execContext.roundManager.updateContent(execContext.streamBuffer.toString())

                                // 发射当前内容片段
                                emit(content)
                            }
                        } finally {
                            revisionJob?.cancelAndJoin()
                        }
                    }

                    // Update accumulated token counts and persist them
                    val inputTokens = serviceForFunction.inputTokenCount
                    val cachedInputTokens = serviceForFunction.cachedInputTokenCount
                    val outputTokens = serviceForFunction.outputTokenCount
                    accumulatedInputTokenCount += inputTokens
                    accumulatedOutputTokenCount += outputTokens
                    accumulatedCachedInputTokenCount += cachedInputTokens
                    apiPreferences.updateTokensForProviderModel(serviceForFunction.providerModel, inputTokens, outputTokens, cachedInputTokens)
                    
                    // Update request count
                    apiPreferences.incrementRequestCountForProviderModel(serviceForFunction.providerModel)

                    AppLogger.d(
                            TAG,
                            "Token count updated for ${functionType}. Input: ${inputTokens}, Output: ${outputTokens}, CachedInput: ${cachedInputTokens}. Turn Accumulated: ${accumulatedInputTokenCount}, ${accumulatedOutputTokenCount}, ${accumulatedCachedInputTokenCount}"
                    )
                    logMessageTiming(
                        stage = "enhanced.sendMessage.streamComplete",
                        startTimeMs = requestStartTime,
                        details = "functionType=${functionType}, totalChars=${totalChars}, stream=${stream}"
                    )
                }
            } catch (e: CancellationException) {
                invalidateExecutionContext(execContext, "sendMessage.collect.cancelled")
                AppLogger.d(TAG, "sendMessage流被取消")
                throw e
            } catch (e: Exception) {
                // 用户取消导致，Socket closed 是预期行为，不应作为错误处理
                if (e.message?.contains("Socket closed", ignoreCase = true) == true) {
                    if (isExecutionContextActive(execContext)) {
                        AppLogger.d(TAG, "Stream was cancelled by the user (Socket closed).")
                    } else {
                        AppLogger.d(TAG, "Stream closed after execution context was invalidated.")
                    }
                } else {
                    hadFatalError = true
                    // Handle any exceptions
                    AppLogger.e(TAG, "发送消息时发生错误: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value =
                                InputProcessingState.Error(message = context.getString(R.string.enhanced_error_with_message, e.message ?: ""))
                    }
                }

                // 发生无法处理的错误时，也应停止服务，但用户取消除�?               if (e.message?.contains("Socket closed", ignoreCase = true) != true) {
                    if (!isSubTask) stopAiService()
                }
            } finally {
                try {
                    // 确保流处理完成后调用；如果本轮已被取消，则不能再继续跑完成逻辑�?                   if (!hadFatalError && isExecutionContextActive(execContext)) {
                        val collector = this
                        withContext(Dispatchers.IO) {
                            processStreamCompletion(
                                execContext,
                                functionType,
                                collector,
                                enableThinking,
                                enableMemoryQuery,
                                onNonFatalError,
                                onTokenLimitExceeded,
                                maxTokens,
                                tokenUsageThreshold,
                                isSubTask,
                                characterName,
                                avatarUri,
                                roleCardId,
                                chatId,
                                onToolInvocation,
                                chatModelConfigIdOverride,
                                chatModelIndexOverride,
                                stream,
                                enableGroupOrchestrationHint
                            )
                        }
                    } else if (!hadFatalError) {
                        AppLogger.d(
                            TAG,
                            "跳过流完成处理：执行上下文已失效, id=${execContext.executionId}"
                        )
                    }
                } finally {
                    unregisterExecutionContext(execContext)
                }
            }
        }
        return wrappedStream.withEventChannel(eventChannel)
    }

    /**
     * 使用流处理技术增强工具调用检测能力这个方法通过流式XML解析来辅助识别工具调用，比单纯依赖正则表达式更可�?    * @param content 需要检测工具调用的内容
     * @return 经过增强检测的内容，可能会修复格式问题
     */
    private suspend fun enhanceToolDetection(content: String): String {
        try {
            // 检查内容是否包含可能的工具调用标记
            if (!ChatMarkupRegex.containsToolTag(content)) {
                return content
            }

            // 创建字符流以应用流处理，使用 stream() 替代 asCharStream()
            val charStream = content.stream()

            // 使用XML插件来拆分流
            val plugins = listOf(StreamXmlPlugin())

            // 保存增强后的内容
            val enhancedContent = StringBuilder()

            // 追踪是否发现了工具标�?           var foundToolTag = false

            // 处理拆分的结�?           charStream.splitBy(plugins).collect { group ->
                when (val tag = group.tag) {
                    // 匹配到XML标签
                    is StreamXmlPlugin -> {
                        val xmlContent = StringBuilder()
                        group.stream.collect { char -> xmlContent.append(char) }

                        val xml = xmlContent.toString()
                        // 检查是否是工具标签
                        val tagName = ChatMarkupRegex.extractOpeningTagName(xml)
                        if (ChatMarkupRegex.isToolTagName(tagName) && tagName != null && isToolXmlBlock(xml, tagName)) {
                            foundToolTag = true
                            // 格式标准化，使其符合工具调用的正则表达式预期格式
                            val normalizedXml = normalizeToolXml(xml)
                            enhancedContent.append(normalizedXml)
                            AppLogger.d(TAG, "工具调用XML被增强流处理检测到并标准化")
                        } else {
                            // 保留其他XML标签
                            enhancedContent.append(xml)
                        }
                    }
                    // 纯文本内�?                   null -> {
                        val textContent = StringBuilder()
                        group.stream.collect { char -> textContent.append(char) }
                        enhancedContent.append(textContent.toString())
                    }
                    // 添加必要的else分支
                    else -> {
                        val textContent = StringBuilder()
                        group.stream.collect { char -> textContent.append(char) }
                        enhancedContent.append(textContent.toString())
                        AppLogger.w(TAG, "未知标签类型: ${tag::class.java.simpleName}")
                    }
                }
            }

            // 如果找到了工具标签，返回增强的内容；否则返回原始内容
            return if (foundToolTag) {
                AppLogger.d(TAG, "增强的XML工具检测完了）
                enhancedContent.toString()
            } else {
                content
            }
        } catch (e: Exception) {
            // 如果流处理失败，返回原始内容并记录错�?           AppLogger.e(TAG, "增强工具检测失�?${e.message}", e)
            return content
        }
    }

    /**
     * 规范化工具XML以符合正则表达式预期
     * @param xml 原始XML文本
     * @return 标准化后的XML
     */
    private fun normalizeToolXml(xml: String): String {
        var result = xml.trim()
        val toolTagName = ChatMarkupRegex.extractOpeningTagName(result)

        // 确保工具名称格式正确
        if (ChatMarkupRegex.isToolTagName(toolTagName) && toolTagName != null) {
            result = result.replace(
                Regex("<${Regex.escape(toolTagName)}\\s+name\\s*=", RegexOption.IGNORE_CASE),
                "<${toolTagName} name="
            )
        }

        // 确保参数格式正确
        result = result.replace(Regex("<param\\s+name\\s*="), "<param name=")

        return result
    }

    private fun isToolXmlBlock(xml: String, tagName: String): Boolean {
        val trimmed = xml.trim()
        if (trimmed.endsWith("/>")) {
            return true
        }
        return trimmed.contains("</${tagName}>")
    }

    private data class TruncatedToolRoundRecovery(
        val repairedContent: String,
        val appendedSuffix: String,
        val invalidatedToolNames: List<String>
    )

    private fun detectAndRepairTruncatedToolRound(content: String): TruncatedToolRoundRecovery? {
        if (!content.contains("<tool", ignoreCase = true)) {
            return null
        }

        val completeToolBlocks = ChatMarkupRegex.toolCallPattern.findAll(content).toList()
        val openToolPattern =
            Regex(
                "<(${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE})\\b[^>]*",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

        val candidate = openToolPattern.findAll(content).lastOrNull { match ->
            completeToolBlocks.none { block ->
                match.range.first >= block.range.first && match.range.last <= block.range.last
            }
        } ?: return null

        val fragment = content.substring(candidate.range.first)
        if (!Regex("\\bname\\s*=\\s*\"", RegexOption.IGNORE_CASE).containsMatchIn(fragment)) {
            return null
        }

        val tagName =
            (ChatMarkupRegex.extractOpeningTagName(fragment) ?: candidate.groupValues.getOrNull(1))
                ?.takeIf { ChatMarkupRegex.isToolTagName(it) }
                ?: ChatMarkupRegex.generateRandomToolTagName()

        if (Regex("</${Regex.escape(tagName)}\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(fragment)) {
            return null
        }

        val appendedSuffix = buildTruncatedToolRepairSuffix(fragment, tagName)
        if (appendedSuffix.isEmpty()) {
            return null
        }
        val repairedContent = content + appendedSuffix
        val invalidatedToolNames =
            buildList {
                ChatMarkupRegex.toolCallPattern.findAll(content)
                    .mapNotNull { match ->
                        match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    .forEach { add(it) }
                extractXmlAttributeValue(fragment, "name")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
                .distinct()
                .toList()

        return TruncatedToolRoundRecovery(
            repairedContent = repairedContent,
            appendedSuffix = appendedSuffix,
            invalidatedToolNames = invalidatedToolNames
        )
    }

    private fun buildTruncatedToolRepairSuffix(fragment: String, fallbackTagName: String): String {
        val toolTagName =
            fallbackTagName.takeIf { ChatMarkupRegex.isToolTagName(it) }
                ?: ChatMarkupRegex.generateRandomToolTagName()

        val openingTagEnd = fragment.indexOf('>')
        if (openingTagEnd < 0) {
            return completePartialOpenTag(fragment, toolTagName, defaultNameValue = "truncated_tool_call") +
                "</${toolTagName}>"
        }

        val body = fragment.substring(openingTagEnd + 1)
        val completeTagPattern =
            Regex("</?([A-Za-z][A-Za-z0-9_]*)\\b[^>]*>", RegexOption.IGNORE_CASE)
        var openParamCount = 0

        completeTagPattern.findAll(body).forEach { match ->
            val tagText = match.value
            val tagName = match.groupValues[1]
            val isClosing = tagText.startsWith("</")

            when {
                tagName.equals("param", ignoreCase = true) -> {
                    if (isClosing) {
                        if (openParamCount > 0) {
                            openParamCount--
                        }
                    } else if (!tagText.endsWith("/>")) {
                        openParamCount++
                    }
                }

                tagName.equals(toolTagName, ignoreCase = true) && isClosing -> {
                    return ""
                }
            }
        }

        val suffix = StringBuilder()
        val trailingPartialTag = extractTrailingPartialTag(fragment)
        var toolClosedBySuffix = false

        if (trailingPartialTag != null) {
            when {
                isPartialClosingTagFor(trailingPartialTag, "param") -> {
                    suffix.append(completePartialClosingTag(trailingPartialTag, "param"))
                    if (openParamCount > 0) {
                        openParamCount--
                    }
                }

                isPartialOpeningTagFor(trailingPartialTag, "param") -> {
                    val completedTagNameSuffix = completePartialTagName(trailingPartialTag, "param")
                    suffix.append(
                        completePartialOpenTag(
                            trailingPartialTag + completedTagNameSuffix,
                            "param",
                            defaultNameValue = "_truncated_fragment"
                        )
                    )
                    suffix.insert(0, completedTagNameSuffix)
                    openParamCount++
                }

                isPartialClosingTagFor(trailingPartialTag, toolTagName) -> {
                    suffix.append(completePartialClosingTag(trailingPartialTag, toolTagName))
                    toolClosedBySuffix = true
                }

                isPartialOpeningTagFor(trailingPartialTag, toolTagName) -> {
                    val completedTagNameSuffix =
                        completePartialTagName(trailingPartialTag, toolTagName)
                    suffix.append(
                        completePartialOpenTag(
                            trailingPartialTag + completedTagNameSuffix,
                            toolTagName,
                            defaultNameValue = "truncated_tool_call"
                        )
                    )
                    suffix.insert(0, completedTagNameSuffix)
                }

                trailingPartialTag == "<" -> {
                    suffix.append("!-- truncated -->")
                }
            }
        }

        repeat(openParamCount) {
            suffix.append("</param>")
        }
        if (!toolClosedBySuffix) {
            suffix.append("</${toolTagName}>")
        }
        return suffix.toString()
    }

    private fun extractXmlAttributeValue(source: String, attributeName: String): String? {
        val pattern =
            Regex(
                "\\b${Regex.escape(attributeName)}\\s*=\\s*\"([^\"]*)\"",
                RegexOption.IGNORE_CASE
            )
        return pattern.find(source)?.groupValues?.getOrNull(1)
    }

    private fun extractTrailingPartialTag(fragment: String): String? {
        val lastOpen = fragment.lastIndexOf('<')
        val lastClose = fragment.lastIndexOf('>')
        if (lastOpen <= lastClose) {
            return null
        }
        return fragment.substring(lastOpen)
    }

    private fun completePartialOpenTag(
        partialTag: String,
        tagName: String,
        defaultNameValue: String
    ): String {
        val suffix = StringBuilder()
        val normalizedPartial = partialTag.lowercase()
        val normalizedTagName = tagName.lowercase()
        val tagPrefix = "<${normalizedTagName}"
        val attrValueOpenPattern = Regex("\\bname\\s*=\\s*\"[^\"]*$", RegexOption.IGNORE_CASE)
        val attrEqPattern = Regex("\\bname\\s*=\\s*$", RegexOption.IGNORE_CASE)
        val defaultAttrPattern =
            Regex("^<${Regex.escape(tagName)}\\s*$", RegexOption.IGNORE_CASE)
        val partialNamePatterns =
            listOf(
                Regex("\\bn$", RegexOption.IGNORE_CASE) to "ame=\"\"",
                Regex("\\bna$", RegexOption.IGNORE_CASE) to "me=\"\"",
                Regex("\\bnam$", RegexOption.IGNORE_CASE) to "e=\"\"",
                Regex("\\bname$", RegexOption.IGNORE_CASE) to "=\"\""
            )
        val partialNameCompletion =
            partialNamePatterns.firstOrNull { it.first.containsMatchIn(partialTag) }?.second

        when {
            attrValueOpenPattern.containsMatchIn(partialTag) -> suffix.append("\"")
            attrEqPattern.containsMatchIn(partialTag) -> suffix.append("\"\"")
            partialNameCompletion != null -> suffix.append(partialNameCompletion)
            normalizedPartial == tagPrefix || defaultAttrPattern.matches(partialTag) -> {
                suffix.append(" name=\"")
                suffix.append(defaultNameValue)
                suffix.append("\"")
            }
        }

        if (((partialTag.length + suffix.length) > 0) &&
            ((partialTag + suffix.toString()).count { it == '"' } % 2 != 0)
        ) {
            suffix.append("\"")
        }
        if (!(partialTag + suffix.toString()).endsWith(">")) {
            suffix.append(">")
        }
        return suffix.toString()
    }

    private fun isPartialOpeningTagFor(partialTag: String, tagName: String): Boolean {
        if (!partialTag.startsWith("<") || partialTag.startsWith("</")) {
            return false
        }
        val currentName = Regex("^<([A-Za-z_]*)", RegexOption.IGNORE_CASE)
            .find(partialTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: return false
        if (currentName.isEmpty()) {
            return false
        }
        return tagName.lowercase().startsWith(currentName)
    }

    private fun isPartialClosingTagFor(partialTag: String, tagName: String): Boolean {
        if (!partialTag.startsWith("</")) {
            return false
        }
        val currentName = Regex("^</([A-Za-z_]*)", RegexOption.IGNORE_CASE)
            .find(partialTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: return false
        if (currentName.isEmpty()) {
            return false
        }
        return tagName.lowercase().startsWith(currentName)
    }

    private fun completePartialTagName(partialTag: String, tagName: String): String {
        val currentName = Regex("^</?([A-Za-z_]*)", RegexOption.IGNORE_CASE)
            .find(partialTag)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            .orEmpty()
        return tagName.substring(currentName.length.coerceAtMost(tagName.length))
    }

    private fun completePartialClosingTag(partialTag: String, tagName: String): String {
        return buildString {
            append(completePartialTagName(partialTag, tagName))
            append(">")
        }
    }

    /** 在处理完流后调用，使用增强的工具检测功�?/
    private suspend fun processStreamCompletion(
            context: MessageExecutionContext,
            functionType: FunctionType = FunctionType.CHAT,
            collector: StreamCollector<String>,
            enableThinking: Boolean = false,
            enableMemoryQuery: Boolean = true,
            onNonFatalError: suspend (error: String) -> Unit,
            onTokenLimitExceeded: (suspend () -> Unit)? = null,
            maxTokens: Int,
            tokenUsageThreshold: Double,
            isSubTask: Boolean,
            characterName: String? = null,
            avatarUri: String? = null,
            roleCardId: String? = null,
            chatId: String? = null,
            onToolInvocation: (suspend (String) -> Unit)? = null,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            stream: Boolean = true,
            enableGroupOrchestrationHint: Boolean = false
    ) {
        try {
            val startTime = messageTimingNow()
            // If conversation is no longer active, return immediately
            if (!context.isConversationActive.get()) {
                return
            }

            // Get response content
            val content = context.streamBuffer.toString().trim()

            // If content is empty, it means an error likely occurred or the model returned nothing.
            // We must still finalize the conversation to reset the state correctly.
            if (content.isEmpty()) {
                AppLogger.d(TAG, "Stream content is empty. Finalizing conversation state.")
                // We call handleTaskCompletion to properly set the conversation as inactive and update the UI state.
                // We pass enableMemoryQuery = false because there's no content to analyze or save.
                handleWaitForUserNeed(
                    context = context,
                    content = content,
                    isSubTask = isSubTask,
                    chatId = chatId
                )
                return
            }

            // If content is empty, finish immediately
            if (content.isEmpty()) {
                return
            }

            // 禁止“纯思考输出”：移除 thinking 后正文为空时，发出专用告警并回传，AI 继续生成
            val contentWithoutThinking = ChatUtils.removeThinkingContent(content)
            if (contentWithoutThinking.isEmpty()) {
                val pureThinkingWarning =
                        ConversationMarkupManager.createWarningStatus(
                                this@EnhancedAIService.context.getString(
                                        R.string.enhanced_pure_thinking_only_warning
                                )
                        )
                context.roundManager.appendContent("\n${pureThinkingWarning}")
                collector.emit(pureThinkingWarning)
                try {
                    context.conversationHistory.add(
                        PromptTurn(kind = PromptTurnKind.TOOL_RESULT, content = pureThinkingWarning)
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "添加纯思考告警到历史记录失败", e)
                    return
                }
                AppLogger.w(TAG, "检测到纯思考输出（removeThinking后正文为空），已回传告警给AI继续生成")
                handleToolInvocation(
                        toolInvocations = emptyList(),
                        context = context,
                        functionType = functionType,
                        collector = collector,
                        enableThinking = enableThinking,
                        enableMemoryQuery = enableMemoryQuery,
                        onNonFatalError = onNonFatalError,
                        onTokenLimitExceeded = onTokenLimitExceeded,
                        maxTokens = maxTokens,
                        tokenUsageThreshold = tokenUsageThreshold,
                        isSubTask = isSubTask,
                        characterName = characterName,
                        avatarUri = avatarUri,
                        roleCardId = roleCardId,
                        chatId = chatId,
                        onToolInvocation = onToolInvocation,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride,
                        stream = stream,
                        enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                        toolResultOverrideMessage = pureThinkingWarning
                )
                return
            }

            // 使用增强的工具检测功能处理内�?           val enhancedContent = enhanceToolDetection(content)
            val truncatedToolRecovery = detectAndRepairTruncatedToolRound(content)
            val finalContent = truncatedToolRecovery?.repairedContent ?: enhancedContent

            // 截断修复仅追加缺失后缀，不撤回已经发出的内�?           if (truncatedToolRecovery != null) {
                val appendedSuffix = truncatedToolRecovery.appendedSuffix
                if (appendedSuffix.isNotEmpty()) {
                    context.streamBuffer.append(appendedSuffix)
                    context.roundManager.updateContent(context.streamBuffer.toString())
                    collector.emit(appendedSuffix)
                }
            } else if (finalContent != content) {
                context.streamBuffer.setLength(0)
                context.streamBuffer.append(finalContent)
                context.roundManager.updateContent(finalContent)
            }

            // 预先提取工具调用信息和完成标记，避免重复解析
            val extractedToolInvocations =
                    if (truncatedToolRecovery == null) {
                        ToolExecutionManager.extractToolInvocations(finalContent)
                    } else {
                        emptyList()
                    }
            val hasTaskCompletion = ConversationMarkupManager.containsTaskCompletion(finalContent)

            // 如果只有任务完成标记且没有工具调用，立即处理完成逻辑
            if (truncatedToolRecovery == null && hasTaskCompletion && extractedToolInvocations.isEmpty()) {
                handleTaskCompletion(
                    context = context,
                    content = finalContent,
                    enableMemoryQuery = enableMemoryQuery,
                    isSubTask = isSubTask,
                    chatId = chatId,
                    characterName = characterName,
                    avatarUri = avatarUri
                )
                return
            }

            // Check again if conversation is active
            if (!context.isConversationActive.get()) {
                return
            }

            // Add current assistant message to conversation history
            try {
                context.conversationHistory.add(
                    PromptTurn(
                        kind = PromptTurnKind.ASSISTANT,
                        content = context.roundManager.getCurrentRoundContent()
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "添加助手消息到历史记录失败：${e.message})
                return
            }

            // Check again if conversation is active
            if (!context.isConversationActive.get()) {
                return
            }

            if (truncatedToolRecovery != null) {
                val warningStatus =
                        ConversationMarkupManager.createWarningStatus(
                                this@EnhancedAIService.context.getString(
                                        R.string.enhanced_truncated_tool_call_warning
                                )
                        )
                val warningDisplayContent = "\n${warningStatus}"
                context.roundManager.appendContent(warningDisplayContent)
                collector.emit(warningDisplayContent)
                AppLogger.w(
                        TAG,
                        "检测到未闭合工具调用，本轮工具全部作废。invalidated=${truncatedToolRecovery.invalidatedToolNames}"
                )
                handleToolInvocation(
                        toolInvocations = emptyList(),
                        context = context,
                        functionType = functionType,
                        collector = collector,
                        enableThinking = enableThinking,
                        enableMemoryQuery = enableMemoryQuery,
                        onNonFatalError = onNonFatalError,
                        onTokenLimitExceeded = onTokenLimitExceeded,
                        maxTokens = maxTokens,
                        tokenUsageThreshold = tokenUsageThreshold,
                        isSubTask = isSubTask,
                        characterName = characterName,
                        avatarUri = avatarUri,
                        roleCardId = roleCardId,
                        chatId = chatId,
                        onToolInvocation = onToolInvocation,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride,
                        stream = stream,
                        enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                        toolResultOverrideMessage = warningStatus
                )
                return
            }

            // Main flow: Detect and process tool invocations
            if (extractedToolInvocations.isNotEmpty()) {
                if (hasTaskCompletion) {
                    val warning =
                            ConversationMarkupManager.createToolsSkippedByCompletionWarning(
                                    this@EnhancedAIService.context,
                                    extractedToolInvocations.map { it.tool.name }
                            )
                    context.roundManager.appendContent(warning)
                    collector.emit(warning)
                    try {
                        context.conversationHistory.add(
                            PromptTurn(kind = PromptTurnKind.TOOL_RESULT, content = warning)
                        )
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加任务完成跳过工具警告到历史记录失败：${e.message})
                    }
                }

                // Handle wait for user need marker
                if (ConversationMarkupManager.containsWaitForUserNeed(finalContent)) {
                    val userNeedContent =
                            ConversationMarkupManager.createWarningStatus(
                                    this@EnhancedAIService.context.getString(R.string.enhanced_tool_warning),
                            )
                    context.roundManager.appendContent(userNeedContent)
                    collector.emit(userNeedContent)
                    try {
                        context.conversationHistory.add(
                            PromptTurn(kind = PromptTurnKind.TOOL_RESULT, content = userNeedContent)
                        )
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加工具调用警告到历史记录失败：${e.message})
                    }
                }

                // Add current assistant message to conversation history

                logMessageTiming(
                    stage = "enhanced.processStreamCompletion.detectToolInvocations",
                    startTimeMs = startTime,
                    details = "count=${extractedToolInvocations.size}"
                )
                handleToolInvocation(
                        extractedToolInvocations,
                        context,
                        functionType,
                        collector,
                        enableThinking,
                        enableMemoryQuery,
                        onNonFatalError,
                        onTokenLimitExceeded,
                        maxTokens,
                        tokenUsageThreshold,
                        isSubTask,
                        characterName,
                        avatarUri,
                        roleCardId,
                        chatId,
                        onToolInvocation,
                        chatModelConfigIdOverride,
                        chatModelIndexOverride,
                        stream = stream,
                        enableGroupOrchestrationHint = enableGroupOrchestrationHint
                )
                return
            }

            // 修改默认行为：如果没有特殊标记或工具调用，默认等待用户输�?           // 而不是直接标记为完成
            // 创建等待用户输入的内�?           val userNeedContent =
                    ConversationMarkupManager.createWaitForUserNeedContent(
                            context.roundManager.getDisplayContent()
                    )

            // 处理为等待用户输入模�?           handleWaitForUserNeed(
                context = context,
                content = userNeedContent,
                isSubTask = isSubTask,
                chatId = chatId,
                characterName = characterName,
                avatarUri = avatarUri
            )
            logMessageTiming(
                stage = "enhanced.processStreamCompletion.complete",
                startTimeMs = startTime
            )
        } catch (e: Exception) {
            // Catch any exceptions in the processing flow
            AppLogger.e(TAG, "处理流完成时发生错误", e)
            withContext(Dispatchers.Main) {
                _inputProcessingState.value = InputProcessingState.Idle
            }
        }
    }

    /** Handle task completion logic - simplified version without callbacks */
    private suspend fun handleTaskCompletion(
        context: MessageExecutionContext,
        content: String,
        enableMemoryQuery: Boolean,
        isSubTask: Boolean,
        chatId: String? = null,
        characterName: String? = null,
        avatarUri: String? = null
    ) {
        // Mark conversation as complete
        context.isConversationActive.set(false)

        // 清除内容�?       // roundManager.clearContent()
        
        // 保存最后的回复内容用于通知
        lastReplyContent = context.roundManager.getDisplayContent()

        // Ensure input processing state is updated to completed
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.Completed
            }
        }

        if (enableMemoryQuery) {
            // 保存问题记录到库
            toolProcessingScope.launch {
                com.apex.api.chat.library.ProblemLibrary.saveProblemAsync(
                        this@EnhancedAIService.context,
                        toolHandler,
                        context.conversationHistory.toRoleContentPairs(),
                        content,
                        multiServiceManager.getServiceForFunction(FunctionType.PROBLEM_LIBRARY)
                )
            }
        }

        if (!isSubTask) {
        notifyReplyCompleted(chatId, characterName, avatarUri)
        stopAiService(characterName, avatarUri)
        }
    }

    /** Handle wait for user need logic - simplified version without callbacks */
    private suspend fun handleWaitForUserNeed(
        context: MessageExecutionContext,
        content: String,
        isSubTask: Boolean,
        chatId: String? = null,
        characterName: String? = null,
        avatarUri: String? = null
    ) {
        // Mark conversation as complete
        context.isConversationActive.set(false)

        // 清除内容�?       // roundManager.clearContent()
        
        // 保存最后的回复内容用于通知
        lastReplyContent = context.roundManager.getDisplayContent()

        // Ensure input processing state is updated to completed
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.Completed
            }
        }

        AppLogger.d(TAG, "Wait for user need - skipping problem library analysis")
        if (!isSubTask) {
        notifyReplyCompleted(chatId, characterName, avatarUri)
        stopAiService(characterName, avatarUri)
        }
    }

    /** Handle tool invocation processing - simplified version without callbacks */
    private suspend fun handleToolInvocation(
        toolInvocations: List<ToolInvocation>,
        context: MessageExecutionContext,
        functionType: FunctionType = FunctionType.CHAT,
        collector: StreamCollector<String>,
        enableThinking: Boolean = false,
        enableMemoryQuery: Boolean = true,
        onNonFatalError: suspend (error: String) -> Unit,
        onTokenLimitExceeded: (suspend () -> Unit)? = null,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        isSubTask: Boolean,
        characterName: String? = null,
        avatarUri: String? = null,
        roleCardId: String? = null,
        chatId: String? = null,
        onToolInvocation: (suspend (String) -> Unit)? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        stream: Boolean = true,
        enableGroupOrchestrationHint: Boolean = false,
        toolResultOverrideMessage: String? = null
    ) {
        val startTime = messageTimingNow()

        toolInvocations.forEach { invocation ->
            onToolInvocation?.invoke(invocation.tool.name)
        }

        if (!isSubTask && toolInvocations.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                val toolNames = toolInvocations.joinToString(", ") { resolveToolDisplayName(it.tool) }
                _inputProcessingState.value = InputProcessingState.ExecutingTool(toolNames)
            }
        }

        val processToolJob = toolProcessingScope.launch {
            val allToolResults = ToolExecutionManager.executeInvocations(
                invocations = toolInvocations,
                context = this@EnhancedAIService.context,
                toolHandler = toolHandler,
                packageManager = packageManager,
                collector = collector,
                callerName = characterName,
                callerChatId = chatId,
                callerCardId = roleCardId
            )

            if (allToolResults.isNotEmpty()) {
                AppLogger.d(TAG, "所有工具结果收集完毕，准备最终处理器�?
                processToolResults(
                    allToolResults, context, functionType, collector, enableThinking,
                    enableMemoryQuery, onNonFatalError, onTokenLimitExceeded, maxTokens, tokenUsageThreshold, isSubTask,
                    characterName, avatarUri, roleCardId, chatId, onToolInvocation,
                    chatModelConfigIdOverride, chatModelIndexOverride, stream, enableGroupOrchestrationHint
                )
            } else if (!toolResultOverrideMessage.isNullOrEmpty()) {
                AppLogger.d(TAG, "0工具路由命中，使用覆盖消息继续请求AI�?
                processToolResults(
                    results = emptyList(),
                    context = context,
                    functionType = functionType,
                    collector = collector,
                    enableThinking = enableThinking,
                    enableMemoryQuery = enableMemoryQuery,
                    onNonFatalError = onNonFatalError,
                    onTokenLimitExceeded = onTokenLimitExceeded,
                    maxTokens = maxTokens,
                    tokenUsageThreshold = tokenUsageThreshold,
                    isSubTask = isSubTask,
                    characterName = characterName,
                    avatarUri = avatarUri,
                    roleCardId = roleCardId,
                    chatId = chatId,
                    onToolInvocation = onToolInvocation,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride,
                    stream = stream,
                    enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                    toolResultMessageOverride = toolResultOverrideMessage
                )
            }

            logMessageTiming(
                stage = "enhanced.handleToolInvocation.complete",
                startTimeMs = startTime,
                details = "toolCount=${toolInvocations.size}"
            )
        }

        val invocationId = java.util.UUID.randomUUID().toString()
        toolExecutionJobs[invocationId] = processToolJob

        try {
            processToolJob.join()
        } finally {
            toolExecutionJobs.remove(invocationId)
        }
    }


    /** Process tool execution result - simplified version without callbacks */
    private suspend fun processToolResults(
            results: List<ToolResult>,
            context: MessageExecutionContext,
            functionType: FunctionType = FunctionType.CHAT,
            collector: StreamCollector<String>,
            enableThinking: Boolean = false,
            enableMemoryQuery: Boolean = true,
            onNonFatalError: suspend (error: String) -> Unit,
            onTokenLimitExceeded: (suspend () -> Unit)? = null,
            maxTokens: Int,
            tokenUsageThreshold: Double,
            isSubTask: Boolean,
            characterName: String? = null,
            avatarUri: String? = null,
            roleCardId: String? = null,
            chatId: String? = null,
            onToolInvocation: (suspend (String) -> Unit)? = null,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            stream: Boolean = true,
            enableGroupOrchestrationHint: Boolean = false,
            toolResultMessageOverride: String? = null
    ) {
        val startTime = messageTimingNow()
        val toolNames = results.joinToString(", ") { it.toolName }
        val toolResultMessage = toolResultMessageOverride ?: results.joinToString("\n") {
            ConversationMarkupManager.formatToolResultForMessage(it)
        }

        if (toolResultMessage.isBlank()) {
            AppLogger.w(TAG, "工具结果消息为空，跳过后续AI请求")
            return
        }

        val displayToolNames = if (toolNames.isNotBlank()) toolNames else "warning"
        if (results.isNotEmpty()) {
            AppLogger.d(TAG, "开始处理工具结�?${toolNames}, 成功: ${results.all { it.success }}")
        } else {
            AppLogger.d(TAG, "开始处理工具覆盖消息，长， ${toolResultMessage.length}")
        }

        // Add transition state
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.ProcessingToolResult(displayToolNames)
            }
        }

        // Check if conversation is still active
        if (!context.isConversationActive.get()) {
            return
        }

        // Add tool result to conversation history
        context.conversationHistory.add(
            PromptTurn(
                kind = PromptTurnKind.TOOL_RESULT,
                content = toolResultMessage,
                toolName = toolNames.ifBlank { null }
            )
        )

        val normalizedChatHistory =
            conversationService.normalizeConversationHistoryForModel(context.conversationHistory)
        context.conversationHistory.clear()
        context.conversationHistory.addAll(normalizedChatHistory)

        // Get current conversation history is now just the normalized context history
        val currentChatHistory = context.conversationHistory

        // 不再需要，因为结果在调用时已实时输�?       // context.roundManager.appendContent(toolResultMessage)
        // try { collector.emit(toolResultMessage) } catch (_: Exception) {}

        // Start new round - ensure tool execution response will be shown in a new message
        startAssistantResponseRound(context)

        // Clearly show we're preparing to send tool result to AI
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.ProcessingToolResult(displayToolNames)
            }
        }

        // Add short delay to make state change more visible
        delay(300)

        // Get all model parameters from preferences (with enabled state)
        val modelParameters = getModelParametersForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )

        // 获取对应功能类型的AIService实例
        val serviceForFunction = getAIServiceForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
        
        // 获取工具列表（如果启用Tool Call�?提前获取，以便在token计算中使�?       val availableTools = getAvailableToolsForFunction(
            functionType = functionType,
            roleCardId = roleCardId,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
 
        val currentTokens = estimatePreparedRequestWindow(
            serviceForFunction = serviceForFunction,
            preparedHistory = currentChatHistory,
            availableTools = availableTools,
            publishEstimate = true
        )

        // After a tool call, check if token usage exceeds the threshold
        if (maxTokens > 0) {
            val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()

            if (usageRatio >= tokenUsageThreshold) {
                AppLogger.w(TAG, "Token usage (${usageRatio}) exceeds threshold (${tokenUsageThreshold}) after tool call. Triggering summary.")
                onTokenLimitExceeded?.invoke()
                context.isConversationActive.set(false)
                if (!isSubTask) {
                    stopAiService(characterName, avatarUri)
                }
                // 关键修复：在触发总结后，直接返回，因为后续流程将由回调处�?               return
            }
        }

        // 清空之前的单次请求token计数
        _perRequestTokenCounts.value = null
        
        // 使用新的Stream API处理工具执行结果
        withContext(Dispatchers.IO) {
            try {
                // 发送消息并获取响应�?               val aiStartTime = messageTimingNow()
                val responseStream =
                        serviceForFunction.sendMessage(
                                context = this@EnhancedAIService.context,
                                chatHistory = currentChatHistory,
                                modelParameters = modelParameters,
                                enableThinking = enableThinking,
                                stream = stream,
                                availableTools = availableTools,
                                onTokensUpdated = { input, cachedInput, output ->
                                    _perRequestTokenCounts.value = Pair(input, output)
                                },
                                onNonFatalError = onNonFatalError
                        )

                // 更新状态为接收�?               if (!isSubTask) {
                withContext(Dispatchers.Main) {
                    _inputProcessingState.value =
                            InputProcessingState.Receiving(this@EnhancedAIService.context.getString(R.string.enhanced_receiving_tool_result))
                    }
                }

                // 处理�?               var chunkCount = 0
                var totalChars = 0
                var lastLogTime = messageTimingNow()
                var isFirstChunk = true
                val revisableStream = responseStream as? TextStreamEventCarrier
                val revisionTracker = TextStreamRevisionTracker()
                val revisionMutex = Mutex()

                coroutineScope {
                    val revisionJob =
                        revisableStream?.let { carrier ->
                            launch {
                                carrier.eventChannel.collect { event ->
                                    context.eventChannel.emit(event)
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
                                            context.streamBuffer.clear()
                                            context.streamBuffer.append(snapshot)
                                            context.roundManager.updateContent(snapshot)
                                        }
                                    }
                                }
                            }
                        }

                    try {
                        responseStream.collect { content ->
                            if (isFirstChunk) {
                                isFirstChunk = false
                                logMessageTiming(
                                    stage = "enhanced.processToolResults.firstResponseChunk",
                                    startTimeMs = aiStartTime,
                                    details = "toolNames=${displayToolNames}, stream=${stream}"
                                )
                            }

                            revisionMutex.withLock {
                                revisionTracker.append(content)
                            }

                            // 更新streamBuffer
                            context.streamBuffer.append(content)

                            // 更新内容到轮次管理器
                            context.roundManager.updateContent(context.streamBuffer.toString())

                            // 累计统计
                            chunkCount++
                            totalChars += content.length

                            // 定期记录日志
                            val currentTime = messageTimingNow()
                            if (currentTime - lastLogTime > 5000) { // �?秒记录一次，                                lastLogTime = currentTime
                            }

                            // 通过收集器将内容发射出去，让UI可以接收�?                           collector.emit(content)
                        }
                    } finally {
                        revisionJob?.cancelAndJoin()
                    }
                }

                // Update accumulated token counts and persist them
                val inputTokens = serviceForFunction.inputTokenCount
                val cachedInputTokens = serviceForFunction.cachedInputTokenCount
                val outputTokens = serviceForFunction.outputTokenCount
                accumulatedInputTokenCount += inputTokens
                accumulatedOutputTokenCount += outputTokens
                accumulatedCachedInputTokenCount += cachedInputTokens
                apiPreferences.updateTokensForProviderModel(serviceForFunction.providerModel, inputTokens, outputTokens, cachedInputTokens)
                
                // Update request count
                apiPreferences.incrementRequestCountForProviderModel(serviceForFunction.providerModel)

                AppLogger.d(
                        TAG,
                        "Token count updated after tool result for ${functionType}. Input: ${inputTokens}, Output: ${outputTokens}, CachedInput: ${cachedInputTokens}. Turn Accumulated: ${accumulatedInputTokenCount}, ${accumulatedOutputTokenCount}, ${accumulatedCachedInputTokenCount}"
                )

                logMessageTiming(
                    stage = "enhanced.processToolResults.aiResponseComplete",
                    startTimeMs = aiStartTime,
                    details = "toolNames=${displayToolNames}, totalChars=${totalChars}"
                )

                // 流处理完成，处理完成逻辑
                processStreamCompletion(
                    context,
                    functionType,
                    collector,
                    enableThinking,
                    enableMemoryQuery,
                    onNonFatalError,
                    onTokenLimitExceeded,
                    maxTokens,
                    tokenUsageThreshold,
                    isSubTask,
                    characterName,
                    avatarUri,
                    roleCardId,
                    chatId,
                    onToolInvocation,
                    chatModelConfigIdOverride,
                    chatModelIndexOverride,
                    stream,
                    enableGroupOrchestrationHint
                )
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "处理工具执行结果被取消）
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "处理工具执行结果时出�? e)
                withContext(Dispatchers.Main) {
                    _inputProcessingState.value =
                            InputProcessingState.Error(this@EnhancedAIService.context.getString(R.string.enhanced_process_tool_result_failed, e.message ?: ""))
                }
            } finally {
                logMessageTiming(
                    stage = "enhanced.processToolResults.complete",
                    startTimeMs = startTime,
                    details = "toolNames=${displayToolNames}, resultCount=${results.size}"
                )
            }
        }
    }
    /**
     * Get the current input token count from the last API call
     * @return The number of input tokens used in the most recent request
     */
    fun getCurrentInputTokenCount(): Int {
        return accumulatedInputTokenCount
    }

    /**
     * Get the current output token count from the last API call
     * @return The number of output tokens generated in the most recent response
     */
    fun getCurrentOutputTokenCount(): Int {
        return accumulatedOutputTokenCount
    }

    /**
     * Get the current cached input token count accumulated across the current turn
     * @return The number of cached input tokens used in the current turn
     */
    fun getCurrentCachedInputTokenCount(): Int {
        return accumulatedCachedInputTokenCount
    }

    /** Reset token counters to zero Use this when starting a new conversation */
    fun resetTokenCounters() {
        Companion.resetTokenCounters(context)
    }

    /**
     * 重置指定功能类型或所有功能类型的token计数�?    * @param functionType 功能类型，如果为null则重置所有功能类�?    */
    suspend fun resetTokenCountersForFunction(functionType: FunctionType? = null) {
        Companion.resetTokenCountersForFunction(context, functionType)
    }

    /**
     * 生成对话总结
     * @param messages 要总结的消息列�?    * @return 生成的总结文本
     */
    suspend fun generateSummary(messages: List<Pair<String, String>>): String {
        return generateSummary(messages, null)
    }

    /**
     * 生成对话总结，并且包含上一次的总结内容
     * @param messages 要总结的消息列�?    * @param previousSummary 上一次的总结内容，可以为null
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?
    ): String {
        return generateSummaryFromPromptTurns(messages.toPromptTurns(), previousSummary)
    }

    suspend fun generateSummaryFromPromptTurns(
            messages: List<PromptTurn>,
            previousSummary: String?
    ): String {
        // 调用ConversationService中的方法
        return conversationService.generateSummaryFromPromptTurns(messages, previousSummary, multiServiceManager)
    }

    /**
     * 获取指定功能类型的当前输入token计数
     * @param functionType 功能类型
     * @return 输入token计数
     */
    suspend fun getCurrentInputTokenCountForFunction(functionType: FunctionType): Int {
        return Companion.getCurrentInputTokenCountForFunction(context, functionType)
    }

    /**
     * 获取指定功能类型的当前输出token计数
     * @param functionType 功能类型
     * @return 输出token计数
     */
    suspend fun getCurrentOutputTokenCountForFunction(functionType: FunctionType): Int {
        return Companion.getCurrentOutputTokenCountForFunction(context, functionType)
    }

    private fun resolveToolDisplayName(tool: AITool): String {
        if (tool.name != "package_proxy") {
            return tool.name
        }
        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        return if (targetToolName.isNotBlank()) targetToolName else tool.name
    }

    /** Prepare the conversation history with system prompt */
    private suspend fun prepareConversationHistory(
            chatHistory: List<PromptTurn>,
            processedInput: String,
            chatId: String?,
            workspacePath: String?,
            workspaceEnv: String?,
            promptFunctionType: PromptFunctionType,
            thinkingGuidance: Boolean,
            customSystemPromptTemplate: String? = null,
            enableMemoryQuery: Boolean,
            roleCardId: String?,
            enableGroupOrchestrationHint: Boolean,
            groupParticipantNamesText: String? = null,
            proxySenderName: String? = null,
            isSubTask: Boolean = false,
            functionType: FunctionType = FunctionType.CHAT,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            dispatchHistoryHooks: (PromptHookContext) -> PromptHookContext =
                PromptHookRegistry::dispatchPromptHistoryHooks,
            dispatchSystemPromptComposeHooks: (PromptHookContext) -> PromptHookContext =
                PromptHookRegistry::dispatchSystemPromptComposeHooks,
            dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext =
                PromptHookRegistry::dispatchToolPromptComposeHooks
    ): List<PromptTurn> {
        // Check if backend image recognition service is configured (for intent-based vision)
        // For subtasks, always disable backend image recognition (only support OCR)
        val hasImageRecognition = if (isSubTask) false else multiServiceManager.hasImageRecognitionConfigured()
        val hasAudioRecognition = if (isSubTask) false else multiServiceManager.hasAudioRecognitionConfigured()
        val hasVideoRecognition = if (isSubTask) false else multiServiceManager.hasVideoRecognitionConfigured()

        // 获取当前功能类型（通常是聊天模型）的模型配置，用于判断聊天模型是否自带识图能力
        val config = getModelConfigForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
        val useToolCallApi = config.enableToolCall
        val chatModelHasDirectImage = config.enableDirectImageProcessing
        val chatModelHasDirectAudio = config.enableDirectAudioProcessing
        val chatModelHasDirectVideo = config.enableDirectVideoProcessing

        return conversationService.prepareConversationHistory(
                chatHistory,
                processedInput,
                chatId,
                workspacePath,
                workspaceEnv,
                packageManager,
                promptFunctionType,
                thinkingGuidance,
                customSystemPromptTemplate,
                enableMemoryQuery,
                roleCardId,
                enableGroupOrchestrationHint,
                groupParticipantNamesText,
                proxySenderName,
                hasImageRecognition,
                hasAudioRecognition,
                hasVideoRecognition,
                chatModelHasDirectAudio,
                chatModelHasDirectVideo,
                useToolCallApi,
                chatModelHasDirectImage,
                dispatchHistoryHooks,
                dispatchSystemPromptComposeHooks,
                dispatchToolPromptComposeHooks
        )
    }

    private fun serializePromptHookModelParameters(
        modelParameters: List<com.apex.data.model.ModelParameter<*>>
    ): List<Map<String, Any?>> {
        return modelParameters.map { parameter ->
            mapOf(
                "id" to parameter.id,
                "name" to parameter.name,
                "apiName" to parameter.apiName,
                "description" to parameter.description,
                "defaultValue" to parameter.defaultValue,
                "currentValue" to parameter.currentValue,
                "isEnabled" to parameter.isEnabled,
                "valueType" to parameter.valueType.name,
                "minValue" to parameter.minValue,
                "maxValue" to parameter.maxValue,
                "category" to parameter.category.name,
                "isCustom" to parameter.isCustom
            )
        }
    }

    private fun serializePromptHookToolPrompts(
        toolPrompts: List<ToolPrompt>?
    ): List<Map<String, Any?>> {
        return toolPrompts.orEmpty().map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.parameters,
                "details" to tool.details,
                "notes" to tool.notes,
                "parametersStructured" to
                    tool.parametersStructured.orEmpty().map { parameter ->
                        mapOf(
                            "name" to parameter.name,
                            "type" to parameter.type,
                            "description" to parameter.description,
                            "required" to parameter.required,
                            "default" to parameter.default
                        )
                    }
            )
        }
    }

    /** Cancel the current conversation */
    fun cancelConversation() {
        invalidateAllExecutionContexts("cancelConversation")

        // Set conversation inactive
        // isConversationActive.set(false) // This is now per-context, can't set a global one

        // Cancel all underlying AIService streaming instances
        initScope.launch {
            runCatching {
                multiServiceManager.cancelAllStreaming()
            }.onFailure { e ->
                AppLogger.e(TAG, "取消AIService流式输出失败", e)
            }
        }

        // Cancel all tool executions
        cancelAllToolExecutions()

        // Clean up current conversation content
        // roundManager.clearContent() // This is now per-context, can't clear a global one
        AppLogger.d(TAG, "Conversation canceled")

        // Reset input processing state
        _inputProcessingState.value = InputProcessingState.Idle

        // Reset per-request token counts
        _perRequestTokenCounts.value = null
        accumulatedInputTokenCount = 0
        accumulatedOutputTokenCount = 0
        accumulatedCachedInputTokenCount = 0

        // Clear callback references
        currentResponseCallback = null
        currentCompleteCallback = null

        // 停止AI服务并关闭屏幕常�?       stopAiService()

        AppLogger.d(TAG, "Conversation cancellation complete")
    }

    /** Cancel all tool executions */
    private fun cancelAllToolExecutions() {
        toolProcessingScope.coroutineContext.cancelChildren()
    }

    /**
     * 获取可用工具列表（用于Tool Call API�?    * 如果模型配置启用了Tool Call，返回工具列表；否则返回null
     */
    private suspend fun getAvailableToolsForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ): List<ToolPrompt>? {
        return try {
            // 先读取全局工具和记忆开�?           val enableTools = apiPreferences.enableToolsFlow.first()
            val enableMemoryQuery = apiPreferences.enableMemoryQueryFlow.first()
            val toolPromptVisibility = runCatching {
                apiPreferences.toolPromptVisibilityFlow.first()
            }.getOrElse { emptyMap() }

            // 如果同时关闭了普通工具和记忆相关工具，则完全不提供Tool Call工具
            if (!enableTools && !enableMemoryQuery) {
                AppLogger.d(TAG, "全局设置已禁用工具和记忆，本次调用不提供任何Tool Call工具")
                return null
            }

            // 获取对应功能类型的模型配�?           val config = getModelConfigForFunction(
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )
            
            // 检查是否启用Tool Call
            if (!config.enableToolCall) {
                return null
            }
            
            // 获取所有工具分�?           val isEnglish = LocaleUtils.getCurrentLanguage(context) == "en"

            // 后端识图服务是否可用（IMAGE_RECOGNITION 功能），用于 intent-based 视觉模型
            val hasBackendImageRecognition = multiServiceManager.hasImageRecognitionConfigured()

            val hasBackendAudioRecognition = multiServiceManager.hasAudioRecognitionConfigured()
            val hasBackendVideoRecognition = multiServiceManager.hasVideoRecognitionConfigured()

            val safBookmarkNames = runCatching {
                apiPreferences.safBookmarksFlow.first().map { it.name }
            }.getOrElse { emptyList() }

            // 当前功能模型（通常是聊天模型）是否支持直接看图
            val chatModelHasDirectImage = config.enableDirectImageProcessing

            val chatModelHasDirectAudio = config.enableDirectAudioProcessing
            val chatModelHasDirectVideo = config.enableDirectVideoProcessing

            val categories = if (isEnglish) {
                SystemToolPrompts.getAIAllCategoriesEn(
                    hasBackendImageRecognition = hasBackendImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasBackendAudioRecognition = hasBackendAudioRecognition,
                    hasBackendVideoRecognition = hasBackendVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    safBookmarkNames = safBookmarkNames
                )
            } else {
                SystemToolPrompts.getAIAllCategoriesCn(
                    hasBackendImageRecognition = hasBackendImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasBackendAudioRecognition = hasBackendAudioRecognition,
                    hasBackendVideoRecognition = hasBackendVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    safBookmarkNames = safBookmarkNames
                )
            }

            // 按类别拆分记忆工具和非记忆工具，以与 SystemPromptConfig 中的语义保持一�?           val memoryCategoryName = context.getString(R.string.enhanced_memory_tools_category)

            val memoryTools = categories
                .firstOrNull { it.categoryName == memoryCategoryName }
                ?.tools
                ?: emptyList()

            val nonMemoryTools = categories
                .filter { it.categoryName != memoryCategoryName }
                .flatMap { it.tools }

            // 根据开关组合最终可用工具：
            // - enableTools && enableMemoryQuery      -> 所有工�?           // - enableTools && !enableMemoryQuery     -> 仅非记忆工具
            // - !enableTools && enableMemoryQuery     -> 仅记忆工�?           val selectedTools = mutableListOf<ToolPrompt>()
            if (enableTools) {
                selectedTools.addAll(nonMemoryTools)
            }
            if (enableMemoryQuery) {
                selectedTools.addAll(memoryTools)
            }

            selectedTools.retainAll { tool ->
                toolPromptVisibility[tool.name] != false
            }

            if (config.enableToolCall) {
                selectedTools.add(
                    ToolPrompt(
                        name = "package_proxy",
                        description = "Proxy tool for package tools activated by use_package.",
                        parametersStructured = listOf(
                            ToolParameterSchema(
                                name = "tool_name",
                                type = "string",
                                description = "Target tool name from an activated package (for example: packageName:toolName)",
                                required = true
                            ),
                            ToolParameterSchema(
                                name = "params",
                                type = "object",
                                description = "JSON object of parameters to forward to the target tool",
                                required = true
                            )
                        )
                    )
                )
            }

            if (selectedTools.isEmpty()) {
                AppLogger.d(TAG, "根据当前工具/记忆开关，未选择任何Tool Call工具")
                return null
            }

            AppLogger.d(
                TAG,
                "Tool Call已启用，提供 ${selectedTools.size} 个工�?enableTools=${enableTools}, enableMemoryQuery=${enableMemoryQuery}, visibleToolOverrides=${toolPromptVisibility.size}, roleCardCustomTools=${roleCardToolAccess.customEnabled})"
            )
            selectedTools
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取工具列表失败", e)
            null
        }
    }

    // --- Service Lifecycle Management ---

    /** 启动或更新前台服务为“AI 正在运行”状态，以保持应用活�?/
    private fun startAiService(characterName: String? = null, avatarUri: String? = null) {
        val refCount = FOREGROUND_REF_COUNT.incrementAndGet()
        val appInForeground = ActivityLifecycleManager.getCurrentActivity() != null
        val alwaysListeningEnabled = runCatching {
            runBlocking { WakeWordPreferences(context).alwaysListeningEnabledFlow.first() }
        }.getOrDefault(false)
        val externalHttpEnabled = runCatching {
            runBlocking { ExternalHttpApiPreferences.getInstance(context).enabledFlow.first() }
        }.getOrDefault(false)
        if (!appInForeground &&
            !AIForegroundService.isRunning.get() &&
            !alwaysListeningEnabled &&
            !externalHttpEnabled
        ) {
            AppLogger.d(TAG, "应用不在前台，跳过启动AIForegroundService")
            return
        }
        try {
            val updateIntent = Intent(context, AIForegroundService::class.java).apply {
                putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_RUNNING)
                if (characterName != null) {
                    putExtra(AIForegroundService.EXTRA_CHARACTER_NAME, characterName)
                }
                if (avatarUri != null) {
                    putExtra(AIForegroundService.EXTRA_AVATAR_URI, avatarUri)
                }
            }
            context.startService(updateIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新AI前台服务为运行中状态失�?${e.message}", e)
        }

        if (refCount == 1) {
            ActivityLifecycleManager.checkAndApplyKeepScreenOn(true)
        }
    }

    private fun notifyReplyCompleted(
        chatId: String?,
        characterName: String? = null,
        avatarUri: String? = null
    ) {
        AIForegroundService.notifyReplyCompleted(
            context = context,
            chatId = chatId,
            characterName = characterName,
            rawReplyContent = lastReplyContent,
            avatarUri = avatarUri
        )
    }

    /** 将前台服务更新为“空，已完成”状态，但不真正停止服务 */
    private fun stopAiService(characterName: String? = null, avatarUri: String? = null) {
        val remaining = run {
            var remainingValue = -1
            while (true) {
                val current = FOREGROUND_REF_COUNT.get()
                if (current <= 0) {
                    remainingValue = -1
                    break
                }
                val next = current - 1
                if (FOREGROUND_REF_COUNT.compareAndSet(current, next)) {
                    remainingValue = next
                    break
                }
            }
            remainingValue
        }
        if (remaining < 0) return
        if (remaining > 0) return
         if (AIForegroundService.isRunning.get()) {
             AppLogger.d(TAG, "更新AI前台服务为闲置状�?.")

            try {
                val stopIntent = Intent(context, AIForegroundService::class.java).apply {
                    putExtra(AIForegroundService.EXTRA_CHARACTER_NAME, characterName)
                    putExtra(AIForegroundService.EXTRA_AVATAR_URI, avatarUri)
                    putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_IDLE)
                }

                AppLogger.d(TAG, "传递闲置状�? 角色: ${characterName}, 头像: ${avatarUri}")

                // 仅发送更新，不再真正停止前台服务
                context.startService(stopIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新AI前台服务为闲置状态失�?${e.message}", e)
            }
        } else {
            AppLogger.d(TAG, "AI前台服务未在运行，无需更新闲置状态，)
        }

        // 使用管理器来恢复屏幕常亮设置
        ActivityLifecycleManager.checkAndApplyKeepScreenOn(false)
    }

    /**
     * 处理文件绑定操作（实例方法）
     * @param originalContent 原始文件内容
     * @param aiGeneratedCode AI生成的代码（包含"//existing code"标记�?    * @return 混合后的文件内容
     */
    suspend fun applyFileBinding(
            originalContent: String,
            aiGeneratedCode: String
    ): Pair<String, String> {
        return fileBindingService.processFileBinding(
                originalContent,
                aiGeneratedCode
        )
    }

    /**
     * 翻译文本功能
     * @param text 要翻译的文本
     * @return 翻译后的文本
     */
    suspend fun translateText(text: String): String {
        return conversationService.translateText(text, multiServiceManager)
    }

    /**
     * 自动生成工具包描�?    * @param pluginName 工具包名�?    * @param toolDescriptions 工具描述列表
     * @return 生成的工具包描述
     */
    suspend fun generatePackageDescription(
        pluginName: String,
        toolDescriptions: List<String>
    ): String {
        return conversationService.generatePackageDescription(pluginName, toolDescriptions, multiServiceManager)
    }


    /**
     * Manually saves the current conversation to the problem library.
     * @param conversationHistory The history of the conversation to save.
     * @param lastContent The content of the last message in the conversation.
     */
    suspend fun saveConversationToMemory(
        conversationHistory: List<Pair<String, String>>,
        lastContent: String
    ) {
        AppLogger.d(TAG, "手动触发记忆更新...")
        withContext(Dispatchers.IO) {
            try {
                com.apex.api.chat.library.ProblemLibrary.saveProblemAsync(
                    context,
                    toolHandler,
                    conversationHistory,
                    lastContent,
                    multiServiceManager.getServiceForFunction(FunctionType.PROBLEM_LIBRARY)
                )
                AppLogger.d(TAG, "手动记忆更新成功")
            } catch (e: Exception) {
                AppLogger.e(TAG, "手动记忆更新失败", e)
                throw e
            }
        }
    }

    /**
     * 使用识图模型分析图片
     * @param imagePath 图片路径
     * @param userIntent 用户意图，例，这个图片里面有什，，图片的题目公式是什，，     * @return AI分析结果
     */
    suspend fun analyzeImageWithIntent(imagePath: String, userIntent: String): String {
        return conversationService.analyzeImageWithIntent(imagePath, userIntent, multiServiceManager)
    }

    suspend fun analyzeAudioWithIntent(audioPath: String, userIntent: String): String {
        return conversationService.analyzeAudioWithIntent(audioPath, userIntent, multiServiceManager)
    }

    suspend fun analyzeVideoWithIntent(videoPath: String, userIntent: String): String {
        return conversationService.analyzeVideoWithIntent(videoPath, userIntent, multiServiceManager)
    }
}
