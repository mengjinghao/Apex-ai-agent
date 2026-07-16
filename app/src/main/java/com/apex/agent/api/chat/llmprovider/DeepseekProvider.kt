package com.apex.api.chat.llmprovider

import android.content.Context
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.core.chat.hooks.mergeAdjacentTurns
import com.apex.util.AppLogger
import com.apex.data.model.ModelParameter
import com.apex.data.model.ToolPrompt
import com.apex.util.ChatUtils
import com.apex.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 针对 DeepSeek 模型的特�?API Provider
 * 继承�?OpenAIProvider，以重用大部分兼容逻辑，但特别处理�?`reasoning_content` 参数
 * 当启用推理模式时，会�?assistant 消息中的 <think> 标签内容提取出来作为 reasoning_content 字段
 *
 * 优化特性：
 * - R1 模型参数自动修正（temperature=1.0, top_p=0.95�? * - 增强�?reasoning_content 流式处理
 * - DeepSeek 特定错误处理（速率限制、安全审查、上下文溢出�? * - JSON Output 模式支持
 * - Tool Call �?reasoning_content 的交互优�? */
class DeepseekProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: com.apex.data.model.ApiProviderType = com.apex.data.model.ApiProviderType.DEEPSEEK,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
        apiEndpoint = apiEndpoint,
        apiKeyProvider = apiKeyProvider,
        modelName = modelName,
        client = client,
        customHeaders = customHeaders,
        providerType = providerType,
        supportsVision = supportsVision,
        supportsAudio = supportsAudio,
        supportsVideo = supportsVideo,
        enableToolCall = enableToolCall
    ) {

    // DeepSeek 模型类型检�?    private val isR1Model = modelName.contains("r1", ignoreCase = true) || 
                           modelName.contains("reasoner", ignoreCase = true)
    private val isV4Model = modelName.contains("v4", ignoreCase = true)
    private val isV3Model = modelName.contains("v3", ignoreCase = true) || 
                           modelName.contains("chat", ignoreCase = true)
    private val isCoderModel = modelName.contains("coder", ignoreCase = true)

    /**
     * 验证并修�?DeepSeek 特定的参�?     * R1 模型要求 temperature 固定�?1.0，top_p 建议�?0.95
     */
    private fun validateAndFixDeepSeekParameters(modelParameters: List<ModelParameter<*>>): List<ModelParameter<*>> {
        if (!isR1Model) return modelParameters
        
        return modelParameters.map { param ->
            when (param.apiName) {
                "temperature" -> {
                    // R1 模型强制 temperature = 1.0
                    if (param.currentValue != 1.0f) {
                        AppLogger.w("DeepseekProvider", "R1 模型要求 temperature 固定�?1.0，已自动修正")
                        @Suppress("UNCHECKED_CAST")
                        (param as ModelParameter<Float>).apply {
                            currentValue = 1.0f
                        }
                    }
                    param
                }
                "top_p" -> {
                    // R1 模型建议 top_p = 0.95
                    if (param.currentValue != 0.95f) {
                        AppLogger.w("DeepseekProvider", "R1 模型建议 top_p �?0.95，已自动修正")
                        @Suppress("UNCHECKED_CAST")
                        (param as ModelParameter<Float>).apply {
                            currentValue = 0.95f
                        }
                    }
                    param
                }
                else -> param
            }
        }
    }

    /**
     * 重写创建请求体的方法，以支持 DeepSeek �?`reasoning_content` 参数
     * 当启用推理模式时，需要特殊处理消息格�?     */
    override fun createRequestBody(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean
    ): RequestBody {
        // 验证并修�?DeepSeek 特定参数（R1 模型强制 temperature=1.0, top_p=0.95�?        val validatedParameters = validateAndFixDeepSeekParameters(modelParameters)

        fun applyThinkingParamsIfNeeded(jsonObject: JSONObject) {
            if (!enableThinking) return

            // DeepSeek Thinking Mode: thinking: { type: enabled }
            jsonObject.put(
                "thinking",
                JSONObject().apply {
                    put("type", "enabled")
                }
            )
        }

        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)

        // DeepSeek Thinking Mode
        applyThinkingParamsIfNeeded(jsonObject)

        // 添加已启用的模型参数（使用验证后的参数）
        for (param in validatedParameters) {
            if (param.isEnabled) {
                when (param.valueType) {
                    com.apex.data.model.ParameterValueType.INT ->
                        jsonObject.put(param.apiName, param.currentValue as Int)
                    com.apex.data.model.ParameterValueType.FLOAT ->
                        jsonObject.put(param.apiName, param.currentValue as Float)
                    com.apex.data.model.ParameterValueType.STRING ->
                        jsonObject.put(param.apiName, param.currentValue as String)
                    com.apex.data.model.ParameterValueType.BOOLEAN ->
                        jsonObject.put(param.apiName, param.currentValue as Boolean)
                    com.apex.data.model.ParameterValueType.OBJECT -> {
                        val raw = param.currentValue.toString().trim()
                        val parsed: Any? = try {
                            when {
                                raw.startsWith("{") -> JSONObject(raw)
                                raw.startsWith("[") -> JSONArray(raw)
                                else -> null
                            }
                        } catch (e: Exception) {
                            AppLogger.w("DeepseekProvider", "OBJECT参数解析失败: ${param.apiName}", e)
                            null
                        }
                        if (parsed != null) {
                            jsonObject.put(param.apiName, parsed)
                        } else {
                            jsonObject.put(param.apiName, raw)
                        }
                    }
                }
            }
        }

        // 当工具为空时，将 enableToolCall 视为 false
        val effectiveEnableToolCall = enableToolCall && availableTools != null && availableTools.isNotEmpty()

        // 如果启用 Tool Call 且传入了工具列表，添�?tools 定义
        var toolsJson: String? = null
        if (effectiveEnableToolCall) {
            val tools = buildToolDefinitions(availableTools!!)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                jsonObject.put("tool_choice", "auto")
                toolsJson = tools.toString()
            }
        }

        // 使用特殊的消息构建方法（支持 reasoning_content�?        val messagesArray = buildMessagesWithReasoning(
            context,
            chatHistory,
            effectiveEnableToolCall
        )
        jsonObject.put("messages", messagesArray)

        // 记录最终的请求体（省略过长�?tools 字段�?        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        val sanitizedLogJson = sanitizeImageDataForLogging(logJson)
        logLargeString("DeepseekProvider", sanitizedLogJson.toString(4), "Final DeepSeek reasoning mode request body: ")

        return createJsonRequestBody(jsonObject.toString())
    }

    /**
     * 构建支持 reasoning_content 的消息数�?     * 对于 assistant 角色的消息，提取 <think> 标签内容作为 reasoning_content
     */
    private fun buildMessagesWithReasoning(
        context: Context,
        chatHistory: List<PromptTurn>,
        useToolCall: Boolean
    ): JSONArray {
        val messagesArray = JSONArray()
        val effectiveHistory = chatHistory.mergeAdjacentTurns()

        var queuedAssistantToolText: String? = null
        var queuedAssistantReasoning: String? = null
        var queuedToolCalls = JSONArray()
        val queuedToolCallIds = mutableListOf<String>()
        val openToolCallIds = mutableListOf<String>()

        fun appendQueuedAssistantToolText(text: String) {
            if (text.isBlank()) return
            queuedAssistantToolText =
                if (queuedAssistantToolText.isNullOrBlank()) {
                    text
                } else {
                    queuedAssistantToolText + "\n" + text
                }
        }

        fun appendQueuedAssistantReasoning(reasoningContent: String) {
            if (reasoningContent.isBlank()) return
            queuedAssistantReasoning =
                if (queuedAssistantReasoning.isNullOrBlank()) {
                    reasoningContent
                } else {
                    queuedAssistantReasoning + "\n" + reasoningContent
                }
        }

        fun queueToolCalls(textContent: String, toolCalls: JSONArray, reasoningContent: String = "") {
            appendQueuedAssistantToolText(textContent)
            appendQueuedAssistantReasoning(reasoningContent)
            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(i) ?: continue
                queuedToolCalls.put(toolCall)
                val callId = toolCall.optString("id", "").trim()
                if (callId.isNotEmpty()) {
                    queuedToolCallIds.add(callId)
                }
            }
        }

        fun emitQueuedToolCallsIfNeeded() {
            if (queuedToolCalls.length() == 0) return

            messagesArray.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("reasoning_content", queuedAssistantReasoning.orEmpty())
                    if (!queuedAssistantToolText.isNullOrBlank()) {
                        put("content", buildContentField(context, queuedAssistantToolText!!))
                    } else {
                        put("content", null)
                    }
                    put("tool_calls", queuedToolCalls)
                }
            )

            openToolCallIds.addAll(queuedToolCallIds)
            queuedAssistantToolText = null
            queuedAssistantReasoning = null
            queuedToolCalls = JSONArray()
            queuedToolCallIds.clear()
        }

        fun flushOpenToolCallsAsCancelled(reason: String) {
            emitQueuedToolCallsIfNeeded()
            if (openToolCallIds.isEmpty()) return

            AppLogger.w(
                "DeepseekProvider",
                "发现未完成的 tool_calls，按取消处理: count=${openToolCallIds.size}, reason=${reason}"
            )
            for (toolCallId in openToolCallIds) {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolCallId)
                        put("content", "User cancelled")
                    }
                )
            }
            openToolCallIds.clear()
        }

        if (effectiveHistory.isNotEmpty()) {
            for (turn in effectiveHistory) {
                val originalContent = comparableContentForTurn(turn, preserveThinkInHistory = true)
                if (useToolCall) {
                    when (turn.kind) {
                        PromptTurnKind.SYSTEM -> {
                            flushOpenToolCallsAsCancelled("system_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.USER,
                        PromptTurnKind.SUMMARY -> {
                            flushOpenToolCallsAsCancelled("user_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.ASSISTANT -> {
                            val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)
                            val (textContent, parsedToolCalls) = parseXmlToolCalls(content)
                            val toolCalls =
                                if (parsedToolCalls != null) {
                                    wrapPackageToolCallsWithProxy(parsedToolCalls)
                                } else {
                                    null
                                }

                            if (toolCalls != null && toolCalls.length() > 0) {
                                if (openToolCallIds.isNotEmpty()) {
                                    flushOpenToolCallsAsCancelled("assistant_tool_call_before_result")
                                }
                                queueToolCalls(textContent, toolCalls, reasoningContent)
                            } else {
                                flushOpenToolCallsAsCancelled("assistant_boundary")
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("reasoning_content", reasoningContent)
                                        put("content", buildContentField(context, content.ifBlank { "[Empty]" }))
                                    }
                                )
                            }
                        }

                        PromptTurnKind.TOOL_CALL -> {
                            val (textContent, parsedToolCalls) = parseXmlToolCalls(originalContent)
                            val toolCalls =
                                if (parsedToolCalls != null) {
                                    wrapPackageToolCallsWithProxy(parsedToolCalls)
                                } else {
                                    null
                                }

                            if (toolCalls != null && toolCalls.length() > 0) {
                                if (openToolCallIds.isNotEmpty()) {
                                    flushOpenToolCallsAsCancelled("typed_tool_call_before_result")
                                }
                                queueToolCalls(textContent, toolCalls)
                            } else {
                                flushOpenToolCallsAsCancelled("typed_tool_call_without_payload")
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "assistant")
                                        put("reasoning_content", "")
                                        put("content", buildContentField(context, originalContent.ifBlank { "[Empty]" }))
                                    }
                                )
                            }
                        }

                        PromptTurnKind.TOOL_RESULT -> {
                            emitQueuedToolCallsIfNeeded()
                            val (textContent, toolResults) = parseXmlToolResults(originalContent)
                            val resultsList = toolResults ?: emptyList()

                            if (resultsList.isNotEmpty() && openToolCallIds.isNotEmpty()) {
                                val validCount = minOf(resultsList.size, openToolCallIds.size)
                                repeat(validCount) { index ->
                                    val (_, resultContent) = resultsList[index]
                                    messagesArray.put(
                                        JSONObject().apply {
                                            put("role", "tool")
                                            put("tool_call_id", openToolCallIds[index])
                                            put("content", resultContent)
                                        }
                                    )
                                }
                                repeat(validCount) {
                                    openToolCallIds.removeAt(0)
                                }

                                if (resultsList.size > validCount) {
                                    AppLogger.w(
                                        "DeepseekProvider",
                                        "发现多余�?tool_result: ${resultsList.size} results vs ${validCount} pending tool_calls"
                                    )
                                }

                                if (textContent.isNotEmpty()) {
                                    messagesArray.put(
                                        JSONObject().apply {
                                            put("role", "user")
                                            put("content", buildContentField(context, textContent))
                                        }
                                    )
                                }
                            } else {
                                flushOpenToolCallsAsCancelled("tool_result_without_structured_match")
                                val fallbackContent =
                                    when {
                                        textContent.isNotEmpty() -> textContent
                                        originalContent.isNotBlank() -> originalContent
                                        else -> "[Empty]"
                                    }
                                messagesArray.put(
                                    JSONObject().apply {
                                        put("role", "user")
                                        put("content", buildContentField(context, fallbackContent))
                                    }
                                )
                            }
                        }
                    }
                } else {
                    when (turn.kind) {
                        PromptTurnKind.SYSTEM -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.USER,
                        PromptTurnKind.SUMMARY,
                        PromptTurnKind.TOOL_RESULT -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", buildContentField(context, originalContent))
                                }
                            )
                        }

                        PromptTurnKind.ASSISTANT -> {
                            val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("reasoning_content", reasoningContent)
                                    put("content", buildContentField(context, content.ifBlank { "[Empty]" }))
                                }
                            )
                        }

                        PromptTurnKind.TOOL_CALL -> {
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("reasoning_content", "")
                                    put("content", buildContentField(context, originalContent.ifBlank { "[Empty]" }))
                                }
                            )
                        }
                    }
                }
            }
        }

        flushOpenToolCallsAsCancelled("history_end")
        return messagesArray
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean
    ): Stream<String> {
        // 直接调用父类�?sendMessage 实现
        // DeepSeek 特定的错误处理将在父类的统一重试策略中处�?        return super.sendMessage(context, chatHistory, modelParameters, enableThinking, stream, availableTools, preserveThinkInHistory, onTokensUpdated, onNonFatalError, enableRetry)
    }

    /**
     * DeepSeek 特定的连接测试方�?     * 针对 DeepSeek API 的特点进行优化测�?     */
    override suspend fun testConnection(context: Context): Result<String> {
        return try {
            // 使用简单的测试消息进行连接测试
            val testHistory = listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = "You are a helpful assistant."),
                PromptTurn(kind = PromptTurnKind.USER, content = "Hi")
            )
            
            val stream = sendMessage(
                context = context,
                chatHistory = testHistory,
                modelParameters = emptyList(),
                enableThinking = false, // 测试时不启用思考模式以加快响应
                stream = true,
                availableTools = null,
                preserveThinkInHistory = false,
                onTokensUpdated = { _, _, _ -> },
                onNonFatalError = {},
                enableRetry = false
            )

            stream.collect { _ -> }
            Result.success("DeepSeek 连接测试成功")
        } catch (e: Exception) {
            AppLogger.e("DeepseekProvider", "连接测试失败", e)
            Result.failure(java.io.IOException("DeepSeek 连接测试失败: ${e.message ?: "未知错误"}", e))
        }
    }

    /**
     * 获取 DeepSeek 模型的特定信�?     */
    fun getModelInfo(): String {
        return buildString {
            append("DeepSeek Model: ${modelName}\n")
            append("Type: ")
            when {
                isR1Model -> append("R1 (Reasoning)")
                isV3Model -> append("V3 (Chat)")
                isCoderModel -> append("Coder")
                else -> append("Unknown")
            }
            append("\n")
            if (isR1Model) {
                append("Note: R1 models require temperature=1.0, top_p=0.95\n")
            }
        }
    }
}
