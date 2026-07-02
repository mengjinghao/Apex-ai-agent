package com.apex.agent.core.workflow.enhanced.loop

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 循环规范 - 支持 4 种循环模式
 *
 * 参照 Dify Iteration 节点、Coze Loop 节点、PocketFlow BatchNode
 */
sealed class LoopSpec {
    /** 固定次数循环 */
    data class Count(val times: Int, val parallel: Boolean = false) : LoopSpec()

    /** 遍历列表（for-each） */
    data class ForEach(val items: List<Any>, val maxConcurrency: Int = 4) : LoopSpec()

    /** while 条件循环 */
    data class While(val condition: (iteration: Int, lastResult: Any?) -> Boolean, val maxIterations: Int = 1000) : LoopSpec()

    /** map-reduce：map 阶段并行，reduce 阶段串行合并 */
    data class MapReduce(
        val items: List<Any>,
        val maxConcurrency: Int = 4,
        val reducer: (accumulator: Any?, current: Any?) -> Any?
    ) : LoopSpec()
}

/**
 * 循环上下文 - 每次迭代可访问
 */
data class LoopContext(
    val iteration: Int,
    val item: Any? = null,
    val isLast: Boolean = false,
    val accumulator: Any? = null,
    val previousResult: Any? = null
)

/**
 * 循环结果
 */
data class LoopResult(
    val outputs: List<Any?>,
    val iterations: Int,
    val brokeEarly: Boolean = false,
    val finalAccumulator: Any? = null
)

/**
 * 循环执行器
 */
class LoopExecutor {

    /**
     * 执行循环
     * @param spec 循环规范
     * @param body 循环体，接收 LoopContext 返回结果
     * @param shouldBreak 提前退出判断
     * @param shouldContinue 跳过本次判断
     */
    suspend fun execute(
        spec: LoopSpec,
        body: suspend (LoopContext) -> Any?,
        shouldBreak: (LoopContext, Any?) -> Boolean = { _, _ -> false },
        shouldContinue: (LoopContext) -> Boolean = { _ -> false }
    ): LoopResult = coroutineScope {
        when (spec) {
            is LoopSpec.Count -> executeCount(spec, body, shouldBreak, shouldContinue)
            is LoopSpec.ForEach -> executeForEach(spec, body, shouldBreak, shouldContinue)
            is LoopSpec.While -> executeWhile(spec, body, shouldBreak, shouldContinue)
            is LoopSpec.MapReduce -> executeMapReduce(spec, body)
        }
    }

    private suspend fun executeCount(
        spec: LoopSpec.Count,
        body: suspend (LoopContext) -> Any?,
        shouldBreak: (LoopContext, Any?) -> Boolean,
        shouldContinue: (LoopContext) -> Boolean
    ): LoopResult {
        val outputs = mutableListOf<Any?>()
        var lastResult: Any? = null
        var brokeEarly = false

        if (spec.parallel) {
            // 并行执行
            val semaphore = Semaphore(spec.times.coerceAtMost(8))
            val deferred = (0 until spec.times).map { i ->
                async {
                    semaphore.withPermit {
                        val ctx = LoopContext(
                            iteration = i,
                            isLast = i == spec.times - 1,
                            previousResult = lastResult
                        )
                        if (shouldContinue(ctx)) null else body(ctx)
                    }
                }
            }
            outputs.addAll(deferred.awaitAll())
        } else {
            for (i in 0 until spec.times) {
                val ctx = LoopContext(
                    iteration = i,
                    isLast = i == spec.times - 1,
                    previousResult = lastResult
                )
                if (shouldContinue(ctx)) {
                    outputs.add(null)
                    continue
                }
                val result = body(ctx)
                outputs.add(result)
                lastResult = result
                if (shouldBreak(ctx, result)) {
                    brokeEarly = true
                    break
                }
            }
        }
        return LoopResult(outputs, outputs.size, brokeEarly, lastResult)
    }

    private suspend fun executeForEach(
        spec: LoopSpec.ForEach,
        body: suspend (LoopContext) -> Any?,
        shouldBreak: (LoopContext, Any?) -> Boolean,
        shouldContinue: (LoopContext) -> Boolean
    ): LoopResult = coroutineScope {
        val outputs = ConcurrentHashMap<Int, Any?>()
        var brokeEarly = false
        val semaphore = Semaphore(spec.maxConcurrency.coerceAtLeast(1))

        val deferred = spec.items.mapIndexed { i, item ->
            async {
                semaphore.withPermit {
                    val ctx = LoopContext(
                        iteration = i,
                        item = item,
                        isLast = i == spec.items.size - 1
                    )
                    if (shouldContinue(ctx)) null else body(ctx)
                }
            }
        }

        val results = deferred.awaitAll()
        results.forEachIndexed { i, r -> outputs[i] = r }

        LoopResult(outputs.toSortedMap().values.toList(), spec.items.size, brokeEarly, results.lastOrNull())
    }

    private suspend fun executeWhile(
        spec: LoopSpec.While,
        body: suspend (LoopContext) -> Any?,
        shouldBreak: (LoopContext, Any?) -> Boolean,
        shouldContinue: (LoopContext) -> Boolean
    ): LoopResult {
        val outputs = mutableListOf<Any?>()
        var iteration = 0
        var lastResult: Any? = null
        var brokeEarly = false

        while (iteration < spec.maxIterations && spec.condition(iteration, lastResult)) {
            val ctx = LoopContext(
                iteration = iteration,
                isLast = false,
                previousResult = lastResult
            )
            if (shouldContinue(ctx)) {
                outputs.add(null)
                iteration++
                continue
            }
            val result = body(ctx)
            outputs.add(result)
            lastResult = result
            if (shouldBreak(ctx, result)) {
                brokeEarly = true
                break
            }
            iteration++
        }
        return LoopResult(outputs, iteration, brokeEarly, lastResult)
    }

    private suspend fun executeMapReduce(
        spec: LoopSpec.MapReduce,
        body: suspend (LoopContext) -> Any?
    ): LoopResult = coroutineScope {
        val semaphore = Semaphore(spec.maxConcurrency.coerceAtLeast(1))

        // Map 阶段：并行
        val mapped = spec.items.mapIndexed { i, item ->
            async {
                semaphore.withPermit {
                    val ctx = LoopContext(iteration = i, item = item)
                    body(ctx)
                }
            }
        }.awaitAll()

        // Reduce 阶段：串行
        var acc: Any? = null
        mapped.forEachIndexed { i, v ->
            acc = spec.reducer(acc, v)
        }

        LoopResult(mapped, spec.items.size, false, acc)
    }
}
