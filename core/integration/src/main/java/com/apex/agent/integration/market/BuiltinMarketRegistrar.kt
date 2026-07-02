package com.apex.agent.integration.market

import com.apex.agent.integration.category.mcp.markets.AiSkillStoreMcpMarket
import com.apex.agent.integration.category.mcp.markets.AwesomeMcpServersMarket
import com.apex.agent.integration.category.mcp.markets.BuiltinMcpMarket
import com.apex.agent.integration.category.mcp.markets.GlamaMcpMarket
import com.apex.agent.integration.category.mcp.markets.McpMarketCnMarket
import com.apex.agent.integration.category.mcp.markets.McpSoMarket
import com.apex.agent.integration.category.mcp.markets.ModelScopeMcpMarket
import com.apex.agent.integration.category.mcp.markets.SmitheryMcpMarket
import com.apex.agent.integration.category.models.markets.AgnesAiModelMarket
import com.apex.agent.integration.category.models.markets.BuiltinModelMarket
import com.apex.agent.integration.category.models.markets.DeepSeekModelMarket
import com.apex.agent.integration.category.models.markets.HuggingFaceModelMarket
import com.apex.agent.integration.category.models.markets.ModelScopeModelMarket
import com.apex.agent.integration.category.plugins.markets.AiBasePluginMarket
import com.apex.agent.integration.category.plugins.markets.AgentHubPluginMarket
import com.apex.agent.integration.category.plugins.markets.AirbytePluginMarket
import com.apex.agent.integration.category.plugins.markets.ComposioPluginMarket
import com.apex.agent.integration.category.plugins.markets.GitCodePluginMarket
import com.apex.agent.integration.category.plugins.markets.GitHubPluginMarket
import com.apex.agent.integration.category.plugins.markets.GiteePluginMarket
import com.apex.agent.integration.category.plugins.markets.HuggingFacePluginMarket
import com.apex.agent.integration.category.plugins.markets.IflowPluginMarket
import com.apex.agent.integration.category.plugins.markets.NamiAiPluginMarket
import com.apex.agent.integration.category.skills.markets.AgentSkillShMarket
import com.apex.agent.integration.category.skills.markets.AgentSkillsCcMarket
import com.apex.agent.integration.category.skills.markets.LobeHubSkillsMarket
import com.apex.agent.integration.category.skills.markets.SkillsMpMarket

/**
 * 内置市场注册器。
 *
 * 注册所有内置的市场实现，让 [IntegrationCenter.create()] 后立即可用。
 *
 * 包含 4 大分类共 19 个市场：
 * - MCP (6): Smithery / mcp.so / ModelScope / Glama / AISkillStore / MCP星球 / awesome-mcp
 * - Skills (4): LobeHub / SkillsMP / AgentSkills.cc / AgentSkill.sh
 * - Plugins (4): GitHub / Gitee / GitCode / HuggingFace
 * - Models (4): 魔搭 / Agnes AI / DeepSeek / HuggingFace
 */
object BuiltinMarketRegistrar {

    private var registered = false

    /**
     * 注册所有内置市场。
     *
     * 幂等操作，重复调用无副作用。
     */
    fun registerAll() {
        if (registered) return
        synchronized(this) {
            if (registered) return

            // MCP 市场 (8)
            MarketRegistry.register(BuiltinMcpMarket())
            MarketRegistry.register(SmitheryMcpMarket())
            MarketRegistry.register(McpSoMarket())
            MarketRegistry.register(ModelScopeMcpMarket())
            MarketRegistry.register(GlamaMcpMarket())
            MarketRegistry.register(AiSkillStoreMcpMarket())
            MarketRegistry.register(McpMarketCnMarket())
            MarketRegistry.register(AwesomeMcpServersMarket())

            // Skills 市场 (4)
            MarketRegistry.register(LobeHubSkillsMarket())
            MarketRegistry.register(SkillsMpMarket())
            MarketRegistry.register(AgentSkillsCcMarket())
            MarketRegistry.register(AgentSkillShMarket())

            // Plugins 市场 (10)
            MarketRegistry.register(GitHubPluginMarket())
            MarketRegistry.register(GiteePluginMarket())
            MarketRegistry.register(GitCodePluginMarket())
            MarketRegistry.register(HuggingFacePluginMarket())
            MarketRegistry.register(ComposioPluginMarket())
            MarketRegistry.register(NamiAiPluginMarket())
            MarketRegistry.register(IflowPluginMarket())
            MarketRegistry.register(AgentHubPluginMarket())
            MarketRegistry.register(AiBasePluginMarket())
            MarketRegistry.register(AirbytePluginMarket())

            // Models 市场 (5)
            MarketRegistry.register(BuiltinModelMarket())
            MarketRegistry.register(ModelScopeModelMarket())
            MarketRegistry.register(AgnesAiModelMarket())
            MarketRegistry.register(DeepSeekModelMarket())
            MarketRegistry.register(HuggingFaceModelMarket())

            registered = true
        }
    }

    /**
     * 检查是否已注册。
     */
    fun isRegistered(): Boolean = registered

    /**
     * 获取内置市场清单。
     */
    fun getBuiltinMarketIds(): List<String> = listOf(
        // MCP (8)
        "builtin_mcp", "smithery", "mcp_so", "modelscope_mcp", "glama", "aiskillstore", "mcp_market_cn", "awesome_mcp",
        // Skills (4)
        "lobehub_skills", "skillsmp", "agentskills_cc", "agentskill_sh",
        // Plugins (10)
        "github_plugins", "gitee_plugins", "gitcode_plugins", "hf_plugins",
        "composio", "nami_ai", "iflow", "agenthub", "aibase", "airbyte",
        // Models (5)
        "builtin_models", "modelscope_models", "agnes_ai", "deepseek", "huggingface"
    )
}
