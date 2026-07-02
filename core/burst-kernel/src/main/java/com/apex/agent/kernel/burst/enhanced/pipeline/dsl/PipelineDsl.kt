package com.apex.agent.kernel.burst.enhanced.pipeline.dsl

import com.apex.agent.kernel.burst.enhanced.pipeline.ExecutionPipelineEngine
import com.apex.agent.kernel.burst.enhanced.pipeline.orchestrator.PipelineOrchestrator

/**
 * B37: 流水线 DSL 构建器
 *
 * 流式 API 构建复杂流水线：
 * ```kotlin
 * val pipeline = pipeline("我的流水线") {
 *     description = "示例"
 *     maxRetries = 3
 *     then("step1", "reasoning.react") { input ->
 *         "增强: $input"
 *     }
 *     parallel("step2") {
 *         branch("reasoning.tree-of-thoughts")
 *         branch("reasoning.multi-hop")
 *     } merge { results ->
 *         results.joinToString("\n")
 *     }
 *     branch("step3", "reasoning.react") { input ->
 *         input.contains("urgent")
 *     } onTrue {
 *         then("urgent", "berserk_execution")
 *     } onFalse {
 *         then("normal", "adaptive_execution")
 *     }
 *     onError { error ->
 *         "错误处理: ${error.message}"
 *     }
 * }
 * ```
 */
@DslMarker
annotation class PipelineDsl

@PipelineDsl
class PipelineBuilder(private val name: String) {
    var description: String = ""
    var maxRetries: Int = 3
    var timeoutMs: Long = 300_000L
    var enableCheckpointing: Boolean = true
    var failFast: Boolean = true
    var parallelism: Int = 4
    var tags: List<String> = emptyList()

    private val steps = mutableListOf<ExecutionPipelineEngine.PipelineStep>()
    private var stepCounter = 0

    /**
     * 串行步骤
     */
    fun then(
        skillId: String,
        inputTransform: ((String) -> String)? = null,
        outputTransform: ((String) -> String)? = null
    ) {
        stepCounter++
        steps.add(ExecutionPipelineEngine.PipelineStep.Sequential(
            id = "step_$stepCounter",
            skillId = skillId,
            inputTransform = inputTransform,
            outputTransform = outputTransform
        ))
    }

    /**
     * 串行步骤（带自定义 ID）
     */
    fun then(
        id: String,
        skillId: String,
        inputTransform: ((String) -> String)? = null,
        outputTransform: ((String) -> String)? = null
    ) {
        stepCounter++
        steps.add(ExecutionPipelineEngine.PipelineStep.Sequential(
            id = id, skillId = skillId,
            inputTransform = inputTransform, outputTransform = outputTransform
        ))
    }

    /**
     * 并行步骤
     */
    fun parallel(merge: (List<String>) -> String, block: ParallelBuilder.() -> Unit) {
        stepCounter++
        val builder = ParallelBuilder()
        builder.block()
        steps.add(ExecutionPipelineEngine.PipelineStep.Parallel(
            id = "parallel_$stepCounter",
            branches = builder.branches,
            merger = merge
        ))
    }

    /**
     * 条件分支
     */
    fun branch(
        skillId: String,
        condition: (String) -> Boolean,
        block: BranchBuilder.() -> Unit
    ) {
        stepCounter++
        val builder = BranchBuilder()
        builder.block()
        steps.add(ExecutionPipelineEngine.PipelineStep.Conditional(
            id = "branch_$stepCounter",
            skillId = skillId,
            condition = condition,
            trueStep = builder.trueStep ?: ExecutionPipelineEngine.PipelineStep.Sequential("noop_true", "reasoning.react"),
            falseStep = builder.falseStep
        ))
    }

    /**
     * 循环步骤
     */
    fun loop(skillId: String, maxIterations: Int = 10, breakCondition: (String) -> Boolean) {
        stepCounter++
        steps.add(ExecutionPipelineEngine.PipelineStep.Loop(
            id = "loop_$stepCounter",
            skillId = skillId,
            maxIterations = maxIterations,
            breakCondition = breakCondition
        ))
    }

    /**
     * Fork 步骤
     */
    fun fork(skillId: String, count: Int, aggregator: (List<String>) -> String) {
        stepCounter++
        steps.add(ExecutionPipelineEngine.PipelineStep.Fork(
            id = "fork_$stepCounter",
            skillId = skillId,
            forkCount = count,
            aggregator = aggregator
        ))
    }

    /**
     * 构建
     */
    fun build(): PipelineOrchestrator.PipelineDefinition {
        return PipelineOrchestrator.PipelineDefinition(
            name = name,
            description = description,
            steps = steps.toList(),
            config = PipelineOrchestrator.PipelineConfig(
                maxRetries = maxRetries,
                timeoutMs = timeoutMs,
                enableCheckpointing = enableCheckpointing,
                failFast = failFast,
                parallelism = parallelism
            ),
            tags = tags
        )
    }
}

@PipelineDsl
class ParallelBuilder {
    val branches = mutableListOf<ExecutionPipelineEngine.PipelineStep.Parallel.Branch>()

    fun branch(skillId: String, weight: Float = 1.0f) {
        branches.add(ExecutionPipelineEngine.PipelineStep.Parallel.Branch(skillId, weight))
    }
}

@PipelineDsl
class BranchBuilder {
    var trueStep: ExecutionPipelineEngine.PipelineStep? = null
    var falseStep: ExecutionPipelineEngine.PipelineStep? = null

    fun onTrue(skillId: String) {
        trueStep = ExecutionPipelineEngine.PipelineStep.Sequential("true_branch", skillId)
    }

    fun onFalse(skillId: String) {
        falseStep = ExecutionPipelineEngine.PipelineStep.Sequential("false_branch", skillId)
    }
}

/**
 * DSL 入口
 */
fun pipeline(name: String, block: PipelineBuilder.() -> Unit): PipelineOrchestrator.PipelineDefinition {
    val builder = PipelineBuilder(name)
    builder.block()
    return builder.build()
}
