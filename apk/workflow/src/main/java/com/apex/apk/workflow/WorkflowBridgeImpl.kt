package com.apex.apk.workflow

import com.apex.lib.workflow.WorkflowDefinition
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

class WorkflowBridgeImpl(
    private val facade: WorkflowServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.WORKFLOW, "[WorkflowBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "workflow/register" -> {
                        val workflowJson = args["workflowJson"]?.jsonPrimitive?.content ?: ""
                        val workflow = facade.parseWorkflow(workflowJson)
                            ?: throw IllegalArgumentException("invalid workflow JSON")
                        buildResult(facade.registerWorkflow(workflow)) { JsonObject(emptyMap()) }
                    }
                    "workflow/execute" -> {
                        val workflowId = args["workflowId"]?.jsonPrimitive?.content ?: ""
                        val inputsStr = args["inputs"]?.jsonPrimitive?.content ?: "{}"
                        val inputs = parseInputs(inputsStr)
                        buildResult(facade.execute(workflowId, inputs)) { ctx ->
                            buildJsonObject {
                                put("workflowId", ctx.workflowId)
                                put("traceId", ctx.traceId)
                                put("currentNodeId", ctx.currentNodeId)
                                put("historySize", ctx.history.size)
                                put("variablesSize", ctx.variables.size)
                            }
                        }
                    }
                    "workflow/list" -> {
                        buildResult(facade.listWorkflows()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("workflows", list.joinToString("\n") { "${it.id}: ${it.displayName} (${it.nodeCount} nodes)" })
                            }
                        }
                    }
                    "workflow/unregister" -> {
                        val id = args["workflowId"]?.jsonPrimitive?.content ?: ""
                        val ok = facade.unregisterWorkflow(id)
                        buildJsonObject { put("success", ok) }.toString()
                    }
                    "workflow/history" -> {
                        val list = facade.getHistory()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("items", list.joinToString("\n") {
                                "${it.workflowId} (trace=${it.traceId}, success=${it.success}, duration=${it.durationMs}ms)"
                            })
                        }.toString()
                    }
                    "workflow/serialize" -> {
                        val id = args["workflowId"]?.jsonPrimitive?.content ?: ""
                        // 找不到直接返回空
                        buildJsonObject {
                            put("success", true)
                            put("json", "")
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

    private fun parseInputs(inputsStr: String): Map<String, Any> {
        return try {
            val obj = json.parseToJsonElement(inputsStr) as? JsonObject ?: return emptyMap()
            obj.entries.associate { (k, v) ->
                k to ((v as? JsonPrimitive)?.content ?: v.toString())
            }
        } catch (_: Throwable) { emptyMap() }
    }

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
