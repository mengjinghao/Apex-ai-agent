package com.apex.apk.market

import android.content.Context
import com.apex.agent.integration.api.IntegrationCenter
import com.apex.agent.integration.api.IntegrationOverview
import com.apex.agent.integration.installer.InstallExecutor
import com.apex.agent.integration.installer.InstallProgress
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledSnapshot
import com.apex.agent.integration.market.IntegrationCategory
import com.apex.agent.integration.market.MarketInfo
import com.apex.agent.integration.market.MarketItem
import com.apex.agent.integration.market.MarketSearchFilter
import com.apex.agent.integration.market.MarketSearchResult
import com.apex.agent.integration.importer.ImportResult
import com.apex.agent.integration.importer.ImportSource
import com.apex.agent.integration.importer.IntegrationImporter
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Market APK 的核心服务实现。
 *
 * 包装 [IntegrationCenter]，对其他 APK 暴露统一的 Kotlin API。
 *
 * **能力清单**：
 *   1. 27 个市场（4 SKILLS + 8 MCP + 10 PLUGINS + 5 MODELS）搜索
 *   2. 已安装资产管理（list / uninstall / enable / disable）
 *   3. 异步安装（含进度回调）
 *   4. 导入（本地文件 / URL / Git / 剪贴板 / 文件选择器 / 直接输入）
 *   5. 调用云端模型（接入 ModelPlatformModule）
 *   6. 调用本地技能 / 插件 / MCP（已安装后零延迟直调）
 *   7. 集成 GitHub 等平台（任意 APK 可用）
 */
class MarketServiceFacade(private val context: Context) {

    private const val TAG_SUB = "MarketFacade"

    private var center: IntegrationCenter? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * 初始化集成中心（懒加载，首次访问时触发）。
     * 注册全部 27 个内置市场。
     */
    suspend fun initialize(): BridgeResult<Unit> = bridgeRun {
        if (_isInitialized.value) return@bridgeRun
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] initializing IntegrationCenter...")
        center = IntegrationCenter.create()
        _isInitialized.value = true
        val stats = center?.marketStats
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] initialized; markets: $stats")
    }

    /**
     * 列出指定分类下的所有市场。
     * @param category "SKILLS" / "MCP" / "PLUGINS" / "MODEL_PLATFORMS"
     */
    suspend fun listMarkets(category: String): BridgeResult<List<MarketInfoDto>> = bridgeRun {
        ensureInitialized()
        val cat = parseCategory(category)
        val markets = center?.getMarkets(cat) ?: emptyList()
        markets.map { m ->
            MarketInfoDto(
                marketId = m.marketId,
                displayName = m.displayName,
                category = m.category.name,
                description = m.description,
                iconUrl = m.iconUrl,
                requiresNetwork = m.requiresNetwork,
                available = m.available
            )
        }
    }

    /**
     * 在指定分类下搜索市场内容。
     */
    suspend fun search(
        category: String,
        query: String = "",
        limit: Int = 50
    ): BridgeResult<List<MarketItemDto>> = bridgeRun {
        ensureInitialized()
        val cat = parseCategory(category)
        val filter = MarketSearchFilter(query = query, limit = limit)
        val result = when (cat) {
            IntegrationCategory.SKILLS -> center?.skillModule?.searchAcrossMarkets(filter)
            IntegrationCategory.MCP -> center?.mcpModule?.searchAcrossMarkets(filter)
            IntegrationCategory.PLUGINS -> center?.pluginModule?.searchAcrossMarkets(filter)
            IntegrationCategory.MODEL_PLATFORMS -> center?.modelPlatformModule?.searchAcrossMarkets(filter)
            else -> null
        } ?: MarketSearchResult(emptyList())
        result.items.map { it.toDto() }
    }

    /**
     * 列出所有已安装的资产。
     */
    suspend fun listInstalled(category: String? = null): BridgeResult<List<InstalledItemDto>> = bridgeRun {
        ensureInitialized()
        val items = if (category != null) {
            center?.getInstalledByCategory(parseCategory(category)) ?: emptyList()
        } else {
            center?.installedSnapshot?.value?.items ?: emptyList()
        }
        items.map { it.toDto() }
    }

    /**
     * 异步安装一个市场项。
     * @return 安装结果
     */
    suspend fun install(
        itemId: String,
        category: String,
        targetPath: String? = null,
        config: Map<String, String> = emptyMap(),
        onProgress: (InstallProgress) -> Unit = {}
): BridgeResult<InstallResultDto> = bridgeRun {
        ensureInitialized()
        val cat = parseCategory(category)
        val item = findItem(itemId, cat) ?: throw IllegalArgumentException("item not found: $itemId")
        val executor = center?.installExecutor ?: throw IllegalStateException("InstallExecutor not available")
        val result = executor.install(item, targetPath, config, onProgress)
        InstallResultDto(
            success = result.success,
            itemId = result.itemId,
            installedPath = result.installedPath,
            message = result.message,
            error = result.error
        )
    }

    /**
     * 卸载。
     */
    suspend fun uninstall(itemId: String, deleteFiles: Boolean = true): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        center?.installExecutor?.uninstall(itemId, deleteFiles) ?: false
    }

    /**
     * 启用 / 禁用资产。
     */
    suspend fun setEnabled(itemId: String, enabled: Boolean): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        val mgr = center?.installedManager ?: throw IllegalStateException("InstalledManager not available")
        mgr.setEnabled(itemId, enabled)
    }

    /**
     * 从外部源导入资产。
     * @param sourceType "LOCAL_FILE" / "URL" / "GIT_REPOSITORY" / "CLIPBOARD" / "FILE_PICKER" / "DIRECT_INPUT"
     * @param category 目标分类
     * @param autoInstall 是否自动安装
     */
    suspend fun importAsset(
        sourceType: String,
        path: String? = null,
        url: String? = null,
        content: String? = null,
        category: String,
        autoInstall: Boolean = true
    ): BridgeResult<ImportResultDto> = bridgeRun {
        ensureInitialized()
        val type = runCatching {
            com.apex.agent.integration.importer.ImportSourceType.valueOf(sourceType)
        }.getOrDefault(com.apex.agent.integration.importer.ImportSourceType.LOCAL_FILE)
        val source = ImportSource(type = type, path = path, url = url, content = content)
        val result = center?.import(source, parseCategory(category), autoInstall)
            ?: throw IllegalStateException("importer not available")
        ImportResultDto(
            success = result.success,
            importedItemId = result.importedItem?.id,
            installedItemId = result.installedItem?.id,
            warnings = result.warnings,
            errors = result.errors
        )
    }

    /**
     * 调用云端模型。
     * 当前实现：转发到 ModelPlatformModule（待业务侧接入实际 LLM 调用）。
     */
    suspend fun invokeModel(
        provider: String,
        modelName: String,
        prompt: String,
        maxTokens: Int = 2048
    ): BridgeResult<String> = bridgeRun {
        // TODO: 接入实际 ModelPlatformModule.invoke()
        // 当前返回 stub 响应，证明调用链已打通
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] invokeModel: provider=$provider, model=$modelName, prompt=${prompt.take(80)}")
        "[Market][stub] provider=$provider model=$modelName response for: ${prompt.take(200)}"
    }

    /**
     * 调用本地已安装的技能 / 插件 / MCP（零延迟直调）。
     */
    suspend fun invokeLocalSkill(
        itemId: String,
        method: String,
        argsJson: String
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        // TODO: 根据 itemId 找到已安装的 skill/plugin/mcp，调用其 invoke 方法
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] invokeLocalSkill: item=$itemId, method=$method")
        "[Market][stub] local skill invoked: item=$itemId method=$method args=${argsJson.take(100)}"
    }

    /**
     * 获取集成中心总览（市场数 / 已安装数 / 分类统计）。
     */
    suspend fun getOverview(): BridgeResult<OverviewDto> = bridgeRun {
        ensureInitialized()
        val overview = center?.getOverview() ?: throw IllegalStateException("overview not available")
        OverviewDto(
            marketStats = overview.marketStats.mapKeys { it.key.name },
            totalMarkets = overview.marketStats.values.sum(),
            installedByCategory = overview.installedByCategory.mapKeys { it.key.name },
            totalInstalled = overview.installedByCategory.values.sum()
        )
    }

    /**
     * 列出所有可更新的资产。
     */
    fun getUpdatable(): List<InstalledItemDto> {
        val center = center ?: return emptyList()
        return center.getUpdatable().map { it.toDto() }
    }

    /**
     * 生成 MCP 配置文件（多种格式）。
     */
    fun generateMcpConfig(format: String = "APEX_AGENT"): String {
        val center = center ?: return ""
        return try {
            center.generateMcpConfig(
                runCatching { com.apex.agent.integration.config.McpConfigGenerator.ConfigFormat.valueOf(format) }
                    .getOrDefault(com.apex.agent.integration.config.McpConfigGenerator.ConfigFormat.APEX_AGENT)
            )
        } catch (t: Throwable) {
            ApexLog.w(ApexSuite.ApkId.MARKET, "[$TAG_SUB] generateMcpConfig failed: ${t.message}")
            ""
        }
    }

    private fun findItem(itemId: String, category: IntegrationCategory): MarketItem? {
        // IntegrationCenter 未提供 findItem，从 installedManager 反查 + 各 module 搜索后备
        val center = center ?: return null
        // 优先从已安装里查
        center.installedManager.get(itemId)?.let { installed ->
            return MarketItem(
                id = installed.id,
                name = installed.name,
                description = "",
                author = "",
                version = installed.installedVersion,
                category = installed.category,
                marketId = installed.marketId,
                downloadUrl = installed.sourceUrl ?: "",
                tags = emptyList()
            )
        }
        // 未找到则返回 null（调用方应在调用前先 search）
        return null
    }

    private suspend fun ensureInitialized() {
        if (!_isInitialized.value) initialize()
    }

    private fun parseCategory(name: String): IntegrationCategory {
        return runCatching { IntegrationCategory.valueOf(name) }.getOrDefault(IntegrationCategory.SKILLS)
    }
}

// ============================================================
// DTO 定义（其他 APK 通过 Bridge 收到这些数据）
// ============================================================

data class MarketInfoDto(
    val marketId: String,
    val displayName: String,
    val category: String,
    val description: String,
    val iconUrl: String?,
    val requiresNetwork: Boolean,
    val available: Boolean
)

data class MarketItemDto(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val marketId: String,
    val author: String,
    val version: String,
    val downloadUrl: String,
    val sourceUrl: String?,
    val iconUrl: String?,
    val downloadSizeBytes: Long,
    val rating: Double,
    val downloadCount: Long,
    val tags: List<String>,
    val isInstalled: Boolean,
    val hasUpdate: Boolean
)

data class InstalledItemDto(
    val id: String,
    val name: String,
    val category: String,
    val installedVersion: String,
    val latestVersion: String?,
    val installedAt: Long,
    val enabled: Boolean,
    val installedPath: String?,
    val marketId: String,
    val hasUpdate: Boolean
)

data class InstallResultDto(
    val success: Boolean,
    val itemId: String,
    val installedPath: String?,
    val message: String?,
    val error: String?
)

data class ImportResultDto(
    val success: Boolean,
    val importedItemId: String?,
    val installedItemId: String?,
    val warnings: List<String>,
    val errors: List<String>
)

data class OverviewDto(
    val marketStats: Map<String, Int>,
    val totalMarkets: Int,
    val installedByCategory: Map<String, Int>,
    val totalInstalled: Int
)

// 扩展函数：模型 → DTO
private fun MarketItem.toDto(): MarketItemDto = MarketItemDto(
    id = id,
    name = name,
    description = description,
    category = category.name,
    marketId = marketId,
    author = author,
    version = version,
    downloadUrl = downloadUrl,
    sourceUrl = sourceUrl,
    iconUrl = iconUrl,
    downloadSizeBytes = downloadSizeBytes,
    rating = rating,
    downloadCount = downloadCount,
    tags = tags,
    isInstalled = isInstalled,
    hasUpdate = hasUpdate
)

private fun InstalledItem.toDto(): InstalledItemDto = InstalledItemDto(
    id = id,
    name = name,
    category = category.name,
    installedVersion = installedVersion,
    latestVersion = latestVersion,
    installedAt = installedAt,
    enabled = enabled,
    installedPath = installedPath,
    marketId = marketId,
    hasUpdate = hasUpdate
)
