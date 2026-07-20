package com.ai.assistance.aiterminal.terminal.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

enum class DeepSeekModel(val modelId: String, val description: String) {
    V4_PRO("deepseek-v4-pro", "1.6T MoE, 49B active, best for complex agent tasks"),
    V4_FLASH("deepseek-v4-flash", "284B MoE, 13B active, fast & cheap"),
    V4_PRO_MAX("deepseek-v4-pro-max", "Highest agentic performance variant")
}

enum class ReasoningEffort(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max")
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class DeepSeekToolCall(
    val toolCallId: String,
    val functionName: String,
    val arguments: String
)

/**
 * DeepSeek Chat Completions client (with optional tool-calling).
 *
 * Security (I-1/I-4): the entire request body — including the user prompt,
 * the model id, the reasoning effort, and every tool name / description /
 * parameter value — is now built with `org.json.JSONObject` / `JSONArray`.
 * This replaces the previous manual string-escaping approach which was
 * brittle and could be broken by edge-case prompt content. JSONObject
 * guarantees JSON validity regardless of input.
 */
class DeepSeekApi(
    private val apiKey: String,
    private val model: DeepSeekModel = DeepSeekModel.V4_PRO,
    private val reasoningEffort: ReasoningEffort? = ReasoningEffort.MAX,
    private val maxTokens: Int = 8192,
    private val temperature: Double = 0.3
) : LLMAPI {
    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val TAG = "DeepSeekApi"

        // PERF-56: 共享 OkHttpClient — 连接池复用,省 TLS 握手 (每请求省 ~100-300ms)。
        // 原 HttpURLConnection 每次新建连接 + 握手,批量 LLM 调用场景下开销显著。
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonBody(prompt, null, null).toString()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody.toRequestBody(mediaType))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.code == HttpURLConnection.HTTP_OK) {
                    extractContentFromResponse(body)
                } else {
                    "Error: HTTP ${response.code} - model=${model.modelId}, ${body.take(300)}"
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun generateWithTools(
        prompt: String,
        tools: List<ToolDefinition>,
        toolChoice: String = "auto"
    ): FunctionCallResponse = withContext(Dispatchers.IO) {
        try {
            val toolsJsonArray = buildToolsJsonArray(tools)
            val requestBody = buildJsonBody(prompt, toolsJsonArray, toolChoice).toString()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody.toRequestBody(mediaType))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.code == HttpURLConnection.HTTP_OK) {
                    parseFunctionCallResponse(body)
                } else {
                    FunctionCallResponse(
                        content = "Error: HTTP ${response.code}",
                        toolCalls = emptyList(),
                        isToolCall = false
                    )
                }
            }
        } catch (e: Exception) {
            FunctionCallResponse(
                content = "Error: ${e.message}",
                toolCalls = emptyList(),
                isToolCall = false
            )
        }
    }

    /**
     * Builds the request body as a JSONObject. All interpolated fields (prompt,
     * model id, reasoning effort, tool choice) go through JSONObject.put which
     * guarantees correct JSON escaping.
     *
     * - [toolsJsonArray] is null for the plain `generate()` call.
     * - [toolChoice] is null when no tools are present.
     */
    private fun buildJsonBody(
        prompt: String,
        toolsJsonArray: JSONArray?,
        toolChoice: String?
    ): JSONObject {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)
        )
        val body = JSONObject()
            .put("model", model.modelId)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort.value)
        }
        if (toolsJsonArray != null) {
            body.put("tools", toolsJsonArray)
            if (toolChoice != null) {
                body.put("tool_choice", toolChoice)
            }
        }
        return body
    }

    /**
     * Builds the `tools` JSON array entirely with JSONObject/JSONArray so that
     * tool names, descriptions, and parameter values cannot break out of their
     * JSON fields (I-1: JSON injection).
     */
    private fun buildToolsJsonArray(tools: List<ToolDefinition>): JSONArray {
        val toolsArray = JSONArray()
        for (tool in tools) {
            val properties = JSONObject()
            val required = JSONArray()
            for ((key, value) in tool.parameters) {
                val propObj = JSONObject()
                when (value) {
                    is Map<*, *> -> {
                        val paramMap = value as Map<String, Any?>
                        propObj.put("type", paramMap["type"] ?: "string")
                        propObj.put("description", paramMap["description"] ?: key)
                    }
                    else -> {
                        propObj.put("type", "string")
                        propObj.put("description", value?.toString() ?: key)
                    }
                }
                properties.put(key, propObj)
                required.put(key)
            }
            val parametersObj = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)

            val functionObj = JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", parametersObj)

            val toolObj = JSONObject()
                .put("type", "function")
                .put("function", functionObj)

            toolsArray.put(toolObj)
        }
        return toolsArray
    }

    data class FunctionCallResponse(
        val content: String,
        val toolCalls: List<DeepSeekToolCall>,
        val isToolCall: Boolean
    )

    private fun parseFunctionCallResponse(response: String): FunctionCallResponse {
        val content = extractContentFromResponse(response)
        val toolCalls = mutableListOf<DeepSeekToolCall>()
        try {
            val json = JSONObject(response)
            val message = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            val toolCallsArray = message?.optJSONArray("tool_calls")
            if (toolCallsArray != null) {
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.optJSONObject(i) ?: continue
                    val id = tc.optString("id")
                    val function = tc.optJSONObject("function")
                    val name = function?.optString("name") ?: ""
                    // OpenAI/DeepSeek 协议: arguments 是 escaped JSON 字符串。
                    // opt("arguments")?.toString() 兼容服务端偶尔返回 JSONObject 的情况。
                    val arguments = function?.opt("arguments")?.toString() ?: ""
                    if (id.isNotEmpty() || name.isNotEmpty()) {
                        toolCalls.add(DeepSeekToolCall(
                            toolCallId = id,
                            functionName = name,
                            arguments = arguments
                        ))
                    }
                }
            }
        } catch (_: Exception) {
            // 非 JSON 响应 — 退化为无工具调用
        }
        return FunctionCallResponse(
            content = content,
            toolCalls = toolCalls,
            isToolCall = toolCalls.isNotEmpty()
        )
    }

    private fun extractContentFromResponse(response: String): String {
        try {
            val json = JSONObject(response)
            val message = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            if (message != null) {
                if (message.has("content") && !message.isNull("content")) {
                    return message.optString("content")
                }
                if (message.has("reasoning_content") && !message.isNull("reasoning_content")) {
                    return message.optString("reasoning_content")
                }
            }
        } catch (_: Exception) {
            // 非 JSON — fall through to SSE 流式解析
        }
        return extractFromStreamedResponse(response)
    }

    private fun extractFromStreamedResponse(response: String): String {
        // 即便 stream=false,某些代理/网关会返回 SSE 多行格式。逐行解析为 JSONObject。
        val sb = StringBuilder()
        for (line in response.lines()) {
            if (!line.startsWith("data: ")) continue
            val jsonStr = line.removePrefix("data: ").trim()
            if (jsonStr == "[DONE]") continue
            try {
                val obj = JSONObject(jsonStr)
                val delta = obj
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                if (delta != null && delta.has("content") && !delta.isNull("content")) {
                    sb.append(delta.optString("content"))
                }
            } catch (_: Exception) {
                // 忽略无法解析的行
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else "No content found in response"
    }
}
