package com.apex.agent.integration.category.skills.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * SkillsMP 技能市场。
 *
 * 聚合 70 万+ 开源技能，语义检索匹配需求，npx 一行命令安装，完全免费。
 */
class SkillsMpMarket : IntegrationMarket {

    override val marketId = "skillsmp"
    override val displayName = "SkillsMP"
    override val category = IntegrationCategory.SKILLS
    override val description = "70 万+ 开源技能，语义检索，npx 一行安装"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("前端", "后端", "DevOps", "AI/ML", "移动端", "游戏", "安全")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "skillsmp:react-optimize",
            name = "React 性能优化技能",
            description = "分析 React 组件性能瓶颈，给出优化建议",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "npx @skillsmp/react-optimize",
            tags = listOf("前端", "react", "性能"),
            rating = 4.6,
            downloadCount = 8000,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "skillsmp:docker-deploy",
            name = "Docker 部署技能",
            description = "自动生成 Dockerfile 和 docker-compose 配置",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "npx @skillsmp/docker-deploy",
            tags = listOf("DevOps", "docker"),
            rating = 4.7,
            downloadCount = 10000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "skillsmp:ml-pipeline",
            name = "ML Pipeline 技能",
            description = "构建机器学习训练流水线，数据预处理到模型评估",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "npx @skillsmp/ml-pipeline",
            tags = listOf("AI/ML"),
            rating = 4.5,
            downloadCount = 6000,
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
