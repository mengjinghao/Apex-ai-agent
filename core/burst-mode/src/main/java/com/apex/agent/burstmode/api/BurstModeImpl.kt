package com.apex.agent.burstmode.api

import android.content.Context
import android.util.Log
import com.apex.agent.burstmode.config.BurstModeConfig
import com.apex.agent.burstmode.config.BurstProfile
import com.apex.agent.burstmode.config.LlmProvider
import com.apex.agent.burstmode.exception.BurstModeException
import com.apex.agent.burstmode.monitor.BurstMetrics
import com.apex.agent.burstmode.monitor.BurstMetricsSnapshot
import com.apex.agent.burstmode.preset.BurstPreset
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.kernel.burst.BurstExecutionEngine
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.kernel.burst.KernelState
import com.apex.agent.plugins.burst.base.BurstSkillContext
import com.apex.agent.plugins.burst.base.BurstSkillManifest
import com.apex.agent.plugins.burst.base.BurstSkillResult
import com.apex.agent.plugins.burst.base.IBurstSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap

/**
 * [BurstMode] 的默认实现。
 *
 * 封装 [BurstKernel]，提供简洁的对外 API。
 * 内部管理配置、预设、指标、健康检查、并发控制。
 */
internal class BurstModeImpl(
    private val context: Context,
    @Volatile private var config: BurstModeConfig,
    @Volatile private var preset: BurstPreset,
    private val profile: BurstProfile,
    private val autoStart: Boolean,
    private val metricsEnabled: Boolean,
    private val healthCheckEnabled: Boolean,
    private val healthCheckIntervalMs: Long
) : BurstMode {

    companion object {
        private const val TAG = "BurstMode"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val metrics = if (metricsEnabled) BurstMetrics() else null

    /** 技能管理器。 */
    private val _skillManager = SkillManagerImpl()
    override val skillManager: SkillManager = _skillManager

    /** 任务队列。 */
    private val _taskQueue = TaskQueueImpl()
    override val taskQueue: TaskQueue = _taskQueue

    /** 事件分发器。 */
    private val eventDispatcher = EventDispatcher()

    /** 检查点管理器。 */
    private val _checkpointManager = com.apex.agent.burstmode.checkpoint.CheckpointManager(
        com.apex.agent.burstmode.checkpoint.InMemoryCheckpointStore()
    )
    override val checkpointManager: com.apex.agent.burstmode.checkpoint.CheckpointManager = _checkpointManager

    /** 技能选择器。 */
    private val _skillSelector = com.apex.agent.burstmode.selection.SkillSelector()
    override val skillSelector: com.apex.agent.burstmode.selection.SkillSelector = _skillSelector

    /** 限流器。 */
    private val _rateLimiter = com.apex.agent.burstmode.ratelimit.RateLimiter(
        com.apex.agent.burstmode.ratelimit.RateLimitStrategy.TokenBucket(
            capacity = config.maxConcurrency * 2,
            refillRateMs = 1000L / config.maxConcurrency
        )
    )
    override val rateLimiter: com.apex.agent.burstmode.ratelimit.RateLimiter = _rateLimiter

    /** 负载监控器。 */
    private val _loadMonitor = com.apex.agent.burstmode.ratelimit.LoadMonitor(
        memoryThresholdMb = config.memoryBudgetMb,
        concurrencyThreshold = config.maxConcurrency
    )
    override val loadMonitor: com.apex.agent.burstmode.ratelimit.LoadMonitor = _loadMonitor

    /** 结果缓存。 */
    private val _resultCache = com.apex.agent.burstmode.cache.ResultCache(
        strategy = com.apex.agent.burstmode.cache.CacheStrategy.TimeBased(
            ttlMs = 300_000,  // 5 分钟
            maxSize = 100
        )
    )
    override val resultCache: com.apex.agent.burstmode.cache.ResultCache = _resultCache

    /** 重试执行器。 */
    private val _retryExecutor = com.apex.agent.burstmode.retry.RetryExecutor(
        strategy = com.apex.agent.burstmode.retry.RetryStrategy.ExponentialBackoff(
            maxRetries = config.maxRetries,
            baseDelayMs = config.retryDelayMs
        )
    )
    override val retryExecutor: com.apex.agent.burstmode.retry.RetryExecutor = _retryExecutor

    /** 超时管理器。 */
    private val _timeoutManager = com.apex.agent.burstmode.timeout.TimeoutManager(
        strategy = com.apex.agent.burstmode.timeout.TimeoutStrategy.Fixed(config.defaultTimeoutMs)
    )
    override val timeoutManager: com.apex.agent.burstmode.timeout.TimeoutManager = _timeoutManager

    /** 上下文作用域。 */
    private val _contextScope = com.apex.agent.burstmode.context.ContextScope()
    override val contextScope: com.apex.agent.burstmode.context.ContextScope = _contextScope

    /** 并发控制信号量。 */
    private var concurrencySemaphore = Semaphore(config.maxConcurrency)

    /** 是否已初始化。 */
    @Volatile
    private var initialized = false

    /** 是否已暂停。 */
    @Volatile
    private var paused = false

    /** 状态流。 */
    private val _state = MutableStateFlow(KernelState.STOPPED)
    override val state: StateFlow<KernelState> = _state.asStateFlow()

    /** 健康检查协程 Job。 */
    private var healthCheckJob: kotlinx.coroutines.Job? = null

    override val currentConfig: BurstModeConfig
        get() = config

    override val currentPreset: BurstPreset
        get() = preset

    override val isInitialized: Boolean
        get() = initialized

    override fun addListener(listener: BurstModeListener): Boolean {
        return eventDispatcher.addListener(listener)
    }

    override fun removeListener(listener: BurstModeListener): Boolean {
        return eventDispatcher.removeListener(listener)
    }

    override fun clearListeners() {
        eventDispatcher.clear()
    }

    /**
     * 初始化。
     * 由 [BurstModeBuilder.initialize] 调用，业务侧不直接调用。
     */
    fun initialize() {
        if (initialized) {
            Log.w(TAG, "BurstMode already initialized")
            return
        }

        try {
            _state.value = KernelState.STARTING
            Log.i(TAG, "Initializing BurstMode with preset=${preset.displayName}, profile=${profile.name}")

            // 重建并发信号量
            concurrencySemaphore = Semaphore(config.maxConcurrency)

            // 启动健康检查
            if (healthCheckEnabled) {
                startHealthCheck()
            }

            // 自动启动
            if (autoStart) {
                _state.value = KernelState.RUNNING
            } else {
                _state.value = KernelState.PAUSED
                paused = true
            }

            initialized = true
            Log.i(TAG, "BurstMode initialized successfully")
        } catch (e: Exception) {
            _state.value = KernelState.ERROR
            throw BurstModeException.KernelError("initialize", e)
        }
    }

    override suspend fun execute(task: BurstTask): BurstSkillResult {
        ensureInitialized()
        ensureNotPaused()

        // 检查缓存
        _resultCache.get(task)?.let { cached ->
            eventDispatcher.dispatch(BurstModeEvent.TaskSucceeded(task.id, cached))
            return cached
        }

        metrics?.onTaskStarted()
        eventDispatcher.dispatch(BurstModeEvent.TaskStarted(task.id, task.description))
        val startTime = System.currentTimeMillis()

        return try {
            val result = concurrencySemaphore.withPermit {
                withTimeoutOrNull(config.defaultTimeoutMs) {
                    executeTaskInternal(task)
                } ?: throw BurstModeException.Timeout(task.id, config.defaultTimeoutMs)
            }

            val elapsed = System.currentTimeMillis() - startTime
            if (result.success) {
                metrics?.onTaskSucceeded(
                    executionTimeMs = elapsed,
                    tokensProcessed = estimateTokens(task.description + (result.output ?: "")),
                    memoryMbSec = (config.memoryBudgetMb * elapsed / 1000).toLong()
                )
                eventDispatcher.dispatch(BurstModeEvent.TaskSucceeded(task.id, result))
                // 写入缓存
                _resultCache.put(task, result)
            } else {
                metrics?.onTaskFailed(elapsed, (config.memoryBudgetMb * elapsed / 1000).toLong())
                eventDispatcher.dispatch(BurstModeEvent.TaskFailed(task.id, result.errorMessage ?: "Unknown"))
            }
            result
        } catch (e: BurstModeException.Timeout) {
            metrics?.onTaskFailed(System.currentTimeMillis() - startTime, 0)
            eventDispatcher.dispatch(BurstModeEvent.TaskFailed(task.id, "Timeout after ${config.defaultTimeoutMs}ms"))
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            metrics?.onTaskCancelled()
            eventDispatcher.dispatch(BurstModeEvent.TaskCancelled(task.id, e.message))
            throw e
        } catch (e: Exception) {
            metrics?.onTaskFailed(System.currentTimeMillis() - startTime, 0)
            eventDispatcher.dispatch(BurstModeEvent.TaskFailed(task.id, e.message ?: "Unknown"))
            throw BurstModeException.ExecutionFailed(task.id, e.message ?: "Unknown error", e)
        }
    }

    override suspend fun executeBatch(tasks: List<BurstTask>): List<BurstSkillResult> {
        ensureInitialized()
        ensureNotPaused()

        return kotlinx.coroutines.coroutineScope {
            tasks.map { task ->
                async { execute(task) }
            }.awaitAll()
        }
    }

    override fun executeAsync(task: BurstTask): Deferred<BurstSkillResult> {
        return scope.async {
            execute(task)
        }
    }

    override suspend fun executeWithDependencyGraph(
        graph: com.apex.agent.burstmode.execution.TaskDependencyGraph,
        strategy: com.apex.agent.burstmode.execution.DependencyExecutionStrategy
    ): List<com.apex.agent.burstmode.execution.DependencyExecutionResult> {
        ensureInitialized()
        ensureNotPaused()

        val layers = graph.getExecutionLayers()
        val results = mutableMapOf<String, com.apex.agent.burstmode.execution.DependencyExecutionResult>()
        val failedTaskIds = mutableSetOf<String>()
        var aborted = false

        for (layer in layers) {
            if (aborted) {
                // 标记剩余任务为跳过
                for (taskId in layer) {
                    results[taskId] = com.apex.agent.burstmode.execution.DependencyExecutionResult(
                        taskId = taskId,
                        success = false,
                        skipped = true,
                        errorMessage = "Skipped due to previous failure (ABORT strategy)"
                    )
                }
                continue
            }

            // 检查每个任务是否需要跳过（依赖失败）
            val toExecute = mutableListOf<Pair<String, BurstTask>>()
            for (taskId in layer) {
                val task = graph.getAllNodes()[taskId] ?: continue
                val deps = graph.getDependencies(taskId)

                when (strategy) {
                    com.apex.agent.burstmode.execution.DependencyExecutionStrategy.SKIP_ON_FAILURE -> {
                        val hasFailedDep = deps.any { it in failedTaskIds }
                        if (hasFailedDep) {
                            results[taskId] = com.apex.agent.burstmode.execution.DependencyExecutionResult(
                                taskId = taskId,
                                success = false,
                                skipped = true,
                                errorMessage = "Skipped: dependency failed"
                            )
                        } else {
                            toExecute.add(taskId to task)
                        }
                    }
                    com.apex.agent.burstmode.execution.DependencyExecutionStrategy.CONTINUE_ON_FAILURE -> {
                        toExecute.add(taskId to task)
                    }
                    com.apex.agent.burstmode.execution.DependencyExecutionStrategy.ABORT_ON_FAILURE -> {
                        if (failedTaskIds.isNotEmpty()) {
                            aborted = true
                            results[taskId] = com.apex.agent.burstmode.execution.DependencyExecutionResult(
                                taskId = taskId,
                                success = false,
                                skipped = true,
                                errorMessage = "Aborted: previous failure"
                            )
                        } else {
                            toExecute.add(taskId to task)
                        }
                    }
                }
            }

            // 并行执行本层任务
            val layerResults = kotlinx.coroutines.coroutineScope {
                toExecute.map { (taskId, task) ->
                    async {
                        try {
                            val result = execute(task)
                            com.apex.agent.burstmode.execution.DependencyExecutionResult(
                                taskId = taskId,
                                success = result.success,
                                errorMessage = if (!result.success) result.errorMessage else null
                            )
                        } catch (e: Exception) {
                            com.apex.agent.burstmode.execution.DependencyExecutionResult(
                                taskId = taskId,
                                success = false,
                                errorMessage = e.message
                            )
                        }
                    }
                }.awaitAll()
            }

            for (result in layerResults) {
                results[result.taskId] = result
                if (!result.success && !result.skipped) {
                    failedTaskIds.add(result.taskId)
                }
            }
        }

        // 按拓扑顺序返回结果
        val topoOrder = graph.topologicalSort()
        return topoOrder.mapNotNull { results[it] }
    }

    override suspend fun executeWithChain(
        task: BurstTask,
        chain: com.apex.agent.burstmode.execution.SkillChain
    ): BurstSkillResult {
        ensureInitialized()
        ensureNotPaused()

        eventDispatcher.dispatch(BurstModeEvent.TaskStarted(task.id, task.description))
        val startTime = System.currentTimeMillis()

        return try {
            val result = chain.execute(task)
            val elapsed = System.currentTimeMillis() - startTime

            if (result.success) {
                metrics?.onTaskSucceeded(
                    executionTimeMs = elapsed,
                    tokensProcessed = estimateTokens(task.description + (result.output ?: "")),
                    memoryMbSec = (config.memoryBudgetMb * elapsed / 1000).toLong()
                )
                eventDispatcher.dispatch(BurstModeEvent.TaskSucceeded(task.id, result))
            } else {
                metrics?.onTaskFailed(elapsed, (config.memoryBudgetMb * elapsed / 1000).toLong())
                eventDispatcher.dispatch(BurstModeEvent.TaskFailed(task.id, result.errorMessage ?: "Chain failed"))
            }
            result
        } catch (e: Exception) {
            metrics?.onTaskFailed(System.currentTimeMillis() - startTime, 0)
            eventDispatcher.dispatch(BurstModeEvent.TaskFailed(task.id, e.message ?: "Chain error"))
            throw BurstModeException.ExecutionFailed(task.id, e.message ?: "Chain execution error", e)
        }
    }

    override suspend fun updateConfig(newConfig: BurstModeConfig) {
        val oldConfig = config
        val oldConcurrency = config.maxConcurrency
        config = newConfig
        preset = BurstPreset.CUSTOM

        // 并发数变化时重建信号量
        if (newConfig.maxConcurrency != oldConcurrency) {
            concurrencySemaphore = Semaphore(newConfig.maxConcurrency)
            Log.i(TAG, "Concurrency updated: $oldConcurrency -> ${newConfig.maxConcurrency}")
        }

        eventDispatcher.dispatch(BurstModeEvent.ConfigUpdated(oldConfig, newConfig))
        Log.i(TAG, "Config updated (preset reset to CUSTOM)")
    }

    override suspend fun switchPreset(newPreset: BurstPreset) {
        val oldPreset = preset
        val newConfig = newPreset.toConfig()

        // 检查 LLM 可用性
        when (newConfig.llmProvider) {
            LlmProvider.LOCAL_LLAMA -> {
                Log.i(TAG, "Switching to LOCAL_LLAMA preset (verify llama availability in production)")
            }
            LlmProvider.DEEPSEEK, LlmProvider.CLAUDE, LlmProvider.OPENAI -> {
                if (newConfig.llmApiKey.isNullOrBlank()) {
                    throw BurstModeException.PresetSwitchFailed(
                        newPreset,
                        "API key required for ${newConfig.llmProvider.displayName}"
                    )
                }
            }
            LlmProvider.CUSTOM -> {
                if (newConfig.llmEndpoint.isNullOrBlank()) {
                    throw BurstModeException.PresetSwitchFailed(
                        newPreset,
                        "Custom endpoint required for CUSTOM provider"
                    )
                }
            }
            LlmProvider.NONE -> {
                // 无需检查
            }
        }

        config = newConfig
        preset = newPreset
        concurrencySemaphore = Semaphore(newConfig.maxConcurrency)

        eventDispatcher.dispatch(BurstModeEvent.PresetSwitched(oldPreset, newPreset))
        Log.i(TAG, "Preset switched to ${newPreset.displayName}")
    }

    override fun getMetrics(): BurstMetricsSnapshot {
        return metrics?.getSnapshot() ?: BurstMetricsSnapshot.EMPTY
    }

    override fun observeMetrics(): StateFlow<BurstMetricsSnapshot> {
        return metrics?.observe() ?: MutableStateFlow(BurstMetricsSnapshot.EMPTY).asStateFlow()
    }

    override fun resetMetrics() {
        metrics?.reset()
    }

    override suspend fun pause() {
        if (!initialized) return
        val oldState = _state.value
        paused = true
        _state.value = KernelState.PAUSED
        eventDispatcher.dispatch(BurstModeEvent.StateChanged(oldState, KernelState.PAUSED))
        Log.i(TAG, "BurstMode paused")
    }

    override suspend fun resume() {
        if (!initialized) return
        val oldState = _state.value
        paused = false
        _state.value = KernelState.RUNNING
        eventDispatcher.dispatch(BurstModeEvent.StateChanged(oldState, KernelState.RUNNING))
        Log.i(TAG, "BurstMode resumed")
    }

    override suspend fun shutdown() {
        Log.i(TAG, "Shutting down BurstMode...")
        val oldState = _state.value
        _state.value = KernelState.STOPPING

        healthCheckJob?.cancel()
        healthCheckJob = null

        _skillManager.clear()
        _taskQueue.clear()
        _resultCache.clear()
        _contextScope.clear()
        _timeoutManager.clearHistory()
        metrics?.reset()
        eventDispatcher.dispatch(BurstModeEvent.Shutdown)
        eventDispatcher.clear()

        scope.cancel()
        initialized = false
        _state.value = KernelState.STOPPED
        eventDispatcher.dispatch(BurstModeEvent.StateChanged(oldState, KernelState.STOPPED))
        Log.i(TAG, "BurstMode shut down")
    }

    // ===== 内部方法 =====

    private fun ensureInitialized() {
        if (!initialized) {
            throw BurstModeException.NotInitialized()
        }
    }

    private fun ensureNotPaused() {
        if (paused) {
            throw BurstModeException.ExecutionFailed(
                taskId = "unknown",
                errorMessage = "BurstMode is paused. Call resume() first."
            )
        }
    }

    /**
     * 内部任务执行。
     *
     * 当前实现返回占位结果。
     * 生产环境应：
     * 1. 从 BurstKernel.pluginLoader 获取合适的技能
     * 2. 构造 BurstSkillContext
     * 3. 调用 BurstExecutionEngine.execute
     */
    private suspend fun executeTaskInternal(task: BurstTask): BurstSkillResult {
        // 简化实现：直接返回成功结果
        // 生产环境应通过 BurstKernel 执行真实技能
        return BurstSkillResult(
            success = true,
            output = "BurstMode executed task: ${task.description.take(200)}",
            metrics = com.apex.agent.plugins.burst.base.SkillMetrics(
                executionTimeMs = 0,
                tokensProcessed = estimateTokens(task.description).toInt()
            )
        )
    }

    private fun startHealthCheck() {
        healthCheckJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(healthCheckIntervalMs)
                try {
                    performHealthCheck()
                } catch (e: Exception) {
                    Log.w(TAG, "Health check failed: ${e.message}")
                }
            }
        }
    }

    private fun performHealthCheck() {
        // 检查内存
        val runtime = Runtime.getRuntime()
        val usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        if (usedMemoryMb > config.memoryBudgetMb) {
            Log.w(TAG, "Memory usage ${usedMemoryMb}MB exceeds budget ${config.memoryBudgetMb}MB")
        }

        // 检查并发数
        val currentConc = metrics?.getSnapshot()?.currentConcurrency ?: 0
        if (currentConc >= config.maxConcurrency) {
            Log.d(TAG, "At max concurrency: $currentConc/${config.maxConcurrency}")
        }
    }

    private fun estimateTokens(text: String): Long {
        // 粗略估算：1 token ≈ 4 字符（英文）/ 1.5 字符（中文）
        return (text.length / 3L).coerceAtLeast(1)
    }
}
