package com.apex.agent.integration.category.models

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem

/**
 * 内置 LLM Provider 配置。
 *
 * 迁移自原 Apex-agent 的 ProviderProfile，包含主流 LLM 平台的连接配置。
 * 业务侧可直接使用这些预设配置，无需手动填写 endpoint 和模型名。
 *
 * # 包含的 Provider
 * - DeepSeek（深度推理/代码生成）
 * - Claude（Anthropic，最强推理）
 * - OpenAI（GPT-4/GPT-3.5）
 * - 通义千问（阿里，国内直连）
 * - 智谱 GLM（国内开源）
 * - Moonshot（Kimi，长上下文）
 * - 月之暗面（MiniMax）
 * - 本地 Ollama（离线推理）
 */
object BuiltinModelProviders {

    /**
     * Provider 认证类型。
     */
    enum class AuthType { API_KEY, OAUTH, NONE }

    /**
     * Provider 类型。
     */
    enum class ProviderType { CLOUD, LOCAL, SELF_HOSTED }

    /**
     * LLM Provider 配置。
     */
    data class ProviderConfig(
        val name: String,
        val displayName: String,
        val baseUrl: String,
        val apiKeyEnvVar: String,
        val authType: AuthType = AuthType.API_KEY,
        val providerType: ProviderType = ProviderType.CLOUD,
        val defaultModel: String,
        val modelsUrl: String = "",
        val icon: String = "",
        val description: String,
        val supportsStreaming: Boolean = true,
        val freeQuota: String = "",
        val region: String = "global"
    )

    /**
     * 所有内置 Provider 配置。
     */
    val providers: List<ProviderConfig> = listOf(
        ProviderConfig(
            name = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            apiKeyEnvVar = "DEEPSEEK_API_KEY",
            defaultModel = "deepseek-chat",
            description = "高性价比 LLM API，深度推理能力",
            freeQuota = "新用户赠送",
            icon = "https://platform.deepseek.com/favicon.ico"
        ),
        ProviderConfig(
            name = "deepseek-reasoner",
            displayName = "DeepSeek Reasoner",
            baseUrl = "https://api.deepseek.com/v1",
            apiKeyEnvVar = "DEEPSEEK_API_KEY",
            defaultModel = "deepseek-reasoner",
            description = "深度推理模型，链式思考（CoT）",
            freeQuota = "新用户赠送",
            icon = "https://platform.deepseek.com/favicon.ico"
        ),
        ProviderConfig(
            name = "claude",
            displayName = "Claude (Anthropic)",
            baseUrl = "https://api.anthropic.com/v1",
            apiKeyEnvVar = "ANTHROPIC_API_KEY",
            defaultModel = "claude-sonnet-4-20250514",
            description = "Anthropic 最强推理模型，擅长长文本分析",
            freeQuota = "有限免费额度",
            icon = "https://anthropic.com/favicon.ico"
        ),
        ProviderConfig(
            name = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKeyEnvVar = "OPENAI_API_KEY",
            defaultModel = "gpt-4o",
            modelsUrl = "https://api.openai.com/v1/models",
            description = "GPT-4o / GPT-4 Turbo / GPT-3.5",
            freeQuota = "无免费额度",
            icon = "https://openai.com/favicon.ico"
        ),
        ProviderConfig(
            name = "qwen",
            displayName = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/api/v1",
            apiKeyEnvVar = "DASHSCOPE_API_KEY",
            defaultModel = "qwen-max",
            description = "阿里通义千问，国内直连，多模态理解",
            freeQuota = "10 万 tokens/月",
            region = "cn",
            icon = "https://dashscope.aliyuncs.com/favicon.ico"
        ),
        ProviderConfig(
            name = "glm",
            displayName = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            apiKeyEnvVar = "ZHIPU_API_KEY",
            defaultModel = "glm-4",
            description = "智谱 AI GLM-4，支持工具调用",
            freeQuota = "赠送 tokens",
            region = "cn",
            icon = "https://open.bigmodel.cn/favicon.ico"
        ),
        ProviderConfig(
            name = "moonshot",
            displayName = "Moonshot (Kimi)",
            baseUrl = "https://api.moonshot.cn/v1",
            apiKeyEnvVar = "MOONSHOT_API_KEY",
            defaultModel = "moonshot-v1-128k",
            description = "Kimi 长上下文模型，128K token 窗口",
            freeQuota = "有限免费额度",
            region = "cn",
            icon = "https://moonshot.cn/favicon.ico"
        ),
        ProviderConfig(
            name = "minimax",
            displayName = "MiniMax",
            baseUrl = "https://api.minimax.chat/v1",
            apiKeyEnvVar = "MINIMAX_API_KEY",
            defaultModel = "abab6.5-chat",
            description = "MiniMax 对话模型",
            freeQuota = "赠送 tokens",
            region = "cn",
            icon = null ?: ""
        ),
        ProviderConfig(
            name = "baichuan",
            displayName = "百川大模型",
            baseUrl = "https://api.baichuan-ai.com/v1",
            apiKeyEnvVar = "BAICHUAN_API_KEY",
            defaultModel = "Baichuan2-Turbo",
            description = "百川智能，中文能力优秀",
            freeQuota = "赠送 tokens",
            region = "cn",
            icon = null ?: ""
        ),
        ProviderConfig(
            name = "ollama",
            displayName = "Ollama (本地)",
            baseUrl = "http://localhost:11434/v1",
            apiKeyEnvVar = "",
            authType = AuthType.NONE,
            providerType = ProviderType.LOCAL,
            defaultModel = "llama3.2",
            description = "本地推理，完全离线，无需 API Key",
            freeQuota = "永久免费",
            region = "local",
            icon = "https://ollama.ai/favicon.ico"
        ),
        ProviderConfig(
            name = "agnes",
            displayName = "Agnes AI",
            baseUrl = "https://api.agnes.ai/v1",
            apiKeyEnvVar = "AGNES_API_KEY",
            authType = AuthType.NONE,
            defaultModel = "agnes-text-pro",
            description = "全球全模态永久免费，无额度限制",
            freeQuota = "永久免费",
            icon = null ?: ""
        )
    )

    /**
     * 转换为 [MarketItem] 列表。
     */
    fun toMarketItems(marketId: String = "builtin_models"): List<MarketItem> {
        return providers.map { provider ->
            MarketItem(
                id = "$marketId:${provider.name}",
                name = provider.displayName,
                description = provider.description,
                author = provider.name,
                version = "1.0.0",
                category = IntegrationCategory.MODEL_PLATFORMS,
                marketId = marketId,
                sourceType = IntegrationSourceType.OFFICIAL_MARKET,
                downloadUrl = provider.baseUrl,
                tags = buildList {
                    add(provider.providerType.name.lowercase())
                    if (provider.region == "cn") add("国内")
                    if (provider.freeQuota.contains("永久免费")) add("免费")
                    if (provider.supportsStreaming) add("streaming")
                },
                rating = 4.5,
                downloadCount = 0,
                verified = true,
                metadata = mapOf(
                    "baseUrl" to provider.baseUrl,
                    "defaultModel" to provider.defaultModel,
                    "authType" to provider.authType.name,
                    "providerType" to provider.providerType.name,
                    "freeQuota" to provider.freeQuota,
                    "region" to provider.region,
                    "apiKeyEnvVar" to provider.apiKeyEnvVar,
                    "supportsStreaming" to provider.supportsStreaming.toString()
                )
            )
        }
    }

    /**
     * 按 name 获取配置。
     */
    fun getByName(name: String): ProviderConfig? = providers.find { it.name == name }

    /**
     * 按区域获取（global/cn/local）。
     */
    fun getByRegion(region: String): List<ProviderConfig> {
        return providers.filter { it.region == region }
    }

    /**
     * 获取所有免费 Provider。
     */
    fun getFreeProviders(): List<ProviderConfig> {
        return providers.filter {
            it.freeQuota.contains("永久免费") || it.freeQuota.contains("免费")
        }
    }

    /**
     * 获取本地 Provider。
     */
    fun getLocalProviders(): List<ProviderConfig> {
        return providers.filter { it.providerType == ProviderType.LOCAL }
    }
}
