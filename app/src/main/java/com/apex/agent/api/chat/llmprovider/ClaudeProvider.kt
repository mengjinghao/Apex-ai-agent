package com.apex.api.chat.llmprovider

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.R
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.core.chat.hooks.mergeAdjacentTurns
import com.apex.core.chat.hooks.toPromptTurns
import com.apex.data.model.ApiProviderType
import com.apex.data.model.ModelOption
import com.apex.data.model.ModelParameter
import com.apex.data.model.ToolPrompt
import com.apex.api.chat.llmprovider.EndpointCompleter
import com.apex.util.ChatUtils
import com.apex.util.StreamingJsonXmlConverter
import com.apex.util.ChatMarkupRegex
import com.apex.util.TokenCacheManager
import com.apex.util.exceptions.UserCancellationException
import com.apex.util.stream.MutableSharedStream
import com.apex.util.stream.SharedStream
import com.apex.util.stream.Stream
import com.apex.util.stream.StreamCollector
import com.apex.util.stream.TextStreamEvent
import com.apex.util.stream.TextStreamEventType
import com.apex.util.stream.withEventChannel
import com.apex.util.stream.stream
import com.apex.api.chat.llmprovider.MediaLinkParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Claude API的实现，处理Claude特有的API格式 */
class ClaudeProvider(
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    private val modelName: String,
    private val client: OkHttpClient,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val providerType: ApiProviderType = ApiProviderType.ANTHROPIC,
    private val enableToolCall: Boolean = false // 是否启用Tool Call接口（预留，Claude有原生tool支持�? : AIService {
    // private val client: OkHttpClient = HttpClientFactory.instance

    private val JSON = "application/json".toMediaType()
    private val ANTHROPIC_VERSION = "2023-06-01" // Claude API版本
    private val PROMPT_CACHE_CONTROL_TYPE = "ephemeral"
    private val DEFAULT_MAX_TOKENS = 4096

    // 当前活跃的Call对象，用于取消流式传�?   private var activeCall: Call? = null
    private var activeResponse: Response? = null
    @Volatile private var isManuallyCancelled = false

    /**
     * 由客户端错误（如4xx状态码）触发的API异常，是否重试由统一策略决定
     */
    class NonRetriableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // 添加token计数�?   private val tokenCacheManager = TokenCacheManager()

    // 公开token计数
    override val inputTokenCount: Int
        get() = tokenCacheManager.totalInputTokenCount
    override val cachedInputTokenCount: Int
        get() = tokenCacheManager.cachedInputTokenCount
    override val outputTokenCount: Int
        get() = tokenCacheManager.outputTokenCount

    // 供应，模型标识，    override val providerModel: String
        get() = "${providerType.name}:${modelName}"

    // 重置token计数
    override fun resetTokenCounts() {
        tokenCacheManager.resetTokenCounts()
    }

    // 取消当前流式传输
    override fun cancelStreaming() {
        isManuallyCancelled = true

        // 1. 强制关闭 Response（这会立即中断流读取操作�?       activeResponse?.let {
            try {
                it.close()
                AppLogger.d("AIService", "已强制关闭Response�?
            } catch (e: Exception) {
                AppLogger.w("AIService", "关闭Response时出�?${e.message}")
            }
        }
        activeResponse = null

        // 2. 取消 Call
        activeCall?.let {
            if (!it.isCanceled()) {
                it.cancel()
                AppLogger.d("AIService", "已取消当前流式传输，Call已中�?
            }
        }
        activeCall = null

        AppLogger.d("AIService", "取消标志已设置，流读取将立即被中�?
    }

    private fun headersForLog(headers: Headers): String {
        return buildString {
            headers.names().forEach { name ->
                val value = when {
                    name.equals("x-api-key", ignoreCase = true) -> "[REDACTED]"
                    name.equals("authorization", ignoreCase = true) -> "[REDACTED]"
                    else -> headers[name] ?: ""
                }
                append(name)
                append(": ")
                append(value)
                append('\n')
            }
        }.trimEnd()
    }

    private data class AnthropicUsageCounts(
        val actualInputTokens: Int,
        val cachedInputTokens: Int,
        val totalInputTokens: Int,
        val outputTokens: Int,
        val cacheCreationInputTokens: Int
    )

    private fun sumNumericFields(jsonObject: JSONObject): Int {
        jsonObject ?: return 0

        var total = 0
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = jsonObject.opt(key)) {
                is Number -> total += value.toInt()
                is JSONObject -> total += sumNumericFields(value)
            }
        }
        return total
    }

    private fun parseAnthropicUsage(usage: JSONObject): AnthropicUsageCounts? {
        usage ?: return null

        val cachedInputTokens = when {
            usage.has("cache_read_input_tokens") -> usage.optInt("cache_read_input_tokens", 0)
            usage.optJSONObject("input_tokens_details") != null ->
                usage.optJSONObject("input_tokens_details")?.optInt("cached_tokens", 0) ?: 0
            else -> usage.optInt("cached_tokens", 0)
        }.coerceAtLeast(0)

        val cacheCreationInputTokens = when {
            usage.has("cache_creation_input_tokens") -> usage.optInt("cache_creation_input_tokens", 0)
            usage.optJSONObject("cache_creation") != null ->
                sumNumericFields(usage.optJSONObject("cache_creation"))
            else -> 0
        }.coerceAtLeast(0)

        val actualInputTokens = if (usage.has("input_tokens")) {
            usage.optInt("input_tokens", 0).coerceAtLeast(0) + cacheCreationInputTokens
        } else {
            (usage.optInt("prompt_tokens", 0).coerceAtLeast(0) - cachedInputTokens)
                .coerceAtLeast(0) + cacheCreationInputTokens
        }

        val totalInputTokens = actualInputTokens + cachedInputTokens
        val outputTokens =
            usage.optInt("output_tokens", usage.optInt("completion_tokens", 0)).coerceAtLeast(0)

        if (totalInputTokens <= 0 && outputTokens <= 0) {
            return null
        }

        return AnthropicUsageCounts(
            actualInputTokens = actualInputTokens,
            cachedInputTokens = cachedInputTokens,
            totalInputTokens = totalInputTokens,
            outputTokens = outputTokens,
            cacheCreationInputTokens = cacheCreationInputTokens
        )
    }

    private suspend fun applyAnthropicUsage(
        usage: JSONObject?,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        source: String,
        overwriteOutputTokens: Boolean
    ): Boolean {
        val parsed = parseAnthropicUsage(usage) ?: return false

        tokenCacheManager.updateActualTokens(
            actualInput = parsed.actualInputTokens,
            cachedInput = parsed.cachedInputTokens
        )

        if (overwriteOutputTokens && parsed.outputTokens > 0) {
            tokenCacheManager.setOutputTokens(parsed.outputTokens)
        }

        AppLogger.d(
            "AIService",
            "Claude[${source}]实际Token: 输入=${parsed.totalInputTokens}, 缓存=${parsed.cachedInputTokens}, 输出=${parsed.outputTokens}, cache_creation=${parsed.cacheCreationInputTokens}"
        )

        onTokensUpdated(
            parsed.totalInputTokens,
            parsed.cachedInputTokens,
            tokenCacheManager.outputTokenCount
        )
        return true
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

    private fun sanitizeToolCallId(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-') {
                sb.append(ch)
            } else {
                sb.append('_')
            }
        }
        var out = sb.toString().replace(Regex("_+"), "_")
        out = out.trim('_')
        return if (out.isEmpty()) "toolu" else out
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
        var base = positive.toString(36)
        base = base.filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }

    /**
     * 解析XML格式的tool调用，转换为Claude Tool格式
     * @return Pair<文本内容, tool_use数组>
     */
    private fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        if (!enableToolCall) return Pair(content, null)

        val matches = ChatMarkupRegex.toolCallPattern.findAll(content)

        if (!matches.any()) {
            return Pair(content, null)
        }

        val toolUses = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val toolBody = match.groupValues[3]

            // 解析参数
            val input = JSONObject()

            ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                input.put(paramName, paramValue)
            }

            // 构建tool_use对象（Claude格式�?           val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${input}")
            val callId = sanitizeToolCallId("toolu_${toolNamePart}_${hashPart}_${callIndex}")
            toolUses.put(JSONObject().apply {
                put("type", "tool_use")
                put("id", callId)
                put("name", toolName)
                put("input", input)
            })

            callIndex++
            AppLogger.d("AIService", "XML→ClaudeToolUse: ${toolName} -> ID: ${callId}")

            // 从文本内容中移除tool标签
            textContent = textContent.replace(match.value, "")
        }
        
        return Pair(textContent.trim(), toolUses)
    }
    
    /**
     * 解析XML格式的tool_result，转换为Claude Tool Result格式
     * @return Pair<文本内容, tool_result数组>
     */
    private fun parseXmlToolResults(content: String): Pair<String, List<Pair<String, String>>?> {
        if (!enableToolCall) return Pair(content, null)
        
        val matches = ChatMarkupRegex.toolResultAnyPattern.findAll(content)
        
        if (!matches.any()) {
            return Pair(content, null)
        }
        
        val results = mutableListOf<Pair<String, String>>()
        var textContent = content
        var resultIndex = 0
        
        matches.forEach { match ->
            val fullContent = match.groupValues[2].trim()
            val contentMatch = ChatMarkupRegex.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            
            results.add(Pair("toolu_result_${resultIndex}", resultContent))
            textContent = textContent.replace(match.value, "").trim()
            
            AppLogger.d("AIService", "解析Claude tool_result #${resultIndex}, content length=${resultContent.length}")
            resultIndex++
        }
        
        return Pair(textContent.trim(), results)
    }
    
    /**
     * 从ToolPrompt列表构建Claude格式的Tool Definitions
     */
    private fun buildToolDefinitionsForClaude(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()
        
        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("name", tool.name)
                // 组合description和details作为完整描述
                val fullDescription = if (tool.details.isNotEmpty()) {
                    "${tool.description}\n${tool.details}"
                } else {
                    tool.description
                }
                put("description", fullDescription)
                
                // 使用结构化参数构建input_schema
                val inputSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                put("input_schema", inputSchema)
            })
        }
        
        return tools
    }
    
    /**
     * 从结构化参数构建JSON Schema（Claude格式�?    */
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
     * 构建包含文本和图片的content数组
     */
    private fun buildContentArray(text: String): JSONArray {
        val contentArray = JSONArray()

        val textAfterMediaRemoval = if (MediaLinkParser.hasMediaLinks(text)) {
            AppLogger.w("AIService", "检测到音视频链接，但Claude格式当前仅支持图片，多媒体链接将被移�?
            MediaLinkParser.removeMediaLinks(text).trim()
        } else {
            text
        }
        
        // 检查是否包含图片链�?       if (MediaLinkParser.hasImageLinks(textAfterMediaRemoval)) {
            val imageLinks = MediaLinkParser.extractImageLinks(textAfterMediaRemoval)
            val textWithoutLinks = MediaLinkParser.removeImageLinks(textAfterMediaRemoval).trim()
            
            // 添加图片
            imageLinks.forEach { link ->
                contentArray.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", link.mimeType)
                        put("data", link.base64Data)
                    })
                })
            }
            
            // 添加文本（如果有�?           if (textWithoutLinks.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", textWithoutLinks)
                })
            }
        } else {
            // 纯文本消�?           contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", textAfterMediaRemoval)
            })
        }
        
        return contentArray
    }

    private fun appendContentBlocks(target: JSONArray, blocks: JSONArray) {
        for (index in 0 until blocks.length()) {
            target.put(blocks.get(index))
        }
    }

    private fun sanitizeImageDataForLogging(json: JSONObject): JSONObject {
        fun sanitizeObject(obj: JSONObject) {
            fun sanitizeArray(arr: JSONArray) {
                for (index in 0 until arr.length()) {
                    when (val value = arr.get(index)) {
                        is JSONObject -> sanitizeObject(value)
                        is JSONArray -> sanitizeArray(value)
                        is String -> {
                            if (value.startsWith("data:") && value.contains(";base64,")) {
                                arr.put(index, "[image base64 omitted, length=${value.length}]")
                            }
                        }
                    }
                }
            }

            val mediaType = obj.optString("media_type", obj.optString("mime_type", ""))
            if (mediaType.startsWith("image/", ignoreCase = true) && obj.has("data")) {
                val dataValue = obj.opt("data")
                if (dataValue is String) {
                    obj.put("data", "[image base64 omitted, length=${dataValue.length}]")
                }
            }

            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = obj.get(key)) {
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

    private data class ClaudeSerializedHistory(
        val messagesArray: JSONArray,
        val systemBlocks: JSONArray?
    )

    private fun cacheControlObject(): JSONObject {
        return JSONObject().apply {
            put("type", PROMPT_CACHE_CONTROL_TYPE)
        }
    }

    private fun attachCacheControlIfAbsent(block: JSONObject): Boolean {
        if (block.has("cache_control")) {
            return false
        }
        block.put("cache_control", cacheControlObject())
        return true
    }

    private fun findLastContentBlock(messagesArray: JSONArray): JSONObject? {
        for (messageIndex in messagesArray.length() - 1 downTo 0) {
            val messageObject = messagesArray.optJSONObject(messageIndex) ?: continue
            val contentArray = messageObject.optJSONArray("content") ?: continue
            for (contentIndex in contentArray.length() - 1 downTo 0) {
                val contentBlock = contentArray.optJSONObject(contentIndex)
                if (contentBlock != null) {
                    return contentBlock
                }
            }
        }
        return null
    }

    private fun applyStableCacheBreakpoints(
        tools: JSONArray?,
        systemBlocks: JSONArray?,
        messagesArray: JSONArray
    ): Int {
        var breakpoints = 0

        if (tools != null && tools.length() > 0) {
            val lastTool = tools.optJSONObject(tools.length() - 1)
            if (lastTool != null && attachCacheControlIfAbsent(lastTool)) {
                breakpoints++
            }
        }

        if (systemBlocks != null && systemBlocks.length() > 0) {
            val lastSystemBlock = systemBlocks.optJSONObject(systemBlocks.length() - 1)
            if (lastSystemBlock != null && attachCacheControlIfAbsent(lastSystemBlock)) {
                breakpoints++
            }
        }

        val lastMessageBlock = findLastContentBlock(messagesArray)
        if (lastMessageBlock != null && attachCacheControlIfAbsent(lastMessageBlock)) {
            breakpoints++
        }

        return breakpoints
    }

    private fun buildComparableHistory(
        systemBlocks: JSONArray?,
        messagesArray: JSONArray
    ): List<Pair<String, String>> {
        val comparableHistory = mutableListOf<Pair<String, String>>()

        if (systemBlocks != null && systemBlocks.length() > 0) {
            comparableHistory.add("system" to stableJsonValue(systemBlocks))
        }

        for (messageIndex in 0 until messagesArray.length()) {
            val messageObject = messagesArray.optJSONObject(messageIndex) ?: continue
            val role = messageObject.optString("role")
            val contentArray = messageObject.optJSONArray("content") ?: JSONArray()
            comparableHistory.add(role to stableJsonValue(contentArray))
        }

        return comparableHistory
    }

    private fun stableJsonValue(value: Any): String {
        return when (value) {
            null -> "null"
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    keys.add(iterator.next())
                }
                keys.sort()
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    "\"${key}\":${stableJsonValue(value.opt(key))}"
                }
            }
            is JSONArray -> {
                (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
                    stableJsonValue(value.opt(index))
                }
            }
            is String -> JSONObject.quote(value)
            is Number,
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun buildSerializedHistory(
        chatHistory: List<PromptTurn>,
        preserveThinkInHistory: Boolean
    ): ClaudeSerializedHistory {
        val messagesArray = JSONArray()
        val effectiveHistory = chatHistory.mergeAdjacentTurns()

        val systemMessages = effectiveHistory.filter { it.kind == PromptTurnKind.SYSTEM }
        val systemPrompt =
            systemMessages
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n\n") { it.content }
        val systemBlocks =
            systemPrompt
                ?.takeIf { it.isNotEmpty() }
                ?.let { prompt ->
                    JSONArray().put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        }
                    )
                }

        val historyWithoutSystem = effectiveHistory.filter { it.kind != PromptTurnKind.SYSTEM }
        var queuedAssistantToolText: String? = null
        var queuedToolUses = JSONArray()
        val queuedToolUseIds = mutableListOf<String>()
        val openToolUseIds = mutableListOf<String>()

        fun appendQueuedAssistantToolText(text: String) {
            if (text.isBlank()) return
            queuedAssistantToolText =
                if (queuedAssistantToolText.isNullOrBlank()) {
                    text
                } else {
                    queuedAssistantToolText + "\n" + text
                }
        }

        fun queueToolUses(textContent: String, toolUses: JSONArray) {
            appendQueuedAssistantToolText(textContent)
            for (i in 0 until toolUses.length()) {
                val toolUse = toolUses.optJSONObject(i) ?: continue
                queuedToolUses.put(toolUse)
                val toolUseId = toolUse.optString("id", "").trim()
                if (toolUseId.isNotEmpty()) {
                    queuedToolUseIds.add(toolUseId)
                }
            }
        }

        fun emitQueuedToolUsesIfNeeded() {
            if (queuedToolUses.length() == 0) return

            val contentArray = JSONArray()
            if (!queuedAssistantToolText.isNullOrBlank()) {
                appendContentBlocks(contentArray, buildContentArray(queuedAssistantToolText!!))
            }
            for (i in 0 until queuedToolUses.length()) {
                contentArray.put(queuedToolUses.getJSONObject(i))
            }

            messagesArray.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", contentArray)
                }
            )

            openToolUseIds.addAll(queuedToolUseIds)
            queuedAssistantToolText = null
            queuedToolUses = JSONArray()
            queuedToolUseIds.clear()
        }

        fun appendCancelledOpenToolUses(target: JSONArray, reason: String): Boolean {
            emitQueuedToolUsesIfNeeded()
            if (openToolUseIds.isEmpty()) return false

            AppLogger.w(
                "AIService",
                "发现未完成的tool_use，按取消处理: count=${openToolUseIds.size}, reason=${reason}"
            )
            for (toolUseId in openToolUseIds) {
                target.put(
                    JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", toolUseId)
                        put("content", "User cancelled")
                    }
                )
            }
            openToolUseIds.clear()
            return true
        }

        fun flushOpenToolUsesAsCancelled(reason: String) {
            val contentArray = JSONArray()
            if (!appendCancelledOpenToolUses(contentArray, reason)) return
            messagesArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
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

            if (enableToolCall) {
                when (turn.kind) {
                    PromptTurnKind.SYSTEM -> Unit

                    PromptTurnKind.ASSISTANT -> {
                        val (textContent, toolUses) = parseXmlToolCalls(content)
                        if (toolUses != null && toolUses.length() > 0) {
                            if (openToolUseIds.isNotEmpty()) {
                                flushOpenToolUsesAsCancelled("assistant_tool_use_before_result")
                            }
                            queueToolUses(textContent, toolUses)
                        } else {
                            flushOpenToolUsesAsCancelled("assistant_boundary")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", buildContentArray(content))
                                }
                            )
                        }
                    }

                    PromptTurnKind.TOOL_CALL -> {
                        val (textContent, toolUses) = parseXmlToolCalls(content)
                        if (toolUses != null && toolUses.length() > 0) {
                            if (openToolUseIds.isNotEmpty()) {
                                flushOpenToolUsesAsCancelled("typed_tool_use_before_result")
                            }
                            queueToolUses(textContent, toolUses)
                        } else {
                            flushOpenToolUsesAsCancelled("typed_tool_call_without_payload")
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", buildContentArray(content))
                                }
                            )
                        }
                    }

                    PromptTurnKind.USER,
                    PromptTurnKind.SUMMARY -> {
                        val contentArray = JSONArray()
                        appendCancelledOpenToolUses(contentArray, "user_boundary")
                        appendContentBlocks(contentArray, buildContentArray(content))
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            }
                        )
                    }

                    PromptTurnKind.TOOL_RESULT -> {
                        emitQueuedToolUsesIfNeeded()
                        val (textContent, toolResults) = parseXmlToolResults(content)
                        val resultsList = toolResults ?: emptyList()

                        if (resultsList.isNotEmpty() && openToolUseIds.isNotEmpty()) {
                            val contentArray = JSONArray()
                            val validCount = minOf(resultsList.size, openToolUseIds.size)

                            for (index in 0 until validCount) {
                                val (_, resultContent) = resultsList[index]
                                contentArray.put(
                                    JSONObject().apply {
                                        put("type", "tool_result")
                                        put("tool_use_id", openToolUseIds[index])
                                        put("content", resultContent)
                                    }
                                )
                                AppLogger.d(
                                    "AIService",
                                    "历史XML→ClaudeToolResult: ID=${openToolUseIds[index]}, content length=${resultContent.length}"
                                )
                            }

                            repeat(validCount) {
                                openToolUseIds.removeAt(0)
                            }

                            if (resultsList.size > validCount) {
                                AppLogger.w(
                                    "AIService",
                                    "发现多余的tool_result: ${resultsList.size} results vs ${validCount} pending tool_uses"
                                )
                            }

                            if (textContent.isNotEmpty()) {
                                appendContentBlocks(contentArray, buildContentArray(textContent))
                            }

                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                }
                            )
                        } else {
                            val contentArray = JSONArray()
                            appendCancelledOpenToolUses(contentArray, "tool_result_without_structured_match")
                            appendContentBlocks(
                                contentArray,
                                buildContentArray(
                                    when {
                                        textContent.isNotEmpty() -> textContent
                                        else -> content
                                    }
                                )
                            )
                            messagesArray.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                }
                            )
                        }
                    }
                }
            } else {
                val claudeRole =
                    when (turn.kind) {
                        PromptTurnKind.ASSISTANT,
                        PromptTurnKind.TOOL_CALL -> "assistant"
                        else -> "user"
                    }
                val contentArray = buildContentArray(content)
                val messageObject = JSONObject()
                messageObject.put("role", claudeRole)
                messageObject.put("content", contentArray)
                messagesArray.put(messageObject)
            }
        }

        flushOpenToolUsesAsCancelled("history_end")

        return ClaudeSerializedHistory(
            messagesArray = messagesArray,
            systemBlocks = systemBlocks
        )
    }

    /**
     * 构建Claude的消息体和计算Token的核心逻辑
     */
    private fun buildMessagesAndCountTokens(
            chatHistory: List<PromptTurn>,
            preserveThinkInHistory: Boolean,
            tools: JSONArray? = null
    ): Triple<JSONArray, JSONArray?, Int> {
        val serializedHistory = buildSerializedHistory(chatHistory, preserveThinkInHistory)
        val breakpointsApplied =
            applyStableCacheBreakpoints(
                tools = tools,
                systemBlocks = serializedHistory.systemBlocks,
                messagesArray = serializedHistory.messagesArray
            )
        val toolsJson = tools?.takeIf { it.length() > 0 }?.toString()
        val comparableHistory =
            buildComparableHistory(
                systemBlocks = serializedHistory.systemBlocks,
                messagesArray = serializedHistory.messagesArray
            )
        val tokenCount =
            tokenCacheManager.calculateInputTokens(
                comparableHistory,
                toolsJson
            )
        AppLogger.d("AIService", "Claude显式缓存断点已应�?count=${breakpointsApplied}")
        return Triple(
            serializedHistory.messagesArray,
            serializedHistory.systemBlocks,
            tokenCount
        )
    }
    override suspend fun calculateInputTokens(
            chatHistory: List<PromptTurn>,
            availableTools: List<ToolPrompt>?
    ): Int {
        val serializedHistory = buildSerializedHistory(chatHistory, preserveThinkInHistory = false)
        val tools =
            if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
                buildToolDefinitionsForClaude(availableTools).takeIf { it.length() > 0 }
            } else {
                null
            }
        applyStableCacheBreakpoints(
            tools = tools,
            systemBlocks = serializedHistory.systemBlocks,
            messagesArray = serializedHistory.messagesArray
        )
        val toolsJson = tools?.toString()
        val comparableHistory =
            buildComparableHistory(
                systemBlocks = serializedHistory.systemBlocks,
                messagesArray = serializedHistory.messagesArray
            )
        return tokenCacheManager.calculateInputTokens(
            comparableHistory,
            toolsJson,
            updateState = false
        )
    }

    // 创建Claude API请求�?   private fun createRequestBody(
            chatHistory: List<PromptTurn>,
            modelParameters: List<ModelParameter<*>> = emptyList(),
            enableThinking: Boolean,
            stream: Boolean = true,
            availableTools: List<ToolPrompt>? = null,
            preserveThinkInHistory: Boolean = false
    ): RequestBody {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)

        // 添加已启用的模型参数
        addParameters(jsonObject, modelParameters)

        val maxTokensFromParams = modelParameters
            .firstOrNull { it.apiName == "max_tokens" }
            ?.currentValue
        val maxTokensValue = (maxTokensFromParams as? Number)?.toInt()?.takeIf { it > 0 }
            ?: jsonObject.optInt("max_tokens", 0).takeIf { it > 0 }
            ?: resolveOfficialAnthropicMaxTokens()
        if (maxTokensValue != null) {
            jsonObject.put("max_tokens", maxTokensValue)
        }

        // 添加 Tool Call 工具定义（如果启用且有可用工具）
        var tools: JSONArray? = null
        if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
            val builtTools = buildToolDefinitionsForClaude(availableTools)
            if (builtTools.length() > 0) {
                tools = builtTools
                jsonObject.put("tools", builtTools)
                AppLogger.d("AIService", "已添�?{builtTools.length()} ，Claude Tool Definitions")
            }
        }

        val (messagesArray, systemBlocks, _) =
            buildMessagesAndCountTokens(chatHistory, preserveThinkInHistory, tools)

        jsonObject.put("messages", messagesArray)

        // Claude对系统消息的处理有所不同，它使用system参数
        if (systemBlocks != null) {
            jsonObject.put("system", systemBlocks)
        }

        // 添加extended thinking支持
        if (enableThinking) {
            val thinkingObject = JSONObject()
            thinkingObject.put("type", "enabled")

            val budgetTokensFromParams = modelParameters
                .firstOrNull { it.apiName == "budget_tokens" }
                ?.currentValue
            val budgetTokensValue = (budgetTokensFromParams as? Number)?.toInt()?.takeIf { it > 0 }
                ?: minOf(1024, maxTokensValue ?: DEFAULT_MAX_TOKENS)
            thinkingObject.put("budget_tokens", budgetTokensValue)

            jsonObject.put("thinking", thinkingObject)
            AppLogger.d("AIService", "启用Claude的extended thinking功能")
        }

        // 日志输出时省略过长的tools字段
        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        sanitizeImageDataForLogging(logJson)
        AppLogger.d("AIService", "Claude请求�?${logJson.toString(4)}")
        return jsonObject.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON)
    }

    private fun resolveOfficialAnthropicMaxTokens(): Int? {
        if (providerType != ApiProviderType.ANTHROPIC) {
            return null
        }

        val normalizedModelName = modelName.trim().lowercase()
        return when {
            normalizedModelName.startsWith("claude-opus-4-1") -> 32_000
            normalizedModelName.startsWith("claude-opus-4") -> 32_000
            normalizedModelName.startsWith("claude-sonnet-4") -> 64_000
            normalizedModelName.startsWith("claude-3-7-sonnet") -> 64_000
            normalizedModelName.startsWith("claude-3-5-sonnet") -> 8_192
            normalizedModelName.startsWith("claude-3-5-haiku") -> 8_192
            normalizedModelName.startsWith("claude-3-haiku") -> 4_096
            else -> DEFAULT_MAX_TOKENS
        }
    }

    // 添加模型参数
    private fun addParameters(jsonObject: JSONObject, modelParameters: List<ModelParameter<*>>) {
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.apiName) {
                    "temperature" ->
                            jsonObject.put("temperature", (param.currentValue as Number).toFloat())
                    "top_p" -> jsonObject.put("top_p", (param.currentValue as Number).toFloat())
                    "top_k" -> jsonObject.put("top_k", (param.currentValue as Number).toInt())
                    "max_tokens" ->
                            jsonObject.put("max_tokens", (param.currentValue as Number).toInt())
                    "max_tokens_to_sample" ->
                            jsonObject.put(
                                    "max_tokens_to_sample",
                                    (param.currentValue as Number).toInt()
                            )
                    "stop_sequences" -> {
                        // 处理停止序列
                        val stopSequences = param.currentValue as? List<*>
                        if (stopSequences != null) {
                            val stopArray = JSONArray()
                            stopSequences.forEach { stopArray.put(it.toString()) }
                            jsonObject.put("stop_sequences", stopArray)
                        }
                    }
                    // 忽略thinking相关参数，因为它们会在单独的部分处理
                    "thinking",
                    "budget_tokens" -> {
                        // 忽略，在特定部分处理
                    }
                    else -> {
                        // 添加其他Claude特定参数
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
                                    AppLogger.w("AIService", "Claude OBJECT参数解析失败: ${param.apiName}", e)
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
                AppLogger.d("AIService", "添加Claude参数 ${param.apiName} = ${param.currentValue}")
            }
        }
    }

    // 创建请求
    private suspend fun createRequest(requestBody: RequestBody): Request {
        val currentApiKey = apiKeyProvider.getApiKey()
        val completedEndpoint = EndpointCompleter.completeEndpoint(apiEndpoint, providerType)
        val builder =
                Request.Builder()
                        .url(completedEndpoint)
                        .post(requestBody)
                        .addHeader("x-api-key", currentApiKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .addHeader("Content-Type", "application/json")

        // 添加自定义请求头
        customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        val request = builder.build()
        AppLogger.d("AIService", "Claude请求URL: ${request.url}")
        AppLogger.d("AIService", "Claude请求�?\n${headersForLog(request.headers)}")
        return request
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
        if (exception is UserCancellationException || exception is CancellationException) {
            throw exception
        }
        if (isManuallyCancelled) {
            AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试着�?
            throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), exception)
        }

        val errorText = resolveRetryErrorText(context, exception)

        if (!enableRetry) {
            throw IOException(errorText, exception)
        }

        val newRetryCount = retryCount + 1
        if (newRetryCount > maxRetries) {
            AppLogger.e("AIService", "【Claude，errorText 且达到最大重试次，的${maxRetries})", exception)
            throw IOException(
                context.getString(R.string.openai_error_connection_timeout, maxRetries, errorText),
                exception
            )
        }

        val retryDelayMs = LlmRetryPolicy.nextDelayMs(newRetryCount)
        AppLogger.w("AIService", "【Claude，errorText${retryDelayMs}ms 后进行第 ${newRetryCount} 次重�?.", exception)
        onNonFatalError(buildRetryMessage(errorText, newRetryCount))
        delay(retryDelayMs)
        return newRetryCount
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
        val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val responseStream = stream {
        isManuallyCancelled = false
        tokenCacheManager.setOutputTokens(0)

        val maxRetries = LlmRetryPolicy.MAX_RETRY_ATTEMPTS
        var retryCount = 0
        var lastException: Exception? = null
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

        fun parseAnthropicNonStreaming(jsonResponse: JSONObject): String {
            val content = jsonResponse.optJSONArray("content") ?: return ""
            if (content.length() <= 0) return ""
            val fullText = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> {
                        val text = block.optString("text", "")
                        if (text.isNotEmpty()) fullText.append(text)
                    }
                    "thinking" -> {
                        val thinking = block.optString("thinking", "")
                        if (thinking.isNotEmpty()) {
                            fullText.append("\n<think>")
                            fullText.append(thinking)
                            fullText.append("</think>\n")
                        }
                    }
                    "redacted_thinking" -> {
                    }
                    "tool_use" -> {
                        if (enableToolCall) {
                            val toolName = block.optString("name", "")
                            if (toolName.isNotEmpty()) {
                                val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
                                fullText.append("\n<${toolTagName} name=\"${toolName}\">")
                                val input = block.optJSONObject("input")
                                if (input != null) {
                                    val converter = StreamingJsonXmlConverter()
                                    val events = converter.feed(input.toString())
                                    events.forEach { event ->
                                        when (event) {
                                            is StreamingJsonXmlConverter.Event.Tag -> fullText.append(event.text)
                                            is StreamingJsonXmlConverter.Event.Content -> fullText.append(event.text)
                                        }
                                    }
                                    val flushEvents = converter.flush()
                                    flushEvents.forEach { event ->
                                        when (event) {
                                            is StreamingJsonXmlConverter.Event.Tag -> fullText.append(event.text)
                                            is StreamingJsonXmlConverter.Event.Content -> fullText.append(event.text)
                                        }
                                    }
                                }
                                fullText.append("\n</${toolTagName}>\n")
                            }
                        }
                    }
                }
            }
            return fullText.toString()
        }

        fun parseOpenAiNonStreaming(jsonResponse: JSONObject): String {
            val choices = jsonResponse.optJSONArray("choices") ?: return ""
            if (choices.length() <= 0) return ""
            val first = choices.optJSONObject(0) ?: return ""
            val messageObj = first.optJSONObject("message")
            return messageObj?.optString("content", "") ?: ""
        }

        emitSavepoint(requestSavepointId)

        AppLogger.d("AIService", "准备连接到Claude AI服务...")
        while (retryCount <= maxRetries) {
            if (isManuallyCancelled) {
                AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试着�?
                throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled))
            }

            val call = try {
                if (retryCount > 0) {
                    AppLogger.d(
                        "AIService",
                        "【Claude 重试】原子回滚后重新请求，本轮已撤回内容长度: ${receivedContent.length}"
                    )
                }

                val requestBody = createRequestBody(
                    chatHistory,
                    modelParameters,
                    enableThinking,
                    stream,
                    availableTools,
                    preserveThinkInHistory
                )
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
                val request = createRequest(requestBody)
                client.newCall(request)
            } catch (e: Exception) {
                throw e
            }

            activeCall = call
            try {
                AppLogger.d("AIService", "正在建立连接...")
                withContext(Dispatchers.IO) {
                    val response = call.execute()
                    activeResponse = response
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: context.getString(R.string.openai_error_no_error_details)
                            // 4xx错误仍保留单独的异常类型，具体是否重试由统一策略决定
                            if (response.code in 400..499) {
                                throw NonRetriableException(context.getString(R.string.openai_error_api_request_failed_with_status, response.code, errorBody))
                            }
                            throw IOException(context.getString(R.string.openai_error_api_request_failed_with_status, response.code, errorBody))
                        }

                        AppLogger.d("AIService", "连接成功，等待响�?.")
                        val responseBody = response.body ?: throw IOException(context.getString(R.string.provider_error_response_empty))

                        val contentType = response.header("Content-Type") ?: ""
                        AppLogger.d(
                            "AIService",
                            "Claude响应状�?code=${response.code}, contentType=${contentType}"
                        )

                        val preview = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
                        val previewTrim = preview.trimStart()
                        val looksLikeJson = previewTrim.startsWith("{") || previewTrim.startsWith("[")
                        val looksLikeSse = previewTrim.startsWith("data:") || preview.contains("\ndata:")
                        val isEventStream = contentType.contains("event-stream", ignoreCase = true)
                        AppLogger.d(
                            "AIService",
                            "Claude响应格式检�?looksLikeJson=${looksLikeJson}, looksLikeSse=${looksLikeSse}, isEventStream=${isEventStream}"
                        )

                        if (stream && !looksLikeSse && looksLikeJson) {
                            val responseText = responseBody.string().trim()
                            val json = JSONObject(responseText)
                            val resultText = parseAnthropicNonStreaming(json).ifBlank { parseOpenAiNonStreaming(json) }
                            if (resultText.isNotBlank()) {
                                emit(resultText)
                                receivedContent.append(resultText)
                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(resultText))
                            }
                            val usageApplied = applyAnthropicUsage(
                                usage = json.optJSONObject("usage"),
                                onTokensUpdated = onTokensUpdated,
                                source = "non_streaming_json",
                                overwriteOutputTokens = true
                            )
                            if (resultText.isBlank() && !usageApplied) {
                                throw IOException(context.getString(R.string.provider_error_parsing_failed))
                            }
                            if (resultText.isNotBlank() && !usageApplied) {
                                onTokensUpdated(
                                    tokenCacheManager.totalInputTokenCount,
                                    tokenCacheManager.cachedInputTokenCount,
                                    tokenCacheManager.outputTokenCount
                                )
                            }
                            return@withContext
                        }

                        if (!stream) {
                            val responseText = responseBody.string().trim()
                            val json = JSONObject(responseText)
                            val resultText = parseAnthropicNonStreaming(json).ifBlank { parseOpenAiNonStreaming(json) }
                            if (resultText.isNotBlank()) {
                                emit(resultText)
                                receivedContent.append(resultText)
                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(resultText))
                            }
                            val usageApplied = applyAnthropicUsage(
                                usage = json.optJSONObject("usage"),
                                onTokensUpdated = onTokensUpdated,
                                source = "non_streaming_response",
                                overwriteOutputTokens = true
                            )
                            if (resultText.isNotBlank() && !usageApplied) {
                                onTokensUpdated(
                                    tokenCacheManager.totalInputTokenCount,
                                    tokenCacheManager.cachedInputTokenCount,
                                    tokenCacheManager.outputTokenCount
                                )
                            }
                            return@withContext
                        }

                        val reader = responseBody.charStream().buffered()
                        var currentToolParser: StreamingJsonXmlConverter? = null
                        var currentToolTagName: String? = null
                        var isInToolCall = false
                        var isInThinkingBlock = false
                        var emittedAny = false
                        val nonSseJsonLinesBuffer = StringBuilder()

                        while (true) {
                            val rawLine = reader.readLine() ?: break
                            val line = rawLine.trim()
                            if (activeCall?.isCanceled() == true) {
                                AppLogger.d("AIService", "流式传输已被取消，提前退出处理）
                                break
                            }
                            if (!line.startsWith("data:")) {
                                // 某些兼容端点可能直接返回 JSON/JSONL（不，SSE ，data: 前缀�?                               if ((line.startsWith("{") || line.startsWith("[")) &&
                                    nonSseJsonLinesBuffer.length < 2_000_000
                                ) {
                                    nonSseJsonLinesBuffer.append(line).append('\n')
                                }
                                continue
                            }
                            val data = line.substringAfter("data:").trimStart()
                            if (data == "[DONE]") break
                            if (data.isBlank()) continue

                            val jsonResponse = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            val type = jsonResponse.optString("type", "")

                            // OpenAI-style chunk (no `type`)
                            if (type.isBlank()) {
                                val choices = jsonResponse.optJSONArray("choices")
                                val first = choices?.optJSONObject(0)
                                val delta = first?.optJSONObject("delta")
                                val content = delta?.optString("content", "").orEmpty()
                                if (content.isNotEmpty()) {
                                    emittedAny = true
                                    tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                                    onTokensUpdated(
                                        tokenCacheManager.totalInputTokenCount,
                                        tokenCacheManager.cachedInputTokenCount,
                                        tokenCacheManager.outputTokenCount
                                    )
                                    emit(content)
                                    receivedContent.append(content)
                                }
                                continue
                            }

                            when (type) {
                                "ping" -> {
                                }
                                "message_start" -> {
                                    applyAnthropicUsage(
                                        usage = jsonResponse.optJSONObject("message")?.optJSONObject("usage"),
                                        onTokensUpdated = onTokensUpdated,
                                        source = "message_start",
                                        overwriteOutputTokens = false
                                    )
                                }
                                "content_block_start" -> {
                                    val contentBlock = jsonResponse.optJSONObject("content_block")
                                    if (contentBlock != null) {
                                        when (contentBlock.optString("type")) {
                                            "tool_use" -> {
                                                if (enableToolCall) {
                                                    val toolName = contentBlock.optString("name", "")
                                                    if (toolName.isNotEmpty()) {
                                                        val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
                                                        currentToolTagName = toolTagName
                                                        val toolStartTag = "\n<${toolTagName} name=\"${toolName}\">"
                                                        emittedAny = true
                                                        emit(toolStartTag)
                                                        receivedContent.append(toolStartTag)

                                                        currentToolParser = StreamingJsonXmlConverter()
                                                        isInToolCall = true

                                                        val input = contentBlock.optJSONObject("input")
                                                        if (input != null) {
                                                            val events = currentToolParser!!.feed(input.toString())
                                                            events.forEach { event ->
                                                                when (event) {
                                                                    is StreamingJsonXmlConverter.Event.Tag -> {
                                                                        emit(event.text)
                                                                        receivedContent.append(event.text)
                                                                    }
                                                                    is StreamingJsonXmlConverter.Event.Content -> {
                                                                        emit(event.text)
                                                                        receivedContent.append(event.text)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            "thinking" -> {
                                                val thinkingStartTag = "\n<think>"
                                                emittedAny = true
                                                emit(thinkingStartTag)
                                                receivedContent.append(thinkingStartTag)
                                                isInThinkingBlock = true

                                                val initialThinking = contentBlock.optString("thinking", "")
                                                if (initialThinking.isNotEmpty()) {
                                                    tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(initialThinking))
                                                    onTokensUpdated(
                                                        tokenCacheManager.totalInputTokenCount,
                                                        tokenCacheManager.cachedInputTokenCount,
                                                        tokenCacheManager.outputTokenCount
                                                    )
                                                    emit(initialThinking)
                                                    receivedContent.append(initialThinking)
                                                }
                                            }
                                            "redacted_thinking" -> {
                                            }
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = jsonResponse.optJSONObject("delta")
                                    if (delta != null) {
                                        val deltaType = delta.optString("type", "")
                                        if (deltaType == "text_delta" || delta.has("text")) {
                                            val content = delta.optString("text", "")
                                            if (content.isNotEmpty()) {
                                                emittedAny = true
                                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                                                onTokensUpdated(
                                                    tokenCacheManager.totalInputTokenCount,
                                                    tokenCacheManager.cachedInputTokenCount,
                                                    tokenCacheManager.outputTokenCount
                                                )
                                                emit(content)
                                                receivedContent.append(content)
                                            }
                                        } else if (isInThinkingBlock && (deltaType == "thinking_delta" || delta.has("thinking"))) {
                                            val thinking = delta.optString("thinking", "")
                                            if (thinking.isNotEmpty()) {
                                                emittedAny = true
                                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(thinking))
                                                onTokensUpdated(
                                                    tokenCacheManager.totalInputTokenCount,
                                                    tokenCacheManager.cachedInputTokenCount,
                                                    tokenCacheManager.outputTokenCount
                                                )
                                                emit(thinking)
                                                receivedContent.append(thinking)
                                            }
                                        } else if (enableToolCall && isInToolCall && currentToolParser != null && deltaType == "input_json_delta") {
                                            val partialJson = delta.optString("partial_json", "")
                                            if (partialJson.isNotEmpty()) {
                                                val events = currentToolParser!!.feed(partialJson)
                                                events.forEach { event ->
                                                    when (event) {
                                                        is StreamingJsonXmlConverter.Event.Tag -> {
                                                            emit(event.text)
                                                            receivedContent.append(event.text)
                                                        }
                                                        is StreamingJsonXmlConverter.Event.Content -> {
                                                            emit(event.text)
                                                            receivedContent.append(event.text)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (isInToolCall && currentToolParser != null) {
                                        val events = currentToolParser!!.flush()
                                        events.forEach { event ->
                                            when (event) {
                                                is StreamingJsonXmlConverter.Event.Tag -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                                is StreamingJsonXmlConverter.Event.Content -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                            }
                                        }
                                        val toolTagName =
                                            requireNotNull(currentToolTagName) { "Missing Claude tool XML tag name" }
                                        val toolEndTag = "\n</${toolTagName}>\n"
                                        emit(toolEndTag)
                                        receivedContent.append(toolEndTag)

                                        isInToolCall = false
                                        currentToolParser = null
                                        currentToolTagName = null
                                    } else if (isInThinkingBlock) {
                                        val thinkingEndTag = "</think>\n"
                                        emit(thinkingEndTag)
                                        receivedContent.append(thinkingEndTag)
                                        isInThinkingBlock = false
                                    }
                                }
                                "message_delta" -> {
                                    applyAnthropicUsage(
                                        usage = jsonResponse.optJSONObject("usage"),
                                        onTokensUpdated = onTokensUpdated,
                                        source = "message_delta",
                                        overwriteOutputTokens = true
                                    )
                                }
                                "message_stop" -> {
                                    if (isInToolCall && currentToolParser != null) {
                                        val events = currentToolParser!!.flush()
                                        events.forEach { event ->
                                            when (event) {
                                                is StreamingJsonXmlConverter.Event.Tag -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                                is StreamingJsonXmlConverter.Event.Content -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                            }
                                        }
                                        val toolTagName =
                                            requireNotNull(currentToolTagName) { "Missing Claude tool XML tag name" }
                                        val toolEndTag = "\n</${toolTagName}>\n"
                                        emit(toolEndTag)
                                        receivedContent.append(toolEndTag)
                                        isInToolCall = false
                                        currentToolParser = null
                                        currentToolTagName = null
                                    }
                                    if (isInThinkingBlock) {
                                        val thinkingEndTag = "</think>\n"
                                        emit(thinkingEndTag)
                                        receivedContent.append(thinkingEndTag)
                                        isInThinkingBlock = false
                                    }
                                    break
                                }
                            }
                        }

                        if (!emittedAny && nonSseJsonLinesBuffer.isNotBlank()) {
                            val buffered = nonSseJsonLinesBuffer.toString().trim()
                            AppLogger.w(
                                "AIService",
                                "Claude流式返回疑似JSON/JSONL(无data:前缀），尝试回退解析。preview=${buffered.take(200)}"
                            )

                            // 先尝试整体当成一个JSON对象解析
                            val wholeJson = runCatching { JSONObject(buffered) }.getOrNull()
                            if (wholeJson != null) {
                                val resultText = parseAnthropicNonStreaming(wholeJson)
                                    .ifBlank { parseOpenAiNonStreaming(wholeJson) }
                                if (resultText.isNotBlank()) {
                                    emittedAny = true
                                    emit(resultText)
                                    receivedContent.append(resultText)
                                    tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(resultText))
                                }
                                val usageApplied = applyAnthropicUsage(
                                    usage = wholeJson.optJSONObject("usage"),
                                    onTokensUpdated = onTokensUpdated,
                                    source = "buffered_json_fallback",
                                    overwriteOutputTokens = true
                                )
                                if (resultText.isNotBlank() && !usageApplied) {
                                    onTokensUpdated(
                                        tokenCacheManager.totalInputTokenCount,
                                        tokenCacheManager.cachedInputTokenCount,
                                        tokenCacheManager.outputTokenCount
                                    )
                                }
                            } else {
                                // 再尝试逐行解析（JSONL），优先支持 OpenAI-style delta
                                buffered.lineSequence().forEach { jsonLine ->
                                    val t = jsonLine.trim()
                                    if (!t.startsWith("{")) return@forEach
                                    val obj = runCatching { JSONObject(t) }.getOrNull() ?: return@forEach
                                    val choices = obj.optJSONArray("choices") ?: return@forEach
                                    val first = choices.optJSONObject(0) ?: return@forEach
                                    val delta = first.optJSONObject("delta") ?: return@forEach
                                    val content = delta.optString("content", "")
                                    if (content.isNotBlank()) {
                                        emittedAny = true
                                        tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                                        onTokensUpdated(
                                            tokenCacheManager.totalInputTokenCount,
                                            tokenCacheManager.cachedInputTokenCount,
                                            tokenCacheManager.outputTokenCount
                                        )
                                        emit(content)
                                        receivedContent.append(content)
                                    }
                                }
                            }
                        }

                        if (!emittedAny && previewTrim.isNotEmpty() && looksLikeJson) {
                            AppLogger.w("AIService", "Claude流式响应未解析到任何内容，可能不是SSE，preview=${previewTrim.take(200)}")
                        }
                    } finally {
                        response.close()
                        AppLogger.d("AIService", "【Claude】关闭响应连�?
                    }
                }

                AppLogger.d("AIService", "【Claude】请求成功完了）
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
            } finally {
                activeCall = null
                activeResponse = null
            }
        }

        lastException?.let { ex ->
            AppLogger.e("AIService", "【Claude】重试失败，请检查网络连�? ex)
        } ?: AppLogger.e("AIService", "【Claude】重试失败，请检查网络连�?
        throw IOException(
            context.getString(
                R.string.openai_error_connection_timeout,
                maxRetries,
                lastException?.message ?: context.getString(R.string.provider_error_network_interrupted)
            )
        )
        }
        return responseStream.withEventChannel(eventChannel)
    }

    /**
     * 获取模型列表 注意：此方法直接调用ModelListFetcher获取模型列表
     * @return 模型列表结果
     */
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        // 调用ModelListFetcher获取模型列表
        return ModelListFetcher.getModelsList(
            context = context,
            apiKey = apiKeyProvider.getApiKey(),
            apiEndpoint = apiEndpoint,
            apiProviderType = providerType
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
                onTokensUpdated = { _, _, _ -> },
                onNonFatalError = {},
                enableRetry = false
            )

            // 消耗流以确保连接有效，            // �?Hi" 的响应应该很短，所以这会很快完成�?           stream.collect { _ -> }

            Result.success(context.getString(R.string.openai_connection_success))
        } catch (e: Exception) {
            AppLogger.e("AIService", "连接测试失败", e)
            Result.failure(IOException(context.getString(R.string.openai_connection_test_failed, e.message ?: ""), e))
        }
    }
}
