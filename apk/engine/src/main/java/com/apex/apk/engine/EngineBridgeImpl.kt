package com.apex.apk.engine

import android.content.Intent
import android.os.IBinder
import com.apex.lib.engine.ContainerLifecycleEvent
import com.apex.lib.engine.ContainerState
import com.apex.lib.engine.ContainerStatusInfo
import com.apex.lib.engine.DeviceInfo
import com.apex.lib.engine.ShellResult
import com.apex.sdk.bridge.ApkBridgeStubAdapter
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

    /**
     * 容器生命周期事件环形缓冲（最多 [MAX_LIFECYCLE_EVENTS] 条）。
     *
     * `ContainerLifecycle.events` 是 `SharedFlow`（replay=0），不保留历史，
     * 因此 BridgeImpl 在构造时启动一个常驻 collector 把事件追加到本缓冲，
     * 供 `engine/getContainerLifecycleEvents` 路由读取"最近事件"。
     */
    private val lifecycleEvents = mutableListOf<ContainerLifecycleEvent>()
    private val lifecycleEventsLock = Any()

    init {
        // 订阅 orchestrator.containerLifecycle.events，写入环形缓冲。
        // collector 跑在 [scope]（SupervisorJob），与 BridgeImpl 同生命周期。
        scope.launch {
            try {
                facade.orchestrator.containerLifecycle.events.collect { event ->
                    synchronized(lifecycleEventsLock) {
                        lifecycleEvents.add(event)
                        while (lifecycleEvents.size > MAX_LIFECYCLE_EVENTS) {
                            lifecycleEvents.removeAt(0)
                        }
                    }
                }
            } catch (t: Throwable) {
                ApexLog.w(
                    ApexSuite.ApkId.ENGINE,
                    "[EngineBridge] lifecycle events collector terminated: ${t.message}"
                )
            }
        }
    }

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
                        facade.requestShizukuPermission(0)
                        JsonPrimitive(true).toString()
                    }
                    "engine/getEngineVersion" -> JsonPrimitive(facade.getEngineVersion()).toString()
                    "engine/getDeviceInfo" -> facade.getDeviceInfo().toJson().toString()

                    // ============================================================
                    // lib:engine 编排器新增能力（orchestrator 路由）
                    // ============================================================

                    // 聚合状态快照：绑定 + 引擎版本 + Shizuku 策略 + 容器状态 + 工具数
                    "engine/queryStatus" -> {
                        val r = facade.orchestrator.queryStatus()
                        buildResult(r) { snap ->
                            buildJsonObject {
                                put("bound", snap.bound)
                                put("engineVersion", snap.engineVersion)
                                put("containerState", snap.containerState.name)
                                put("containerStateDisplay", snap.containerState.displayName)
                                put("toolCount", snap.toolCount)
                                put("shizukuAvailable", snap.shizuku.isAvailable)
                                put("shizukuAuthorized", snap.shizuku.isAuthorized)
                                put("shizukuVersion", snap.shizuku.version)
                                put("shizukuAvailability", snap.shizuku.availability().name)
                            }
                        }
                    }

                    // 执行工具（带工具名校验，区别于旧 executeTool 走 facade.executeTool）
                    // 接受 toolName 参数；args 为工具参数 JSON 字符串
                    "engine/executeToolSafe" -> {
                        val toolName = args["toolName"]?.jsonPrimitive?.content ?: ""
                        val toolArgs = args["args"]?.jsonPrimitive?.content ?: ""
                        val r = facade.orchestrator.executeToolSafe(toolName, toolArgs)
                        buildResult(r) { it.toJson() }
                    }

                    // 当前容器状态机状态（STOPPED/STARTING/RUNNING/STOPPING/ERROR）
                    "engine/getContainerLifecycleState" -> {
                        val state = facade.orchestrator.containerLifecycle.current()
                        buildJsonObject {
                            put("success", true)
                            put("state", state.name)
                            put("displayName", state.displayName)
                            put("isRunning", state == ContainerState.RUNNING)
                            put("isStarting", state == ContainerState.STARTING)
                            put("isStopped", state == ContainerState.STOPPED)
                            put("isStopping", state == ContainerState.STOPPING)
                            put("isError", state == ContainerState.ERROR)
                        }.toString()
                    }

                    // 最近的容器生命周期事件列表（可选 since 毫秒时间戳过滤）
                    // 事件来自 BridgeImpl 内的环形缓冲（订阅 SharedFlow，最多 100 条）
                    "engine/getContainerLifecycleEvents" -> {
                        val since = args["since"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val events = synchronized(lifecycleEventsLock) {
                            lifecycleEvents.filter { it.timestamp >= since }.toList()
                        }
                        buildJsonObject {
                            put("success", true)
                            put("count", events.size)
                            put("events", events.joinToString("\n") { e ->
                                "${e.from.name}->${e.to.name}|${e.reason}|${e.timestamp}"
                            })
                        }.toString()
                    }

                    // 工具目录全量（走 lib 的 ToolCatalog，区别于旧 listTools 走 IEngineService）
                    "engine/getToolCatalog" -> {
                        val list = facade.orchestrator.toolCatalog.list()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("items", list.joinToString("\n") {
                                "${it.name} (${it.category}) root=${it.requiresRoot}: ${it.description}"
                            })
                        }.toString()
                    }

                    // 按分类返回工具
                    "engine/getToolsByCategory" -> {
                        val category = args["category"]?.jsonPrimitive?.content ?: ""
                        val list = facade.orchestrator.toolCatalog.byCategory(category)
                        buildJsonObject {
                            put("success", true)
                            put("category", category)
                            put("count", list.size)
                            put("items", list.joinToString("\n") {
                                "${it.name} root=${it.requiresRoot}: ${it.description}"
                            })
                        }.toString()
                    }

                    // 当前 Shizuku 策略：可用性分级 + 降级建议
                    "engine/getShizukuPolicy" -> {
                        val policy = facade.orchestrator.currentShizukuPolicy()
                        buildJsonObject {
                            put("success", true)
                            put("availability", policy.availability().name)
                            put("availabilityDisplay", policy.availability().displayName)
                            put("isAvailable", policy.isAvailable)
                            put("isAuthorized", policy.isAuthorized)
                            put("version", policy.version)
                            put("minVersionRequired", policy.minVersionRequired)
                            put("fallbackOnUnavailable", policy.fallbackOnUnavailable)
                            put("shouldUseShizukuIfPreferred", policy.shouldUseShizuku(true))
                            put("shouldFallbackIfPreferred", policy.shouldFallback(true))
                            put("describe", policy.describe())
                        }.toString()
                    }

                    // 重置容器状态机到 STOPPED（诊断用，不调用底层 stopContainer）
                    "engine/resetContainerLifecycle" -> {
                        facade.orchestrator.containerLifecycle.resetTo(
                            ContainerState.STOPPED,
                            "manual reset via bridge"
                        )
                        buildJsonObject {
                            put("success", true)
                            put("state", facade.orchestrator.containerLifecycle.current().name)
                        }.toString()
                    }

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

/** 容器生命周期事件环形缓冲上限。 */
private const val MAX_LIFECYCLE_EVENTS = 100

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
