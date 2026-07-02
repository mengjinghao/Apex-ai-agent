package com.apex.apk.terminal

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

/**
 * Terminal APK 的 Bridge 实现。
 *
 * 路由方法：
 *   - terminal/createNormalSession
 *   - terminal/createMultiAgentSession
 *   - terminal/createBurstSession
 *   - terminal/writeInput
 *   - terminal/readOutput
 *   - terminal/getStreamChannel
 *   - terminal/destroySession
 *   - terminal/listSessions
 *   - terminal/switchMode
 *   - terminal/startBurstTask
 *   - terminal/shutdown
 */
class TerminalBridgeImpl(
    private val facade: TerminalServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.TERMINAL, "[TerminalBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "terminal/createNormalSession" -> {
                        val dir = args["workingDir"]?.jsonPrimitive?.content ?: "~"
                        buildResult(facade.createNormalSession(dir)) { JsonPrimitive(it) }
                    }
                    "terminal/createMultiAgentSession" -> {
                        val dir = args["workingDir"]?.jsonPrimitive?.content ?: "~"
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: "builtin.supervisor"
                        buildResult(facade.createMultiAgentSession(dir, agentId)) { JsonPrimitive(it) }
                    }
                    "terminal/createBurstSession" -> {
                        val dir = args["workingDir"]?.jsonPrimitive?.content ?: "~"
                        val profile = args["burstProfile"]?.jsonPrimitive?.content ?: "BALANCED"
                        buildResult(facade.createBurstSession(dir, profile)) { JsonPrimitive(it) }
                    }
                    "terminal/writeInput" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val data = args["data"]?.jsonPrimitive?.content?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                        buildResult(facade.writeInput(sessionId, data)) { JsonObject(emptyMap()) }
                    }
                    "terminal/readOutput" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.readOutput(sessionId)) { JsonPrimitive(String(it, Charsets.UTF_8)) }
                    }
                    "terminal/getStreamChannel" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val channel = facade.getStreamChannel(sessionId)
                        buildJsonObject {
                            put("success", channel != null)
                            put("channelName", channel ?: "")
                        }.toString()
                    }
                    "terminal/destroySession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.destroySession(sessionId)) { JsonPrimitive(it) }
                    }
                    "terminal/listSessions" -> {
                        val list = facade.listSessions()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("sessions", list.joinToString("\n") {
                                "${it.sessionId}: ${it.mode} (dir=${it.workingDir})"
                            })
                        }.toString()
                    }
                    "terminal/switchMode" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val modeStr = args["mode"]?.jsonPrimitive?.content ?: "NORMAL"
                        val mode = runCatching { TerminalKind.valueOf(modeStr) }.getOrDefault(TerminalKind.NORMAL)
                        buildResult(facade.switchMode(sessionId, mode)) { JsonObject(emptyMap()) }
                    }
                    "terminal/startBurstTask" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val task = args["taskDescription"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.startBurstTask(sessionId, task)) { JsonObject(emptyMap()) }
                    }
                    "terminal/shutdown" -> {
                        facade.shutdown()
                        buildJsonObject { put("success", true) }.toString()
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

    override fun openStream(channelName: String): String {
        // 返回通道名，调用方据此连接 LocalSocket
        return channelName
    }

    override fun closeStream(channelName: String) {
        com.apex.sdk.bridge.StreamChannelRegistry.close(channelName)
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
