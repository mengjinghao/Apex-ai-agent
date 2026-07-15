package com.apex.agent.core.normal.meme.web

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 网络搜梗抽象层
 *
 * 支持多搜索引擎查询梗的含义、流行度、用法。
 * 每个引擎实现 searchMeme 方法，返回标准化的 MemeSearchResult。
 *
 * 灵感来源：web-search 技能（z-ai-web-dev-sdk web_search 函数）
 * 适配：Android Kotlin 环境通过 OkHttp 直接调用公开搜索建议 API
 */

/**
 * 搜索结果项
 */
data class MemeSearchResultItem(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String,
    val date: String? = null
)

/**
 * 搜索结果
 */
data class MemeSearchResult(
    val query: String,
    val engine: String,
    val items: List<MemeSearchResultItem>,
    val totalFound: Int,
    val searchTimeMs: Long,
    val success: Boolean,
    val error: String? = null,
    val fromCache: Boolean = false
)

/**
 * 热搜条目
 */
data class HotSearchItem(
    val rank: Int,
    val title: String,
    val hotScore: Long,
    val url: String? = null,
    val category: String = "general"
)

/**
 * 搜索引擎接口
 */
interface WebSearchProvider {
    val name: String
    val supported: Boolean

    /**
     * 搜索梗的含义/解释/用法
     */
    suspend fun searchMeme(query: String, num: Int = 10): MemeSearchResult

    /**
     * 搜索建议（自动补全）
     */
    suspend fun suggest(query: String): List<String>
}

/**
 * 搜索引擎注册表
 *
 * 管理多个搜索引擎，支持故障转移和负载均衡
 */
class WebSearchProviderRegistry {

    private val providers = mutableListOf<WebSearchProvider>()
        private val failureCount = ConcurrentHashMap<String, Int>()
        private val lastFailureTime = ConcurrentHashMap<String, Long>()
        private val maxFailures = 3
    private val cooldownMs = 5 * 60_000L  // 5 分钟冷却

    /**
     * 注册引擎
     */
    fun register(provider: WebSearchProvider) {
        providers.add(provider)
    }

    /**
     * 获取可用引擎列表（按优先级，跳过冷却中的）
     */
    fun getAvailableProviders(): List<WebSearchProvider> {
        val now = System.currentTimeMillis()
        return providers.filter { p ->
            val failures = failureCount[p.name] ?: 0
        val lastFail = lastFailureTime[p.name] ?: 0
            if (failures >= maxFailures && now - lastFail < cooldownMs) {
                false  // 冷却中
            } else if (failures >= maxFailures && now - lastFail >= cooldownMs) {
                // 冷却结束，重置
        failureCount[p.name] = 0
                true
            } else {
                true
            }
        }
    }

    /**
     * 记录成功
     */
    fun recordSuccess(providerName: String) {
        failureCount[providerName] = 0
    }

    /**
     * 记录失败
     */
    fun recordFailure(providerName: String) {
        failureCount[providerName] = (failureCount[providerName] ?: 0) + 1
        lastFailureTime[providerName] = System.currentTimeMillis()
    }

    /**
     * 获取所有引擎
     */
    fun listProviders(): List<WebSearchProvider> = providers.toList()

    /**
     * 获取引擎状态
     */
    fun getProviderStatus(): Map<String, ProviderStatus> {
        val now = System.currentTimeMillis()
        return providers.associate { p ->
            val failures = failureCount[p.name] ?: 0
        val lastFail = lastFailureTime[p.name] ?: 0
            val status = when {
                failures >= maxFailures && now - lastFail < cooldownMs ->
                    ProviderStatus.COOLING_DOWN(failures, cooldownMs - (now - lastFail))
        failures > 0 -> ProviderStatus.DEGRADED(failures)
        else -> ProviderStatus.HEALTHY
            }
        p.name to status
        }
    }
        sealed class ProviderStatus {
        data object HEALTHY : ProviderStatus()
        data class DEGRADED(val failures: Int) : ProviderStatus()
        data class COOLING_DOWN(val failures: Int, val remainingMs: Long) : ProviderStatus()
    }
}

/**
 * HTTP 客户端工具
 */
object MemeHttpUtil {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
        private val requestCounter = AtomicLong(0)

    /**
     * GET 请求
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult {
        return try {
            val requestBuilder = Request.Builder().url(url).get()
            // 模拟浏览器 UA，避免被反爬
        requestBuilder.addHeader("User-Agent", defaultUserAgent())
        requestBuilder.addHeader("Accept", "application/json, text/plain, */*")
        requestBuilder.addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: ""
        HttpResult(
                success = response.isSuccessful,
                statusCode = response.code,
                body = body,
                url = url
            )
        } catch (e: Exception) {
            HttpResult(success = false, statusCode = -1, body = "", url = url, error = e.message)
        }
    }

    /**
     * URL 编码
     */
    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * 默认 UA
     */
    private fun defaultUserAgent(): String {
        val id = requestCounter.incrementAndGet()
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

data class HttpResult(
    val success: Boolean,
    val statusCode: Int,
    val body: String,
    val url: String,
    val error: String? = null
)

/**
 * JSON 工具（基于 org.json，Android 内置）
 */
object MemeJsonUtil {
    /**
     * 安全解析 JSON 数组
     */
    fun parseArray(json: String): JSONArray? {
        return try {
            // 去除可能的 JSONP 包装
    val cleaned = cleanJsonp(json)
        JSONArray(cleaned)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 安全解析 JSON 对象
     */
    fun parseObject(json: String): JSONObject? {
        return try {
            val cleaned = cleanJsonp(json)
        JSONObject(cleaned)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清理 JSONP 包装，如 callback({...})
     */
    private fun cleanJsonp(json: String): String {
        val trimmed = json.trim()
        // 如果是 JSONP 格式 callback(...)
        if (trimmed.contains("(") && trimmed.endsWith(")")) {
            val start = trimmed.indexOf("(")
        val end = trimmed.lastIndexOf(")")
        if (start in 0..end) {
                return trimmed.substring(start + 1, end)
            }
        }
        return trimmed
    }
}
