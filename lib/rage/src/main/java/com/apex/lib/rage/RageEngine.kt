package com.apex.lib.rage

import com.apex.rage.nativelib.NativeEvent
import com.apex.rage.nativelib.NativeExecutionResult
import com.apex.rage.nativelib.NativeRageConfig
import com.apex.rage.nativelib.NativeTask
import com.apex.rage.nativelib.RageNativeBridge
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 狂暴模式核心引擎 —— **薄壳**:委托给 [:rage-jni] 的 [RageNativeBridge],
 * 后者通过 JNI 调用 [:rage-native] 的 C++17 核心编排器
 * (TaskStateMachine + ParallelScheduler + 4-Agent Orchestrator + SkillGraph)。
 *
 * 职责:
 * - 任务执行:[startTask] 委托给 [bridge],结果同步到 [taskStore]
 * - 任务管理:[cancelTask] / [listTasks] / [getTask] / [deleteTask] / [clearTasks]
 * - 技能查询:[listSkills] / [findSkill] / [findSkillsByCategory] / [findSkillByName]
 *   (Kotlin 侧 [RageSkillCatalog],不变)
 * - 指标监控:[getMetrics]
 * - 配置管理:[applyConfig] / [switchPreset]
 * - 事件流:[events] 暴露 [RageEvent] SharedFlow(由 [NativeEvent] 翻译而来)
 *
 * 所有 API 返回 [BridgeResult],异常由 [bridgeRun] 捕获。
 *
 * [bridge] 可空 —— 为 null 时 [startTask] 返回失败(说明 :rage-jni 未注入)。
 * 此设计为向后兼容:旧调用方 `RageEngine()` 不传 bridge 时,
 * 技能查询 / 配置 / 任务存储仍可用,但无法实际执行任务。
 *
 * **已移除**(原 architect 内部 API,行为已下沉到 C++ 核心):
 * - `coreAgents` / `toggleAgent` / `spawnAgent` / `terminateAgent` / `dynamicAgents`
 * - `getBlackboardSnapshot` / `fetchExecutionHistory` / `clearExecutionHistory`
 * - `autoExpand` / `gitBranching` / `sandboxExec` / `githubSearch` / `codeRag` / `maxRetries`
 *   便捷属性(改用 `engine.config.enable*` / `engine.config.maxRetries`)
 *
 * 用法:
 * ```
 * val engine = RageEngine(bridge = RageNativeBridge(...))
 * engine.switchPreset(RageStrategyPreset.AGGRESSIVE)
 * val result = engine.startTask("实现一个 REST API").getOrThrow()
 * engine.listSkills().getOrThrow().forEach { println("${'$'}{it.id}: ${'$'}{it.name}") }
 * ```
 */
class RageEngine(
    val bridge: RageNativeBridge? = null,
    val skillCatalog: RageSkillCatalog = RageSkillCatalog.default(),
    private val taskStore: RageTaskStore = RageTaskStore(),
    var config: RageModeConfig = RagePresets.BALANCED
) {

    private val _events = MutableSharedFlow<RageEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RageEvent> = _events.asSharedFlow()

    init {
        applyConfig(config)
        ApexLog.i(
            ApexSuite.ApkId.RAGE,
            "[RageEngine] initialized (skills=${skillCatalog.count()}, bridge=${if (bridge != null) "wired" else "null"})"
        )
    }

    // ============================================================
    // 任务执行
    // ============================================================

    /**
     * 启动任务 —— 委托给 [bridge] (→ rage-jni → rage-native C++ 核心),
     * 结果同步到 [taskStore]。事件流通过 [events] 暴露。
     *
     * 执行流程:
     * 1. 生成 taskId,在 [taskStore] 注册 RUNNING 任务
     * 2. 构造 [NativeTask] (带 taskId 以便事件关联) + [NativeRageConfig]
     * 3. 在子协程中订阅 [RageNativeBridge.events],翻译 [NativeEvent] → [RageEvent],
     *    并对 `TASK_PROGRESS` 事件调用 [onProgress]
     * 4. 调用 `bridge.startTask(nativeTask, nativeConfig)` (suspend,阻塞至完成)
     * 5. 把 [NativeExecutionResult] 映射为 [TaskExecutionResult],同步 [taskStore]
     * 6. 返回 `BridgeResult.Success(result)`
     *
     * 若 [bridge] 为 null,立即标记任务为 FAILED 并返回失败。
     *
     * @param description 任务描述
     * @param preset      策略预设名(AGGRESSIVE / BALANCED / CONSERVATIVE / DEBUG)
     * @param onProgress  进度回调(0.0 ~ 1.0),由 C++ 侧 `TASK_PROGRESS` 事件驱动
     * @return 执行结果
     */
    suspend fun startTask(
        description: String,
        preset: String = "BALANCED",
        onProgress: ((Float, String) -> Unit)? = null
    ): BridgeResult<TaskExecutionResult> = bridgeRun {
        val taskId = Trace.newId("rage")
        ApexLog.i(
            ApexSuite.ApkId.RAGE,
            "[RageEngine] startTask: ${description.take(60)} (preset=$preset, bridge=${bridge != null})"
        )

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

        val activeBridge = bridge ?: run {
            val msg = "RageNativeBridge not wired — rage mode requires :rage-jni to be injected"
            taskStore.updateStatus(
                taskId = taskId,
                status = RageTaskStatus.FAILED,
                errorMessage = msg
            )
            _events.tryEmit(RageEvent.TaskFailed(taskId, msg))
            throw IllegalStateException(msg)
        }

        val nativeTask = NativeTask(
            id = taskId,
            description = description,
            preset = preset
        )
        val nativeConfig: NativeRageConfig = config.toNative()

        try {
            val result: TaskExecutionResult = coroutineScope {
                // 订阅 bridge.events 期间翻译为 RageEvent + 调 onProgress
                // (子协程在 startTask 返回时自动取消)
                val collectJob = launch {
                    activeBridge.events.collect { ev: NativeEvent ->
                        // 只处理本任务或全局事件(taskId == null)
                        if (ev.taskId != null && ev.taskId != taskId) return@collect
                        ev.toRageEvent()?.let { _events.tryEmit(it) }
                        if (ev.type == "TASK_PROGRESS") {
                            onProgress?.invoke(ev.progress ?: 0f, ev.message ?: "")
                        }
                    }
                }
                try {
                    val nativeResult: NativeExecutionResult =
                        activeBridge.startTask(nativeTask, nativeConfig)
                    nativeResult.toKotlin()
                } finally {
                    collectJob.cancel()
                }
            }

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
     * 取消任务 —— 委托给 [bridge.cancelTask] (C++ 侧终止编排),
     * 同时同步本地 [taskStore] 状态。
     *
     * [bridge] 为 null 时仅更新本地状态(best-effort)。
     */
    fun cancelTask(taskId: String): BridgeResult<Boolean> = bridgeRun {
        // 委托给 native 桥(best-effort,失败仅日志,不阻塞本地状态更新)
        runCatching { bridge?.cancelTask(taskId) }
            .onFailure { ApexLog.w(ApexSuite.ApkId.RAGE, "[RageEngine] bridge.cancelTask failed: ${it.message}") }

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
    fun listTasks(): BridgeResult<List<RageTask>> = bridgeRun { taskStore.list() }

    /** 获取单个任务。 */
    fun getTask(taskId: String): BridgeResult<RageTask?> = bridgeRun { taskStore.get(taskId) }

    /** 删除任务。 */
    fun deleteTask(taskId: String): BridgeResult<Boolean> = bridgeRun { taskStore.delete(taskId) }

    /** 清空全部任务。 */
    fun clearTasks(): BridgeResult<Int> = bridgeRun { taskStore.clear() }

    // ============================================================
    // 指标
    // ============================================================

    /**
     * 获取运行指标快照。
     *
     * 当前实现:回退到本地 [taskStore] 累计的指标(C++ 核心的实时指标
     * 通过 [NativeMetrics.toKotlin] 映射,但 [RageNativeBridge] 暂未
     * 单独暴露 getMetrics API —— 后续可扩展)。
     */
    fun getMetrics(): BridgeResult<RageMetrics> = bridgeRun {
        taskStore.getMetrics()
    }

    // ============================================================
    // 技能目录(Kotlin 侧,不变)
    // ============================================================

    /** 列出全部技能(31 个内置)。 */
    fun listSkills(): BridgeResult<List<RageSkillDescriptor>> = bridgeRun { skillCatalog.list() }

    /** 按 ID 查找技能。 */
    fun findSkill(id: String): BridgeResult<RageSkillDescriptor?> = bridgeRun { skillCatalog.find(id) }

    /** 按分类查询技能。 */
    fun findSkillsByCategory(category: RageSkillCategory): BridgeResult<List<RageSkillDescriptor>> = bridgeRun {
        skillCatalog.findByCategory(category)
    }

    /** 按名称查找技能。 */
    fun findSkillByName(name: String): BridgeResult<RageSkillDescriptor?> = bridgeRun {
        skillCatalog.findByName(name)
    }

    // ============================================================
    // 配置管理
    // ============================================================

    /**
     * 应用新配置 —— 仅更新本地 [config]。
     *
     * 实际编排参数在每次 [startTask] 调用时通过 [NativeRageConfig] 传给 C++ 核心,
     * 因此无需显式同步 bridge(无状态热更新)。
     */
    fun applyConfig(newConfig: RageModeConfig) {
        config = newConfig
        ApexLog.i(
            ApexSuite.ApkId.RAGE,
            "[RageEngine] config applied: concurrency=${newConfig.maxConcurrency}, retries=${newConfig.maxRetries}"
        )
    }

    /** 切换策略预设。 */
    fun switchPreset(preset: RageStrategyPreset) {
        applyConfig(RagePresets.forPreset(preset))
    }

    /** 按名称切换预设。 */
    fun switchPresetByName(name: String) {
        applyConfig(RagePresets.forName(name))
    }
}
