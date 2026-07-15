package com.apex.agent.core.normal.meme.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 百度搜索建议 Provider
 *
 * 使用百度搜索建议 API（公开，无需 API Key）
 * https://suggestion.baidu.com/su?wd=xxx&action=opensearch
 *
 * 同时使用百度网页搜索（HTML 解析）
 */
class BaiduSearchProvider : WebSearchProvider {

    override val name = "Baidu"
        override val supported = true

    private val suggestUrl = "https://suggestion.baidu.com/su"
        private val searchUrl = "https://www.baidu.com/s"
    override suspend fun searchMeme(query: String, num: Int): MemeSearchResult {
        val start = System.currentTimeMillis()
        val enhancedQuery = enhanceQuery(query)
        return withContext(Dispatchers.IO) {
            val url = "$searchUrl?wd=${MemeHttpUtil.encode(enhancedQuery)}&rn=$num"
        val result = MemeHttpUtil.get(url)
        if (!result.success) {
                return@withContext MemeSearchResult(
                    query = query, engine = name, items = emptyList(),
                    totalFound = 0, searchTimeMs = System.currentTimeMillis() - start,
                    success = false, error = result.error ?: "HTTP ${result.statusCode}"
                )
            }
        val items = parseBaiduResults(result.body, num)
        MemeSearchResult(
                query = query, engine = name, items = items,
                totalFound = items.size, searchTimeMs = System.currentTimeMillis() - start,
                success = true
            )
        }
    }
        override suspend fun suggest(query: String): List<String> {
        return withContext(Dispatchers.IO) {
            // 百度建议 API 返回 JSONP: window.baidu.sug({...})
        val url = "$suggestUrl?wd=${MemeHttpUtil.encode(query)}&action=opensearch&ie=UTF-8"
        val result = MemeHttpUtil.get(url)
        if (!result.success) return@withContext emptyList()

            // 百度返回 JSON 数组: ["query",["suggestion1","suggestion2",...]]
    val array = MemeJsonUtil.parseArray(result.body)
        if (array != null && array.length() > 1) {
                val suggestions = array.optJSONArray(1)
        if (suggestions != null) {
                    return@withContext (0 until suggestions.length()).mapNotNull { i ->
                        suggestions.optString(i).takeIf { it.isNotBlank() }
                    }
                }
            }
        emptyList()
        }
    }
        private fun enhanceQuery(query: String): String {
        val trimmed = query.trim()
        return when {
            trimmed.contains("是什么") || trimmed.contains("什么意思") -> "$trimmed 梗"
        trimmed.contains("梗") -> trimmed
            trimmed.length <= 6 -> "$trimmed 梗 什么意思"
        else -> "$trimmed 网络梗 解释"
        }
    }

    /**
     * 解析百度搜索结果 HTML
     */
    private fun parseBaiduResults(html: String, maxResults: Int): List<MemeSearchResultItem> {
        val items = mutableListOf<MemeSearchResultItem>()

        // 百度结果块: <div class="result c-container ...">...<h3><a href="...">标题</a></h3>...摘要...</div>
        // 百度链接通常是跳转链接 http://www.baidu.com/link?url=...
    val resultPattern = Regex(
            """<div[^>]*class="result[^"]*c-container[^"]*"[^>]*>.*?<h3[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?</h3>.*?(?:<span[^>]*class="content-right_8Zs40"[^>]*>|<div[^>]*class="c-abstract"[^>]*>)(.*?)</""",
            RegexOption.DOT_MATCHES_ALL
        )
        resultPattern.findAll(html).take(maxResults).forEach { match ->
            val url = match.groupValues[1]
        val title = cleanHtml(match.groupValues[2])
        val snippet = cleanHtml(match.groupValues[3])
        if (title.isNotBlank()) {
                items.add(MemeSearchResultItem(
                    title = title,
                    snippet = snippet.take(300),
                    url = url,
                    source = "Baidu"
                ))
            }
        }

        // 备用：更宽松的解析
    if (items.isEmpty()) {
            val simplePattern = Regex("""<h3[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""")
        simplePattern.findAll(html).take(maxResults).forEach { match ->
                val title = cleanHtml(match.groupValues[2])
        if (title.length > 3) {
                    items.add(MemeSearchResultItem(
                        title = title,
                        snippet = "",
                        url = match.groupValues[1],
                        source = "Baidu"
                    ))
                }
            }
        }
        return items
    }
        private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
