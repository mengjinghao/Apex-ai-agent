package com.apex.core.tools.adapters

import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 极速免费联网搜索引操
 * 基于必应，纯本地请求，无API密钥，极速响应
 */
object QuickSearchEngine {
    private const val TAG = "QuickSearchEngine"

    /**
     * 搜索结果数据类
     */
    data class SearchResult(
        val title: String,
        val summary: String,
        val url: String,
        val source: String = "全网实时搜索"
    )

    /**
     * 搜索响应
     */
    data class SearchResponse(
        val success: Boolean,
        val query: String,
        val time: String,
        val data: List<SearchResult>,
        val errorMessage: String? = null
    )

    // 搜索结果缓存
    private val searchCache = ConcurrentHashMap<String, CachedResult>()
        private const val CACHE_EXPIRE = 10 * 60 * 1000L // 10分钟
    private const val MAX_CACHE_SIZE = 100
    private const val CLEANUP_BATCH_SIZE = 30

    private data class CachedResult(
        val data: SearchResponse,
        val timestamp: Long
    )

    // OkHttp客户端
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 清理过期缓存
     */
    private fun cleanExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = searchCache.filterValues { 
            currentTime - it.timestamp >= CACHE_EXPIRE
        }.keys
        expiredKeys.forEach { searchCache.remove(it) }
        if (expiredKeys.isNotEmpty()) {
            AppLogger.d(TAG, "清理于${expiredKeys.size} 个过期缓字")
        }
    }

    /**
     * 清理超过大小限制的缓字
     */
    private fun cleanOldCacheIfNeeded() {
        if (searchCache.size >= MAX_CACHE_SIZE) {
            cleanExpiredCache()
        if (searchCache.size >= MAX_CACHE_SIZE) {
                // 按时间排序，删除最旧的缓存
    val sortedKeys = searchCache.entries
                    .sortedBy { it.value.timestamp }
                    .take(CLEANUP_BATCH_SIZE)
                    .map { it.key }
        sortedKeys.forEach { searchCache.remove(it) }
        AppLogger.d(TAG, "清理于${sortedKeys.size} 个旧缓存")
            }
        }
    }

    /**
     * 执行极速搜索
     * @param keyword 搜索关键试
     * @param count 返回结果数量，默计为
     * @return 搜索响应
     */
    suspend fun search(keyword: String, count: Int = 6): SearchResponse = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        
        // 边界情况处理：空查询
    if (query.isEmpty()) {
            return@withContext SearchResponse(
                success = false,
                query = "",
                time = getCurrentTime(),
                data = emptyList(),
                errorMessage = "搜索关键词不能为空"
            )
        }
        
        // 边界情况处理：查询过知
    if (query.length < 2) {
            return@withContext SearchResponse(
                success = false,
                query = query,
                time = getCurrentTime(),
                data = emptyList(),
                errorMessage = "搜索关键词过短，请输入至将个字符"
            )
        }
        
        // 边界情况处理：结果数量验试
    val safeCount = count.coerceIn(1, 20)

        // 检查缓字
    val cacheKey = "${query}_${safeCount}"
        searchCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRE) {
                AppLogger.d(TAG, "使用缓存: ${query}")
        return@withContext cached.data
            } else {
                searchCache.remove(cacheKey)
            }
        }
        AppLogger.d(TAG, "开始搜索 ${query}")
        try {
            // 构建请求
    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://cn.bing.com/search?q=${encodedQuery}"
        val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            // 执行请求
    val response = client.newCall(request).execute()
            
            // 检查响应状态
    if (!response.isSuccessful) {
                return@withContext SearchResponse(
                    success = false,
                    query = query,
                    time = getCurrentTime(),
                    data = emptyList(),
                    errorMessage = "搜索请求失败，状态码: ${response.code}"
                )
            }
        val html = response.body?.string() ?: ""
            
            // 检查HTML内容
    if (html.isEmpty()) {
                return@withContext SearchResponse(
                    success = false,
                    query = query,
                    time = getCurrentTime(),
                    data = emptyList(),
                    errorMessage = "无法获取搜索结果，请检查网络连接"
                )
            }

            // 解析结果
    val results = parseSearchResults(html, safeCount)
        val searchResponse = if (results.isEmpty()) {
                // 空结果但请求成功
        SearchResponse(
                    success = true,
                    query = query,
                    time = getCurrentTime(),
                    data = emptyList(),
                    errorMessage = null
                )
            } else {
                SearchResponse(
                    success = true,
                    query = query,
                    time = getCurrentTime(),
                    data = results
                )
            }

            // 清理旧缓存并存储新结果
        cleanOldCacheIfNeeded()
        searchCache[cacheKey] = CachedResult(searchResponse, System.currentTimeMillis())
        AppLogger.d(TAG, "搜索完成，找分{results.size}个结果")
        return@withContext searchResponse

        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.e(TAG, "搜索超时", e)
        return@withContext SearchResponse(
                success = false,
                query = query,
                time = getCurrentTime(),
                data = emptyList(),
                errorMessage = "搜索超时，请检查网络连接后重试"
            )
        } catch (e: java.net.UnknownHostException) {
            AppLogger.e(TAG, "网络连接失败", e)
        return@withContext SearchResponse(
                success = false,
                query = query,
                time = getCurrentTime(),
                data = emptyList(),
                errorMessage = "无法连接到网络，请检查网络设置"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "搜索失败", e)
        return@withContext SearchResponse(
                success = false,
                query = query,
                time = getCurrentTime(),
                data = emptyList(),
                errorMessage = "搜索失败: ${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 解析搜索结果HTML
     */
    private fun parseSearchResults(html: String, maxCount: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            // 匹配搜索结果项
    val itemPattern = Regex("""<li[^>]*class="[^"]*b_algo[^"]*"[^>]*>[\s\S]*?<\/li>""")
        val items = itemPattern.findAll(html).take(maxCount)
        for (item in items) {
                val itemHtml = item.value

                // 提取标题和链接
    val titleMatch = Regex("""<h2[^>]*>[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]*)<\/a>[\s\S]*?<\/h2>""").find(itemHtml)
        var url = titleMatch?.groupValues?.get(1) ?: ""
        var title = clearHtml(titleMatch?.groupValues?.get(2) ?: "")

                // 提取描述
    val descMatch = Regex("""<div[^>]*class="[^"]*b_caption[^"]*"[^>]*>[\s\S]*?<p[^>]*>([\s\S]*)<\/p>""").find(itemHtml)
        var desc = clearHtml(descMatch?.groupValues?.get(1) ?: "")

                // 清理URL（去掉bing的跟踪参数）
    if (url.startsWith("http")) {
                    // 如果是bing的跳转链接，尝试提取真实URL
    if (url.contains("bing.com")) {
                        val ueMatch = Regex("""\?.*u=([^&]+)""").find(url)
        if (ueMatch != null) {
                            val encodedUrl = ueMatch.groupValues[1]
                            try {
                                url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                            } catch (e: Exception) {
                                // 忽略
                            }
                        }
                    }
                }
        if (title.isNotEmpty() && url.isNotEmpty()) {
                    results.add(SearchResult(title, desc, url))
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析搜索结果异常", e)
        }
        return results
    }

    /**
     * 清除HTML标签
     */
    private fun clearHtml(html: String): String {
        return html
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""&nbsp;"""), " ")
            .replace(Regex("""&lt;"""), "<")
            .replace(Regex("""&gt;"""), ">")
            .replace(Regex("""&amp;"""), "&")
            .replace(Regex("""&quot;"""), "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * 获取当前时间字符为
     */
    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
    }

    /**
     * 格式化搜索结果为可读文本
     */
    fun formatResults(response: SearchResponse): String {
        if (!response.success) {
            return "搜索失败: ${response.errorMessage ?: "未知错误"}"
        }
        if (response.data.isEmpty()) {
            return "未找到关于。{response.query}」的相关结果"
        }
        return buildString {
            append("🔍 搜索。{response.query}」结果（${response.time}）：\n")
        append("───────────────────────\n\n")
        response.data.forEachIndexed { index, result ->
                append("${index + 1}. ${result.title}\n")
        if (result.summary.isNotEmpty()) {
                    append("   ${result.summary.take(200)}\n")
                }
        append("   🔗 ${result.url}\n\n")
            }
        append("───────────────────────\n")
        append("共找分{response.data.size}条结果")
        }
    }
}