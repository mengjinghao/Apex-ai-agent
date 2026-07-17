package com.apex.ai.service

import com.apex.ai.data.ApiConfig
import com.apex.ai.data.ChatMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAIProvider(private val config: ApiConfig) : AIService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var currentCall: Call? = null
    private val cancelled = AtomicBoolean(false)

    override suspend fun sendMessage(messages: List<ChatMessage>, onChunk: (String) -> Unit): String {
        if (config.apiKey.isEmpty()) {
            throw IllegalStateException("API Key 未配置")
        }

        val jsonBody = buildRequestBody(messages)
        val url = normalizeUrl(config.endpoint) + "/chat/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            currentCall = call

            cont.invokeOnCancellation {
                cancelled.set(true)
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (!cancelled.get()) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        cont.resumeWithException(
                            java.io.IOException("HTTP ${response.code}: $errorBody")
                        )
                        return
                    }

                    try {
                        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                        val fullResponse = StringBuilder()

                        while (!cancelled.get()) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.substring(6)
                                if (data.trim() == "[DONE]") break

                                try {
                                    val json = JSONObject(data)
                                    val choices = json.optJSONArray("choices")
                                    if (choices != null && choices.length() > 0) {
                                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                                        if (delta != null) {
                                            val content = delta.optString("content", "")
                                            if (content.isNotEmpty()) {
                                                fullResponse.append(content)
                                                onChunk(content)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Skip malformed JSON
                                }
                            }
                        }

                        reader.close()
                        response.close()
                        cont.resume(fullResponse.toString())
                    } catch (e: Exception) {
                        if (!cancelled.get()) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private fun buildRequestBody(messages: List<ChatMessage>): String {
        val messagesArray = JSONArray()

        // System prompt
        if (config.systemPrompt.isNotEmpty()) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", config.systemPrompt)
            })
        }

        // Chat history
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
        }

        return json.toString()
    }

    private fun normalizeUrl(endpoint: String): String {
        var url = endpoint.trim()
        if (url.endsWith("/")) url = url.dropLast(1)
        if (url.endsWith("/chat/completions")) url = url.dropLast("/chat/completions".length)
        return url
    }

    override fun cancel() {
        cancelled.set(true)
        currentCall?.cancel()
    }
}
