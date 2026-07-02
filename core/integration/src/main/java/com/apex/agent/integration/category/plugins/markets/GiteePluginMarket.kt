package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Gitee 插件市场。
 *
 * 国内代码托管平台，国内直连速度快。
 * - 地址：https://gitee.com/
 */
class GiteePluginMarket : IntegrationMarket {

    override val marketId = "gitee_plugins"
    override val displayName = "Gitee"
    override val category = IntegrationCategory.PLUGINS
    override val description = "国内代码托管平台，国内直连，开源插件"
    override val iconUrl = "https://gitee.com/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("国内工具", "中文", "Spring", "Android", "微信", "支付宝")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "gitee:weixin-tools",
            name = "微信开发工具集",
            description = "微信公众号/小程序/支付开发工具",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://gitee.com/mirrors/weixin-java-tools",
            tags = listOf("微信", "国内", "Java"),
            rating = 4.5,
            downloadCount = 10000,
            verified = true,
            metadata = mapOf("region" to "cn", "language" to "Java")
        ),
        MarketItem(
            id = "gitee:alipay-sdk",
            name = "支付宝 SDK",
            description = "支付宝支付/转账/查询 SDK",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://gitee.com/alipay/alipay-sdk",
            tags = listOf("支付宝", "国内", "支付"),
            rating = 4.4,
            downloadCount = 6000,
            verified = true,
            metadata = mapOf("region" to "cn")
        ),
        MarketItem(
            id = "gitee:android-utils",
            name = "Android 工具集",
            description = "Android 开发常用工具类集合",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://gitee.com/android-utils",
            tags = listOf("Android", "国内", "工具"),
            rating = 4.3,
            downloadCount = 8000,
            metadata = mapOf("region" to "cn", "language" to "Kotlin")
        )
    )

    private fun matchFilter(item: MarketItem, filter: MarketSearchFilter): Boolean {
        if (filter.query.isNotBlank() && filter.query !in item.name && filter.query !in item.description) return false
        if (filter.tags.isNotEmpty() && filter.tags.intersect(item.tags.toSet()).isEmpty()) return false
        if (filter.verifiedOnly && !item.verified) return false
        if (item.rating < filter.minRating) return false
        return true
    }
}
