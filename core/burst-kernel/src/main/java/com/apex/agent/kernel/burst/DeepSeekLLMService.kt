package com.apex.agent.kernel.burst

import com.apex.agent.plugins.burst.base.ChatMessage
import com.apex.agent.plugins.burst.base.ILLMService
import com.apex.agent.plugins.burst.base.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

            val escaped = prompt
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t")

            val reasoningBlock = reasoningEffort?.let { ",\"reasoning_effort\": \"$it\"" } ?: ""

            val body = """
                {
                    "model": "$modelName",
                    "messages": [{"role": "user", "content": "$escaped"}],
                    "temperature": $temperature,
                    "max_tokens": ${maxTokens.coerceAtMost(this@DeepSeekLLMService.maxTokens)}
                    $reasoningBlock
                }
            """.trimIndent()

            OutputStreamWriter(connection.outputStream).use {
                it.write(body); it.flush()
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

            val escaped = prompt
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t")

            val reasoningBlock = reasoningEffort?.let { ",\"reasoning_effort\": \"$it\"" } ?: ""

            val body = """
                {
                    "model": "$modelName",
                    "messages": [{"role": "user", "content": "$escaped"}],
                    "temperature": $temperature,
                    "max_tokens": ${maxTokens.coerceAtMost(this@DeepSeekLLMService.maxTokens)},
                    "stream": true
                    $reasoningBlock
                }
            """.trimIndent()

            OutputStreamWriter(connection.outputStream).use {
                it.write(body); it.flush()
            }

            val reader = connection.inputStream.bufferedReader()
            var continueReading = true
            while (continueReading) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val contentMatch = "\"content\"\\s*:\\s*\"([^\"]*)\"".toRegex().find(data)
                    contentMatch?.let {
                        val token = it.groupValues[1]
                            .replace("\\n", "\n").replace("\\\"", "\"")
                        if (token.isNotEmpty()) {
                            continueReading = onToken(token)
                        }
                    }
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

            val msgs = messages.joinToString(",") { msg ->
                val escaped = msg.content
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\t", "\\t")
                """{"role": "${msg.role}", "content": "$escaped"}"""
            }

            val reasoningBlock = reasoningEffort?.let { ",\"reasoning_effort\": \"$it\"" } ?: ""

            val body = """
                {
                    "model": "$modelName",
                    "messages": [$msgs],
                    "temperature": $temperature,
                    "max_tokens": ${maxTokens.coerceAtMost(this@DeepSeekLLMService.maxTokens)}
                    $reasoningBlock
                }
            """.trimIndent()

            OutputStreamWriter(connection.outputStream).use {
                it.write(body); it.flush()
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
        val nullContent = "\"content\":\\s*null".toRegex()
        if (nullContent.containsMatchIn(response)) return ""

        val contentPattern = "\"content\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val match = contentPattern.find(response)
        return match?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\t", "\t")
            ?: "No content"
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
