package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * GitCode 插件市场。
 *
 * CSDN 旗下代码托管平台，国内直连。
 * - 地址：https://gitcode.com/
 */
class GitCodePluginMarket : IntegrationMarket {

    override val marketId = "gitcode_plugins"
    override val displayName = "GitCode"
    override val category = IntegrationCategory.PLUGINS
    override val description = "CSDN 旗下代码托管平台，国内直连"
    override val iconUrl = "https://gitcode.com/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("AI", "大数据", "云原生", "前端", "后端", "数据库")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "gitcode:ai-toolkit",
            name = "AI 开发工具包",
            description = "国内 AI 开发工具集，适配国产模型",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://gitcode.com/ai-toolkit",
            tags = listOf("AI", "国内", "工具"),
            rating = 4.4,
            downloadCount = 5000,
            metadata = mapOf("region" to "cn")
        ),
        MarketItem(
            id = "gitcode:bigdata",
            name = "大数据组件集",
            description = "Hadoop/Spark/Flink 国内适配版",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://gitcode.com/bigdata",
            tags = listOf("大数据", "国内"),
            rating = 4.3,
            downloadCount = 4000,
            metadata = mapOf("region" to "cn")
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
