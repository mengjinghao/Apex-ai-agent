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

class DeepSeekLLMService : ILLMService {
    private var apiKey: String = ""
    private var modelName: String = "deepseek-v4-pro"
    private var initialized = false
    private var temperature: Float = 0.3f
    private var maxTokens: Int = 8192
    private var reasoningEffort: String? = "max"

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"
    }

    override fun isAvailable(): Boolean = initialized && apiKey.isNotBlank()

    override fun getUnavailableReason(): String {
        return when {
            !initialized -> "DeepSeek LLM not initialized"
            apiKey.isBlank() -> "DeepSeek API key not configured"
            else -> "Unknown"
        }
    }

    override fun initialize(config: LLMConfig): Boolean {
        if (config.modelPath.isBlank()) return false
        apiKey = config.modelPath
        temperature = config.temperature
        maxTokens = config.maxTokens.coerceAtLeast(2048)
        modelName = if (config.nCtx > 65536) "deepseek-v4-pro" else "deepseek-v4-flash"
        reasoningEffort = if (config.nCtx > 65536) "max" else null
        initialized = true
        return true
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext fallbackGenerate(prompt)
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.doOutput = true

            val body = JSONObject()
                .put("model", modelName)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
                .put("temperature", temperature)
                .put("max_tokens", maxTokens.coerceAtMost(this@DeepSeekLLMService.maxTokens))
            if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort)
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody); it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = connection.inputStream.bufferedReader().readText()
                extractContent(resp)
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                "Error: HTTP ${connection.responseCode} - ${err.take(200)}"
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
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.doOutput = true

            val body = JSONObject()
                .put("model", modelName)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
                .put("temperature", temperature)
                .put("max_tokens", maxTokens.coerceAtMost(this@DeepSeekLLMService.maxTokens))
                .put("stream", true)
            if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort)
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
        if (!isAvailable()) return@withContext fallbackChat(messages)
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.doOutput = true

            val msgs = JSONArray()
            for (msg in messages) {
                msgs.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }

            val body = JSONObject()
                .put("model", modelName)
                .put("messages", msgs)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens.coerceAtMost(this@DeepSeekLLMService.maxTokens))
            if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort)
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody); it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = connection.inputStream.bufferedReader().readText()
                extractContent(resp)
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                "Error: HTTP ${connection.responseCode} - ${err.take(200)}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun countTokens(text: String): Int = text.length / 2

    override fun release() {
        initialized = false
        apiKey = ""
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

    private fun fallbackGenerate(prompt: String): String {
        return buildString {
            appendLine("DeepSeek LLM not available. Set API key via LLMConfig.modelPath.")
            appendLine("Prompt length: ${prompt.length} chars")
        }
    }

    private fun fallbackChat(messages: List<ChatMessage>): String {
        val lastUserMsg = messages.lastOrNull { it.role == "user" }?.content ?: ""
        return fallbackGenerate(lastUserMsg)
    }
}
