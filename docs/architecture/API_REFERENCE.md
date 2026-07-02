# Apex AI Agent — 跨 APK 调用 API 速查

> 业务侧的统一调用入口：`com.apex.sdk.bridge.ApexClient`
>
> 所有方法返回 `BridgeResult<String>`，value 是 JSON 字符串，
> 由调用方自行用 `kotlinx.serialization` 解析。

---

## Engine APK

```kotlin
ApexClient.engine.executeShell("ls /sdcard")
ApexClient.engine.executeShellViaShizuku("pm list packages")
ApexClient.engine.executeTool("file", """{"operation":"read","path":"/sdcard/test.txt"}""")
ApexClient.engine.listTools()
ApexClient.engine.startContainer() / stopContainer() / restartContainer()
ApexClient.engine.getContainerStatus()
ApexClient.engine.getContainerOutput()
ApexClient.engine.performClick(x = 100, y = 200)
ApexClient.engine.performSwipe(sx=100, sy=500, ex=100, ey=100, durationMs=300)
ApexClient.engine.getUiHierarchy()
ApexClient.engine.getCurrentActivityName()
ApexClient.engine.takeScreenshot(path = "/sdcard/shot.png")
ApexClient.engine.checkPermission("android.permission.READ_EXTERNAL_STORAGE")
ApexClient.engine.isShizukuAvailable()
ApexClient.engine.isShizukuPermissionGranted()
ApexClient.engine.requestShizukuPermission()
ApexClient.engine.getEngineVersion()
ApexClient.engine.getDeviceInfo()
```

---

## Rage Mode APK（狂暴模式）

```kotlin
ApexClient.rage.initialize(preset = "BALANCED")  // PERFORMANCE/BALANCED/POWER_SAVER/LOCAL_INFERENCE/CLOUD_INFERENCE/STREAMING/TEST
val sessionId = ApexClient.rage.startSession(
    taskDescription = "分析代码并优化",
    skillId = "reasoning.react",  // 可选，不传则自动选择
    preset = "BALANCED"
)
ApexClient.rage.executeTask(sessionId)
ApexClient.rage.pauseSession(sessionId)
ApexClient.rage.resumeSession(sessionId)
ApexClient.rage.stopSession(sessionId)
ApexClient.rage.listSkills()  // 列出 31 个内置技能
ApexClient.rage.switchPreset("PERFORMANCE")
ApexClient.rage.getMetrics()
ApexClient.rage.listSessions()
ApexClient.rage.listCheckpoints()
ApexClient.rage.shutdown()
```

**31 个内置技能 ID**（节选）：
- 推理：`reasoning.react`, `reasoning.chain-of-thought`, `reasoning.tree-of-thoughts`, `reasoning.reflexion`, `reasoning.self-consistency`, `reasoning.multi-hop`, `extreme_reasoning`
- 执行：`berserk_execution`, `tool_racing`, `tool_fusion`, `recovery_chain`
- 基础设施：`memory_storage`, `knowledge_graph`, `rag_pipeline`, `file_search`, `security_manager`
- 任务：`task_graph`, `task_scheduler`, `thinking_agent`, `adaptive_execution`, `template_manager`
- 自修正：`self_correction`, `recovery`, `red_blue_adversarial`

---

## Multi-Agent APK

```kotlin
ApexClient.multiAgent.registerAgent(
    agentId = "code-reviewer",
    displayName = "代码审查员",
    role = "REVIEWER"  // SUPERVISOR/WORKER/REVIEWER/CRITIC/OBSERVER
)
val result = ApexClient.multiAgent.runCollaboration(
    mode = "PIPELINE",  // PIPELINE/DEBATE/ADVERSARIAL/PARALLEL_RACING/HIERARCHICAL
    agentIds = listOf("builtin.supervisor", "builtin.worker", "builtin.reviewer"),
    initialPrompt = "审查这份 PR 并给出反馈",
    maxRounds = 10,
    timeoutMs = 60_000L
)
ApexClient.multiAgent.listAgents()
ApexClient.multiAgent.unregisterAgent("code-reviewer")
ApexClient.multiAgent.readBlackboard("last.code-reviewer")
ApexClient.multiAgent.writeBlackboard("context.lang", "kotlin")
ApexClient.multiAgent.blackboardSnapshot()
ApexClient.multiAgent.listSessions()
ApexClient.multiAgent.cancelSession(sessionId)
```

---

## Workflow APK

```kotlin
val workflowJson = """
{
  "id": "my-workflow",
  "displayName": "我的工作流",
  "nodes": [...],
  "edges": [...],
  "entryNodeId": "step1"
}
""".trimIndent()
ApexClient.workflow.register(workflowJson)
ApexClient.workflow.execute("my-workflow", inputs = mapOf("input" to "analyze this"))
ApexClient.workflow.list()  // 包括 3 个内置工作流
ApexClient.workflow.unregister("my-workflow")
ApexClient.workflow.history()
```

**8 种节点类型**：`LlmCall` / `ToolCall` / `Condition` / `Loop` / `Parallel` / `HttpRequest` / `Terminal` / `Code`

**3 个内置工作流**：
- `builtin.simple-llm-chain` — 简单 LLM 调用链
- `builtin.http-fetch-process` — HTTP 抓取 + 处理
- `builtin.conditional-branch` — 条件分支示例

---

## Market APK

```kotlin
ApexClient.market.initialize()
ApexClient.market.listMarkets("SKILLS")  // SKILLS/MCP/PLUGINS/MODEL_PLATFORMS
ApexClient.market.search("SKILLS", query = "react", limit = 50)
ApexClient.market.listInstalled()  // 列出所有已安装
ApexClient.market.install("some-skill-id", "SKILLS")
ApexClient.market.uninstall("some-skill-id")
ApexClient.market.setEnabled("some-skill-id", enabled = false)

// 导入
ApexClient.market.invoke("market/import", mapOf(
    "sourceType" to "URL",  // LOCAL_FILE/URL/GIT_REPOSITORY/CLIPBOARD/FILE_PICKER/DIRECT_INPUT
    "url" to "https://example.com/skill.json",
    "category" to "SKILLS",
    "autoInstall" to "true"
))

// 调用云端模型
ApexClient.market.invokeModel(
    provider = "deepseek",
    modelName = "deepseek-chat",
    prompt = "解释这段代码：..."
)

// 调用本地已安装技能（零延迟直调）
ApexClient.market.invokeLocalSkill(
    itemId = "file-search",
    method = "search",
    argsJson = """{"query":"test"}"""
)

ApexClient.market.getOverview()
ApexClient.market.getUpdatable()
ApexClient.market.generateMcpConfig(format = "APEX_AGENT")
```

**27 个市场分布**：
- SKILLS：LobeHub / SkillsMP / AgentSkills.cc / AgentSkill.sh
- MCP：Builtin / Smithery / mcp.so / ModelScope / Glama / AISkillStore / MCP星球 / awesome-mcp-servers
- PLUGINS：GitHub / Gitee / GitCode / HuggingFace / Composio / NamiAI / Iflow / AgentHub / AIBase / Airbyte
- MODEL_PLATFORMS：Builtin / ModelScope / AgnesAI / DeepSeek / HuggingFace

---

## Terminal APK（三块结构）

```kotlin
// 普通 Agent 模式
val session1 = ApexClient.terminal.createNormalSession(workingDir = "/sdcard/agent")

// 多 Agent 模式
val session2 = ApexClient.terminal.createMultiAgentSession(
    workingDir = "/sdcard/agent",
    agentId = "builtin.supervisor"
)

// 狂暴模式
val session3 = ApexClient.terminal.createBurstSession(
    workingDir = "/sdcard/agent",
    burstProfile = "BALANCED"
)

ApexClient.terminal.writeInput(sessionId, "ls\n")
ApexClient.terminal.readOutput(sessionId)
ApexClient.terminal.getStreamChannel(sessionId)  // 拿到 LocalSocket 通道名
ApexClient.terminal.destroySession(sessionId)
ApexClient.terminal.listSessions()
ApexClient.terminal.switchMode(sessionId, mode = "BURST")  // NORMAL/MULTI_AGENT/BURST
ApexClient.terminal.startBurstTask(sessionId, taskDescription = "分析日志")
```

**LocalSocket 流式传输**（用于 PTY 高频输出）：
```kotlin
val channelResult = ApexClient.terminal.getStreamChannel(sessionId)
val channelName = (channelResult as BridgeResult.Success).value
// 解析 JSON 拿到 channelName，然后连接 LocalSocket
val client = LocalStreamClient(channelName)
client.connect()
client.send("ls -la\n".toByteArray())
client.receiveFlow().collect { chunk ->
    print(String(chunk))  // 实时渲染终端输出
}
```

---

## Working Files APK

```kotlin
ApexClient.workingFiles.bindFolder(
    folderId = "default",
    displayName = "工作区",
    path = "/sdcard/agent",
    mode = "ALL"  // NORMAL/MULTI_AGENT/BURST/ALL
)
ApexClient.workingFiles.listFolders()
ApexClient.workingFiles.listFoldersForMode("NORMAL")
ApexClient.workingFiles.listFiles("default", relativePath = "src/main/java")
ApexClient.workingFiles.readFile("default", "src/main/java/MainActivity.kt")
ApexClient.workingFiles.writeFile("default", "output.txt", content = "result", append = true)
ApexClient.workingFiles.deleteFile("default", "output.txt")
ApexClient.workingFiles.createDirectory("default", "logs")
ApexClient.workingFiles.exists("default", "config.json")
ApexClient.workingFiles.loadCodeFile("default", "src/main/java/MainActivity.kt")
    // 返回: path/language/lineCount/totalChars/contentPreview/tokenCount
ApexClient.workingFiles.unbindFolder("default")
```

**实时跟随文件变更**：
```kotlin
// 订阅变更（通过 LocalSocket）
val channel = ApexClient.workingFiles.subscribeChanges("default")
val client = LocalStreamClient(channel)
client.connect()
client.receiveFlow().collect { event ->
    // event 是 FileEvent 序列化字节
    println("file changed: ${String(event)}")
}
```

---

## Diagnostics APK

```kotlin
ApexClient.diagnostics.startLogCapture()
ApexClient.diagnostics.getRecentLogs(maxLines = 500)
ApexClient.diagnostics.listLogFiles()
ApexClient.diagnostics.readLogFile("apex-2026-07-02.log")
ApexClient.diagnostics.listCrashReports()
ApexClient.diagnostics.getMemoryStats()  // usedMb/totalMb/maxMb
ApexClient.diagnostics.getNativeMemory()
ApexClient.diagnostics.getApkHealthList()  // 所有 APK 的心跳状态
ApexClient.diagnostics.getSystemInfo()
ApexClient.diagnostics.forceGc()
ApexClient.diagnostics.dumpHeap("apex-heap.hprof")
```

---

## Voice APK

```kotlin
ApexClient.voice.initializeTts(language = "zh-CN")
ApexClient.voice.setTtsParams(speed = 1.0f, pitch = 1.0f, volume = 1.0f)

// 同步朗读（阻塞直到完成）
ApexClient.voice.speak("你好，世界", language = "zh-CN")

// 异步朗读（立即返回）
ApexClient.voice.speakAsync("开始处理任务", language = "zh-CN")
ApexClient.voice.stopSpeaking()
ApexClient.voice.isSpeaking()
ApexClient.voice.listTtsEngines()
ApexClient.voice.listSupportedLanguages()

// 语音识别
ApexClient.voice.startRecognition(language = "zh-CN")
val text = ApexClient.voice.recognizeOnce(language = "zh-CN", timeoutMs = 30_000L)
ApexClient.voice.stopRecognition()
ApexClient.voice.cancelRecognition()
```

---

## 套件级事件总线

```kotlin
// 订阅事件
SuiteEventBus.events.onEach { event ->
    when (event.type) {
        SuiteEventTypes.MODEL_SELECTED -> updateModel(event.payload)
        SuiteEventTypes.WORKING_FOLDER_CHANGED -> refreshFileTree()
        SuiteEventTypes.SKILL_INSTALLED -> refreshSkillList()
        SuiteEventTypes.APK_CRASHED -> showCrashDialog(event.payload)
        SuiteEventTypes.APK_RECOVERED -> dismissCrashDialog()
    }
}.launchIn(scope)

// 发布事件
SuiteEventBus.publish(
    type = "my.custom.event",
    payload = mapOf("key" to "value"),
    sourceApk = "main"
)
```

---

## 强类型服务获取（同进程时零延迟）

```kotlin
// 当本 APK 与目标 APK 同进程时，直接拿到 Facade 实例
val engineFacade = TypedServiceRegistry.get<EngineServiceFacade>()
if (engineFacade != null) {
    // 零延迟 JVM 方法调用
    val result = engineFacade.executeShell("ls")
} else {
    // 跨进程降级：走 ApexClient.engine.xxx（AIDL）
    val result = ApexClient.engine.executeShell("ls")
}
```

**Facade 类全名**：
- `com.apex.apk.engine.EngineServiceFacade`
- `com.apex.apk.rage.RageServiceFacade`
- `com.apex.apk.multiagent.MultiAgentServiceFacade`
- `com.apex.apk.workflow.WorkflowServiceFacade`
- `com.apex.apk.market.MarketServiceFacade`
- `com.apex.apk.terminal.TerminalServiceFacade`
- `com.apex.apk.workingfiles.WorkingFilesServiceFacade`
- `com.apex.apk.diagnostics.DiagnosticsServiceFacade`
- `com.apex.apk.voice.VoiceServiceFacade`

---

## BridgeResult 处理模式

```kotlin
val result = ApexClient.engine.executeShell("ls")
when (result) {
    is BridgeResult.Success -> {
        val json = result.value  // JSON 字符串
        val parsed = Json.parseToJsonElement(json).jsonObject
        if (parsed["success"]?.jsonPrimitive?.boolean == true) {
            val data = parsed["data"]?.jsonObject
            val stdout = data?.get("stdout")?.jsonPrimitive?.content
            println(stdout)
        }
    }
    is BridgeResult.Failure -> {
        val err = result.error
        when (err.code) {
            BridgeError.CODE_APK_NOT_INSTALLED -> promptInstallApk("engine")
            BridgeError.CODE_CALL_TIMEOUT -> showTimeoutToast()
            else -> showError(err.message)
        }
    }
}
```
