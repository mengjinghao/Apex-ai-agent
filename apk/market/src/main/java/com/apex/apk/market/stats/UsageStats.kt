package com.apex.apk.market.stats

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 使用统计 — 跟踪用户对市场项的操作（搜索/查看/安装/调用）。
 *
 * **用途**：
 *   - "最近使用"列表
 *   - "最常使用"排行榜
 *   - 推荐相关项（基于历史）
 *   - 用户体验分析（隐私保护：本地存储，不上传）
 *
 * **存储**：`<app_data>/apex-market-stats/`
 *   - `usage.json` — 按 itemId 累计的统计
 *   - `events.jsonl` — 事件流（按时间）
 */
class UsageStats(private val storageDir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val statsFile = File(storageDir, "usage.json").apply { storageDir.mkdirs() }
    private val eventsFile = File(storageDir, "events.jsonl")

    /** itemId → 累计统计 */
    private val stats = ConcurrentHashMap<String, ItemStats>()
    private val totalEvents = AtomicLong(0)

    init { load() }

    /**
     * 记录一次事件。
     */
    fun record(
        itemId: String,
        name: String,
        category: IntegrationCategory,
        eventType: UsageEventType,
        metadata: Map<String, String> = emptyMap()
    ) {
        val event = UsageEvent(
            itemId = itemId,
            name = name,
            category = category.name,
            eventType = eventType.name,
            timestamp = System.currentTimeMillis(),
            metadata = metadata
        )

        // 更新累计统计
        val current = stats[itemId] ?: ItemStats(itemId, name, category.name)
        val updated = when (eventType) {
            UsageEventType.SEARCH -> current.copy(searchCount = current.searchCount + 1, lastSearchedAt = event.timestamp)
            UsageEventType.VIEW -> current.copy(viewCount = current.viewCount + 1, lastViewedAt = event.timestamp)
            UsageEventType.INSTALL -> current.copy(installCount = current.installCount + 1, lastInstalledAt = event.timestamp, isInstalled = true)
            UsageEventType.UNINSTALL -> current.copy(isInstalled = false, lastInstalledAt = event.timestamp)
            UsageEventType.INVOKE -> current.copy(invokeCount = current.invokeCount + 1, lastInvokedAt = event.timestamp)
            UsageEventType.FAVORITE -> current.copy(favorited = true)
            UsageEventType.UNFAVORITE -> current.copy(favorited = false)
        }
        stats[itemId] = updated

        // 写入事件流（append-only）
        try {
            eventsFile.appendText(json.encodeToString(event) + "\n")
        } catch (t: Throwable) {
            ApexLog.w("market", "[Stats] append event failed: ${t.message}")
        }

        totalEvents.incrementAndGet()

        // 每 10 次事件持久化一次 stats
        if (totalEvents.get() % 10 == 0L) {
            persistStats()
        }
    }

    /**
     * 获取某项的统计。
     */
    fun getStats(itemId: String): ItemStats? = stats[itemId]

    /**
     * "最近使用"列表。
     */
    fun getRecentlyUsed(limit: Int = 20): List<ItemStats> {
        return stats.values
            .filter { it.lastInvokedAt > 0 || it.lastViewedAt > 0 }
            .sortedByDescending { maxOf(it.lastInvokedAt, it.lastViewedAt, it.lastSearchedAt) }
            .take(limit)
    }

    /**
     * "最常使用"排行榜。
     */
    fun getMostUsed(limit: Int = 20): List<ItemStats> {
        return stats.values
            .sortedByDescending { it.invokeCount + it.viewCount * 2 + it.searchCount }
            .take(limit)
    }

    /**
     * 按分类统计使用次数。
     */
    fun getUsageByCategory(): Map<String, Int> {
        return stats.values.groupBy { it.category }
            .mapValues { it.value.sumOf { s -> s.invokeCount + s.viewCount + s.searchCount } }
    }

    /**
     * 获取总统计。
     */
    fun getTotalStats(): TotalStats {
        return TotalStats(
            totalItems = stats.size,
            totalSearches = stats.values.sumOf { it.searchCount },
            totalViews = stats.values.sumOf { it.viewCount },
            totalInstalls = stats.values.sumOf { it.installCount },
            totalInvokes = stats.values.sumOf { it.invokeCount },
            currentlyInstalled = stats.values.count { it.isInstalled },
            currentlyFavorited = stats.values.count { it.favorited }
        )
    }

    /**
     * 读取最近 N 条事件。
     */
    fun getRecentEvents(limit: Int = 100): List<UsageEvent> {
        if (!eventsFile.exists()) return emptyList()
        return try {
            eventsFile.readLines()
                .takeLast(limit)
                .mapNotNull { line ->
                    try { json.decodeFromString(UsageEvent.serializer(), line) } catch (_: Throwable) { null }
                }
                .reversed()
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * 清除所有统计。
     */
    fun clear() {
        stats.clear()
        totalEvents.set(0)
        statsFile.delete()
        eventsFile.delete()
    }

    private fun persistStats() {
        try {
            statsFile.writeText(json.encodeToString(stats.values.toList()))
        } catch (_: Throwable) {}
    }

    private fun load() {
        try {
            if (!statsFile.exists()) return
            val data = json.decodeFromString<List<ItemStats>>(statsFile.readText())
            data.forEach { stats[it.itemId] = it }
        } catch (_: Throwable) {}
    }
}

@Serializable
data class ItemStats(
    val itemId: String,
    val name: String,
    val category: String,
    val searchCount: Int = 0,
    val viewCount: Int = 0,
    val installCount: Int = 0,
    val invokeCount: Int = 0,
    val lastSearchedAt: Long = 0,
    val lastViewedAt: Long = 0,
    val lastInstalledAt: Long = 0,
    val lastInvokedAt: Long = 0,
    val isInstalled: Boolean = false,
    val favorited: Boolean = false
)

@Serializable
data class UsageEvent(
    val itemId: String,
    val name: String,
    val category: String,
    val eventType: String,
    val timestamp: Long,
    val metadata: Map<String, String> = emptyMap()
)

enum class UsageEventType {
    SEARCH, VIEW, INSTALL, UNINSTALL, INVOKE, FAVORITE, UNFAVORITE
}

data class TotalStats(
    val totalItems: Int,
    val totalSearches: Int,
    val totalViews: Int,
    val totalInstalls: Int,
    val totalInvokes: Int,
    val currentlyInstalled: Int,
    val currentlyFavorited: Int
)
