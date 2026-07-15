package com.apex.agent.core.normal.meme.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 梗百科查询 Provider
 *
 * 从梗百科网站查询梗的详细解释：
 * - 小鸡词典（jikipedia.com）- 中文梗百科
 * - 百度百科
 * - 维基百科
 *
 * 用法：当用户问"xxx是什么梗"时，调用此 provider 查询
 */
class MemeWikiProvider {

    /**
     * 查询梗的含义
     */
    suspend fun lookupMeme(query: String): MemeWikiResult {
        val start = System.currentTimeMillis()

        // 尝试多个来源
    val jikipedia = tryJikipedia(query)
        if (jikipedia.success) {
            return jikipedia.copy(searchTimeMs = System.currentTimeMillis() - start)
        }
        val baidu = tryBaiduBaike(query)
        if (baidu.success) {
            return baidu.copy(searchTimeMs = System.currentTimeMillis() - start)
        }
        return MemeWikiResult(
            query = query,
            success = false,
            error = "未找到梗的解释",
            searchTimeMs = System.currentTimeMillis() - start
        )
    }

    /**
     * 尝试小鸡词典
     * 小鸡词典是专门的中文梗百科
     */
    private suspend fun tryJikipedia(query: String): MemeWikiResult {
        return withContext(Dispatchers.IO) {
            // 小鸡词典搜索 API（公开页面）
    val searchUrl = "https://jikipedia.com/search?phrase=${MemeHttpUtil.encode(query)}"
        val result = MemeHttpUtil.get(searchUrl)
        if (!result.success) {
                return@withContext MemeWikiResult(
                    query = query, success = false,
                    error = "小鸡词典请求失败: ${result.error}"
                )
            }

            // 解析页面内容，提取梗的定义
    val html = result.body

            // 尝试提取 JSON-LD 数据
    val jsonLdPattern = Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            jsonLdPattern.find(html)?.let { match ->
                val json = MemeJsonUtil.parseObject(match.groupValues[1])
        if (json != null) {
                    val name = json.optString("name", query)
        val description = json.optString("description", "")
        if (description.isNotBlank()) {
                        return@withContext MemeWikiResult(
                            query = query,
                            success = true,
                            name = name,
                            definition = description,
                            source = "小鸡词典",
                            sourceUrl = searchUrl
                        )
                    }
                }
            }

            // 尝试从 HTML 提取定义
    val defPattern = Regex("""<div[^>]*class="[^"]*definition[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            defPattern.find(html)?.let { match ->
                val definition = cleanHtml(match.groupValues[1])
        if (definition.isNotBlank() && definition.length > 10) {
                    return@withContext MemeWikiResult(
                        query = query,
                        success = true,
                        name = query,
                        definition = definition.take(500),
                        source = "小鸡词典",
                        sourceUrl = searchUrl
                    )
                }
            }

            // 尝试提取 content 描述
    val contentPattern = Regex("""<meta[^>]*name="description"[^>]*content="([^"]+)"""")
            contentPattern.find(html)?.let { match ->
                val content = match.groupValues[1]
                if (content.length > 20) {
                    return@withContext MemeWikiResult(
                        query = query,
                        success = true,
                        name = query,
                        definition = content.take(500),
                        source = "小鸡词典",
                        sourceUrl = searchUrl
                    )
                }
            }

            MemeWikiResult(query = query, success = false, error = "小鸡词典未找到结果")
        }
    }

    /**
     * 尝试百度百科
     */
    private suspend fun tryBaiduBaike(query: String): MemeWikiResult {
        return withContext(Dispatchers.IO) {
            val enhancedQuery = "${query}梗"
        val url = "https://baike.baidu.com/item/${MemeHttpUtil.encode(enhancedQuery)}"
        val result = MemeHttpUtil.get(url)
        if (!result.success) {
                return@withContext MemeWikiResult(
                    query = query, success = false,
                    error = "百度百科请求失败"
                )
            }
        val html = result.body

            // 提取百度百科摘要
    val summaryPattern = Regex("""<meta[^>]*name="description"[^>]*content="([^"]+)"""")
            summaryPattern.find(html)?.let { match ->
                val summary = match.groupValues[1]
                if (summary.length > 20) {
                    return@withContext MemeWikiResult(
                        query = query,
                        success = true,
                        name = query,
                        definition = summary.take(500),
                        source = "百度百科",
                        sourceUrl = url
                    )
                }
            }

            // 提取正文内容
    val contentPattern = Regex("""<div[^>]*class="[^"]*lemma-summary[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            contentPattern.find(html)?.let { match ->
                val content = cleanHtml(match.groupValues[1])
        if (content.length > 20) {
                    return@withContext MemeWikiResult(
                        query = query,
                        success = true,
                        name = query,
                        definition = content.take(500),
                        source = "百度百科",
                        sourceUrl = url
                    )
                }
            }

            MemeWikiResult(query = query, success = false, error = "百度百科未找到结果")
        }
    }
}

/**
 * 梗百科查询结果
 */
data class MemeWikiResult(
    val query: String,
    val success: Boolean,
    val name: String = "",
    val definition: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    val error: String? = null,
    val searchTimeMs: Long = 0
)
