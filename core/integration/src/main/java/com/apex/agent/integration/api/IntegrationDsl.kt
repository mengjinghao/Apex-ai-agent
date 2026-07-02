package com.apex.agent.integration.api

import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketRegistry
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * 集成中心 DSL。
 *
 * 提供简洁的 Kotlin DSL 语法来注册市场和操作集成。
 *
 * # 使用示例
 *
 * ```
 * integrationCenter {
 *     // 注册市场
 *     registerMarket {
 *         marketId = "my_skill_market"
 *         displayName = "我的技能市场"
 *         category = IntegrationCategory.SKILLS
 *         description = "自定义技能市场"
 *
 *         onSearch { filter ->
 *             MarketSearchResult(items = myItems, totalCount = myItems.size)
 *         }
 *
 *         onGetItem { id ->
 *             myItems.find { it.id == id }
 *         }
 *     }
 *
 *     // 批量注册
 *     registerMarkets(listOf(market1, market2, market3))
 * }
 * ```
 */
@DslMarker
annotation class IntegrationDsl

/**
 * DSL 顶层入口。
 */
fun integrationCenter(init: IntegrationDslBuilder.() -> Unit) {
    IntegrationDslBuilder().apply(init)
}

/**
 * DSL 构建器。
 */
@IntegrationDsl
class IntegrationDslBuilder {

    /**
     * 注册自定义市场（DSL 风格）。
     */
    fun registerMarket(init: MarketBuilder.() -> Unit) {
        val market = MarketBuilder().apply(init).build()
        MarketRegistry.register(market)
    }

    /**
     * 注册已有的市场实例。
     */
    fun registerMarket(market: IntegrationMarket) {
        MarketRegistry.register(market)
    }

    /**
     * 批量注册。
     */
    fun registerMarkets(markets: List<IntegrationMarket>) {
        MarketRegistry.registerAll(markets)
    }

    /**
     * 自动发现（ServiceLoader）。
     */
    fun autoDiscover() {
        MarketRegistry.autoDiscover()
    }
}

/**
 * 市场 DSL 构建器。
 *
 * 用 DSL 语法快速创建市场实例，无需实现 [IntegrationMarket] 接口。
 */
@IntegrationDsl
class MarketBuilder {

    var marketId: String = ""
    var displayName: String = ""
    var category: IntegrationCategory = IntegrationCategory.SKILLS
    var description: String = ""
    var iconUrl: String? = null
    var requiresNetwork: Boolean = true

    private var searchHandler: (suspend (MarketSearchFilter) -> MarketSearchResult)? = null
    private var getItemHandler: (suspend (String) -> MarketItem?)? = null
    private var categoriesHandler: (suspend () -> List<String>)? = null
    private var availableHandler: (suspend () -> Boolean)? = null
    private var refreshHandler: (suspend () -> Unit)? = null

    /**
     * 设置搜索处理函数。
     */
    fun onSearch(handler: suspend (MarketSearchFilter) -> MarketSearchResult) {
        searchHandler = handler
    }

    /**
     * 设置获取单项处理函数。
     */
    fun onGetItem(handler: suspend (String) -> MarketItem?) {
        getItemHandler = handler
    }

    /**
     * 设置获取分类标签处理函数。
     */
    fun onGetCategories(handler: suspend () -> List<String>) {
        categoriesHandler = handler
    }

    /**
     * 设置可用性检查函数。
     */
    fun onAvailable(handler: suspend () -> Boolean) {
        availableHandler = handler
    }

    /**
     * 设置刷新函数。
     */
    fun onRefresh(handler: suspend () -> Unit) {
        refreshHandler = handler
    }

    internal fun build(): IntegrationMarket {
        require(marketId.isNotBlank()) { "marketId is required" }
        require(displayName.isNotBlank()) { "displayName is required" }

        val builtId = marketId
        val builtName = displayName
        val builtCategory = category
        val builtDesc = description
        val builtIcon = iconUrl
        val builtRequiresNet = requiresNetwork
        val builtSearch = searchHandler
        val builtGetItem = getItemHandler
        val builtCategories = categoriesHandler
        val builtAvailable = availableHandler
        val builtRefresh = refreshHandler

        return object : IntegrationMarket {
            override val marketId = builtId
            override val displayName = builtName
            override val category = builtCategory
            override val description = builtDesc
            override val iconUrl = builtIcon
            override val requiresNetwork = builtRequiresNet

            override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
                return builtSearch?.invoke(filter) ?: MarketSearchResult.EMPTY
            }

            override suspend fun getItem(itemId: String): MarketItem? {
                return builtGetItem?.invoke(itemId)
            }

            override suspend fun getCategories(): List<String> {
                return builtCategories?.invoke() ?: emptyList()
            }

            override suspend fun isAvailable(): Boolean {
                return builtAvailable?.invoke() ?: true
            }

            override suspend fun refresh() {
                builtRefresh?.invoke()
            }
        }
    }
}

/**
 * 市场项 DSL 构建器。
 *
 * ```
 * val item = marketItem {
 *     id = "my_skill"
 *     name = "我的技能"
 *     description = "自定义技能"
 *     version = "1.0.0"
 *     category = IntegrationCategory.SKILLS
 *     marketId = "my_market"
 *     downloadUrl = "https://example.com/skill.zip"
 *     tags = listOf("reasoning", "analysis")
 * }
 * ```
 */
@IntegrationDsl
class MarketItemBuilder {

    var id: String = ""
    var name: String = ""
    var description: String = ""
    var author: String = ""
    var version: String = "1.0.0"
    var category: IntegrationCategory = IntegrationCategory.SKILLS
    var marketId: String = ""
    var downloadUrl: String = ""
    var iconUrl: String? = null
    var tags: List<String> = emptyList()
    var verified: Boolean = false
    var rating: Double = 0.0
    var downloadCount: Long = 0
    var metadata: Map<String, String> = emptyMap()

    internal fun build(): MarketItem {
        require(id.isNotBlank()) { "id is required" }
        require(name.isNotBlank()) { "name is required" }
        require(marketId.isNotBlank()) { "marketId is required" }

        return MarketItem(
            id = id,
            name = name,
            description = description,
            author = author,
            version = version,
            category = category,
            marketId = marketId,
            downloadUrl = downloadUrl,
            iconUrl = iconUrl,
            tags = tags,
            verified = verified,
            rating = rating,
            downloadCount = downloadCount,
            metadata = metadata
        )
    }
}

/**
 * 创建市场项的 DSL 入口。
 */
fun marketItem(init: MarketItemBuilder.() -> Unit): MarketItem {
    return MarketItemBuilder().apply(init).build()
}
