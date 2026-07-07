package com.apex.agent.integration.category.skills.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * AgentSkill.sh 技能市场。
 *
 * 10 万+ 跨平台技能，双层安全校验，支持 25 款 Agent 客户端，免费社区技能无额度限制。
 */
class AgentSkillShMarket : IntegrationMarket {

    override val marketId = "agentskill_sh"
    override val displayName = "AgentSkill.sh"
    override val category = IntegrationCategory.SKILLS
    override val description = "10 万+ 跨平台技能，双层安全校验，25 款 Agent 兼容"
    override val iconUrl = null
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("安全", "性能", "架构", "重构", "CI/CD", "监控", "文档")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "agentskill:security-audit",
            name = "安全审计技能",
            description = "代码安全审计，检测 OWASP Top 10 漏洞",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://agentskill.sh/security-audit",
            tags = listOf("安全", "审计"),
            rating = 4.8,
            downloadCount = 11000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md", "security" to "verified")
        ),
        MarketItem(
            id = "agentskill:arch-design",
            name = "架构设计技能",
            description = "系统架构设计，DDD/Clean Architecture/微服务",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://agentskill.sh/arch-design",
            tags = listOf("架构"),
            rating = 4.7,
            downloadCount = 8000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "agentskill:ci-cd",
            name = "CI/CD 流水线技能",
            description = "自动设计 GitHub Actions / GitLab CI 流水线",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://agentskill.sh/ci-cd",
            tags = listOf("CI/CD"),
            rating = 4.6,
            downloadCount = 7000,
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
