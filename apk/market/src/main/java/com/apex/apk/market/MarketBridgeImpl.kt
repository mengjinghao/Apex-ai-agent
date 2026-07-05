package com.apex.apk.market

import com.apex.lib.market.MarketCatalog
import com.apex.lib.market.MarketCategory
import com.apex.lib.market.MarketEvent
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MarketBridgeImpl(
    private val facade: MarketServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.MARKET, "[MarketBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "market/initialize" -> buildResult(facade.initialize()) { JsonObject(emptyMap()) }
                    "market/listMarkets" -> {
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        buildResult(facade.listMarkets(cat)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("markets", list.joinToString("\n") { "${it.marketId}: ${it.displayName} (available=${it.available})" })
                            }
                        }
                    }
                    "market/search" -> {
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val query = args["query"]?.jsonPrimitive?.content ?: ""
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
                        buildResult(facade.search(cat, query, limit)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.id}: ${it.name} v${it.version}" })
                            }
                        }
                    }
                    "market/listInstalled" -> {
                        val cat = args["category"]?.jsonPrimitive?.content
                        buildResult(facade.listInstalled(cat)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.id}: ${it.name} v${it.installedVersion} (enabled=${it.enabled})" })
                            }
                        }
                    }
                    "market/install" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        buildResult(facade.install(itemId, cat)) { r ->
                            buildJsonObject {
                                put("success", r.success)
                                put("itemId", r.itemId)
                                put("installedPath", r.installedPath ?: "")
                                put("message", r.message ?: "")
                            }
                        }
                    }
                    "market/uninstall" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.uninstall(itemId)) { JsonPrimitive(it) }
                    }
                    "market/setEnabled" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val enabled = args["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        buildResult(facade.setEnabled(itemId, enabled)) { JsonPrimitive(it) }
                    }
                    "market/import" -> {
                        val sourceType = args["sourceType"]?.jsonPrimitive?.content ?: "LOCAL_FILE"
                        val path = args["path"]?.jsonPrimitive?.content
                        val url = args["url"]?.jsonPrimitive?.content
                        val content = args["content"]?.jsonPrimitive?.content
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val autoInstall = args["autoInstall"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                        buildResult(facade.importAsset(sourceType, path, url, content, cat, autoInstall)) { r ->
                            buildJsonObject {
                                put("success", r.success)
                                put("importedItemId", r.importedItemId ?: "")
                                put("installedItemId", r.installedItemId ?: "")
                                put("warnings", r.warnings.size)
                                put("errors", r.errors.size)
                            }
                        }
                    }
                    "market/invokeModel" -> {
                        val provider = args["provider"]?.jsonPrimitive?.content ?: ""
                        val modelName = args["modelName"]?.jsonPrimitive?.content ?: ""
                        val prompt = args["prompt"]?.jsonPrimitive?.content ?: ""
                        val maxTokens = args["maxTokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2048
                        buildResult(facade.invokeModel(provider, modelName, prompt, maxTokens)) { JsonPrimitive(it) }
                    }
                    "market/invokeLocalSkill" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val m = args["method"]?.jsonPrimitive?.content ?: ""
                        val aJson = args["argsJson"]?.jsonPrimitive?.content ?: "{}"
                        buildResult(facade.invokeLocalSkill(itemId, m, aJson)) { JsonPrimitive(it) }
                    }
                    "market/getOverview" -> {
                        buildResult(facade.getOverview()) { o ->
                            buildJsonObject {
                                put("totalMarkets", o.totalMarkets)
                                put("totalInstalled", o.totalInstalled)
                            }
                        }
                    }
                    "market/getUpdatable" -> {
                        val list = facade.getUpdatable()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("items", list.joinToString("\n") { "${it.id}: ${it.installedVersion} → ${it.latestVersion ?: "?"}" })
                        }.toString()
                    }
                    "market/generateMcpConfig" -> {
                        val format = args["format"]?.jsonPrimitive?.content ?: "APEX_AGENT"
                        val config = facade.generateMcpConfig(format)
                        buildJsonObject {
                            put("success", true)
                            put("config", config)
                        }.toString()
                    }
                    "market/listSuiteApks" -> {
                        val list = facade.listSuiteApks()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("apks", list.joinToString("\n---\n") { a ->
                                "${if (a.installed) "[✓]" else "[✗]"} ${a.displayName} (${a.necessity})" +
                                if (a.installed) " v${a.installedVersion ?: "?"}" else " ~${a.approxSizeMb}MB" +
                                if (a.missingDependencies.isNotEmpty()) " [missing deps: ${a.missingDependencies.joinToString(",")}]" else ""
                            })
                        }.toString()
                    }
                    "market/installSuiteApk" -> {
                        val apkId = args["apkId"]?.jsonPrimitive?.content ?: ""
                        val apkFileUri = args["apkFileUri"]?.jsonPrimitive?.content
                        val ok = facade.installSuiteApk(apkId, apkFileUri)
                        buildJsonObject { put("success", ok) }.toString()
                    }
                    "market/launchSuiteApk" -> {
                        val apkId = args["apkId"]?.jsonPrimitive?.content ?: ""
                        val ok = facade.launchSuiteApk(apkId)
                        buildJsonObject { put("success", ok) }.toString()
                    }
                    "market/getSuiteInstallSummary" -> {
                        buildJsonObject {
                            put("success", true)
                            put("summary", facade.getSuiteInstallSummary())
                        }.toString()
                    }
                    "market/checkRequiredApks" -> {
                        val missing = facade.checkRequiredApks()
                        buildJsonObject {
                            put("success", true)
                            put("missingCount", missing.size)
                            put("missingApkIds", missing.joinToString(","))
                        }.toString()
                    }

                    // ===== Apex 独有增强：LLM 调用 =====
                    "market/invokeModel" -> {
                        val provider = args["provider"]?.jsonPrimitive?.content ?: ""
                        val modelName = args["modelName"]?.jsonPrimitive?.content ?: ""
                        val prompt = args["prompt"]?.jsonPrimitive?.content ?: ""
                        val maxTokens = args["maxTokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2048
                        val systemPrompt = args["systemPrompt"]?.jsonPrimitive?.content
                        val temperature = args["temperature"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.7f
                        buildResult(facade.invokeModel(provider, modelName, prompt, maxTokens, systemPrompt, temperature)) { JsonPrimitive(it) }
                    }
                    "market/listAvailableProviders" -> {
                        buildResult(facade.listAvailableProviders()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("providers", list.joinToString("\n") {
                                    "${if (it.hasApiKey) "[✓]" else "[✗]"} ${it.displayName} (${it.name}) - ${it.defaultModel}"
                                })
                            }
                        }
                    }
                    "market/isProviderAvailable" -> {
                        val provider = args["provider"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.isProviderAvailable(provider)) { JsonPrimitive(it) }
                    }

                    // ===== Apex 独有增强：本地技能调用 =====
                    "market/invokeLocalSkill" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val m = args["method"]?.jsonPrimitive?.content ?: ""
                        val aJson = args["argsJson"]?.jsonPrimitive?.content ?: "{}"
                        buildResult(facade.invokeLocalSkill(itemId, m, aJson)) { JsonPrimitive(it) }
                    }
                    "market/listLocalSkillMethods" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.listLocalSkillMethods(itemId)) { list ->
                            buildJsonObject { put("methods", list.joinToString(",")) }
                        }
                    }
                    "market/getInstalledItemMetadata" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getInstalledItemMetadata(itemId)) { meta ->
                            if (meta == null) buildJsonObject { put("found", false) }
                            else buildJsonObject { put("found", true); put("metadata", meta) }
                        }
                    }

                    // ===== Apex 独有增强：搜索增强 =====
                    "market/searchInMarket" -> {
                        val marketId = args["marketId"]?.jsonPrimitive?.content ?: ""
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val query = args["query"]?.jsonPrimitive?.content ?: ""
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
                        buildResult(facade.searchInMarket(marketId, cat, query, limit)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.id}: ${it.name} v${it.version}" })
                            }
                        }
                    }

                    // ===== Apex 独有增强：收藏夹 =====
                    "market/addFavorite" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val name = args["name"]?.jsonPrimitive?.content ?: ""
                        val desc = args["description"]?.jsonPrimitive?.content ?: ""
                        val marketId = args["marketId"]?.jsonPrimitive?.content ?: ""
                        val version = args["version"]?.jsonPrimitive?.content ?: ""
                        val note = args["note"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.addFavorite(itemId, cat, name, desc, marketId, version, note)) { JsonPrimitive(it) }
                    }
                    "market/removeFavorite" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.removeFavorite(itemId)) { JsonPrimitive(it) }
                    }
                    "market/toggleFavorite" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val name = args["name"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.toggleFavorite(itemId, cat, name)) { JsonPrimitive(it) }
                    }
                    "market/isFavorite" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        buildJsonObject { put("success", true); put("isFavorite", facade.isFavorite(itemId)) }.toString()
                    }
                    "market/listFavorites" -> {
                        val cat = args["category"]?.jsonPrimitive?.content
                        buildResult(facade.listFavorites(cat)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("favorites", list.joinToString("\n") { "${it.itemId}|${it.name}|${it.category}|${it.note.take(30)}" })
                            }
                        }
                    }
                    "market/searchFavorites" -> {
                        val q = args["query"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.searchFavorites(q)) { list ->
                            buildJsonObject { put("count", list.size) }
                        }
                    }
                    "market/updateFavoriteNote" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val note = args["note"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.updateFavoriteNote(itemId, note)) { JsonPrimitive(it) }
                    }
                    "market/clearFavorites" -> {
                        buildResult(facade.clearFavorites()) { JsonPrimitive(it) }
                    }
                    "market/favoritesCount" -> {
                        buildJsonObject { put("success", true); put("count", facade.favoritesCount()) }.toString()
                    }

                    // ===== Apex 独有增强：使用统计 =====
                    "market/getItemStats" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getItemStats(itemId)) { s ->
                            if (s == null) buildJsonObject { put("found", false) }
                            else buildJsonObject {
                                put("found", true)
                                put("searchCount", s.searchCount)
                                put("viewCount", s.viewCount)
                                put("installCount", s.installCount)
                                put("invokeCount", s.invokeCount)
                                put("isInstalled", s.isInstalled)
                                put("favorited", s.favorited)
                            }
                        }
                    }
                    "market/getRecentlyUsed" -> {
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                        buildResult(facade.getRecentlyUsed(limit)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.itemId}|${it.name}|invokes=${it.invokeCount}" })
                            }
                        }
                    }
                    "market/getMostUsed" -> {
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                        buildResult(facade.getMostUsed(limit)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.itemId}|${it.name}|score=${it.invokeCount + it.viewCount * 2 + it.searchCount}" })
                            }
                        }
                    }
                    "market/getTotalUsageStats" -> {
                        buildResult(facade.getTotalUsageStats()) { s ->
                            buildJsonObject {
                                put("totalItems", s.totalItems)
                                put("totalSearches", s.totalSearches)
                                put("totalViews", s.totalViews)
                                put("totalInstalls", s.totalInstalls)
                                put("totalInvokes", s.totalInvokes)
                                put("currentlyInstalled", s.currentlyInstalled)
                                put("currentlyFavorited", s.currentlyFavorited)
                            }
                        }
                    }
                    "market/getUsageByCategory" -> {
                        buildResult(facade.getUsageByCategory()) { map ->
                            buildJsonObject {
                                map.forEach { (k, v) -> put(k, v) }
                            }
                        }
                    }
                    "market/getRecentEvents" -> {
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100
                        buildResult(facade.getRecentEvents(limit)) { list ->
                            buildJsonObject { put("count", list.size) }
                        }
                    }
                    "market/recordView" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val name = args["name"]?.jsonPrimitive?.content ?: ""
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        buildResult(facade.recordView(itemId, name, cat)) { JsonObject(emptyMap()) }
                    }
                    "market/clearUsageStats" -> {
                        buildResult(facade.clearUsageStats()) { JsonObject(emptyMap()) }
                    }

                    // ===== Apex 独有增强：缓存管理 =====
                    "market/clearCacheForMarket" -> {
                        val marketId = args["marketId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.clearCacheForMarket(marketId)) { JsonPrimitive(it) }
                    }
                    "market/clearAllCache" -> {
                        buildResult(facade.clearAllCache()) { JsonPrimitive(it) }
                    }
                    "market/cleanExpiredCache" -> {
                        buildResult(facade.cleanExpiredCache()) { JsonPrimitive(it) }
                    }
                    "market/getCacheStats" -> {
                        val s = facade.getCacheStats()
                        buildJsonObject {
                            put("success", true)
                            put("entryCount", s.entryCount)
                            put("totalSizeMb", s.totalSizeMb)
                        }.toString()
                    }

                    // ===== Apex 独有增强：批量操作 + 更新检查 =====
                    "market/batchInstall" -> {
                        // items 格式："itemId1,cat1,path1;itemId2,cat2,;..."
                        val itemsStr = args["items"]?.jsonPrimitive?.content ?: ""
                        val items = itemsStr.split(";").filter { it.isNotBlank() }.map { entry ->
                            val parts = entry.split(",")
                            Triple(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "SKILLS", parts.getOrNull(2))
                        }
                        buildResult(facade.batchInstall(items)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("successCount", list.count { it.success })
                                put("failureCount", list.count { !it.success })
                            }
                        }
                    }
                    "market/batchUninstall" -> {
                        val ids = args["itemIds"]?.jsonPrimitive?.content?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                        buildResult(facade.batchUninstall(ids)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("successCount", list.count { it.second })
                            }
                        }
                    }
                    "market/checkForUpdates" -> {
                        buildResult(facade.checkForUpdates()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.id}: ${it.installedVersion} → ${it.latestVersion ?: "?"}" })
                            }
                        }
                    }
                    "market/updateAll" -> {
                        buildResult(facade.updateAll()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("successCount", list.count { it.success })
                            }
                        }
                    }
                    "market/refreshAllMarkets" -> {
                        buildResult(facade.refreshAllMarkets()) { JsonObject(emptyMap()) }
                    }
                    "market/refreshMarket" -> {
                        val marketId = args["marketId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.refreshMarket(marketId)) { JsonPrimitive(it) }
                    }
                    "market/diagnose" -> {
                        buildResult(facade.diagnose()) { JsonPrimitive(it) }
                    }

                    // ===== lib:market 引擎：目录元数据 + 安装任务状态（走 lib 引擎而非旧 IntegrationCenter 路径） =====
                    "market/listCatalog" -> {
                        buildResult(facade.getEngine().listCatalog(null)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("catalog", list.joinToString("\n") {
                                    "${it.marketId}|${it.displayName}|${it.category.name}|${it.description.take(50)}|enabled=${it.builtin}"
                                })
                            }
                        }
                    }
                    "market/listCatalogByCategory" -> {
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val mc = MarketCategory.fromIntegrationName(cat)
                        buildResult(facade.getEngine().listCatalog(mc)) { list ->
                            buildJsonObject {
                                put("category", mc.name)
                                put("count", list.size)
                                put("catalog", list.joinToString("\n") {
                                    "${it.marketId}|${it.displayName}|${it.description.take(50)}"
                                })
                            }
                        }
                    }
                    "market/getCatalogEntry" -> {
                        val marketId = args["marketId"]?.jsonPrimitive?.content ?: ""
                        val entry = MarketCatalog.byId(marketId)
                        if (entry == null) {
                            buildJsonObject {
                                put("success", false); put("found", false)
                                put("errorMessage", "market not found: $marketId")
                            }.toString()
                        } else {
                            buildJsonObject {
                                put("success", true); put("found", true)
                                put("marketId", entry.marketId)
                                put("displayName", entry.displayName)
                                put("category", entry.category.name)
                                put("description", entry.description)
                                put("sourceUrl", entry.sourceUrl)
                                put("requiresNetwork", entry.requiresNetwork)
                                put("builtin", entry.builtin)
                                put("enabled", entry.builtin)
                                entry.iconUrl?.let { put("iconUrl", it) }
                            }.toString()
                        }
                    }
                    "market/catalogStats" -> {
                        buildResult(facade.getEngine().catalogStats()) { stats ->
                            buildJsonObject {
                                put("totalCount", stats.values.sum())
                                stats.forEach { (cat, n) -> put(cat.name, n) }
                            }
                        }
                    }
                    "market/getInstallTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        val task = facade.getEngine().getInstallTask(taskId)
                        if (task == null) {
                            buildJsonObject { put("success", false); put("found", false) }.toString()
                        } else {
                            buildJsonObject {
                                put("success", true); put("found", true)
                                put("taskId", task.taskId)
                                put("itemId", task.itemId)
                                put("itemName", task.itemName)
                                put("status", task.status.name)
                                put("progress", task.progress)
                                put("stage", task.stage)
                                put("category", task.category.name)
                                put("startedAt", task.startedAt)
                                task.completedAt?.let { put("completedAt", it) }
                                task.installedPath?.let { put("installedPath", it) }
                                task.error?.let { put("error", it) }
                                task.message?.let { put("message", it) }
                            }.toString()
                        }
                    }
                    "market/listInstallTasks" -> {
                        val statusFilter = args["status"]?.jsonPrimitive?.content
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100
                        val all = facade.getEngine().listInstallTasks(limit)
                        val filtered = if (statusFilter.isNullOrBlank()) all
                            else all.filter { it.status.name == statusFilter.uppercase() }
                        buildJsonObject {
                            put("success", true)
                            put("count", filtered.size)
                            put("tasks", filtered.joinToString("\n") {
                                "${it.taskId}|${it.itemId}|${it.status.name}|${it.progress}%|${it.stage}"
                            })
                        }.toString()
                    }
                    "market/cancelInstallTask" -> {
                        val taskId = args["taskId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.getEngine().cancelInstall(taskId)) { JsonPrimitive(it) }
                    }
                    "market/searchViaEngine" -> {
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val query = args["query"]?.jsonPrimitive?.content ?: ""
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
                        val marketId = args["marketId"]?.jsonPrimitive?.content
                        buildResult(facade.searchViaEngine(cat, query, marketId, limit)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") {
                                    "${it.id}: ${it.name} v${it.version} [${it.category}]"
                                })
                            }
                        }
                    }
                    "market/enqueueInstallViaEngine" -> {
                        val itemId = args["itemId"]?.jsonPrimitive?.content ?: ""
                        val cat = args["category"]?.jsonPrimitive?.content ?: "SKILLS"
                        val name = args["name"]?.jsonPrimitive?.content ?: itemId
                        val version = args["version"]?.jsonPrimitive?.content ?: ""
                        val marketId = args["marketId"]?.jsonPrimitive?.content ?: ""
                        val targetPath = args["targetPath"]?.jsonPrimitive?.content
                        buildResult(facade.enqueueInstall(itemId, cat, name, version, marketId, targetPath)) { taskId ->
                            buildJsonObject { put("taskId", taskId) }
                        }
                    }
                    "market/getHotItems" -> {
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                        buildResult(facade.getEngine().getHotItems(limit)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") {
                                    "${it.itemId}|${it.name}|invokes=${it.invokeCount}|views=${it.viewCount}|searches=${it.searchCount}"
                                })
                            }
                        }
                    }
                    "market/getEngineEvents" -> {
                        val since = args["since"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
                        val collectMs = args["collectMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 500L
                        val flow = facade.getEngine().events
                        val collected: List<MarketEvent> = withTimeoutOrNull(collectMs) {
                            flow.filter { eventTimestamp(it) >= since }.take(limit).toList()
                        } ?: emptyList()
                        buildJsonObject {
                            put("success", true)
                            put("count", collected.size)
                            put("collectWindowMs", collectMs)
                            put("events", collected.joinToString("\n") { describeMarketEvent(it) })
                        }.toString()
                    }

                    else -> errorResponse("unknown method: $method")
                }
            }
        }.getOrElse { t -> errorResponse(t.message ?: t.javaClass.simpleName) }
    }

    override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
        onProgress(50, "executing")
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String = channelName
    override fun closeStream(channelName: String) {}

    private fun <T> buildResult(result: BridgeResult<T>, transform: (T) -> JsonObject): String = when (result) {
        is BridgeResult.Success -> buildJsonObject {
            put("success", true)
            put("data", transform(result.value))
        }.toString()
        is BridgeResult.Failure -> buildJsonObject {
            put("success", false)
            put("errorCode", result.error.code)
            put("errorMessage", result.error.message)
        }.toString()
    }

    private fun errorResponse(message: String): String = buildJsonObject {
        put("success", false)
        put("errorMessage", message)
    }.toString()

    // ===== lib:market MarketEvent 辅助（getEngineEvents 路由用） =====

    private fun eventTimestamp(e: MarketEvent): Long = when (e) {
        is MarketEvent.SearchCompleted -> e.timestamp
        is MarketEvent.FavoriteAdded -> e.timestamp
        is MarketEvent.FavoriteRemoved -> e.timestamp
        is MarketEvent.InstallQueued -> e.timestamp
        is MarketEvent.InstallProgress -> e.timestamp
        is MarketEvent.InstallCompleted -> e.timestamp
        is MarketEvent.InstallFailed -> e.timestamp
        is MarketEvent.Uninstalled -> e.timestamp
        is MarketEvent.CacheInvalidated -> e.timestamp
        is MarketEvent.UsageRecorded -> e.timestamp
    }

    private fun describeMarketEvent(e: MarketEvent): String = when (e) {
        is MarketEvent.SearchCompleted ->
            "SearchCompleted[${e.category}] q=\"${e.query.take(30)}\" n=${e.resultCount} cache=${e.fromCache} @${e.timestamp}"
        is MarketEvent.FavoriteAdded -> "FavoriteAdded[${e.itemId}] @${e.timestamp}"
        is MarketEvent.FavoriteRemoved -> "FavoriteRemoved[${e.itemId}] @${e.timestamp}"
        is MarketEvent.InstallQueued -> "InstallQueued[${e.taskId}] item=${e.itemId} @${e.timestamp}"
        is MarketEvent.InstallProgress ->
            "InstallProgress[${e.taskId}] item=${e.itemId} ${e.progress}% ${e.stage} @${e.timestamp}"
        is MarketEvent.InstallCompleted ->
            "InstallCompleted[${e.taskId}] item=${e.itemId} -> ${e.installedPath} @${e.timestamp}"
        is MarketEvent.InstallFailed ->
            "InstallFailed[${e.taskId}] item=${e.itemId} err=${e.error} @${e.timestamp}"
        is MarketEvent.Uninstalled -> "Uninstalled[${e.itemId}] @${e.timestamp}"
        is MarketEvent.CacheInvalidated ->
            "CacheInvalidated market=${e.marketId ?: "ALL"} n=${e.entryCount} @${e.timestamp}"
        is MarketEvent.UsageRecorded -> "UsageRecorded[${e.itemId}] type=${e.eventType} @${e.timestamp}"
    }
}
