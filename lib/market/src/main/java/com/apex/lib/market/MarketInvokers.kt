package com.apex.lib.market

import kotlinx.serialization.Serializable

/**
 * 调用契约 — lib:market 定义接口，APK 提供实际实现。
 *
 * lib:market 不依赖 OkHttp / Android / `core:integration`，因此涉及网络 /
 * 进程 / 已安装资产查询的能力都通过下列 interface 暴露，由 APK 的
 * [com.apex.apk.market.MarketServiceFacade] 注入实际实现。
 *
 * 本文件包含 4 类契约：
 *   1. [MarketFetcher] — 实际搜索 27 个市场（缓存未命中时调用）
 *   2. [Installer]     — 实际下载 + 安装 / 卸载资产
 *   3. [LlmInvoker]    — 调用云端 LLM Provider
 *   4. [SkillInvoker]  — 调用本地已安装技能 / MCP / 插件
 */

// ============================================================
// 1. MarketFetcher — 真实搜索后端
// ============================================================

/**
 * 市场搜索后端契约。
 *
 * APK 实现时委托给 `IntegrationCenter` 的对应 module（skillModule / mcpModule / ...）。
 *
 * lib:market 的 [MarketEngine.search] 在缓存未命中时调用 [fetch]。
 */
fun interface MarketFetcher {
    /**
     * 跨市场搜索。
     *
     * @param category 分类
     * @param query 查询关键字（空表示列出全部）
     * @param marketId 限定市场（null 或空表示跨市场聚合）
     * @param limit 最大返回数
     * @return 搜索结果（lib 模型）
     */
    suspend fun fetch(
        category: MarketCategory,
        query: String,
        marketId: String?,
        limit: Int
    ): List<MarketItem>
}

// ============================================================
// 2. Installer — 真实下载 + 安装 / 卸载
// ============================================================

/**
 * 资产下载 + 安装契约。
 *
 * APK 实现时委托给 `IntegrationCenter.installExecutor`。
 */
interface Installer {
    /**
     * 下载 + 安装一个市场项。
     *
     * 实现侧应在过程中调用 [onProgress]（progress ∈ [0,100]，stage 描述当前阶段）。
     *
     * @return 安装结果
     */
    suspend fun install(
        item: MarketItem,
        targetPath: String?,
        config: Map<String, String>,
        onProgress: (progress: Int, stage: String) -> Unit
    ): InstallOutcome

    /**
     * 卸载。
     */
    suspend fun uninstall(itemId: String, deleteFiles: Boolean): Boolean

    /**
     * 启用 / 禁用。
     */
    suspend fun setEnabled(itemId: String, enabled: Boolean): Boolean

    /**
     * 列出所有已安装项（可按分类筛选）。
     */
    suspend fun listInstalled(category: MarketCategory?): List<InstalledItem>

    /**
     * 列出所有可更新项。
     */
    suspend fun listUpdatable(): List<InstalledItem>

    /**
     * 检查某 itemId 是否已安装。
     */
    suspend fun isInstalled(itemId: String): Boolean
}

/**
 * [Installer.install] 的返回。
 */
data class InstallOutcome(
    val success: Boolean,
    val itemId: String,
    val installedPath: String? = null,
    val message: String? = null,
    val error: String? = null
)

// ============================================================
// 3. LlmInvoker — 云端 LLM 调用契约
// ============================================================

/**
 * LLM 调用请求。
 */
@Serializable
data class LlmInvocation(
    val provider: String,            // "deepseek" / "openai" / "claude" / ...
    val modelName: String,
    val prompt: String,
    val maxTokens: Int = 2048,
    val systemPrompt: String? = null,
    val temperature: Float = 0.7f,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * LLM 调用结果。
 */
@Serializable
data class LlmResult(
    val text: String,
    val provider: String,
    val modelName: String,
    val tokensUsed: Int = 0,
    val finishReason: String = "stop",
    val latencyMs: Long = 0L,
    val raw: Map<String, String> = emptyMap()
)

/**
 * Provider 可用性信息。
 */
@Serializable
data class ProviderInfo(
    val name: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val hasApiKey: Boolean,
    val apiKeySource: String,         // "installed" / "env" / "missing"
    val region: String = "",
    val freeQuota: String = "",
    val supportsStreaming: Boolean = false
)

/**
 * LLM 调用器接口 — APK 实现真实 HTTP 调用（OpenAI 兼容 / Anthropic 协议）。
 */
interface LlmInvoker {
    /** 执行一次 LLM 调用。 */
    suspend fun invoke(req: LlmInvocation): LlmResult

    /** 列出所有可用 Provider（含 apiKey 状态）。 */
    fun listAvailableProviders(): List<ProviderInfo>

    /** 检查某 Provider 是否可用（有 apiKey）。 */
    fun isProviderAvailable(provider: String): Boolean
}

// ============================================================
// 4. SkillInvoker — 本地技能 / MCP / 插件调用契约
// ============================================================

/**
 * 本地技能调用请求。
 */
@Serializable
data class SkillInvocation(
    val itemId: String,
    val method: String,              // "execute" / "list_tools" / "call_tool" / ...
    val argsJson: String = "{}",
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 本地技能调用结果。
 */
@Serializable
data class SkillResult(
    val success: Boolean,
    val resultJson: String = "{}",
    val error: String? = null,
    val latencyMs: Long = 0L
)

/**
 * 本地技能调用器接口 — APK 实现真实 MCP / Plugin / Skill 适配。
 */
interface SkillInvoker {
    /** 执行一次本地技能调用。 */
    suspend fun invoke(req: SkillInvocation): SkillResult

    /** 列出某已安装项支持的方法。 */
    fun listMethods(itemId: String): List<String>

    /** 获取某已安装项的元数据（JSON 字符串）。 */
    fun getMetadata(itemId: String): String?
}
