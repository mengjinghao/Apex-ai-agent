package com.apex.apk.rage

import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rage Mode APK 的 Bridge 实现 — 通用 invoke 路由到 [RageServiceFacade]。
 *
 * 支持的方法路由：
 *   - rage/initialize         初始化狂暴模式
 *   - rage/startSession       启动会话
 *   - rage/executeTask        执行任务
 *   - rage/pauseSession       暂停会话
 *   - rage/resumeSession      恢复会话
 *   - rage/stopSession        终止会话
 *   - rage/listSkills         列出技能
 *   - rage/loadSkill          加载技能
 *   - rage/unloadSkill        卸载技能
 *   - rage/switchPreset       切换预设
 *   - rage/getMetrics         获取指标
 *   - rage/listSessions       列出会话
 *   - rage/listCheckpoints    列出断点
 *   - rage/shutdown           关闭狂暴模式
 */
class RageBridgeImpl(
    private val facade: RageServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.RAGE, "[RageBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "rage/initialize" -> {
                        val preset = args["preset"]?.jsonPrimitive?.content?.let { 
                            runCatching { RagePreset.valueOf(it) }.getOrDefault(RagePreset.BALANCED)
                        } ?: RagePreset.BALANCED
                        buildResult(facade.initialize(preset = preset)) { JsonObject(emptyMap()) }
                    }
                    "rage/startSession" -> {
                        val task = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        val skill = args["skillId"]?.jsonPrimitive?.content
                        val preset = args["preset"]?.jsonPrimitive?.content?.let {
                            runCatching { RagePreset.valueOf(it) }.getOrDefault(RagePreset.BALANCED)
                        } ?: RagePreset.BALANCED
                        buildResult(facade.startSession(task, skill, preset)) { JsonPrimitive(it) }
                    }
                    "rage/executeTask" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.executeTask(sessionId)) { it.toJson() }
                    }
                    "rage/pauseSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.pauseSession(sessionId)) { JsonPrimitive(it) }
                    }
                    "rage/resumeSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.resumeSession(sessionId)) { JsonPrimitive(it) }
                    }
                    "rage/stopSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.stopSession(sessionId)) { JsonPrimitive(it) }
                    }
                    "rage/listSkills" -> {
                        buildResult(facade.listSkills()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("skills", list.joinToString("\n") { "${it.skillId}: ${it.skillName} - ${it.description}" })
                            }
                        }
                    }
                    "rage/switchPreset" -> {
                        val presetName = args["preset"]?.jsonPrimitive?.content ?: "BALANCED"
                        val preset = runCatching { RagePreset.valueOf(presetName) }.getOrDefault(RagePreset.BALANCED)
                        buildResult(facade.switchPreset(preset)) { JsonObject(emptyMap()) }
                    }
                    "rage/getMetrics" -> {
                        val m = facade.getMetrics()
                        if (m != null) {
                            buildJsonObject {
                                put("success", true)
                                put("data", m.toJson())
                            }.toString()
                        } else {
                            buildJsonObject {
                                put("success", false)
                                put("errorMessage", "BurstMode not initialized")
                            }.toString()
                        }
                    }
                    "rage/listSessions" -> {
                        val list = facade.listSessions()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("sessions", list.joinToString("\n") { "${it.sessionId}: ${it.taskName} (paused=${it.paused}, completed=${it.completed})" })
                        }.toString()
                    }
                    "rage/listCheckpoints" -> {
                        buildResult(facade.listCheckpoints()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("items", list.joinToString("\n") { "${it.taskId}: ${it.completedSteps}/${it.totalSteps}" })
                            }
                        }
                    }
                    "rage/shutdown" -> buildResult(facade.shutdown()) { JsonObject(emptyMap()) }
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

private fun RageExecutionResult.toJson(): JsonObject = buildJsonObject {
    put("sessionId", sessionId)
    put("skillId", skillId)
    put("success", success)
    put("output", output)
    put("errorMessage", errorMessage ?: "")
    put("executionTimeMs", executionTimeMs)
    put("tokensProcessed", tokensProcessed)
}

private fun RageMetricsSnapshot.toJson(): JsonObject = buildJsonObject {
    put("totalTasks", totalTasks)
    put("successfulTasks", successfulTasks)
    put("failedTasks", failedTasks)
    put("cancelledTasks", cancelledTasks)
    put("averageExecutionTimeMs", averageExecutionTimeMs)
    put("successRate", successRate)
    put("currentConcurrency", currentConcurrency)
    put("peakConcurrency", peakConcurrency)
    put("totalTokensProcessed", totalTokensProcessed)
    put("totalMemoryUsedMb", totalMemoryUsedMb)
}
