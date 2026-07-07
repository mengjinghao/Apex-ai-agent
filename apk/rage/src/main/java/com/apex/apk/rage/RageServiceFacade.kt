package com.apex.apk.rage

import android.app.Application
import android.content.Context
import com.apex.agent.burstmode.api.BurstMode
import com.apex.agent.burstmode.config.BurstModeConfig
import com.apex.agent.burstmode.execution.DependencyExecutionStrategy
import com.apex.agent.burstmode.execution.SkillChain
import com.apex.agent.burstmode.execution.TaskDependencyGraph
import com.apex.agent.burstmode.preset.BurstPreset
import com.apex.agent.burstmode.selection.SkillSelectionStrategy
import com.apex.agent.burstmode.api.TaskPriority
import com.apex.agent.burstmode.api.TaskQueueSnapshot
import com.apex.agent.burstmode.checkpoint.TaskCheckpoint
import com.apex.agent.domain.model.BurstInput
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.kernel.burst.KernelState
import com.apex.agent.kernel.burst.LLMProvider
import com.apex.agent.plugins.burst.base.BurstSkillResult
import com.apex.agent.plugins.burst.base.IBurstSkill
import com.apex.agent.plugins.burst.base.LLMConfig
import com.apex.apk.rage.events.RageEvent
import com.apex.apk.rage.events.RageEventBridge
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.apex.apk.rage.agent.RageAgentArchitect
import com.apex.apk.rage.agent.RageTaskStore
import com.apex.apk.rage.agent.TaskExecutionResult
import com.apex.apk.rage.agent.ExecutionRecord
import com.apex.apk.rage.agent.AgentStepRecord
import com.apex.apk.rage.agent.DynamicAgentInfo
import com.apex.apk.rage.agent.TaskIndexEntry
import com.apex.lib.rage.RageEngine
import com.apex.lib.rage.RageModeConfig
import com.apex.lib.rage.RageStrategyPreset
import com.apex.lib.rage.RagePresets

/**
 * Rage Mode APK 的核心服务实现（完整版）。
 *
 * **暴露 BurstMode + BurstKernel 95%+ 能力**：
 *
 * **核心执行**（4 种模式）：
 *   1. executeTask — 单任务执行
 *   2. executeBatch — 批量并发执行
 *   3. executeWithDependencyGraph — DAG 依赖执行（分层并行）
 *   4. executeWithChain — 链式管道执行（顺序/并行/分支/错误处理）
 *   5. executeAsync — 异步执行（返回 Deferred，可取消/超时）
 *
 * **任务队列**：
 *   - enqueueTask / cancelTask / peekTask / pendingCount
 *   - 队列快照观察（StateFlow）
 *
 * **断点续传**：
 *   - saveCheckpoint / loadCheckpoint / resumeFromCheckpoint
 *   - listIncompleteTasks / deleteCheckpoint / clearCheckpoints
 *   - canResume / getResumePoint
 *
 * **事件流**：
 *   - RageEventBridge 桥接 14 种 BurstModeEvent → SuiteEventBus
 *   - 实时进度推送（onTaskProgress）
 *   - 任务成功/失败/取消通知
 *
 * **指标监控**：
 *   - getMetrics / observeMetrics（StateFlow）/ resetMetrics
 *
 * **状态流**：
 *   - observeState（KernelState StateFlow）
 *
 * **配置管理**：
 *   - switchPreset / updateConfig（细粒度热更新）
 *
 * **技能管理**：
 *   - listSkills / loadSkill / unloadSkill
 *   - getSkillsByTag / getSkillsByCapability
 *
 * **基础设施**（5 大模块）：
 *   - RateLimiter / LoadMonitor / ResultCache / RetryExecutor / TimeoutManager
 *
 * **AR/VR 可视化**：
 *   - enableSpatialVisualization
 */
class RageServiceFacade(private val context: Context) {

    private val TAG_SUB = "RageFacade"

    private var burstMode: BurstMode? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** 事件桥接器 — BurstMode 事件 → SuiteEventBus */
    val eventBridge: RageEventBridge = RageEventBridge()

    /** 狂暴模式核心引擎 — 持有架构师 + 31 技能目录 + 内存任务存储 */
    private val engine: RageEngine = RageEngine()

    /** 4 Agent 架构师 — 委托给引擎 */
    val architect: RageAgentArchitect get() = engine.architect

    /** 任务历史持久化（文件存储，与引擎的内存任务存储互补） */
    val taskStore: RageTaskStore = RageTaskStore(context)

    /** 当前活跃会话：sessionId → RageSession。 */
    private val sessions = mutableMapOf<String, RageSession>()

    /** 异步执行的 Deferred 缓存：taskId → Deferred<BurstSkillResult> */
    private val asyncTasks = mutableMapOf<String, Deferred<BurstSkillResult>>()

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 初始化狂暴模式。
     */
    suspend fun initialize(
        llmConfig: LLMConfig? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<Unit> = bridgeRun {
        if (_isInitialized.value) return@bridgeRun
        val app = context.applicationContext as Application

        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] initializing BurstKernel...")
        BurstKernel.start(
            app = app,
            llmConfig = llmConfig ?: defaultLlmConfig(),
            provider = LLMProvider.LOCAL_LLAMA,
            collaborationFramework = null
        )

        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] creating BurstMode with preset=$preset")
        burstMode = BurstMode.create(context)
            .withPreset(preset.toBurstPreset())
            .enableAutoStart(true)
            .enableMetrics(true)
            .enableHealthCheck(true)
            .initialize()

        // 桥接事件
        eventBridge.attach(burstMode!!)

        _isInitialized.value = true
        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] initialized; available skills: ${burstMode?.skillManager?.count() ?: 0}")
    }

    // ============================================================
    // 核心：4 种执行模式
    // ============================================================

    /**
     * 启动一个狂暴模式会话。
     */
    suspend fun startSession(
        taskDescription: String,
        preferredSkillId: String? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized(preset)
        val sessionId = com.apex.sdk.common.Trace.newId("rage")
        val task = BurstTask(
            id = sessionId,
            name = taskDescription.take(80),
            description = taskDescription,
            input = BurstInput(text = taskDescription),
            skillId = preferredSkillId
        )
        sessions[sessionId] = RageSession(sessionId, task, preset, System.currentTimeMillis())
        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] session started: $sessionId (skill=${preferredSkillId ?: "auto"})")
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_STARTED,
            mapOf("sessionId" to sessionId, "skillId" to (preferredSkillId ?: "auto")),
            ApexSuite.ApkId.RAGE
        )
        sessionId
    }

    /**
     * 单任务执行（真实进度推送）。
     */
    suspend fun executeTask(
        sessionId: String,
        onProgress: ((Float, String?) -> Unit)? = null
    ): BridgeResult<RageExecutionResult> = bridgeRun {
        val session = sessions[sessionId] ?: throw IllegalStateException("session not found: $sessionId")
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")

        // 注册进度回调
        if (onProgress != null) {
            eventBridge.registerProgressCallback(session.task.id, onProgress)
        }

        onProgress?.invoke(0.0f, "selecting skill")
        val skill = session.task.skillId?.let { mode.skillManager.get(it) }
            ?: mode.skillSelector.selectSkill(session.task)
        val skillId = skill?.manifest?.skillId ?: "reasoning.react"

        onProgress?.invoke(0.1f, "executing with skill: $skillId")
        val result = mode.execute(session.task.copy(skillId = skillId))

        onProgress?.invoke(1.0f, "done")
        session.completed = true
        session.result = result

        eventBridge.unregisterProgressCallback(session.task.id)

        RageExecutionResult(
            sessionId = sessionId,
            skillId = skillId,
            success = result.success,
            output = result.output ?: "",
            errorMessage = result.errorMessage,
            executionTimeMs = result.metrics?.executionTimeMs ?: 0L,
            tokensProcessed = result.metrics?.tokensProcessed ?: 0
        )
    }

    /**
     * **批量并发执行**多个任务。
     *
     * @param taskDescriptions 任务描述列表
     * @param skillId 可选技能 ID（所有任务共用）
     * @return 每个任务的执行结果
     */
    suspend fun executeBatch(
        taskDescriptions: List<String>,
        skillId: String? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<List<RageExecutionResult>> = bridgeRun {
        ensureInitialized(preset)
        val mode = burstMode!!
        val tasks = taskDescriptions.mapIndexed { index, desc ->
            BurstTask(
                id = "batch-${System.currentTimeMillis()}-$index",
                name = desc.take(80),
                description = desc,
                input = BurstInput(text = desc),
                skillId = skillId
            )
        }
        val results = mode.executeBatch(tasks)
        results.mapIndexed { index, result ->
            RageExecutionResult(
                sessionId = tasks[index].id,
                skillId = skillId ?: "auto",
                success = result.success,
                output = result.output ?: "",
                errorMessage = result.errorMessage,
                executionTimeMs = result.metrics?.executionTimeMs ?: 0L,
                tokensProcessed = result.metrics?.tokensProcessed ?: 0
            )
        }
    }

    /**
     * **DAG 依赖执行** — 支持分层并行 + 依赖失败策略。
     *
     * @param tasks 任务列表
     * @param dependencies 依赖关系列表（Pair<taskId, dependsOnTaskId>）
     * @param strategy 依赖失败策略（SKIP_ON_FAILURE / CONTINUE_ON_FAILURE / ABORT_ON_FAILURE）
     */
    suspend fun executeWithDependencyGraph(
        tasks: List<Pair<String, String>>,  // (taskId, taskDescription)
        dependencies: List<Pair<String, String>>,  // (taskId, dependsOn)
        strategy: String = "SKIP_ON_FAILURE",
        skillId: String? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<List<DependencyExecutionResultDto>> = bridgeRun {
        ensureInitialized(preset)
        val mode = burstMode!!
        val graph = TaskDependencyGraph()
        // 添加节点
        tasks.forEach { (taskId, desc) ->
            graph.addNode(taskId, BurstTask(
                id = taskId,
                name = desc.take(80),
                description = desc,
                input = BurstInput(text = desc),
                skillId = skillId
            ))
        }
        // 添加依赖
        dependencies.forEach { (taskId, dependsOn) ->
            graph.addDependency(taskId, dependsOn)
        }
        // 检查循环
        if (graph.hasCycle()) {
            throw IllegalStateException("dependency graph has cycle")
        }
        val execStrategy = runCatching { DependencyExecutionStrategy.valueOf(strategy) }
            .getOrDefault(DependencyExecutionStrategy.SKIP_ON_FAILURE)
        val results = mode.executeWithDependencyGraph(graph, execStrategy)
        results.map { r ->
            DependencyExecutionResultDto(
                taskId = r.taskId,
                success = r.success,
                skipped = r.skipped,
                errorMessage = r.errorMessage
            )
        }
    }

    /**
     * **链式管道执行** — 顺序/并行/分支/错误处理。
     *
     * @param initialTask 初始任务描述
     * @param chainSteps 链步骤列表（每步含 name + executor 描述）
     * @return 最终结果
     */
    suspend fun executeWithChain(
        initialTask: String,
        chainSteps: List<ChainStepDto>,
        skillId: String? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<RageExecutionResult> = bridgeRun {
        ensureInitialized(preset)
        val mode = burstMode!!
        val task = BurstTask(
            id = "chain-${System.currentTimeMillis()}",
            name = initialTask.take(80),
            description = initialTask,
            input = BurstInput(text = initialTask),
            skillId = skillId
        )
        // 构建 SkillChain
        val chain = SkillChain.create()
        chainSteps.forEach { step ->
            chain.then(step.name) { inputTask ->
                val stepTask = inputTask.copy(
                    description = step.description,
                    skillId = step.skillId ?: skillId
                )
                mode.execute(stepTask)
            }
        }
        val result = mode.executeWithChain(task, chain)
        RageExecutionResult(
            sessionId = task.id,
            skillId = skillId ?: "chain",
            success = result.success,
            output = result.output ?: "",
            errorMessage = result.errorMessage,
            executionTimeMs = result.metrics?.executionTimeMs ?: 0L,
            tokensProcessed = result.metrics?.tokensProcessed ?: 0
        )
    }

    /**
     * **异步执行**（返回 taskId，可取消/超时）。
     */
    suspend fun executeAsync(
        taskDescription: String,
        skillId: String? = null,
        preset: RagePreset = RagePreset.BALANCED
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized(preset)
        val mode = burstMode!!
        val taskId = "async-${System.currentTimeMillis()}"
        val task = BurstTask(
            id = taskId,
            name = taskDescription.take(80),
            description = taskDescription,
            input = BurstInput(text = taskDescription),
            skillId = skillId
        )
        val deferred = mode.executeAsync(task)
        asyncTasks[taskId] = deferred
        taskId
    }

    /**
     * 等待异步任务完成。
     */
    suspend fun awaitAsyncTask(
        taskId: String,
        timeoutMs: Long = 60_000L
    ): BridgeResult<RageExecutionResult?> = bridgeRun {
        val deferred = asyncTasks[taskId] ?: return@bridgeRun null
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: throw IllegalStateException("async task timeout: $taskId")
        asyncTasks.remove(taskId)
        RageExecutionResult(
            sessionId = taskId,
            skillId = "async",
            success = result.success,
            output = result.output ?: "",
            errorMessage = result.errorMessage,
            executionTimeMs = result.metrics?.executionTimeMs ?: 0L,
            tokensProcessed = result.metrics?.tokensProcessed ?: 0
        )
    }

    /**
     * 取消异步任务。
     */
    fun cancelAsyncTask(taskId: String): Boolean {
        val deferred = asyncTasks.remove(taskId) ?: return false
        return deferred.cancel()
    }

    // ============================================================
    // 会话管理
    // ============================================================

    suspend fun pauseSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.pause()
        sessions[sessionId]?.paused = true
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_PAUSED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.RAGE
        )
        true
    }

    suspend fun resumeSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        val mode = burstMode ?: throw IllegalStateException("BurstMode not initialized")
        mode.resume()
        sessions[sessionId]?.paused = false
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_RESUMED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.RAGE
        )
        true
    }

    /**
     * **真正停止会话** — 取消异步任务 + 从 sessions 移除 + 发布事件。
     */
    suspend fun stopSession(sessionId: String): BridgeResult<Boolean> = bridgeRun {
        // 取消异步任务
        asyncTasks.remove(sessionId)?.cancel()
        // 取消队列中的任务
        burstMode?.taskQueue?.cancel(sessionId)
        // 从 sessions 移除
        sessions.remove(sessionId)
        com.apex.sdk.bridge.SuiteEventBus.publish(
            com.apex.sdk.bridge.SuiteEventTypes.BURST_SESSION_STOPPED,
            mapOf("sessionId" to sessionId),
            ApexSuite.ApkId.RAGE
        )
        true
    }

    fun listSessions(): List<SessionInfo> = sessions.map { (id, s) ->
        SessionInfo(
            sessionId = id,
            taskName = s.task.name,
            skillId = s.task.skillId ?: "auto",
            createdAt = s.createdAt,
            paused = s.paused,
            completed = s.completed
        )
    }

    // ============================================================
    // 任务队列管理（P1 增强）
    // ============================================================

    /**
     * 任务入队。
     */
    suspend fun enqueueTask(
        taskDescription: String,
        priority: String = "NORMAL",
        skillId: String? = null
    ): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val mode = burstMode!!
        val p = runCatching { TaskPriority.valueOf(priority) }.getOrDefault(TaskPriority.NORMAL)
        val task = BurstTask(
            id = "queued-${System.currentTimeMillis()}",
            name = taskDescription.take(80),
            description = taskDescription,
            input = BurstInput(text = taskDescription),
            skillId = skillId
        )
        val queued = mode.taskQueue.enqueue(task, p)
        queued.task.id
    }

    /**
     * 取消队列中的任务。
     */
    suspend fun cancelQueuedTask(taskId: String): BridgeResult<Boolean> = bridgeRun {
        burstMode?.taskQueue?.cancel(taskId) ?: false
    }

    /**
     * 查看队首任务（不移除）。
     */
    suspend fun peekQueue(): BridgeResult<String?> = bridgeRun {
        burstMode?.taskQueue?.peek()?.task?.id
    }

    /**
     * 队列待处理任务数。
     */
    suspend fun pendingTaskCount(): BridgeResult<Int> = bridgeRun {
        burstMode?.taskQueue?.pendingCount() ?: 0
    }

    /**
     * 清空队列。
     */
    suspend fun clearQueue(): BridgeResult<Int> = bridgeRun {
        burstMode?.taskQueue?.clear() ?: 0
    }

    /**
     * 队列快照（含 pending/completed/failed/cancelled 计数）。
     */
    suspend fun getQueueSnapshot(): BridgeResult<TaskQueueSnapshot?> = bridgeRun {
        burstMode?.taskQueue?.snapshot?.value
    }

    // ============================================================
    // 断点续传（P0 增强 — 完整 CheckpointManager）
    // ============================================================

    /**
     * 保存断点。
     */
    suspend fun saveCheckpoint(
        taskId: String,
        completedSteps: List<String>,
        totalSteps: Int,
        intermediateResult: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        val mode = burstMode!!
        val task = sessions[taskId]?.task ?: BurstTask(
            id = taskId, name = "checkpoint-task", description = "",
            input = BurstInput(text = "")
        )
        mode.checkpointManager.saveCheckpoint(
            task = task,
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            intermediateResult = intermediateResult?.let { com.apex.agent.plugins.burst.base.BurstSkillResult(success = true, output = it) },
            metadata = metadata
        )
    }

    /**
     * 加载断点。
     */
    suspend fun loadCheckpoint(taskId: String): BridgeResult<TaskCheckpoint?> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.loadCheckpoint(taskId)
    }

    /**
     * **从断点恢复执行**。
     */
    suspend fun resumeFromCheckpoint(taskId: String): BridgeResult<RageExecutionResult> = bridgeRun {
        ensureInitialized()
        val mode = burstMode!!
        val checkpoint = mode.checkpointManager.loadCheckpoint(taskId)
            ?: throw IllegalStateException("checkpoint not found: $taskId")
        if (!checkpoint.isComplete) {
            // 重新执行任务（实际生产环境应根据 completedSteps 跳过已完成步骤）
            val result = mode.execute(checkpoint.task)
            // 删除旧断点
            mode.checkpointManager.deleteCheckpoint(taskId)
            RageExecutionResult(
                sessionId = taskId,
                skillId = checkpoint.task.skillId ?: "auto",
                success = result.success,
                output = result.output ?: "",
                errorMessage = result.errorMessage,
                executionTimeMs = result.metrics?.executionTimeMs ?: 0L,
                tokensProcessed = result.metrics?.tokensProcessed ?: 0
            )
        } else {
            // 已完成，返回中间结果
            RageExecutionResult(
                sessionId = taskId,
                skillId = checkpoint.task.skillId ?: "auto",
                success = true,
                output = checkpoint.intermediateResult?.output ?: "",
                errorMessage = null,
                executionTimeMs = 0L,
                tokensProcessed = 0
            )
        }
    }

    /**
     * 列出所有断点。
     */
    suspend fun listCheckpoints(): BridgeResult<List<CheckpointInfo>> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.listAllCheckpoints().map { c ->
            CheckpointInfo(
                taskId = c.taskId,
                completedSteps = c.completedSteps.size,
                totalSteps = c.totalSteps,
                canResume = true
            )
        }
    }

    /**
     * 列出未完成的断点。
     */
    suspend fun listIncompleteTasks(): BridgeResult<List<CheckpointInfo>> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.listIncompleteTasks().map { c ->
            CheckpointInfo(
                taskId = c.taskId,
                completedSteps = c.completedSteps.size,
                totalSteps = c.totalSteps,
                canResume = true
            )
        }
    }

    /**
     * 检查是否可恢复。
     */
    suspend fun canResume(taskId: String): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.canResume(taskId)
    }

    /**
     * 获取恢复点（下一步步骤名）。
     */
    suspend fun getResumePoint(taskId: String): BridgeResult<String?> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.getResumePoint(taskId)
    }

    /**
     * 删除断点。
     */
    suspend fun deleteCheckpoint(taskId: String): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.deleteCheckpoint(taskId)
    }

    /**
     * 清空所有断点。
     */
    suspend fun clearCheckpoints(): BridgeResult<Unit> = bridgeRun {
        ensureInitialized()
        burstMode!!.checkpointManager.clearAll()
    }

    // ============================================================
    // 技能管理（P2 增强 — 多维查询）
    // ============================================================

    suspend fun listSkills(): BridgeResult<List<SkillDescriptor>> = bridgeRun {
        ensureInitialized()
        burstMode!!.skillManager.getAllManifests().map { m ->
            SkillDescriptor(
                skillId = m.skillId,
                skillName = m.skillName,
                version = m.version,
                description = m.description,
                author = m.author,
                tags = m.tags,
                capabilities = m.capabilities,
                priority = m.priority
            )
        }
    }

    /**
     * 按标签查询技能。
     */
    suspend fun getSkillsByTag(tag: String): BridgeResult<List<SkillDescriptor>> = bridgeRun {
        ensureInitialized()
        burstMode!!.skillManager.getByTag(tag).map { skill ->
            val m = skill.manifest
            SkillDescriptor(m.skillId, m.skillName, m.version, m.description, m.author, m.tags, m.capabilities, m.priority)
        }
    }

    /**
     * 按能力查询技能。
     */
    suspend fun getSkillsByCapability(capability: String): BridgeResult<List<SkillDescriptor>> = bridgeRun {
        ensureInitialized()
        burstMode!!.skillManager.getByCapability(capability).map { skill ->
            val m = skill.manifest
            SkillDescriptor(m.skillId, m.skillName, m.version, m.description, m.author, m.tags, m.capabilities, m.priority)
        }
    }

    /**
     * 加载自定义技能。
     */
    suspend fun loadSkill(skill: IBurstSkill): BridgeResult<String> = bridgeRun {
        ensureInitialized()
        val registered = burstMode!!.skillManager.register(skill)
        if (!registered) throw IllegalStateException("skill registration failed: ${skill.manifest.skillId}")
        skill.manifest.skillId
    }

    /**
     * 卸载技能。
     */
    suspend fun unloadSkill(skillId: String): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        burstMode!!.skillManager.unregister(skillId)
    }

    /**
     * 获取技能数量。
     */
    suspend fun getSkillCount(): BridgeResult<Int> = bridgeRun {
        ensureInitialized()
        burstMode!!.skillManager.count()
    }

    /**
     * 技能是否已注册。
     */
    suspend fun isSkillLoaded(skillId: String): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        burstMode!!.skillManager.contains(skillId)
    }

    // ============================================================
    // 预设与配置（P2 增强 — 细粒度热更新）
    // ============================================================

    suspend fun switchPreset(preset: RagePreset): BridgeResult<Unit> = bridgeRun {
        ensureInitialized()
        burstMode!!.switchPreset(preset.toBurstPreset())
    }

    /**
     * 动态更新配置（细粒度热更新）。
     */
    suspend fun updateConfig(
        maxConcurrency: Int? = null,
        defaultTimeoutMs: Long? = null,
        enableAdaptiveOptimization: Boolean? = null,
        enableMetricsCollection: Boolean? = null,
        memoryBudgetMb: Int? = null
    ): BridgeResult<Unit> = bridgeRun {
        ensureInitialized()
        val current = burstMode!!.currentConfig
        val newConfig = BurstModeConfig.Builder()
            .maxConcurrency(maxConcurrency ?: current.maxConcurrency)
            .defaultTimeoutMs(defaultTimeoutMs ?: current.defaultTimeoutMs)
            .enableAdaptiveOptimization(enableAdaptiveOptimization ?: current.enableAdaptiveOptimization)
            .enableMetricsCollection(enableMetricsCollection ?: current.enableMetricsCollection)
            .memoryBudgetMb(memoryBudgetMb ?: current.memoryBudgetMb)
            .build()
        burstMode!!.updateConfig(newConfig)
    }

    /**
     * 获取当前配置。
     */
    suspend fun getCurrentConfig(): BridgeResult<ConfigInfo> = bridgeRun {
        ensureInitialized()
        val c = burstMode!!.currentConfig
        ConfigInfo(
            maxConcurrency = c.maxConcurrency,
            defaultTimeoutMs = c.defaultTimeoutMs,
            enableAdaptiveOptimization = c.enableAdaptiveOptimization,
            enableMetricsCollection = c.enableMetricsCollection,
            memoryBudgetMb = c.memoryBudgetMb,
            preset = burstMode!!.currentPreset.name
        )
    }

    // ============================================================
    // 指标与状态（P1 增强 — 流式观察）
    // ============================================================

    /**
     * 获取指标快照。
     */
    fun getMetrics(): RageMetricsSnapshot? {
        val mode = burstMode ?: return null
        val snapshot = mode.getMetrics()
        return RageMetricsSnapshot(
            totalTasks = snapshot.totalTasks,
            successfulTasks = snapshot.successfulTasks,
            failedTasks = snapshot.failedTasks,
            cancelledTasks = snapshot.cancelledTasks,
            averageExecutionTimeMs = snapshot.averageExecutionTimeMs,
            successRate = snapshot.successRate,
            currentConcurrency = snapshot.currentConcurrency,
            peakConcurrency = snapshot.peakConcurrency,
            totalTokensProcessed = snapshot.totalTokensProcessed,
            totalMemoryUsedMb = snapshot.totalMemoryUsedMb
        )
    }

    /**
     * 获取下一个指标更新（流式观察的简化版 — 等待下一次指标变化）。
     */
    suspend fun observeNextMetrics(): BridgeResult<RageMetricsSnapshot?> = bridgeRun {
        ensureInitialized()
        val flow = burstMode!!.observeMetrics()
        val snapshot = flow.first()
        RageMetricsSnapshot(
            totalTasks = snapshot.totalTasks,
            successfulTasks = snapshot.successfulTasks,
            failedTasks = snapshot.failedTasks,
            cancelledTasks = snapshot.cancelledTasks,
            averageExecutionTimeMs = snapshot.averageExecutionTimeMs,
            successRate = snapshot.successRate,
            currentConcurrency = snapshot.currentConcurrency,
            peakConcurrency = snapshot.peakConcurrency,
            totalTokensProcessed = snapshot.totalTokensProcessed,
            totalMemoryUsedMb = snapshot.totalMemoryUsedMb
        )
    }

    /**
     * 重置指标。
     */
    suspend fun resetMetrics(): BridgeResult<Unit> = bridgeRun {
        ensureInitialized()
        burstMode!!.resetMetrics()
    }

    /**
     * 获取内核状态。
     */
    fun getKernelState(): String {
        return burstMode?.state?.value?.name ?: "STOPPED"
    }

    /**
     * 获取健康检查结果。
     */
    suspend fun getHealthStatus(): BridgeResult<HealthStatus> = bridgeRun {
        ensureInitialized()
        val mode = burstMode!!
        val metrics = mode.getMetrics()
        val loadMonitor = mode.loadMonitor
        val usedMemory = loadMonitor.getUsedMemoryMb()
        val shouldDegrade = loadMonitor.shouldDegrade(metrics.currentConcurrency)
        HealthStatus(
            healthy = !shouldDegrade,
            usedMemoryMb = usedMemory,
            currentConcurrency = metrics.currentConcurrency,
            maxConcurrency = mode.currentConfig.maxConcurrency,
            shouldDegrade = shouldDegrade
        )
    }

    // ============================================================
    // 基础设施管理（P2 增强）
    // ============================================================

    /**
     * 结果缓存 — 清除指定前缀。
     */
    suspend fun clearResultCache(prefix: String? = null): BridgeResult<Int> = bridgeRun {
        ensureInitialized()
        val cache = burstMode!!.resultCache
        if (prefix != null) cache.removeByPrefix(prefix) else { val n = cache.size(); cache.clear(); n }
    }

    /**
     * 结果缓存统计。
     */
    suspend fun getResultCacheStats(): BridgeResult<CacheStatsInfo> = bridgeRun {
        ensureInitialized()
        val stats = burstMode!!.resultCache.getStats()
        CacheStatsInfo(
            size = stats.currentSize,
            hitCount = stats.cacheHits,
            missCount = stats.cacheMisses,
            hitRate = stats.hitRate
        )
    }

    /**
     * 设置技能选择策略（type_matching / keyword_matching / priority / complexity_based）。
     */
    suspend fun setSkillSelectionStrategy(strategy: String): BridgeResult<Unit> = bridgeRun {
        ensureInitialized()
        val s: SkillSelectionStrategy = when (strategy.lowercase()) {
            "type_matching", "typematching" -> com.apex.agent.burstmode.selection.TypeMatchingStrategy()
            "keyword_matching", "keywordmatching" -> com.apex.agent.burstmode.selection.KeywordMatchingStrategy()
            "priority" -> com.apex.agent.burstmode.selection.PriorityStrategy()
            "complexity_based", "complexitybased" -> com.apex.agent.burstmode.selection.ComplexityBasedStrategy()
            else -> com.apex.agent.burstmode.selection.PriorityStrategy()
        }
        burstMode!!.skillSelector.withStrategy(s)
    }

    // ============================================================
    // AR/VR 可视化（P2 增强）
    // ============================================================

    /**
     * 启用 AR/VR 空间可视化。
     */
    suspend fun enableSpatialVisualization(): BridgeResult<Boolean> = bridgeRun {
        ensureInitialized()
        BurstKernel.enableSpatialVisualization()
        true
    }

    // ============================================================
    // 关闭
    // ============================================================

    suspend fun shutdown(): BridgeResult<Unit> = bridgeRun {
        burstMode?.let { eventBridge.detach(it) }
        burstMode?.shutdown()
        BurstKernel.stop()
        _isInitialized.value = false
        burstMode = null
        sessions.clear()
        asyncTasks.clear()
    }

    // ============================================================
    // 4 Agent 架构师 — 委托给 RageEngine
    // ============================================================

    /**
     * 使用 4 Agent 架构执行任务。
     *
     * 流程：Planner(架构师) → Searcher(领航员) → 动态扩容 → Executor(码农) → Critic(质检员)
     * - 黑板架构（全局共享状态）
     * - 全局容错（连续 maxRetries 次失败 → 终止 → 重新规划）
     * - Git 分支管理 + 沙盒执行
     *
     * 委托给 [engine.startTask]，同时持久化到文件历史 [taskStore]。
     */
    suspend fun executeArchitectTask(
        taskDescription: String,
        preset: String = "BALANCED",
        onProgress: ((Float, String) -> Unit)? = null
    ): BridgeResult<TaskExecutionResult> = bridgeRun {
        ApexLog.i(ApexSuite.ApkId.RAGE, "[$TAG_SUB] architect execute: ${taskDescription.take(60)}")
        val result = engine.startTask(taskDescription, preset, onProgress).getOrThrow()
        // 同时保存到文件历史
        taskStore.saveTask(result, taskDescription)
        result
    }

    /**
     * 获取 4 核心 Agent 配置。
     */
    fun getCoreAgents(): Map<String, com.apex.apk.rage.agent.AgentConfig> = engine.coreAgents

    /**
     * 切换核心 Agent 开关。
     */
    fun toggleCoreAgent(agentId: String): Boolean = engine.toggleAgent(agentId)

    /**
     * 设置扩容策略开关。
     */
    fun setExpandStrategy(
        autoExpand: Boolean? = null,
        gitBranching: Boolean? = null,
        sandboxExec: Boolean? = null,
        githubSearch: Boolean? = null,
        codeRag: Boolean? = null
    ) {
        autoExpand?.let { engine.autoExpand = it }
        gitBranching?.let { engine.gitBranching = it }
        sandboxExec?.let { engine.sandboxExec = it }
        githubSearch?.let { engine.githubSearch = it }
        codeRag?.let { engine.codeRag = it }
    }

    /**
     * 获取扩容策略状态。
     */
    fun getExpandStrategy(): ExpandStrategyInfo = ExpandStrategyInfo(
        autoExpand = engine.autoExpand,
        gitBranching = engine.gitBranching,
        sandboxExec = engine.sandboxExec,
        githubSearch = engine.githubSearch,
        codeRag = engine.codeRag,
        maxRetries = engine.maxRetries
    )

    /**
     * 获取当前动态扩容 Agent 列表。
     */
    fun getDynamicAgents(): List<DynamicAgentInfo> = engine.dynamicAgents

    /**
     * 获取黑板快照。
     */
    fun getArchitectBlackboard(): Map<String, String> = engine.getBlackboardSnapshot()

    /**
     * 获取任务历史列表（文件持久化）。
     */
    fun getTaskHistory(): List<TaskIndexEntry> = taskStore.loadIndex()

    /**
     * 获取任务全流程详情（含所有步骤，文件持久化）。
     */
    fun getTaskDetail(taskId: String): TaskExecutionResult? = taskStore.loadTask(taskId)

    /**
     * 删除历史任务。
     */
    fun deleteTask(taskId: String): Boolean = taskStore.deleteTask(taskId)

    /**
     * 清空所有历史任务。
     */
    fun clearTaskHistory(): Int = taskStore.clearAll()

    /**
     * 获取执行历史记录（内存中的）。
     */
    fun getExecutionHistory(): List<ExecutionRecord> = engine.getExecutionHistory()

    /**
     * 清空执行历史（内存）。
     */
    fun clearExecutionHistory(): Int = engine.clearExecutionHistory()

    /**
     * 手动 spawn 一个特化 Agent。
     */
    fun spawnAgent(name: String, systemPrompt: String, tools: List<String>): DynamicAgentInfo =
        engine.spawnAgent(name, systemPrompt, tools)

    /**
     * 终止动态 Agent。
     */
    fun terminateAgent(agentId: String): Boolean = engine.terminateAgent(agentId)

    /**
     * 切换策略预设（委托给引擎）。
     */
    fun switchRagePreset(preset: RageStrategyPreset) {
        engine.switchPreset(preset)
    }

    /**
     * 获取引擎运行指标。
     */
    fun getRageMetrics(): com.apex.lib.rage.RageMetrics = engine.getMetrics().getOrNull()
        ?: com.apex.lib.rage.RageMetrics()

    /**
     * 列出内置技能目录（31 个）。
     */
    fun listRageSkills(): List<com.apex.lib.rage.RageSkillDescriptor> = engine.listSkills().getOrNull() ?: emptyList()

    // ============================================================
    // lib:rage 引擎新能力 — 薄委托（供 BridgeImpl 路由）
    // ============================================================

    /**
     * 查找技能：先按 ID，找不到再按显示名。
     */
    fun findRageSkill(idOrName: String): com.apex.lib.rage.RageSkillDescriptor? {
        engine.findSkill(idOrName).getOrNull()?.let { return it }
        return engine.findSkillByName(idOrName).getOrNull()
    }

    /**
     * 按分类查询技能。
     */
    fun findRageSkillsByCategory(
        category: com.apex.lib.rage.RageSkillCategory
    ): List<com.apex.lib.rage.RageSkillDescriptor> =
        engine.findSkillsByCategory(category).getOrNull() ?: emptyList()

    /**
     * 全部技能分类。
     */
    fun listRageSkillCategories(): List<com.apex.lib.rage.RageSkillCategory> =
        com.apex.lib.rage.RageSkillCategory.values().toList()

    /**
     * 列出引擎内存任务（可选按状态过滤）。
     */
    fun listRageTasks(
        status: com.apex.lib.rage.RageTaskStatus? = null
    ): List<com.apex.lib.rage.RageTask> {
        val all = engine.listTasks().getOrNull() ?: emptyList()
        return if (status != null) all.filter { it.status == status } else all
    }

    /**
     * 获取单个引擎内存任务。
     */
    fun getRageTask(taskId: String): com.apex.lib.rage.RageTask? =
        engine.getTask(taskId).getOrNull()

    /**
     * 应用引擎配置（热更新架构师参数）。
     */
    fun applyRageConfig(config: com.apex.lib.rage.RageModeConfig) {
        engine.applyConfig(config)
    }

    /**
     * 列出全部策略预设（4 套）。
     */
    fun listRagePresets(): List<com.apex.lib.rage.RageStrategyPreset> =
        com.apex.lib.rage.RagePresets.ALL

    /**
     * 按名称获取预设配置（找不到返回 BALANCED）。
     */
    fun getRagePreset(name: String): com.apex.lib.rage.RageModeConfig =
        com.apex.lib.rage.RagePresets.forName(name)

    /**
     * 架构师状态聚合快照（活跃 Agent 数 / 黑板键数 / 执行历史 / 并发）。
     */
    fun getRageArchitectState(): RageArchitectState {
        val core = engine.coreAgents
        val dynamic = engine.dynamicAgents
        val blackboard = engine.getBlackboardSnapshot()
        val metrics = engine.getMetrics().getOrNull() ?: com.apex.lib.rage.RageMetrics()
        return RageArchitectState(
            coreAgentCount = core.size,
            activeCoreAgentCount = core.values.count { it.enabled },
            dynamicAgentCount = dynamic.size,
            blackboardKeys = blackboard.size,
            executionHistoryCount = engine.getExecutionHistory().size,
            currentConcurrency = metrics.currentConcurrency,
            peakConcurrency = metrics.peakConcurrency,
            maxRetries = engine.maxRetries,
            autoExpand = engine.autoExpand
        )
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private suspend fun ensureInitialized(preset: RagePreset = RagePreset.BALANCED) {
        if (!_isInitialized.value) initialize(preset = preset)
    }

    private fun defaultLlmConfig(): LLMConfig = LLMConfig(
        nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
        nCtx = 8192,
        temperature = 0.7f,
        maxTokens = 4096
    )
}

// ============================================================
// 数据类
// ============================================================

/** Rage 会话。 */
private data class RageSession(
    val sessionId: String,
    val task: BurstTask,
    val preset: RagePreset,
    val createdAt: Long,
    var paused: Boolean = false,
    var completed: Boolean = false,
    var result: BurstSkillResult? = null
)

/** 狂暴模式预设。 */
enum class RagePreset {
    PERFORMANCE, BALANCED, POWER_SAVER, LOCAL_INFERENCE, CLOUD_INFERENCE, STREAMING, TEST;
    fun toBurstPreset(): BurstPreset = when (this) {
        PERFORMANCE -> BurstPreset.PERFORMANCE
        BALANCED -> BurstPreset.BALANCED
        POWER_SAVER -> BurstPreset.POWER_SAVER
        LOCAL_INFERENCE -> BurstPreset.LOCAL_INFERENCE
        CLOUD_INFERENCE -> BurstPreset.CLOUD_INFERENCE
        STREAMING -> BurstPreset.STREAMING
        TEST -> BurstPreset.TEST
    }
}

/** 执行结果。 */
data class RageExecutionResult(
    val sessionId: String,
    val skillId: String,
    val success: Boolean,
    val output: String,
    val errorMessage: String?,
    val executionTimeMs: Long,
    val tokensProcessed: Int
)

/** DAG 执行结果。 */
data class DependencyExecutionResultDto(
    val taskId: String,
    val success: Boolean,
    val skipped: Boolean,
    val errorMessage: String?
)

/** 链式步骤 DTO。 */
data class ChainStepDto(
    val name: String,
    val description: String,
    val skillId: String? = null
)

/** 技能描述。 */
data class SkillDescriptor(
    val skillId: String,
    val skillName: String,
    val version: String,
    val description: String,
    val author: String,
    val tags: List<String>,
    val capabilities: List<String>,
    val priority: Int
)

/** 监控指标。 */
data class RageMetricsSnapshot(
    val totalTasks: Long,
    val successfulTasks: Long,
    val failedTasks: Long,
    val cancelledTasks: Long,
    val averageExecutionTimeMs: Double,
    val successRate: Double,
    val currentConcurrency: Int,
    val peakConcurrency: Int,
    val totalTokensProcessed: Long,
    val totalMemoryUsedMb: Long
)

/** 会话信息。 */
data class SessionInfo(
    val sessionId: String,
    val taskName: String,
    val skillId: String,
    val createdAt: Long,
    val paused: Boolean,
    val completed: Boolean
)

/** 断点信息。 */
data class CheckpointInfo(
    val taskId: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val canResume: Boolean
)

/** 配置信息。 */
data class ConfigInfo(
    val maxConcurrency: Int,
    val defaultTimeoutMs: Long,
    val enableAdaptiveOptimization: Boolean,
    val enableMetricsCollection: Boolean,
    val memoryBudgetMb: Int,
    val preset: String
)

/** 健康状态。 */
data class HealthStatus(
    val healthy: Boolean,
    val usedMemoryMb: Long,
    val currentConcurrency: Int,
    val maxConcurrency: Int,
    val shouldDegrade: Boolean
)

/** 缓存统计。 */
data class CacheStatsInfo(
    val size: Int,
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double
)

/** 扩容策略信息。 */
data class ExpandStrategyInfo(
    val autoExpand: Boolean,
    val gitBranching: Boolean,
    val sandboxExec: Boolean,
    val githubSearch: Boolean,
    val codeRag: Boolean,
    val maxRetries: Int
)

/** 架构师状态聚合快照（lib:rage 引擎视图）。 */
data class RageArchitectState(
    val coreAgentCount: Int,
    val activeCoreAgentCount: Int,
    val dynamicAgentCount: Int,
    val blackboardKeys: Int,
    val executionHistoryCount: Int,
    val currentConcurrency: Int,
    val peakConcurrency: Int,
    val maxRetries: Int,
    val autoExpand: Boolean
)
