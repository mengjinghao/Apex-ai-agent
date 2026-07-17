package com.apex.engine.tools.builtin

import com.apex.core.model.ApexTool
import com.apex.core.model.ToolCategory
import com.apex.core.model.ToolMetadata
import com.apex.core.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** HTTP GET 工具 */
class HttpGetTool : ApexTool {
    override val metadata = ToolMetadata(
        id = "http_get",
        name = "HTTP GET",
        description = "发送 HTTP GET 请求获取网页内容",
        category = ToolCategory.NETWORK,
        isReadOnly = true
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(arguments: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val url = arguments["url"]?.toString()
        if (url.isNullOrEmpty()) return@withContext ToolResult.Error("缺少 url 参数")

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                ToolResult.Success(body.take(10000))  // 限制 10K
            }
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: "HTTP 请求失败")
        }
    }
}
