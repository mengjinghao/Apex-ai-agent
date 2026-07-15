package com.apex.core.tools.adapters

import com.apex.core.tools.ToolAdapter
import com.apex.core.tools.ToolParameter
import com.apex.core.tools.ToolResultData
import com.apex.core.tools.HttpResponseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 外部API调用工具适配器，支持调用各种外部API服务
 * 优化版本：添加连接池、请求缓存、重试机制、超时配置等功能
 */
class ExternalApiToolAdapter : ToolAdapter {

    // 配置化的HTTP客户�?
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    // 请求缓存
    private val requestCache = ConcurrentHashMap<String, CachedResponse>()
        private val MAX_CACHE_SIZE = 100
    private val CACHE_EXPIRE_TIME = 10 * 60 * 1000L // 10分钟
    override fun getName(): String {
        return "external_api"
    }

    override fun getDescription(): String {
        return "调用外部API服务，支持GET、POST、PUT、DELETE、PATCH等HTTP方法，包含连接池、请求缓存、重试机�?
    }

    override suspend fun execute(parameters: Map<String, Any>): ToolResultData = withContext(Dispatchers.IO) {
        val url = parameters["url"] as? String ?: return@withContext HttpResponseData(
            url = "",
            statusCode = 400,
            statusMessage = "Bad Request",
            headers = emptyMap(),
            contentType = "text/plain",
            content = "错误：缺少url参数",
            size = 0
        )
        val method = parameters["method"] as? String ?: "GET"
        val headers = parameters["headers"] as? Map<String, String> ?: emptyMap()
        val body = parameters["body"] as? String
        val contentType = parameters["content_type"] as? String ?: "application/json"
        val useCache = parameters["use_cache"] as? Boolean ?: true
        val timeout = (parameters["timeout"] as? Int ?: 30)
        val maxRetries = (parameters["max_retries"] as? Int ?: 0)
        val followRedirects = parameters["follow_redirects"] as? Boolean ?: true

        try {
            // 检查缓存（仅GET请求�?
    val cacheKey = if (method.uppercase() == "GET") {
                "${method}:${url}:${headers.hashCode()}"
            } else {
                null
            }
        if (useCache && cacheKey != null) {
                requestCache[cacheKey]?.let { cached ->
                    if (System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRE_TIME) {
                        return@withContext cached.response.copy(
                            statusMessage = "[缓存] ${cached.response.statusMessage}"
                        )
                    } else {
                        requestCache.remove(cacheKey)
                    }
                }
            }

            // 执行请求（支持重试）
    val response = executeWithRetry(url, method, headers, body, contentType, timeout, maxRetries, followRedirects)

            // 缓存响应（仅GET请求�?
    if (useCache && cacheKey != null && response.statusCode in 200..299) {
                if (requestCache.size >= MAX_CACHE_SIZE) {
                    val oldestKey = requestCache.keys.firstOrNull()
                    oldestKey?.let { requestCache.remove(it) }
                }
                requestCache[cacheKey] = CachedResponse(response, System.currentTimeMillis())
            }

            response
        } catch (e: IOException) {
            HttpResponseData(
                url = url,
                statusCode = 500,
                statusMessage = "Internal Server Error",
                headers = emptyMap(),
                contentType = "text/plain",
                content = "请求失败，{e.message}",
                size = 0
            )
        } catch (e: Exception) {
            HttpResponseData(
                url = url,
                statusCode = 500,
                statusMessage = "Internal Server Error",
                headers = emptyMap(),
                contentType = "text/plain",
                content = "错误，{e.message}",
                size = 0
            )
        }
    }

    override fun getParameters(): List<ToolParameter> {
        return listOf(
            ToolParameter("url", "string", "API地址", true),
            ToolParameter("method", "string", "HTTP方法：GET, POST, PUT, DELETE, PATCH", false, "GET"),
            ToolParameter("headers", "object", "请求头（可选）", false),
            ToolParameter("body", "string", "请求体（POST/PUT/PATCH需要）", false),
            ToolParameter("content_type", "string", "内容类型（默认：application/json�? false, "application/json"),
            ToolParameter("use_cache", "boolean", "是否使用请求缓存（仅GET请求，默认：true�? false, true),
            ToolParameter("timeout", "int", "超时时间（秒，默认：30�? false, 30),
            ToolParameter("max_retries", "int", "最大重试次数（默认，，不重试着�?false, 0),
            ToolParameter("follow_redirects", "boolean", "是否跟随重定向（默认：true�? false, true)
        )
    }

    override fun isAvailable(): Boolean {
        return true
    }
        private suspend fun executeWithRetry(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String,
        timeout: Int,
        maxRetries: Int,
        followRedirects: Boolean
    ): HttpResponseData = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var retryCount = 0

        // 创建自定义超时的客户�?
    val customClient = client.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .build()

        while (retryCount <= maxRetries) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)

                // 添加请求�?               headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                // 添加请求�?
    val request = if (body != null && 
                    (method.uppercase() == "POST" || 
                     method.uppercase() == "PUT" || 
                     method.uppercase() == "PATCH")) {
                    val requestBody = body.toRequestBody(contentType.toMediaType())
                    requestBuilder.method(method.uppercase(), requestBody)
                } else {
                    requestBuilder.method(method.uppercase(), null)
                }.build()
        val response = customClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        val responseHeaders = mutableMapOf<String, String>()
                response.headers.forEach { (name, value) ->
                    responseHeaders[name] = value
                }
        val responseContentType = response.header("Content-Type") ?: "text/plain"

                // 检查是否需要重试（仅对5xx错误和网络错误）
    if (response.code in 500..599 && retryCount < maxRetries) {
                    retryCount++
                    // 等待时间递增
    val waitTime = (1000 * retryCount).toLong()
                    kotlinx.coroutines.delay(waitTime)
                    continue
                }

                return@withContext HttpResponseData(
                    url = url,
                    statusCode = response.code,
                    statusMessage = response.message,
                    headers = responseHeaders,
                    contentType = responseContentType,
                    content = responseBody,
                    size = responseBody.length
                )

            } catch (e: IOException) {
                lastException = e
                if (retryCount < maxRetries) {
                    retryCount++
                    val waitTime = (1000 * retryCount).toLong()
                    kotlinx.coroutines.delay(waitTime)
                } else {
                    throw e
                }
            }
        }

        // 如果所有重试都失败
    throw lastException ?: IOException("请求失败")
    }
        private data class CachedResponse(
        val response: HttpResponseData,
        val timestamp: Long
    )
}
