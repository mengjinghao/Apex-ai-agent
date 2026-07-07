package com.apex.agent.core.normal.meme.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 热搜 Provider
 *
 * 获取实时热搜榜，了解当前流行梗的来源：
 * - 微博热搜（通过公开页面解析）
 * - 知乎热榜
 * - 百度热搜
 * - 抖音热榜
 *
 * 热搜是梗的重要来源，跟踪热搜能让玩梗模式保持新鲜
 */
class HotSearchProvider {

    /**
     * 获取微博热搜
     * 通过公开的移动端热搜页面解析
     */
    suspend fun getWeiboHotSearch(limit: Int = 20): List<HotSearchItem> {
        return withContext(Dispatchers.IO) {
            // 微博热搜公开页面
            val url = "https://m.weibo.cn/api/container/getIndex?containerid=106003type%3D25%26t%3D3%26disable_hot%3D1%26filter_type%3Drealtimehot"
            val result = MemeHttpUtil.get(url)

            if (!result.success) return@withContext emptyList()

            val items = mutableListOf<HotSearchItem>()
            val json = MemeJsonUtil.parseObject(result.body)
            if (json != null) {
                val cards = json.optJSONObject("data")?.optJSONArray("cards")
                if (cards != null) {
                    for (i in 0 until cards.length()) {
                        val card = cards.optJSONObject(i)
                        val cardGroup = card?.optJSONArray("card_group")
                        if (cardGroup != null) {
                            for (j in 0 until cardGroup.length()) {
                                val item = cardGroup.optJSONObject(j)
                                val title = item?.optString("desc", "") ?: ""
                                val hot = item?.optString("desc_extr", "0")?.toLongOrNull() ?: 0L
                                if (title.isNotBlank()) {
                                    items.add(HotSearchItem(
                                        rank = items.size + 1,
                                        title = title,
                                        hotScore = hot,
                                        url = item?.optString("scheme", null),
                                        category = "weibo"
                                    ))
                                }
                                if (items.size >= limit) break
                            }
                        }
                        if (items.size >= limit) break
                    }
                }
            }
            items
        }
    }

    /**
     * 获取百度热搜
     */
    suspend fun getBaiduHotSearch(limit: Int = 20): List<HotSearchItem> {
        return withContext(Dispatchers.IO) {
            // 百度热搜页面
            val url = "https://top.baidu.com/board?tab=realtime"
            val result = MemeHttpUtil.get(url)

            if (!result.success) return@withContext emptyList()

            // 解析 HTML 提取热搜词
            val items = mutableListOf<HotSearchItem>()
            // 百度热搜的 HTML 结构: <div class="c-single-text-ellipsis">关键词</div>
            val pattern = Regex("""<div[^>]*class="[^"]*c-single-text-ellipsis[^"]*"[^>]*>(.*?)</div>""")
            pattern.findAll(result.body).take(limit).forEachIndexed { index, match ->
                val title = cleanHtml(match.groupValues[1])
                if (title.isNotBlank() && title.length > 1) {
                    items.add(HotSearchItem(
                        rank = index + 1,
                        title = title,
                        hotScore = 0L,
                        category = "baidu"
                    ))
                }
            }
            items
        }
    }

    /**
     * 获取知乎热榜
     */
    suspend fun getZhihuHotList(limit: Int = 20): List<HotSearchItem> {
        return withContext(Dispatchers.IO) {
            val url = "https://www.zhihu.com/api/v3/feed/topstory/hot-lists/total?limit=$limit"
            val result = MemeHttpUtil.get(url, mapOf(
                "Referer" to "https://www.zhihu.com/hot"
            ))

            if (!result.success) return@withContext emptyList()

            val items = mutableListOf<HotSearchItem>()
            val json = MemeJsonUtil.parseObject(result.body)
            val dataArray = json?.optJSONArray("data")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i)?.optJSONObject("target")
                    val title = item?.optString("title", "") ?: ""
                    val hot = item?.optString("answer_count", "0")?.toLongOrNull() ?: 0L
                    if (title.isNotBlank()) {
                        items.add(HotSearchItem(
                            rank = i + 1,
                            title = title,
                            hotScore = hot,
                            url = item?.optString("url"),
                            category = "zhihu"
                        ))
                    }
                    if (items.size >= limit) break
                }
            }
            items
        }
    }

    /**
     * 获取所有热搜（聚合）
     */
    suspend fun getAllHotSearch(limit: Int = 10): List<HotSearchItem> {
        val weibo = getWeiboHotSearch(limit)
        val baidu = getBaiduHotSearch(limit)
        val zhihu = getZhihuHotList(limit)

        // 合并去重，按来源交替排序
        val combined = mutableListOf<HotSearchItem>()
        val maxIndex = maxOf(weibo.size, baidu.size, zhihu.size)
        for (i in 0 until maxIndex) {
            if (i < weibo.size) combined.add(weibo[i])
            if (i < baidu.size) combined.add(baidu[i])
            if (i < zhihu.size) combined.add(zhihu[i])
        }

        // 去重（相同标题只保留第一个）
        val seen = mutableSetOf<String>()
        return combined.filter { item ->
            val key = item.title.lowercase()
            if (seen.add(key)) true else false
        }.mapIndexed { index, item -> item.copy(rank = index + 1) }
    }

    /**
     * 检测热搜中的梗候选
     */
    suspend fun getTrendingMemes(limit: Int = 10): List<TrendingMeme> {
        val hotSearch = getAllHotSearch(limit * 2)
        return hotSearch
            .filter { item ->
                // 过滤：标题包含梗相关关键词
                val title = item.title.lowercase()
                title.contains("梗") ||
                title.contains("神句") ||
                title.contains("段子") ||
                title.contains("笑死") ||
                title.contains("破防") ||
                title.contains("绝了") ||
                title.contains("yyds") ||
                // 或者很短可能是梗
                (item.title.length <= 8 && item.hotScore > 1000)
            }
            .take(limit)
            .map { item ->
                TrendingMeme(
                    title = item.title,
                    source = item.category,
                    hotScore = item.hotScore,
                    url = item.url,
                    detectedAt = System.currentTimeMillis()
                )
            }
    }

    data class TrendingMeme(
        val title: String,
        val source: String,
        val hotScore: Long,
        val url: String?,
        val detectedAt: Long
    )

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
