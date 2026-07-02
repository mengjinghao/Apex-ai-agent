package com.apex.agent.integration.category.plugins.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * Airbyte Agent Engine 数据源连接器市场。
 *
 * - 600+ 数据源连接器
 * - 开源免费
 * - 可导出 MCP 服务给 Agent 做数据查询技能
 * - 地址：https://airbyte.com/
 */
class AirbytePluginMarket : IntegrationMarket {

    override val marketId = "airbyte"
    override val displayName = "Airbyte"
    override val category = IntegrationCategory.PLUGINS
    override val description = "600+ 数据源连接器，开源免费，导出 MCP 数据查询技能"
    override val iconUrl = "https://airbyte.com/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("数据库", "SaaS", "文件", "API", "消息队列", "数据仓库")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "airbyte:postgres",
            name = "PostgreSQL 连接器",
            description = "PostgreSQL 数据源连接，导出 MCP 查询服务",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            sourceType = IntegrationSourceType.GIT_REPOSITORY,
            downloadUrl = "https://github.com/airbytehq/airbyte/tree/master/airbyte-integrations/connectors/source-postgres",
            tags = listOf("数据库", "postgres", "开源"),
            rating = 4.6,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("license" to "MIT", "type" to "source")
        ),
        MarketItem(
            id = "airbyte:mysql",
            name = "MySQL 连接器",
            description = "MySQL 数据源连接，导出 MCP 查询服务",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://github.com/airbytehq/airbyte/tree/master/airbyte-integrations/connectors/source-mysql",
            tags = listOf("数据库", "mysql", "开源"),
            rating = 4.5,
            downloadCount = 6000,
            verified = true,
            metadata = mapOf("license" to "MIT", "type" to "source")
        ),
        MarketItem(
            id = "airbyte:google-sheets",
            name = "Google Sheets 连接器",
            description = "Google Sheets 数据读取，导出 MCP 查询服务",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://github.com/airbytehq/airbyte/tree/master/airbyte-integrations/connectors/source-google-sheets",
            tags = listOf("SaaS", "google-sheets", "开源"),
            rating = 4.4,
            downloadCount = 5000,
            metadata = mapOf("license" to "MIT", "type" to "source")
        ),
        MarketItem(
            id = "airbyte:s3",
            name = "S3 连接器",
            description = "AWS S3 文件数据源，导出 MCP 文件查询",
            version = "1.0.0",
            category = IntegrationCategory.PLUGINS,
            marketId = marketId,
            downloadUrl = "https://github.com/airbytehq/airbyte/tree/master/airbyte-integrations/connectors/source-s3",
            tags = listOf("文件", "s3", "开源"),
            rating = 4.3,
            downloadCount = 4000,
            metadata = mapOf("license" to "MIT", "type" to "source")
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
