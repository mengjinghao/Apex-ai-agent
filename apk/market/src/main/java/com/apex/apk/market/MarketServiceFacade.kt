package com.apex.apk.market

import com.apex.agent.integration.api.IntegrationCategory

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
import com.apex.apk.market.cache.CacheStats
import com.apex.apk.market.cache.MarketCache
import com.apex.apk.market.favorites.FavoriteEntry
import com.apex.apk.market.favorites.Favorites
import com.apex.apk.market.llm.LlmInvoker
import com.apex.apk.market.llm.ProviderAvailability
import com.apex.apk.market.skill.LocalSkillInvoker
import com.apex.apk.market.stats.ItemStats
import com.apex.apk.market.stats.TotalStats
import com.apex.apk.market.stats.UsageEvent
import com.apex.apk.market.stats.UsageEventType
import com.apex.apk.market.stats.UsageStats
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

// ===== lib:market 集成 =====
import com.apex.lib.market.InstallManager
import com.apex.lib.market.InstallOutcome
import com.apex.lib.market.InstallStatus
import com.apex.lib.market.InstallTask
import com.apex.lib.market.Installer
import com.apex.lib.market.LlmInvocation
import com.apex.lib.market.LlmInvoker as LibLlmInvoker
import com.apex.lib.market.LlmResult
import com.apex.lib.market.MarketCatalog
import com.apex.lib.market.MarketCategory
import com.apex.lib.market.MarketEngine
import com.apex.lib.market.MarketEvent
import com.apex.lib.market.MarketFetcher
import com.apex.lib.market.MarketItem as LibMarketItem
import com.apex.lib.market.InstalledItem as LibInstalledItem
import com.apex.lib.market.ProviderInfo
import com.apex.lib.market.SkillInvocation
import com.apex.lib.market.SkillInvoker as LibSkillInvoker
import com.apex.lib.market.SkillResult
import kotlinx.coroutines.flow.SharedFlow

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

    private val TAG_SUB = "MarketFacade"

    private var center: IntegrationCenter? = null
    private val _isInitialized = MutableStateFlow(false)

    // ===== Apex 独有增强模块 =====
    /** LLM 调用器 — 真实调用 11 个内置 Provider */
    private lateinit var llmInvoker: LlmInvoker
    /** 本地技能调用器 — 调用已安装的 MCP/Plugin/Skill */
    private lateinit var localSkillInvoker: LocalSkillInvoker
    /** 市场缓存 — 离线浏览 + 减少网络请求 */
    private val cache: MarketCache = MarketCache(File(context.filesDir, "apex-market-cache"))
    /** 收藏夹 */
    private val favorites: Favorites = Favorites(File(context.filesDir, "apex-market-favorites"))
    /** 使用统计 */
    private val usageStats: UsageStats = UsageStats(File(context.filesDir, "apex-market-stats"))

    // ===== lib:market 引擎（持有并委托） =====
    /**
     * lib:market 引擎实例 — 持有独立的 cache/favorites/usageStats（基于 lib 模型），
     * 并通过 [MarketFetcher] / [Installer] / [LibLlmInvoker] / [LibSkillInvoker]
     * 4 个接口由本 Facade 提供实际实现（委托给 IntegrationCenter / LlmInvoker / LocalSkillInvoker）。
     */
    private val engine: MarketEngine =
        MarketEngine(File(context.filesDir, "apex-market-lib"))

    /** 暴露 lib 引擎的事件流（UI / 跨 APK 通知可订阅）。 */
    val engineEvents: SharedFlow<MarketEvent> get() = engine.events

    /** 暴露 lib 引擎的安装进度事件流。 */
    val installEvents: SharedFlow<MarketEvent> get() = engine.installEvents()

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * 初始化集成中心（懒加载，首次访问时触发）。
     * 注册全部 27 个内置市场。
     */
    suspend fun initialize(): BridgeResult<Unit> = bridgeRun {
        if (_isInitialized.value) return@bridgeRun
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] initializing IntegrationCenter...")
        center = IntegrationCenter.create()
        // 初始化 LLM 和 Skill Invoker
        llmInvoker = LlmInvoker(center!!.installedManager)
        localSkillInvoker = LocalSkillInvoker(center!!.installedManager)

        // ===== 注入 lib:market 引擎的实际实现契约 =====
        val c = center!!
        engine.fetcher = MarketFetcher { libCat, query, marketId, limit ->
            // 委托给 IntegrationCenter 的对应 module 搜索
            val integCat = libCategoryToIntegration(libCat)
            val filter = MarketSearchFilter(query = query, pageSize = limit)
            val result = when (marketId) {
                null, "" -> when (integCat) {
                    IntegrationCategory.SKILLS -> c.skillModule?.searchAcrossMarkets(filter)
                    IntegrationCategory.MCP -> c.mcpModule?.searchAcrossMarkets(filter)
                    IntegrationCategory.PLUGINS -> c.pluginModule?.searchAcrossMarkets(filter)
                    IntegrationCategory.MODEL_PLATFORMS -> c.modelPlatformModule?.searchAcrossMarkets(filter)
                    else -> null
                }
                else -> when (integCat) {
                    IntegrationCategory.SKILLS -> c.skillModule?.searchInMarket(marketId, filter)
                    IntegrationCategory.MCP -> c.mcpModule?.searchInMarket(marketId, filter)
                    IntegrationCategory.PLUGINS -> c.pluginModule?.searchInMarket(marketId, filter)
                    IntegrationCategory.MODEL_PLATFORMS -> c.modelPlatformModule?.searchInMarket(marketId, filter)
                    else -> null
                }
            } ?: MarketSearchResult(emptyList())
            result.items.map { it.toLibItem() }
        }
        engine.installer = object : Installer {
            override suspend fun install(
                item: LibMarketItem,
                targetPath: String?,
                config: Map<String, String>,
                onProgress: (Int, String) -> Unit
            ): InstallOutcome {
                val integCat = libCategoryToIntegration(item.categoryEnum)
                val integItem = findItem(item.id, integCat)
                    ?: return InstallOutcome(false, item.id, error = "item not found: ${item.id}")
                val executor = c.installExecutor
                    ?: return InstallOutcome(false, item.id, error = "InstallExecutor not available")
                val r = executor.install(integItem, targetPath, config) { p ->
                    onProgress(p.percent, p.state.name.lowercase())
                }
                return InstallOutcome(
                    success = r.success,
                    itemId = r.itemId,
                    installedPath = r.installedPath,
                    message = r.message,
                    error = r.error
                )
            }

            override suspend fun uninstall(itemId: String, deleteFiles: Boolean): Boolean {
                return c.installExecutor?.uninstall(itemId, deleteFiles) ?: false
            }

            override suspend fun setEnabled(itemId: String, enabled: Boolean): Boolean {
                return c.installedManager.setEnabled(itemId, enabled)
            }

            override suspend fun listInstalled(category: MarketCategory?): List<LibInstalledItem> {
                val items = if (category != null) {
                    c.getInstalledByCategory(libCategoryToIntegration(category))
                } else {
                    c.installedSnapshot.value.items
                }
                return items.map { it.toLibInstalled() }
            }

            override suspend fun listUpdatable(): List<LibInstalledItem> {
                return c.getUpdatable().map { it.toLibInstalled() }
            }

            override suspend fun isInstalled(itemId: String): Boolean {
                return c.installedManager.get(itemId) != null
            }
        }
        engine.llmInvoker = object : LibLlmInvoker {
            override suspend fun invoke(req: LlmInvocation): LlmResult {
                val text = llmInvoker.invoke(
                    req.provider, req.modelName, req.prompt,
                    req.maxTokens, req.systemPrompt, req.temperature
                )
                return LlmResult(
                    text = text,
                    provider = req.provider,
                    modelName = req.modelName
                )
            }

            override fun listAvailableProviders(): List<ProviderInfo> {
                return llmInvoker.listAvailableProviders().map {
                    ProviderInfo(
                        name = it.name,
                        displayName = it.displayName,
                        baseUrl = it.baseUrl,
                        defaultModel = it.defaultModel,
                        hasApiKey = it.hasApiKey,
                        apiKeySource = it.apiKeySource,
                        region = it.region,
                        freeQuota = it.freeQuota,
                        supportsStreaming = it.supportsStreaming
                    )
                }
            }

            override fun isProviderAvailable(provider: String): Boolean {
                return llmInvoker.isProviderAvailable(provider)
            }
        }
        engine.skillInvoker = object : LibSkillInvoker {
            override suspend fun invoke(req: SkillInvocation): SkillResult {
                return try {
                    val result = localSkillInvoker.invoke(req.itemId, req.method, req.argsJson)
                    SkillResult(success = true, resultJson = result)
                } catch (t: Throwable) {
                    SkillResult(success = false, error = t.message ?: t.javaClass.simpleName)
                }
            }

            override fun listMethods(itemId: String): List<String> {
                return localSkillInvoker.listMethods(itemId)
            }

            override fun getMetadata(itemId: String): String? {
                return localSkillInvoker.getMetadata(itemId)
            }
        }
        engine.initialize()

        _isInitialized.value = true
        val stats = center?.marketStats
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] initialized; markets: $stats; engine wired")
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
     * 在指定分类下搜索市场内容（带缓存）。
     */
    suspend fun search(
        category: String,
        query: String = "",
        limit: Int = 50,
        useCache: Boolean = true
    ): BridgeResult<List<MarketItemDto>> = bridgeRun {
        ensureInitialized()
        val cat = parseCategory(category)

        // 1. 尝试缓存命中
        if (useCache) {
            val cached = cache.get("_aggregate_", query, cat)
            if (cached != null) {
                return@bridgeRun cached.take(limit).map { it.toDto() }
            }
        }

        // 2. 实际搜索
        val filter = MarketSearchFilter(query = query, pageSize = limit)
        val result = when (cat) {
            IntegrationCategory.SKILLS -> center?.skillModule?.searchAcrossMarkets(filter)
            IntegrationCategory.MCP -> center?.mcpModule?.searchAcrossMarkets(filter)
            IntegrationCategory.PLUGINS -> center?.pluginModule?.searchAcrossMarkets(filter)
            IntegrationCategory.MODEL_PLATFORMS -> center?.modelPlatformModule?.searchAcrossMarkets(filter)
            else -> null
        } ?: MarketSearchResult(emptyList())

        // 3. 写入缓存
        if (useCache) {
            cache.put("_aggregate_", query, cat, result.items)
        }

        // 4. 记录使用统计
        result.items.take(5).forEach { item ->
            usageStats.record(
                itemId = item.id,
                name = item.name,
                category = cat,
                eventType = UsageEventType.SEARCH,
                metadata = mapOf("query" to query)
            )
        }

        result.items.map { it.toDto() }
    }

    /**
     * 在指定市场的指定分类下搜索（不跨市场）。
     */
    suspend fun searchInMarket(
        marketId: String,
        category: String,
        query: String = "",
        limit: Int = 50,
        useCache: Boolean = true
    ): BridgeResult<List<MarketItemDto>> = bridgeRun {
        ensureInitialized()
        val cat = parseCategory(category)

        if (useCache) {
            val cached = cache.get(marketId, query, cat)
            if (cached != null) {
                return@bridgeRun cached.take(limit).map { it.toDto() }
            }
        }

        val filter = MarketSearchFilter(query = query, pageSize = limit)
        val result = when (cat) {
            IntegrationCategory.SKILLS -> center?.skillModule?.searchInMarket(marketId, filter)
            IntegrationCategory.MCP -> center?.mcpModule?.searchInMarket(marketId, filter)
            IntegrationCategory.PLUGINS -> center?.pluginModule?.searchInMarket(marketId, filter)
            IntegrationCategory.MODEL_PLATFORMS -> center?.modelPlatformModule?.searchInMarket(marketId, filter)
            else -> null
        } ?: MarketSearchResult(emptyList())

        if (useCache) {
            cache.put(marketId, query, cat, result.items)
        }

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
     * 调用云端模型 — 真实实现（支持 11 个 Provider）。
     *
     * 支持：DeepSeek / Claude / OpenAI / 通义千问 / 智谱 GLM / Moonshot /
     * MiniMax / Baichuan / Ollama / Agnes / 等。
     *
     * API Key 来源：
     *   1. InstalledManager 中已安装的 model platform 的 metadata["apiKey"]
     *   2. 环境变量（Android 通常无效）
     */
    suspend fun invokeModel(
        provider: String,
        modelName: String,
        prompt: String,
        maxTokens: Int = 2048,
        systemPrompt: String? = null,
        temperature: Float = 0.7f
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val response = llmInvoker.invoke(provider, modelName, prompt, maxTokens, systemPrompt, temperature)
        // 记录使用统计
        center?.let { c ->
            val installed = c.installedManager.getAll().firstOrNull {
                it.name.equals(provider, ignoreCase = true) &&
                it.category == IntegrationCategory.MODEL_PLATFORMS
            }
            if (installed != null) {
                usageStats.record(
                    itemId = installed.id,
                    name = installed.name,
                    category = IntegrationCategory.MODEL_PLATFORMS,
                    eventType = UsageEventType.INVOKE,
                    metadata = mapOf("model" to modelName, "tokens" to maxTokens.toString())
                )
            }
        }
        response
    }

    /**
     * 列出所有可用 LLM Provider（含 apiKey 状态）。
     */
    suspend fun listAvailableProviders(): BridgeResult<List<ProviderAvailability>> = bridgeRun {
        ensureInitialized()
        llmInvoker.listAvailableProviders()
    }

    /**
     * 检查某 Provider 是否可用（有 apiKey）。
     */
    suspend fun isProviderAvailable(provider: String): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        llmInvoker.isProviderAvailable(provider)
    }

    /**
     * 调用本地已安装的技能 / 插件 / MCP — 真实实现。
     */
    suspend fun invokeLocalSkill(
        itemId: String,
        method: String,
        argsJson: String
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val result = localSkillInvoker.invoke(itemId, method, argsJson)
        // 记录使用统计
        center?.let { c ->
            val installed = c.installedManager.get(itemId)
            if (installed != null) {
                usageStats.record(
                    itemId = itemId,
                    name = installed.name,
                    category = installed.category,
                    eventType = UsageEventType.INVOKE,
                    metadata = mapOf("method" to method)
                )
            }
        }
        result
    }

    /**
     * 列出某已安装项支持的方法。
     */
    suspend fun listLocalSkillMethods(itemId: String): BridgeResult<List<String>> = bridgeRun {
        ensureInitialized()
        localSkillInvoker.listMethods(itemId)
    }

    /**
     * 获取已安装项的元数据。
     */
    suspend fun getInstalledItemMetadata(itemId: String): BridgeResult<String?> = bridgeRun {
        ensureInitialized()
        localSkillInvoker.getMetadata(itemId)
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
            totalInstalled = overview.installedCount,
            updatableCount = overview.updatableCount,
            enabledCount = overview.enabledCount,
            disabledCount = overview.disabledCount
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
     * 列出套件中所有 APK 的安装状态（用于"套件 APK 商店"UI）。
     *
     * 返回每个 APK 的描述符 + 安装状态 + 已安装版本。
     */
    fun listSuiteApks(): List<SuiteApkStatus> {
        return com.apex.sdk.common.ApkDescriptors.ALL.map { desc ->
            val installed = com.apex.sdk.common.ApkDependencyManager.isApkInstalled(context, desc.apkId)
            val version = if (installed) com.apex.sdk.common.ApkDependencyManager.getInstalledVersion(context, desc.apkId) else null
            val missingDeps = com.apex.sdk.common.ApkDependencyManager.checkDependencies(context, desc.apkId)
            SuiteApkStatus(
                apkId = desc.apkId,
                packageName = desc.packageName,
                displayName = desc.displayName,
                description = desc.description,
                necessity = desc.necessity.name,
                capabilities = desc.capabilities,
                dependsOn = desc.dependsOn,
                approxSizeMb = desc.approxSizeMb,
                downloadUrl = desc.downloadUrl,
                installed = installed,
                installedVersion = version,
                missingDependencies = missingDeps.map { it.apkId }
            )
        }
    }

    /**
     * 安装套件 APK（跳转到下载页或启动本地 APK 文件安装）。
     *
     * @param apkId 要安装的 APK ID
     * @param apkFileUri 已下载的 APK 文件 URI（可选，未提供则跳转下载页）
     * @return 是否成功启动安装流程
     */
    fun installSuiteApk(apkId: String, apkFileUri: String? = null): Boolean {
        return if (apkFileUri != null) {
            com.apex.sdk.common.ApkDependencyManager.startInstallFromUri(
                context,
                android.net.Uri.parse(apkFileUri)
            )
        } else {
            com.apex.sdk.common.ApkDependencyManager.openDownloadPage(context, apkId)
        }
    }

    /**
     * 启动套件 APK。
     */
    fun launchSuiteApk(apkId: String): Boolean {
        return com.apex.sdk.common.ApkDependencyManager.launchApk(context, apkId)
    }

    /**
     * 获取套件安装摘要。
     */
    fun getSuiteInstallSummary(): String {
        return com.apex.sdk.common.ApkDependencyManager.getInstallSummary(context)
    }

    /**
     * 检查所有必须 APK 是否已安装。
     */
    fun checkRequiredApks(): List<String> {
        return com.apex.sdk.common.ApkDependencyManager.checkRequiredApks(context).map { it.apkId }
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

    // ============================================================
    // Apex 独有增强：收藏夹
    // ============================================================

    /** 添加收藏。 */
    suspend fun addFavorite(itemId: String, category: String, name: String, description: String = "", marketId: String = "", version: String = "", note: String = ""): BridgeResult<Boolean> = bridgeRun {
        val cat = parseCategory(category)
        // 构造一个最小 MarketItem 用于收藏
        val item = MarketItem(
            id = itemId, name = name, description = description, version = version,
            category = cat, marketId = marketId, downloadUrl = ""
        )
        favorites.add(item, note)
        usageStats.record(itemId, name, cat, UsageEventType.FAVORITE)
        true
    }

    /** 移除收藏。 */
    suspend fun removeFavorite(itemId: String): BridgeResult<Boolean> = bridgeRun {
        val removed = favorites.remove(itemId)
        if (removed) {
            usageStats.record(itemId, "", IntegrationCategory.SKILLS, UsageEventType.UNFAVORITE)
        }
        removed
    }

    /** 切换收藏状态。 */
    suspend fun toggleFavorite(itemId: String, category: String, name: String, description: String = "", marketId: String = "", version: String = ""): BridgeResult<Boolean> = bridgeRun {
        if (favorites.isFavorite(itemId)) {
            favorites.remove(itemId)
            usageStats.record(itemId, name, parseCategory(category), UsageEventType.UNFAVORITE)
            false
        } else {
            val item = MarketItem(id = itemId, name = name, description = description, version = version,
                category = parseCategory(category), marketId = marketId, downloadUrl = "")
            favorites.add(item)
            usageStats.record(itemId, name, parseCategory(category), UsageEventType.FAVORITE)
            true
        }
    }

    /** 是否已收藏。 */
    fun isFavorite(itemId: String): Boolean = favorites.isFavorite(itemId)

    /** 列出所有收藏。 */
    suspend fun listFavorites(category: String? = null): BridgeResult<List<FavoriteEntry>> = bridgeRun {
        if (category != null) {
            favorites.listByCategory(parseCategory(category))
        } else {
            favorites.listAll()
        }
    }

    /** 搜索收藏。 */
    suspend fun searchFavorites(query: String): BridgeResult<List<FavoriteEntry>> = bridgeRun {
        favorites.search(query)
    }

    /** 更新收藏备注。 */
    suspend fun updateFavoriteNote(itemId: String, note: String): BridgeResult<Boolean> = bridgeRun {
        favorites.updateNote(itemId, note)
    }

    /** 清空收藏。 */
    suspend fun clearFavorites(): BridgeResult<Int> = bridgeRun { favorites.clear() }

    /** 收藏总数。 */
    fun favoritesCount(): Int = favorites.count()

    /** 按分类统计收藏。 */
    fun favoritesCountByCategory(): Map<String, Int> = favorites.countByCategory()

    // ============================================================
    // Apex 独有增强：使用统计
    // ============================================================

    /** 获取某项的使用统计。 */
    suspend fun getItemStats(itemId: String): BridgeResult<ItemStats?> = bridgeRun {
        usageStats.getStats(itemId)
    }

    /** "最近使用"列表。 */
    suspend fun getRecentlyUsed(limit: Int = 20): BridgeResult<List<ItemStats>> = bridgeRun {
        usageStats.getRecentlyUsed(limit)
    }

    /** "最常使用"排行榜。 */
    suspend fun getMostUsed(limit: Int = 20): BridgeResult<List<ItemStats>> = bridgeRun {
        usageStats.getMostUsed(limit)
    }

    /** 总统计。 */
    suspend fun getTotalUsageStats(): BridgeResult<TotalStats> = bridgeRun {
        usageStats.getTotalStats()
    }

    /** 按分类统计使用次数。 */
    suspend fun getUsageByCategory(): BridgeResult<Map<String, Int>> = bridgeRun {
        usageStats.getUsageByCategory()
    }

    /** 获取最近事件流。 */
    suspend fun getRecentEvents(limit: Int = 100): BridgeResult<List<UsageEvent>> = bridgeRun {
        usageStats.getRecentEvents(limit)
    }

    /** 记录查看事件（用于"最近使用"统计）。 */
    suspend fun recordView(itemId: String, name: String, category: String): BridgeResult<Unit> = bridgeRun {
        usageStats.record(itemId, name, parseCategory(category), UsageEventType.VIEW)
    }

    /** 清除所有统计。 */
    suspend fun clearUsageStats(): BridgeResult<Unit> = bridgeRun {
        usageStats.clear()
    }

    // ============================================================
    // Apex 独有增强：市场缓存管理
    // ============================================================

    /** 清除指定市场的缓存。 */
    suspend fun clearCacheForMarket(marketId: String): BridgeResult<Int> = bridgeRun {
        cache.clearForMarket(marketId)
    }

    /** 清除所有缓存。 */
    suspend fun clearAllCache(): BridgeResult<Int> = bridgeRun {
        cache.clearAll()
    }

    /** 清除过期缓存。 */
    suspend fun cleanExpiredCache(): BridgeResult<Int> = bridgeRun {
        cache.cleanExpired()
    }

    /** 缓存统计。 */
    fun getCacheStats(): CacheStats = cache.getStats()

    /** 列出所有缓存条目。 */
    fun listCacheEntries() = cache.listEntries()

    // ============================================================
    // Apex 独有增强：批量操作 + 更新检查
    // ============================================================

    /** 批量安装。 */
    suspend fun batchInstall(
        items: List<Triple<String, String, String>>  // (itemId, category, targetPath?)
    ): BridgeResult<List<InstallResultDto>> = bridgeRun {
        ensureInitialized()
        val results = mutableListOf<InstallResultDto>()
        for ((itemId, category, targetPath) in items) {
            val cat = parseCategory(category)
            val item = findItem(itemId, cat) ?: run {
                results.add(InstallResultDto(false, itemId, null, null, "item not found"))
                continue
            }
            val executor = center?.installExecutor ?: continue
            val r = executor.install(item, targetPath)
            results.add(InstallResultDto(
                success = r.success, itemId = r.itemId,
                installedPath = r.installedPath, message = r.message, error = r.error
            ))
            if (r.success) {
                usageStats.record(itemId, item.name, cat, UsageEventType.INSTALL)
            }
        }
        results
    }

    /** 批量卸载。 */
    suspend fun batchUninstall(itemIds: List<String>): BridgeResult<List<Pair<String, Boolean>>> = bridgeRun {
        ensureInitialized()
        itemIds.map { id ->
            val ok = center?.installExecutor?.uninstall(id, true) ?: false
            if (ok) {
                val installed = center?.installedManager?.get(id)
                if (installed != null) {
                    usageStats.record(id, installed.name, installed.category, UsageEventType.UNINSTALL)
                }
            }
            id to ok
        }
    }

    /** 检查更新（扫描所有已安装项的最新版本）。 */
    suspend fun checkForUpdates(): BridgeResult<List<InstalledItemDto>> = bridgeRun {
        ensureInitialized()
        // 实际版本检查需要联网，这里返回 getUpdatable（基于已知 latestVersion）
        center?.getUpdatable()?.map { it.toDto() } ?: emptyList()
    }

    /** 批量更新所有可更新项。 */
    suspend fun updateAll(): BridgeResult<List<InstallResultDto>> = bridgeRun {
        ensureInitialized()
        val updatable = center?.getUpdatable() ?: emptyList()
        val items = updatable.map { Triple(it.id, it.category.name, null as String?) }
        batchInstall(items)
    }

    /** 刷新所有市场（清缓存 + 重新加载）。 */
    suspend fun refreshAllMarkets(): BridgeResult<Unit> = bridgeRun {
        ensureInitialized()
        cache.clearAll()
        center?.refreshAllMarkets()
    }

    /** 刷新指定市场。 */
    suspend fun refreshMarket(marketId: String): BridgeResult<Int> = bridgeRun {
        cache.clearForMarket(marketId)
    }

    /** 市场诊断。 */
    suspend fun diagnose(): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val report = center?.diagnose()
        report?.toString() ?: "no diagnostics available"
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

    // ============================================================
    // lib:market 引擎委托 API（基于 lib 模型 / BridgeResult）
    // ============================================================

    /** 暴露内部 [MarketEngine] 实例（高级调用方可直接访问 lib 全部 API）。 */
    fun getEngine(): MarketEngine = engine

    // ----- 市场目录（lib 镜像） -----

    /** 列出 27 个内置市场目录（按分类，可选）。 */
    suspend fun listCatalog(category: String? = null): BridgeResult<List<MarketCatalog.Entry>> {
        val cat = category?.let { MarketCategory.fromIntegrationName(it) }
        return engine.listCatalog(cat)
    }

    /** 在市场目录中按关键字搜索。 */
    suspend fun searchCatalog(query: String, limit: Int = 50): BridgeResult<List<MarketCatalog.Entry>> =
        engine.searchCatalog(query, limit)

    // ----- 基于引擎的搜索（lib 模型，缓存优先 + 自动统计） -----

    /**
     * 通过 lib 引擎搜索（缓存优先，未命中调用 [MarketFetcher]）。
     * 返回 lib 的 [LibMarketItem] 列表，便于跨 APK 透传。
     */
    suspend fun searchViaEngine(
        category: String,
        query: String = "",
        marketId: String? = null,
        limit: Int = 50,
        useCache: Boolean = true
    ): BridgeResult<List<LibMarketItem>> {
        ensureInitialized()
        val cat = MarketCategory.fromIntegrationName(category)
        return engine.search(cat, query, marketId, limit, useCache)
    }

    // ----- 安装任务管理（lib 状态机） -----

    /**
     * 通过 lib 引擎入队安装（状态机 + 进度流）。
     * @return taskId
     */
    suspend fun enqueueInstall(
        itemId: String,
        category: String,
        name: String,
        version: String = "",
        marketId: String = "",
        targetPath: String? = null,
        config: Map<String, String> = emptyMap()
    ): BridgeResult<String> {
        ensureInitialized()
        val cat = MarketCategory.fromIntegrationName(category)
        val item = LibMarketItem(
            id = itemId, name = name, version = version,
            category = cat.name, marketId = marketId
        )
        return engine.install(item, targetPath, config)
    }

    /** 取消安装任务。 */
    suspend fun cancelInstallTask(taskId: String): BridgeResult<Boolean> =
        engine.cancelInstall(taskId)

    /** 查询安装任务状态。 */
    fun getInstallTask(taskId: String): InstallTask? = engine.getInstallTask(taskId)

    /** 按 itemId 查找进行中的安装任务。 */
    fun getInstallTaskByItem(itemId: String): InstallTask? = engine.getInstallTaskByItem(itemId)

    /** 列出所有安装任务。 */
    fun listInstallTasks(limit: Int = 100): List<InstallTask> = engine.listInstallTasks(limit)

    /** 列出最近完成的安装任务。 */
    fun listRecentCompletedInstalls(limit: Int = 20): List<InstallTask> =
        engine.listRecentCompletedInstalls(limit)

    // ----- LLM / Skill 通过引擎调用（lib 契约） -----

    /** 通过 lib 引擎调用 LLM（使用注入的 [LibLlmInvoker]）。 */
    suspend fun invokeLlmViaEngine(
        provider: String,
        modelName: String,
        prompt: String,
        maxTokens: Int = 2048,
        systemPrompt: String? = null,
        temperature: Float = 0.7f
    ): BridgeResult<LlmResult> {
        ensureInitialized()
        return engine.invokeLlm(
            LlmInvocation(
                provider = provider, modelName = modelName, prompt = prompt,
                maxTokens = maxTokens, systemPrompt = systemPrompt, temperature = temperature
            )
        )
    }

    /** 通过 lib 引擎调用本地技能。 */
    suspend fun invokeSkillViaEngine(
        itemId: String,
        method: String,
        argsJson: String = "{}"
    ): BridgeResult<SkillResult> {
        ensureInitialized()
        return engine.invokeSkill(SkillInvocation(itemId = itemId, method = method, argsJson = argsJson))
    }

    /** 列出可用 Provider（lib 模型）。 */
    suspend fun listProvidersViaEngine(): BridgeResult<List<ProviderInfo>> {
        ensureInitialized()
        return engine.listAvailableProviders()
    }

    // ----- 引擎事件流访问 -----

    /** 引擎事件流（lib [MarketEvent]）。 */
    fun engineEventsFlow(): SharedFlow<MarketEvent> = engine.events

    /** 安装进度事件流。 */
    fun installEventsFlow(): SharedFlow<MarketEvent> = engine.installEvents()

    // ============================================================
    // lib ↔ integration 模型互转
    // ============================================================

    /** lib [MarketCategory] → integration [IntegrationCategory]。 */
    private fun libCategoryToIntegration(cat: MarketCategory): IntegrationCategory = when (cat) {
        MarketCategory.SKILL -> IntegrationCategory.SKILLS
        MarketCategory.MCP -> IntegrationCategory.MCP
        MarketCategory.PLUGIN -> IntegrationCategory.PLUGINS
        MarketCategory.MODEL -> IntegrationCategory.MODEL_PLATFORMS
        MarketCategory.AGENT_ROLE -> IntegrationCategory.SKILLS  // AGENT_ROLE 复用 SKILLS 桶
    }

    /** integration [MarketItem] → lib [LibMarketItem]。 */
    private fun MarketItem.toLibItem(): LibMarketItem = LibMarketItem(
        id = id,
        name = name,
        author = author,
        description = description,
        version = version,
        category = category.name,
        sourceUrl = sourceUrl,
        downloadUrl = downloadUrl,
        iconUrl = iconUrl,
        marketId = marketId,
        installed = isInstalled,
        hasUpdate = hasUpdate,
        rating = rating,
        downloadCount = downloadCount,
        downloadSizeBytes = downloadSizeBytes,
        tags = tags,
        metadata = metadata
    )

    /** integration [InstalledItem] → lib [LibInstalledItem]。 */
    private fun InstalledItem.toLibInstalled(): LibInstalledItem = LibInstalledItem(
        itemId = id,
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
    val totalInstalled: Int,
    val updatableCount: Int,
    val enabledCount: Int,
    val disabledCount: Int
)

/** 套件中单个 APK 的安装状态。 */
data class SuiteApkStatus(
    val apkId: String,
    val packageName: String,
    val displayName: String,
    val description: String,
    /** REQUIRED / OPTIONAL / DEBUG */
    val necessity: String,
    val capabilities: List<String>,
    val dependsOn: List<String>,
    val approxSizeMb: Int,
    val downloadUrl: String,
    val installed: Boolean,
    val installedVersion: String?,
    /** 该 APK 依赖但未安装的其他 APK ID 列表。 */
    val missingDependencies: List<String>
) {
    /** 是否可立即使用（已安装且所有依赖已安装）。 */
    val isReady: Boolean get() = installed && missingDependencies.isEmpty()
}

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
