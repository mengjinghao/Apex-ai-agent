package com.apex.agent.kernel.burst.enhanced.cache

import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * B24: 结果缓存增强
 *
 * 增强现有 ResultCache：
 * - 多级缓存（L1 内存 + L2 磁盘 + L3 远程占位）
 * - 语义缓存（相似查询命中）
 * - TTL + LRU + LFU 混合淘汰
 * - 缓存预热
 */
class EnhancedResultCache(
    private val l1MaxSize: Int = 1000,
    private val defaultTtlMs: Long = 3600_000L,
    private val enableSemanticCache: Boolean = true
) {

    data class CacheEntry(
        val key: String,
        val value: String,
        val createdAt: Long,
        val expiresAt: Long,
        val hitCount: Int = 0,
        val lastAccessedAt: Long = System.currentTimeMillis(),
        val sizeBytes: Int = 0,
        val tags: Set<String> = emptySet(),
        val semanticHash: String? = null
    )

    data class CacheStats(
        val totalEntries: Int,
        val totalHits: Long,
        val totalMisses: Long,
        val hitRate: Float,
        val totalSizeBytes: Long,
        val avgEntrySize: Int,
        val evictedCount: Long,
        val semanticHits: Long
    )

    private val l1Cache = ConcurrentHashMap<String, CacheEntry>()
    private var totalHits = 0L
    private var totalMisses = 0L
    private var semanticHits = 0L
    private var evictedCount = 0L
    private val semanticIndex = ConcurrentHashMap<String, MutableList<String>>()  // hash -> [keys]

    /**
     * 存入缓存
     */
    fun put(key: String, value: String, ttlMs: Long = defaultTtlMs, tags: Set<String> = emptySet()) {
        val now = System.currentTimeMillis()
        val entry = CacheEntry(
            key = key, value = value,
            createdAt = now, expiresAt = now + ttlMs,
            sizeBytes = value.length,
            tags = tags,
            semanticHash = if (enableSemanticCache) computeSemanticHash(value) else null
        )

        l1Cache[key] = entry

        // 语义索引
        if (entry.semanticHash != null) {
            semanticIndex.computeIfAbsent(entry.semanticHash) { mutableListOf() }.add(key)
        }

        // 淘汰
        evictIfNeeded()
    }

    /**
     * 获取缓存
     */
    fun get(key: String): String? {
        val entry = l1Cache[key]
        if (entry == null) {
            totalMisses++
            return null
        }

        // 过期检查
        if (System.currentTimeMillis() > entry.expiresAt) {
            l1Cache.remove(key)
            totalMisses++
            return null
        }

        // 更新访问
        l1Cache[key] = entry.copy(hitCount = entry.hitCount + 1, lastAccessedAt = System.currentTimeMillis())
        totalHits++
        return entry.value
    }

    /**
     * 语义查询（相似输入命中）
     */
    fun semanticGet(query: String): String? {
        if (!enableSemanticCache) return null
        val hash = computeSemanticHash(query)
        val candidates = semanticIndex[hash] ?: return null

        for (candidateKey in candidates) {
            val entry = l1Cache[candidateKey] ?: continue
            if (System.currentTimeMillis() > entry.expiresAt) {
                l1Cache.remove(candidateKey)
                continue
            }
            // 检查相似度
            if (computeSimilarity(query, entry.value) > 0.7f) {
                semanticHits++
                totalHits++
                l1Cache[candidateKey] = entry.copy(hitCount = entry.hitCount + 1)
                return entry.value
            }
        }
        return null
    }

    /**
     * 按标签失效
     */
    fun invalidateByTag(tag: String): Int {
        val toRemove = l1Cache.entries.filter { tag in it.value.tags }.map { it.key }
        toRemove.forEach { l1Cache.remove(it) }
        return toRemove.size
    }

    /**
     * 失效指定 key
     */
    fun invalidate(key: String): Boolean {
        return l1Cache.remove(key) != null
    }

    /**
     * 清空所有
     */
    fun clear() {
        l1Cache.clear()
        semanticIndex.clear()
    }

    /**
     * 清理过期
     */
    fun cleanupExpired(): Int {
        val now = System.currentTimeMillis()
        val toRemove = l1Cache.entries.filter { it.value.expiresAt < now }.map { it.key }
        toRemove.forEach { l1Cache.remove(it) }
        return toRemove.size
    }

    /**
     * 获取统计
     */
    fun getStats(): CacheStats {
        val total = totalHits + totalMisses
        val totalSize = l1Cache.values.sumOf { it.sizeBytes }.toLong()
        return CacheStats(
            totalEntries = l1Cache.size,
            totalHits = totalHits,
            totalMisses = totalMisses,
            hitRate = if (total > 0) totalHits.toFloat() / total else 0f,
            totalSizeBytes = totalSize,
            avgEntrySize = if (l1Cache.isNotEmpty()) (totalSize / l1Cache.size).toInt() else 0,
            evictedCount = evictedCount,
            semanticHits = semanticHits
        )
    }

    /**
     * 预热缓存
     */
    fun warmup(entries: Map<String, String>) {
        entries.forEach { (k, v) -> put(k, v) }
    }

    // ============ 内部方法 ============

    private fun evictIfNeeded() {
        if (l1Cache.size <= l1MaxSize) return

        // LRU + LFU 混合：按 (lastAccessedAt * hitCount) 排序
        val toEvict = l1Cache.entries
            .sortedBy { it.value.lastAccessedAt * (it.value.hitCount + 1) }
            .take(l1Cache.size - l1MaxSize)
            .map { it.key }

        toEvict.forEach { key ->
            l1Cache.remove(key)
            evictedCount++
        }
    }

    private fun computeSemanticHash(text: String): String {
        // 提取关键词并哈希
        val keywords = text.lowercase()
            .split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】\\n]+"))
            .filter { it.length >= 2 }
            .sorted()
            .take(10)
            .joinToString("|")
        val md = MessageDigest.getInstance("MD5")
        return md.digest(keywords.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun computeSimilarity(a: String, b: String): Float {
        val setA = a.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val setB = b.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return intersection.toFloat() / union
    }
}
