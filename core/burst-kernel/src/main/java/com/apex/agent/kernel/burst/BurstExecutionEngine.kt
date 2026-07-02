package com.apex.agent.kernel.burst

import android.content.Context
import com.apex.agent.data.burstmode.swarm.SwarmBurstOrchestrator
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.BurstSkillContext
import com.apex.agent.plugins.burst.base.BurstSkillResult
import com.apex.agent.plugins.burst.base.IBurstSkill
import com.apex.agent.plugins.burst.base.SkillMetrics
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

enum class ExecutionMode {
    STANDARD,
    SWARM,
    TASK_GRAPH
}

/**
 * Burst 执行引擎
 *
 * 支持三种执行模式：
 * - STANDARD：多路径并发执行同一个 skill，取最快的成功结果（修复旧版"取最慢的"逻辑）
 * - SWARM：将任务按段落切分，分发到 swarm 中多个 agent 并行处理
 * - TASK_GRAPH：直接执行（保留扩展点）
 *
 * 修复点（相对旧版）：
 * 1) mergeResults：旧版 maxByOrNull{... ?: Long.MAX_VALUE} 会优先选中无 metrics 的结果，且语义是"最慢的"。
 *    新版改为 minByOrNull{... ?: Long.MAX_VALUE}，选最快的成功结果；全部失败时返回第一个失败。
 * 2) executeSwarm：旧版 allSuccess = results.values.all { it.isNotBlank() } 只判断输出非空，
 *    丢失了 BurstSkillResult.success 信息。新版保留完整 BurstSkillResult，结合 success 字段判断。
 * 3) executeSwarm：旧版 metrics.executionTimeMs = 0。新版记录真实耗时。
 * 4) cleanup() 暴露给 BurstKernel.stop() 调用，避免 scope 泄漏。
 */
class BurstExecutionEngine(
    private val appContext: Context,
    private val collabFramework: Any? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun execute(
        task: BurstTask,
        skill: IBurstSkill,
        skillContext: BurstSkillContext,
        mode: ExecutionMode = ExecutionMode.STANDARD
    ): BurstSkillResult = withContext(Dispatchers.Default) {
        when (mode) {
            ExecutionMode.SWARM -> executeSwarm(task, skill, skillContext)
            ExecutionMode.TASK_GRAPH -> executeTaskGraph(task, skill, skillContext)
            ExecutionMode.STANDARD -> executeMultiPath(task, skill, skillContext)
        }
    }

    private suspend fun executeSwarm(
        task: BurstTask,
        skill: IBurstSkill,
        skillContext: BurstSkillContext
    ): BurstSkillResult {
        val framework = collabFramework
            ?: return executeMultiPath(task, skill, skillContext)

        val swarm = SwarmBurstOrchestrator(appContext, framework)
        val session = swarm.initializeSwarm(task.id)

        val chunks = task.description.split("\n\n").filter { it.isNotBlank() }
            .ifEmpty { listOf(task.description) }

        val startedNs = System.nanoTime()

        // 修复 B2/B3：原版 chunkProcessor 返回 String，丢失 BurstSkillResult.success/error 信息
        // 改为：保留原签名 (String,String)->String 不变（与 SwarmBurstOrchestrator 接口兼容），
        // 但用 side-channel map 缓存每个 chunk 对应的完整 BurstSkillResult，
        // 这样后续合并时可结合 success 字段判断，避免"输出非空就视为成功"的错误。
        val chunkResults = ConcurrentHashMap<String, BurstSkillResult>()

        val chunkProcessor: suspend (String, String) -> String = { agentId, chunk ->
            val subTask = task.copy(
                id = "${task.id}_${agentId}_${chunk.hashCode()}",
                description = chunk
            )
            val result = try {
                skill.execute(subTask)
            } catch (e: Exception) {
                BurstSkillResult(
                    success = false,
                    errorMessage = "agent=$agentId: ${e.message}",
                    output = ""
                )
            }
            chunkResults[chunk] = result
            result.output ?: ""
        }

        @Suppress("UNCHECKED_CAST")
        val rawResults: Map<String, String> = try {
            swarm.processWithSwarm(session.id, chunks, chunkProcessor)
        } catch (e: Exception) {
            return BurstSkillResult(
                success = false,
                errorMessage = "swarm orchestration failed: ${e.message}",
                metrics = SkillMetrics(
                    executionTimeMs = (System.nanoTime() - startedNs) / 1_000_000L
                )
            )
        }

        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L

        // 修复 B2：原版 allSuccess = results.values.all { it.isNotBlank() }
        // 只判断输出非空，丢失 success 信息。新版用 chunkResults（含完整 BurstSkillResult）。
        // 对没有匹配到 chunkResults 的项（理论上不应发生），按 raw 输出非空兜底。
        val orderedResults: List<BurstSkillResult> = chunks.map { chunk ->
            chunkResults[chunk] ?: run {
                val output = rawResults[chunk] ?: rawResults.values.firstOrNull { it.isNotBlank() } ?: ""
                BurstSkillResult(
                    success = output.isNotBlank(),
                    output = output,
                    errorMessage = if (output.isBlank()) "no chunk result captured" else null
                )
            }
        }

        val allSuccess = orderedResults.all { it.success }
        val mergedOutput = orderedResults.joinToString("\n---\n") { it.output ?: "" }
        val errorMessages = orderedResults.mapNotNull { it.errorMessage }.joinToString("; ")
        val totalSteps = orderedResults.sumOf { it.metrics?.stepsCompleted ?: 0 }
        val totalTokens = orderedResults.sumOf { it.metrics?.tokensProcessed ?: 0 }
        val maxMemoryMb = orderedResults.maxOfOrNull { it.metrics?.memoryUsedMb ?: 0 } ?: 0

        // 修复 B4：原版 metrics.executionTimeMs = 0，新版记录真实耗时
        return BurstSkillResult(
            success = allSuccess,
            output = mergedOutput.takeIf { it.isNotBlank() },
            errorMessage = if (allSuccess) null else (errorMessages.ifBlank { "swarm execution failed" }),
            metrics = SkillMetrics(
                executionTimeMs = elapsedMs,
                stepsCompleted = totalSteps,
                tokensProcessed = totalTokens,
                memoryUsedMb = maxMemoryMb
            )
        )
    }

    private suspend fun executeTaskGraph(
        task: BurstTask,
        skill: IBurstSkill,
        skillContext: BurstSkillContext
    ): BurstSkillResult {
        return skill.execute(task)
    }

    companion object {
        private const val MULTI_PATH_COUNT = 3
    }

    /**
     * 多路径并发执行：N 个 path 同时跑同一个 skill，取最快的成功结果。
     * 这对应 RacingSkill 的核心思路 —— 用并发冗余换可靠性。
     */
    suspend fun executeMultiPath(
        task: BurstTask,
        skill: IBurstSkill,
        skillContext: BurstSkillContext
    ): BurstSkillResult = withContext(Dispatchers.Default) {
        val paths = mutableListOf<Deferred<BurstSkillResult>>()

        repeat(MULTI_PATH_COUNT) { pathId ->
            paths.add(scope.async(start = CoroutineStart.DEFAULT) {
                try {
                    skill.execute(task)
                } catch (e: Exception) {
                    BurstSkillResult(
                        success = false,
                        errorMessage = "path-$pathId: ${e.message}"
                    )
                }
            })
        }

        val results = paths.awaitAll()
        mergeResults(results)
    }

    /**
     * 必须在 stop() 时调用，避免协程 scope 泄漏。
     */
    fun cleanup() {
        runCatching { scope.cancel() }
    }

    /**
     * 合并多路径结果：
     * - 若有任一成功：返回执行时间最短的成功结果（最快路径胜出）
     * - 若全部失败：返回第一个失败结果，但合并所有 error 信息便于排查
     *
     * 修复旧版 maxByOrNull{... ?: Long.MAX_VALUE} 的两个问题：
     * a) 旧版选"最慢的"成功结果 —— 与多路径冗余的初衷相反
     * b) 旧版用 Long.MAX_VALUE 兜底无 metrics 的结果，反而优先选中无 metrics 的（最大值）
     */
    private fun mergeResults(results: List<BurstSkillResult>): BurstSkillResult {
        val successful = results.filter { it.success }
        return if (successful.isNotEmpty()) {
            successful.minByOrNull { it.metrics?.executionTimeMs ?: Long.MAX_VALUE }
                ?: successful.first()
        } else {
            // 全失败：聚合 error 信息，但保留第一个作为主体（避免 NPE）
            val first = results.firstOrNull()
                ?: return BurstSkillResult(success = false, errorMessage = "no results")
            val aggregatedErrors = results
                .mapNotNull { it.errorMessage }
                .joinToString("; ")
                .ifBlank { first.errorMessage }
            first.copy(errorMessage = aggregatedErrors)
        }
    }
}
