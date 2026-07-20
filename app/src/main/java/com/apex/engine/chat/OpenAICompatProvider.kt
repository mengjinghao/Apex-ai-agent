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
 *
 * 工具调用：当 [stream] 收到非空 [tools] 参数时，会按 OpenAI function-calling
 * 协议将工具元信息序列化进 request body 的 `tools` 字段，并把 `tool_choice`
 * 设为 `"auto"`，让模型自行决定是否发起工具调用。返回的 [StreamEvent.ToolCallEvent]
 * 由调用方（如 [com.apex.ui.features.chat.ChatViewModel]）消费。
 *
 * PERF-38: 全局共享 [SHARED_CLIENT]，确保 Hilt 注入的 Provider 与
 * ServiceLocator 通过 ChatEngine 默认构造器创建的 Provider 复用同一个
 * OkHttp 客户端 —— 即同一个 ConnectionPool + 同一个 Dispatcher 线程池，
 * 避免重复创建带来的连接/线程浪费。
 */
class OpenAICompatProvider : LLMProvider {

    private val client: OkHttpClient = SHARED_CLIENT

    private var currentCall: okhttp3.Call? = null
    private val cancelled = AtomicBoolean(false)

    companion object {
        /**
         * 进程级单例 OkHttpClient —— 所有 [OpenAICompatProvider] 实例共享。
         * lazy 确保首次构造发生在实际使用时（而非 class load），且线程安全。
         * 配置 pingInterval=40s 维持 keepalive，避免空闲连接被对端关闭。
         */
        private val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(40, TimeUnit.SECONDS)  // keepalive
                .build()
        }
    }

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
            messagesArray.put(when {
                // role=tool：OpenAI 协议要求 tool_call_id + content
                msg.isTool -> JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", msg.toolCallId ?: "")
                    put("content", msg.content)
                }
                // role=assistant 且本回合触发了工具调用：序列化 tool_calls 数组
                msg.isAssistant && !msg.toolCallsJson.isNullOrBlank() -> JSONObject().apply {
                    put("role", "assistant")
                    // content 可能为空字符串——按协议应传 null 而非空串
                    put("content", if (msg.content.isEmpty()) null as Any? else msg.content)
                    val toolCallsArr = try {
                        JSONArray(msg.toolCallsJson)
                    } catch (_: Exception) {
                        JSONArray()
                    }
                    put("tool_calls", toolCallsArr)
                }
                else -> JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
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
            // OpenAI function-calling 工具列表（仅在调用方提供了工具时才注入）
            if (tools.isNotEmpty()) {
                val toolsArray = JSONArray()
                for (meta in tools) {
                    val functionSpec = JSONObject().apply {
                        put("name", meta.id)
                        put("description", meta.description)
                        // parameters 是 JSON Schema 字符串；解析为 JSONObject 后嵌入，
                        // 解析失败时退化为空对象（OpenAI 要求 parameters 必须是对象）。
                        val paramsObj = try {
                            JSONObject(if (meta.parameters.isBlank()) "{}" else meta.parameters)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        put("parameters", paramsObj)
                    }
                    toolsArray.put(JSONObject().apply {
                        put("type", "function")
                        put("function", functionSpec)
                    })
                }
                put("tools", toolsArray)
                put("tool_choice", "auto")
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
