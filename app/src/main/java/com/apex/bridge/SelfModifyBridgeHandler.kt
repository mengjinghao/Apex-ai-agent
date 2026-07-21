package com.apex.bridge

import com.apex.selfmodify.SelfModifyService
import com.apex.selfmodify.plan.ApplyResult
import com.apex.selfmodify.plan.ModificationPlan
import com.apex.sdk.bridge.IApkBridgeInternal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * Bridge handler that routes `selfmodify/*` methods to [SelfModifyService].
 *
 * Registered in [com.apex.di.SelfModifyModule] via
 * [com.apex.sdk.bridge.InProcessRegistry] so that
 * `ApexClient.selfModify.readFile(...)` (which calls
 * `ApexBridge.invoke("selfmodify/readFile", ...)`) resolves to this handler
 * with zero-latency in-process dispatch — no Binder, no Parcel.
 *
 * Per AGENT_SELF_MODIFY_SPEC §8.2 (Phase 5a — bridge routing).
 *
 * **Method routing table**:
 *
 * | method                       | service call                       | returns (JSON)                    |
 * |------------------------------|------------------------------------|-----------------------------------|
 * | `selfmodify/readFile`        | `svc.readFile(path)`               | `{"result":"<src>"}`              |
 * | `selfmodify/listFiles`       | `svc.listFiles(pattern)`           | `{"result":[...]}`                |
 * | `selfmodify/findSymbol`      | `svc.findSymbol(name)`             | `{"result":[{file,line,column}]}`|
 * | `selfmodify/findReferences`  | `svc.findReferences(symbol)`       | `{"result":[{file,line,symbol}]}`|
 * | `selfmodify/applyPlan`       | `svc.apply(ModificationPlan)`      | `{"result":"success\|rolledBack\|rejected",...}` |
 * | `selfmodify/rollback`        | `svc.rollback(commit?)`            | `{"result":bool}`                 |
 * | `selfmodify/listSnapshots`   | `svc.listSnapshots()`              | `{"result":[{sha,tag,timestamp,message}]}` |
 * | `selfmodify/reindex`         | `svc.reindex()`                    | `{"result":"reindexed","symbolCount":N}` |
 *
 * **Service name note**: the client ([com.apex.sdk.bridge.ApexClient.SelfModifyClient])
 * uses the all-lowercase `selfmodify/` prefix (matching `ApexBridge.invoke`'s
 * `method.substringBefore('/')` convention). This handler is registered under
 * the key `"selfmodify"` (not `"selfModify"`) so [InProcessRegistry] lookup hits.
 *
 * **Sync→suspend bridge**: [invoke] is synchronous (per [IApkBridgeInternal]),
 * but [SelfModifyService] methods are `suspend`. We bridge with [runBlocking];
 * the service internally offloads heavy work (git, file IO) to `Dispatchers.IO`
 * via its own `withContext`, so no Main-thread deadlock occurs.
 *
 * **JSON encoding**: lists of data classes ([SymbolLocation], [SnapshotInfo], etc.)
 * are encoded via [Json.encodeToJsonElement] — the data classes are `@Serializable`.
 * Scalar results (file contents, booleans) are wrapped directly.
 */
class SelfModifyBridgeHandler(private val svc: SelfModifyService) : IApkBridgeInternal {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun invoke(method: String, argsJson: String): String {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            JSONObject()
        }
        return runBlocking {
            when (method) {
                "selfmodify/readFile" -> {
                    val path = args.optString("path")
                    if (path.isEmpty()) {
                        errJson("Missing 'path'")
                    } else {
                        try {
                            okStr(svc.readFile(path))
                        } catch (e: Exception) {
                            errJson("readFile failed: ${e.message}")
                        }
                    }
                }

                "selfmodify/listFiles" -> {
                    val pattern = args.optString("pattern", ".*")
                    try {
                        val files = svc.listFiles(pattern)
                        buildJsonObject {
                            put("result", json.encodeToJsonElement(files))
                        }.toString()
                    } catch (e: Exception) {
                        errJson("listFiles failed: ${e.message}")
                    }
                }

                "selfmodify/findSymbol" -> {
                    val name = args.optString("name")
                    if (name.isEmpty()) {
                        errJson("Missing 'name'")
                    } else {
                        try {
                            val locs = svc.findSymbol(name)
                            buildJsonObject {
                                put("result", json.encodeToJsonElement(locs))
                            }.toString()
                        } catch (e: Exception) {
                            errJson("findSymbol failed: ${e.message}")
                        }
                    }
                }

                "selfmodify/findReferences" -> {
                    val symbol = args.optString("symbol")
                    if (symbol.isEmpty()) {
                        errJson("Missing 'symbol'")
                    } else {
                        try {
                            val refs = svc.findReferences(symbol)
                            buildJsonObject {
                                put("result", json.encodeToJsonElement(refs))
                            }.toString()
                        } catch (e: Exception) {
                            errJson("findReferences failed: ${e.message}")
                        }
                    }
                }

                "selfmodify/applyPlan" -> {
                    val planJson = args.optString("planJson")
                    if (planJson.isEmpty()) {
                        errJson("Missing 'planJson'")
                    } else {
                        try {
                            val plan = json.decodeFromString(ModificationPlan.serializer(), planJson)
                            when (val r = svc.apply(plan)) {
                                is ApplyResult.Success -> buildJsonObject {
                                    put("result", "success")
                                    put("planId", r.plan.id)
                                    put("compileMs", r.compileMs)
                                }.toString()
                                is ApplyResult.RolledBack -> buildJsonObject {
                                    put("result", "rolledBack")
                                    put("planId", r.plan.id)
                                    put("reason", r.reason)
                                }.toString()
                                is ApplyResult.Rejected -> buildJsonObject {
                                    put("result", "rejected")
                                    put("reason", r.reason)
                                }.toString()
                            }
                        } catch (e: Exception) {
                            errJson("applyPlan failed: ${e.message}")
                        }
                    }
                }

                "selfmodify/rollback" -> {
                    val commit = args.optString("commit", "")
                    try {
                        val ok = svc.rollback(if (commit.isEmpty()) null else commit)
                        buildJsonObject { put("result", ok) }.toString()
                    } catch (e: Exception) {
                        errJson("rollback failed: ${e.message}")
                    }
                }

                "selfmodify/listSnapshots" -> {
                    try {
                        val snaps = svc.listSnapshots()
                        buildJsonObject {
                            put("result", json.encodeToJsonElement(snaps))
                        }.toString()
                    } catch (e: Exception) {
                        errJson("listSnapshots failed: ${e.message}")
                    }
                }

                "selfmodify/reindex" -> {
                    try {
                        val count = svc.reindex()
                        buildJsonObject {
                            put("result", "reindexed")
                            put("symbolCount", count)
                        }.toString()
                    } catch (e: Exception) {
                        errJson("reindex failed: ${e.message}")
                    }
                }

                else -> errJson("unknown method: $method")
            }
        }
    }

    override fun invokeAsync(
        method: String,
        argsJson: String,
        onProgress: (Int, String) -> Unit
    ): String {
        // Synchronous fallback — all selfmodify methods are short enough for
        // direct invoke(). Long-running ops (applyPlan with compile gate) report
        // progress via the audit log, not the progress callback.
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String {
        // Streaming not supported for selfmodify — file events go through FileWatcher directly.
        return ""
    }

    override fun closeStream(channelName: String) {
        // no-op
    }

    private fun okStr(value: String): String =
        buildJsonObject { put("result", value) }.toString()

    private fun errJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()
}
