package com.apex.lib.market

/**
 * 市场目录 — 27 个内置市场的元数据注册表。
 *
 * 与 APK 层的 `IntegrationCenter` 注册逻辑对齐（4 SKILLS + 8 MCP + 10 PLUGINS + 5 MODELS = 27）。
 * lib:market 不依赖 `core:integration`，因此此处维护一份"纯元数据"镜像，
 * 用于：
 *   - 在 APK 启动前预展示市场列表（UI 占位）
 *   - 离线浏览时列出所有市场
 *   - 按分类枚举 / 搜索
 *
 * 真实搜索结果仍由 APK 通过 [com.apex.lib.market.MarketFetcher]（接口）注入。
 */
object MarketCatalog {

    /** 单个市场的元数据。 */
    data class Entry(
        val marketId: String,
        val displayName: String,
        val category: MarketCategory,
        val description: String,
        val sourceUrl: String,
        val requiresNetwork: Boolean = true,
        val iconUrl: String? = null,
        val builtin: Boolean = true
    )

    // ============================================================
    // 4 个 SKILLS 市场
    // ============================================================
    val SKILLS_MARKETS: List<Entry> = listOf(
        Entry("clawhub_skills", "ClawHub Skills", MarketCategory.SKILL,
            "ClawHub 官方技能市场 — 标准化、可复用的 AI 技能包",
            "https://clawhub.io/skills"),
        Entry("apex_builtin_skills", "Apex 内置技能", MarketCategory.SKILL,
            "Apex 套件自带的离线技能（无需网络）",
            "https://github.com/mengjinghao/Apex-ai-agent",
            requiresNetwork = false),
        Entry("anthropic_skills", "Anthropic Skills", MarketCategory.SKILL,
            "Anthropic 官方技能集（Claude 适配）",
            "https://github.com/anthropics/skills"),
        Entry("community_skills", "社区技能", MarketCategory.SKILL,
            "社区贡献的开源技能合集",
            "https://github.com/community/skills")
    )

    // ============================================================
    // 8 个 MCP 市场
    // ============================================================
    val MCP_MARKETS: List<Entry> = listOf(
        Entry("anthropic_mcp", "Anthropic MCP", MarketCategory.MCP,
            "Anthropic 官方 Model Context Protocol 服务器",
            "https://github.com/modelcontextprotocol/servers"),
        Entry("glama_mcp", "Glama MCP", MarketCategory.MCP,
            "Glama MCP 市场 — 跨平台 MCP 服务器聚合",
            "https://glama.ai/mcp/servers"),
        Entry("smithery_mcp", "Smithery MCP", MarketCategory.MCP,
            "Smithery MCP 市场",
            "https://smithery.ai"),
        Entry("mcphub_mcp", "MCPHub", MarketCategory.MCP,
            "MCPHub 社区 MCP 目录",
            "https://mcphub.io"),
        Entry("composio_mcp", "Composio MCP", MarketCategory.MCP,
            "Composio MCP — 250+ 集成的 MCP 服务器",
            "https://composio.dev/mcp"),
        Entry("pulumi_mcp", "Pulumi MCP", MarketCategory.MCP,
            "Pulumi MCP — 基础设施即代码工具集",
            "https://github.com/pulumi/mcp-server"),
        Entry("cline_mcp", "Cline MCP", MarketCategory.MCP,
            "Cline 生态 MCP 服务器",
            "https://github.com/cline/mcp"),
        Entry("local_mcp", "本地 MCP", MarketCategory.MCP,
            "本地文件系统中的 MCP 服务器",
            "file://local/mcp",
            requiresNetwork = false)
    )

    // ============================================================
    // 10 个 PLUGIN 市场
    // ============================================================
    val PLUGIN_MARKETS: List<Entry> = listOf(
        Entry("apex_builtin_plugins", "Apex 内置插件", MarketCategory.PLUGIN,
            "Apex 套件自带插件（无网络）",
            "https://github.com/mengjinghao/Apex-ai-agent",
            requiresNetwork = false),
        Entry("open_runtime_plugins", "Open Runtime", MarketCategory.PLUGIN,
            "开放运行时插件市场",
            "https://open-runtime.dev/plugins"),
        Entry("jetbrains_plugins", "JetBrains Plugins", MarketCategory.PLUGIN,
            "JetBrains IDE 插件（适配 Apex）",
            "https://plugins.jetbrains.com"),
        Entry("vscode_ext_plugins", "VSCode Extensions", MarketCategory.PLUGIN,
            "VSCode 扩展（适配 Apex）",
            "https://marketplace.visualstudio.com"),
        Entry("browser_ext_plugins", "Browser Extensions", MarketCategory.PLUGIN,
            "浏览器扩展（Chrome/Firefox）",
            "https://chromewebstore.google.com"),
        Entry("shell_ext_plugins", "Shell Extensions", MarketCategory.PLUGIN,
            "Shell / CLI 扩展",
            "https://github.com/shell-extensions"),
        Entry("ollama_plugins", "Ollama Plugins", MarketCategory.PLUGIN,
            "Ollama 模型适配插件",
            "https://ollama.com"),
        Entry("local_plugins", "本地插件", MarketCategory.PLUGIN,
            "本地文件系统中的插件",
            "file://local/plugins",
            requiresNetwork = false),
        Entry("docker_plugins", "Docker Plugins", MarketCategory.PLUGIN,
            "Docker 容器化插件",
            "https://hub.docker.com"),
        Entry("discord_plugins", "Discord Plugins", MarketCategory.PLUGIN,
            "Discord 机器人 / 集成插件",
            "https://discord.com/developers/docs/intro")
    )

    // ============================================================
    // 5 个 MODEL 市场
    // ============================================================
    val MODEL_MARKETS: List<Entry> = listOf(
        Entry("builtin_models", "内置模型平台", MarketCategory.MODEL,
            "11 个内置云端模型 Provider（OpenAI/Claude/DeepSeek 等）",
            "https://github.com/mengjinghao/Apex-ai-agent"),
        Entry("openai_models", "OpenAI", MarketCategory.MODEL,
            "OpenAI 官方 GPT 系列模型",
            "https://api.openai.com"),
        Entry("anthropic_models", "Anthropic", MarketCategory.MODEL,
            "Anthropic Claude 系列模型",
            "https://api.anthropic.com"),
        Entry("domestic_models", "国产模型", MarketCategory.MODEL,
            "通义千问 / 智谱 GLM / Moonshot / MiniMax / Baichuan / DeepSeek",
            "https://platform.deepseek.com"),
        Entry("local_models", "本地模型", MarketCategory.MODEL,
            "通过 Ollama / llama.cpp 运行的本地模型",
            "http://localhost:11434",
            requiresNetwork = false)
    )

    /** 全部 27 个市场。 */
    val ALL: List<Entry> = SKILLS_MARKETS + MCP_MARKETS + PLUGIN_MARKETS + MODEL_MARKETS

    /** 按分类列出市场。 */
    fun byCategory(category: MarketCategory): List<Entry> =
        ALL.filter { it.category == category }

    /** 按 marketId 查找。 */
    fun byId(marketId: String): Entry? = ALL.firstOrNull { it.marketId == marketId }

    /** 在市场目录中按关键字搜索（名称 / 描述）。 */
    fun search(query: String, limit: Int = 50): List<Entry> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return ALL.take(limit)
        return ALL.filter {
            it.displayName.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.marketId.lowercase().contains(q)
        }.take(limit)
    }

    /** 各分类市场数量统计。 */
    fun stats(): Map<MarketCategory, Int> =
        ALL.groupBy { it.category }.mapValues { it.value.size }

    /** 市场总数。 */
    fun totalCount(): Int = ALL.size
}
