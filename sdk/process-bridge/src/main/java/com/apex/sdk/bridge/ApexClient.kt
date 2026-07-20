package com.apex.sdk.bridge

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkDependencyManager
import com.apex.sdk.common.ApkDescriptors
import com.apex.sdk.common.BridgeResult
import android.content.Context
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

    /** 自改源码客户端（Agent 自改源码子系统，per AGENT_SELF_MODIFY_SPEC §8.2）。 */
    val selfModify: SelfModifyClient = SelfModifyClient()

    // ============================================================
    // Engine
    // ============================================================

    class EngineClient {
        /** 检查 Engine APK 是否已安装（必须组件）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.ENGINE)

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
        /** 检查 Rage APK 是否已安装（可选组件，使用狂暴模式前必须安装）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.RAGE)

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

        // ===== P0 增强：4 种执行模式 =====
        suspend fun executeBatch(taskDescriptions: List<String>, skillId: String? = null, preset: String = "BALANCED"): BridgeResult<String> {
            val args = mutableMapOf(
                "tasks" to taskDescriptions.joinToString("\n"),
                "preset" to preset
            )
            if (skillId != null) args["skillId"] = skillId
            return invoke("rage/executeBatch", args, "rage")
        }
        suspend fun executeWithDependencyGraph(
            tasks: List<Pair<String, String>>,  // (taskId, description)
            dependencies: List<Pair<String, String>>,  // (taskId, dependsOn)
            strategy: String = "SKIP_ON_FAILURE",
            skillId: String? = null
        ): BridgeResult<String> {
            val tasksStr = tasks.joinToString("\n") { (id, desc) -> "$id|$desc" }
            val depsStr = dependencies.joinToString(";") { (id, dep) -> "$id|$dep" }
            val args = mutableMapOf("tasks" to tasksStr, "dependencies" to depsStr, "strategy" to strategy)
            if (skillId != null) args["skillId"] = skillId
            return invoke("rage/executeWithDependencyGraph", args, "rage")
        }
        suspend fun executeWithChain(
            initialTask: String,
            steps: List<Pair<String, String>>,  // (stepName, description)
            skillId: String? = null
        ): BridgeResult<String> {
            val stepsStr = steps.joinToString("\n") { (name, desc) -> "$name|$desc" }
            val args = mutableMapOf("initialTask" to initialTask, "steps" to stepsStr)
            if (skillId != null) args["skillId"] = skillId
            return invoke("rage/executeWithChain", args, "rage")
        }
        suspend fun executeAsync(taskDescription: String, skillId: String? = null): BridgeResult<String> {
            val args = mutableMapOf("taskDescription" to taskDescription)
            if (skillId != null) args["skillId"] = skillId
            return invoke("rage/executeAsync", args, "rage")
        }
        suspend fun awaitAsyncTask(taskId: String, timeoutMs: Long = 60_000L): BridgeResult<String> =
            invoke("rage/awaitAsyncTask", mapOf("taskId" to taskId, "timeoutMs" to timeoutMs.toString()), "rage")
        suspend fun cancelAsyncTask(taskId: String): BridgeResult<String> =
            invoke("rage/cancelAsyncTask", mapOf("taskId" to taskId), "rage")

        // ===== P1 增强：任务队列 =====
        suspend fun enqueueTask(taskDescription: String, priority: String = "NORMAL", skillId: String? = null): BridgeResult<String> {
            val args = mutableMapOf("taskDescription" to taskDescription, "priority" to priority)
            if (skillId != null) args["skillId"] = skillId
            return invoke("rage/enqueueTask", args, "rage")
        }
        suspend fun cancelQueuedTask(taskId: String): BridgeResult<String> =
            invoke("rage/cancelQueuedTask", mapOf("taskId" to taskId), "rage")
        suspend fun peekQueue(): BridgeResult<String> = invoke("rage/peekQueue", emptyMap(), "rage")
        suspend fun pendingTaskCount(): BridgeResult<String> = invoke("rage/pendingTaskCount", emptyMap(), "rage")
        suspend fun clearQueue(): BridgeResult<String> = invoke("rage/clearQueue", emptyMap(), "rage")
        suspend fun getQueueSnapshot(): BridgeResult<String> = invoke("rage/getQueueSnapshot", emptyMap(), "rage")

        // ===== P0 增强：完整断点续传 =====
        suspend fun saveCheckpoint(taskId: String, completedSteps: List<String>, totalSteps: Int, intermediateResult: String? = null): BridgeResult<String> {
            val args = mutableMapOf(
                "taskId" to taskId,
                "completedSteps" to completedSteps.joinToString(","),
                "totalSteps" to totalSteps.toString()
            )
            if (intermediateResult != null) args["intermediateResult"] = intermediateResult
            return invoke("rage/saveCheckpoint", args, "rage")
        }
        suspend fun loadCheckpoint(taskId: String): BridgeResult<String> =
            invoke("rage/loadCheckpoint", mapOf("taskId" to taskId), "rage")
        suspend fun resumeFromCheckpoint(taskId: String): BridgeResult<String> =
            invoke("rage/resumeFromCheckpoint", mapOf("taskId" to taskId), "rage")
        suspend fun listIncompleteTasks(): BridgeResult<String> = invoke("rage/listIncompleteTasks", emptyMap(), "rage")
        suspend fun canResume(taskId: String): BridgeResult<String> =
            invoke("rage/canResume", mapOf("taskId" to taskId), "rage")
        suspend fun getResumePoint(taskId: String): BridgeResult<String> =
            invoke("rage/getResumePoint", mapOf("taskId" to taskId), "rage")
        suspend fun deleteCheckpoint(taskId: String): BridgeResult<String> =
            invoke("rage/deleteCheckpoint", mapOf("taskId" to taskId), "rage")
        suspend fun clearCheckpoints(): BridgeResult<String> = invoke("rage/clearCheckpoints", emptyMap(), "rage")

        // ===== P2 增强：技能多维查询 =====
        suspend fun getSkillsByTag(tag: String): BridgeResult<String> =
            invoke("rage/getSkillsByTag", mapOf("tag" to tag), "rage")
        suspend fun getSkillsByCapability(capability: String): BridgeResult<String> =
            invoke("rage/getSkillsByCapability", mapOf("capability" to capability), "rage")
        suspend fun unloadSkill(skillId: String): BridgeResult<String> =
            invoke("rage/unloadSkill", mapOf("skillId" to skillId), "rage")
        suspend fun getSkillCount(): BridgeResult<String> = invoke("rage/getSkillCount", emptyMap(), "rage")
        suspend fun isSkillLoaded(skillId: String): BridgeResult<String> =
            invoke("rage/isSkillLoaded", mapOf("skillId" to skillId), "rage")

        // ===== P2 增强：配置管理 =====
        suspend fun updateConfig(
            maxConcurrency: Int? = null, defaultTimeoutMs: Long? = null,
            enableAdaptiveOptimization: Boolean? = null,
            enableMetricsCollection: Boolean? = null, memoryBudgetMb: Int? = null
        ): BridgeResult<String> {
            val args = mutableMapOf<String, String>()
            if (maxConcurrency != null) args["maxConcurrency"] = maxConcurrency.toString()
            if (defaultTimeoutMs != null) args["defaultTimeoutMs"] = defaultTimeoutMs.toString()
            if (enableAdaptiveOptimization != null) args["enableAdaptiveOptimization"] = enableAdaptiveOptimization.toString()
            if (enableMetricsCollection != null) args["enableMetricsCollection"] = enableMetricsCollection.toString()
            if (memoryBudgetMb != null) args["memoryBudgetMb"] = memoryBudgetMb.toString()
            return invoke("rage/updateConfig", args, "rage")
        }
        suspend fun getCurrentConfig(): BridgeResult<String> = invoke("rage/getCurrentConfig", emptyMap(), "rage")

        // ===== P1 增强：指标与状态 =====
        suspend fun observeNextMetrics(): BridgeResult<String> = invoke("rage/observeNextMetrics", emptyMap(), "rage")
        suspend fun resetMetrics(): BridgeResult<String> = invoke("rage/resetMetrics", emptyMap(), "rage")
        suspend fun getKernelState(): BridgeResult<String> = invoke("rage/getKernelState", emptyMap(), "rage")
        suspend fun getHealthStatus(): BridgeResult<String> = invoke("rage/getHealthStatus", emptyMap(), "rage")

        // ===== P2 增强：基础设施 =====
        suspend fun clearResultCache(prefix: String? = null): BridgeResult<String> {
            val args = if (prefix != null) mapOf("prefix" to prefix) else emptyMap()
            return invoke("rage/clearResultCache", args, "rage")
        }
        suspend fun getResultCacheStats(): BridgeResult<String> = invoke("rage/getResultCacheStats", emptyMap(), "rage")
        suspend fun setSkillSelectionStrategy(strategy: String): BridgeResult<String> =
            invoke("rage/setSkillSelectionStrategy", mapOf("strategy" to strategy), "rage")

        // ===== AR/VR 可视化 =====
        suspend fun enableSpatialVisualization(): BridgeResult<String> =
            invoke("rage/enableSpatialVisualization", emptyMap(), "rage")
    }

    // ============================================================
    // Multi-Agent
    // ============================================================

    class MultiAgentClient {
        /** 检查 Multi-Agent APK 是否已安装（可选组件，使用多 Agent 协作前必须安装）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.MULTI_AGENT)

        suspend fun registerAgent(agentId: String, displayName: String, role: String = "WORKER"): BridgeResult<String> =
            invoke("multiagent/registerAgent", mapOf(
                "agentId" to agentId,
                "displayName" to displayName,
                "role" to role
            ), "multi-agent")

        suspend fun runCollaboration(
            mode: String, agentIds: List<String>, initialPrompt: String,
            maxRounds: Int = 10, timeoutMs: Long = 60_000L,
            moderatorId: String? = null,
            consensusThreshold: Float = 1.0f,
            votingThreshold: Float = 0.5f,
            continueOnFailure: Boolean = false
        ): BridgeResult<String> {
            val args = mutableMapOf(
                "mode" to mode,
                "agentIds" to agentIds.joinToString(","),
                "initialPrompt" to initialPrompt,
                "maxRounds" to maxRounds.toString(),
                "timeoutMs" to timeoutMs.toString(),
                "consensusThreshold" to consensusThreshold.toString(),
                "votingThreshold" to votingThreshold.toString(),
                "continueOnFailure" to continueOnFailure.toString()
            )
            if (moderatorId != null) args["moderatorId"] = moderatorId
            return invoke("multiagent/runCollaboration", args, "multi-agent")
        }

        // ===== 增强：协作推荐 + 模板 =====
        suspend fun recommendCollaboration(taskDescription: String): BridgeResult<String> =
            invoke("multiagent/recommendCollaboration", mapOf("taskDescription" to taskDescription), "multi-agent")
        suspend fun listTemplates(): BridgeResult<String> =
            invoke("multiagent/listTemplates", emptyMap(), "multi-agent")

        // ===== 增强：Agent 查询 =====
        suspend fun findAgentsByCapability(capability: String): BridgeResult<String> =
            invoke("multiagent/findAgentsByCapability", mapOf("capability" to capability), "multi-agent")
        suspend fun findAgentsByRole(role: String): BridgeResult<String> =
            invoke("multiagent/findAgentsByRole", mapOf("role" to role), "multi-agent")

        // ===== 增强：消息传递 =====
        suspend fun sendMessage(sessionId: String, fromAgentId: String, toAgentId: String, content: String, type: String = "DIRECT"): BridgeResult<String> =
            invoke("multiagent/sendMessage", mapOf(
                "sessionId" to sessionId, "fromAgentId" to fromAgentId,
                "toAgentId" to toAgentId, "content" to content, "type" to type
            ), "multi-agent")
        suspend fun getSessionMessages(sessionId: String): BridgeResult<String> =
            invoke("multiagent/getSessionMessages", mapOf("sessionId" to sessionId), "multi-agent")

        // ===== 增强：Blackboard =====
        suspend fun blackboardKeys(): BridgeResult<String> =
            invoke("multiagent/blackboardKeys", emptyMap(), "multi-agent")
        suspend fun clearBlackboard(): BridgeResult<String> =
            invoke("multiagent/clearBlackboard", emptyMap(), "multi-agent")

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
        /** 检查 Workflow APK 是否已安装（可选组件，使用工作流前必须安装）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.WORKFLOW)

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
        /** 检查 Market APK 是否已安装（必须组件）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.MARKET)

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

        suspend fun getOverview(): BridgeResult<String> = invoke("market/getOverview", emptyMap(), "market")
        suspend fun getUpdatable(): BridgeResult<String> = invoke("market/getUpdatable", emptyMap(), "market")
        suspend fun generateMcpConfig(format: String = "APEX_AGENT"): BridgeResult<String> =
            invoke("market/generateMcpConfig", mapOf("format" to format), "market")

        // 套件 APK 商店 — 管理其他 APK 的安装
        suspend fun listSuiteApks(): BridgeResult<String> = invoke("market/listSuiteApks", emptyMap(), "market")
        suspend fun installSuiteApk(apkId: String, apkFileUri: String? = null): BridgeResult<String> {
            val args = if (apkFileUri != null) mapOf("apkId" to apkId, "apkFileUri" to apkFileUri)
                       else mapOf("apkId" to apkId)
            return invoke("market/installSuiteApk", args, "market")
        }
        suspend fun launchSuiteApk(apkId: String): BridgeResult<String> =
            invoke("market/launchSuiteApk", mapOf("apkId" to apkId), "market")
        suspend fun getSuiteInstallSummary(): BridgeResult<String> =
            invoke("market/getSuiteInstallSummary", emptyMap(), "market")
        suspend fun checkRequiredApks(): BridgeResult<String> =
            invoke("market/checkRequiredApks", emptyMap(), "market")

        // ===== Apex 独有增强：LLM 调用 =====
        suspend fun invokeModel(
            provider: String, modelName: String, prompt: String,
            maxTokens: Int = 2048, systemPrompt: String? = null, temperature: Float = 0.7f
        ): BridgeResult<String> {
            val args = mutableMapOf(
                "provider" to provider, "modelName" to modelName, "prompt" to prompt,
                "maxTokens" to maxTokens.toString(), "temperature" to temperature.toString()
            )
            if (systemPrompt != null) args["systemPrompt"] = systemPrompt
            return invoke("market/invokeModel", args, "market")
        }
        suspend fun listAvailableProviders(): BridgeResult<String> =
            invoke("market/listAvailableProviders", emptyMap(), "market")
        suspend fun isProviderAvailable(provider: String): BridgeResult<String> =
            invoke("market/isProviderAvailable", mapOf("provider" to provider), "market")

        // ===== Apex 独有增强：本地技能调用 =====
        suspend fun invokeLocalSkill(itemId: String, method: String, argsJson: String = "{}"): BridgeResult<String> =
            invoke("market/invokeLocalSkill", mapOf("itemId" to itemId, "method" to method, "argsJson" to argsJson), "market")
        suspend fun listLocalSkillMethods(itemId: String): BridgeResult<String> =
            invoke("market/listLocalSkillMethods", mapOf("itemId" to itemId), "market")
        suspend fun getInstalledItemMetadata(itemId: String): BridgeResult<String> =
            invoke("market/getInstalledItemMetadata", mapOf("itemId" to itemId), "market")

        // ===== Apex 独有增强：搜索增强 =====
        suspend fun searchInMarket(marketId: String, category: String, query: String = "", limit: Int = 50): BridgeResult<String> =
            invoke("market/searchInMarket", mapOf("marketId" to marketId, "category" to category, "query" to query, "limit" to limit.toString()), "market")

        // ===== Apex 独有增强：收藏夹 =====
        suspend fun addFavorite(itemId: String, category: String, name: String, description: String = "", marketId: String = "", version: String = "", note: String = ""): BridgeResult<String> {
            val args = mutableMapOf("itemId" to itemId, "category" to category, "name" to name)
            if (description.isNotBlank()) args["description"] = description
            if (marketId.isNotBlank()) args["marketId"] = marketId
            if (version.isNotBlank()) args["version"] = version
            if (note.isNotBlank()) args["note"] = note
            return invoke("market/addFavorite", args, "market")
        }
        suspend fun removeFavorite(itemId: String): BridgeResult<String> =
            invoke("market/removeFavorite", mapOf("itemId" to itemId), "market")
        suspend fun toggleFavorite(itemId: String, category: String, name: String): BridgeResult<String> =
            invoke("market/toggleFavorite", mapOf("itemId" to itemId, "category" to category, "name" to name), "market")
        suspend fun isFavorite(itemId: String): BridgeResult<String> =
            invoke("market/isFavorite", mapOf("itemId" to itemId), "market")
        suspend fun listFavorites(category: String? = null): BridgeResult<String> {
            val args = if (category != null) mapOf("category" to category) else emptyMap()
            return invoke("market/listFavorites", args, "market")
        }
        suspend fun searchFavorites(query: String): BridgeResult<String> =
            invoke("market/searchFavorites", mapOf("query" to query), "market")
        suspend fun updateFavoriteNote(itemId: String, note: String): BridgeResult<String> =
            invoke("market/updateFavoriteNote", mapOf("itemId" to itemId, "note" to note), "market")
        suspend fun clearFavorites(): BridgeResult<String> =
            invoke("market/clearFavorites", emptyMap(), "market")
        suspend fun favoritesCount(): BridgeResult<String> =
            invoke("market/favoritesCount", emptyMap(), "market")

        // ===== Apex 独有增强：使用统计 =====
        suspend fun getItemStats(itemId: String): BridgeResult<String> =
            invoke("market/getItemStats", mapOf("itemId" to itemId), "market")
        suspend fun getRecentlyUsed(limit: Int = 20): BridgeResult<String> =
            invoke("market/getRecentlyUsed", mapOf("limit" to limit.toString()), "market")
        suspend fun getMostUsed(limit: Int = 20): BridgeResult<String> =
            invoke("market/getMostUsed", mapOf("limit" to limit.toString()), "market")
        suspend fun getTotalUsageStats(): BridgeResult<String> =
            invoke("market/getTotalUsageStats", emptyMap(), "market")
        suspend fun getUsageByCategory(): BridgeResult<String> =
            invoke("market/getUsageByCategory", emptyMap(), "market")
        suspend fun getRecentEvents(limit: Int = 100): BridgeResult<String> =
            invoke("market/getRecentEvents", mapOf("limit" to limit.toString()), "market")
        suspend fun recordView(itemId: String, name: String, category: String): BridgeResult<String> =
            invoke("market/recordView", mapOf("itemId" to itemId, "name" to name, "category" to category), "market")
        suspend fun clearUsageStats(): BridgeResult<String> =
            invoke("market/clearUsageStats", emptyMap(), "market")

        // ===== Apex 独有增强：缓存管理 =====
        suspend fun clearCacheForMarket(marketId: String): BridgeResult<String> =
            invoke("market/clearCacheForMarket", mapOf("marketId" to marketId), "market")
        suspend fun clearAllCache(): BridgeResult<String> =
            invoke("market/clearAllCache", emptyMap(), "market")
        suspend fun cleanExpiredCache(): BridgeResult<String> =
            invoke("market/cleanExpiredCache", emptyMap(), "market")
        suspend fun getCacheStats(): BridgeResult<String> =
            invoke("market/getCacheStats", emptyMap(), "market")

        // ===== Apex 独有增强：批量操作 + 更新检查 =====
        suspend fun batchInstall(items: List<Triple<String, String, String?>>): BridgeResult<String> {
            // 编码为 "itemId,cat,path;itemId,cat,;..."
            val itemsStr = items.joinToString(";") { (id, cat, path) ->
                "$id,$cat,${path ?: ""}"
            }
            return invoke("market/batchInstall", mapOf("items" to itemsStr), "market")
        }
        suspend fun batchUninstall(itemIds: List<String>): BridgeResult<String> =
            invoke("market/batchUninstall", mapOf("itemIds" to itemIds.joinToString(",")), "market")
        suspend fun checkForUpdates(): BridgeResult<String> =
            invoke("market/checkForUpdates", emptyMap(), "market")
        suspend fun updateAll(): BridgeResult<String> =
            invoke("market/updateAll", emptyMap(), "market")
        suspend fun refreshAllMarkets(): BridgeResult<String> =
            invoke("market/refreshAllMarkets", emptyMap(), "market")
        suspend fun refreshMarket(marketId: String): BridgeResult<String> =
            invoke("market/refreshMarket", mapOf("marketId" to marketId), "market")
        suspend fun diagnose(): BridgeResult<String> =
            invoke("market/diagnose", emptyMap(), "market")
    }

    // ============================================================
    // Terminal
    // ============================================================

    class TerminalClient {
        /** 检查 Terminal APK 是否已安装（必须组件）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.TERMINAL)

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
        /** 检查 Working Files APK 是否已安装（必须组件）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.WORKING_FILES)

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

        suspend fun bindFolderByUri(folderId: String, displayName: String, uri: String, mode: String = "ALL"): BridgeResult<String> =
            invoke("workingfiles/bindFolderByUri", mapOf(
                "folderId" to folderId, "displayName" to displayName,
                "uri" to uri, "mode" to mode
            ), "working-files")

        suspend fun listFilesByUri(folderId: String, relativePath: String = ""): BridgeResult<String> =
            invoke("workingfiles/listFilesByUri", mapOf("folderId" to folderId, "relativePath" to relativePath), "working-files")

        suspend fun readFileByUri(folderId: String, relativePath: String): BridgeResult<String> =
            invoke("workingfiles/readFileByUri", mapOf("folderId" to folderId, "relativePath" to relativePath), "working-files")

        suspend fun writeFileByUri(folderId: String, relativePath: String, content: String, append: Boolean = false): BridgeResult<String> =
            invoke("workingfiles/writeFileByUri", mapOf(
                "folderId" to folderId, "relativePath" to relativePath,
                "content" to content, "append" to append.toString()
            ), "working-files")

        suspend fun subscribeChanges(folderId: String): BridgeResult<String> =
            invoke("workingfiles/subscribeChanges", mapOf("folderId" to folderId), "working-files")

        suspend fun unsubscribeChanges(folderId: String): BridgeResult<String> =
            invoke("workingfiles/unsubscribeChanges", mapOf("folderId" to folderId), "working-files")

        // ===== VSCode 式代码查看 + Agent 执行流程 + 回退 =====

        suspend fun getFileTree(rootPath: String, maxDepth: Int = 10): BridgeResult<String> =
            invoke("workingfiles/getFileTree", mapOf("rootPath" to rootPath, "maxDepth" to maxDepth.toString()), "working-files")

        suspend fun loadCodeFileWithTokens(filePath: String): BridgeResult<String> =
            invoke("workingfiles/loadCodeFileWithTokens", mapOf("filePath" to filePath), "working-files")

        suspend fun takeSnapshot(filePath: String, rootPath: String, description: String = "", source: String = "MANUAL"): BridgeResult<String> =
            invoke("workingfiles/takeSnapshot", mapOf(
                "filePath" to filePath, "rootPath" to rootPath,
                "description" to description, "source" to source
            ), "working-files")

        suspend fun writeWithSnapshot(
            filePath: String, rootPath: String, content: String,
            agentId: String, agentName: String, sessionId: String,
            description: String
        ): BridgeResult<String> = invoke("workingfiles/writeWithSnapshot", mapOf(
            "filePath" to filePath, "rootPath" to rootPath, "content" to content,
            "agentId" to agentId, "agentName" to agentName, "sessionId" to sessionId,
            "description" to description
        ), "working-files")

        suspend fun listSnapshots(filePath: String): BridgeResult<String> =
            invoke("workingfiles/listSnapshots", mapOf("filePath" to filePath), "working-files")

        suspend fun getSnapshot(snapshotId: String): BridgeResult<String> =
            invoke("workingfiles/getSnapshot", mapOf("snapshotId" to snapshotId), "working-files")

        suspend fun getLatestSnapshot(filePath: String): BridgeResult<String> =
            invoke("workingfiles/getLatestSnapshot", mapOf("filePath" to filePath), "working-files")

        /** 回退文件到指定快照。 */
        suspend fun restoreSnapshot(snapshotId: String, operator: String = "user"): BridgeResult<String> =
            invoke("workingfiles/restoreSnapshot", mapOf("snapshotId" to snapshotId, "operator" to operator), "working-files")

        suspend fun deleteAllSnapshots(filePath: String): BridgeResult<String> =
            invoke("workingfiles/deleteAllSnapshots", mapOf("filePath" to filePath), "working-files")

        suspend fun computeDiff(oldContent: String, newContent: String): BridgeResult<String> =
            invoke("workingfiles/computeDiff", mapOf("oldContent" to oldContent, "newContent" to newContent), "working-files")

        suspend fun diffSnapshots(beforeId: String, afterId: String): BridgeResult<String> =
            invoke("workingfiles/diffSnapshots", mapOf("beforeId" to beforeId, "afterId" to afterId), "working-files")

        suspend fun diffWithCurrent(snapshotId: String): BridgeResult<String> =
            invoke("workingfiles/diffWithCurrent", mapOf("snapshotId" to snapshotId), "working-files")

        suspend fun diffForStep(stepId: String): BridgeResult<String> =
            invoke("workingfiles/diffForStep", mapOf("stepId" to stepId), "working-files")

        // Agent 执行流程
        suspend fun startAgentSession(agentId: String, agentName: String, taskDescription: String, mode: String = "NORMAL"): BridgeResult<String> =
            invoke("workingfiles/startAgentSession", mapOf(
                "agentId" to agentId, "agentName" to agentName,
                "taskDescription" to taskDescription, "mode" to mode
            ), "working-files")

        suspend fun recordAgentStep(
            sessionId: String, agentId: String, agentName: String,
            type: String, title: String, description: String = "",
            affectedFiles: List<String> = emptyList(), snapshotIds: List<String> = emptyList(),
            isSuccess: Boolean = true, errorMessage: String? = null,
            durationMs: Long = 0
        ): BridgeResult<String> {
            val args = mutableMapOf(
                "sessionId" to sessionId, "agentId" to agentId, "agentName" to agentName,
                "type" to type, "title" to title, "description" to description,
                "isSuccess" to isSuccess.toString(), "durationMs" to durationMs.toString(),
                "affectedFiles" to affectedFiles.joinToString(","),
                "snapshotIds" to snapshotIds.joinToString(",")
            )
            if (errorMessage != null) args["errorMessage"] = errorMessage
            return invoke("workingfiles/recordAgentStep", args, "working-files")
        }

        suspend fun finishAgentSession(sessionId: String, finalResult: String? = null, status: String = "COMPLETED"): BridgeResult<String> {
            val args = mutableMapOf("sessionId" to sessionId, "status" to status)
            if (finalResult != null) args["finalResult"] = finalResult
            return invoke("workingfiles/finishAgentSession", args, "working-files")
        }

        suspend fun getAgentFlow(sessionId: String): BridgeResult<String> =
            invoke("workingfiles/getAgentFlow", mapOf("sessionId" to sessionId), "working-files")

        suspend fun listAgentSessions(): BridgeResult<String> =
            invoke("workingfiles/listAgentSessions", emptyMap(), "working-files")

        suspend fun listActiveAgentSessions(): BridgeResult<String> =
            invoke("workingfiles/listActiveAgentSessions", emptyMap(), "working-files")

        suspend fun listAgentSteps(sessionId: String): BridgeResult<String> =
            invoke("workingfiles/listAgentSteps", mapOf("sessionId" to sessionId), "working-files")

        suspend fun listAgentStepsForFile(sessionId: String, filePath: String): BridgeResult<String> =
            invoke("workingfiles/listAgentStepsForFile", mapOf("sessionId" to sessionId, "filePath" to filePath), "working-files")

        suspend fun deleteAgentSession(sessionId: String): BridgeResult<String> =
            invoke("workingfiles/deleteAgentSession", mapOf("sessionId" to sessionId), "working-files")

        suspend fun getSnapshotStats(): BridgeResult<String> =
            invoke("workingfiles/getSnapshotStats", emptyMap(), "working-files")

        // ===== Apex 独有增强：虚拟分支 =====
        suspend fun createBranch(name: String, filePath: String, baseSnapshotId: String? = null, description: String = "", agentId: String? = null): BridgeResult<String> {
            val args = mutableMapOf("name" to name, "filePath" to filePath, "description" to description)
            if (baseSnapshotId != null) args["baseSnapshotId"] = baseSnapshotId
            if (agentId != null) args["agentId"] = agentId
            return invoke("workingfiles/createBranch", args, "working-files")
        }
        suspend fun switchToBranch(filePath: String, branchId: String): BridgeResult<String> =
            invoke("workingfiles/switchToBranch", mapOf("filePath" to filePath, "branchId" to branchId), "working-files")
        suspend fun switchToMain(filePath: String): BridgeResult<String> =
            invoke("workingfiles/switchToMain", mapOf("filePath" to filePath), "working-files")
        suspend fun mergeBranch(branchId: String, strategy: String = "MERGE_MANUAL"): BridgeResult<String> =
            invoke("workingfiles/mergeBranch", mapOf("branchId" to branchId, "strategy" to strategy), "working-files")
        suspend fun discardBranch(branchId: String): BridgeResult<String> =
            invoke("workingfiles/discardBranch", mapOf("branchId" to branchId), "working-files")
        suspend fun listBranches(filePath: String): BridgeResult<String> =
            invoke("workingfiles/listBranches", mapOf("filePath" to filePath), "working-files")
        suspend fun listActiveBranches(filePath: String): BridgeResult<String> =
            invoke("workingfiles/listActiveBranches", mapOf("filePath" to filePath), "working-files")
        suspend fun getActiveBranch(filePath: String): BridgeResult<String> =
            invoke("workingfiles/getActiveBranch", mapOf("filePath" to filePath), "working-files")
        suspend fun getBranchDiff(branchId: String): BridgeResult<String> =
            invoke("workingfiles/getBranchDiff", mapOf("branchId" to branchId), "working-files")
        suspend fun deleteBranch(branchId: String): BridgeResult<String> =
            invoke("workingfiles/deleteBranch", mapOf("branchId" to branchId), "working-files")

        // ===== Apex 独有增强：智能回退 =====
        suspend fun analyzeRevert(sessionId: String, stepId: String): BridgeResult<String> =
            invoke("workingfiles/analyzeRevert", mapOf("sessionId" to sessionId, "stepId" to stepId), "working-files")
        suspend fun executeSmartRevert(sessionId: String, stepId: String, operator: String = "user"): BridgeResult<String> =
            invoke("workingfiles/executeSmartRevert", mapOf("sessionId" to sessionId, "stepId" to stepId, "operator" to operator), "working-files")

        // ===== Apex 独有增强：语义 Diff =====
        suspend fun analyzeSemanticDiff(diffJson: String): BridgeResult<String> =
            invoke("workingfiles/analyzeSemanticDiff", mapOf("diffJson" to diffJson), "working-files")
        suspend fun semanticDiffSnapshots(beforeId: String, afterId: String): BridgeResult<String> =
            invoke("workingfiles/semanticDiffSnapshots", mapOf("beforeId" to beforeId, "afterId" to afterId), "working-files")

        // ===== Apex 独有增强：时间机器 =====
        suspend fun loadTimeMachine(filePath: String): BridgeResult<String> =
            invoke("workingfiles/loadTimeMachine", mapOf("filePath" to filePath), "working-files")
        suspend fun timeMachineJumpTo(index: Int): BridgeResult<String> =
            invoke("workingfiles/timeMachineJumpTo", mapOf("index" to index.toString()), "working-files")
        suspend fun timeMachineJumpToTimestamp(timestamp: Long): BridgeResult<String> =
            invoke("workingfiles/timeMachineJumpToTimestamp", mapOf("timestamp" to timestamp.toString()), "working-files")
        suspend fun timeMachineNext(): BridgeResult<String> =
            invoke("workingfiles/timeMachineNext", emptyMap(), "working-files")
        suspend fun timeMachinePrevious(): BridgeResult<String> =
            invoke("workingfiles/timeMachinePrevious", emptyMap(), "working-files")

        // ===== Apex 独有增强：多 Agent 冲突检测 =====
        suspend fun acquireFileLock(filePath: String, agentId: String, type: String = "WRITE_LOCK", ttlMs: Long = 30000L): BridgeResult<String> =
            invoke("workingfiles/acquireFileLock", mapOf("filePath" to filePath, "agentId" to agentId, "type" to type, "ttlMs" to ttlMs.toString()), "working-files")
        suspend fun releaseFileLock(token: String): BridgeResult<String> =
            invoke("workingfiles/releaseFileLock", mapOf("token" to token), "working-files")
        suspend fun releaseAllLocksForAgent(agentId: String): BridgeResult<String> =
            invoke("workingfiles/releaseAllLocksForAgent", mapOf("agentId" to agentId), "working-files")
        suspend fun isFileLocked(filePath: String): BridgeResult<String> =
            invoke("workingfiles/isFileLocked", mapOf("filePath" to filePath), "working-files")
        suspend fun getFileLockStatus(filePath: String): BridgeResult<String> =
            invoke("workingfiles/getFileLockStatus", mapOf("filePath" to filePath), "working-files")
        suspend fun detectConflict(filePath: String, agentId: String): BridgeResult<String> =
            invoke("workingfiles/detectConflict", mapOf("filePath" to filePath, "agentId" to agentId), "working-files")
        suspend fun listLockedFiles(): BridgeResult<String> =
            invoke("workingfiles/listLockedFiles", emptyMap(), "working-files")

        // ===== Apex 独有增强：变更回放 =====
        suspend fun loadReplayer(sessionId: String): BridgeResult<String> =
            invoke("workingfiles/loadReplayer", mapOf("sessionId" to sessionId), "working-files")
        suspend fun playReplay(speed: Float = 1.0f): BridgeResult<String> =
            invoke("workingfiles/playReplay", mapOf("speed" to speed.toString()), "working-files")
        suspend fun pauseReplay(): BridgeResult<String> =
            invoke("workingfiles/pauseReplay", emptyMap(), "working-files")
        suspend fun resetReplay(): BridgeResult<String> =
            invoke("workingfiles/resetReplay", emptyMap(), "working-files")
        suspend fun jumpReplayTo(stepIndex: Int): BridgeResult<String> =
            invoke("workingfiles/jumpReplayTo", mapOf("stepIndex" to stepIndex.toString()), "working-files")
        suspend fun replayNextStep(): BridgeResult<String> =
            invoke("workingfiles/replayNextStep", emptyMap(), "working-files")
        suspend fun replayPreviousStep(): BridgeResult<String> =
            invoke("workingfiles/replayPreviousStep", emptyMap(), "working-files")
        suspend fun setReplaySpeed(speed: Float): BridgeResult<String> =
            invoke("workingfiles/setReplaySpeed", mapOf("speed" to speed.toString()), "working-files")
        suspend fun replayProgress(): BridgeResult<String> =
            invoke("workingfiles/replayProgress", emptyMap(), "working-files")
    }

    // ============================================================
    // Diagnostics
    // ============================================================

    class DiagnosticsClient {
        /** 检查 Diagnostics APK 是否已安装（调试组件，仅诊断场景使用）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.DIAGNOSTICS)

        suspend fun startLogCapture(): BridgeResult<String> = invoke("diagnostics/startLogCapture", emptyMap(), "main")
        suspend fun getRecentLogs(maxLines: Int = 500): BridgeResult<String> =
            invoke("diagnostics/getRecentLogs", mapOf("maxLines" to maxLines.toString()), "main")
        suspend fun listLogFiles(): BridgeResult<String> = invoke("diagnostics/listLogFiles", emptyMap(), "main")
        suspend fun listCrashReports(): BridgeResult<String> = invoke("diagnostics/listCrashReports", emptyMap(), "main")
        suspend fun getMemoryStats(): BridgeResult<String> = invoke("diagnostics/getMemoryStats", emptyMap(), "main")
        suspend fun getApkHealthList(): BridgeResult<String> = invoke("diagnostics/getApkHealthList", emptyMap(), "main")
        suspend fun getSystemInfo(): BridgeResult<String> = invoke("diagnostics/getSystemInfo", emptyMap(), "main")
        suspend fun forceGc(): BridgeResult<String> = invoke("diagnostics/forceGc", emptyMap(), "main")
        suspend fun dumpHeap(fileName: String = "apex-heap.hprof"): BridgeResult<String> =
            invoke("diagnostics/dumpHeap", mapOf("fileName" to fileName), "main")
    }

    // ============================================================
    // Voice
    // ============================================================

    class VoiceClient {
        /** 检查 Voice APK 是否已安装（可选组件，使用语音功能前必须安装）。 */
        fun isAvailable(context: Context): Boolean =
            ApkDependencyManager.isApkInstalled(context, ApexSuite.ApkId.VOICE)

        suspend fun initializeTts(language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/initializeTts", mapOf("language" to language), "voice")

        suspend fun setTtsParams(speed: Float = 1.0f, pitch: Float = 1.0f, volume: Float = 1.0f): BridgeResult<String> =
            invoke("voice/setTtsParams", mapOf(
                "speed" to speed.toString(),
                "pitch" to pitch.toString(),
                "volume" to volume.toString()
            ), "voice")

        suspend fun speak(text: String, language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/speak", mapOf("text" to text, "language" to language), "voice")

        suspend fun speakAsync(text: String, language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/speakAsync", mapOf("text" to text, "language" to language), "voice")

        suspend fun stopSpeaking(): BridgeResult<String> = invoke("voice/stopSpeaking", emptyMap(), "voice")

        suspend fun isSpeaking(): BridgeResult<String> = invoke("voice/isSpeaking", emptyMap(), "voice")

        suspend fun listTtsEngines(): BridgeResult<String> = invoke("voice/listTtsEngines", emptyMap(), "voice")

        suspend fun listSupportedLanguages(): BridgeResult<String> = invoke("voice/listSupportedLanguages", emptyMap(), "voice")

        suspend fun startRecognition(language: String = "zh-CN"): BridgeResult<String> =
            invoke("voice/startRecognition", mapOf("language" to language), "voice")

        suspend fun recognizeOnce(language: String = "zh-CN", timeoutMs: Long = 30_000L): BridgeResult<String> =
            invoke("voice/recognizeOnce", mapOf("language" to language, "timeoutMs" to timeoutMs.toString()), "voice")

        suspend fun stopRecognition(): BridgeResult<String> = invoke("voice/stopRecognition", emptyMap(), "voice")

        suspend fun cancelRecognition(): BridgeResult<String> = invoke("voice/cancelRecognition", emptyMap(), "voice")
    }

    // ============================================================
    // Self-Modify（Agent 自改源码）
    // ============================================================

    /**
     * Agent 自改源码客户端 — 镜像 SelfModifyService 的公开 API。
     *
     * Per AGENT_SELF_MODIFY_SPEC §8.2。所有方法通过 "selfmodify/" 前缀的 method 路由，
     * 经 ApexBridge.invoke 调用 SelfModifyService（同进程走 InProcessRegistry 零延迟，
     * 跨进程走 AIDL）。
     *
     * 注意（Phase 4 状态）：本客户端的 method 路由（IApkBridgeInternal 注册
     * selfmodify 系列方法 -> SelfModifyService）属于 Phase 5 工作，尚未接线。当前调用
     * 会返回 BridgeResult.Failure。Phase 5 将在 Engine APK 的 BridgeModule 中注册路由
     * 并补全端到端集成测试。
     */
    class SelfModifyClient {

        /** 读取 workspace 源码文件。 */
        suspend fun readFile(path: String): BridgeResult<String> =
            invoke("selfmodify/readFile", mapOf("path" to path))

        /** 列出匹配 pattern 的文件。 */
        suspend fun listFiles(pattern: String = ".*"): BridgeResult<String> =
            invoke("selfmodify/listFiles", mapOf("pattern" to pattern))

        /** 查找符号定义，返回 JSON 数组 (file/line/column)。 */
        suspend fun findSymbol(name: String): BridgeResult<String> =
            invoke("selfmodify/findSymbol", mapOf("name" to name))

        /** 查找符号引用，返回 JSON 数组 (file/line/symbol)。 */
        suspend fun findReferences(symbol: String): BridgeResult<String> =
            invoke("selfmodify/findReferences", mapOf("symbol" to symbol))

        /** 应用一个修改计划，触发完整流程：snapshot -> write -> compile gate -> reload -> audit（失败自动回滚）。 */
        suspend fun applyPlan(planJson: String): BridgeResult<String> =
            invoke("selfmodify/applyPlan", mapOf("planJson" to planJson))

        /** 回滚到指定 commit（省略则回滚到上一个快照）。 */
        suspend fun rollback(commit: String? = null): BridgeResult<String> {
            val args = if (commit != null) mapOf("commit" to commit) else emptyMap()
            return invoke("selfmodify/rollback", args)
        }

        /** 列出最近快照（git log），返回 JSON 数组。 */
        suspend fun listSnapshots(): BridgeResult<String> =
            invoke("selfmodify/listSnapshots", emptyMap())

        /** 触发全量重建代码索引。 */
        suspend fun reindex(): BridgeResult<String> =
            invoke("selfmodify/reindex", emptyMap())
    }

    // ============================================================
    // APK 依赖检查（业务侧调用前检查目标 APK 是否已安装）
    // ============================================================

    /**
     * 检查指定 APK 是否已安装。
     * 业务侧在调用可选 APK（如 Rage / Multi-Agent）前应先检查。
     */
    fun isApkInstalled(context: Context, apkId: String): Boolean =
        ApkDependencyManager.isApkInstalled(context, apkId)

    /**
     * 检查指定能力是否有任意 APK 提供。
     */
    fun hasCapability(context: Context, capability: String): Boolean =
        ApkDependencyManager.hasCapability(context, capability)

    /**
     * 要求某 APK 必须已安装，否则返回 [BridgeResult.Failure]。
     * 业务侧用法：
     *   ```kotlin
     *   val ready = ApexClient.requireApk(context, ApexSuite.ApkId.RAGE)
     *   if (ready is BridgeResult.Failure) return ready  // 友好错误
     *   ApexClient.rage.startSession(...)
     *   ```
     */
    fun requireApk(context: Context, apkId: String): BridgeResult<Unit> {
        return if (ApkDependencyManager.isApkInstalled(context, apkId)) {
            BridgeResult.Success(Unit)
        } else {
            BridgeResult.Failure(com.apex.sdk.common.BridgeError.notInstalledFriendly(apkId))
        }
    }

    /**
     * 要求某能力必须可用，否则返回 [BridgeResult.Failure]。
     */
    fun requireCapability(context: Context, capability: String): BridgeResult<Unit> {
        return if (ApkDependencyManager.hasCapability(context, capability)) {
            BridgeResult.Success(Unit)
        } else {
            BridgeResult.Failure(com.apex.sdk.common.BridgeError.capabilityMissing(capability))
        }
    }

    /**
     * 获取所有未安装的必须 APK。
     */
    fun getMissingRequired(context: Context): List<com.apex.sdk.common.ApkDescriptor> =
        ApkDependencyManager.checkRequiredApks(context)

    /**
     * 获取套件安装摘要。
     */
    fun getInstallSummary(context: Context): String =
        ApkDependencyManager.getInstallSummary(context)

    /**
     * 启动某 APK 的下载页（GitHub Release）。
     */
    fun openDownloadPage(context: Context, apkId: String): Boolean =
        ApkDependencyManager.openDownloadPage(context, apkId)

    /**
     * 启动某 APK（如果已安装）。
     */
    fun launchApk(context: Context, apkId: String): Boolean =
        ApkDependencyManager.launchApk(context, apkId)

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
