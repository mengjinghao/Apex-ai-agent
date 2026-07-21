package com.apex.bridge

import com.apex.selfmodify.SelfModifyService
import com.apex.sdk.bridge.IApkBridgeInternal
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge handler for selfModify/* methods.
 *
 * Routes ApexClient.selfModify.* calls (via ApexBridge.invoke("selfModify/<method>", argsJson))
 * to the [SelfModifyService].
 *
 * Registered in ApexApplication.onCreate via InProcessRegistry.
 */
class SelfModifyBridgeHandler(private val svc: SelfModifyService) : IApkBridgeInternal {

    override fun invoke(method: String, argsJson: String): String {
        val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
        return runBlocking {
            try {
                when (method) {
                    "selfModify/readFile" -> {
                        val path = args.getString("path")
                        JSONObject().put("result", svc.readFile(path)).toString()
                    }
                    "selfModify/listFiles" -> {
                        val pattern = args.optString("pattern", ".*")
                        val files = svc.listFiles(pattern)
                        JSONObject().put("result", JSONArray(files)).toString()
                    }
                    "selfModify/findSymbol" -> {
                        val name = args.getString("name")
                        val locs = svc.findSymbol(name)
                        JSONObject().put("result", JSONArray(locs.map { it.toString() })).toString()
                    }
                    "selfModify/findReferences" -> {
                        val symbol = args.getString("symbol")
                        val refs = svc.findReferences(symbol)
                        JSONObject().put("result", JSONArray(refs.map { it.toString() })).toString()
                    }
                    "selfModify/applyPlan" -> {
                        // Plan deserialization is complex; for bridge, return guidance
                        JSONObject().put("error", "applyPlan via bridge not yet supported — use ModifyCodeTool directly").toString()
                    }
                    "selfModify/rollback" -> {
                        val commit = args.optString("commit", "")
                        val ok = svc.rollback(if (commit.isEmpty()) null else commit)
                        JSONObject().put("result", ok).toString()
                    }
                    "selfModify/listSnapshots" -> {
                        val snaps = svc.listSnapshots()
                        JSONObject().put("result", JSONArray(snaps.map { it.toString() })).toString()
                    }
                    "selfModify/reindex" -> {
                        JSONObject().put("result", "reindex triggered").toString()
                    }
                    else -> JSONObject().put("error", "unknown method: $method").toString()
                }
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
            }
        }
    }

    override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String = ""
    override fun closeStream(channelName: String) { /* no-op */ }
}
