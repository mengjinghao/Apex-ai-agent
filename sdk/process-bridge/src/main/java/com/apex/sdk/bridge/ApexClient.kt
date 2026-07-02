package com.apex.sdk.bridge

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 跨 APK 调用客户端门面 — 业务侧的统一入口。
 *
 * **设计原则**：
 *   - 本类不直接引用各 APK 的 Facade 类，避免 SDK 模块循环依赖各 APK
 *   - 全部通过 [ApexBridge.invoke] 走通用 method + JSON 调用
 *   - 调用方拿到 BridgeResult<String>，自行用 kotlinx.serialization 解析
 *
 * **使用示例**：
 *   ```kotlin
 *   // 在任意 APK 中调用 Engine
 *   val result = ApexClient.engine.executeShell("ls /sdcard")
 *   if (result is BridgeResult.Success) {
 *       println(result.value)  // JSON 字符串
 *   }
 *
 *   // 调用 Terminal（创建会话）
 *   val sessionResult = ApexClient.terminal.createNormalSession("/sdcard")
 *
 *   // 调用 Market 搜索
 *   val items = ApexClient.market.search("SKILLS", "react", 20)
 *
 *   // 调用 Rage Mode 执行任务
 *   val sessionId = ApexClient.rage.startSession("分析代码", "reasoning.react")
 *   val execResult = ApexClient.rage.executeTask(sessionId)
 *   ```
 *
 * **零延迟路径**：
 *   当本 APK 与目标 APK 共享 [ApexSuite.MAIN_PROCESS] 时，
 *   ApexBridge.invoke 内部会优先走 [InProcessRegistry]，零延迟 JVM 直调。
 *
 * **降级路径**：
 *   跨进程时走 AIDL，毫秒级延迟。
 *
 * **如果目标 APK 未安装**：
 *   返回 [BridgeResult.Failure]，errorCode = [com.apex.sdk.common.BridgeError.CODE_APK_NOT_INSTALLED]
 */
object ApexClient {

    /** 引擎 APK 客户端。 */
    val engine: EngineClient = EngineClient()

    /** 狂暴模式 APK 客户端。 */
    val rage: RageClient = RageClient()

    /** 多 Agent APK 客户端。 */
    val multiAgent: MultiAgentClient = MultiAgentClient()

    /** 工作流 APK 客户端。 */
    val workflow: WorkflowClient = WorkflowClient()

    /** 市场 APK 客户端。 */
    val market: MarketClient = MarketClient()

    /** 终端 APK 客户端。 */
    val terminal: TerminalClient = TerminalClient()

    /** 工作文件区 APK 客户端。 */
    val workingFiles: WorkingFilesClient = WorkingFilesClient()

    /** 诊断 APK 客户端。 */
    val diagnostics: DiagnosticsClient = DiagnosticsClient()

    /** 语音 APK 客户端。 */
    val voice: VoiceClient = VoiceClient()

    // ============================================================
    // Engine
    // ============================================================

    class EngineClient {
        suspend fun executeShell(cmd: String): BridgeResult<String> = invoke("engine/executeShell", mapOf("cmd" to cmd))

        suspend fun executeShellViaShizuku(cmd: String): BridgeResult<String> =
            invoke("engine/executeShellViaShizuku", mapOf("cmd" to cmd))

        suspend fun executeTool(tool: String, args: String): BridgeResult<String> =
            invoke("engine/executeTool", mapOf("tool" to tool, "args" to args))

        suspend fun listTools(): BridgeResult<String> = invoke("engine/listTools", emptyMap())

        suspend fun startContainer(): BridgeResult<String> = invoke("engine/startContainer", emptyMap())
        suspend fun stopContainer(): BridgeResult<String> = invoke("engine/stopContainer", emptyMap())
        suspend fun restartContainer(): BridgeResult<String> = invoke("engine/restartContainer", emptyMap())
        suspend fun getContainerStatus(): BridgeResult<String> = invoke("engine/getContainerStatus", emptyMap())
        suspend fun getContainerOutput(): BridgeResult<String> = invoke("engine/getContainerOutput", emptyMap())

        suspend fun performClick(x: Int, y: Int): BridgeResult<String> =
            invoke("engine/performClick", mapOf("x" to x.toString(), "y" to y.toString()))

        suspend fun performSwipe(sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long = 300): BridgeResult<String> =
            invoke("engine/performSwipe", mapOf(
                "startX" to sx.toString(), "startY" to sy.toString(),
                "endX" to ex.toString(), "endY" to ey.toString(),
                "durationMs" to durationMs.toString()
            ))

        suspend fun getUiHierarchy(): BridgeResult<String> = invoke("engine/getUiHierarchy", emptyMap())
        suspend fun getCurrentActivityName(): BridgeResult<String> = invoke("engine/getCurrentActivityName", emptyMap())
        suspend fun takeScreenshot(path: String, format: String = "png"): BridgeResult<String> =
            invoke("engine/takeScreenshot", mapOf("path" to path, "format" to format))

        suspend fun checkPermission(permission: String): BridgeResult<String> =
            invoke("engine/checkPermission", mapOf("permission" to permission))

        suspend fun isShizukuAvailable(): BridgeResult<String> = invoke("engine/isShizukuAvailable", emptyMap())
        suspend fun isShizukuPermissionGranted(): BridgeResult<String> = invoke("engine/isShizukuPermissionGranted", emptyMap())
        suspend fun requestShizukuPermission(): BridgeResult<String> = invoke("engine/requestShizukuPermission", emptyMap())

        suspend fun getEngineVersion(): BridgeResult<String> = invoke("engine/getEngineVersion", emptyMap())
        suspend fun getDeviceInfo(): BridgeResult<String> = invoke("engine/getDeviceInfo", emptyMap())
    }

    // ============================================================
    // Rage
    // ============================================================

    class RageClient {
        suspend fun initialize(preset: String = "BALANCED"): BridgeResult<String> =
            invoke("rage/initialize", mapOf("preset" to preset), "rage")

        suspend fun startSession(taskDescription: String, skillId: String? = null, preset: String = "BALANCED"): BridgeResult<String> =
            invoke("rage/startSession", buildMap {
                put("taskDescription", taskDescription)
                if (skillId != null) put("skillId", skillId)
                put("preset", preset)
            }, "rage")

        suspend fun executeTask(sessionId: String): BridgeResult<String> =
            invoke("rage/executeTask", mapOf("sessionId" to sessionId), "rage")

        suspend fun pauseSession(sessionId: String): BridgeResult<String> =
            invoke("rage/pauseSession", mapOf("sessionId" to sessionId), "rage")

        suspend fun resumeSession(sessionId: String): BridgeResult<String> =
            invoke("rage/resumeSession", mapOf("sessionId" to sessionId), "rage")

        suspend fun stopSession(sessionId: String): BridgeResult<String> =
            invoke("rage/stopSession", mapOf("sessionId" to sessionId), "rage")

        suspend fun listSkills(): BridgeResult<String> = invoke("rage/listSkills", emptyMap(), "rage")

        suspend fun switchPreset(preset: String): BridgeResult<String> =
            invoke("rage/switchPreset", mapOf("preset" to preset), "rage")

        suspend fun getMetrics(): BridgeResult<String> = invoke("rage/getMetrics", emptyMap(), "rage")
        suspend fun listSessions(): BridgeResult<String> = invoke("rage/listSessions", emptyMap(), "rage")
        suspend fun listCheckpoints(): BridgeResult<String> = invoke("rage/listCheckpoints", emptyMap(), "rage")
        suspend fun shutdown(): BridgeResult<String> = invoke("rage/shutdown", emptyMap(), "rage")
    }

    // ============================================================
    // Multi-Agent
    // ============================================================

    class MultiAgentClient {
        suspend fun registerAgent(agentId: String, displayName: String, role: String = "WORKER"): BridgeResult<String> =
            invoke("multiagent/registerAgent", mapOf(
                "agentId" to agentId,
                "displayName" to displayName,
                "role" to role
            ), "multi-agent")

        suspend fun runCollaboration(
            mode: String, agentIds: List<String>, initialPrompt: String,
            maxRounds: Int = 10, timeoutMs: Long = 60_000L
        ): BridgeResult<String> = invoke("multiagent/runCollaboration", mapOf(
            "mode" to mode,
            "agentIds" to agentIds.joinToString(","),
            "initialPrompt" to initialPrompt,
            "maxRounds" to maxRounds.toString(),
            "timeoutMs" to timeoutMs.toString()
        ), "multi-agent")

        suspend fun listAgents(): BridgeResult<String> = invoke("multiagent/listAgents", emptyMap(), "multi-agent")
        suspend fun unregisterAgent(agentId: String): BridgeResult<String> =
            invoke("multiagent/unregisterAgent", mapOf("agentId" to agentId), "multi-agent")
        suspend fun readBlackboard(key: String): BridgeResult<String> =
            invoke("multiagent/readBlackboard", mapOf("key" to key), "multi-agent")
        suspend fun writeBlackboard(key: String, value: String): BridgeResult<String> =
            invoke("multiagent/writeBlackboard", mapOf("key" to key, "value" to value), "multi-agent")
        suspend fun blackboardSnapshot(): BridgeResult<String> =
            invoke("multiagent/blackboardSnapshot", emptyMap(), "multi-agent")
        suspend fun listSessions(): BridgeResult<String> = invoke("multiagent/listSessions", emptyMap(), "multi-agent")
        suspend fun cancelSession(sessionId: String): BridgeResult<String> =
            invoke("multiagent/cancelSession", mapOf("sessionId" to sessionId), "multi-agent")
    }

    // ============================================================
    // Workflow
    // ============================================================

    class WorkflowClient {
        suspend fun register(workflowJson: String): BridgeResult<String> =
            invoke("workflow/register", mapOf("workflowJson" to workflowJson), "workflow")

        suspend fun execute(workflowId: String, inputs: Map<String, String> = emptyMap()): BridgeResult<String> {
            val inputsJson = buildJsonObject {
                inputs.forEach { (k, v) -> put(k, v) }
            }.toString()
            return invoke("workflow/execute", mapOf(
                "workflowId" to workflowId,
                "inputs" to inputsJson
            ), "workflow")
        }

        suspend fun list(): BridgeResult<String> = invoke("workflow/list", emptyMap(), "workflow")
        suspend fun unregister(workflowId: String): BridgeResult<String> =
            invoke("workflow/unregister", mapOf("workflowId" to workflowId), "workflow")
        suspend fun history(): BridgeResult<String> = invoke("workflow/history", emptyMap(), "workflow")
    }

    // ============================================================
    // Market
    // ============================================================

    class MarketClient {
        suspend fun initialize(): BridgeResult<String> = invoke("market/initialize", emptyMap(), "market")
        suspend fun listMarkets(category: String): BridgeResult<String> =
            invoke("market/listMarkets", mapOf("category" to category), "market")

        suspend fun search(category: String, query: String, limit: Int = 50): BridgeResult<String> =
            invoke("market/search", mapOf("category" to category, "query" to query, "limit" to limit.toString()), "market")

        suspend fun listInstalled(category: String? = null): BridgeResult<String> {
            val args = if (category != null) mapOf("category" to category) else emptyMap()
            return invoke("market/listInstalled", args, "market")
        }

        suspend fun install(itemId: String, category: String): BridgeResult<String> =
            invoke("market/install", mapOf("itemId" to itemId, "category" to category), "market")

        suspend fun uninstall(itemId: String): BridgeResult<String> =
            invoke("market/uninstall", mapOf("itemId" to itemId), "market")

        suspend fun setEnabled(itemId: String, enabled: Boolean): BridgeResult<String> =
            invoke("market/setEnabled", mapOf("itemId" to itemId, "enabled" to enabled.toString()), "market")

        suspend fun invokeModel(provider: String, modelName: String, prompt: String, maxTokens: Int = 2048): BridgeResult<String> =
            invoke("market/invokeModel", mapOf(
                "provider" to provider, "modelName" to modelName,
                "prompt" to prompt, "maxTokens" to maxTokens.toString()
            ), "market")

        suspend fun invokeLocalSkill(itemId: String, method: String, argsJson: String): BridgeResult<String> =
            invoke("market/invokeLocalSkill", mapOf(
                "itemId" to itemId, "method" to method, "argsJson" to argsJson
            ), "market")

        suspend fun getOverview(): BridgeResult<String> = invoke("market/getOverview", emptyMap(), "market")
        suspend fun getUpdatable(): BridgeResult<String> = invoke("market/getUpdatable", emptyMap(), "market")
        suspend fun generateMcpConfig(format: String = "APEX_AGENT"): BridgeResult<String> =
            invoke("market/generateMcpConfig", mapOf("format" to format), "market")
    }

    // ============================================================
    // Terminal
    // ============================================================

    class TerminalClient {
        suspend fun createNormalSession(workingDir: String = "~"): BridgeResult<String> =
            invoke("terminal/createNormalSession", mapOf("workingDir" to workingDir), "terminal")

        suspend fun createMultiAgentSession(workingDir: String = "~", agentId: String = "builtin.supervisor"): BridgeResult<String> =
            invoke("terminal/createMultiAgentSession", mapOf("workingDir" to workingDir, "agentId" to agentId), "terminal")

        suspend fun createBurstSession(workingDir: String = "~", burstProfile: String = "BALANCED"): BridgeResult<String> =
            invoke("terminal/createBurstSession", mapOf("workingDir" to workingDir, "burstProfile" to burstProfile), "terminal")

        suspend fun writeInput(sessionId: String, data: String): BridgeResult<String> =
            invoke("terminal/writeInput", mapOf("sessionId" to sessionId, "data" to data), "terminal")

        suspend fun readOutput(sessionId: String): BridgeResult<String> =
            invoke("terminal/readOutput", mapOf("sessionId" to sessionId), "terminal")

        suspend fun getStreamChannel(sessionId: String): BridgeResult<String> =
            invoke("terminal/getStreamChannel", mapOf("sessionId" to sessionId), "terminal")

        suspend fun destroySession(sessionId: String): BridgeResult<String> =
            invoke("terminal/destroySession", mapOf("sessionId" to sessionId), "terminal")

        suspend fun listSessions(): BridgeResult<String> = invoke("terminal/listSessions", emptyMap(), "terminal")
        suspend fun switchMode(sessionId: String, mode: String): BridgeResult<String> =
            invoke("terminal/switchMode", mapOf("sessionId" to sessionId, "mode" to mode), "terminal")

        suspend fun startBurstTask(sessionId: String, taskDescription: String): BridgeResult<String> =
            invoke("terminal/startBurstTask", mapOf("sessionId" to sessionId, "taskDescription" to taskDescription), "terminal")
    }

    // ============================================================
    // Working Files
    // ============================================================

    class WorkingFilesClient {
        suspend fun bindFolder(folderId: String, displayName: String, path: String, mode: String = "ALL"): BridgeResult<String> =
            invoke("workingfiles/bindFolder", mapOf(
                "folderId" to folderId, "displayName" to displayName,
                "path" to path, "mode" to mode
            ), "working-files")

        suspend fun unbindFolder(folderId: String): BridgeResult<String> =
            invoke("workingfiles/unbindFolder", mapOf("folderId" to folderId), "working-files")

        suspend fun listFolders(): BridgeResult<String> = invoke("workingfiles/listFolders", emptyMap(), "working-files")

        suspend fun listFiles(folderId: String, relativePath: String = ""): BridgeResult<String> =
            invoke("workingfiles/listFiles", mapOf("folderId" to folderId, "relativePath" to relativePath), "working-files")

        suspend fun readFile(folderId: String, relativePath: String): BridgeResult<String> =
            invoke("workingfiles/readFile", mapOf("folderId" to folderId, "relativePath" to relativePath), "working-files")

        suspend fun writeFile(folderId: String, relativePath: String, content: String, append: Boolean = false): BridgeResult<String> =
            invoke("workingfiles/writeFile", mapOf(
                "folderId" to folderId, "relativePath" to relativePath,
                "content" to content, "append" to append.toString()
            ), "working-files")

        suspend fun loadCodeFile(folderId: String, relativePath: String): BridgeResult<String> =
            invoke("workingfiles/loadCodeFile", mapOf("folderId" to folderId, "relativePath" to relativePath), "working-files")
    }

    // ============================================================
    // Diagnostics
    // ============================================================

    class DiagnosticsClient {
        suspend fun startLogCapture(): BridgeResult<String> = invoke("diagnostics/startLogCapture", emptyMap(), "diagnostics")
        suspend fun getRecentLogs(maxLines: Int = 500): BridgeResult<String> =
            invoke("diagnostics/getRecentLogs", mapOf("maxLines" to maxLines.toString()), "diagnostics")
        suspend fun listLogFiles(): BridgeResult<String> = invoke("diagnostics/listLogFiles", emptyMap(), "diagnostics")
        suspend fun listCrashReports(): BridgeResult<String> = invoke("diagnostics/listCrashReports", emptyMap(), "diagnostics")
        suspend fun getMemoryStats(): BridgeResult<String> = invoke("diagnostics/getMemoryStats", emptyMap(), "diagnostics")
        suspend fun getApkHealthList(): BridgeResult<String> = invoke("diagnostics/getApkHealthList", emptyMap(), "diagnostics")
        suspend fun getSystemInfo(): BridgeResult<String> = invoke("diagnostics/getSystemInfo", emptyMap(), "diagnostics")
        suspend fun forceGc(): BridgeResult<String> = invoke("diagnostics/forceGc", emptyMap(), "diagnostics")
        suspend fun dumpHeap(fileName: String = "apex-heap.hprof"): BridgeResult<String> =
            invoke("diagnostics/dumpHeap", mapOf("fileName" to fileName), "diagnostics")
    }

    // ============================================================
    // Voice
    // ============================================================

    class VoiceClient {
        suspend fun initializeTts(language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/initializeTts", mapOf("language" to language), "voice")

        suspend fun speak(text: String, language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/speak", mapOf("text" to text, "language" to language), "voice")

        suspend fun speakAsync(text: String, language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/speakAsync", mapOf("text" to text, "language" to language), "voice")

        suspend fun stopSpeaking(): BridgeResult<String> = invoke("voice/stopSpeaking", emptyMap(), "voice")

        suspend fun startRecognition(language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/startRecognition", mapOf("language" to language), "voice")

        suspend fun recognizeOnce(language: String = "zh-CN", timeoutMs: Long = 30_000L): BridgeResult<String> =
            invoke("voice/recognizeOnce", mapOf("language" to language, "timeoutMs" to timeoutMs.toString()), "voice")

        suspend fun stopRecognition(): BridgeResult<String> = invoke("voice/stopRecognition", emptyMap(), "voice")
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    /**
     * 通用 invoke：把 args 转成 JSON，调用 [ApexBridge.invoke]。
     * @param method 形如 "engine/executeShell"
     * @param args   参数 map（值必须是基本类型 / String）
     * @param apkId  目标 APK ID（用于诊断日志），如 "engine" / "rage" / "terminal"
     */
    private suspend fun invoke(
        method: String,
        args: Map<String, String>,
        apkId: String = method.substringBefore('/')
    ): BridgeResult<String> {
        val argsJson = buildJsonObject {
            args.forEach { (k, v) -> put(k, v) }
        }.toString()
        ApexLog.v(apkId, "[ApexClient] invoke $method")
        return ApexBridge.invoke(method, argsJson)
    }
}
