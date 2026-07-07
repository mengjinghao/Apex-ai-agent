package com.apex.apk.market.favorites

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.MarketItem
import com.apex.sdk.common.ApexLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 收藏夹 — 让用户收藏感兴趣的市场项，方便后续查找。
 *
 * **功能**：
 *   - 添加/移除收藏
 *   - 按分类筛选收藏
 *   - 检查是否已收藏
 *   - 跨会话持久化
 *
 * **存储**：`<app_data>/apex-market-favorites/favorites.json`
 */
class Favorites(private val storageDir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(storageDir, "favorites.json").apply { storageDir.mkdirs() }
    private val favorites = ConcurrentHashMap<String, FavoriteEntry>()

    init { load() }

    /**
     * 添加收藏。
     */
    fun add(item: MarketItem, note: String = ""): Boolean {
        val entry = FavoriteEntry(
            itemId = item.id,
            name = item.name,
            description = item.description,
            category = item.category.name,
            marketId = item.marketId,
            version = item.version,
            addedAt = System.currentTimeMillis(),
            note = note
        )
        favorites[item.id] = entry
        persist()
        ApexLog.d("market", "[Favorites] added: ${item.id}")
        return true
    }

    /**
     * 移除收藏。
     */
    fun remove(itemId: String): Boolean {
        val removed = favorites.remove(itemId) != null
        if (removed) persist()
        return removed
    }

    /**
     * 是否已收藏。
     */
    fun isFavorite(itemId: String): Boolean = favorites.containsKey(itemId)

    /**
     * 切换收藏状态。
     * @return 切换后是否为收藏
     */
    fun toggle(item: MarketItem): Boolean {
        return if (isFavorite(item.id)) {
            remove(item.id)
            false
        } else {
            add(item)
            true
        }
    }

    /**
     * 列出所有收藏。
     */
    fun listAll(): List<FavoriteEntry> = favorites.values.sortedByDescending { it.addedAt }

    /**
     * 按分类列出收藏。
     */
    fun listByCategory(category: IntegrationCategory): List<FavoriteEntry> {
        return favorites.values.filter { it.category == category.name }.sortedByDescending { it.addedAt }
    }

    /**
     * 搜索收藏（按名称/描述/note）。
     */
    fun search(query: String): List<FavoriteEntry> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return listAll()
        return favorites.values.filter {
            it.name.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.note.lowercase().contains(q)
        }.sortedByDescending { it.addedAt }
    }

    /**
     * 更新收藏备注。
     */
    fun updateNote(itemId: String, note: String): Boolean {
        val entry = favorites[itemId] ?: return false
        favorites[itemId] = entry.copy(note = note)
        persist()
        return true
    }

    /**
     * 清空所有收藏。
     */
    fun clear(): Int {
        val count = favorites.size
        favorites.clear()
        persist()
        return count
    }

    /**
     * 收藏总数。
     */
    fun count(): Int = favorites.size

    /**
     * 按分类统计。
     */
    fun countByCategory(): Map<String, Int> {
        return favorites.values.groupBy { it.category }.mapValues { it.value.size }
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(favorites.values.toList()))
        } catch (t: Throwable) {
            ApexLog.w("market", "[Favorites] persist failed: ${t.message}")
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val data = json.decodeFromString<List<FavoriteEntry>>(file.readText())
            data.forEach { favorites[it.itemId] = it }
        } catch (_: Throwable) {}
    }
}

@Serializable
data class FavoriteEntry(
    val itemId: String,
    val name: String,
    val description: String,
    val category: String,
    val marketId: String,
    val version: String,
    val addedAt: Long,
    val note: String = ""
)
