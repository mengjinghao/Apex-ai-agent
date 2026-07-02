package com.apex.agent.integration.market

import com.apex.agent.integration.api.IntegrationCategory

/**
 * 集成市场。
 *
 * 一个市场对应一个"小模块"——某个集成平台的市场。
 * 例如：
 * - Skills 分类下：Apex 技能市场、社区技能市场、GitHub 技能市场
 * - MCP 分类下：mcp.so 市场、官方 MCP 市场
 * - 模型平台下：DeepSeek、Claude、OpenAI、本地模型
 *
 * 每个 [IntegrationCategory] 下可以注册多个市场，业务侧通过 [MarketRegistry] 管理。
 *
 * # 实现自定义市场
 *
 * ```
 * class MySkillMarket : IntegrationMarket {
 *     override val marketId = "my_skill_market"
 *     override val displayName = "我的技能市场"
 *     override val category = IntegrationCategory.SKILLS
 *
 *     override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
 *         // 实现搜索逻辑
 *     }
 *
 *     override suspend fun getItem(itemId: String): MarketItem? {
 *         // 获取单个项详情
 *     }
 *
 *     override suspend fun getCategories(): List<String> {
 *         // 返回市场内的分类标签
 *     }
 * }
 * ```
 */
interface IntegrationMarket {

    /** 市场唯一 ID。 */
    val marketId: String

    /** 显示名称。 */
    val displayName: String

    /** 所属大分类。 */
    val category: IntegrationCategory

    /** 市场描述。 */
    val description: String

    /** 市场图标 URL（可选）。 */
    val iconUrl: String?

    /** 是否需要网络访问。 */
    val requiresNetwork: Boolean

    /**
     * 搜索市场中的项目。
     *
     * @param filter 搜索过滤条件
     * @return 搜索结果
     */
    suspend fun search(filter: MarketSearchFilter = MarketSearchFilter()): MarketSearchResult

    /**
     * 获取单个项目详情。
     *
     * @param itemId 项目 ID
     * @return 项目信息，不存在返回 null
     */
    suspend fun getItem(itemId: String): MarketItem?

    /**
     * 获取市场内的分类标签（用于 UI 展示分类筛选）。
     *
     * @return 标签列表
     */
    suspend fun getCategories(): List<String>

    /**
     * 获取推荐项目（首页展示）。
     *
     * @param limit 数量限制
     * @return 推荐项目列表
     */
    suspend fun getFeatured(limit: Int = 10): List<MarketItem> {
        // 默认实现：搜索热门
        return search(MarketSearchFilter(sortBy = SortBy.POPULARITY, pageSize = limit)).items
    }

    /**
     * 获取最新项目。
     */
    suspend fun getLatest(limit: Int = 10): List<MarketItem> {
        return search(MarketSearchFilter(sortBy = SortBy.NEWEST, pageSize = limit)).items
    }

    /**
     * 检查市场是否可用（如网络可达、API key 有效等）。
     */
    suspend fun isAvailable(): Boolean = true

    /**
     * 刷新市场缓存。
     */
    suspend fun refresh() {}
}

/**
 * 市场元信息（不含数据，用于列表展示）。
 */
data class MarketInfo(
    val marketId: String,
    val displayName: String,
    val category: IntegrationCategory,
    val description: String,
    val iconUrl: String?,
    val requiresNetwork: Boolean,
    val available: Boolean = true
) {
    companion object {
        fun fromMarket(market: IntegrationMarket, available: Boolean = true): MarketInfo {
            return MarketInfo(
                marketId = market.marketId,
                displayName = market.displayName,
                category = market.category,
                description = market.description,
                iconUrl = market.iconUrl,
                requiresNetwork = market.requiresNetwork,
                available = available
            )
        }
    }
}
