package com.apex.agent.kernel.burst.enhanced.pipeline.orchestrator

import com.apex.agent.kernel.burst.enhanced.pipeline.ExecutionPipelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * B31: 流水线编排器
 *
 * 完善流水线业务逻辑的核心编排层：
 * - 接收流水线定义 + 初始输入
 * - 编排步骤执行顺序
 * - 管理数据流转
 * - 协调错误处理
 * - 发射进度事件
 *
 * 这是对 B19 ExecutionPipelineEngine 的业务层封装
 */
class PipelineOrchestrator(
    private val engine: ExecutionPipelineEngine = ExecutionPipelineEngine(),
    private val maxConcurrentPipelines: Int = 10
) {

    /**
     * 流水线定义
     */
    data class PipelineDefinition(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val steps: List<ExecutionPipelineEngine.PipelineStep>,
        val config: PipelineConfig = PipelineConfig(),
        val tags: List<String> = emptyList()
    )

    /**
     * 流水线配置
     */
    data class PipelineConfig(
        val maxRetries: Int = 3,
        val timeoutMs: Long = 300_000L,
        val enableCheckpointing: Boolean = true,
        val checkpointIntervalMs: Long = 10_000L,
        val parallelism: Int = 4,
        val failFast: Boolean = true,
        val collectMetrics: Boolean = true
    )

    /**
     * 流水线执行结果
     */
    data class OrchestratedResult(
        val pipelineId: String,
        val pipelineName: String,
        val success: Boolean,
        val finalOutput: String,
        val stepCount: Int,
        val completedSteps: Int,
        val totalDurationMs: Long,
        val retryCount: Int,
        val checkpointSaved: Boolean,
        val metrics: PipelineMetrics,
        val error: String? = null
    )

    data class PipelineMetrics(
        val stepDurations: Map<String, Long>,
        val totalTokensUsed: Long,
        val peakMemoryMb: Int,
        val cacheHitCount: Int,
        val cacheMissCount: Int
    )

    /**
     * 进度事件
     */
    sealed class ProgressEvent {
        data class PipelineStarted(val pipelineId: String, val name: String, val stepCount: Int) : ProgressEvent()
        data class StepStarted(val pipelineId: String, val stepId: String, val stepIndex: Int, val totalSteps: Int) : ProgressEvent()
        data class StepCompleted(val pipelineId: String, val stepId: String, val durationMs: Long, val success: Boolean) : ProgressEvent()
        data class PipelineProgress(val pipelineId: String, val progress: Float, val currentStep: String) : ProgressEvent()
        data class PipelineCompleted(val pipelineId: String, val success: Boolean, val durationMs: Long) : ProgressEvent()
        data class PipelineFailed(val pipelineId: String, val stepId: String, val error: String) : ProgressEvent()
        data class CheckpointSaved(val pipelineId: String, val stepId: String) : ProgressEvent()
    }

    // ============ 状态 ============

    private val activePipelines = ConcurrentHashMap<String, PipelineExecutionContext>()
    private val completedResults = ConcurrentHashMap<String, OrchestratedResult>()
    private val _progressEvents = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 256)
    val progressEvents: SharedFlow<ProgressEvent> = _progressEvents.asSharedFlow()

    private data class PipelineExecutionContext(
        val definition: PipelineDefinition,
        val startTime: Long,
        val currentStepIndex: Int,
        val stepOutputs: MutableMap<String, String>,
        val retryCount: Int,
        val isCancelled: Boolean
    )

    /**
     * 技能执行器接口（业务侧注入）
     */
    fun interface PipelineSkillExecutor : ExecutionPipelineEngine.SkillExecutor

    /**
     * 执行流水线
     */
    suspend fun execute(
        definition: PipelineDefinition,
        initialInput: String,
        executor: PipelineSkillExecutor
    ): OrchestratedResult = withContext(Dispatchers.Default) {
        val pipelineId = definition.id
        val startTime = System.currentTimeMillis()

        // 检查并发限制
        if (activePipelines.size >= maxConcurrentPipelines) {
            return@withContext OrchestratedResult(
                pipelineId = pipelineId,
                pipelineName = definition.name,
                success = false,
                finalOutput = "",
                stepCount = definition.steps.size,
                completedSteps = 0,
                totalDurationMs = 0,
                retryCount = 0,
                checkpointSaved = false,
                metrics = PipelineMetrics(emptyMap(), 0, 0, 0, 0),
                error = "并发流水线数已达上限 ($maxConcurrentPipelines)"
            )
        }

        // 注册上下文
        val context = PipelineExecutionContext(
            definition = definition,
            startTime = startTime,
            currentStepIndex = 0,
            stepOutputs = mutableMapOf(),
            retryCount = 0,
            isCancelled = false
        )
        activePipelines[pipelineId] = context

        // 发射开始事件
        _progressEvents.emit(ProgressEvent.PipelineStarted(pipelineId, definition.name, definition.steps.size))

        // 委托给引擎执行
        val result = engine.execute(pipelineId, definition.steps, initialInput, executor)

        // 构建结果
        val orchestrated = OrchestratedResult(
            pipelineId = pipelineId,
            pipelineName = definition.name,
            success = result.success,
            finalOutput = result.output,
            stepCount = definition.steps.size,
            completedSteps = result.stepResults.count { it.value.success },
            totalDurationMs = System.currentTimeMillis() - startTime,
            retryCount = 0,
            checkpointSaved = definition.config.enableCheckpointing,
            metrics = PipelineMetrics(
                stepDurations = result.stepResults.mapValues { it.value.durationMs },
                totalTokensUsed = 0,
                peakMemoryMb = 0,
                cacheHitCount = 0,
                cacheMissCount = 0
            ),
            error = result.error
        )

        // 发射完成事件
        if (result.success) {
            _progressEvents.emit(ProgressEvent.PipelineCompleted(pipelineId, true, orchestrated.totalDurationMs))
        } else {
            val failedStep = result.stepResults.entries.firstOrNull { !it.value.success }?.key ?: "unknown"
            _progressEvents.emit(ProgressEvent.PipelineFailed(pipelineId, failedStep, result.error ?: "未知错误"))
        }

        // 清理
        activePipelines.remove(pipelineId)
        completedResults[pipelineId] = orchestrated

        orchestrated
    }

    /**
     * 批量执行流水线
     */
    suspend fun executeBatch(
        definitions: List<Pair<PipelineDefinition, String>>,
        executor: PipelineSkillExecutor
    ): List<OrchestratedResult> = coroutineScope {
        definitions.map { (def, input) ->
            async { execute(def, input, executor) }
        }.awaitAll()
    }

    /**
     * 取消流水线
     */
    fun cancel(pipelineId: String): Boolean {
        val context = activePipelines[pipelineId] ?: return false
        activePipelines[pipelineId] = context.copy(isCancelled = true)
        return true
    }

    /**
     * 获取活跃流水线
     */
    fun getActivePipelines(): List<String> = activePipelines.keys.toList()

    /**
     * 获取结果
     */
    fun getResult(pipelineId: String): OrchestratedResult? = completedResults[pipelineId]

    /**
     * 获取所有结果
     */
    fun getAllResults(): List<OrchestratedResult> = completedResults.values.toList()

    /**
     * 获取统计
     */
    fun getStats(): OrchestratorStats {
        val results = completedResults.values
        return OrchestratorStats(
            totalExecuted = results.size,
            totalSuccess = results.count { it.success },
            totalFailed = results.count { !it.success },
            avgDurationMs = if (results.isNotEmpty()) results.map { it.totalDurationMs }.average().toLong() else 0,
            activeCount = activePipelines.size
        )
    }

    data class OrchestratorStats(
        val totalExecuted: Int,
        val totalSuccess: Int,
        val totalFailed: Int,
        val avgDurationMs: Long,
        val activeCount: Int
    )

    /**
     * 生成执行报告
     */
    fun generateReport(pipelineId: String): String {
        val result = completedResults[pipelineId] ?: return "流水线不存在"
        val sb = StringBuilder()
        sb.appendLine("═══ 流水线执行报告 ═══")
        sb.appendLine("ID: ${result.pipelineId}")
        sb.appendLine("名称: ${result.pipelineName}")
        sb.appendLine("结果: ${if (result.success) "✓ 成功" else "✗ 失败"}")
        sb.appendLine("步骤: ${result.completedSteps}/${result.stepCount}")
        sb.appendLine("耗时: ${result.totalDurationMs}ms")
        if (result.error != null) sb.appendLine("错误: ${result.error}")
        sb.appendLine()
        sb.appendLine("步骤耗时:")
        result.metrics.stepDurations.forEach { (stepId, duration) ->
            sb.appendLine("  $stepId: ${duration}ms")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
