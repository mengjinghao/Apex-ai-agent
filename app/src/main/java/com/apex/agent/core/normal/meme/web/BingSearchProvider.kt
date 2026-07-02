package com.apex.agent.core.normal.meme.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bing 搜索建议 Provider
 *
 * 使用 Bing 搜索建议 API（公开，无需 API Key）
 * https://www.bing.com/AS/Suggestions?q=xxx&mkt=zh-CN
 *
 * 同时使用 Bing 网页搜索（通过 HTML 解析，简化版）
 */
class BingSearchProvider : WebSearchProvider {

    override val name = "Bing"
    override val supported = true

    private val suggestUrl = "https://www.bing.com/AS/Suggestions"
    private val searchUrl = "https://www.bing.com/search"

    override suspend fun searchMeme(query: String, num: Int): MemeSearchResult {
        val start = System.currentTimeMillis()
        val enhancedQuery = enhanceQuery(query)

        return withContext(Dispatchers.IO) {
            val url = "$searchUrl?q=${MemeHttpUtil.encode(enhancedQuery)}&count=$num&mkt=zh-CN"
            val result = MemeHttpUtil.get(url)

            if (!result.success) {
                return@withContext MemeSearchResult(
                    query = query, engine = name, items = emptyList(),
                    totalFound = 0, searchTimeMs = System.currentTimeMillis() - start,
                    success = false, error = result.error ?: "HTTP ${result.statusCode}"
                )
            }

            val items = parseBingResults(result.body, num)
            MemeSearchResult(
                query = query, engine = name, items = items,
                totalFound = items.size, searchTimeMs = System.currentTimeMillis() - start,
                success = true
            )
        }
    }

    override suspend fun suggest(query: String): List<String> {
        return withContext(Dispatchers.IO) {
            val url = "$suggestUrl?q=${MemeHttpUtil.encode(query)}&mkt=zh-CN&cvid=1"
            val result = MemeHttpUtil.get(url)

            if (!result.success) return@withContext emptyList()

            // Bing 建议返回 JSON 数组格式
            // [["query",["suggestion1","suggestion2",...]]]
            val array = MemeJsonUtil.parseArray(result.body)
            if (array != null && array.length() > 0) {
                val first = array.optJSONArray(0)
                if (first != null && first.length() > 1) {
                    val suggestions = first.optJSONArray(1)
                    if (suggestions != null) {
                        return@withContext (0 until suggestions.length()).mapNotNull { i ->
                            suggestions.optString(i).takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            emptyList()
        }
    }

    /**
     * 增强查询词，让搜索更精准
     */
    private fun enhanceQuery(query: String): String {
        // 如果查询词很短，添加"梗"后缀
        val trimmed = query.trim()
        return when {
            trimmed.contains("是什么") || trimmed.contains("什么意思") -> "$trimmed 梗 网络用语"
            trimmed.contains("梗") -> trimmed
            trimmed.length <= 6 -> "$trimmed 梗 什么意思"
            else -> "$trimmed 网络梗 解释"
        }
    }

    /**
     * 解析 Bing 搜索结果 HTML（简化版，提取标题和摘要）
     */
    private fun parseBingResults(html: String, maxResults: Int): List<MemeSearchResultItem> {
        val items = mutableListOf<MemeSearchResultItem>()

        // 简化：用正则提取搜索结果
        // Bing 结果块: <li class="b_algo">...<h2><a href="...">标题</a></h2>...<p>摘要</p>...</li>
        val resultPattern = Regex(
            """<li[^>]*class="b_algo"[^>]*>.*?<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?(?:<p[^>]*>|<div[^>]*class="b_caption"[^>]*>.*?<p[^>]*>)(.*?)</p>""",
            RegexOption.DOT_MATCHES_ALL
        )

        resultPattern.findAll(html).take(maxResults).forEach { match ->
            val url = match.groupValues[1]
            val title = cleanHtml(match.groupValues[2])
            val snippet = cleanHtml(match.groupValues[3])

            if (title.isNotBlank() && url.isNotBlank()) {
                items.add(MemeSearchResultItem(
                    title = title,
                    snippet = snippet.take(300),
                    url = url,
                    source = "Bing"
                ))
            }
        }

        // 如果正则没匹配到，尝试更宽松的解析
        if (items.isEmpty()) {
            val linkPattern = Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""")
            linkPattern.findAll(html)
                .filter { m ->
                    val href = m.groupValues[1]
                    href.startsWith("http") &&
                    !href.contains("bing.com") &&
                    !href.contains("microsoft.com") &&
                    !href.contains("go.microsoft")
                }
                .take(maxResults)
                .forEach { match ->
                    val title = cleanHtml(match.groupValues[2])
                    if (title.length > 5) {
                        items.add(MemeSearchResultItem(
                            title = title,
                            snippet = "",
                            url = match.groupValues[1],
                            source = "Bing"
                        ))
                    }
                }
        }

        return items
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")  // 去标签
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
