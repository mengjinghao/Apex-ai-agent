package com.apex.apk.market.cache

import com.apex.agent.integration.market.IntegrationCategory
import com.apex.agent.integration.market.MarketItem
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 市场缓存 — 离线浏览 + 减少网络请求。
 *
 * **缓存策略**：
 *   - 按 (marketId, query) 维度缓存搜索结果
 *   - 默认 TTL = 5 分钟（可配置）
 *   - 持久化到本地 JSON（重启后仍可用）
 *   - 支持手动刷新（清除缓存）
 *
 * **存储路径**：`<app_data>/apex-market-cache/`
 *   - `index.json` — 缓存索引
 *   - `entries/<hash>.json` — 单条缓存
 */
class MarketCache(
    private val storageDir: File,
    private val defaultTtlMs: Long = 5 * 60 * 1000L  // 5 分钟
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entriesDir = File(storageDir, "entries").apply { mkdirs() }
    private val indexFile = File(storageDir, "index.json")

    /** key = "${marketId}|${query}|${category}" */
    private val entries = ConcurrentHashMap<String, CacheEntry>()

    init { loadIndex() }

    /**
     * 获取缓存。
     * @return 命中且未过期则返回结果，否则 null
     */
    fun get(marketId: String, query: String, category: IntegrationCategory): List<MarketItem>? {
        val key = buildKey(marketId, query, category)
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.cachedAt > defaultTtlMs) {
            // 过期
            return null
        }
        // 从磁盘加载
        val file = File(entriesDir, "${entry.fileName}.json")
        if (!file.exists()) return null
        return try {
            val cached = json.decodeFromString<List<MarketItem>>(file.readText())
            ApexLog.d("market", "[Cache] hit: $key (${cached.size} items)")
            cached
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 写入缓存。
     */
    fun put(marketId: String, query: String, category: IntegrationCategory, items: List<MarketItem>) {
        val key = buildKey(marketId, query, category)
        val fileName = hashKey(key)
        val entry = CacheEntry(
            key = key,
            marketId = marketId,
            query = query,
            category = category.name,
            cachedAt = System.currentTimeMillis(),
            itemCount = items.size,
            fileName = fileName
        )
        entries[key] = entry

        // 写磁盘
        try {
            val file = File(entriesDir, "$fileName.json")
            file.writeText(json.encodeToString(items))
            persistIndex()
        } catch (t: Throwable) {
            ApexLog.w("market", "[Cache] put failed: ${t.message}")
        }
    }

    /**
     * 清除指定市场的缓存。
     */
    fun clearForMarket(marketId: String): Int {
        val toRemove = entries.filter { it.value.marketId == marketId }
        toRemove.forEach { (key, entry) ->
            entries.remove(key)
            File(entriesDir, "${entry.fileName}.json").delete()
        }
        persistIndex()
        return toRemove.size
    }

    /**
     * 清除所有缓存。
     */
    fun clearAll(): Int {
        val count = entries.size
        entries.clear()
        entriesDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        return count
    }

    /**
     * 清除过期缓存。
     */
    fun cleanExpired(): Int {
        val now = System.currentTimeMillis()
        val expired = entries.filter { now - it.value.cachedAt > defaultTtlMs }
        expired.forEach { (key, entry) ->
            entries.remove(key)
            File(entriesDir, "${entry.fileName}.json").delete()
        }
        persistIndex()
        return expired.size
    }

    /**
     * 缓存统计。
     */
    fun getStats(): CacheStats {
        val totalSize = entriesDir.listFiles()?.sumOf { it.length() } ?: 0L
        return CacheStats(
            entryCount = entries.size,
            totalSizeBytes = totalSize,
            oldestEntryAgeMs = entries.minOfOrNull { System.currentTimeMillis() - it.value.cachedAt } ?: 0L,
            newestEntryAgeMs = entries.maxOfOrNull { System.currentTimeMillis() - it.value.cachedAt } ?: 0L
        )
    }

    /**
     * 列出所有缓存条目（用于诊断 UI）。
     */
    fun listEntries(): List<CacheEntry> = entries.values.sortedByDescending { it.cachedAt }

    private fun buildKey(marketId: String, query: String, category: IntegrationCategory): String {
        return "$marketId|${query.lowercase().trim()}|${category.name}"
    }

    private fun hashKey(key: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun persistIndex() {
        try {
            val data = entries.values.toList()
            indexFile.writeText(json.encodeToString(data))
        } catch (_: Throwable) {}
    }

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val data = json.decodeFromString<List<CacheEntry>>(indexFile.readText())
            data.forEach { entries[it.key] = it }
        } catch (_: Throwable) {}
    }
}

@Serializable
data class CacheEntry(
    val key: String,
    val marketId: String,
    val query: String,
    val category: String,
    val cachedAt: Long,
    val itemCount: Int,
    val fileName: String
)

data class CacheStats(
    val entryCount: Int,
    val totalSizeBytes: Long,
    val oldestEntryAgeMs: Long,
    val newestEntryAgeMs: Long
) {
    val totalSizeMb: Double get() = totalSizeBytes / 1024.0 / 1024.0
}
