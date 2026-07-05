package com.apex.lib.rage

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 狂暴模式核心引擎 — 持有 [RageAgentArchitect] + [RageSkillCatalog] + [RageTaskStore]。
 *
 * 职责：
 * - 任务执行：[startTask] 委托给 [architect]，结果同步到 [taskStore]
 * - 任务管理：[cancelTask] / [listTasks] / [getTask] / [deleteTask] / [clearTasks]
 * - 技能查询：[listSkills] / [findSkill] / [findSkillsByCategory]
 * - 指标监控：[getMetrics]
 * - 配置管理：[applyConfig] / [switchPreset]
 * - 架构师代理：[toggleAgent] / [spawnAgent] / [terminateAgent] 等
 * - 事件流：[events] 暴露 [RageEvent] SharedFlow
 *
 * 所有 API 返回 [BridgeResult]，异常由 [bridgeRun] 捕获。
 *
 * 用法：
 * ```
 * val engine = RageEngine()
 * engine.switchPreset(RageStrategyPreset.AGGRESSIVE)
 * val result = engine.startTask("实现一个 REST API").getOrThrow()
 * engine.listSkills().getOrThrow().forEach { println("${it.id}: ${it.name}") }
 * ```
 */
class RageEngine(
    val architect: RageAgentArchitect = RageAgentArchitect(),
    val skillCatalog: RageSkillCatalog = RageSkillCatalog.default(),
    private val taskStore: RageTaskStore = RageTaskStore(),
    var config: RageModeConfig = RagePresets.BALANCED
) {

    private val _events = MutableSharedFlow<RageEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RageEvent> = _events.asSharedFlow()

    init {
        applyConfig(config)
        ApexLog.i(ApexSuite.ApkId.RAGE, "[RageEngine] initialized (skills=${skillCatalog.count()})")
    }

    // ============================================================
    // 任务执行
    // ============================================================

    /**
     * 启动任务 — 委托给 [architect] 执行，结果同步到 [taskStore]。
     *
     * @param description 任务描述
     * @param preset      策略预设名（AGGRESSIVE / BALANCED / CONSERVATIVE / DEBUG）
     * @param onProgress  进度回调（0.0 ~ 1.0）
     * @return 执行结果
     */
    suspend fun startTask(
        description: String,
        preset: String = "BALANCED",
        onProgress: ((Float, String) -> Unit)? = null
    ): BridgeResult<TaskExecutionResult> = bridgeRun {
        val taskId = Trace.newId("rage")
        ApexLog.i(ApexSuite.ApkId.RAGE, "[RageEngine] startTask: ${description.take(60)} (preset=$preset)")

        // 注册到内存任务存储
        taskStore.create(
            RageTask(
                id = taskId,
                description = description,
                preset = preset,
                status = RageTaskStatus.RUNNING,
                startedAt = System.currentTimeMillis()
            )
        )
        taskStore.enterRunning()

        try {
            val result = architect.executeTask(description, preset, onProgress ?: { _, _ -> })

            // 同步状态到任务存储
            taskStore.updateStatus(
                taskId = taskId,
                status = if (result.success) RageTaskStatus.COMPLETED else RageTaskStatus.FAILED,
                result = if (result.success) "completed" else null,
                errorMessage = result.errorMessage,
                progress = 1.0f,
                agentInvocations = result.agentInvocations,
                retryCount = result.retryCount,
                durationMs = result.durationMs
            )

            // 转发架构师事件为引擎事件
            _events.tryEmit(
                RageEvent.TaskCompleted(taskId, result.success, result.durationMs)
            )
            result
        } catch (t: Throwable) {
            taskStore.updateStatus(
                taskId = taskId,
                status = RageTaskStatus.FAILED,
                errorMessage = t.message
            )
            _events.tryEmit(RageEvent.TaskFailed(taskId, t.message ?: "unknown"))
            throw t
        } finally {
            taskStore.exitRunning()
        }
    }

    /**
     * 取消任务。
     */
    fun cancelTask(taskId: String): BridgeResult<Boolean> = bridgeRun {
        val updated = taskStore.updateStatus(taskId, RageTaskStatus.CANCELLED)
        if (updated != null) {
            _events.tryEmit(RageEvent.TaskCancelled(taskId, "cancelled by user"))
            ApexLog.i(ApexSuite.ApkId.RAGE, "[RageEngine] cancelled: $taskId")
            true
        } else {
            false
        }
    }

    /** 列出全部任务。 */
    fun listTasks(): BridgeResult<List<RageTask>> = bridgeRun {
        taskStore.list()
    }

    /** 获取单个任务。 */
    fun getTask(taskId: String): BridgeResult<RageTask?> = bridgeRun {
        taskStore.get(taskId)
    }

    /** 删除任务。 */
    fun deleteTask(taskId: String): BridgeResult<Boolean> = bridgeRun {
        taskStore.delete(taskId)
    }

    /** 清空全部任务。 */
    fun clearTasks(): BridgeResult<Int> = bridgeRun {
        taskStore.clear()
    }

    // ============================================================
    // 指标
    // ============================================================

    /** 获取运行指标快照。 */
    fun getMetrics(): BridgeResult<RageMetrics> = bridgeRun {
        taskStore.getMetrics()
    }

    // ============================================================
    // 技能目录
    // ============================================================

    /** 列出全部技能（31 个内置）。 */
    fun listSkills(): BridgeResult<List<RageSkillDescriptor>> = bridgeRun {
        skillCatalog.list()
    }

    /** 按 ID 查找技能。 */
    fun findSkill(id: String): BridgeResult<RageSkillDescriptor?> = bridgeRun {
        skillCatalog.find(id)
    }

    /** 按分类查询技能。 */
    fun findSkillsByCategory(category: RageSkillCategory): BridgeResult<List<RageSkillDescriptor>> = bridgeRun {
        skillCatalog.findByCategory(category)
    }

    /** 按名称查找技能。 */
    fun findSkillByName(name: String): BridgeResult<RageSkillDescriptor?> = bridgeRun {
        skillCatalog.findByName(name)
    }

    // ============================================================
    // 架构师代理（便捷方法）
    // ============================================================

    /** 4 核心 Agent 配置。 */
    val coreAgents: Map<String, AgentConfig> get() = architect.coreAgents.toMap()

    /** 切换核心 Agent 开关。 */
    fun toggleAgent(agentId: String): Boolean = architect.toggleAgent(agentId)

    /** 手动 spawn 特化 Agent。 */
    fun spawnAgent(name: String, systemPrompt: String, tools: List<String>): DynamicAgentInfo =
        architect.spawnAgent(name, systemPrompt, tools)

    /** 终止动态 Agent。 */
    fun terminateAgent(agentId: String): Boolean = architect.terminateAgent(agentId)

    /** 获取黑板快照。 */
    fun getBlackboardSnapshot(): Map<String, String> = architect.getBlackboardSnapshot()

    /** 获取执行历史（内存）。 */
    fun getExecutionHistory(): List<ExecutionRecord> = architect.getExecutionHistory()

    /** 清空执行历史（内存）。 */
    fun clearExecutionHistory(): Int = architect.clearHistory()

    /** 当前动态扩容 Agent 列表。 */
    val dynamicAgents: List<DynamicAgentInfo> get() = architect.dynamicAgents.toList()

    // ============================================================
    // 配置管理
    // ============================================================

    /**
     * 应用新配置（热更新架构师参数）。
     */
    fun applyConfig(newConfig: RageModeConfig) {
        config = newConfig
        architect.autoExpand = newConfig.enableAutoExpand
        architect.gitBranching = newConfig.enableGitBranching
        architect.sandboxExec = newConfig.enableSandboxExec
        architect.githubSearch = newConfig.enableGithubSearch
        architect.codeRag = newConfig.enableCodeRag
        architect.maxRetries = newConfig.maxRetries
        ApexLog.i(ApexSuite.ApkId.RAGE, "[RageEngine] config applied: concurrency=${newConfig.maxConcurrency}, retries=${newConfig.maxRetries}")
    }

    /**
     * 切换策略预设。
     */
    fun switchPreset(preset: RageStrategyPreset) {
        applyConfig(RagePresets.forPreset(preset))
    }

    /**
     * 按名称切换预设。
     */
    fun switchPresetByName(name: String) {
        applyConfig(RagePresets.forName(name))
    }

    // ============================================================
    // 扩容策略便捷属性
    // ============================================================

    var autoExpand: Boolean
        get() = architect.autoExpand
        set(value) { architect.autoExpand = value }

    var gitBranching: Boolean
        get() = architect.gitBranching
        set(value) { architect.gitBranching = value }

    var sandboxExec: Boolean
        get() = architect.sandboxExec
        set(value) { architect.sandboxExec = value }

    var githubSearch: Boolean
        get() = architect.githubSearch
        set(value) { architect.githubSearch = value }

    var codeRag: Boolean
        get() = architect.codeRag
        set(value) { architect.codeRag = value }

    var maxRetries: Int
        get() = architect.maxRetries
        set(value) { architect.maxRetries = value }
}
