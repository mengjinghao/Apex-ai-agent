package com.apex.agent.integration.category.skills.markets

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationMarket
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult

/**
 * LobeHub Skills 技能市场。
 *
 * - 规模：23 万+ SKILL.md 标准技能包
 * - 免费：全量免费浏览、一键下载，配套 CLI 批量导入
 * - 兼容：OpenClaw / Cursor / Apex-Agent
 * - 地址：https://lobehub.com/
 */
class LobeHubSkillsMarket : IntegrationMarket {

    override val marketId = "lobehub_skills"
    override val displayName = "LobeHub Skills"
    override val category = IntegrationCategory.SKILLS
    override val description = "全球最大技能市场，23 万+ SKILL.md 标准技能包"
    override val iconUrl = "https://lobehub.com/favicon.ico"
    override val requiresNetwork = true

    override suspend fun search(filter: MarketSearchFilter): MarketSearchResult {
        val items = builtinItems().filter { matchFilter(it, filter) }
        return MarketSearchResult(items = items, totalCount = items.size, hasMore = false)
    }

    override suspend fun getItem(itemId: String): MarketItem? = builtinItems().find { it.id == itemId }

    override suspend fun getCategories(): List<String> =
        listOf("开发", "办公", "数据分析", "自动化", "写作", "翻译", "设计", "营销")

    private fun builtinItems(): List<MarketItem> = listOf(
        MarketItem(
            id = "lobehub:code-review",
            name = "代码审查技能",
            description = "深度代码审查，发现潜在 bug、性能问题、安全漏洞",
            author = "LobeHub",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            sourceType = IntegrationSourceType.OFFICIAL_MARKET,
            downloadUrl = "https://lobehub.com/skills/code-review",
            tags = listOf("开发", "代码审查"),
            rating = 4.8,
            downloadCount = 25000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "lobehub:data-analysis",
            name = "数据分析技能",
            description = "CSV/Excel 数据分析，自动生成图表和洞察",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://lobehub.com/skills/data-analysis",
            tags = listOf("数据分析"),
            rating = 4.7,
            downloadCount = 18000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "lobehub:auto-test",
            name = "自动化测试技能",
            description = "自动生成单元测试、集成测试、E2E 测试",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://lobehub.com/skills/auto-test",
            tags = listOf("自动化", "测试"),
            rating = 4.6,
            downloadCount = 15000,
            verified = true,
            metadata = mapOf("format" to "SKILL.md")
        ),
        MarketItem(
            id = "lobehub:translation",
            name = "多语言翻译技能",
            description = "高质量多语言翻译，保留术语和格式",
            version = "1.0.0",
            category = IntegrationCategory.SKILLS,
            marketId = marketId,
            downloadUrl = "https://lobehub.com/skills/translation",
            tags = listOf("翻译"),
            rating = 4.5,
            downloadCount = 20000,
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
