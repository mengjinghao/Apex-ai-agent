package com.apex.agent.application

import com.apex.agent.diagnostics.DiagnosticsServiceFacade
import com.apex.sdk.bridge.ApkBridgeStubAdapter
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 主 APK 的 Bridge 实现 — 路由 diagnostics/* 等主 APK 自身暴露的方法。
 *
 * 诊断功能原本是独立 APK（:apk:diagnostics），现已合并到主 APK。
 * 其他 APK 通过 `ApexClient.diagnostics.*` 调用时，路由到主 APK 的 [DiagnosticsServiceFacade]。
 *
 * 由于所有 APK 共享 `com.apex.agent.mainprocess` 进程，调用方通常通过
 * [TypedServiceRegistry] 直接拿到 [DiagnosticsServiceFacade] 实例（零延迟），
 * 本 Bridge 仅作为跨进程降级路径。
 */
class MainApkBridgeImpl : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    private fun facade(): DiagnosticsServiceFacade? =
        TypedServiceRegistry.get<DiagnosticsServiceFacade>()

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.MAIN, "[MainBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        val f = facade() ?: return buildJsonObject {
            put("success", false)
            put("errorMessage", "DiagnosticsServiceFacade not initialized")
        }.toString()

        return runCatching {
            runBlocking {
                when (method) {
                    "diagnostics/startLogCapture" -> buildOk { f.startLogCapture() }
                    "diagnostics/getRecentLogs" -> {
                        val max = args["maxLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 500
                        val r = f.getRecentLogs(max)
                        buildResult(r) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("logs", list.joinToString("\n") { "${it.timestamp} [${it.tag}] ${it.message}" })
                            }
                        }
                    }
                    "diagnostics/listLogFiles" -> {
                        val r = f.listLogFiles()
                        buildResult(r) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("files", list.joinToString("\n") { "${it.name} (${it.sizeBytes}B)" })
                            }
                        }
                    }
                    "diagnostics/readLogFile" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        val max = args["maxLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1000
                        buildResult(f.readLogFile(name, max)) { JsonPrimitive(it) }
                    }
                    "diagnostics/deleteLogFile" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        buildResult(f.deleteLogFile(name)) { JsonPrimitive(it) }
                    }
                    "diagnostics/clearAllLogs" -> buildResult(f.clearAllLogs()) { JsonPrimitive(it) }
                    "diagnostics/listCrashReports" -> {
                        buildResult(f.listCrashReports()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("files", list.joinToString("\n") { "${it.name} (${it.sizeBytes}B)" })
                            }
                        }
                    }
                    "diagnostics/readCrashReport" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        buildResult(f.readCrashReport(name)) { JsonPrimitive(it) }
                    }
                    "diagnostics/deleteCrashReport" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: ""
                        buildResult(f.deleteCrashReport(name)) { JsonPrimitive(it) }
                    }
                    "diagnostics/getMemoryStats" -> {
                        val m = f.getMemoryStats()
                        buildJsonObject {
                            put("success", true)
                            put("usedMb", m.usedMb)
                            put("totalMb", m.totalMb)
                            put("maxMb", m.maxMb)
                        }.toString()
                    }
                    "diagnostics/getNativeMemory" -> {
                        val m = f.getNativeMemory()
                        buildJsonObject {
                            put("success", true)
                            put("totalMb", m.totalMb)
                            put("freeMb", m.freeMb)
                            put("allocatedMb", m.allocatedMb)
                        }.toString()
                    }
                    "diagnostics/getApkHealthList" -> {
                        val list = f.getApkHealthList()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("items", list.joinToString("\n") {
                                "${it.apkId}: healthy=${it.healthy} (${it.lastHeartbeatAgoMs}ms ago)"
                            })
                        }.toString()
                    }
                    "diagnostics/getSystemInfo" -> {
                        val info = f.getSystemInfo()
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
                        f.forceGc()
                        buildJsonObject { put("success", true) }.toString()
                    }
                    "diagnostics/dumpHeap" -> {
                        val name = args["fileName"]?.jsonPrimitive?.content ?: "apex-heap.hprof"
                        val path = f.dumpHeap(name)
                        buildJsonObject { put("success", true); put("path", path) }.toString()
                    }
                    else -> buildJsonObject {
                        put("success", false)
                        put("errorMessage", "unknown method: $method")
                    }.toString()
                }
            }
        }.getOrElse { t ->
            buildJsonObject {
                put("success", false)
                put("errorMessage", t.message ?: t.javaClass.simpleName)
            }.toString()
        }
    }

    override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
        onProgress(50, "executing")
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String = channelName
    override fun closeStream(channelName: String) {}

    private inline fun buildOk(block: () -> Unit): String = try {
        block()
        buildJsonObject { put("success", true) }.toString()
    } catch (t: Throwable) {
        buildJsonObject { put("success", false); put("errorMessage", t.message ?: "") }.toString()
    }

    private fun <T> buildResult(result: com.apex.sdk.common.BridgeResult<T>, transform: (T) -> JsonObject): String =
        when (result) {
            is com.apex.sdk.common.BridgeResult.Success -> buildJsonObject {
                put("success", true)
                put("data", transform(result.value))
            }.toString()
            is com.apex.sdk.common.BridgeResult.Failure -> buildJsonObject {
                put("success", false)
                put("errorCode", result.error.code)
                put("errorMessage", result.error.message)
            }.toString()
        }
}
