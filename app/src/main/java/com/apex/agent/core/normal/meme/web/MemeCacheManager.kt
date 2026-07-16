package com.apex.agent.core.normal.meme.web

import java.util.concurrent.ConcurrentHashMap

/**
 * 梗搜索缓存管理器
 *
 * 缓存网络搜索结果，减少重复请求：
 * - TTL 过期机制（默认 1 小时）
 * - LRU 淘汰（默认 500 条）
 * - 搜索建议缓存（5 分钟）
 * - 热搜缓存（30 分钟）
 */
class MemeCacheManager(
    private val defaultTtlMs: Long = 60 * 60_000L,        // 1 小时
    private val suggestTtlMs: Long = 5 * 60_000L,          // 5 分钟
    private val hotSearchTtlMs: Long = 30 * 60_000L,       // 30 分钟
    private val maxEntries: Int = 500
) {

    private data class CacheEntry(
        val key: String,
        val value: Any,
        val createdAt: Long,
        val ttlMs: Long,
        val accessCount: Int = 0,
        var lastAccessedAt: Long = System.currentTimeMillis()
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 存入缓存（搜索结果）
     */
    fun putSearchResult(query: String, engine: String, result: MemeSearchResult) {
        val key = "search:${engine.lowercase()}:${query.lowercase()}"
        put(key, result, defaultTtlMs)
        evictIfNeeded()
    }

    /**
     * 获取搜索结果
     */
    fun getSearchResult(query: String, engine: String): MemeSearchResult? {
        val key = "search:${engine.lowercase()}:${query.lowercase()}"
        val entry = cache[key] ?: return null
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        // 标记来自缓存
        return (entry.value as MemeSearchResult).copy(fromCache = true)
    }

    /**
     * 存入搜索建议
     */
    fun putSuggestions(query: String, engine: String, suggestions: List<String>) {
        val key = "suggest:${engine.lowercase()}:${query.lowercase()}"
        put(key, suggestions, suggestTtlMs)
    }

    /**
     * 获取搜索建议
     */
    fun getSuggestions(query: String, engine: String): List<String>? {
        val key = "suggest:${engine.lowercase()}:${query.lowercase()}"
        val entry = cache[key] ?: return null
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return entry.value as List<String>
    }

    /**
     * 存入热搜
     */
    fun putHotSearch(source: String, items: List<HotSearchItem>) {
        val key = "hotsearch:$source"
        put(key, items, hotSearchTtlMs)
    }

    /**
     * 获取热搜
     */
    fun getHotSearch(source: String): List<HotSearchItem>? {
        val key = "hotsearch:$source"
        val entry = cache[key] ?: return null
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return entry.value as List<HotSearchItem>
    }

    /**
     * 存入梗百科
     */
    fun putWiki(query: String, result: MemeWikiResult) {
        val key = "wiki:${query.lowercase()}"
        put(key, result, defaultTtlMs * 24)  // 24 小时
    }

    /**
     * 获取梗百科
     */
    fun getWiki(query: String): MemeWikiResult? {
        val key = "wiki:${query.lowercase()}"
        val entry = cache[key] ?: return null
        if (isExpired(entry)) {
            cache.remove(key)
            return null
        }
        return entry.value as MemeWikiResult
    }

    /**
     * 清除所有缓存
     */
    fun clear() {
        cache.clear()
    }

    /**
     * 清除过期项
     */
    fun cleanupExpired(): Int {
        val now = System.currentTimeMillis()
        val toRemove = cache.entries.filter { isExpired(it.value) }.map { it.key }
        toRemove.forEach { cache.remove(it) }
        return toRemove.size
    }

    /**
     * 获取缓存统计
     */
    fun getStats(): CacheStats {
        val now = System.currentTimeMillis()
        val total = cache.size
        val expired = cache.values.count { isExpired(it) }
        val byType = cache.keys.groupBy { it.substringBefore(":") }
            .mapValues { it.value.size }
        return CacheStats(total, expired, byType)
    }


    // ============ 内部方法 ============

    private fun put(key: String, value: Any, ttlMs: Long) {
        cache[key] = CacheEntry(
            key = key,
            value = value,
            createdAt = System.currentTimeMillis(),
            ttlMs = ttlMs
        )
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        return System.currentTimeMillis() - entry.createdAt > entry.ttlMs
    }

    private fun evictIfNeeded() {
        if (cache.size <= maxEntries) return

        // LRU 淘汰：按最后访问时间排序，淘汰最久未访问的
        val toRemove = cache.entries
            .sortedBy { it.value.lastAccessedAt }
            .take(cache.size - maxEntries)
            .map { it.key }
        toRemove.forEach { cache.remove(it) }
    }
}
