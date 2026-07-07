package com.apex.agent.integration.api

import com.apex.agent.integration.category.mcp.McpModule
import com.apex.agent.integration.category.models.ModelPlatformModule
import com.apex.agent.integration.category.plugins.PluginModule
import com.apex.agent.integration.category.skills.SkillModule
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.installed.InstalledSnapshot
import com.apex.agent.integration.importer.ImportResult
import com.apex.agent.integration.importer.ImportSource
import com.apex.agent.integration.importer.IntegrationImporter
import com.apex.agent.integration.market.BuiltinMarketRegistrar
import com.apex.agent.integration.market.MarketInfo
import com.apex.agent.integration.market.MarketRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * 集成中心。
 *
 * 集成大目录的顶层门面，聚合 4 大模块（Skills / MCP / Plugins / Model Platforms），
 * 提供统一的访问入口。
 *
 * # 架构设计
 *
 * ```
 * IntegrationCenter（门面）
 *   ├── skillModule: SkillModule          → 技能市场
 *   ├── mcpModule: McpModule              → MCP 市场
 *   ├── pluginModule: PluginModule        → 插件市场
 *   ├── modelPlatformModule: ModelPlatformModule → 模型平台市场
 *   ├── installedManager: InstalledManager      → 已安装管理
 *   └── importer: IntegrationImporter           → 导入功能
 * ```
 *
 * # 使用示例
 *
 * ```
 * val center = IntegrationCenter.create()
 *
 * // 获取所有市场（按分类）
 * val skillMarkets = center.getMarkets(IntegrationCategory.SKILLS)
 *
 * // 搜索技能
 * val results = center.skillModule.searchAcrossMarkets(filter)
 *
 * // 查看已安装
 * center.installedSnapshot.collect { snap ->
 *     println("已安装: ${snap.totalCount}")
 * }
 *
 * // 导入技能
 * val result = center.importer.import(source, IntegrationCategory.SKILLS)
 * ```
 */
interface IntegrationCenter {

    /** Skills 模块。 */
    val skillModule: SkillModule

    /** MCP 模块。 */
    val mcpModule: McpModule

    /** 插件模块。 */
    val pluginModule: PluginModule

    /** 模型平台模块。 */
    val modelPlatformModule: ModelPlatformModule

    /** 已安装管理器。 */
    val installedManager: InstalledManager

    /** 导入器。 */
    val importer: IntegrationImporter

    /** MCP 配置生成器。 */
    val mcpConfigGenerator: com.apex.agent.integration.config.McpConfigGenerator

    /** 安装执行器。 */
    val installExecutor: com.apex.agent.integration.installer.InstallExecutor

    /** 诊断器。 */
    val diagnostics: com.apex.agent.integration.diagnostics.IntegrationDiagnostics

    /**
     * 已安装项的快照（可观察）。
     */
    val installedSnapshot: StateFlow<InstalledSnapshot>

    /**
     * 获取指定分类的所有市场。
     */
    suspend fun getMarkets(category: IntegrationCategory): List<MarketInfo>

    /**
     * 获取所有市场的分类统计。
     *
     * @return 分类 -> 市场数
     */
    fun getMarketStats(): Map<IntegrationCategory, Int>

    /**
     * 刷新所有市场缓存。
     */
    suspend fun refreshAllMarkets()

    /**
     * 获取需要更新的项。
     */
    fun getUpdatable(): List<com.apex.agent.integration.installed.InstalledItem>

    /**
     * 按分类获取已安装项。
     */
    fun getInstalledByCategory(category: IntegrationCategory): List<com.apex.agent.integration.installed.InstalledItem>

    /**
     * 导入集成项。
     *
     * @param source 导入来源
     * @param category 目标分类
     * @param autoInstall 是否自动安装
     * @return 导入结果
     */
    suspend fun import(
        source: ImportSource,
        category: IntegrationCategory,
        autoInstall: Boolean = true
    ): ImportResult

    /**
     * 执行集成诊断。
     *
     * @return 诊断报告
     */
    suspend fun diagnose(): com.apex.agent.integration.diagnostics.IntegrationDiagnostics.DiagnosticsReport

    /**
     * 生成 MCP 配置 JSON。
     *
     * @param format 目标格式（Claude/Cursor/Apex/通用）
     * @return 配置 JSON 字符串
     */
    fun generateMcpConfig(format: com.apex.agent.integration.config.McpConfigGenerator.ConfigFormat = com.apex.agent.integration.config.McpConfigGenerator.ConfigFormat.APEX_AGENT): String

    /**
     * 获取集成概览（分类统计 + 市场统计）。
     */
    fun getOverview(): IntegrationOverview

    companion object {
        /**
         * 创建集成中心。
         *
         * 自动注册所有内置市场（19 个，覆盖 4 大分类）。
         */
        fun create(): IntegrationCenter {
            BuiltinMarketRegistrar.registerAll()
            return IntegrationCenterImpl()
        }

        /**
         * 创建集成中心，并自动发现市场。
         *
         * 除内置市场外，还通过 ServiceLoader 发现自定义市场。
         */
        fun createWithAutoDiscovery(): IntegrationCenter {
            BuiltinMarketRegistrar.registerAll()
            MarketRegistry.autoDiscover()
            return IntegrationCenterImpl()
        }
    }
}

/**
 * [IntegrationCenter] 的默认实现。
 */
internal class IntegrationCenterImpl : IntegrationCenter {

    private val _installedManager = InstalledManager()
    override val installedManager: InstalledManager = _installedManager

    override val skillModule: SkillModule = SkillModule(_installedManager)
    override val mcpModule: McpModule = McpModule(_installedManager)
    override val pluginModule: PluginModule = PluginModule(_installedManager)
    override val modelPlatformModule: ModelPlatformModule = ModelPlatformModule(_installedManager)

    override val importer: IntegrationImporter = IntegrationImporter(_installedManager)

    override val mcpConfigGenerator: com.apex.agent.integration.config.McpConfigGenerator =
        com.apex.agent.integration.config.McpConfigGenerator(_installedManager)

    override val installExecutor: com.apex.agent.integration.installer.InstallExecutor =
        com.apex.agent.integration.installer.InstallExecutor(
            _installedManager,
            java.io.File(System.getProperty("java.io.tmpdir"), "apex-integration")
        )

    override val diagnostics: com.apex.agent.integration.diagnostics.IntegrationDiagnostics =
        com.apex.agent.integration.diagnostics.IntegrationDiagnostics(_installedManager)

    override val installedSnapshot: StateFlow<InstalledSnapshot>
        get() = _installedManager.snapshot

    override suspend fun getMarkets(category: IntegrationCategory): List<MarketInfo> {
        return MarketRegistry.listByCategory(category)
    }

    override fun getMarketStats(): Map<IntegrationCategory, Int> {
        return IntegrationCategory.values().associateWith { category ->
            MarketRegistry.countByCategory(category)
        }
    }

    override suspend fun refreshAllMarkets() {
        for (market in MarketRegistry.getAll()) {
            try {
                market.refresh()
            } catch (_: Exception) {
                // 单个市场刷新失败不影响其他
            }
        }
    }

    override fun getUpdatable(): List<com.apex.agent.integration.installed.InstalledItem> {
        return _installedManager.getUpdatable()
    }

    override fun getInstalledByCategory(category: IntegrationCategory): List<com.apex.agent.integration.installed.InstalledItem> {
        return _installedManager.getByCategory(category)
    }

    override suspend fun import(
        source: ImportSource,
        category: IntegrationCategory,
        autoInstall: Boolean
    ): ImportResult {
        return importer.import(source, category, autoInstall)
    }

    override suspend fun diagnose(): com.apex.agent.integration.diagnostics.IntegrationDiagnostics.DiagnosticsReport {
        return diagnostics.diagnose()
    }

    override fun generateMcpConfig(format: com.apex.agent.integration.config.McpConfigGenerator.ConfigFormat): String {
        return mcpConfigGenerator.generate(format)
    }

    override fun getOverview(): IntegrationOverview {
        return IntegrationOverview(
            marketStats = getMarketStats(),
            installedCount = _installedManager.count(),
            installedByCategory = IntegrationCategory.values().associateWith { cat ->
                _installedManager.countByCategory(cat)
            },
            updatableCount = _installedManager.getUpdatable().size,
            enabledCount = _installedManager.getEnabled().size,
            disabledCount = _installedManager.getDisabled().size
        )
    }
}

/**
 * 集成概览。
 */
data class IntegrationOverview(
    val marketStats: Map<IntegrationCategory, Int>,
    val installedCount: Int,
    val installedByCategory: Map<IntegrationCategory, Int>,
    val updatableCount: Int,
    val enabledCount: Int,
    val disabledCount: Int
)
