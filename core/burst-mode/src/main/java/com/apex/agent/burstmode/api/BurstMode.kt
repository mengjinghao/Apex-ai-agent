package com.apex.agent.burstmode.api

import com.apex.agent.burstmode.config.BurstModeConfig
import com.apex.agent.burstmode.config.BurstProfile
import com.apex.agent.burstmode.exception.BurstModeException
import com.apex.agent.burstmode.monitor.BurstMetrics
import com.apex.agent.burstmode.monitor.BurstMetricsSnapshot
import com.apex.agent.burstmode.preset.BurstPreset
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.kernel.burst.KernelState
import com.apex.agent.plugins.burst.base.BurstSkillResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 狂暴模式对外门面 API。
 *
 * 这是业务侧使用狂暴模式的唯一入口，封装了 [BurstKernel] 的复杂性，
 * 提供简洁、类型安全、易于理解的 API。
 *
 * # 设计目标
 *
 * - **简洁**：业务侧只需 3 步即可使用（配置 → 启动 → 执行）
 * - **类型安全**：所有配置项有明确类型，避免字符串拼写错误
 * - **可观测**：内置指标收集和状态观察
 * - **可扩展**：通过 [BurstPreset] 支持预设场景，通过 [BurstProfile] 支持配置切换
 * - **容错**：统一的异常处理，通过 [BurstModeException] 暴露错误
 *
 * # 使用示例
 *
 * ## 基本使用
 * ```
 * val burstMode = BurstMode.create(context)
 *     .withPreset(BurstPreset.BALANCED)
 *     .initialize()
 *
 * val task = BurstTask(
 *     id = "task_1",
 *     description = "分析这段代码的潜在 bug",
 *     complexity = BurstTask.Complexity.MEDIUM
 * )
 * val result = burstMode.execute(task)
 * ```
 *
 * ## 自定义配置
 * ```
 * val burstMode = BurstMode.create(context)
 *     .withConfig(
 *         BurstModeConfig.builder()
 *             .maxConcurrency(8)
 *             .timeoutMs(60_000)
 *             .enableAdaptiveOptimization(true)
 *             .build()
 *     )
 *     .initialize()
 * ```
 *
 * ## 观察状态
 * ```
 * burstMode.state.collect { state ->
 *     when (state) {
 *         KernelState.RUNNING -> showRunningIndicator()
 *         KernelState.STOPPED -> hideIndicator()
 *         else -> {}
 *     }
 * }
 * ```
 */
interface BurstMode {

    /**
     * 当前狂暴模式状态。
     * 状态变化通过 [StateFlow] 暴露，可被 UI 层观察。
     */
    val state: StateFlow<KernelState>

    /**
     * 当前生效的配置。
     * 可通过 [updateConfig] 动态修改。
     */
    val currentConfig: BurstModeConfig

    /**
     * 当前生效的预设。
     * 如果未设置预设，返回 [BurstPreset.CUSTOM]。
     */
    val currentPreset: BurstPreset

    /**
     * 是否已初始化。
     * 初始化后才能调用 [execute]。
     */
    val isInitialized: Boolean

    /**
     * 技能管理器。
     *
     * 用于注册、注销、查询自定义技能。
     */
    val skillManager: SkillManager

    /**
     * 任务队列。
     *
     * 用于管理待执行任务的优先级队列。
     */
    val taskQueue: TaskQueue

    /**
     * 检查点管理器。
     *
     * 用于任务断点续传。
     */
    val checkpointManager: com.apex.agent.burstmode.checkpoint.CheckpointManager

    /**
     * 技能选择器。
     *
     * 智能选择最优技能执行任务。
     */
    val skillSelector: com.apex.agent.burstmode.selection.SkillSelector

    /**
     * 限流器。
     *
     * 控制任务执行速率，防止系统过载。
     */
    val rateLimiter: com.apex.agent.burstmode.ratelimit.RateLimiter

    /**
     * 负载监控器。
     *
     * 监控系统资源使用情况。
     */
    val loadMonitor: com.apex.agent.burstmode.ratelimit.LoadMonitor

    /**
     * 结果缓存。
     *
     * 缓存任务执行结果，避免重复计算。
     */
    val resultCache: com.apex.agent.burstmode.cache.ResultCache

    /**
     * 重试执行器。
     *
     * 提供任务失败后的重试能力。
     */
    val retryExecutor: com.apex.agent.burstmode.retry.RetryExecutor

    /**
     * 超时管理器。
     *
     * 管理任务执行的超时控制。
     */
    val timeoutManager: com.apex.agent.burstmode.timeout.TimeoutManager

    /**
     * 任务上下文作用域。
     *
     * 跨任务共享的流程变量容器。
     */
    val contextScope: com.apex.agent.burstmode.context.ContextScope

    /**
     * 添加事件监听器。
     *
     * @param listener 监听器实例
     * @return true 添加成功，false 已存在
     */
    fun addListener(listener: BurstModeListener): Boolean

    /**
     * 移除事件监听器。
     *
     * @param listener 监听器实例
     * @return true 移除成功，false 不存在
     */
    fun removeListener(listener: BurstModeListener): Boolean

    /**
     * 移除所有事件监听器。
     */
    fun clearListeners()

    /**
     * 执行狂暴模式任务。
     *
     * @param task 要执行的任务
     * @return 执行结果
     * @throws BurstModeException.NotInitialized 如果未初始化
     * @throws BurstModeException.Timeout 如果任务超时
     * @throws BurstModeException.ExecutionFailed 如果执行失败
     */
    suspend fun execute(task: BurstTask): BurstSkillResult

    /**
     * 批量执行任务。
     *
     * 并发执行多个任务，所有任务完成后返回。
     * 并发度由 [BurstModeConfig.maxConcurrency] 控制。
     *
     * @param tasks 任务列表
     * @return 结果列表（顺序与输入一致）
     */
    suspend fun executeBatch(tasks: List<BurstTask>): List<BurstSkillResult>

    /**
     * 按依赖图执行任务。
     *
     * 支持任务间的 DAG 依赖关系，自动按拓扑顺序执行，
     * 同层任务并行执行。
     *
     * @param graph 任务依赖图
     * @param strategy 依赖失败处理策略
     * @return 每个任务的执行结果
     */
    suspend fun executeWithDependencyGraph(
        graph: com.apex.agent.burstmode.execution.TaskDependencyGraph,
        strategy: com.apex.agent.burstmode.execution.DependencyExecutionStrategy = com.apex.agent.burstmode.execution.DependencyExecutionStrategy.SKIP_ON_FAILURE
    ): List<com.apex.agent.burstmode.execution.DependencyExecutionResult>

    /**
     * 按技能链执行任务。
     *
     * @param task 初始任务
     * @param chain 技能链
     * @return 最终结果
     */
    suspend fun executeWithChain(
        task: BurstTask,
        chain: com.apex.agent.burstmode.execution.SkillChain
    ): BurstSkillResult

    /**
     * 异步执行任务，返回 [kotlinx.coroutines.Deferred]。
     *
     * 适用于需要取消或超时控制的场景。
     */
    fun executeAsync(task: BurstTask): kotlinx.coroutines.Deferred<BurstSkillResult>

    /**
     * 更新配置。
     *
     * 部分配置项会立即生效（如超时时间），
     * 部分需要重启才能生效（如最大并发数）。
     *
     * @param config 新配置
     */
    suspend fun updateConfig(config: BurstModeConfig)

    /**
     * 切换预设。
     *
     * 切换预设会自动应用预设的配置。
     * 如果预设需要的资源不可用（如本地 LLM），会抛出异常。
     *
     * @param preset 目标预设
     */
    suspend fun switchPreset(preset: BurstPreset)

    /**
     * 获取当前指标快照。
     *
     * 包含执行次数、成功率、平均耗时等。
     */
    fun getMetrics(): BurstMetricsSnapshot

    /**
     * 观察指标变化。
     * 每次任务完成后会发出新的快照。
     */
    fun observeMetrics(): StateFlow<BurstMetricsSnapshot>

    /**
     * 重置指标。
     */
    fun resetMetrics()

    /**
     * 暂停狂暴模式。
     *
     * 暂停后正在执行的任务会完成，但新任务不会被接受。
     */
    suspend fun pause()

    /**
     * 恢复狂暴模式。
     */
    suspend fun resume()

    /**
     * 关闭狂暴模式，释放所有资源。
     */
    suspend fun shutdown()

    companion object {
        /**
         * 创建 [BurstMode] 构建器。
         *
         * @param context Android Context
         * @return 构建器实例
         */
        fun create(context: android.content.Context): BurstModeBuilder {
            return BurstModeBuilder(context)
        }
    }
}
