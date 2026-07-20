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

/**
 * 共享 OkHttpClient — PERF-56。连接池复用,省 TLS 握手 (每请求省 ~100-300ms)。
 * OpenAIApi / DoubaoApi 共用同一个 client 实例。
 */
private val sharedHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

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
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody.toRequestBody(mediaType))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            sharedHttpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (response.code == HttpURLConnection.HTTP_OK) {
                    extractContentFromResponse(respBody)
                } else {
                    "Error: HTTP ${response.code} - ${respBody.take(200)}"
                }
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
            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            )
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)
            val requestBody = body.toString()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody.toRequestBody(mediaType))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            sharedHttpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (response.code == HttpURLConnection.HTTP_OK) {
                    extractContentFromResponse(respBody)
                } else {
                    "Error: HTTP ${response.code} - ${respBody.take(200)}"
                }
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
