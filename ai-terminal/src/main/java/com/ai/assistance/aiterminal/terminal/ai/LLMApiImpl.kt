package com.ai.assistance.aiterminal.terminal.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * OpenAI-compatible Chat Completions client.
 *
 * Security (I-1/I-4): JSON request body is built with `org.json.JSONObject` so
 * that the prompt cannot break out of the JSON string field. The previous
 * implementation used string interpolation (`"content": "$prompt"`) which is
 * vulnerable to JSON injection — a prompt containing a `"` would corrupt the
 * payload and could inject attacker-controlled fields.
 */
class OpenAIApi(private val apiKey: String, private val model: String = "gpt-4o") : LLMAPI {
    companion object {
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpsURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true

            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            )
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.7)
                .put("max_tokens", 500)
                .put("stream", false)
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                extractContentFromResponse(response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                "Error: HTTP $responseCode - ${error.take(200)}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun extractContentFromResponse(response: String): String {
        val contentPattern = "\"content\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val match = contentPattern.find(response)
        return match?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "No content found"
    }
}

/**
 * Doubao (Volcengine Ark) Chat Completions client.
 *
 * Security (I-1/I-4): JSON request body is built with `org.json.JSONObject`
 * (see OpenAIApi doc for rationale).
 */
class DoubaoApi(private val apiKey: String, private val model: String = "doubao-pro") : LLMAPI {
    companion object {
        private const val API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true

            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            )
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)
            val requestBody = body.toString()

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                extractContentFromResponse(response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                "Error: HTTP $responseCode - ${error.take(200)}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun extractContentFromResponse(response: String): String {
        val contentPattern = "\"content\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val match = contentPattern.find(response)
        return match?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "No content found"
    }
}
