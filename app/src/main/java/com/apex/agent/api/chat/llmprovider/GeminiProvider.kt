package com.apex.api.chat.llmprovider

import com.apex.util.AppLogger
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.core.chat.hooks.mergeAdjacentTurns
import com.apex.core.chat.hooks.toPromptTurns
import com.apex.data.model.ApiProviderType
import com.apex.data.model.ModelOption
import com.apex.data.model.ModelParameter
import com.apex.data.model.ToolPrompt
import com.apex.data.model.ParameterCategory
import com.apex.util.ChatUtils
import com.apex.util.ChatMarkupRegex
import com.apex.util.StreamingJsonXmlConverter
import com.apex.util.TokenCacheManager
import com.apex.util.exceptions.UserCancellationException
import com.apex.util.stream.MutableSharedStream
import com.apex.util.stream.Stream
import com.apex.util.stream.StreamCollector
import com.apex.util.stream.TextStreamEvent
import com.apex.util.stream.TextStreamEventType
import com.apex.util.stream.withEventChannel
import com.apex.util.stream.stream
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import com.apex.agent.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.apex.api.chat.llmprovider.MediaLinkParser

/** Google Gemini API的实例支持标准Gemini接口流式传输 */
class GeminiProvider(
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    private val modelName: String,
    private val client: OkHttpClient,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val providerType: ApiProviderType = ApiProviderType.GOOGLE,
    private val enableGoogleSearch: Boolean = false,
    private val enableToolCall: Boolean = false // 是否启用Tool Call接口（预留，Gemini有原生tool支持�? : AIService {
    companion object {
        private const val TAG = "GeminiProvider"
        private const val DEBUG = true // 开启调试日�?   }

    // HTTP客户�?   // private val client: OkHttpClient = HttpClientFactory.instance

    private val JSON = "application/json".toMediaType()

    // 活跃请求，用于取消流式请�?   private var activeCall: Call? = null
    private var activeResponse: Response? = null
    @Volatile private var isManuallyCancelled = false

    /**
     * 由客户端错误（如4xx状态码）触发的API异常，是否重试由统一策略决定
     */
    class NonRetriableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // Token计数
    private val tokenCacheManager = TokenCacheManager()
    
    // 思考状态跟�?   private var isInThinkingMode = false

    override val inputTokenCount: Int
        get() = tokenCacheManager.totalInputTokenCount
    override val cachedInputTokenCount: Int
        get() = tokenCacheManager.cachedInputTokenCount
    override val outputTokenCount: Int
        get() = tokenCacheManager.outputTokenCount

    // 供应，模型标识，    override val providerModel: String
        get() = "${providerType.name}:${modelName}"

    // 取消当前流式传输
    override fun cancelStreaming() {
        isManuallyCancelled = true

        // 1. 强制关闭 Response（这会立即中断流读取操作�?       activeResponse?.let {
            try {
                it.close()
                AppLogger.d(TAG, "已强制关闭Response�?
            } catch (e: Exception) {
                AppLogger.w(TAG, "关闭Response时出�?${e.message}")
            }
        }
        activeResponse = null

        // 2. 取消 Call
        activeCall?.let {
            if (!it.isCanceled()) {
                it.cancel()
                AppLogger.d(TAG, "已取消当前流式传输，Call已中�?
            }
        }
        activeCall = null

        AppLogger.d(TAG, "取消标志已设置，流读取将立即被中�?
    }

    // 重置Token计数
    override fun resetTokenCounts() {
        tokenCacheManager.resetTokenCounts()
        isInThinkingMode = false
    }

    override suspend fun calculateInputTokens(
            chatHistory: List<PromptTurn>,
            availableTools: List<ToolPrompt>?
    ): Int {
        // 构建工具定义的JSON字符�?       val toolsJson = buildToolsJson(availableTools)
        val comparableHistory =
            ChatUtils.stripGeminiThoughtSignatureMeta(
                chatHistory.map { turn ->
                    val comparableRole =
                        when (turn.kind) {
                            PromptTurnKind.SYSTEM -> "system"
                            PromptTurnKind.USER -> "user"
                            PromptTurnKind.ASSISTANT -> "assistant"
                            PromptTurnKind.TOOL_CALL -> "tool_call"
                            PromptTurnKind.TOOL_RESULT -> "tool_result"
                            PromptTurnKind.SUMMARY -> "summary"
                        }
                    val comparableContent =
                        if (turn.kind == PromptTurnKind.ASSISTANT) {
                            ChatUtils.removeThinkingContent(turn.content)
                        } else {
                            turn.content
                        }
                    comparableRole to comparableContent
                }
            )
        return tokenCacheManager.calculateInputTokens(
            comparableHistory,
            toolsJson,
            updateState = false
        )
    }
    
    /**
     * 构建工具定义的JSON字符串，用于token计算
     */
    private fun buildToolsJson(availableTools: List<ToolPrompt>): String? {
        if (!enableToolCall || availableTools == null || availableTools.isEmpty()) {
            return if (enableGoogleSearch) {
                // 只有 Google Search
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                }.toString()
            } else {
                null
            }
        }
        
        val tools = JSONArray()
        
        // 添加 Function Calling 工具
        val functionDeclarations = buildToolDefinitionsForGemini(availableTools)
        if (functionDeclarations.length() > 0) {
            tools.put(JSONObject().apply {
                put("function_declarations", functionDeclarations)
            })
        }
        
        // 添加 Google Search grounding 工具（如果启用）
        if (enableGoogleSearch) {
            tools.put(JSONObject().apply {
                put("googleSearch", JSONObject())
            })
        }
        
        return if (tools.length() > 0) tools.toString() else null
    }

    // ==================== Tool Call 支持 ====================
    
    /**
     * XML转义/反转义工�?    */
    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;")
        }
        
        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&")
        }
    }

    private data class GeminiThoughtSignaturePayload(
        val contentWithoutMeta: String,
        val thoughtSignature: String?
    )

    private data class GeminiFunctionCallPayload(
        val textContent: String,
        val functionCalls: List<JSONObject>,
        val thoughtSignature: String?
    )

    private fun encodeGeminiThoughtSignature(signature: String): String {
        return Base64.encodeToString(signature.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeGeminiThoughtSignature(signatureBase64: String): String? {
        return try {
            String(Base64.decode(signatureBase64, Base64.DEFAULT), Charsets.UTF_8)
                .takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            logDebug("Gemini thoughtSignature meta base64 无法解码，已忽略")
            null
        }
    }

    private fun extractGeminiThoughtSignaturePayload(content: String): GeminiThoughtSignaturePayload {
        val signatureBase64 = ChatMarkupRegex.extractGeminiThoughtSignature(content)
        val contentWithoutMeta = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content)
        val thoughtSignature = signatureBase64?.let { decodeGeminiThoughtSignature(it) }
        return GeminiThoughtSignaturePayload(
            contentWithoutMeta = contentWithoutMeta,
            thoughtSignature = thoughtSignature
        )
    }

    private fun appendGeminiThoughtSignatureMeta(
        contentBuilder: StringBuilder,
        thoughtSignature: String
    ) {
        if (contentBuilder.isNotEmpty() && contentBuilder[contentBuilder.length - 1] != '\n') {
            contentBuilder.append('\n')
        }
        contentBuilder.append(
            ChatMarkupRegex.geminiThoughtSignatureMetaTag(
                encodeGeminiThoughtSignature(thoughtSignature)
            )
        )
    }

    private fun JSONObject.optGeminiThoughtSignature(): String? {
        val camelCase = optString("thoughtSignature", "").trim()
        if (camelCase.isNotEmpty()) {
            return camelCase
        }
        val snakeCase = optString("thought_signature", "").trim()
        if (snakeCase.isNotEmpty()) {
            return snakeCase
        }
        return null
    }
    
    /**
     * 解析XML格式的tool调用，转换为Gemini FunctionCall格式
     * @return 文本内容、functionCall对象列表、以及挂在Part级别的thought signature
     */
    private fun parseXmlToolCalls(content: String): GeminiFunctionCallPayload {
        if (!enableToolCall) {
            return GeminiFunctionCallPayload(
                textContent = content,
                functionCalls = emptyList(),
                thoughtSignature = null
            )
        }

        val thoughtSignaturePayload = extractGeminiThoughtSignaturePayload(content)
        val sanitizedContent = thoughtSignaturePayload.contentWithoutMeta
        val matches = ChatMarkupRegex.toolCallPattern.findAll(sanitizedContent).toList()
        
        if (matches.isEmpty()) {
            return GeminiFunctionCallPayload(
                textContent = sanitizedContent,
                functionCalls = emptyList(),
                thoughtSignature = null
            )
        }

        val functionCalls =
            matches.map { match ->
                val toolName = match.groupValues[2]
                val toolBody = match.groupValues[3]

                val args = JSONObject()
                ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                    val paramName = paramMatch.groupValues[1]
                    val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                    args.put(paramName, paramValue)
                }

                AppLogger.d(TAG, "XML→GeminiFunctionCall: ${toolName}")
                JSONObject().apply {
                    put("name", toolName)
                    put("args", args)
                }
            }

        var textContent = sanitizedContent
        matches.forEach { match ->
            textContent = textContent.replace(match.value, "").trim()
        }
        
        return GeminiFunctionCallPayload(
            textContent = textContent,
            functionCalls = functionCalls,
            thoughtSignature = thoughtSignaturePayload.thoughtSignature
        )
    }
    
    /**
     * 解析XML格式的tool_result，转换为Gemini FunctionResponse格式
     * @return Pair<文本内容, functionResponse对象列表>
     */
    private fun parseXmlToolResults(content: String): Pair<String, List<JSONObject>?> {
        if (!enableToolCall) return Pair(content, null)
        
        val matches = ChatMarkupRegex.toolResultWithNameAnyPattern.findAll(content)
        
        if (!matches.any()) {
            return Pair(content, null)
        }
        
        val functionResponses = mutableListOf<JSONObject>()
        var textContent = content
        
        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val fullContent = match.groupValues[3].trim()
            val contentMatch = ChatMarkupRegex.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            
            // 构建functionResponse对象（Gemini格式�?           val functionResponse = JSONObject().apply {
                put("name", toolName)
                put("response", JSONObject().apply {
                    put("result", resultContent)
                })
            }
            
            functionResponses.add(functionResponse)
            AppLogger.d(TAG, "解析Gemini functionResponse: ${toolName}, content length=${resultContent.length}")
            
            textContent = textContent.replace(match.value, "").trim()
        }
        
        return Pair(textContent, functionResponses)
    }
    
    /**
     * 从ToolPrompt列表构建Gemini格式的Function Declarations
     */
    private fun buildToolDefinitionsForGemini(toolPrompts: List<ToolPrompt>): JSONArray {
        val functionDeclarations = JSONArray()
        
        for (tool in toolPrompts) {
            functionDeclarations.put(JSONObject().apply {
                put("name", tool.name)
                // 组合description和details作为完整描述
                val fullDescription = if (tool.details.isNotEmpty()) {
                    "${tool.description}\n${tool.details}"
                } else {
                    tool.description
                }
                put("description", fullDescription)
                
                // 使用结构化参数构建schema
                val parametersSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                put("parameters", parametersSchema)
            })
        }
        
        return functionDeclarations
    }
    
    /**
     * 从结构化参数构建JSON Schema（Gemini格式�?    */
    private fun buildSchemaFromStructured(params: List<com.apex.data.model.ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }
        
        val properties = JSONObject()
        val required = JSONArray()
        
        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })
            
            if (param.required) {
                required.put(param.name)
            }
        }
        
        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }
        
        return schema
    }
    
    /**
     * 构建包含文本和图片的parts数组
     */
    private fun buildPartsArray(text: String): JSONArray {
        val partsArray = JSONArray()

        val hasImages = MediaLinkParser.hasImageLinks(text)
        val hasMedia = MediaLinkParser.hasMediaLinks(text)

        if (hasImages || hasMedia) {
            val imageLinks = if (hasImages) MediaLinkParser.extractImageLinks(text) else emptyList()
            val mediaLinks = if (hasMedia) MediaLinkParser.extractMediaLinks(text) else emptyList()

            var textWithoutLinks = text
            if (hasImages) {
                textWithoutLinks = MediaLinkParser.removeImageLinks(textWithoutLinks)
            }
            if (hasMedia) {
                textWithoutLinks = MediaLinkParser.removeMediaLinks(textWithoutLinks)
            }
            textWithoutLinks = textWithoutLinks.trim()

            // 添加媒体（音，视频，            mediaLinks.forEach { link ->
                partsArray.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", link.mimeType)
                        put("data", link.base64Data)
                    })
                })
            }

            // 添加图片
            imageLinks.forEach { link ->
                partsArray.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", link.mimeType)
                        put("data", link.base64Data)
                    })
                })
            }

            // 添加文本（如果有�?           if (textWithoutLinks.isNotEmpty()) {
                partsArray.put(JSONObject().apply {
                    put("text", textWithoutLinks)
                })
            }
        } else {
            // 纯文本消�?           partsArray.put(JSONObject().apply {
                put("text", text)
            })
        }
        
        return partsArray
    }

    private fun buildContentsAndCountTokens(
            chatHistory: List<PromptTurn>,
            toolsJson: String? = null,
            preserveThinkInHistory: Boolean = false
    ): Pair<Pair<JSONArray, JSONObject?>, Int> {
        val contentsArray = JSONArray()
        var systemInstruction: JSONObject? = null

        // 使用TokenCacheManager计算token数量
        val sanitizedHistoryForTokenCount =
            ChatUtils.stripGeminiThoughtSignatureMeta(
                chatHistory.map { turn ->
                    val comparableRole =
                        when (turn.kind) {
                            PromptTurnKind.SYSTEM -> "system"
                            PromptTurnKind.USER -> "user"
                            PromptTurnKind.ASSISTANT -> "assistant"
                            PromptTurnKind.TOOL_CALL -> "tool_call"
                            PromptTurnKind.TOOL_RESULT -> "tool_result"
                            PromptTurnKind.SUMMARY -> "summary"
                        }
                    val comparableContent =
                        if (!preserveThinkInHistory && turn.kind == PromptTurnKind.ASSISTANT) {
                            ChatUtils.removeThinkingContent(turn.content)
                        } else {
                            turn.content
                        }
                    comparableRole to comparableContent
                }
            )
        val tokenCount = tokenCacheManager.calculateInputTokens(
            sanitizedHistoryForTokenCount,
            toolsJson
        )

        val effectiveHistory = chatHistory.mergeAdjacentTurns()

        // Find and process system message first
        val systemMessages = effectiveHistory.filter { it.kind == PromptTurnKind.SYSTEM }
        if (systemMessages.isNotEmpty()) {
            val systemContent = systemMessages.joinToString("\n\n") { it.content }
            logDebug("发现系统消息: ${systemContent.take(50)}...")

            systemInstruction = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemContent) })
                })
            }
        }

        // Process the rest of the history
        val historyWithoutSystem = effectiveHistory.filter { it.kind != PromptTurnKind.SYSTEM }
        var queuedAssistantToolText: String? = null
        var queuedAssistantThoughtSignature: String? = null
        val queuedFunctionCalls = mutableListOf<JSONObject>()
        val openFunctionCallNames = mutableListOf<String>()

        fun appendParts(target: JSONArray, parts: JSONArray) {
            for (index in 0 until parts.length()) {
                target.put(parts.get(index))
            }
        }

        fun appendQueuedAssistantToolText(text: String) {
            if (text.isBlank()) return
            queuedAssistantToolText =
                if (queuedAssistantToolText.isNullOrBlank()) {
                    text
                } else {
                    queuedAssistantToolText + "\n" + text
                }
        }

        fun queueFunctionCalls(
            textContent: String,
            functionCalls: List<JSONObject>,
            thoughtSignature: String? = null
        ) {
            appendQueuedAssistantToolText(textContent)
            if (queuedAssistantThoughtSignature == null && !thoughtSignature.isNullOrBlank()) {
                queuedAssistantThoughtSignature = thoughtSignature
            }
            queuedFunctionCalls.addAll(functionCalls)
        }

        fun emitQueuedFunctionCallsIfNeeded() {
            if (queuedFunctionCalls.isEmpty()) return

            val partsArray = JSONArray()
            if (!queuedAssistantToolText.isNullOrBlank()) {
                appendParts(partsArray, buildPartsArray(queuedAssistantToolText!!))
            }
            queuedFunctionCalls.forEachIndexed { index, functionCall ->
                partsArray.put(
                    JSONObject().apply {
                        put("functionCall", functionCall)
                        if (index == 0) {
                            queuedAssistantThoughtSignature?.let { signature ->
                                put("thought_signature", signature)
                            }
                        }
                    }
                )
            }

            contentsArray.put(
                JSONObject().apply {
                    put("role", "model")
                    put("parts", partsArray)
                }
            )

            queuedFunctionCalls.forEach { functionCall ->
                openFunctionCallNames.add(functionCall.optString("name", "").trim())
            }
            queuedAssistantToolText = null
            queuedAssistantThoughtSignature = null
            queuedFunctionCalls.clear()
        }

        fun appendCancelledOpenFunctionResponses(target: JSONArray, reason: String): Boolean {
            emitQueuedFunctionCallsIfNeeded()
            if (openFunctionCallNames.isEmpty()) return false

            logDebug("发现未完成的Gemini functionCall，按取消处理: count=${openFunctionCallNames.size}, reason=${reason}")
            openFunctionCallNames.forEach { functionName ->
                target.put(
                    JSONObject().apply {
                        put(
                            "functionResponse",
                            JSONObject().apply {
                                put("name", functionName.ifBlank { "cancelled_function" })
                                put(
                                    "response",
                                    JSONObject().apply {
                                        put("result", "User cancelled")
                                    }
                                )
                            }
                        )
                    }
                )
            }
            openFunctionCallNames.clear()
            return true
        }

        fun flushOpenFunctionCallsAsCancelled(reason: String) {
            val partsArray = JSONArray()
            if (!appendCancelledOpenFunctionResponses(partsArray, reason)) return
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", partsArray)
                }
            )
        }

        for (turn in historyWithoutSystem) {
            val content =
                if (!preserveThinkInHistory && turn.kind == PromptTurnKind.ASSISTANT) {
                    ChatUtils.removeThinkingContent(turn.content)
                } else {
                    turn.content
                }
            val contentWithoutGeminiMeta = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content)

            if (enableToolCall) {
                when (turn.kind) {
                    PromptTurnKind.ASSISTANT -> {
                        val functionCallPayload = parseXmlToolCalls(content)
                        if (functionCallPayload.functionCalls.isNotEmpty()) {
                            if (openFunctionCallNames.isNotEmpty()) {
                                flushOpenFunctionCallsAsCancelled("assistant_function_call_before_result")
                            }
                            queueFunctionCalls(
                                functionCallPayload.textContent,
                                functionCallPayload.functionCalls,
                                functionCallPayload.thoughtSignature
                            )
                        } else {
                            flushOpenFunctionCallsAsCancelled("assistant_boundary")
                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "model")
                                    put("parts", buildPartsArray(contentWithoutGeminiMeta))
                                }
                            )
                        }
                    }

                    PromptTurnKind.TOOL_CALL -> {
                        val functionCallPayload = parseXmlToolCalls(content)
                        if (functionCallPayload.functionCalls.isNotEmpty()) {
                            if (openFunctionCallNames.isNotEmpty()) {
                                flushOpenFunctionCallsAsCancelled("typed_function_call_before_result")
                            }
                            queueFunctionCalls(
                                functionCallPayload.textContent,
                                functionCallPayload.functionCalls,
                                functionCallPayload.thoughtSignature
                            )
                        } else {
                            flushOpenFunctionCallsAsCancelled("typed_tool_call_without_payload")
                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "model")
                                    put("parts", buildPartsArray(contentWithoutGeminiMeta))
                                }
                            )
                        }
                    }

                    PromptTurnKind.USER,
                    PromptTurnKind.SUMMARY -> {
                        val partsArray = JSONArray()
                        appendCancelledOpenFunctionResponses(partsArray, "user_boundary")
                        appendParts(partsArray, buildPartsArray(contentWithoutGeminiMeta))
                        contentsArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", partsArray)
                            }
                        )
                    }

                    PromptTurnKind.TOOL_RESULT -> {
                        emitQueuedFunctionCallsIfNeeded()
                        val (textContent, functionResponses) = parseXmlToolResults(contentWithoutGeminiMeta)
                        val responsesList = functionResponses ?: emptyList()

                        if (responsesList.isNotEmpty() && openFunctionCallNames.isNotEmpty()) {
                            val partsArray = JSONArray()
                            val validCount = minOf(responsesList.size, openFunctionCallNames.size)

                            repeat(validCount) { index ->
                                val response = JSONObject(responsesList[index].toString())
                                val pendingName = openFunctionCallNames[index]
                                if (pendingName.isNotBlank()) {
                                    response.put("name", pendingName)
                                }
                                partsArray.put(
                                    JSONObject().apply {
                                        put("functionResponse", response)
                                    }
                                )
                                logDebug("历史XML→GeminiFunctionResponse: ${response.optString("name")}")
                            }

                            repeat(validCount) {
                                openFunctionCallNames.removeAt(0)
                            }

                            if (responsesList.size > validCount) {
                                logDebug("发现多余的Gemini functionResponse: ${responsesList.size} results vs ${validCount} pending functionCalls")
                            }

                            if (textContent.isNotEmpty()) {
                                appendParts(partsArray, buildPartsArray(textContent))
                            }

                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("parts", partsArray)
                                }
                            )
                        } else {
                            val partsArray = JSONArray()
                            appendCancelledOpenFunctionResponses(partsArray, "tool_result_without_structured_match")
                            val fallbackContent =
                                when {
                                    textContent.isNotEmpty() -> textContent
                                    contentWithoutGeminiMeta.isNotBlank() -> contentWithoutGeminiMeta
                                    else -> "[Empty]"
                                }
                            appendParts(partsArray, buildPartsArray(fallbackContent))
                            contentsArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("parts", partsArray)
                                }
                            )
                        }
                    }

                    PromptTurnKind.SYSTEM -> Unit
                }
            } else {
                val geminiRole =
                    when (turn.kind) {
                        PromptTurnKind.ASSISTANT,
                        PromptTurnKind.TOOL_CALL -> "model"
                        else -> "user"
                    }
                contentsArray.put(
                    JSONObject().apply {
                        put("role", geminiRole)
                        put("parts", buildPartsArray(contentWithoutGeminiMeta))
                    }
                )
            }
        }

        flushOpenFunctionCallsAsCancelled("history_end")

        return Pair(Pair(contentsArray, systemInstruction), tokenCount)
    }

    // 工具函数：分块打印大型文本日�?   private fun logLargeString(tag: String, message: String, prefix: String = "") {
        // 设置单次日志输出的最大长度（Android日志上限约为4000字符�?       val maxLogSize = 3000

        // 如果消息长度超过限制，分块打开        if (message.length > maxLogSize) {
            // 计算需要分多少块打开            val chunkCount = message.length / maxLogSize + 1

            for (i in 0 until chunkCount) {
                val start = i * maxLogSize
                val end = minOf((i + 1) * maxLogSize, message.length)
                val chunkMessage = message.substring(start, end)

                // 打印带有编号的日�?               AppLogger.d(tag, "${prefix} Part ${i+1}/${chunkCount}: ${chunkMessage}")
            }
        } else {
            // 消息长度在限制之内，直接打印
            AppLogger.d(tag, "${prefix}${message}")
        }
    }

    private fun sanitizeImageDataForLogging(json: JSONObject): JSONObject {
        fun sanitizeObject(obj: JSONObject) {
            fun sanitizeArray(arr: JSONArray) {
                for (i in 0 until arr.length()) {
                    val value = arr.get(i)
                    when (value) {
                        is JSONObject -> sanitizeObject(value)
                        is JSONArray -> sanitizeArray(value)
                        is String -> {
                            if (value.startsWith("data:") && value.contains(";base64,")) {
                                arr.put(i, "[image base64 omitted, length=${value.length}]")
                            }
                        }
                    }
                }
            }

            val maybeMimeType = obj.optString("mime_type", obj.optString("mimeType", ""))
            if (maybeMimeType.startsWith("image/", ignoreCase = true) && obj.has("data")) {
                val dataValue = obj.opt("data")
                if (dataValue is String) {
                    obj.put("data", "[image base64 omitted, length=${dataValue.length}]")
                }
            }

            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.get(key)
                when (value) {
                    is JSONObject -> sanitizeObject(value)
                    is JSONArray -> sanitizeArray(value)
                    is String -> {
                        if (value.startsWith("data:") && value.contains(";base64,")) {
                            obj.put(key, "[image base64 omitted, length=${value.length}]")
                        }
                    }
                }
            }
        }

        sanitizeObject(json)
        return json
    }

     private fun getOutputImagesDir(): File {
         val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
         return File(downloadsDir, "Apex/output images")
     }

     private fun fileExtensionForImageMime(mimeType: String): String {
         return when (mimeType.lowercase().substringBefore(';')) {
             "image/png" -> "png"
             "image/jpeg", "image/jpg" -> "jpg"
             "image/webp" -> "webp"
             "image/gif" -> "gif"
             else -> "png"
         }
     }

     private fun writeOutputImage(bytes: ByteArray, mimeType: String, prefix: String): Uri? {
         return try {
             val dir = getOutputImagesDir()
             if (!dir.exists()) {
                 dir.mkdirs()
             }
             val ext = fileExtensionForImageMime(mimeType)
             val fileName = "${prefix}_${System.currentTimeMillis()}.${ext}"
             val outFile = File(dir, fileName)
             FileOutputStream(outFile).use { it.write(bytes) }
             Uri.fromFile(outFile)
         } catch (e: Exception) {
             logError("保存输出图片失败", e)
             null
         }
     }

    // 日志辅助方法
    private fun logDebug(message: String) {
        if (DEBUG) {
            AppLogger.d(TAG, message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            AppLogger.e(TAG, message, throwable)
        } else {
            AppLogger.e(TAG, message)
        }
    }

    private fun buildGeminiErrorDetail(error: JSONObject, fallback: String): String {
        val message = error.optString("message", "").trim().ifEmpty { fallback }
        val status = error.opt("status")?.toString()?.trim().orEmpty()
        val code = error.opt("code")?.toString()?.trim().orEmpty()

        if (status.isEmpty() && code.isEmpty()) {
            return message
        }

        return buildString {
            append(message)
            append(" [")
            if (status.isNotEmpty()) {
                append("status=").append(status)
            }
            if (status.isNotEmpty() && code.isNotEmpty()) {
                append(", ")
            }
            if (code.isNotEmpty()) {
                append("code=").append(code)
            }
            append("]")
        }
    }

    private fun throwIfGeminiErrorPayload(context: Context, json: JSONObject) {
        val error = json.optJSONObject("error") ?: return
        val detail = buildGeminiErrorDetail(error, context.getString(R.string.gemini_unknown_error))
        val exceptionMessage = context.getString(R.string.gemini_error_response_failed, detail)

        logError("API返回错误: ${detail}")
        throw IOException(exceptionMessage)
    }

    private fun resolveRetryErrorText(context: Context, exception: Exception): String {
        return when (exception) {
            is SocketTimeoutException -> context.getString(R.string.provider_error_timeout)
            is UnknownHostException -> context.getString(R.string.provider_error_unknown_host)
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.provider_error_network_interrupted)
        }
    }

    private suspend fun handleRetryableError(
        context: Context,
        exception: Exception,
        retryCount: Int,
        maxRetries: Int,
        enableRetry: Boolean,
        onNonFatalError: suspend (String) -> Unit,
        buildRetryMessage: (String, Int) -> String
    ): Int {
        if (exception is UserCancellationException || exception is kotlinx.coroutines.CancellationException) {
            throw exception
        }
        if (isManuallyCancelled) {
            logError("请求被用户取消，停止重试着�?exception)
            throw UserCancellationException(context.getString(R.string.gemini_error_request_cancelled), exception)
        }

        val errorText = resolveRetryErrorText(context, exception)

        if (!enableRetry) {
            throw IOException(errorText, exception)
        }

        val newRetryCount = retryCount + 1
        if (newRetryCount > maxRetries) {
            logError("${errorText} 且达到最大重试次，的${maxRetries})", exception)
            throw IOException(
                context.getString(R.string.gemini_error_connection_timeout, maxRetries, errorText),
                exception
            )
        }

        val retryDelayMs = LlmRetryPolicy.nextDelayMs(newRetryCount)
        AppLogger.w(TAG, "${errorText}${retryDelayMs}ms 后进行第 ${newRetryCount} 次重�?.", exception)
        onNonFatalError(buildRetryMessage(errorText, newRetryCount))
        delay(retryDelayMs)
        return newRetryCount
    }

    /** 发送消息到Gemini API */
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
        val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val responseStream = stream {
        isManuallyCancelled = false
        val requestId = System.currentTimeMillis().toString()
        // 重置输出token计数（保留输入历史缓存）
        tokenCacheManager.addOutputTokens(-tokenCacheManager.outputTokenCount)
        isInThinkingMode = false
        
        onTokensUpdated(
                tokenCacheManager.totalInputTokenCount,
                tokenCacheManager.cachedInputTokenCount,
                tokenCacheManager.outputTokenCount
        )

        AppLogger.d(TAG, "发送消息到Gemini API, 模型: ${modelName}")

        val maxRetries = LlmRetryPolicy.MAX_RETRY_ATTEMPTS
        var retryCount = 0
        var lastException: Exception? = null

        // 用于保存已接收到的内容，以便在重试时使用
        val receivedContent = StringBuilder()
        val requestSavepointId = "attempt_${UUID.randomUUID().toString().replace("-", "")}"

        suspend fun emitSavepoint(id: String) {
            eventChannel.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, id))
        }

        suspend fun emitRollback(id: String) {
            if (receivedContent.isNotEmpty()) {
                receivedContent.setLength(0)
            }
            eventChannel.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, id))
        }

        // 捕获stream collector的引�?       val streamCollector = this

        // 状态更新函�? 在Stream中我们使用emit来传递连接状�?       val emitConnectionStatus: (String) -> Unit = { status ->
            // 这里可以根据需要处理连接状态，例如记录日志
            logDebug("连接状�?${status}")
        }

        emitConnectionStatus(context.getString(R.string.gemini_connecting))
        emitSavepoint(requestSavepointId)

        while (retryCount <= maxRetries) {
            // 在循环开始时检查是否已被取�?           if (isManuallyCancelled) {
                logError("请求被用户取消，停止重试着�?
                throw UserCancellationException(context.getString(R.string.gemini_error_request_cancelled))
            }
            
            try {
                if (retryCount > 0) {
                    AppLogger.d(
                        TAG,
                        "【Gemini 重试】原子回滚后重新请求，本轮已撤回内容长度: ${receivedContent.length}"
                    )
                }

                val requestBody = createRequestBody(context, chatHistory, modelParameters, enableThinking, availableTools, preserveThinkInHistory)
                onTokensUpdated(
                        tokenCacheManager.totalInputTokenCount,
                        tokenCacheManager.cachedInputTokenCount,
                        tokenCacheManager.outputTokenCount
                )
                val request = createRequest(context, requestBody, stream, requestId) // 根据stream参数决定使用流式还是非流�?
                val call = client.newCall(request)
                activeCall = call

                emitConnectionStatus(context.getString(R.string.gemini_connecting))

                val startTime = System.currentTimeMillis()
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val response = call.execute()
                    activeResponse = response
                    try {
                        val duration = System.currentTimeMillis() - startTime
                        AppLogger.d(TAG, "收到初始响应, 耗时: ${duration}ms, 状态码: ${response.code}")

                        emitConnectionStatus(context.getString(R.string.gemini_connected_success))

                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: context.getString(R.string.gemini_error_no_error_details)
                            logError("API请求失败: ${response.code}, ${errorBody}")
                            // 4xx错误仍保留单独的异常类型，具体是否重试由统一策略决定
                            if (response.code in 400..499) {
                                throw NonRetriableException(context.getString(R.string.gemini_error_api_request_failed, response.code, errorBody))
                            }
                            // 对于5xx等服务端错误，允许重�?                           throw IOException(context.getString(R.string.gemini_error_api_request_failed, response.code, errorBody))
                        }

                        // 根据stream参数处理响应
                        if (stream) {
                            // 处理流式响应
                            processStreamingResponse(context, response, streamCollector, requestId, onTokensUpdated, receivedContent)
                        } else {
                            // 处理非流式响应并转换为Stream
                            processNonStreamingResponse(context, response, streamCollector, requestId, onTokensUpdated, receivedContent)
                        }
                    } finally {
                        response.close()
                        AppLogger.d(TAG, "关闭响应连接")
                    }
                }

                // 清理活跃引用
                activeCall = null
                activeResponse = null
                return@stream
            } catch (e: Exception) {
                lastException = e
                emitRollback(requestSavepointId)
                retryCount = handleRetryableError(
                    context,
                    e,
                    retryCount,
                    maxRetries,
                    enableRetry,
                    onNonFatalError
                ) { errorText, retryNumber ->
                    context.getString(R.string.provider_error_retry_message, errorText, retryNumber)
                }
            }
        }

        logError("重试${maxRetries}次后仍然失败", lastException)
        throw IOException(
            context.getString(
                R.string.gemini_error_connection_timeout,
                maxRetries,
                lastException?.message ?: context.getString(R.string.provider_error_network_interrupted)
            )
        )
        }
        return responseStream.withEventChannel(eventChannel)
    }

    /** 创建请求�?/
    private fun createRequestBody(
            context: Context,
            chatHistory: List<PromptTurn>,
            modelParameters: List<ModelParameter<*>>,
            enableThinking: Boolean,
            availableTools: List<ToolPrompt>? = null,
            preserveThinkInHistory: Boolean = false
    ): RequestBody {
        val json = JSONObject()

        // 添加工具定义
        val tools = JSONArray()
        
        // 添加 Function Calling 工具（如果启用且有可用工具）
        if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
            val functionDeclarations = buildToolDefinitionsForGemini(availableTools)
            if (functionDeclarations.length() > 0) {
                tools.put(JSONObject().apply {
                    put("function_declarations", functionDeclarations)
                })
                logDebug("已添�?{functionDeclarations.length()} ，Function Declarations")
            }
        }
        
        // 添加 Google Search grounding 工具（如果启用）
        if (enableGoogleSearch) {
            tools.put(JSONObject().apply {
                put("googleSearch", JSONObject())
            })
            logDebug("已启动Google Search Grounding")
        }
        
        // ，tools 添加到请求中，并保存用于token计算
        val toolsJson = if (tools.length() > 0) {
            json.put("tools", tools)
            tools.toString()
        } else {
            null
        }

        val (contentsResult, _) = buildContentsAndCountTokens(chatHistory, toolsJson, preserveThinkInHistory)
        val (contentsArray, systemInstruction) = contentsResult

        if (systemInstruction != null) {
            json.put("systemInstruction", systemInstruction)
        }
        json.put("contents", contentsArray)

        // 添加生成配置
        val generationConfig = JSONObject()

        // 如果启用了思考模式，则为Gemini模型添加特定的`thinkingConfig`参数
        if (enableThinking) {
            val thinkingConfig = JSONObject()
            thinkingConfig.put("includeThoughts", true)
            generationConfig.put("thinkingConfig", thinkingConfig)
            logDebug("已为Gemini模型启用“思考模式”，)
        }

        // 添加模型参数
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.apiName) {
                    "temperature" ->
                            generationConfig.put(
                                    "temperature",
                                    (param.currentValue as Number).toFloat()
                            )
                    "top_p" ->
                            generationConfig.put("topP", (param.currentValue as Number).toFloat())
                    "top_k" -> generationConfig.put("topK", (param.currentValue as Number).toInt())
                    "max_tokens" ->
                            generationConfig.put(
                                    "maxOutputTokens",
                                    (param.currentValue as Number).toInt()
                            )
                    else -> {
                        when (param.valueType) {
                            com.apex.data.model.ParameterValueType.OBJECT -> {
                                val raw = param.currentValue.toString().trim()
                                val parsed: Any? = try {
                                    when {
                                        raw.startsWith("{") -> JSONObject(raw)
                                        raw.startsWith("[") -> JSONArray(raw)
                                        else -> null
                                    }
                                } catch (e: Exception) {
                                    logError("Gemini OBJECT参数解析失败: ${param.apiName}", e)
                                    null
                                }
                                if (param.category == ParameterCategory.OTHER) {
                                    if (parsed != null) {
                                        json.put(param.apiName, parsed)
                                    } else {
                                        json.put(param.apiName, raw)
                                    }
                                } else {
                                    if (parsed != null) {
                                        generationConfig.put(param.apiName, parsed)
                                    } else {
                                        generationConfig.put(param.apiName, raw)
                                    }
                                }
                            }
                            else -> generationConfig.put(param.apiName, param.currentValue)
                        }
                    }
                }
            }
        }

        json.put("generationConfig", generationConfig)

        val jsonString = json.toString()
        // 使用分块日志函数记录请求体（省略过长的tools字段�?       val logJson = JSONObject(jsonString)
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        sanitizeImageDataForLogging(logJson)
        logLargeString(TAG, logJson.toString(4), context.getString(R.string.gemini_request_body_json))

        return jsonString.toByteArray(Charsets.UTF_8).toRequestBody(JSON)
    }

    /** 创建HTTP请求 */
    private suspend fun createRequest(
            context: Context,
            requestBody: RequestBody,
            isStreaming: Boolean,
            requestId: String
    ): Request {
        // 确定请求URL
        val baseUrl = determineBaseUrl(apiEndpoint)
        val method = if (isStreaming) "streamGenerateContent" else "generateContent"
        val requestUrl = "${baseUrl}/v1beta/models/${modelName}:${method}"

        AppLogger.d(TAG, "请求URL: ${requestUrl}")

        // 创建Request Builder
        val builder = Request.Builder()

        // 添加自定义请求头
        customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        // 添加API密钥
        val currentApiKey = apiKeyProvider.getApiKey()
        val finalUrl =
                if (requestUrl.contains("?")) {
                    "${requestUrl}&key=${currentApiKey}"
                } else {
                    "${requestUrl}?key=${currentApiKey}"
                }

        val request = builder.url(finalUrl)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

        logLargeString(TAG, context.getString(R.string.gemini_request_headers, request.headers.toString()))
        return request
    }

    /** 确定基础URL */
    private fun determineBaseUrl(endpoint: String): String {
        return try {
            val url = URL(endpoint)
            val port = if (url.port != -1) ":${url.port}" else ""
            "${url.protocol}://${url.host}${port}"
        } catch (e: Exception) {
            logError("解析API端点失败", e)
            "https://generativelanguage.googleapis.com"
        }
    }

    /** 处理API流式响应 */
    private suspend fun processStreamingResponse(
            context: Context,
            response: Response,
            streamCollector: StreamCollector<String>,
            requestId: String,
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
            receivedContent: StringBuilder
    ) {
        AppLogger.d(TAG, "开始处理响应流")
        val responseBody = response.body ?: throw IOException(context.getString(R.string.gemini_response_empty))
        val reader = responseBody.charStream().buffered()

        // 注意：不再使用fullContent累积所有内�?       var lineCount = 0
        var dataCount = 0
        var jsonCount = 0
        var contentCount = 0

        // 恢复JSON累积逻辑，用于处理分段JSON
        val completeJsonBuilder = StringBuilder()
        var isCollectingJson = false
        var jsonDepth = 0
        var jsonStartSymbol = ' ' // 记录JSON是以 { 还是 [ 开始的

        try {
            reader.useLines { lines ->
                lines.forEach { line ->
                    lineCount++
                    // 检查是否已取消
                    if (activeCall?.isCanceled() == true) {
                        return@forEach
                    }

                    // 处理SSE数据
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        dataCount++

                        // 跳过结束标记
                        if (data == "[DONE]") {
                            logDebug("收到流结束标签[DONE]")
                            return@forEach
                        }

                        try {
                            // 立即解析每个SSE数据行的JSON
                            val json = JSONObject(data)
                            jsonCount++

                            val content = extractContentFromJson(context, json, requestId, onTokensUpdated)
                            if (content.isNotEmpty()) {
                                contentCount++
                                logDebug("提取SSE内容，长�?${content.length}")
                                receivedContent.append(content)

                                // 只发送新增的内容
                                streamCollector.emit(content)
                            }
                        } catch (e: IOException) {
                            throw e
                        } catch (e: Exception) {
                            logError("解析SSE响应数据失败: ${e.message}", e)
                        }
                    } else if (line.trim().isNotEmpty()) {
                        // 处理可能分段的JSON数据
                        val trimmedLine = line.trim()

                        // 检查是否开始收集JSON
                        if (!isCollectingJson &&
                                        (trimmedLine.startsWith("{") || trimmedLine.startsWith("["))
                        ) {
                            isCollectingJson = true
                            jsonDepth = 0
                            completeJsonBuilder.clear()
                            jsonStartSymbol = trimmedLine[0]
                            logDebug("开始收集JSON，起始符�?${jsonStartSymbol}")
                        }

                        if (isCollectingJson) {
                            completeJsonBuilder.append(trimmedLine)

                            // 更新JSON深度
                            for (char in trimmedLine) {
                                if (char == '{' || char == '[') jsonDepth++
                                if (char == '}' || char == ']') jsonDepth--
                            }

                            // 尝试作为完整JSON解析
                            val possibleComplete = completeJsonBuilder.toString()
                            try {
                                if (jsonDepth == 0) {
                                    logDebug("尝试解析完整JSON: ${possibleComplete.take(50)}...")
                                    val jsonContent =
                                            if (jsonStartSymbol == '[') {
                                                JSONArray(possibleComplete)
                                            } else {
                                                JSONObject(possibleComplete)
                                            }

                                    // 解析成功，处理内�?                                   logDebug("成功解析完整JSON，长�?${possibleComplete.length}")

                                    when (jsonContent) {
                                        is JSONArray -> {
                                            // 处理JSON数组
                                            for (i in 0 until jsonContent.length()) {
                                                val jsonObject = jsonContent.optJSONObject(i)
                                                if (jsonObject != null) {
                                                    jsonCount++
                                                    val content =
                                                            extractContentFromJson(
                                                                    context,
                                                                    jsonObject,
                                                                    requestId,
                                                                    onTokensUpdated
                                                            )
                                                    if (content.isNotEmpty()) {
                                                        contentCount++
                                                        logDebug(
                                                                "从JSON数组[${i}]提取内容，长�?${content.length}"
                                                        )
                                                        receivedContent.append(content)

                                                        // 只发送这个单独对象产生的内容
                                                        streamCollector.emit(content)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 解析成功后重置收集器
                                    isCollectingJson = false
                                    completeJsonBuilder.clear()
                                }
                            } catch (e: IOException) {
                                throw e
                            } catch (e: Exception) {
                                // JSON尚未完整，继续收�?                               if (jsonDepth > 0) {
                                    // 仍在收集，这是预期的
                                    logDebug("继续收集JSON，当前深�?${jsonDepth}")
                                } else {
                                    // 深度，但解析失败，可能是无效JSON
                                    logError("JSON解析失败: ${e.message}", e)
                                    isCollectingJson = false
                                    completeJsonBuilder.clear()
                                }
                            }
                        }
                    }
                }
            }

            AppLogger.d(TAG, "响应处理完成: ，{lineCount}�?${jsonCount}个JSON�?提取${contentCount}个内容块")

            // 检查是否还有未解析完的JSON
            if (isCollectingJson && completeJsonBuilder.isNotEmpty()) {
                try {
                    val finalJson = completeJsonBuilder.toString()
                    AppLogger.d(TAG, "处理最终收集的JSON，长�?${finalJson.length}")

                    val jsonContent =
                            if (jsonStartSymbol == '[') {
                                JSONArray(finalJson)
                            } else {
                                JSONObject(finalJson)
                            }
                    // 处理内容
                    when (jsonContent) {
                        is JSONArray -> {
                            for (i in 0 until jsonContent.length()) {
                                val jsonObject = jsonContent.optJSONObject(i) ?: continue
                                jsonCount++
                                val content = extractContentFromJson(context, jsonObject, requestId, onTokensUpdated)
                                if (content.isNotEmpty()) {
                                    contentCount++
                                    logDebug("从最终JSON数组[${i}]提取内容，长�?${content.length}")
                                    receivedContent.append(content)
                                    streamCollector.emit(content)
                                }
                            }
                        }
                        is JSONObject -> {
                            jsonCount++
                            val content = extractContentFromJson(context, jsonContent, requestId, onTokensUpdated)
                            if (content.isNotEmpty()) {
                                contentCount++
                                logDebug("从最终JSON对象提取内容，长�?${content.length}")
                                receivedContent.append(content)
                                streamCollector.emit(content)
                            }
                        }
                    }
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    logError("解析最终收集的JSON失败: ${e.message}", e)
                }
            }

            // 确保思考模式正确结�?           if (isInThinkingMode) {
                logDebug("流结束时仍在思考模式，添加结束标签")
                streamCollector.emit("</think>")
                isInThinkingMode = false
            }
            
            // 确保至少发送一次内�?           if (contentCount == 0) {
                logDebug("未检测到内容，发送空�?
                streamCollector.emit(" ")
            }
        } catch (e: Exception) {
            logError("处理响应时发生异�?${e.message}", e)
            throw e
        } finally {
            activeCall = null
        }
    }

    /** 处理API非流式响�?/
    private suspend fun processNonStreamingResponse(
            context: Context,
            response: Response,
            streamCollector: StreamCollector<String>,
            requestId: String,
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
            receivedContent: StringBuilder
    ) {
        AppLogger.d(TAG, "开始处理非流式响应")
        val responseBody = response.body ?: throw IOException(context.getString(R.string.gemini_response_empty))
        
        try {
            val responseText = responseBody.string()
            logDebug("收到完整响应，长�?${responseText.length}")
            
            // 解析JSON响应
            val json = JSONObject(responseText)
            
            // 提取内容
            val content = extractContentFromJson(context, json, requestId, onTokensUpdated)
            
            if (content.isNotEmpty()) {
                receivedContent.append(content)
                
                // 直接发送整个内容块，下游会自己处理
                streamCollector.emit(content)
                
                logDebug("非流式响应处理完成，总长�?${content.length}")
            } else {
                logDebug("未检测到内容，发送空�?
                streamCollector.emit(" ")
            }
            
            // 确保思考模式正确结�?           if (isInThinkingMode) {
                logDebug("非流式响应结束时仍在思考模式，添加结束标签")
                streamCollector.emit("</think>")
                isInThinkingMode = false
            }
        } catch (e: Exception) {
            logError("处理非流式响应时发生异常: ${e.message}", e)
            throw e
        } finally {
            activeCall = null
        }
    }

    /** 从Gemini响应JSON中提取内�?/
    private suspend fun extractContentFromJson(
        context: Context,
        json: JSONObject,
        requestId: String,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit
    ): String {
        val contentBuilder = StringBuilder()
        val searchSourcesBuilder = StringBuilder()
        val pendingThoughtSignatures = mutableListOf<String>()

        try {
            throwIfGeminiErrorPayload(context, json)

            // 提取候选项
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                logDebug("未找到候选项")
                return ""
            }

            // 处理第一个candidate
            val candidate = candidates.getJSONObject(0)
            
            // 提取 Google Search grounding metadata（搜索来源信息）
            if (enableGoogleSearch) {
                val groundingMetadata = candidate.optJSONObject("groundingMetadata")
                if (groundingMetadata != null) {
                    // 提取搜索查询
                    val webSearchQueries = groundingMetadata.optJSONArray("webSearchQueries")
                    if (webSearchQueries != null && webSearchQueries.length() > 0) {
                        searchSourcesBuilder.append("\n<search>\n\n")
                        searchSourcesBuilder.append(context.getString(R.string.gemini_search_sources_title))

                        for (i in 0 until webSearchQueries.length()) {
                            val query = webSearchQueries.optString(i)
                            searchSourcesBuilder.append(context.getString(R.string.gemini_search_query, query))
                            logDebug("搜索查询 [${i}]: ${query}")
                        }
                        
                        // 提取搜索结果的URL来源
                        val groundingSupports = groundingMetadata.optJSONArray("groundingSupports")
                        if (groundingSupports != null && groundingSupports.length() > 0) {
                            searchSourcesBuilder.append(context.getString(R.string.gemini_reference_sources_title))
                            
                            for (i in 0 until groundingSupports.length()) {
                                val support = groundingSupports.getJSONObject(i)
                                val segment = support.optJSONObject("segment")
                                val groundingChunkIndices = support.optJSONArray("groundingChunkIndices")
                                
                                // 如果有chunk indices，提取对应的URL
                                if (groundingChunkIndices != null) {
                                    for (j in 0 until groundingChunkIndices.length()) {
                                        val chunkIndex = groundingChunkIndices.getInt(j)
                                        val retrievalMetadata = groundingMetadata.optJSONObject("retrievalMetadata")
                                        if (retrievalMetadata != null) {
                                            val webDynamicRetrievalScore = retrievalMetadata.optDouble("webDynamicRetrievalScore", -1.0)
                                            if (webDynamicRetrievalScore > 0) {
                                                logDebug("搜索动态检索分�?${webDynamicRetrievalScore}")
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 提取 grounding chunks（包含URL�?                           val groundingChunks = groundingMetadata.optJSONArray("groundingChunks")
                            if (groundingChunks != null && groundingChunks.length() > 0) {
                                for (i in 0 until groundingChunks.length()) {
                                    val chunk = groundingChunks.getJSONObject(i)
                                    val web = chunk.optJSONObject("web")
                                    if (web != null) {
                                        val uri = web.optString("uri", "")
                                        val title = web.optString("title", "")
                                        if (uri.isNotEmpty()) {
                                            if (title.isNotEmpty()) {
                                                searchSourcesBuilder.append("${i + 1}. [${title}](${uri})\n")
                                            } else {
                                                searchSourcesBuilder.append("${i + 1}. <${uri}>\n")
                                            }
                                            logDebug("搜索来源 [${i}]: ${title} - ${uri}")
                                        }
                                    }
                                }
                            }
                        }
                        
                        searchSourcesBuilder.append("\n</search>\n\n")
                    }
                }
            }

            // 检查finish_reason
            val finishReason = candidate.optString("finishReason", "")
            if (finishReason.isNotEmpty() && finishReason != "STOP") {
                logDebug("收到完成原因: ${finishReason}")
            }

            // 提取content对象
            val content = candidate.optJSONObject("content")
            if (content == null) {
                logDebug("未找到content对象")
                return ""
            }

            // 提取parts数组
            val parts = content.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                logDebug("未找到parts数组或为�?
                return ""
            }

            // 遍历parts，提取text内容和functionCall
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "")
                val isThought = part.optBoolean("thought", false)
                val functionCall = part.optJSONObject("functionCall")

                 val inlineData = part.optJSONObject("inline_data") ?: part.optJSONObject("inlineData")
                 if (inlineData != null) {
                     val mimeType = inlineData.optString("mime_type", inlineData.optString("mimeType", ""))
                     val b64 = inlineData.optString("data", "")
                     if (mimeType.startsWith("image/", ignoreCase = true) && b64.isNotEmpty()) {
                         if (isInThinkingMode) {
                             contentBuilder.append("</think>")
                             isInThinkingMode = false
                         }
                         val bytes = try {
                             Base64.decode(b64, Base64.DEFAULT)
                         } catch (_: Exception) {
                             null
                         }
                         if (bytes != null && bytes.isNotEmpty()) {
                             val uri = writeOutputImage(bytes, mimeType, "gemini_image_${i}")
                             if (uri != null) {
                                 contentBuilder.append("\n![gemini_image_${i}](${uri})\n")
                             }
                         }
                         continue
                     }
                 }

                // 处理 functionCall（流式转换为XML�?               if (functionCall != null && enableToolCall) {
                    val toolName = functionCall.optString("name", "")
                    if (toolName.isNotEmpty()) {
                        // 工具调用必须在思考模式之外，如果当前在思考中，先关闭
                        if (isInThinkingMode) {
                            contentBuilder.append("</think>")
                            isInThinkingMode = false
                            logDebug("检测到工具调用，提前结束思考模型）
                        }
                        
                        // 输出工具开始标�?                       val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
                        contentBuilder.append("\n<${toolTagName} name=\"${toolName}\">")
                        
                        // 使用 StreamingJsonXmlConverter 流式转换参数
                        val args = functionCall.optJSONObject("args")
                        if (args != null) {
                            val converter = StreamingJsonXmlConverter()
                            val argsJson = args.toString()
                            val events = converter.feed(argsJson)
                            events.forEach { event ->
                                when (event) {
                                    is StreamingJsonXmlConverter.Event.Tag -> contentBuilder.append(event.text)
                                    is StreamingJsonXmlConverter.Event.Content -> contentBuilder.append(event.text)
                                }
                            }
                            // 刷新剩余内容
                            val flushEvents = converter.flush()
                            flushEvents.forEach { event ->
                                when (event) {
                                    is StreamingJsonXmlConverter.Event.Tag -> contentBuilder.append(event.text)
                                    is StreamingJsonXmlConverter.Event.Content -> contentBuilder.append(event.text)
                                }
                            }
                        }
                        
                        // 输出工具结束标签
                        contentBuilder.append("\n</${toolTagName}>\n")
                        logDebug("Gemini FunctionCall流式转XML: ${toolName}")

                        part.optGeminiThoughtSignature()?.let { signature ->
                            pendingThoughtSignatures.add(signature)
                        }
                    }
                }

                if (text.isNotEmpty()) {
                    // 处理思考模式状态切�?                   if (isThought && !isInThinkingMode) {
                        // 开始思考模�?                       contentBuilder.append("<think>")
                        isInThinkingMode = true
                        logDebug("开始思考模型）
                    } else if (!isThought && isInThinkingMode) {
                        // 结束思考模�?                       contentBuilder.append("</think>")
                        isInThinkingMode = false
                        logDebug("结束思考模型）
                    }
                    
                    // 添加文本内容
                    contentBuilder.append(text)
                    
                    if (isThought) {
                        logDebug("提取思考内容，长度=${text.length}")
                    } else {
                        logDebug("提取文本，长�?{text.length}")
                    }

                    // 估算token
                    val tokens = ChatUtils.estimateTokenCount(text)
                    tokenCacheManager.addOutputTokens(tokens)
                    onTokensUpdated(
                            tokenCacheManager.totalInputTokenCount,
                            tokenCacheManager.cachedInputTokenCount,
                            tokenCacheManager.outputTokenCount
                    )
                }
            }

            pendingThoughtSignatures.forEach { signature ->
                appendGeminiThoughtSignatureMeta(contentBuilder, signature)
            }

            // 提取实际的token使用数据
            val usageMetadata = json.optJSONObject("usageMetadata")
            if (usageMetadata != null) {
                val promptTokenCount = usageMetadata.optInt("promptTokenCount", 0)
                val cachedContentTokenCount = usageMetadata.optInt("cachedContentTokenCount", 0)
                val candidatesTokenCount = usageMetadata.optInt("candidatesTokenCount", 0)

                val hasServerUsage =
                    promptTokenCount > 0 || cachedContentTokenCount > 0 || candidatesTokenCount > 0
                if (hasServerUsage) {
                    // 更新实际的token计数
                    val actualInputTokens = (promptTokenCount - cachedContentTokenCount).coerceAtLeast(0)
                    tokenCacheManager.updateActualTokens(actualInputTokens, cachedContentTokenCount)
                    tokenCacheManager.setOutputTokens(candidatesTokenCount)

                    logDebug("API实际Token使用: 输入=${actualInputTokens}, 缓存=${cachedContentTokenCount}, 输出=${candidatesTokenCount}")

                    // 更新回调，使用实际的token统计
                    onTokensUpdated(
                        tokenCacheManager.totalInputTokenCount,
                        tokenCacheManager.cachedInputTokenCount,
                        tokenCacheManager.outputTokenCount
                    )
                }
            }

            // 将搜索来源拼接到内容最前面
            val finalContent = if (searchSourcesBuilder.isNotEmpty()) {
                searchSourcesBuilder.toString() + contentBuilder.toString()
            } else {
                contentBuilder.toString()
            }
            
            return finalContent
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            logError("提取内容时发生错�?${e.message}", e)
            return ""
        }
    }

    /** 获取模型列表 */
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return ModelListFetcher.getModelsList(
            context = context,
            apiKey = apiKeyProvider.getApiKey(),
            apiEndpoint = apiEndpoint,
            apiProviderType = ApiProviderType.GOOGLE
        )
    }

    override suspend fun testConnection(context: Context): Result<String> {
        return try {
            // 通过发送一条短消息来测试完整的连接、认证和API端点�?           // 这比getModelsList更可靠，因为它直接命中了聊天API�?           // 提供一个通用的系统提示，以防止某些需要它的模型出现错误，            val testHistory = listOf("system" to "You are a helpful assistant.").toPromptTurns()
            val stream = sendMessage(
                context,
                testHistory + PromptTurn(kind = PromptTurnKind.USER, content = "Hi"),
                emptyList(),
                false,
                false,
                null,
                onTokensUpdated = { _, _, _ -> },
                onNonFatalError = {},
                enableRetry = false
            )

            // 消耗流以确保连接有效，            // �?Hi" 的响应应该很短，所以这会很快完成�?           var hasReceivedData = false
            stream.collect {
                hasReceivedData = true
            }

            // 某些情况下，即使连接成功，也可能不会返回任何数据（例如，如果模型只处理了提示而没有生成响应）�?           // 因此，只要不抛出异常，我们就认为连接成功�?           Result.success(context.getString(R.string.gemini_connection_success))
        } catch (e: Exception) {
            logError("连接测试失败", e)
            Result.failure(IOException(context.getString(R.string.gemini_connection_test_failed, e.message ?: ""), e))
        }
    }
}
