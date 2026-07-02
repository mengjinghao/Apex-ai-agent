package com.ai.assistance.apex.engine.tools.impl

import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.parseArgs
import com.ai.assistance.apex.engine.tools.successResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkTool : Tool {
    override val name = "network"
    override val description = "HTTP network operations tool for GET, POST requests and file downloads"
    override val category = "network"
    override val parameters = arrayOf("operation", "url", "body", "headers", "path")
    override val requiresRoot = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(args: String): ExecutionResult {
        val params = parseArgs(args)
        val operation = params["operation"]?.lowercase() ?: "get"
        val url = params["url"]

        if (url.isNullOrEmpty()) {
            return errorResult("URL is required")
        }

        return when (operation) {
            "get", "http_get" -> httpGet(url, params["headers"])
            "post", "http_post" -> httpPost(url, params["body"], params["headers"])
            "download" -> downloadFile(url, params["path"])
            else -> errorResult("Unknown operation: $operation")
        }
    }

    private fun httpGet(url: String, headers: String?): ExecutionResult {
        return try {
            val requestBuilder = Request.Builder().url(url).get()

            headers?.split(";")?.forEach { header ->
                val parts = header.split(":", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.addHeader(parts[0].trim(), parts[1].trim())
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            ExecutionResult().apply {
                exitCode = if (response.isSuccessful) 0 else response.code
                output = body
                error = if (!response.isSuccessful) response.message else ""
                success = response.isSuccessful
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "HTTP GET failed")
        }
    }

    private fun httpPost(url: String, body: String?, headers: String?): ExecutionResult {
        return try {
            val requestBuilder = Request.Builder().url(url)
                .post((body ?: "").toRequestBody())

            headers?.split(";")?.forEach { header ->
                val parts = header.split(":", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.addHeader(parts[0].trim(), parts[1].trim())
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            ExecutionResult().apply {
                exitCode = if (response.isSuccessful) 0 else response.code
                output = responseBody
                error = if (!response.isSuccessful) response.message else ""
                success = response.isSuccessful
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "HTTP POST failed")
        }
    }

    private fun downloadFile(url: String, path: String?): ExecutionResult {
        if (path.isNullOrEmpty()) {
            return errorResult("Path is required for download")
        }

        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return ExecutionResult().apply {
                    exitCode = response.code
                    error = response.message
                    success = false
                }
            }

            val file = File(path)
            file.parentFile?.mkdirs()

            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            ExecutionResult().apply {
                exitCode = 0
                output = "Downloaded to $path (${file.length()} bytes)"
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Download failed")
        }
    }

}