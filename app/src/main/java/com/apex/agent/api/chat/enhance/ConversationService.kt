package com.apex.api.chat.enhance

import android.content.Context
import com.apex.util.AppLogger
import com.apex.core.chat.hooks.PromptHookContext
import com.apex.core.chat.hooks.PromptHookRegistry
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.core.chat.hooks.toPromptTurns
import com.apex.core.config.SystemPromptConfig
import com.apex.agent.R
import com.apex.core.tools.AIToolHandler
import com.apex.core.tools.packTool.PackageManager
import com.apex.data.model.AITool
import com.apex.data.model.FunctionType
import com.apex.data.model.PreferenceProfile
import com.apex.data.model.ToolParameter
import com.apex.core.tools.UIPageResultData
import com.apex.core.tools.SimplifiedUINode
import com.apex.core.config.FunctionalPrompts
import com.apex.data.preferences.ApiPreferences
import com.apex.data.preferences.DisplayPreferencesManager
import com.apex.data.preferences.WaifuPreferences
import com.apex.data.preferences.ActivePromptManager
import com.apex.data.model.PromptFunctionType
import com.apex.data.preferences.preferencesManager
import com.apex.util.ChatMarkupRegex
import com.apex.util.ChatUtils
import com.apex.core.tools.ToolProgressBus
import com.apex.util.streamnative.NativeXmlSplitter
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.apex.core.tools.ComputerDesktopActionResultData
import com.apex.util.LocaleUtils
import com.apex.api.chat.enhance.MultiServiceManager
import com.apex.data.repository.CustomEmojiRepository
import com.apex.api.chat.llmprovider.MediaLinkBuilder
import com.apex.data.repository.getCustomMoodDefinitions
import com.apex.data.repository.getMoodAnimationMapping
import com.apex.agent.core.hooks.HookRegistry
import com.apex.agent.core.hooks.SessionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 处理会话相关功能的服务类，包括会话总结、偏好处理和对话切割准备 */
class ConversationService(
    private val context: Context,
    private val customEmojiRepository: CustomEmojiRepository
    ) {

    companion object {
        private const val TAG = "ConversationService"
        private const val APPLY_FILE_TOOL_NAME = "apply_file"
        private val fileRequestContentRegex = Regex(
            """<file-request-content\b[^>]*><!\[CDATA\[(.*)\]\]></file-request-content>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }

    private val apiPreferences = ApiPreferences.getInstance(context)
    private val displayPreferencesManager = DisplayPreferencesManager.getInstance(context)
    private val waifuPreferences = WaifuPreferences.getInstance(context)
    private val activePromptManager = ActivePromptManager.getInstance(context)
    private val userPreferencesManager = preferencesManager
    private val conversationMutex = Mutex()

    /**
     * 在压缩操作前触发钩子，保存关键状�?
     * @param sessionId 会话ID
     * @param messageCount 消息数量
     * @param tokenUsage token使用�?
     * @param environmentState 环境状�?
     * @return 钩子收集的状态数�?
     */
    suspend fun triggerPreCompactHook(
        sessionId: String,
        messageCount: Int,
        tokenUsage: Long,
        environmentState: Map<String, String> = emptyMap()
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val sessionContext = SessionContext(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                lastActivity = System.currentTimeMillis(),
                messageCount = messageCount,
                tokenUsage = tokenUsage,
                environmentState = environmentState
            )
            HookRegistry.triggerPreCompact(context, sessionContext)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger preCompact hook", e)
            emptyMap()
        }
    }

    /**
     * 生成对话总结
     * @param messages 要总结的消息列�?    * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            multiServiceManager: MultiServiceManager
    ): String {
        return generateSummaryFromPromptTurns(messages.toPromptTurns(), null, multiServiceManager)
    }

    /**
     * 生成对话总结，并且包含上一次的总结内容
     * @param messages 要总结的消息列�?    * @param previousSummary 上一次的总结内容，可以为null
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?,
            multiServiceManager: MultiServiceManager
    ): String {
        return generateSummaryFromPromptTurns(messages.toPromptTurns(), previousSummary, multiServiceManager)
    }

    suspend fun generateSummaryFromPromptTurns(
            messages: List<PromptTurn>,
            previousSummary: String?,
            multiServiceManager: MultiServiceManager
    ): String {
        try {
            val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
            val systemPrompt = FunctionalPrompts.buildSummarySystemPrompt(previousSummary, useEnglish)
            val sanitizedMessages = ChatUtils.stripGeminiThoughtSignatureMetaTurns(messages)

            val finalMessages =
                listOf(PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt)) +
                    sanitizedMessages

            // Get all model parameters from preferences (with enabled state)
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.SUMMARY)

            // 获取SUMMARY功能类型的AIService实例
            val summaryService = multiServiceManager.getServiceForFunction(FunctionType.SUMMARY)

            // 使用summaryService发送请求，收集完整响应
            val contentBuilder = StringBuilder()

            ToolProgressBus.update(
                ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                0.05f,
                context.getString(R.string.conversation_summary_preparing)
            )

            data class Stage(
                val matchers: List<(String) -> Boolean>,
                val progress: Float,
                val message: String
            )

            val stages = listOf(
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_MARKER_EN) || it.contains(FunctionalPrompts.SUMMARY_MARKER_CN) }),
                    progress = 0.20f,
                    message = context.getString(R.string.conversation_summary_writing_title)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_CORE_TASK_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_CORE_TASK_CN) }),
                    progress = 0.40f,
                    message = context.getString(R.string.conversation_summary_core_task)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_INTERACTION_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_INTERACTION_CN) }),
                    progress = 0.55f,
                    message = context.getString(R.string.conversation_summary_interaction)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_PROGRESS_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_PROGRESS_CN) }),
                    progress = 0.70f,
                    message = context.getString(R.string.conversation_summary_progress)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_KEY_INFO_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_KEY_INFO_CN) }),
                    progress = 0.85f,
                    message = context.getString(R.string.conversation_summary_key_info)
                ),
                Stage(
                    matchers = listOf({ it.contains("=======================================") || it.contains("============================") }),
                    progress = 0.95f,
                    message = context.getString(R.string.conversation_summary_finishing)
                )
            )

            var lastStageIndex = -1
            fun updateStageIfNeeded() {
                if (lastStageIndex + 1 >= stages.size) return
                val snapshot = contentBuilder.toString()
                while (lastStageIndex + 1 < stages.size) {
                    val next = stages[lastStageIndex + 1]
                    val matched = next.matchers.any { it(snapshot) }
                    if (!matched) break
                    lastStageIndex += 1
                    ToolProgressBus.update(
                        ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                        next.progress,
                        next.message
                    )
                }
            }

            // 使用新的Stream API
            val stream =
                    summaryService.sendMessage(
                            context = context,
                            chatHistory =
                                finalMessages + PromptTurn(
                                    kind = PromptTurnKind.USER,
                                    content = FunctionalPrompts.summaryUserMessage(useEnglish)
                                ),
                            modelParameters = modelParameters
                    )

            // 收集流中的所有内�?           stream.collect { content ->
                contentBuilder.append(content)
                updateStageIfNeeded()
            }

            ToolProgressBus.update(
                ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                1f,
                context.getString(R.string.conversation_summary_completed)
            )

            // 获取完整的总结内容
            val summaryContent = ChatUtils.removeThinkingContent(contentBuilder.toString().trim())

            // 如果内容为空，返回默认消�?           if (summaryContent.isBlank()) {
                return "Conversation Summary: Unable to generate valid summary."
            }

            // 获取本次总结生成的token统计
            val inputTokens = summaryService.inputTokenCount
            val cachedInputTokens = summaryService.cachedInputTokenCount
            val outputTokens = summaryService.outputTokenCount

            // 将总结token计数添加到用户偏好分析的token统计�?           try {
                AppLogger.d(TAG, "总结生成使用了输入token: ${inputTokens}, 缓存token: ${cachedInputTokens}, 输出token: ${outputTokens}")
                apiPreferences.updateTokensForProviderModel(summaryService.providerModel, inputTokens, outputTokens, cachedInputTokens)
                
                // Update request count for summary generation
                apiPreferences.incrementRequestCountForProviderModel(summaryService.providerModel)
                
                AppLogger.d(TAG, "已将总结token统计添加到用户偏好分析token计数据）
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新token统计失败", e)
            }

            return summaryContent
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成总结时出�? e)
            // return "对话摘要：生成摘要时出错，但对话仍在继续�?
            throw e
        }
    }

    /**
     * 为聊天准备对话历史记�?    * @param chatHistory 原始聊天历史
     * @param processedInput 处理后的用户输入
     * @param workspacePath 当前绑定的工作区路径，可以为null
     * @param packageManager 包管理器
     * @param promptFunctionType 提示函数类型
     * @param thinkingGuidance 是否需要思考指�?    * @param enableMemoryQuery Whether the AI is allowed to query memories.
     * @param hasImageRecognition Whether a backend image recognition service is configured
     * @return 准备好的对话历史列表
     */
    suspend fun prepareConversationHistory(
            chatHistory: List<PromptTurn>,
            processedInput: String,
            chatId: String?,
            workspacePath: String?,
            workspaceEnv: String? = null,
            packageManager: PackageManager,
            promptFunctionType: PromptFunctionType,
            thinkingGuidance: Boolean = false,
            customSystemPromptTemplate: String? = null,
            enableMemoryQuery: Boolean = true,
            enableGroupOrchestrationHint: Boolean = false,
            groupParticipantNamesText: String? = null,
            proxySenderName: String? = null,
            hasImageRecognition: Boolean = false,
            hasAudioRecognition: Boolean = false,
            hasVideoRecognition: Boolean = false,
            chatModelHasDirectAudio: Boolean = false,
            chatModelHasDirectVideo: Boolean = false,
            useToolCallApi: Boolean = false,
            chatModelHasDirectImage: Boolean = false,
            dispatchHistoryHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchPromptHistoryHooks,
            dispatchSystemPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchSystemPromptComposeHooks,
            dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): List<PromptTurn> {
        val beforeContext =
            dispatchHistoryHooks(
                PromptHookContext(
                    stage = "before_prepare_history",
                    chatId = chatId,
                    promptFunctionType = promptFunctionType.name,
                    processedInput = processedInput,
                    chatHistory = chatHistory,
                    metadata =
                        mapOf(
                            "workspacePath" to workspacePath,
                            "workspaceEnv" to workspaceEnv,
                            "thinkingGuidance" to thinkingGuidance,
                            "customSystemPromptTemplate" to customSystemPromptTemplate,
                            "enableMemoryQuery" to enableMemoryQuery,
                            "enableGroupOrchestrationHint" to enableGroupOrchestrationHint,
                            "groupParticipantNamesText" to groupParticipantNamesText,
                            "proxySenderName" to proxySenderName,
                            "hasImageRecognition" to hasImageRecognition,
                            "hasAudioRecognition" to hasAudioRecognition,
                            "hasVideoRecognition" to hasVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "useToolCallApi" to useToolCallApi,
                            "chatModelHasDirectImage" to chatModelHasDirectImage
                        )
                )
            )
        val effectiveChatHistory = beforeContext.chatHistory
        val preparedHistory = mutableListOf<PromptTurn>()
        var resolvedUseEnglish: Boolean? = null
        conversationMutex.withLock {
            // Add system prompt if not already present
            if (!effectiveChatHistory.any { it.kind == PromptTurnKind.SYSTEM }) {
                val safeProxySenderName = proxySenderName?.takeIf { it.isNotBlank() }

                val preferencesText = if (safeProxySenderName == null) {
                    val activeProfile = preferencesManager.getUserPreferencesFlow().first()
                    buildPreferencesText(activeProfile)
                } else {
                    ""
                }

                // 获取自定义系统提示模�?               val finalCustomSystemPromptTemplate = customSystemPromptTemplate ?: apiPreferences.customSystemPromptTemplateFlow.first()

                // 获取工具启用状�?               val enableTools = apiPreferences.enableToolsFlow.first()
                val disableUserPreferenceDescription =
                        apiPreferences.disableUserPreferenceDescriptionFlow.first()
                val disableLatexDescription = apiPreferences.disableLatexDescriptionFlow.first()
                val disableStatusTags = apiPreferences.disableStatusTagsFlow.first()
                val toolPromptVisibility = runCatching {
                    apiPreferences.toolPromptVisibilityFlow.first()
                }.getOrElse { emptyMap() }

                val safBookmarkNames = runCatching {
                    apiPreferences.safBookmarksFlow.first().map { it.name }
                }.getOrElse { emptyList() }

                val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
                resolvedUseEnglish = useEnglish

                // 获取系统提示词，现在传入workspacePath和识图配置状�?               val systemPrompt = SystemPromptConfig.getSystemPromptWithCustomPrompts(
                    context = context,
                    packageManager = packageManager,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                    safBookmarkNames = safBookmarkNames,
                    customIntroPrompt = "",
                    useEnglish = useEnglish,
                    thinkingGuidance = thinkingGuidance,
                    customSystemPromptTemplate = finalCustomSystemPromptTemplate,
                    enableTools = enableTools,
                    enableMemoryQuery = enableMemoryQuery,
                    hasImageRecognition = hasImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasAudioRecognition = hasAudioRecognition,
                    hasVideoRecognition = hasVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    useToolCallApi = useToolCallApi,
                    disableLatexDescription = disableLatexDescription,
                    disableStatusTags = disableStatusTags,
                    toolVisibility = toolPromptVisibility,
                    allowedPackageNames = emptySet(),
                    allowedSkillNames = emptySet(),
                    allowedMcpServerNames = emptySet(),
                    enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                    groupOrchestrationRoleName = context.getString(R.string.app_name),
                    groupParticipantNamesText = groupParticipantNamesText.orEmpty(),
                    dispatchSystemPromptComposeHooks = dispatchSystemPromptComposeHooks,
                    dispatchToolPromptComposeHooks = dispatchToolPromptComposeHooks
                )

                // 构建waifu特殊规则
                val waifuRulesText = if(waifuPreferences.enableWaifuModeFlow.first()) buildWaifuRulesText() else ""

                // 构建最终的系统提示�?               val finalSystemPrompt = buildString {
                    append(systemPrompt)
                    append(waifuRulesText)
                    if (!disableUserPreferenceDescription && preferencesText.isNotEmpty()) {
                        append("\n\nUser preference description: ")
                        append(preferencesText)
                    }
                }

                // 替换提示词中的占位符
                val aiName = context.getString(R.string.app_name)
                val finalSystemPromptWithReplacements = replacePromptPlaceholders(
                    finalSystemPrompt,
                    aiName
                )
                preparedHistory.add(
                    0,
                    PromptTurn(
                        kind = PromptTurnKind.SYSTEM,
                        content = finalSystemPromptWithReplacements
                    )
                )
            }

            // Process each message in chat history
            effectiveChatHistory.forEachIndexed { index, message ->
                val kind = message.kind
                val content = message.content

                // If it's an assistant message, check for tool results
                if (kind == PromptTurnKind.ASSISTANT) {
                    val xmlTags = splitXmlTag(content)
                    if (xmlTags.isNotEmpty()) {
                        // Process the message with tool results
                        processChatMessageWithTools(content, xmlTags, preparedHistory, index, effectiveChatHistory.size)
                    } else {
                        // Add the message as is
                        preparedHistory.add(message)
                    }
                } else if (kind == PromptTurnKind.TOOL_RESULT) {
                    preparedHistory.add(message.copy(content = normalizeToolResultMarkupForModel(content)))
                } else {
                    // Add typed turns as is
                    preparedHistory.add(message)
                }
            }
        }
        val afterContext =
            dispatchHistoryHooks(
                beforeContext.copy(
                    stage = "after_prepare_history",
                    useEnglish = resolvedUseEnglish,
                    preparedHistory = preparedHistory
                )
            )
        return afterContext.preparedHistory
    }

    /**
     * 提取内容中的XML标签
     * @param content 要处理的内容
     * @return 提取的XML标签列表，每项包含[标签名称, 标签内容]
     */
    fun splitXmlTag(content: String): List<List<String>> {
        return NativeXmlSplitter.splitXmlTag(content)
    }

    fun normalizeConversationHistoryForModel(
        chatHistory: List<PromptTurn>
    ): List<PromptTurn> {
        return chatHistory.map { turn ->
            when (turn.kind) {
                PromptTurnKind.ASSISTANT,
                PromptTurnKind.TOOL_CALL,
                PromptTurnKind.TOOL_RESULT -> turn.copy(content = normalizeToolResultMarkupForModel(turn.content))
                else -> turn
            }
        }
    }

    private fun normalizeToolResultMarkupForModel(content: String): String {
        return ChatMarkupRegex.toolResultTagWithAttrs.replace(content) { matchResult ->
            val tagName = matchResult.groupValues[1]
            val attrs = matchResult.groupValues[2]
            val body = matchResult.groupValues[3]
            val toolName =
                ChatMarkupRegex.nameAttr.find(attrs)?.groupValues?.getOrNull(1).orEmpty()

            if (!toolName.equals(APPLY_FILE_TOOL_NAME, ignoreCase = true)) {
                return@replace matchResult.value
            }

            val requestContent =
                extractApplyFileRequestContent(body) ?: return@replace matchResult.value
            "<${tagName}${attrs}><content>${requestContent}</content></${tagName}>"
        }
    }

    private fun extractApplyFileRequestContent(toolResultBody: String): String? {
        val contentBody =
            ChatMarkupRegex.contentTag.find(toolResultBody)?.groupValues?.getOrNull(1)
                ?: toolResultBody

        return fileRequestContentRegex.find(contentBody)?.groupValues?.getOrNull(1)?.trim()
    }

    /** 处理包含工具结果的聊天消息，并按顺序重新组织消息 任务完成和等待用户响应的status标签算作AI消息，其他status和warning算作用户消息 工具结果为用户消�?/
    suspend fun processChatMessageWithTools(
            content: String,
            xmlTags: List<List<String>>,
            conversationHistory: MutableList<PromptTurn>,
            messageIndex: Int,
            totalMessages: Int
    ) {
        if (xmlTags.isEmpty()) {
            // 如果没有XML标签，直接添加为AI消息
            conversationHistory.add(
                PromptTurn(
                    kind = PromptTurnKind.ASSISTANT,
                    content = content
                )
            )
            return
        }

        // 按顺序处理标�?       val segments = mutableListOf<PromptTurn>()

        for (tag in xmlTags) {
            val tagName = tag[0]
            val normalizedTagName = ChatMarkupRegex.normalizeToolLikeTagName(tagName) ?: tagName
            var tagContent = tag[1]

            // 对于text类型（纯文本），直接作为AI消息
            if (tagName == "text") {
                if (tagContent.isNotBlank()) {
                    segments.add(PromptTurn(kind = PromptTurnKind.ASSISTANT, content = tagContent))
                }
                continue
            }

            // 根据标签类型分配角色
            when (normalizedTagName) {
                "think", "thinking" -> {
                    // 保留完整的think标签（用于DeepSeek推理模式�?                   segments.add(PromptTurn(kind = PromptTurnKind.ASSISTANT, content = tagContent))
                }
                "status" -> {
                    // 判断status类型
                    if (tagContent.contains("type=\"complete\"") ||
                                    tagContent.contains("type=\"wait_for_user_need\"")
                    ) {
                        segments.add(PromptTurn(kind = PromptTurnKind.ASSISTANT, content = tagContent))
                    } else {
                        segments.add(PromptTurn(kind = PromptTurnKind.USER, content = tagContent))
                    }
                }
                "tool_result" -> {
                    segments.add(
                        PromptTurn(
                            kind = PromptTurnKind.TOOL_RESULT,
                            content = normalizeToolResultMarkupForModel(tagContent)
                        )
                    )
                }
                "tool" -> {
                    segments.add(PromptTurn(kind = PromptTurnKind.TOOL_CALL, content = tagContent))
                }
                else -> {
                    segments.add(PromptTurn(kind = PromptTurnKind.ASSISTANT, content = tagContent))
                }
            }
        }

        // 合并连续的相同角色消�?       val mergedSegments = mutableListOf<PromptTurn>()
        var currentKind: PromptTurnKind? = null
        var currentContent = StringBuilder()
        var currentToolName: String? = null
        var currentMetadata: Map<String, Any?> = emptyMap()

        for (segment in segments) {
            val shouldMergeCurrent =
                segment.kind == currentKind &&
                    segment.kind !in setOf(PromptTurnKind.TOOL_CALL, PromptTurnKind.TOOL_RESULT)
            if (shouldMergeCurrent) {
                // 如果角色与当前角色相同，则合并内�?               currentContent.append("\n").append(segment.content)
            } else {
                // 角色不同，先保存当前内容（如果有�?               if (currentContent.isNotEmpty() && currentKind != null) {
                    mergedSegments.add(
                        PromptTurn(
                            kind = currentKind!!,
                            content = currentContent.toString().trim(),
                            toolName = currentToolName,
                            metadata = currentMetadata
                        )
                    )
                    currentContent.clear()
                }
                // 更新当前角色和内�?               currentKind = segment.kind
                currentToolName = segment.toolName
                currentMetadata = segment.metadata
                currentContent.append(segment.content)
            }
        }

        // 添加最后一条消�?       if (currentContent.isNotEmpty() && currentKind != null) {
            mergedSegments.add(
                PromptTurn(
                    kind = currentKind!!,
                    content = currentContent.toString().trim(),
                    toolName = currentToolName,
                    metadata = currentMetadata
                )
            )
        }

        // 将合并后的消息添加到对话历史
        conversationHistory.addAll(mergedSegments)
    }

    /** Build a formatted preferences text string from a PreferenceProfile */
    fun buildPreferencesText(profile: PreferenceProfile): String {
        val parts = mutableListOf<String>()

        if (profile.gender.isNotEmpty()) {
            parts.add("Gender: ${profile.gender}")
        }

        if (profile.birthDate > 0) {
            // Convert timestamp to age and format as text
            val today = Calendar.getInstance()
            val birthCal = Calendar.getInstance().apply { timeInMillis = profile.birthDate }
            var age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
            // Adjust age if birthday hasn't occurred yet this year
            if (today.get(Calendar.MONTH) < birthCal.get(Calendar.MONTH) ||
                            (today.get(Calendar.MONTH) == birthCal.get(Calendar.MONTH) &&
                                    today.get(Calendar.DAY_OF_MONTH) <
                                            birthCal.get(Calendar.DAY_OF_MONTH))
            ) {
                age--
            }
            parts.add("Age: ${age}")

            // Also add birth date for more precise information
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add("Birth Date: ${dateFormat.format(java.util.Date(profile.birthDate))}")
        }

        if (profile.personality.isNotEmpty()) {
            parts.add("Personality: ${profile.personality}")
        }

        if (profile.identity.isNotEmpty()) {
            parts.add("Identity: ${profile.identity}")
        }

        if (profile.occupation.isNotEmpty()) {
            parts.add("Occupation: ${profile.occupation}")
        }

        if (profile.aiStyle.isNotEmpty()) {
            parts.add("Expected AI Style: ${profile.aiStyle}")
        }

        return parts.joinToString("; ")
    }

    /** Data class for search-replace operations, used for JSON deserialization. */
    private data class SearchReplaceOperation(val search: String, val replace: String)

    /**
     * Flattens the hierarchical UI node structure into a simple, flat list of key elements.
     * This provides a much cleaner context for the AI to make decisions.
     */
    private fun flattenUiInfo(pageInfo: UIPageResultData): String {
        val clickableElements = mutableListOf<String>()
        val screenTexts = mutableListOf<String>()

        fun traverse(node: SimplifiedUINode) {
            // If the node is clickable, treat it as an atomic unit. We'll gather all text from
            // its entire subtree to form a comprehensive description for the AI.
            if (node.isClickable) {
                val parts = mutableListOf<String>()
                
                // Start by collecting standard properties like resource ID, class, and bounds.node.resourceId?.takeIf { it.isNotBlank() }?.let { parts.add("id: ${it}") }

                // --- NEW: Recursively find all text and content descriptions in the subtree ---
                val descriptiveTexts = mutableListOf<String>()
                fun findTextsRecursively(n: SimplifiedUINode) {
                    n.text?.takeIf { it.isNotBlank() }?.let { descriptiveTexts.add(it) }
                    n.contentDesc?.takeIf { it.isNotBlank() }?.let { descriptiveTexts.add(it) }
                    n.children.forEach(::findTextsRecursively)
                }
                findTextsRecursively(node)

                // Combine all found texts into a single descriptive string. This is crucial for
                // elements where the text label is in a child node of the clickable area.
                val combinedText = descriptiveTexts.distinct().joinToString(" | ")
                if (combinedText.isNotBlank()) {
                    // Using "desc" to signify this is a constructed description. Increased length.parts.add("desc: \"${combinedText.replace("\"", "'").take(80)}\"")
                }
                // --- END NEW ---

                node.className?.let { parts.add("class: ${it.substringAfterLast('.')}") }
                node.bounds?.let { parts.add("bounds: ${it.replace(' ', ',')}") }

                // Only add the element if it has some identifiable information.
                if (parts.isNotEmpty()) {
                    clickableElements.add("[${parts.joinToString(", ")}]")
                }
                // Once an element is identified as clickable, we don't process its children separately.
            } else {
                // If the node is not clickable, add its text for general context and continue traversal.node.text?.takeIf { it.isNotBlank() }?.let {
                    screenTexts.add("\"${it.replace("\"", "'").take(70)}\"")
                }
                node.children.forEach(::traverse)
            }
        }

        traverse(pageInfo.uiElements)

        // Use distinct to remove duplicate text entries from non-clickable elements.
        val distinctScreenTexts = screenTexts.distinct()

        return """
        Package: ${pageInfo.packageName}
        Activity: ${pageInfo.activityName}
        Clickable Elements:
        ${clickableElements.joinToString("\n")}
        Screen Text (
        for context):
        ${distinctScreenTexts.joinToString("\n")}
        """.trimIndent()
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keysItr = this.keys()
        while (keysItr.hasNext()) {
            val key = keysItr.next()
            var value = this.get(key)
            if (value is JSONObject) {
                value = value.toMap()
            }
            if (value is JSONArray) {
                value = value.toList()
            }
            map[key] = value
        }
        return map
    }

    private fun JSONArray.toList(): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until this.length()) {
            var value = this.get(i)
            if (value is JSONObject) {
                value = value.toMap()
            }
            if (value is JSONArray) {
                value = value.toList()
            }
            list.add(value)
        }
        return list
    }

    private fun createToolFromJson(type: String, arg: Any): AITool {
        val parameters = mutableListOf<ToolParameter>()
        when (arg) {
            is Map<*, *> -> {
                arg.forEach { (key, value) ->
                    val stringValue = when (value) {
                        is Double -> {
                            // If the double has no fractional part, convert to Int string to avoid parse errors.
                            if (value % 1.0 == 0.0) {
                                value.toInt().toString()
                            } else {
                                value.toString()
                            }
                        }
                        else -> value.toString()
                    }
                    parameters.add(ToolParameter(key.toString(), stringValue))
                }
            }
            is String -> {
                 // Fallback for when the AI returns a raw string instead of a JSON object.
                 when (type) {
                     "press_key" -> parameters.add(ToolParameter("key_code", arg))
                     "set_input_text" -> parameters.add(ToolParameter("text", arg))
                     "start_app" -> parameters.add(ToolParameter("package_name", arg))
                 }
            }
        }
        return AITool(type, parameters)
    }

    /**
     * 构建waifu模式的特殊规则文�?    * @return 格式化的waifu规则文本，如果没有规则则返回空字符串
     */
    private suspend fun buildWaifuRulesText(): String {
        val activePrompt = activePromptManager.getActivePrompt()
        val waifuEnableEmoticons = waifuPreferences.waifuEnableEmoticonsFlow.first()
        val waifuEnableSelfie = waifuPreferences.waifuEnableSelfieFlow.first()
        val waifuCustomPrompt = waifuPreferences.waifuCustomPromptFlow.first()
        val waifuSelfiePrompt = waifuPreferences.waifuSelfiePromptFlow.first()
        val waifuRules = mutableListOf<String>()

        if (waifuEnableEmoticons) {
            // 动态获取当前可用的表情分组
            val availableCategories = try {
                customEmojiRepository.initializeBuiltinEmojis(activePrompt)
                customEmojiRepository.getAllCategories(activePrompt).first()
            } catch (e: Exception) {
                com.apex.util.AppLogger.e("ConversationService", "获取表情分组失败", e)
                emptyList()
            }
            
            if (availableCategories.isNotEmpty()) {
                val emotionListText = availableCategories.joinToString(", ")
                waifuRules.add(FunctionalPrompts.waifuEmotionRule(emotionListText))
            } else {
                // 如果没有自定义表情，则不添加情绪规则，或明确告知没有可用表情
                waifuRules.add(FunctionalPrompts.waifuNoCustomEmojiRule())
            }
        }
        
        if (waifuEnableSelfie) {
            waifuRules.add(FunctionalPrompts.waifuSelfieRule(waifuSelfiePrompt))
        }

        if (waifuCustomPrompt.isNotBlank()) {
            waifuRules.add(FunctionalPrompts.waifuCustomPromptRule(waifuCustomPrompt))
        }

        return if (waifuRules.isNotEmpty()) {
            buildString {
                append("\n\n[Extra Rules]")
                waifuRules.forEach { rule ->
                    append("\n- ${rule}")
                }
            }
        } else ""
    }

    /**
     * 虚拟形象�?mood> 标签规则，已移除�?     */
    private fun buildAvatarMoodRulesText(useEnglish: Boolean): String {
        return ""
    }

    private fun shouldInjectMoodRules(promptFunctionType: PromptFunctionType): Boolean {
        return false
    }

    /**
     * Replaces placeholders in the system prompt with actual values.
     * This is necessary because the AI might return placeholders like {{user}} or {{char}}.
     *
     * @param prompt The system prompt containing placeholders.
     * @param aiName The actual AI name to replace {{char}}.
     * @return The prompt with placeholders replaced.
     */
    private suspend fun replacePromptPlaceholders(prompt: String, aiName: String): String {
        var finalPrompt = prompt
        
        // 获取全局用户�?       val globalUserName = displayPreferencesManager.globalUserName.first() ?: "User"
        
        // 替换占位�?       finalPrompt = finalPrompt.replace("{{user}}", globalUserName)
        finalPrompt = finalPrompt.replace("{{char}}", aiName)
        
        return finalPrompt
    }

    /**
     * 翻译文本功能
     * @param text 要翻译的文本
     * @param multiServiceManager 多服务管理器
     * @return 翻译后的文本
     */
    suspend fun translateText(text: String, multiServiceManager: MultiServiceManager): String {
        val currentLanguage = LocaleUtils.getCurrentLanguage(context)
        
        // 根据当前语言确定目标语言
        val targetLanguage = when (currentLanguage) {
            "zh" -> context.getString(R.string.conversation_language_chinese)
            "en" -> "English"
            else -> context.getString(R.string.conversation_language_chinese) // 默认翻译为中�?       }
        
        val translationPrompt = """
${FunctionalPrompts.translationUserPrompt(targetLanguage, text)}
        """.trim()
        
        val chatHistory = listOf(
            PromptTurn(
                kind = PromptTurnKind.SYSTEM,
                content = FunctionalPrompts.translationSystemPrompt()
            )
        )
        
        val contentBuilder = StringBuilder()
        
        try {
            // 获取翻译功能的AIService实例
            val translationService = multiServiceManager.getServiceForFunction(FunctionType.TRANSLATION)
            
            // 获取模型参数
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.TRANSLATION)
            
            val stream = translationService.sendMessage(
                context = context,
                chatHistory = chatHistory + PromptTurn(kind = PromptTurnKind.USER, content = translationPrompt),
                modelParameters = modelParameters
            )
            
            stream.collect { content ->
                contentBuilder.append(content)
            }
            
            return contentBuilder.toString().trim()
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 自动生成工具包描�?    * @param pluginName 工具包名�?    * @param toolDescriptions 工具描述列表
     * @param multiServiceManager 多服务管理器
     * @return 生成的工具包描述
     */
    suspend fun generatePackageDescription(
        pluginName: String,
        toolDescriptions: List<String>,
        multiServiceManager: MultiServiceManager
    ): String {
        if (toolDescriptions.isEmpty()) {
            return ""
        }
        
        val toolList = toolDescriptions.joinToString("\n") { "- ${it}" }

        val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
        val descriptionPrompt =
            FunctionalPrompts.packageDescriptionUserPrompt(
                pluginName = pluginName,
                toolList = toolList,
                useEnglish = useEnglish
            )

        val chatHistory =
            listOf(
                PromptTurn(
                    kind = PromptTurnKind.SYSTEM,
                    content = FunctionalPrompts.packageDescriptionSystemPrompt(useEnglish)
                )
            )
        
        val contentBuilder = StringBuilder()
        
        try {
            // 获取总结功能的AIService实例
            val summaryService = multiServiceManager.getServiceForFunction(FunctionType.SUMMARY)
            
            // 获取模型参数
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.SUMMARY)
            
            val stream = summaryService.sendMessage(
                context = context,
                chatHistory = chatHistory + PromptTurn(kind = PromptTurnKind.USER, content = descriptionPrompt),
                modelParameters = modelParameters
            )
            
            stream.collect { content ->
                contentBuilder.append(content)
            }
            
            val result = ChatUtils.removeThinkingContent(contentBuilder.toString().trim())
            
            // 如果生成失败或内容为空，返回空字符串表示生成失败
            return if (result.isBlank()) {
                ""
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成工具包描述时出错", e)
            return ""
        }
    }

    /**
     * 使用识图模型分析图片
     * @param imagePath 图片路径
     * @param userIntent 用户意图，例，这个图片里面有什，，图片的题目公式是什，，     * @param multiServiceManager 多服务管理器
     * @return AI分析结果
     */
    suspend fun analyzeImageWithIntent(
        imagePath: String,
        userIntent: String?,
        multiServiceManager: MultiServiceManager
    ): String {
        return try {
            val service = multiServiceManager.getServiceForFunction(FunctionType.IMAGE_RECOGNITION)
            
            // 添加图片到池子并获取ID
            val imageId = com.apex.util.ImagePoolManager.addImage(imagePath)
            if (imageId == "error") {
                return "Failed to load image: ${imagePath}"
            }

            // 构建提示词，包含用户意图和图片链�?           val imageLink = MediaLinkBuilder.image(context, imageId)
            val prompt = if (userIntent.isNullOrBlank()) {
                "${imageLink}\n${context.getString(R.string.conversation_analyze_image_prompt)}"
            } else {
                "${imageLink}\n${userIntent}"
            }
            
            // 获取模型参数
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.IMAGE_RECOGNITION)
            
            // 调用AI服务分析图片
            val result = StringBuilder()
            service.sendMessage(
                context = context,
                chatHistory = listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                modelParameters = modelParameters
            ).collect { chunk ->
                result.append(chunk)
            }
            
            // 清理图片缓存
            com.apex.util.ImagePoolManager.removeImage(imageId)
            
            ChatUtils.removeThinkingContent(result.toString()).trim()
        } catch (e: Exception) {
            AppLogger.e(TAG, "识图分析失败", e)
            "Image recognition failed: ${e.message}"
        }
    }

    suspend fun analyzeAudioWithIntent(
        audioPath: String,
        userIntent: String?,
        multiServiceManager: MultiServiceManager
    ): String {
        return try {
            val service = multiServiceManager.getServiceForFunction(FunctionType.AUDIO_RECOGNITION)

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(java.io.File(audioPath).extension.lowercase())
                ?: "audio/*"

            val mediaId = com.apex.util.MediaPoolManager.addMedia(audioPath, mimeType)
            if (mediaId == "error") {
                return "Failed to load audio: ${audioPath}"
            }

            val audioLink = MediaLinkBuilder.audio(context, mediaId)
            val prompt = if (userIntent.isNullOrBlank()) {
                "${audioLink}\n${context.getString(R.string.conversation_analyze_audio_prompt)}"
            } else {
                "${audioLink}\n${userIntent}"
            }

            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.AUDIO_RECOGNITION)

            val result = StringBuilder()
            service.sendMessage(
                context = context,
                chatHistory = listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                modelParameters = modelParameters
            ).collect { chunk ->
                result.append(chunk)
            }

            com.apex.util.MediaPoolManager.removeMedia(mediaId)
            ChatUtils.removeThinkingContent(result.toString()).trim()
        } catch (e: Exception) {
            AppLogger.e(TAG, "音频识别失败", e)
            "Audio recognition failed: ${e.message}"
        }
    }

    suspend fun analyzeVideoWithIntent(
        videoPath: String,
        userIntent: String?,
        multiServiceManager: MultiServiceManager
    ): String {
        return try {
            val service = multiServiceManager.getServiceForFunction(FunctionType.VIDEO_RECOGNITION)

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(java.io.File(videoPath).extension.lowercase())
                ?: "video/*"

            val mediaId = com.apex.util.MediaPoolManager.addMedia(videoPath, mimeType)
            if (mediaId == "error") {
                return "Failed to load video: ${videoPath}"
            }

            val videoLink = MediaLinkBuilder.video(context, mediaId)
            val prompt = if (userIntent.isNullOrBlank()) {
                "${videoLink}\n${context.getString(R.string.conversation_analyze_video_prompt)}"
            } else {
                "${videoLink}\n${userIntent}"
            }

            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.VIDEO_RECOGNITION)

            val result = StringBuilder()
            service.sendMessage(
                context = context,
                chatHistory = listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                modelParameters = modelParameters
            ).collect { chunk ->
                result.append(chunk)
            }

            com.apex.util.MediaPoolManager.removeMedia(mediaId)
            ChatUtils.removeThinkingContent(result.toString()).trim()
        } catch (e: Exception) {
            AppLogger.e(TAG, "视频识别失败", e)
            "Video recognition failed: ${e.message}"
        }
    }
}
