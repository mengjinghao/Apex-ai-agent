package com.apex.apk.diagnostics

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

class DiagnosticsBridgeImpl(
    private val facade: DiagnosticsServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.DIAGNOSTICS, "[DiagnosticsBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "diagnostics/startLogCapture" -> {
                        buildResult(facade.startLogCapture()) { JsonObject(emptyMap()) }
                    }
                    "diagnostics/getRecentLogs" -> {
                        val max = args["maxLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 500
                        buildResult(facade.getRecentLogs(max)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("logs", list.joinToString("\n") { "${it.timestamp} [${it.tag}] ${it.message}" })
                            }
                        }
                    }
                    "diagnostics/listLogFiles" -> {
                        buildResult(facade.listLogFiles()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("files", list.joinToString("\n") { "${it.name} (${it.sizeBytes}B)" })
                            }
                        }
                    }
                    "diagnostics/readLogFile" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        val max = args["maxLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1000
                        buildResult(facade.readLogFile(name, max)) { JsonPrimitive(it) }
                    }
                    "diagnostics/deleteLogFile" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteLogFile(name)) { JsonPrimitive(it) }
                    }
                    "diagnostics/clearAllLogs" -> {
                        buildResult(facade.clearAllLogs()) { JsonPrimitive(it) }
                    }
                    "diagnostics/listCrashReports" -> {
                        buildResult(facade.listCrashReports()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("files", list.joinToString("\n") { "${it.name} (${it.sizeBytes}B)" })
                            }
                        }
                    }
                    "diagnostics/readCrashReport" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.readCrashReport(name)) { JsonPrimitive(it) }
                    }
                    "diagnostics/deleteCrashReport" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.deleteCrashReport(name)) { JsonPrimitive(it) }
                    }
                    "diagnostics/getMemoryStats" -> {
                        val m = facade.getMemoryStats()
                        buildJsonObject {
                            put("success", true)
                            put("usedMb", m.usedMb)
                            put("totalMb", m.totalMb)
                            put("maxMb", m.maxMb)
                        }.toString()
                    }
                    "diagnostics/getNativeMemory" -> {
                        val m = facade.getNativeMemory()
                        buildJsonObject {
                            put("success", true)
                            put("totalMb", m.totalMb)
                            put("freeMb", m.freeMb)
                            put("allocatedMb", m.allocatedMb)
                        }.toString()
                    }
                    "diagnostics/getApkHealthList" -> {
                        val list = facade.getApkHealthList()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("items", list.joinToString("\n") { "${it.apkId}: healthy=${it.healthy} (${it.lastHeartbeatAgoMs}ms ago)" })
                        }.toString()
                    }
                    "diagnostics/getSystemInfo" -> {
                        val info = facade.getSystemInfo()
                        buildJsonObject {
                            put("success", true)
                            put("brand", info.brand)
                            put("model", info.model)
                            put("manufacturer", info.manufacturer)
                            put("sdkInt", info.sdkInt)
                            put("release", info.release)
                            put("pid", info.pid)
                            put("uid", info.uid)
                            put("processName", info.processName)
                            put("abis", info.abis.joinToString(","))
                        }.toString()
                    }
                    "diagnostics/forceGc" -> {
                        facade.forceGc()
                        buildJsonObject { put("success", true) }.toString()
                    }
                    "diagnostics/dumpHeap" -> {
                        val path = facade.dumpHeap(args["fileName"]?.jsonPrimitive?.content ?: "apex-heap.hprof")
                        buildJsonObject { put("success", true); put("path", path) }.toString()
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
