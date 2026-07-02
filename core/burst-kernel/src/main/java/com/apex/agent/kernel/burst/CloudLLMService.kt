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

            val escaped = prompt
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t")

            val body = """
                {
                    "model": "$modelName",
                    "messages": [{"role": "user", "content": "$escaped"}],
                    "temperature": $defaultTemperature,
                    "max_tokens": ${maxTokens.coerceAtMost(defaultMaxTokens)}
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

            val escaped = prompt
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t")

            val body = """
                {
                    "model": "$modelName",
                    "messages": [{"role": "user", "content": "$escaped"}],
                    "temperature": $defaultTemperature,
                    "max_tokens": ${maxTokens.coerceAtMost(defaultMaxTokens)},
                    "stream": true
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
                    val contentMatch = """"content"\s*:\s*"([^"]*)"""".toRegex().find(data)
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

            val msgs = messages.joinToString(",") { msg ->
                val escaped = msg.content
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\t", "\\t")
                """{"role": "${msg.role}", "content": "$escaped"}"""
            }

            val body = """
                {
                    "model": "$modelName",
                    "messages": [$msgs],
                    "temperature": $defaultTemperature,
                    "max_tokens": ${maxTokens.coerceAtMost(defaultMaxTokens)}
                }
            """.trimIndent()

            OutputStreamWriter(connection.outputStream).use {
                it.write(body); it.flush()
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
}
