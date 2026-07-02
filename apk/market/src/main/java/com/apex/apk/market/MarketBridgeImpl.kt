package com.apex.apk.market

import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import kotlinx.coroutines.runBlocking
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
}
