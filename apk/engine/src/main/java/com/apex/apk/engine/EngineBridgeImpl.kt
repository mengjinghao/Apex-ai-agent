package com.apex.apk.engine

import android.content.Intent
import android.os.IBinder
import com.apex.sdk.bridge.ApkBridgeStubAdapter
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Engine APK 对外暴露的服务实现。
 *
 * 通过 [TypedServiceRegistry] 注册 [EngineServiceFacade]，
 * 其他 APK 同进程时直接拿到本实例，零延迟调用。
 *
 * 同时实现 [IApkBridgeInternal]，提供 AIDL 降级路径（跨进程时使用）。
 */
class EngineBridgeImpl(
    private val facade: EngineServiceFacade
) : IApkBridgeInternal {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.ENGINE, "[EngineBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) {
            JsonObject(emptyMap())
        }
        val result = runCatching {
            runBlocking {
                when (method) {
                    "engine/executeShell" -> {
                        val cmd = args["cmd"]?.jsonPrimitive?.content ?: ""
                        val r = facade.executeShell(cmd)
                        buildResult(r) { it.toJson() }
                    }
                    "engine/executeShellViaShizuku" -> {
                        val cmd = args["cmd"]?.jsonPrimitive?.content ?: ""
                        val r = facade.executeShellViaShizuku(cmd)
                        buildResult(r) {
                            buildJsonObject {
                                put("exitCode", it.exitCode)
                                put("output", it.output)
                                put("error", it.error)
                                put("success", it.isSuccess)
                            }
                        }
                    }
                    "engine/executeTool" -> {
                        val tool = args["tool"]?.jsonPrimitive?.content ?: ""
                        val toolArgs = args["args"]?.jsonPrimitive?.content ?: ""
                        val r = facade.executeTool(tool, toolArgs)
                        buildResult(r) { it.toJson() }
                    }
                    "engine/listTools" -> {
                        val r = facade.listTools()
                        buildResult(r) { list ->
                            buildJsonObject {
                                put("tools", JsonPrimitive(list.size))
                                put("items", list.joinToString("\n") { "${it.name} (${it.category}): ${it.description}" })
                            }
                        }
                    }
                    "engine/startContainer" -> buildResult(facade.startContainer()) { JsonPrimitive(it) }
                    "engine/stopContainer" -> buildResult(facade.stopContainer()) { JsonPrimitive(it) }
                    "engine/restartContainer" -> buildResult(facade.restartContainer()) { JsonPrimitive(it) }
                    "engine/getContainerStatus" -> {
                        val r = facade.getContainerStatus()
                        buildResult(r) { it?.toJson() ?: JsonObject(emptyMap()) }
                    }
                    "engine/getContainerOutput" -> {
                        val r = facade.getContainerOutput()
                        buildResult(r) { JsonPrimitive(it) }
                    }
                    "engine/isAccessibilityEnabled" -> JsonPrimitive(facade.isAccessibilityEnabled()).toString()
                    "engine/performClick" -> {
                        val x = args["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val y = args["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        JsonPrimitive(facade.performClick(x, y)).toString()
                    }
                    "engine/performSwipe" -> {
                        val sx = args["startX"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val sy = args["startY"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val ex = args["endX"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val ey = args["endY"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val dur = args["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L
                        JsonPrimitive(facade.performSwipe(sx, sy, ex, ey, dur)).toString()
                    }
                    "engine/getUiHierarchy" -> JsonPrimitive(facade.getUiHierarchy() ?: "").toString()
                    "engine/getCurrentActivityName" -> JsonPrimitive(facade.getCurrentActivityName() ?: "").toString()
                    "engine/takeScreenshot" -> {
                        val path = args["path"]?.jsonPrimitive?.content ?: ""
                        val format = args["format"]?.jsonPrimitive?.content ?: "png"
                        JsonPrimitive(facade.takeScreenshot(path, format)).toString()
                    }
                    "engine/checkPermission" -> {
                        val p = args["permission"]?.jsonPrimitive?.content ?: ""
                        JsonPrimitive(facade.checkPermission(p)).toString()
                    }
                    "engine/isShizukuAvailable" -> JsonPrimitive(facade.isShizukuAvailable()).toString()
                    "engine/isShizukuPermissionGranted" -> JsonPrimitive(facade.isShizukuPermissionGranted()).toString()
                    "engine/getShizukuVersion" -> JsonPrimitive(facade.getShizukuVersion()).toString()
                    "engine/requestShizukuPermission" -> {
                        facade.requestShizukuPermission()
                        JsonPrimitive(true).toString()
                    }
                    "engine/getEngineVersion" -> JsonPrimitive(facade.getEngineVersion()).toString()
                    "engine/getDeviceInfo" -> facade.getDeviceInfo().toJson().toString()
                    else -> errorResponse("unknown method: $method")
                }
            }
        }.getOrElse { t ->
            errorResponse(t.message ?: t.javaClass.simpleName)
        }
        return result
    }

    override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
        // 当前所有方法都是同步快返回，直接调用同步版本
        onProgress(50, "executing")
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String = channelName
    override fun closeStream(channelName: String) { /* no-op */ }

    private fun <T> buildResult(result: com.apex.sdk.common.BridgeResult<T>, transform: (T) -> JsonObject): String {
        return when (result) {
            is com.apex.sdk.common.BridgeResult.Success -> {
                buildJsonObject {
                    put("success", true)
                    put("data", transform(result.value))
                }.toString()
            }
            is com.apex.sdk.common.BridgeResult.Failure -> {
                buildJsonObject {
                    put("success", false)
                    put("errorCode", result.error.code)
                    put("errorMessage", result.error.message)
                }.toString()
            }
        }
    }

    private fun errorResponse(message: String): String {
        return buildJsonObject {
            put("success", false)
            put("errorMessage", message)
        }.toString()
    }
}

// JSON 序列化扩展
private fun ShellResult.toJson(): JsonObject = buildJsonObject {
    put("stdout", stdout)
    put("stderr", stderr)
    put("exitCode", exitCode)
    put("success", success)
    put("executionTimeMs", executionTimeMs)
}

private fun ContainerStatusInfo.toJson(): JsonObject = buildJsonObject {
    put("statusCode", statusCode)
    put("statusMessage", statusMessage)
    put("pid", pid)
    put("startTime", startTime)
    put("rootfsPath", rootfsPath)
    put("isRunning", isRunning)
}

private fun DeviceInfo.toJson(): JsonObject = buildJsonObject {
    put("brand", brand)
    put("model", model)
    put("sdkInt", sdkInt)
    put("release", release)
    put("manufacturer", manufacturer)
    put("abis", abis.joinToString(","))
}
