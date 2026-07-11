package com.apex.apk.terminal
import kotlinx.serialization.json.jsonPrimitive

import com.apex.lib.terminal.TerminalPresets
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
 * 路由方法（旧 API，lib 引擎接入前已有）：
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
 *
 * 路由方法（lib:terminal 新增能力，ROUND-2 补全）：
 *   - terminal/resize          → engine.resize
 *   - terminal/getHistory      → engine.getHistory（可选 limit 取最近 N 条）
 *   - terminal/searchHistory   → engine.searchHistory
 *   - terminal/getOutputBuffer → engine.outputSnapshot / lastOutputLines（可选 maxLines）
 *   - terminal/getSessionInfo  → engine.getSession（type/status/workingDir/createdAt/active/...）
 *   - terminal/getPresets      → TerminalPresets.ALL（三块终端预设配置）
 *   - terminal/clearHistory    → engine.clearHistory
 *   - terminal/isAlive         → facade.isAlive（PTY 存活）
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
                        buildResult(facade.createNormalSession(dir)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "terminal/createMultiAgentSession" -> {
                        val dir = args["workingDir"]?.jsonPrimitive?.content ?: "~"
                        val agentId = args["agentId"]?.jsonPrimitive?.content ?: "builtin.supervisor"
                        buildResult(facade.createMultiAgentSession(dir, agentId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
                    }
                    "terminal/createBurstSession" -> {
                        val dir = args["workingDir"]?.jsonPrimitive?.content ?: "~"
                        val profile = args["burstProfile"]?.jsonPrimitive?.content ?: "BALANCED"
                        buildResult(facade.createBurstSession(dir, profile)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
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
                        buildResult(facade.destroySession(sessionId)) { buildJsonObject { put("result", JsonPrimitive(it)) } }
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

                    // ===== lib:terminal 新增能力路由（ROUND-2 补全）=====

                    // 调整终端尺寸（PTY + 会话 rows/cols 同步更新）
                    "terminal/resize" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val rows = args["rows"]?.jsonPrimitive?.content?.toIntOrNull() ?: 24
                        val cols = args["cols"]?.jsonPrimitive?.content?.toIntOrNull() ?: 80
                        buildResult(facade.engine().resize(sessionId, rows, cols)) { JsonObject(emptyMap()) }
                    }

                    // 获取会话命令历史（可选 limit 取最近 N 条）
                    "terminal/getHistory" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val all = facade.engine().getHistory(sessionId)
                        val list = if (limit > 0) all.takeLast(limit) else all
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("history", list.joinToString("\n") { e ->
                                val ec = e.exitCode?.let { " [exit=$it]" } ?: ""
                                "${e.timestamp}|${e.command}${ec}"
                            })
                        }.toString()
                    }

                    // 全文搜索命令历史（大小写不敏感子串匹配）
                    "terminal/searchHistory" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val query = args["query"]?.jsonPrimitive?.content ?: ""
                        val list = facade.engine().searchHistory(sessionId, query)
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("matches", list.joinToString("\n") { e ->
                                val ec = e.exitCode?.let { " [exit=$it]" } ?: ""
                                "${e.timestamp}|${e.command}${ec}"
                            })
                        }.toString()
                    }

                    // 获取输出缓冲（环形行列表）。maxLines>0 时只返回最近 N 行，否则返回完整快照
                    "terminal/getOutputBuffer" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val maxLines = args["maxLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val lines = if (maxLines > 0) {
                            facade.engine().lastOutputLines(sessionId, maxLines)
                        } else {
                            facade.engine().outputSnapshot(sessionId)
                        }
                        buildJsonObject {
                            put("success", true)
                            put("lineCount", lines.size)
                            put("content", lines.joinToString("\n"))
                        }.toString()
                    }

                    // 获取会话详情（type/status/workingDir/createdAt/active/rows/cols/shell/...）
                    "terminal/getSessionInfo" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val s = facade.engine().getSession(sessionId)
                        if (s == null) {
                            buildJsonObject {
                                put("success", false)
                                put("errorMessage", "session not found: $sessionId")
                            }.toString()
                        } else {
                            buildJsonObject {
                                put("success", true)
                                put("sessionId", s.id)
                                put("type", s.type.name)
                                put("status", s.status.name)
                                put("workingDir", s.workingDir)
                                put("createdAt", s.createdAt)
                                put("active", s.isActive)
                                put("rows", s.rows)
                                put("cols", s.cols)
                                put("shell", s.shell)
                                put("bufferLineLimit", s.bufferLineLimit)
                                if (s.closedAt != null) put("closedAt", s.closedAt)
                                if (s.exitCode != null) put("exitCode", s.exitCode)
                            }.toString()
                        }
                    }

                    // 返回三块终端预设（NORMAL / MULTI_AGENT / RAGE）
                    "terminal/getPresets" -> {
                        val presets = TerminalPresets.ALL
                        buildJsonObject {
                            put("success", true)
                            put("count", presets.size)
                            put("presets", presets.joinToString("\n") { p ->
                                val env = p.env.entries.joinToString(",") { "${it.key}=${it.value}" }
                                val cmds = if (p.initialCommands.isEmpty()) "" else p.initialCommands.joinToString(";")
                                "${p.type.name}|dir=${p.defaultWorkingDir}|shell=${p.shell}|buf=${p.bufferLineLimit}|size=${p.defaultRows}x${p.defaultCols}|env=$env|init=$cmds"
                            })
                        }.toString()
                    }

                    // 清空指定会话的命令历史
                    "terminal/clearHistory" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        facade.engine().clearHistory(sessionId)
                        buildJsonObject { put("success", true) }.toString()
                    }

                    // 查询会话 PTY 是否存活
                    "terminal/isAlive" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val alive = facade.isAlive(sessionId)
                        buildJsonObject {
                            put("success", true)
                            put("alive", alive)
                            put("sessionId", sessionId)
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
