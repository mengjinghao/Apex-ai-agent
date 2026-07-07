package com.apex.agent.core.normal.search

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * F18: 对话搜索与全局检索
 *
 * 跨对话/跨会话的全文搜索与语义搜索：
 * - 关键词搜索（支持通配符、正则）
 * - 语义搜索（向量相似度）
 * - 时间范围过滤
 * - 角色/会话/主题过滤
 * - 搜索结果高亮
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的搜索是 Agent 间共享
 * - 狂暴不关注历史搜索
 * - 本功能是**用户对话历史检索**，是单 Agent 记忆的核心
 */

/**
 * 搜索查询
 */
data class SearchQuery(
    val text: String = "",
    val mode: SearchMode = SearchMode.KEYWORD,
    val filters: SearchFilters = SearchFilters(),
    val limit: Int = 20,
    val offset: Int = 0,
    val highlight: Boolean = true
)

enum class SearchMode {
    /** 关键词搜索（支持 AND/OR/NOT） */
    KEYWORD,
    /** 正则搜索 */
    REGEX,
    /** 模糊搜索（编辑距离） */
    FUZZY,
    /** 语义搜索（向量相似度） */
    SEMANTIC,
    /** 混合搜索（关键词 + 语义） */
    HYBRID
}

data class SearchFilters(
    val chatIds: Set<String> = emptySet(),
    val sessionIds: Set<String> = emptySet(),
    val roles: Set<String> = emptySet(),  // user/assistant/system
    val topics: Set<String> = emptySet(),
    val startTime: Long? = null,
    val endTime: Long? = null,
    val minImportance: Float = 0f,
    val hasCode: Boolean? = null,
    val hasToolCall: Boolean? = null
)

/**
 * 搜索结果
 */
data class SearchResult(
    val messageId: String,
    val chatId: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val score: Float,
    val matchedRanges: List<IntRange>,
    val highlightedContent: String,
    val context: List<SearchContextItem>
)

data class SearchContextItem(
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val isBefore: Boolean
)

/**
 * 搜索响应
 */
data class SearchResponse(
    val query: SearchQuery,
    val results: List<SearchResult>,
    val totalCount: Int,
    val searchTimeMs: Long,
    val facets: SearchFacets
)

data class SearchFacets(
    val byChat: Map<String, Int>,
    val bySession: Map<String, Int>,
    val byRole: Map<String, Int>,
    val byTopic: Map<String, Int>,
    val byTime: Map<String, Int>  // 按天分组
)

/**
 * 可搜索消息
 */
data class SearchableMessage(
    val messageId: String,
    val chatId: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val topics: List<String> = emptyList(),
    val importance: Float = 1.0f,
    val hasCode: Boolean = false,
    val hasToolCall: Boolean = false,
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SearchableMessage) return false
        return messageId == other.messageId
    }
    override fun hashCode(): Int = messageId.hashCode()
}

/**
 * 对话搜索引擎
 */
class ConversationSearchEngine {

    private val messages = ConcurrentHashMap<String, SearchableMessage>()
    private val chatIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val sessionIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val invertedIndex = ConcurrentHashMap<String, MutableSet<String>>()
    private val vocabulary = ConcurrentHashMap<String, Int>()
    private var vocabSize = 0

    /**
     * 索引消息
     */
    fun index(message: SearchableMessage) {
        messages[message.messageId] = message
        chatIndex.computeIfAbsent(message.chatId) { mutableSetOf() }.add(message.messageId)
        sessionIndex.computeIfAbsent(message.sessionId) { mutableSetOf() }.add(message.messageId)

        // 倒排索引
        val tokens = tokenize(message.content)
        for (token in tokens) {
            invertedIndex.computeIfAbsent(token) { mutableSetOf() }.add(message.messageId)
        }

        // 生成 embedding（如未提供）
        if (message.embedding == null) {
            val embedding = embed(message.content)
            messages[message.messageId] = message.copy(embedding = embedding)
        }
    }

    /**
     * 批量索引
     */
    fun indexBatch(messages: List<SearchableMessage>) {
        messages.forEach { index(it) }
    }

    /**
     * 删除消息
     */
    fun remove(messageId: String) {
        val msg = messages.remove(messageId) ?: return
        chatIndex[msg.chatId]?.remove(messageId)
        sessionIndex[msg.sessionId]?.remove(messageId)
        // 倒排索引清理（延迟）
    }

    /**
     * 搜索
     */
    fun search(query: SearchQuery): SearchResponse {
        val start = System.currentTimeMillis()

        val candidates = getFilteredCandidates(query.filters)
        val results = when (query.mode) {
            SearchMode.KEYWORD -> keywordSearch(query.text, candidates)
            SearchMode.REGEX -> regexSearch(query.text, candidates)
            SearchMode.FUZZY -> fuzzySearch(query.text, candidates)
            SearchMode.SEMANTIC -> semanticSearch(query.text, candidates)
            SearchMode.HYBRID -> hybridSearch(query.text, candidates)
        }

        val sorted = results.sortedByDescending { it.score }
        val paged = sorted.drop(query.offset).take(query.limit)
        val withContext = paged.map { addContext(it, 1) }
        val withHighlight = if (query.highlight) withContext.map { highlight(it, query.text) } else withContext

        val facets = computeFacets(candidates)
        return SearchResponse(
            query = query,
            results = withHighlight,
            totalCount = sorted.size,
            searchTimeMs = System.currentTimeMillis() - start,
            facets = facets
        )
    }

    /**
     * 全局搜索（便捷方法）
     */
    fun search(text: String, limit: Int = 20): SearchResponse {
        return search(SearchQuery(text = text, limit = limit))
    }

    /**
     * 按会话搜索
     */
    fun searchInSession(sessionId: String, text: String, limit: Int = 20): SearchResponse {
        return search(SearchQuery(
            text = text,
            filters = SearchFilters(sessionIds = setOf(sessionId)),
            limit = limit
        ))
    }

    /**
     * 按时间范围搜索
     */
    fun searchByTimeRange(start: Long, end: Long, text: String = "", limit: Int = 20): SearchResponse {
        return search(SearchQuery(
            text = text,
            filters = SearchFilters(startTime = start, endTime = end),
            limit = limit
        ))
    }

    // ============ 搜索方法 ============

    private fun getFilteredCandidates(filters: SearchFilters): List<SearchableMessage> {
        return messages.values.filter { msg ->
            (filters.chatIds.isEmpty() || msg.chatId in filters.chatIds) &&
            (filters.sessionIds.isEmpty() || msg.sessionId in filters.sessionIds) &&
            (filters.roles.isEmpty() || msg.role in filters.roles) &&
            (filters.topics.isEmpty() || msg.topics.any { it in filters.topics }) &&
            (filters.startTime == null || msg.timestamp >= filters.startTime) &&
            (filters.endTime == null || msg.timestamp <= filters.endTime) &&
            (msg.importance >= filters.minImportance) &&
            (filters.hasCode == null || msg.hasCode == filters.hasCode) &&
            (filters.hasToolCall == null || msg.hasToolCall == filters.hasToolCall)
        }
    }

    private fun keywordSearch(query: String, candidates: List<SearchableMessage>): List<SearchResult> {
        // 解析查询：支持 AND / OR / NOT
        val terms = parseQuery(query)
        val positiveTerms = terms.filter { !it.startsWith("-") }
        val negativeTerms = terms.filter { it.startsWith("-") }.map { it.removePrefix("-") }

        return candidates.mapNotNull { msg ->
            val content = msg.content.lowercase()
            val matchedRanges = mutableListOf<IntRange>()

            // 正向匹配
            val positiveMatches = positiveTerms.map { term ->
                val idx = content.indexOf(term.lowercase())
                if (idx >= 0) {
                    matchedRanges.add(idx until idx + term.length)
                    true
                } else false
            }

            // 负向匹配（不能包含）
            val negativeMatches = negativeTerms.map { term ->
                content.contains(term.lowercase())
            }

            if (positiveTerms.isNotEmpty() && positiveMatches.any { !it }) return@mapNotNull null
            if (negativeMatches.any { it }) return@mapNotNull null

            val score = if (positiveTerms.isEmpty()) 1f
                        else positiveMatches.count { it }.toFloat() / positiveTerms.size

            SearchResult(
                messageId = msg.messageId,
                chatId = msg.chatId,
                sessionId = msg.sessionId,
                role = msg.role,
                content = msg.content,
                timestamp = msg.timestamp,
                score = score,
                matchedRanges = matchedRanges,
                highlightedContent = msg.content,
                context = emptyList()
            )
        }
    }

    private fun regexSearch(pattern: String, candidates: List<SearchableMessage>): List<SearchResult> {
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { return emptyList() }

        return candidates.mapNotNull { msg ->
            val matches = regex.findAll(msg.content).toList()
            if (matches.isEmpty()) return@mapNotNull null

            val ranges = matches.map { it.range }
            SearchResult(
                messageId = msg.messageId,
                chatId = msg.chatId,
                sessionId = msg.sessionId,
                role = msg.role,
                content = msg.content,
                timestamp = msg.timestamp,
                score = matches.size.toFloat(),
                matchedRanges = ranges,
                highlightedContent = msg.content,
                context = emptyList()
            )
        }
    }

    private fun fuzzySearch(query: String, candidates: List<SearchableMessage>): List<SearchResult> {
        val queryLower = query.lowercase()
        return candidates.mapNotNull { msg ->
            val content = msg.content.lowercase()
            val bestScore = findBestMatch(queryLower, content)
            if (bestScore.first > 0.3f) {
                SearchResult(
                    messageId = msg.messageId,
                    chatId = msg.chatId,
                    sessionId = msg.sessionId,
                    role = msg.role,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    score = bestScore.first,
                    matchedRanges = listOf(bestScore.second),
                    highlightedContent = msg.content,
                    context = emptyList()
                )
            } else null
        }
    }

    private fun semanticSearch(query: String, candidates: List<SearchableMessage>): List<SearchResult> {
        val queryEmbedding = embed(query)
        return candidates.mapNotNull { msg ->
            val sim = cosineSimilarity(queryEmbedding, msg.embedding)
            if (sim > 0.2f) {
                SearchResult(
                    messageId = msg.messageId,
                    chatId = msg.chatId,
                    sessionId = msg.sessionId,
                    role = msg.role,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    score = sim,
                    matchedRanges = emptyList(),
                    highlightedContent = msg.content,
                    context = emptyList()
                )
            } else null
        }
    }

    private fun hybridSearch(query: String, candidates: List<SearchableMessage>): List<SearchResult> {
        val keywordResults = keywordSearch(query, candidates).associateBy { it.messageId }
        val semanticResults = semanticSearch(query, candidates).associateBy { it.messageId }

        val allIds = keywordResults.keys + semanticResults.keys
        return allIds.mapNotNull { id ->
            val kr = keywordResults[id]
            val sr = semanticResults[id]
            when {
                kr != null && sr != null -> kr.copy(score = kr.score * 0.6f + sr.score * 0.4f)
                kr != null -> kr
                sr != null -> sr
                else -> null
            }
        }
    }

    // ============ 辅助方法 ============

    private fun parseQuery(query: String): List<String> {
        // 支持 "quoted phrase" 和 -negative
        val terms = mutableListOf<String>()
        val regex = Regex("(?:\"([^\"]+)\"|(-?\\S+))")
        regex.findAll(query).forEach { match ->
            val quoted = match.groupValues[1]
            val unquoted = match.groupValues[2]
            terms.add(if (quoted.isNotEmpty()) quoted else unquoted)
        }
        return terms.filter { it.isNotBlank() }
    }

    private fun findBestMatch(query: String, content: String): Pair<Float, IntRange> {
        // 简化：滑动窗口 + 编辑距离
        if (query.length > content.length) return 0f to (0..0)

        var bestScore = 0f
        var bestRange = 0..0
        val windowSize = query.length

        for (i in 0..content.length - windowSize) {
            val window = content.substring(i, i + windowSize)
            val sim = 1f - editDistance(query, window).toFloat() / query.length
            if (sim > bestScore) {
                bestScore = sim
                bestRange = i until i + windowSize
            }
        }
        return bestScore to bestRange
    }

    private fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                          else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[m][n]
    }

    private fun addContext(result: SearchResult, contextSize: Int): SearchResult {
        val msg = messages[result.messageId] ?: return result
        val sessionMessages = sessionIndex[msg.sessionId]?.mapNotNull { messages[it] }?.sortedBy { it.timestamp } ?: return result
        val idx = sessionMessages.indexOfFirst { it.messageId == result.messageId }
        if (idx < 0) return result

        val before = sessionMessages.subList((idx - contextSize).coerceAtLeast(0), idx)
            .map { SearchContextItem(it.messageId, it.role, it.content, it.timestamp, isBefore = true) }
        val after = sessionMessages.subList(idx + 1, (idx + 1 + contextSize).coerceAtMost(sessionMessages.size))
            .map { SearchContextItem(it.messageId, it.role, it.content, it.timestamp, isBefore = false) }

        return result.copy(context = before + after)
    }

    private fun highlight(result: SearchResult, query: String): SearchResult {
        if (result.matchedRanges.isEmpty()) return result
        val sb = StringBuilder(result.content)
        // 从后向前插入标记
        for (range in result.matchedRanges.sortedByDescending { it.first }) {
            sb.insert(range.last + 1, "</mark>")
            sb.insert(range.first, "<mark>")
        }
        return result.copy(highlightedContent = sb.toString())
    }

    private fun computeFacets(candidates: List<SearchableMessage>): SearchFacets {
        return SearchFacets(
            byChat = candidates.groupingBy { it.chatId }.eachCount(),
            bySession = candidates.groupingBy { it.sessionId }.eachCount(),
            byRole = candidates.groupingBy { it.role }.eachCount(),
            byTopic = candidates.flatMap { it.topics }.groupingBy { it }.eachCount(),
            byTime = candidates.groupingBy {
                java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(it.timestamp))
            }.eachCount()
        )
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】\\n\\r\\t]+"))
            .filter { it.isNotBlank() && it.length >= 2 }
            .flatMap { token ->
                if (token.any { it.code in 0x4e00..0x9fff }) {
                    token.windowed(2, 1).filter { it.length == 2 }
                } else listOf(token)
            }
    }

    private fun embed(text: String): FloatArray {
        val tokens = tokenize(text)
        val dim = 256
        val vec = FloatArray(dim)
        for (token in tokens) {
            val idx = vocabulary.computeIfAbsent(token) { vocabSize++ % dim }
            vec[idx] += 1.0f
        }
        val norm = sqrt(vec.sumOf { it * it.toDouble() }).toFloat()
        if (norm > 0) for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) (dot / denom).toFloat() else 0f
    }

    /**
     * 获取统计
     */
    fun getStats(): SearchStats {
        return SearchStats(
            totalMessages = messages.size,
            totalChats = chatIndex.size,
            totalSessions = sessionIndex.size,
            vocabularySize = vocabulary.size
        )
    }

    data class SearchStats(
        val totalMessages: Int,
        val totalChats: Int,
        val totalSessions: Int,
        val vocabularySize: Int
    )
}
