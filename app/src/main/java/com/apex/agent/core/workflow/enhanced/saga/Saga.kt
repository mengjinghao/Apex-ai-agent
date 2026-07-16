package com.apex.agent.core.workflow.enhanced.saga

import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Saga 步骤定义
 *
 * 参照 Temporal Saga Pattern
 * 每个 Saga 步骤包含正向执行 + 反向补偿
 */
data class SagaStep<T>(
    val nodeId: String,
    val name: String,
    val execute: suspend () -> T,
    val compensate: (suspend (result: T) -> Unit)? = null
)

/**
 * Saga 执行结果
 */
sealed class SagaResult<T> {
    /** 所有步骤成功 */

    /** 某步失败，已执行补偿 */
    data class Compensated<T>(
        val completedOutputs: Map<String, T>,
        val failure: Throwable,
        val compensationResults: List<CompensationResult>
    ) : SagaResult<T>()
}

data class CompensationResult(
    val nodeId: String,
    val success: Boolean,
    val error: Throwable? = null
)

/**
 * Saga 构建器
 */

    fun step(
        nodeId: String,
        name: String = nodeId,
        execute: suspend () -> T,
        compensate: (suspend (result: T) -> Unit)? = null
    ) = apply {
        steps.add(SagaStep(nodeId, name, execute, compensate))
    }

    fun build(): Saga<T> = Saga(steps.toList())
}

/**
 * Saga 执行器 - 实现正向执行 + 失败时 LIFO 补偿
 *
 * 关键特性：
 * - 正向执行：按顺序执行所有步骤
 * - 失败补偿：某步失败时，对已完成的步骤按 LIFO（逆序）执行补偿
 * - 补偿幂等：补偿动作应设计为幂等（可能被重复执行）
 * - 补偿错误收集：补偿失败不中断其他补偿，但会记录
 */
class Saga<T>(private val steps: List<SagaStep<T>>) {

    suspend fun run(): SagaResult<T> = coroutineScope {
        val completed = ArrayDeque<Pair<SagaStep<T>, T>>()
        val outputs = mutableMapOf<String, T>()

        try {
            for (step in steps) {
                val result = step.execute()
                outputs[step.nodeId] = result
                completed.addFirst(step to result)  // LIFO 回滚
            }
            SagaResult.Success(outputs)
        } catch (e: Throwable) {
            // 反向执行补偿
            val compensationResults = mutableListOf<CompensationResult>()
            for ((step, result) in completed) {
                val comp = step.compensate
                if (comp != null) {
                    try {
                        comp(result)
                        compensationResults.add(CompensationResult(step.nodeId, true))
                    } catch (ce: Throwable) {
                        compensationResults.add(CompensationResult(step.nodeId, false, ce))
                    }
                }
            }
            SagaResult.Compensated(outputs, e, compensationResults)
        }
    }
}

/**
 * Saga 事件 - 用于追踪执行与补偿进度
 */
sealed class SagaEvent {
    data class StepStarted(val nodeId: String, val name: String) : SagaEvent()
    data class StepCompleted(val nodeId: String, val result: Any?) : SagaEvent()
    data class StepFailed(val nodeId: String, val error: Throwable) : SagaEvent()
    data class CompensationStarted(val nodeId: String) : SagaEvent()
    data class CompensationCompleted(val nodeId: String) : SagaEvent()
    data class CompensationFailed(val nodeId: String, val error: Throwable) : SagaEvent()
    data class SagaCompleted(val success: Boolean) : SagaEvent()
}

/**
 * 带事件通知的 Saga 执行器
 */
class ObservableSaga<T>(
    private val saga: Saga<T>,
    private val emitter: (SagaEvent) -> Unit
) {
    suspend fun run(): SagaResult<T> {
        // 注意：这是一个简化包装，实际事件需要在 Saga 内部发射
        // 生产实现应重构 Saga 类支持事件回调
        val result = saga.run()
        emitter(SagaEvent.SagaCompleted(result is SagaResult.Success))
        return result
    }
}
