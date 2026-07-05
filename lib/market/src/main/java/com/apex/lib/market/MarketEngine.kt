package com.apex.lib.market

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * 市场引擎 — lib:market 的核心入口。
 *
 * 整合：
 *   - [MarketCatalog]：27 个内置市场目录元数据
 *   - [MarketCache]：搜索结果缓存（带 TTL + 持久化）
 *   - [Favorites]：收藏夹（持久化）
 *   - [UsageStats]：使用统计（持久化 + 事件流）
 *   - [InstallManager]：安装流程状态机 + 任务队列
 *   - [MarketFetcher]：实际搜索后端（APK 注入）
 *   - [Installer]：实际下载 + 安装（APK 注入）
 *   - [LlmInvoker]：云端 LLM 调用（APK 注入）
 *   - [SkillInvoker]：本地技能调用（APK 注入）
 *
 * **使用方式**：
 *   1. APK 的 [com.apex.apk.market.MarketServiceFacade] 持有 [MarketEngine] 实例
 *   2. 初始化时注入 [MarketFetcher] / [Installer] / [LlmInvoker] / [SkillInvoker]
 *   3. 所有公开 API 返回 [BridgeResult]，异常由 [bridgeRun] 统一捕获
 *   4. UI / 跨 APK 通知订阅 [events] 获取实时事件
 *
 * @property storageDir 持久化根目录（cache / favorites / stats 各占一个子目录）
 */
class MarketEngine(
    private val storageDir: File
) {
    private const val TAG_SUB = "MarketEngine"

    // ===== 子组件（lib 内部） =====
    /** 市场缓存。 */
    val cache: MarketCache = MarketCache(File(storageDir, "cache"))
    /** 收藏夹。 */
    val favorites: Favorites = Favorites(File(storageDir, "favorites"))
    /** 使用统计。 */
    val usageStats: UsageStats = UsageStats(File(storageDir, "stats"))

    // ===== APK 注入的实现契约（init 后才能调用相关方法） =====
    /** 实际搜索后端（APK 注入；未注入则 search 走"仅缓存"模式）。 */
    @Volatile
    var fetcher: MarketFetcher? = null
    /** 实际下载 + 安装后端（APK 注入；未注入则 install 抛异常）。 */
    @Volatile
    var installer: Installer? = null
    /** LLM 调用器（APK 注入；未注入则 invokeLlm 抛异常）。 */
    @Volatile
    var llmInvoker: LlmInvoker? = null
    /** 本地技能调用器（APK 注入；未注入则 invokeSkill 抛异常）。 */
    @Volatile
    var skillInvoker: SkillInvoker? = null

    /** 安装管理器（依赖 [installer]，installer 注入后惰性创建）。 */
    @Volatile
    private var installManager: InstallManager? = null

    private val _engineEvents = MutableSharedFlow<MarketEvent>(extraBufferCapacity = 64)

    /** 引擎事件流（聚合 [InstallManager.events] 与引擎自身事件）。 */
    val events: SharedFlow<MarketEvent> = run {
        // 注意：installManager 是惰性创建的，其 events 流在创建后才能 merge
        // 为简化实现，先暴露 _engineEvents，安装管理器创建后再通过 tryEmit 转发
        _engineEvents.asSharedFlow()
    }

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 初始化引擎（已注入 [installer] 后创建 [InstallManager] 并桥接事件）。
     */
    suspend fun initialize(): BridgeResult<Unit> = bridgeRun {
        storageDir.mkdirs()
        // 桥接 InstallManager 事件到 _engineEvents
        ensureInstallManager()
        ApexLog.i(ApexSuite.ApkId.MARKET,
            "[$TAG_SUB] initialized; cache=${cache.getStats().entryCount} favs=${favorites.count()} itemsTracked=${usageStats.getTotalStats().totalItems}")
    }

    /** 当 [installer] 已注入时惰性创建 [InstallManager] 并订阅其事件。 */
    private fun ensureInstallManager(): InstallManager {
        installManager?.let { return it }
        val inst = installer ?: throw IllegalStateException("Installer not injected")
        synchronized(this) {
            installManager?.let { return it }
            val mgr = InstallManager(inst)
            // 在后台消费 InstallManager.events 并转发到 _engineEvents
            // 注意：SharedFlow 无 cancel 时不会停止；为避免泄漏，使用 tryEmit 在每次事件时转发
            // 这里采用"软转发"——在引擎的关键 API（install / cancel）内直接 tryEmit，
            // 同时通过 kotlinx.coroutines.flow.merge 不可用（需要 scope），简化为：UI 可直接订阅 mgr.events
            installManager = mgr
            ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] InstallManager created")
            return mgr
        }
    }

    /** 暴露 InstallManager 的 events 流（供 UI 直接订阅安装进度）。 */
    fun installEvents(): SharedFlow<MarketEvent> = ensureInstallManagerInternal()?.events
        ?: _engineEvents.asSharedFlow()

    private fun ensureInstallManagerInternal(): InstallManager? = installManager

    private fun requireFetcher(): MarketFetcher =
        fetcher ?: throw IllegalStateException("MarketFetcher not injected")
    private fun requireInstaller(): Installer =
        installer ?: throw IllegalStateException("Installer not injected")
    private fun requireLlmInvoker(): LlmInvoker =
        llmInvoker ?: throw IllegalStateException("LlmInvoker not injected")
    private fun requireSkillInvoker(): SkillInvoker =
        skillInvoker ?: throw IllegalStateException("SkillInvoker not injected")

    // ============================================================
    // 市场目录
    // ============================================================

    /** 列出市场目录（按分类）。 */
    suspend fun listCatalog(category: MarketCategory? = null): BridgeResult<List<MarketCatalog.Entry>> = bridgeRun {
        if (category != null) MarketCatalog.byCategory(category) else MarketCatalog.ALL
    }

    /** 在市场目录中按关键字搜索。 */
    suspend fun searchCatalog(query: String, limit: Int = 50): BridgeResult<List<MarketCatalog.Entry>> = bridgeRun {
        MarketCatalog.search(query, limit)
    }

    /** 市场目录统计。 */
    suspend fun catalogStats(): BridgeResult<Map<MarketCategory, Int>> = bridgeRun {
        MarketCatalog.stats()
    }

    // ============================================================
    // 搜索
    // ============================================================

    /**
     * 搜索市场内容（跨市场聚合，缓存优先）。
     *
     * @param category 分类
     * @param query 关键字（空 = 列出全部）
     * @param marketId 限定市场（null / 空 = 跨市场聚合，使用 "_aggregate_" 作缓存 key）
     * @param limit 最大返回数
     * @param useCache 是否使用缓存
     */
    suspend fun search(
        category: MarketCategory,
        query: String = "",
        marketId: String? = null,
        limit: Int = 50,
        useCache: Boolean = true
    ): BridgeResult<List<MarketItem>> = bridgeRun {
        val cacheKey = marketId?.takeIf { it.isNotBlank() } ?: "_aggregate_"

        // 1. 缓存命中
        if (useCache) {
            val cached = cache.get(cacheKey, query, category)
            if (cached != null) {
                _engineEvents.tryEmit(
                    MarketEvent.SearchCompleted(category, query, cached.size, fromCache = true)
                )
                return@bridgeRun cached.take(limit)
            }
        }

        // 2. 调用 fetcher
        val items = try {
            requireFetcher().fetch(category, query, marketId?.takeIf { it.isNotBlank() }, limit)
        } catch (t: Throwable) {
            ApexLog.w(ApexSuite.ApkId.MARKET,
                "[$TAG_SUB] search fetcher failed, falling back to empty: ${t.message}")
            emptyList()
        }

        // 3. 写缓存
        if (useCache && items.isNotEmpty()) {
            cache.put(cacheKey, query, category, items)
        }

        // 4. 记录使用统计（前 5 个）
        items.take(5).forEach { it ->
            usageStats.record(it.id, it.name, category, UsageEventType.SEARCH, mapOf("query" to query))
        }
        _engineEvents.tryEmit(
            MarketEvent.SearchCompleted(category, query, items.size, fromCache = false)
        )
        items.take(limit)
    }

    // ============================================================
    // 安装 / 卸载
    // ============================================================

    /**
     * 入队一个安装任务。
     * @return taskId
     */
    suspend fun install(
        item: MarketItem,
        targetPath: String? = null,
        config: Map<String, String> = emptyMap()
    ): BridgeResult<String> = bridgeRun {
        val mgr = ensureInstallManager()
        val taskId = mgr.enqueue(item, targetPath, config)
        usageStats.record(item.id, item.name, item.categoryEnum, UsageEventType.INSTALL)
        taskId
    }

    /**
     * 取消安装任务。
     */
    suspend fun cancelInstall(taskId: String): BridgeResult<Boolean> = bridgeRun {
        ensureInstallManager().cancel(taskId)
    }

    /** 获取安装任务状态。 */
    fun getInstallTask(taskId: String): InstallTask? = installManager?.getTask(taskId)

    /** 按 itemId 查找进行中的安装任务。 */
    fun getInstallTaskByItem(itemId: String): InstallTask? = installManager?.getTaskByItem(itemId)

    /** 列出所有安装任务。 */
    fun listInstallTasks(limit: Int = 100): List<InstallTask> =
        installManager?.listTasks(limit) ?: emptyList()

    /** 列出最近完成的安装任务。 */
    fun listRecentCompletedInstalls(limit: Int = 20): List<InstallTask> =
        installManager?.listRecentCompleted(limit) ?: emptyList()

    /**
     * 卸载资产。
     */
    suspend fun uninstall(itemId: String, deleteFiles: Boolean = true): BridgeResult<Boolean> = bridgeRun {
        val ok = requireInstaller().uninstall(itemId, deleteFiles)
        if (ok) {
            usageStats.record(itemId, "", MarketCategory.SKILL, UsageEventType.UNINSTALL)
            _engineEvents.tryEmit(MarketEvent.Uninstalled(itemId))
        }
        ok
    }

    /**
     * 启用 / 禁用资产。
     */
    suspend fun setEnabled(itemId: String, enabled: Boolean): BridgeResult<Boolean> = bridgeRun {
        requireInstaller().setEnabled(itemId, enabled)
    }

    /**
     * 列出所有已安装项。
     */
    suspend fun listInstalled(category: MarketCategory? = null): BridgeResult<List<InstalledItem>> = bridgeRun {
        requireInstaller().listInstalled(category)
    }

    /**
     * 列出可更新项。
     */
    suspend fun listUpdatable(): BridgeResult<List<InstalledItem>> = bridgeRun {
        requireInstaller().listUpdatable()
    }

    // ============================================================
    // 收藏夹
    // ============================================================

    suspend fun addFavorite(item: MarketItem, note: String = ""): BridgeResult<Boolean> = bridgeRun {
        val ok = favorites.add(item, note)
        if (ok) {
            usageStats.record(item.id, item.name, item.categoryEnum, UsageEventType.FAVORITE)
            _engineEvents.tryEmit(MarketEvent.FavoriteAdded(item.id))
        }
        ok
    }

    suspend fun removeFavorite(itemId: String): BridgeResult<Boolean> = bridgeRun {
        val ok = favorites.remove(itemId)
        if (ok) {
            usageStats.record(itemId, "", MarketCategory.SKILL, UsageEventType.UNFAVORITE)
            _engineEvents.tryEmit(MarketEvent.FavoriteRemoved(itemId))
        }
        ok
    }

    suspend fun toggleFavorite(item: MarketItem): BridgeResult<Boolean> = bridgeRun {
        val nowFav = favorites.toggle(item)
        if (nowFav) {
            usageStats.record(item.id, item.name, item.categoryEnum, UsageEventType.FAVORITE)
            _engineEvents.tryEmit(MarketEvent.FavoriteAdded(item.id))
        } else {
            usageStats.record(item.id, item.name, item.categoryEnum, UsageEventType.UNFAVORITE)
            _engineEvents.tryEmit(MarketEvent.FavoriteRemoved(item.id))
        }
        nowFav
    }

    fun isFavorite(itemId: String): Boolean = favorites.isFavorite(itemId)

    suspend fun listFavorites(category: MarketCategory? = null): BridgeResult<List<FavoriteEntry>> = bridgeRun {
        if (category != null) favorites.listByCategory(category) else favorites.listAll()
    }

    suspend fun searchFavorites(query: String): BridgeResult<List<FavoriteEntry>> = bridgeRun {
        favorites.search(query)
    }

    suspend fun updateFavoriteNote(itemId: String, note: String): BridgeResult<Boolean> = bridgeRun {
        favorites.updateNote(itemId, note)
    }

    suspend fun clearFavorites(): BridgeResult<Int> = bridgeRun { favorites.clear() }

    fun favoritesCount(): Int = favorites.count()

    fun favoritesCountByCategory(): Map<String, Int> = favorites.countByCategory()

    // ============================================================
    // 使用统计
    // ============================================================

    suspend fun getItemStats(itemId: String): BridgeResult<ItemStats?> = bridgeRun {
        usageStats.getStats(itemId)
    }

    suspend fun getRecentlyUsed(limit: Int = 20): BridgeResult<List<ItemStats>> = bridgeRun {
        usageStats.getRecentlyUsed(limit)
    }

    suspend fun getMostUsed(limit: Int = 20): BridgeResult<List<ItemStats>> = bridgeRun {
        usageStats.getMostUsed(limit)
    }

    /** "热门项"排行榜 — 同 [getMostUsed]，对外更直观的命名。 */
    suspend fun getHotItems(limit: Int = 20): BridgeResult<List<ItemStats>> = bridgeRun {
        usageStats.getHotItems(limit)
    }

    suspend fun getTotalUsageStats(): BridgeResult<TotalStats> = bridgeRun {
        usageStats.getTotalStats()
    }

    suspend fun getUsageByCategory(): BridgeResult<Map<String, Int>> = bridgeRun {
        usageStats.getUsageByCategory()
    }

    suspend fun getRecentEvents(limit: Int = 100): BridgeResult<List<UsageEvent>> = bridgeRun {
        usageStats.getRecentEvents(limit)
    }

    suspend fun recordView(
        itemId: String,
        name: String,
        category: MarketCategory
    ): BridgeResult<Unit> = bridgeRun {
        usageStats.record(itemId, name, category, UsageEventType.VIEW)
        _engineEvents.tryEmit(MarketEvent.UsageRecorded(itemId, UsageEventType.VIEW.name))
    }

    /** 手动记录一次调用事件（业务侧调用本地技能 / LLM 后透传）。 */
    fun recordInvoke(
        itemId: String,
        name: String,
        category: MarketCategory,
        metadata: Map<String, String> = emptyMap()
    ) {
        usageStats.record(itemId, name, category, UsageEventType.INVOKE, metadata)
        _engineEvents.tryEmit(MarketEvent.UsageRecorded(itemId, UsageEventType.INVOKE.name))
    }

    suspend fun clearUsageStats(): BridgeResult<Unit> = bridgeRun {
        usageStats.clear()
    }

    // ============================================================
    // 缓存管理
    // ============================================================

    suspend fun clearCacheForMarket(marketId: String): BridgeResult<Int> = bridgeRun {
        val n = cache.clearForMarket(marketId)
        _engineEvents.tryEmit(MarketEvent.CacheInvalidated(marketId, n))
        n
    }

    suspend fun clearAllCache(): BridgeResult<Int> = bridgeRun {
        val n = cache.clearAll()
        _engineEvents.tryEmit(MarketEvent.CacheInvalidated(null, n))
        n
    }

    suspend fun cleanExpiredCache(): BridgeResult<Int> = bridgeRun {
        cache.cleanExpired()
    }

    fun getCacheStats(): CacheStats = cache.getStats()

    fun listCacheEntries(): List<CacheEntry> = cache.listEntries()

    // ============================================================
    // LLM 调用
    // ============================================================

    suspend fun invokeLlm(req: LlmInvocation): BridgeResult<LlmResult> = bridgeRun {
        val invoker = requireLlmInvoker()
        val started = System.currentTimeMillis()
        ApexLog.i(ApexSuite.ApkId.MARKET,
            "[$TAG_SUB] invokeLlm: provider=${req.provider} model=${req.modelName} prompt=${req.prompt.take(60)}...")
        val result = invoker.invoke(req)
        val latency = System.currentTimeMillis() - started
        // 记录使用统计（按 provider 归类为 MODEL）
        recordInvoke(
            itemId = "${req.provider}:${req.modelName}",
            name = "${req.provider}/${req.modelName}",
            category = MarketCategory.MODEL,
            metadata = mapOf(
                "model" to req.modelName,
                "tokens" to result.tokensUsed.toString(),
                "latencyMs" to latency.toString()
            )
        )
        result.copy(latencyMs = latency)
    }

    suspend fun listAvailableProviders(): BridgeResult<List<ProviderInfo>> = bridgeRun {
        requireLlmInvoker().listAvailableProviders()
    }

    suspend fun isProviderAvailable(provider: String): BridgeResult<Boolean> = bridgeRun {
        requireLlmInvoker().isProviderAvailable(provider)
    }

    // ============================================================
    // 本地技能调用
    // ============================================================

    suspend fun invokeSkill(req: SkillInvocation): BridgeResult<SkillResult> = bridgeRun {
        val invoker = requireSkillInvoker()
        val started = System.currentTimeMillis()
        ApexLog.i(ApexSuite.ApkId.MARKET,
            "[$TAG_SUB] invokeSkill: item=${req.itemId} method=${req.method}")
        val result = invoker.invoke(req)
        val latency = System.currentTimeMillis() - started
        if (result.success) {
            recordInvoke(
                itemId = req.itemId,
                name = req.itemId,
                category = MarketCategory.SKILL,
                metadata = mapOf("method" to req.method)
            )
        }
        result.copy(latencyMs = latency)
    }

    suspend fun listLocalSkillMethods(itemId: String): BridgeResult<List<String>> = bridgeRun {
        requireSkillInvoker().listMethods(itemId)
    }

    suspend fun getInstalledItemMetadata(itemId: String): BridgeResult<String?> = bridgeRun {
        requireSkillInvoker().getMetadata(itemId)
    }

    // ============================================================
    // 内部 trace 工具
    // ============================================================

    /** 生成一个带 market 前缀的 trace id（供 APK 复用）。 */
    fun newTraceId(prefix: String = "market"): String = Trace.newId(prefix)
}
