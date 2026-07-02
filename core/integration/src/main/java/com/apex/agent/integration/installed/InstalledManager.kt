package com.apex.agent.integration.installed

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationItemState
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 已安装的集成项。
 *
 * @property id 集成项 ID
 * @property name 显示名称
 * @property category 所属分类
 * @property marketId 来源市场 ID
 * @property installedVersion 已安装版本
 * @property latestVersion 最新版本（用于更新检查）
 * @property installedAt 安装时间戳
 * @property installedPath 安装路径
 * @property sourceType 来源类型
 * @property sourceUrl 来源 URL
 * @property enabled 是否启用
 * @property metadata 附加元数据
 */
data class InstalledItem(
    val id: String,
    val name: String,
    val category: IntegrationCategory,
    val marketId: String,
    val installedVersion: String,
    val latestVersion: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
    val installedPath: String? = null,
    val sourceType: IntegrationSourceType = IntegrationSourceType.OFFICIAL_MARKET,
    val sourceUrl: String? = null,
    val enabled: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 是否有更新。
     */
    val hasUpdate: Boolean
        get() = latestVersion != null && latestVersion != installedVersion

    /**
     * 转换为状态。
     */
    fun toState(): IntegrationItemState {
        return if (enabled) IntegrationItemState.INSTALLED else IntegrationItemState.DISABLED
    }
}

/**
 * 已安装项的快照（用于 UI 展示）。
 */
data class InstalledSnapshot(
    val items: List<InstalledItem>,
    val totalCount: Int,
    val byCategory: Map<IntegrationCategory, Int>
) {
    companion object {
        val EMPTY = InstalledSnapshot(
            items = emptyList(),
            totalCount = 0,
            byCategory = emptyMap()
        )
    }
}

/**
 * 已安装管理器。
 *
 * 管理所有已安装的集成项，支持：
 * - 安装/卸载
 * - 启用/禁用
 * - 更新检查
 * - 按分类查询
 * - 状态观察
 *
 * # 使用示例
 *
 * ```
 * val manager = InstalledManager()
 *
 * // 安装
 * manager.install(marketItem)
 *
 * // 查询已安装
 * val installed = manager.getByCategory(IntegrationCategory.SKILLS)
 *
 * // 卸载
 * manager.uninstall("item_id")
 *
 * // 观察变化
 * manager.snapshot.collect { snap ->
 *     println("已安装: ${snap.totalCount}")
 * }
 * ```
 */
class InstalledManager {

    private val items = ConcurrentHashMap<String, InstalledItem>()

    private val _snapshot = MutableStateFlow(InstalledSnapshot.EMPTY)
    val snapshot: StateFlow<InstalledSnapshot> = _snapshot.asStateFlow()

    /**
     * 安装一个集成项。
     *
     * @param item 市场项
     * @param installPath 安装路径（可选）
     * @return true 安装成功，false 已安装
     */
    fun install(item: MarketItem, installPath: String? = null): Boolean {
        val installed = InstalledItem(
            id = item.id,
            name = item.name,
            category = item.category,
            marketId = item.marketId,
            installedVersion = item.version,
            latestVersion = item.version,
            installedPath = installPath,
            sourceType = item.sourceType,
            sourceUrl = item.sourceUrl,
            metadata = item.metadata
        )
        val existing = items.putIfAbsent(item.id, installed)
        if (existing != null) return false
        updateSnapshot()
        return true
    }

    /**
     * 卸载。
     */
    fun uninstall(itemId: String): Boolean {
        val removed = items.remove(itemId) != null
        if (removed) updateSnapshot()
        return removed
    }

    /**
     * 批量卸载。
     */
    fun uninstallByCategory(category: IntegrationCategory): Int {
        val toRemove = items.entries.filter { it.value.category == category }.map { it.key }
        for (id in toRemove) items.remove(id)
        if (toRemove.isNotEmpty()) updateSnapshot()
        return toRemove.size
    }

    /**
     * 启用/禁用。
     */
    fun setEnabled(itemId: String, enabled: Boolean): Boolean {
        val item = items[itemId] ?: return false
        items[itemId] = item.copy(enabled = enabled)
        updateSnapshot()
        return true
    }

    /**
     * 更新已安装项的最新版本信息（用于更新检查）。
     */
    fun updateLatestVersion(itemId: String, latestVersion: String): Boolean {
        val item = items[itemId] ?: return false
        items[itemId] = item.copy(latestVersion = latestVersion)
        updateSnapshot()
        return true
    }

    /**
     * 更新已安装项的版本（执行更新后调用）。
     */
    fun updateVersion(itemId: String, newVersion: String): Boolean {
        val item = items[itemId] ?: return false
        items[itemId] = item.copy(
            installedVersion = newVersion,
            latestVersion = newVersion
        )
        updateSnapshot()
        return true
    }

    /**
     * 获取单个已安装项。
     */
    fun get(itemId: String): InstalledItem? = items[itemId]

    /**
     * 获取所有已安装项。
     */
    fun getAll(): List<InstalledItem> = items.values.toList()

    /**
     * 按分类获取。
     */
    fun getByCategory(category: IntegrationCategory): List<InstalledItem> {
        return items.values.filter { it.category == category }
    }

    /**
     * 按市场获取。
     */
    fun getByMarket(marketId: String): List<InstalledItem> {
        return items.values.filter { it.marketId == marketId }
    }

    /**
     * 获取需要更新的项。
     */
    fun getUpdatable(): List<InstalledItem> {
        return items.values.filter { it.hasUpdate }
    }

    /**
     * 获取已启用的项。
     */
    fun getEnabled(): List<InstalledItem> = items.values.filter { it.enabled }

    /**
     * 获取已禁用的项。
     */
    fun getDisabled(): List<InstalledItem> = items.values.filter { !it.enabled }

    /**
     * 检查是否已安装。
     */
    fun isInstalled(itemId: String): Boolean = items.containsKey(itemId)

    /**
     * 已安装总数。
     */
    fun count(): Int = items.size

    /**
     * 按分类统计。
     */
    fun countByCategory(category: IntegrationCategory): Int {
        return items.values.count { it.category == category }
    }

    /**
     * 清空所有已安装项。
     */
    fun clear() {
        items.clear()
        updateSnapshot()
    }

    private fun updateSnapshot() {
        val allItems = items.values.toList()
        val byCategory = allItems.groupingBy { it.category }.eachCount()
        _snapshot.value = InstalledSnapshot(
            items = allItems,
            totalCount = allItems.size,
            byCategory = byCategory
        )
    }
}
