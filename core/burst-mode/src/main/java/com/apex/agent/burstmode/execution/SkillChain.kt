package com.apex.agent.burstmode.execution

import com.apex.agent.burstmode.api.BurstMode
import com.apex.agent.burstmode.exception.BurstModeException
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.BurstSkillResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 技能链。
 *
 * 描述一组技能的执行流程，支持：
 * - 串行执行（管道式，前一个的输出作为后一个的输入）
 * - 并行执行（同时执行，全部完成后汇总）
 * - 条件分支（根据前一步结果选择后续路径）
 * - 错误处理（失败时停止/跳过/重试）
 *
 * # 使用示例
 *
 * ## 串行管道
 * ```
 * val chain = SkillChain()
 *     .then("analyze") { task -> analyzeSkill.execute(task) }
 *     .then("plan") { task -> planSkill.execute(task) }
 *     .then("execute") { task -> executeSkill.execute(task) }
 *
 * val result = chain.execute(initialTask)
 * ```
 *
 * ## 并行 + 汇总
 * ```
 * val chain = SkillChain()
 *     .parallel(
 *         { task -> searchSkill.execute(task) },
 *         { task -> knowledgeSkill.execute(task) }
 *     ) { results -> mergeResults(results) }
 *
 * val result = chain.execute(task)
 * ```
 *
 * ## 条件分支
 * ```
 * val chain = SkillChain()
 *     .then("classify") { task -> classifySkill.execute(task) }
 *     .branch(
 *         { result -> result.output?.contains("urgent") == true },
 *         { task -> urgentHandler.execute(task) }
 *     ) { task -> normalHandler.execute(task) }
 * ```
 */
class SkillChain private constructor(
    private val steps: List<ChainStep>
) {

    /**
     * 添加串行步骤。
     *
     * 前一步的输出（通过 [BurstSkillResult.output]）会附加到下一步任务的描述中。
     *
     * @param name 步骤名称（用于日志和调试）
     * @param executor 步骤执行函数
     */
    fun then(name: String, executor: suspend (BurstTask) -> BurstSkillResult): SkillChain {
        return SkillChain(steps + ChainStep.Sequential(name, executor))
    }

    /**
     * 添加并行步骤。
     *
     * 所有 executor 同时执行，全部完成后通过 merger 合并结果。
     *
     * @param executors 并行执行的函数列表
     * @param merger 结果合并函数
     */
    fun parallel(
        vararg executors: suspend (BurstTask) -> BurstSkillResult,
        merger: (List<BurstSkillResult>) -> BurstSkillResult
    ): SkillChain {
        return SkillChain(steps + ChainStep.Parallel(executors.toList(), merger))
    }

    /**
     * 添加条件分支。
     *
     * @param condition 条件判断函数（接收上一步结果）
     * @param ifTrue 条件为真时执行
     * @param ifFalse 条件为假时执行
     */
    fun branch(
        condition: (BurstSkillResult) -> Boolean,
        ifTrue: suspend (BurstTask) -> BurstSkillResult,
        ifFalse: suspend (BurstTask) -> BurstSkillResult
    ): SkillChain {
        return SkillChain(steps + ChainStep.Branch(condition, ifTrue, ifFalse))
    }

    /**
     * 添加错误处理步骤。
     *
     * 当前面任意步骤失败时，执行此 handler。
     * 如果 handler 返回成功结果，链继续；否则中止。
     *
     * @param handler 错误处理函数
     */
    fun onError(handler: suspend (BurstTask, BurstSkillResult, Exception) -> BurstSkillResult): SkillChain {
        return SkillChain(steps + ChainStep.ErrorHandler(handler))
    }

    /**
     * 执行技能链。
     *
     * @param initialTask 初始任务
     * @return 最终结果
     */
    suspend fun execute(initialTask: BurstTask): BurstSkillResult {
        var currentTask = initialTask
        var lastResult: BurstSkillResult? = null

        for (step in steps) {
            try {
                val result = when (step) {
                    is ChainStep.Sequential -> {
                        val taskWithPrevOutput = if (lastResult?.output != null) {
                            currentTask.copy(
                                description = currentTask.description + "\n\n[Previous output]\n${lastResult.output}"
                            )
                        } else {
                            currentTask
                        }
                        step.executor(taskWithPrevOutput).also {
                            lastResult = it
                            // 更新 currentTask 为携带本步骤输出的版本
                            currentTask = taskWithPrevOutput
                        }
                    }

                    is ChainStep.Parallel -> {
                        coroutineScope {
                            val results = step.executors.map { executor ->
                                async { executor(currentTask) }
                            }.awaitAll()
                            step.merger(results).also { lastResult = it }
                        }
                    }

                    is ChainStep.Branch -> {
                        val prevResult = lastResult
                            ?: return BurstSkillResult(success = false, errorMessage = "Branch step requires a previous result")
                        val branchExecutor = if (step.condition(prevResult)) step.ifTrue else step.ifFalse
                        branchExecutor(currentTask).also { lastResult = it }
                    }

                    is ChainStep.ErrorHandler -> {
                        // ErrorHandler 只在异常时触发，正常流程跳过
                        lastResult
                    }
                }

                // 如果步骤失败，中止链
                if (result != null && !result.success) {
                    return result
                }

            } catch (e: Exception) {
                // 查找链中的 ErrorHandler
                val errorHandler = steps.filterIsInstance<ChainStep.ErrorHandler>().firstOrNull()
                if (errorHandler != null) {
                    val errorResult = lastResult ?: BurstSkillResult(
                        success = false,
                        errorMessage = e.message
                    )
                    return errorHandler.handler(currentTask, errorResult, e)
                }
                throw e
            }
        }

        return lastResult ?: BurstSkillResult(
            success = true,
            output = "Skill chain completed with no steps"
        )
    }

    /**
     * 获取链中的步骤数。
     */
    fun stepCount(): Int = steps.size

    /**
     * 获取所有步骤名称。
     */
    fun stepNames(): List<String> = steps.map { step ->
        when (step) {
            is ChainStep.Sequential -> step.name
            is ChainStep.Parallel -> "parallel(${step.executors.size})"
            is ChainStep.Branch -> "branch"
            is ChainStep.ErrorHandler -> "onError"
        }
    }

    companion object {
        /**
         * 创建空的技能链。
         */
        fun create(): SkillChain = SkillChain(emptyList())
    }

    /**
     * 链步骤密封类。
     */
    private sealed class ChainStep {
        data class Sequential(
            val name: String,
            val executor: suspend (BurstTask) -> BurstSkillResult
        ) : ChainStep()

        data class Parallel(
            val executors: List<suspend (BurstTask) -> BurstSkillResult>,
            val merger: (List<BurstSkillResult>) -> BurstSkillResult
        ) : ChainStep()

        data class Branch(
            val condition: (BurstSkillResult) -> Boolean,
            val ifTrue: suspend (BurstTask) -> BurstSkillResult,
            val ifFalse: suspend (BurstTask) -> BurstSkillResult
        ) : ChainStep()

        data class ErrorHandler(
            val handler: suspend (BurstTask, BurstSkillResult, Exception) -> BurstSkillResult
        ) : ChainStep()
    }
}

/**
 * 技能链构建器 DSL。
 *
 * ```
 * val chain = skillChain {
 *     then("analyze") { task -> analyzeSkill.execute(task) }
 *     then("plan") { task -> planSkill.execute(task) }
 *     onError { task, result, e -> recoveryResult }
 * }
 * ```
 */
fun skillChain(init: SkillChainBuilder.() -> Unit): SkillChain {
    return SkillChainBuilder().apply(init).build()
}

/**
 * 技能链构建器。
 */
class SkillChainBuilder {
    private val steps = mutableListOf<Pair<String, suspend (BurstTask) -> BurstSkillResult>>()

    fun then(name: String, executor: suspend (BurstTask) -> BurstSkillResult) {
        steps.add(name to executor)
    }

    internal fun build(): SkillChain {
        var chain = SkillChain.create()
        for ((name, executor) in steps) {
            chain = chain.then(name, executor)
        }
        return chain
    }
}
