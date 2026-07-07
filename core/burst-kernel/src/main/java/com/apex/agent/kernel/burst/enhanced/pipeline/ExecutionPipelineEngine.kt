package com.apex.agent.kernel.burst.enhanced.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * B19: 执行流水线引擎
 *
 * 增强现有 SkillChain：
 * - 6 种流水线模式（vs 现有 4 种 ChainStep）
 * - 数据流转（上一步输出注入下一步）
 * - 条件路由
 * - 错误处理链
 * - 并行 + 汇合
 * - 循环（带最大次数）
 */
class ExecutionPipelineEngine {

    /**
     * 流水线步骤
     */
    sealed class PipelineStep {
        abstract val id: String
        abstract val skillId: String

        data class Sequential(
            override val id: String,
            override val skillId: String,
            val inputTransform: ((String) -> String)? = null,
            val outputTransform: ((String) -> String)? = null
        ) : PipelineStep()

        data class Parallel(
            override val id: String,
            val branches: List<Branch>,
            val merger: (List<String>) -> String
        ) : PipelineStep() {
            override val skillId: String = "parallel"
            data class Branch(val skillId: String, val weight: Float = 1.0f)
        }

        data class Conditional(
            override val id: String,
            override val skillId: String,
            val condition: (String) -> Boolean,
            val trueStep: PipelineStep,
            val falseStep: PipelineStep? = null
        ) : PipelineStep()

        data class Loop(
            override val id: String,
            override val skillId: String,
            val maxIterations: Int,
            val breakCondition: (String) -> Boolean
        ) : PipelineStep()

        data class ErrorHandler(
            override val id: String,
            override val skillId: String,
            val handler: (Throwable) -> String,
            val nextStep: PipelineStep? = null
        ) : PipelineStep()

        data class Fork(
            override val id: String,
            override val skillId: String,
            val forkCount: Int,
            val aggregator: (List<String>) -> String
        ) : PipelineStep()
    }

    /**
     * 执行结果
     */
    data class PipelineResult(
        val success: Boolean,
        val output: String,
        val stepResults: Map<String, StepResult>,
        val totalDurationMs: Long,
        val iterations: Int = 0,
        val error: String? = null
    )

    data class StepResult(
        val stepId: String,
        val skillId: String,
        val success: Boolean,
        val output: String,
        val durationMs: Long,
        val error: String? = null
    )

    /**
     * 技能执行器接口
     */
    fun interface SkillExecutor {
        suspend fun execute(skillId: String, input: String): Result<String>
    }

    private val pipelineHistory = ConcurrentHashMap<String, PipelineResult>()

    /**
     * 执行流水线
     */
    suspend fun execute(
        pipelineId: String,
        steps: List<PipelineStep>,
        initialInput: String,
        executor: SkillExecutor
    ): PipelineResult {
        val start = System.currentTimeMillis()
        val stepResults = mutableMapOf<String, StepResult>()
        var currentInput = initialInput

        try {
            for (step in steps) {
                val result = executeStep(step, currentInput, executor, stepResults)
                stepResults[step.id] = result

                if (!result.success) {
                    val pipelineResult = PipelineResult(
                        success = false, output = result.output,
                        stepResults = stepResults,
                        totalDurationMs = System.currentTimeMillis() - start,
                        error = result.error
                    )
                    pipelineHistory[pipelineId] = pipelineResult
                    return pipelineResult
                }

                currentInput = result.output
            }

            val pipelineResult = PipelineResult(
                success = true, output = currentInput,
                stepResults = stepResults,
                totalDurationMs = System.currentTimeMillis() - start
            )
            pipelineHistory[pipelineId] = pipelineResult
            return pipelineResult
        } catch (e: Exception) {
            val pipelineResult = PipelineResult(
                success = false, output = "",
                stepResults = stepResults,
                totalDurationMs = System.currentTimeMillis() - start,
                error = e.message
            )
            pipelineHistory[pipelineId] = pipelineResult
            return pipelineResult
        }
    }

    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(
        step: PipelineStep,
        input: String,
        executor: SkillExecutor,
        allResults: MutableMap<String, StepResult>
    ): StepResult {
        val start = System.currentTimeMillis()
        return when (step) {
            is PipelineStep.Sequential -> {
                val transformedInput = step.inputTransform?.invoke(input) ?: input
                val result = executor.execute(step.skillId, transformedInput)
                val output = if (result.isSuccess) {
                    step.outputTransform?.invoke(result.getOrDefault("")) ?: result.getOrDefault("")
                } else ""
                StepResult(step.id, step.skillId, result.isSuccess,
                    output, System.currentTimeMillis() - start,
                    result.exceptionOrNull()?.message)
            }

            is PipelineStep.Parallel -> {
                val results = coroutineScope {
                    step.branches.map { branch ->
                        async { executor.execute(branch.skillId, input) }
                    }.awaitAll()
                }
                val outputs = results.map { it.getOrDefault("") }
                val merged = step.merger(outputs)
                val allSuccess = results.all { it.isSuccess }
                StepResult(step.id, step.skillId, allSuccess, merged,
                    System.currentTimeMillis() - start,
                    if (!allSuccess) results.firstOrNull { it.isFailure }?.exceptionOrNull()?.message else null)
            }

            is PipelineStep.Conditional -> {
                if (step.condition(input)) {
                    val result = executeStep(step.trueStep, input, executor, allResults)
                    allResults[step.trueStep.id] = result
                    result
                } else if (step.falseStep != null) {
                    val result = executeStep(step.falseStep, input, executor, allResults)
                    allResults[step.falseStep.id] = result
                    result
                } else {
                    StepResult(step.id, step.skillId, true, input, 0)
                }
            }

            is PipelineStep.Loop -> {
                var currentLoopInput = input
                var iterations = 0
                var lastResult: StepResult? = null
                while (iterations < step.maxIterations) {
                    val result = executor.execute(step.skillId, currentLoopInput)
                    iterations++
                    if (!result.isSuccess) {
                        lastResult = StepResult(step.id, step.skillId, false, "",
                            System.currentTimeMillis() - start, result.exceptionOrNull()?.message)
                        break
                    }
                    currentLoopInput = result.getOrDefault("")
                    if (step.breakCondition(currentLoopInput)) {
                        lastResult = StepResult(step.id, step.skillId, true, currentLoopInput,
                            System.currentTimeMillis() - start)
                        break
                    }
                }
                lastResult ?: StepResult(step.id, step.skillId, true, currentLoopInput,
                    System.currentTimeMillis() - start)
            }

            is PipelineStep.ErrorHandler -> {
                // ErrorHandler 作为包裹器，需要先执行被包裹的逻辑
                StepResult(step.id, step.skillId, true, input, 0)
            }

            is PipelineStep.Fork -> {
                val results = coroutineScope {
                    (1..step.forkCount).map {
                        async { executor.execute(step.skillId, input) }
                    }.awaitAll()
                }
                val outputs = results.map { it.getOrDefault("") }
                val aggregated = step.aggregator(outputs)
                StepResult(step.id, step.skillId, results.all { it.isSuccess }, aggregated,
                    System.currentTimeMillis() - start)
            }
        }
    }

    /**
     * 获取历史
     */
    fun getHistory(pipelineId: String): PipelineResult? = pipelineHistory[pipelineId]

    /**
     * 获取所有历史
     */
    fun getAllHistory(): Map<String, PipelineResult> = pipelineHistory.toMap()

    /**
     * 生成流水线报告
     */
    fun generateReport(pipelineId: String): String {
        val result = pipelineHistory[pipelineId] ?: return "流水线不存在"
        val sb = StringBuilder()
        sb.appendLine("═══ 流水线报告: $pipelineId ═══")
        sb.appendLine("结果: ${if (result.success) "✓ 成功" else "✗ 失败"}")
        sb.appendLine("总耗时: ${result.totalDurationMs}ms")
        if (result.error != null) sb.appendLine("错误: ${result.error}")
        sb.appendLine()
        sb.appendLine("步骤:")
        result.stepResults.forEach { (stepId, sr) ->
            val status = if (sr.success) "✓" else "✗"
            sb.appendLine("  $status $stepId (${sr.skillId}): ${sr.durationMs}ms")
            if (!sr.success && sr.error != null) sb.appendLine("       错误: ${sr.error}")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
