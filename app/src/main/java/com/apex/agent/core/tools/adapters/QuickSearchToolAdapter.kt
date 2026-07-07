package com.apex.core.tools.adapters

import com.apex.core.tools.StringResultData
import com.apex.core.tools.ToolAdapter
import com.apex.core.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 轻量搜索工具适配�?* 基于必应搜索的快速文本搜�?*/
class QuickSearchToolAdapter : ToolAdapter {

    // 配置参数
    companion object {
        private const val MAX_CACHE_SIZE = 50
        private const val CACHE_EXPIRE_TIME = 10 * 60 * 1000L // 10分钟
        private const val CONNECT_TIMEOUT = 10L
        private const val READ_TIMEOUT = 30L
        private const val WRITE_TIMEOUT = 30L
    }

    // 搜索结果缓存
    private data class CachedResult(
        val result: String,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CachedResult>()

    // OkHttpClient
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun getName(): String = "quick_search"

    override fun getDescription(): String = "轻量快速的联网搜索，基于必应搜索引�?

    override suspend fun execute(parameters: Map<String, Any>): StringResultData = withContext(Dispatchers.IO) {
        val query = parameters["query"] as? String?.trim()
        val count = parameters["count"] as? Int ?: 6
        val useCache = parameters["use_cache"] as? Boolean ?: true

        if (query.isNullOrEmpty()) {
            return@withContext StringResultData("错误：请提供搜索关键值）
        }

        // 检查缓�?       val cacheKey = "${query}:${count}"
        if (useCache) {
            cache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRE_TIME) {
                    return@withContext StringResultData(cached.result)
                } else {
                    cache.remove(cacheKey)
                }
            }
        }

        try {
            // 执行搜索
            val searchUrl = "https://cn.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            // 解析搜索结果
            val results = parseSearchResults(html, count)
            val formattedResult = formatResults(query, results)

            // 缓存结果
            if (cache.size >= MAX_CACHE_SIZE) {
                val oldestKey = cache.keys.firstOrNull()
                oldestKey?.let { cache.remove(it) }
            }
            cache[cacheKey] = CachedResult(formattedResult, System.currentTimeMillis())

            StringResultData(formattedResult)
        } catch (e: Exception) {
            StringResultData("搜索失败，{e.message}\n请检查网络连接后重试")
        }
    }

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "搜索关键值， true),
        ToolParameter("count", "int", "返回结果数量（默，，, false, 6),
        ToolParameter("use_cache", "boolean", "是否使用缓存（默认true�? false, true)
    )

    override fun isAvailable(): Boolean = true

    /**
     * 解析搜索结果HTML
     */
    private fun parseSearchResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        try {
            // 使用正则表达式提取搜索结�?           val itemPattern = Regex("""<li[^>]*class="[^"]*b_algo[^"]*"[^>]*>.*?</li>""", RegexOption.DOT_MATCHES_ALL)
            val items = itemPattern.findAll(html).take(maxResults)
            
            for (item in items) {
                val itemHtml = item.value
                
                // 提取标题
                val titleMatch = Regex("""<h2[^>]*>.*?<a[^>]*href="([^"]*)"[^>]*>(.*)</a>.*?</h2>""", RegexOption.DOT_MATCHES_ALL).find(itemHtml)
                var url = titleMatch?.groupValues?.get(1) ?: ""
                var title = clearHtml(titleMatch?.groupValues?.get(2) ?: "")
                
                // 提取描述
                val descPattern = Regex("""<p[^>]*>(.*)</p>""", RegexOption.DOT_MATCHES_ALL)
                val descMatch = descPattern.find(itemHtml)
                val description = clearHtml(descMatch?.groupValues?.get(1) ?: "")
                
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    results.add(SearchResult(title, description, url))
                }
            }
        } catch (e: Exception) {
            // 解析失败时返回空列表
        }
        
        return results
    }

    /**
     * 清理HTML标签
     */
    private fun clearHtml(html: String): String {
        return html
            .replace(Regex("""<[^>]*>"""), "")
            .replace(Regex("""&\w+;"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * 格式化搜索结�?    */
    private fun formatResults(query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) {
            return "搜索，query」没有找到相关结果。\n建议尝试不同的关键词或检查网络连接口
        }

        val sb = StringBuilder()
        sb.append("🔍 搜索，query」结果：\n")
        sb.append("───────────────────────\n\n")

        results.forEachIndexed { index, result ->
            sb.append("${index + 1}. ${result.title}\n")
            if (result.description.isNotEmpty()) {
                sb.append("   ${result.description.take(200)}\n")
            }
            sb.append("   ${result.url}\n\n")
        }

        sb.append("───────────────────────\n")
        sb.append("共找�?{results.size} 条相关结果\n")
        sb.append("💡 如需详细内容，可以告诉我具体的链接，我可以帮您抓取网页内容）

        return sb.toString()
    }

    /**
     * 搜索结果数据�?    */
    private data class SearchResult(
        val title: String,
        val description: String,
        val url: String
    )
}
