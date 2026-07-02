package com.apex.agent.core.normal.meme.web

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络搜梗引擎（核心）
 *
 * 整合多搜索引擎 + 热搜 + 梗百科 + 缓存，提供统一的网络搜梗能力。
 *
 * 核心能力：
 * 1. searchMeme(query) - 搜索梗的含义/用法（多引擎并发，取最优）
 * 2. suggest(query) - 搜索建议（自动补全）
 * 3. lookupMeme(query) - 梗百科查询（详细解释）
 * 4. getTrendingMemes() - 获取当前流行梗（来自热搜）
 * 5. explainUnknownMeme(text) - 自动识别未知梗并查询
 *
 * 用法：
 * ```kotlin
 * val engine = MemeWebSearchEngine()
 *
 * // 搜索梗
 * val result = engine.searchMeme("绝绝子")
 *
 * // 查询梗百科
 * val wiki = engine.lookupMeme("yyds")
 *
 * // 获取流行梗
 * val trending = engine.getTrendingMemes()
 * ```
 */
class MemeWebSearchEngine(
    private val cache: MemeCacheManager = MemeCacheManager(),
    private val registry: WebSearchProviderRegistry = WebSearchProviderRegistry().apply {
        register(BingSearchProvider())
        register(BaiduSearchProvider())
    },
    private val hotSearchProvider: HotSearchProvider = HotSearchProvider(),
    private val wikiProvider: MemeWikiProvider = MemeWikiProvider()
) {

    /**
     * 搜索梗
     *
     * 多引擎并发搜索，合并去重，按相关性排序
     *
     * @param query 查询词（如"绝绝子"、"yyds"）
     * @param num 每个引擎返回的最大结果数
     * @param useCache 是否使用缓存
     * @return 合并后的搜索结果
     */
    suspend fun searchMeme(query: String, num: Int = 5, useCache: Boolean = true): MemeSearchResult {
        // 1. 检查缓存
        if (useCache) {
            // 先查缓存（不指定引擎）
            for (provider in registry.getAvailableProviders()) {
                val cached = cache.getSearchResult(query, provider.name)
                if (cached != null && cached.success) {
                    return cached
                }
            }
        }

        // 2. 并发搜索所有可用引擎
        val providers = registry.getAvailableProviders()
        if (providers.isEmpty()) {
            return MemeSearchResult(
                query = query, engine = "none", items = emptyList(),
                totalFound = 0, searchTimeMs = 0, success = false,
                error = "没有可用的搜索引擎"
            )
        }

        val start = System.currentTimeMillis()
        val results = coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        val result = provider.searchMeme(query, num)
                        if (result.success) {
                            registry.recordSuccess(provider.name)
                            cache.putSearchResult(query, provider.name, result)
                        } else {
                            registry.recordFailure(provider.name)
                        }
                        result
                    } catch (e: Exception) {
                        registry.recordFailure(provider.name)
                        MemeSearchResult(
                            query = query, engine = provider.name, items = emptyList(),
                            totalFound = 0, searchTimeMs = 0, success = false,
                            error = e.message
                        )
                    }
                }
            }.map { it.await() }
        }

        // 3. 合并结果
        val merged = mergeResults(query, results)
        val finalResult = merged.copy(searchTimeMs = System.currentTimeMillis() - start)

        // 4. 缓存合并结果
        if (useCache && finalResult.success) {
            cache.putSearchResult(query, "merged", finalResult)
        }

        return finalResult
    }

    /**
     * 搜索建议
     */
    suspend fun suggest(query: String, useCache: Boolean = true): List<String> {
        if (query.length < 2) return emptyList()

        // 检查缓存
        if (useCache) {
            for (provider in registry.getAvailableProviders()) {
                val cached = cache.getSuggestions(query, provider.name)
                if (cached != null) return cached
            }
        }

        val providers = registry.getAvailableProviders()
        val allSuggestions = mutableSetOf<String>()

        for (provider in providers) {
            try {
                val suggestions = provider.suggest(query)
                if (suggestions.isNotEmpty()) {
                    registry.recordSuccess(provider.name)
                    cache.putSuggestions(query, provider.name, suggestions)
                    allSuggestions.addAll(suggestions)
                }
            } catch (e: Exception) {
                registry.recordFailure(provider.name)
            }
            if (allSuggestions.size >= 10) break
        }

        // 过滤与排序：与查询词相关性高的优先
        return allSuggestions
            .filter { it.contains(query, ignoreCase = true) || query.contains(it, ignoreCase = true) }
            .sortedBy { it.length }  // 短的优先（更可能是梗）
            .take(10)
            .toList()
    }

    /**
     * 梗百科查询
     */
    suspend fun lookupMeme(query: String, useCache: Boolean = true): MemeWikiResult {
        // 检查缓存
        if (useCache) {
            val cached = cache.getWiki(query)
            if (cached != null) return cached
        }

        val result = wikiProvider.lookupMeme(query)
        if (result.success) {
            cache.putWiki(query, result)
        }
        return result
    }

    /**
     * 获取当前流行梗
     */
    suspend fun getTrendingMemes(limit: Int = 10, useCache: Boolean = true): List<HotSearchProvider.TrendingMeme> {
        // 检查缓存
        if (useCache) {
            val cached = cache.getHotSearch("trending_memes")
            @Suppress("UNCHECKED_CAST")
            if (cached != null) {
                return cached.map { item ->
                    HotSearchProvider.TrendingMeme(
                        title = item.title,
                        source = item.category,
                        hotScore = item.hotScore,
                        url = item.url,
                        detectedAt = System.currentTimeMillis()
                    )
                }
            }
        }

        val trending = hotSearchProvider.getTrendingMemes(limit)

        // 缓存
        if (trending.isNotEmpty()) {
            val items = trending.map { t ->
                HotSearchItem(0, t.title, t.hotScore, t.url, t.source)
            }
            cache.putHotSearch("trending_memes", items)
        }

        return trending
    }

    /**
     * 获取热搜榜
     */
    suspend fun getHotSearch(source: String = "all", limit: Int = 20, useCache: Boolean = true): List<HotSearchItem> {
        val cacheKey = "hotsearch_${source}"

        // 检查缓存
        if (useCache) {
            val cached = cache.getHotSearch(cacheKey)
            if (cached != null) return cached.take(limit)
        }

        val items = when (source) {
            "weibo" -> hotSearchProvider.getWeiboHotSearch(limit)
            "baidu" -> hotSearchProvider.getBaiduHotSearch(limit)
            "zhihu" -> hotSearchProvider.getZhihuHotList(limit)
            else -> hotSearchProvider.getAllHotSearch(limit)
        }

        if (items.isNotEmpty()) {
            cache.putHotSearch(cacheKey, items)
        }

        return items
    }

    /**
     * 自动识别未知梗并查询
     *
     * 检测文本中可能的梗，对未知梗自动搜索解释
     *
     * @param text 用户输入文本
     * @return 识别到的梗及其解释
     */
    suspend fun explainUnknownMemes(text: String): List<MemeExplanation> {
        val explanations = mutableListOf<MemeExplanation>()

        // 提取候选梗词（简化：连续的非空中文/英文片段）
        val candidates = extractMemeCandidates(text)

        for (candidate in candidates) {
            // 先查缓存（已知的就不重复查）
            val cached = cache.getWiki(candidate)
            if (cached != null && cached.success) {
                explanations.add(MemeExplanation(
                    term = candidate,
                    definition = cached.definition,
                    source = cached.source,
                    sourceUrl = cached.sourceUrl,
                    fromCache = true
                ))
                continue
            }

            // 判断是否值得查询（短词、非通用词）
            if (isLikelyMeme(candidate)) {
                val wiki = lookupMeme(candidate)
                if (wiki.success) {
                    explanations.add(MemeExplanation(
                        term = candidate,
                        definition = wiki.definition,
                        source = wiki.source,
                        sourceUrl = wiki.sourceUrl,
                        fromCache = false
                    ))
                }
            }
        }

        return explanations
    }

    /**
     * 生成梗解释 prompt
     *
     * 当用户消息包含未知梗时，自动搜索并注入解释
     */
    suspend fun generateMemeLookupPrompt(text: String): String {
        val explanations = explainUnknownMemes(text)
        if (explanations.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[网络搜梗结果]")
        explanations.forEach { exp ->
            sb.appendLine()
            sb.append("「${exp.term}」: ${exp.definition.take(200)}")
            sb.append(" (来源: ${exp.source})")
            if (exp.fromCache) sb.append(" [缓存]")
        }
        return sb.toString()
    }

    /**
     * 获取引擎状态
     */
    fun getEngineStatus(): Map<String, WebSearchProviderRegistry.ProviderStatus> {
        return registry.getProviderStatus()
    }

    /**
     * 获取缓存统计
     */
    fun getCacheStats(): MemeCacheManager.CacheStats = cache.getStats()

    /**
     * 清理缓存
     */
    fun cleanupCache(): Int = cache.cleanupExpired()

    // ============ 内部方法 ============

    private fun mergeResults(query: String, results: List<MemeSearchResult>): MemeSearchResult {
        val allItems = mutableListOf<MemeSearchResultItem>()
        val engines = mutableListOf<String>()

        for (result in results) {
            if (result.success) {
                engines.add(result.engine)
                allItems.addAll(result.items)
            }
        }

        if (allItems.isEmpty()) {
            return MemeSearchResult(
                query = query,
                engine = "merged",
                items = emptyList(),
                totalFound = 0,
                searchTimeMs = 0,
                success = false,
                error = results.firstOrNull { !it.success }?.error ?: "所有引擎均失败"
            )
        }

        // 去重（按 URL）
        val seen = mutableSetOf<String>()
        val deduped = allItems.filter { item ->
            val key = item.url.ifBlank { item.title }
            seen.add(key)
        }

        // 排序：标题/摘要包含查询词的优先
        val queryLower = query.lowercase()
        val sorted = deduped.sortedWith(compareByDescending<MemeSearchResultItem> { item ->
            var score = 0
            if (item.title.contains(query, ignoreCase = true)) score += 3
            if (item.title.contains(queryLower, ignoreCase = true)) score += 2
            if (item.snippet.contains(query, ignoreCase = true)) score += 1
            if (item.snippet.contains("梗", ignoreCase = true)) score += 1
            if (item.snippet.contains("网络", ignoreCase = true)) score += 1
            score
        }.thenBy { it.title.length })

        return MemeSearchResult(
            query = query,
            engine = "merged(${engines.joinToString("+")})",
            items = sorted.take(10),
            totalFound = sorted.size,
            searchTimeMs = 0,
            success = true
        )
    }

    private fun extractMemeCandidates(text: String): List<String> {
        // 提取候选词：英文缩写（2-6字母）、中文短语（2-4字）、特殊词
        val candidates = mutableSetOf<String>()

        // 英文缩写：连续 2-6 个字母，可能带数字
        Regex("\\b[A-Za-z]{2,6}\\d?\\b").findAll(text).forEach { m ->
            val word = m.value
            // 过滤常见英文单词
            if (word.uppercase() == word && word.length in 2..6) {
                candidates.add(word)
            }
        }

        // 中文短语：2-4 字
        Regex("[\\u4e00-\\u9fa5]{2,4}").findAll(text).forEach { m ->
            candidates.add(m.value)
        }

        // 已知梗模式
        val knownPatterns = listOf("yyds", "绝绝子", "破防", "蚌埠", "栓Q", "芭比Q", "emo", "躺平", "摆烂", "卷王", "小镇做题家")
        knownPatterns.forEach { pattern ->
            if (text.contains(pattern, ignoreCase = true)) {
                candidates.add(pattern)
            }
        }

        return candidates
            .filter { it.length in 2..10 }
            .filter { it.lowercase() !in COMMON_WORDS }
            .toList()
    }

    private fun isLikelyMeme(term: String): Boolean {
        // 全大写英文缩写很可能是梗
        if (term.uppercase() == term && term.length in 2..6 && term.any { it.isLetter() }) {
            return true
        }
        // 包含梗相关字
        if (term.containsAny("梗", "绝", "破", "蚌", "栓", "emo", "躺", "摆", "卷")) {
            return true
        }
        // 短中文词
        if (term.length in 2..4 && term.all { it.code in 0x4e00..0x9fff }) {
            return true
        }
        return false
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }

    /**
     * 梗解释
     */
    data class MemeExplanation(
        val term: String,
        val definition: String,
        val source: String,
        val sourceUrl: String,
        val fromCache: Boolean
    )

    companion object {
        private val COMMON_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "was", "one",
            "our", "out", "day", "get", "has", "him", "his", "how", "its", "may", "new", "now",
            "old", "see", "two", "way", "who", "boy", "did", "man", "men", "put", "say", "she",
            "too", "use", "yes", "abc", "test", "this", "that", "with", "have", "from", "they",
            "will", "would", "there", "their", "what", "about", "which", "when", "your", "can"
        )

        @Volatile
        private var instance: MemeWebSearchEngine? = null

        fun getInstance(): MemeWebSearchEngine {
            return instance ?: synchronized(this) {
                instance ?: MemeWebSearchEngine().also { instance = it }
            }
        }
    }
}
