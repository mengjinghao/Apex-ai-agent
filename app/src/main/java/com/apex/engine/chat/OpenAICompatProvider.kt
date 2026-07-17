package com.apex.engine.chat

import com.apex.core.model.ChatMessage
import com.apex.core.model.ToolMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI 兼容 Provider — 支持 OpenAI/DeepSeek/Kimi/智谱/Ollama 等。
 * 使用 OkHttp 发送 SSE 流式请求。
 */
class OpenAICompatProvider : LLMProvider {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentCall: okhttp3.Call? = null
    private val cancelled = AtomicBoolean(false)

    override fun stream(
        messages: List<ChatMessage>,
        tools: List<ToolMetadata>,
        config: LLMRequestConfig
    ): Flow<StreamEvent> = flow {
        if (config.apiKey.isEmpty()) {
            emit(StreamEvent.Error("API Key 未配置"))
            return@flow
        }

        cancelled.set(false)
        val url = normalizeUrl(config.endpoint) + "/chat/completions"
        val body = buildRequestBody(messages, tools, config)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        currentCall = call

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                emit(StreamEvent.Error("HTTP ${response.code}: $errBody"))
                response.close()
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            while (!cancelled.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")
                        if (delta != null) {
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                emit(StreamEvent.Chunk(content))
                            }
                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null) {
                                for (i in 0 until toolCalls.length()) {
                                    val tc = toolCalls.getJSONObject(i)
                                    val fn = tc.optJSONObject("function")
                                    if (fn != null) {
                                        emit(StreamEvent.ToolCallEvent(
                                            id = tc.optString("id", ""),
                                            name = fn.optString("name", ""),
                                            arguments = fn.optString("arguments", "")
                                        ))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 跳过解析错误的行
                }
            }
            reader.close()
            response.close()
            if (!cancelled.get()) emit(StreamEvent.Done)
        } catch (_: CancellationException) {
            // 协程被取消
        } catch (e: Exception) {
            if (!cancelled.get()) {
                emit(StreamEvent.Error(e.message ?: "网络请求失败"))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        tools: List<ToolMetadata>,
        config: LLMRequestConfig
    ): String {
        val messagesArray = JSONArray()
        for (msg in messages) {
            if (msg.isStreaming) continue
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val json = JSONObject().apply {
            put("model", config.model)
            put("messages", messagesArray)
            put("stream", true)
            put("temperature", config.temperature)
            if (config.maxTokens != null) {
                put("max_tokens", config.maxTokens)
            }
        }

        return json.toString()
    }

    private fun normalizeUrl(endpoint: String): String {
        var url = endpoint.trim()
        while (url.endsWith("/")) url = url.dropLast(1)
        if (url.endsWith("/chat/completions")) {
            url = url.dropLast("/chat/completions".length)
        }
        return url
    }

    override fun cancel() {
        cancelled.set(true)
        currentCall?.cancel()
    }
}
