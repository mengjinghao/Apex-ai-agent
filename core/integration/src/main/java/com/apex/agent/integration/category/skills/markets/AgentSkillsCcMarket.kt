package com.apex.agent.integration.category.skills.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * AgentSkills.cc 技能市场（Claude 官方技能聚合）。
 *
 * 6.3 万+ 生产级开发技能，内置"自动生成新技能"元技能，免费克隆本地使用。
 */
class AgentSkillsCcMarket : IntegrationMarket {

    override val marketId = "agentskills_cc"
    override val displayName = "AgentSkills.cc"
    override val category = IntegrationCategory.SKILLS
    override val description = "Claude 官方技能聚合，6.3 万+ 生产级开发技能"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("代码生成", "API 设计", "数据库", "测试", "部署", "文档", "调试")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "agentskills:meta-skill-gen",
            name = "元技能生成器",
            description = "自动生成新技能的元技能，根据需求创建 SKILL.md",
            author = "AgentSkills.cc",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://agentskills.cc/meta-gen",
            tags = listOf("代码生成", "元技能"),
            rating = 4.9,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md", "special" to "meta")
        ),
        MarketItem(
            id = "agentskills:api-design",
            name = "API 设计技能",
            description = "RESTful API 设计最佳实践，自动生成 OpenAPI 规范",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://agentskills.cc/api-design",
            tags = listOf("API 设计"),
            rating = 4.7,
            downloadCount = 9000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "agentskills:debug-master",
            name = "调试大师技能",
            description = "系统化调试方法论，快速定位复杂 bug",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://agentskills.cc/debug-master",
            tags = listOf("调试"),
            rating = 4.8,
            downloadCount = 12000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
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
