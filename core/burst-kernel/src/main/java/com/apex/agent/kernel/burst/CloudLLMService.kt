package com.apex.agent.kernel.burst

import com.apex.agent.plugins.burst.base.ChatMessage
import com.apex.agent.plugins.burst.base.ILLMService
import com.apex.agent.plugins.burst.base.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class CloudLLMService(
    private val apiEndpoint: String,
    private val apiKey: String,
    private val modelName: String,
    private val defaultTemperature: Float = 0.1f,
    private val defaultMaxTokens: Int = 512,
    private val timeoutMs: Long = 5000L
) : ILLMService {
    private var initialized = false

    override fun isAvailable(): Boolean = initialized && apiKey.isNotBlank()

    override fun getUnavailableReason(): String = when {
        !initialized -> "Cloud LLM not initialized"
        apiKey.isBlank() -> "API key not configured"
        else -> "Unknown"
    }

    override fun initialize(config: LLMConfig): Boolean {
        if (apiKey.isBlank()) return false
        initialized = true
        return true
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "Error: Cloud LLM not available"
        try {
            val url = URL(apiEndpoint)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = (timeoutMs / 2).toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.doOutput = true

            val body = JSONObject()
                .put("model", modelName)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
                .put("temperature", defaultTemperature)
                .put("max_tokens", maxTokens.coerceAtMost(defaultMaxTokens))
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody); it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = connection.inputStream.bufferedReader().readText()
                extractContent(resp)
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                "Error: HTTP ${connection.responseCode}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override suspend fun generateStream(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        try {
            val url = URL(apiEndpoint)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.connectTimeout = (timeoutMs / 2).toInt()
            connection.readTimeout = timeoutMs.toInt() * 2
            connection.doOutput = true

            val body = JSONObject()
                .put("model", modelName)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
                .put("temperature", defaultTemperature)
                .put("max_tokens", maxTokens.coerceAtMost(defaultMaxTokens))
                .put("stream", true)
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody); it.flush()
            }

            val reader = connection.inputStream.bufferedReader()
            var continueReading = true
            while (continueReading) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val obj = JSONObject(data)
                    val delta = obj
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                    if (delta != null && delta.has("content") && !delta.isNull("content")) {
                        val token = delta.optString("content")
                        if (token.isNotEmpty()) {
                            continueReading = onToken(token)
                        }
                    }
                } catch (_: Exception) {
                    // 忽略无法解析的行
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun chat(messages: List<ChatMessage>, maxTokens: Int): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "Error: Cloud LLM not available"
        try {
            val url = URL(apiEndpoint)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = (timeoutMs / 2).toInt()
            connection.readTimeout = timeoutMs.toInt()
            connection.doOutput = true

            val msgs = JSONArray()
            for (msg in messages) {
                msgs.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }

            val body = JSONObject()
                .put("model", modelName)
                .put("messages", msgs)
                .put("temperature", defaultTemperature)
                .put("max_tokens", maxTokens.coerceAtMost(defaultMaxTokens))
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody); it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = connection.inputStream.bufferedReader().readText()
                extractContent(resp)
            } else {
                "Error: HTTP ${connection.responseCode}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun countTokens(text: String): Int = text.length / 2

    override fun release() {
        initialized = false
    }

    private fun extractContent(response: String): String {
        return try {
            val json = JSONObject(response)
            val message = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            if (message == null) {
                "No content"
            } else if (message.has("content") && message.isNull("content")) {
                ""
            } else if (message.has("content")) {
                message.optString("content")
            } else {
                "No content"
            }
        } catch (_: Exception) {
            "No content"
        }
    }
}
