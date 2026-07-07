package com.apex.agent.core.tools.integration

/**
 * 统一集成市场数据模型
 */
data class IntegrationInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String? = null,
    val logoUrl: String? = null,
    val enabled: Boolean = true,
    val capabilities: List<IntegrationCapability> = emptyList(),
    val itemCount: Int = 0,
    val installedCount: Int = 0
)

data class IntegrationCapability(
    val id: String,
    val name: String,
    val description: String,
    val type: CapabilityType
)

enum class CapabilityType {
    BROWSE, SEARCH, INSTALL, UNINSTALL, UPDATE, CATEGORIES, DETAIL, RATING, FEATURED, POPULAR, TRENDING
}

data class UnifiedItem(
    val source: IntegrationSource,
    val sourceId: String,
    val name: String,
    val type: ItemType,
    val description: String,
    val author: String,
    val version: String,
    val tags: List<String> = emptyList(),
    val installConfig: String? = null,
    val installedId: String? = null,
    val isInstalled: Boolean = false,
    val homepage: String? = null,
    val logoUrl: String? = null,
    val rating: Double? = null,
    val installCount: Int? = null,
    val updatedAt: String? = null
)

data class IntegrationSource(
    val id: String,
    val name: String
) {
    companion object {
        val MCP_SO = IntegrationSource("mcp_so", "mcp.so")
        val LOBE_HUB = IntegrationSource("lobehub", "LobeHub")
        val SKILL_REPO = IntegrationSource("skill_repo", "Skill Repository")
        val PLUGIN = IntegrationSource("plugin", "Plugin Marketplace")
    }
}

enum class ItemType(val displayName: String) {
    MCP_SERVER("MCP Server"),
    SKILL("Skill"),
    PLUGIN("Plugin")
}
