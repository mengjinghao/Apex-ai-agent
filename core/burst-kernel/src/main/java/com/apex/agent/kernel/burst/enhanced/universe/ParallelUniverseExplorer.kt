package com.apex.agent.kernel.burst.enhanced.universe

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * B6: 并行宇宙探索（Parallel Universe / Tree-of-Execution）
 *
 * 对复杂任务并行启动多个"宇宙"：
 * - 每个宇宙用不同 Skill 组合 + 不同 LLM + 不同参数
 * - 首个宇宙产出高质量结果（评估器打分）后合并/终止其他
 *
 * 区别于现有 RacingSkill（同工具竞速），这是策略级并行
 */
class ParallelUniverseExplorer(
    private val defaultUniverseCount: Int = 3,
    private val timeoutMs: Long = 60_000L
) {

    /**
     * 宇宙定义
     */
    data class Universe(
        val id: String,
        val name: String,
        val skillChain: List<String>,      // Skill ID 链
        val llmProvider: String,           // LLM provider
        val configOverrides: Map<String, Any>,
        val budget: ResourceBudget,
        val strategy: UniverseStrategy
    )

    data class ResourceBudget(
        val maxTokens: Int = 4000,
        val maxDurationMs: Long = 30_000L,
        val maxRetries: Int = 3
    )

    enum class UniverseStrategy {
        AGGRESSIVE,      // 激进：高并发，多重试
        BALANCED,        // 平衡
        CONSERVATIVE,    // 保守：低并发，少重试
        CREATIVE,        // 创意：高温大随机
        PRECISE          // 精确：低温低随机
    }

    /**
     * 宇宙执行结果
     */
    data class UniverseResult(
        val universeId: String,
        val success: Boolean,
        val output: String,
        val qualityScore: Float,           // 质量评分 0-1
        val durationMs: Long,
        val tokensUsed: Int,
        val error: String? = null,
        val metadata: Map<String, Any> = emptyMap()
    )

    /**
     * 探索结果
     */
    data class ExplorationResult(
        val taskId: String,
        val bestUniverse: UniverseResult,
        val allResults: List<UniverseResult>,
        val totalDurationMs: Long,
        val totalTokensUsed: Int,
        val explorationStrategy: String
    )

    /**
     * 质量评估器
     */
    fun interface QualityEvaluator {
        suspend fun evaluate(output: String, task: String): Float
    }

    /**
     * 宇宙执行器（业务侧注入）
     */
    fun interface UniverseExecutor {
        suspend fun execute(universe: Universe, task: String): UniverseResult
    }

    // ============ 状态 ============

    private val activeExplorations = ConcurrentHashMap<String, ExplorationContext>()
    private val history = mutableListOf<ExplorationResult>()

    data class ExplorationContext(
        val taskId: String,
        val universes: List<Universe>,
        val startedAt: Long,
        val results: MutableList<UniverseResult>
    )

    // ============ 公共 API ============

    /**
     * 探索多个宇宙
     */
    suspend fun explore(
        task: String,
        universes: List<Universe>? = null,
        executor: UniverseExecutor,
        evaluator: QualityEvaluator? = null
    ): ExplorationResult {
        val actualUniverses = universes ?: generateDefaultUniverses(task)
        val taskId = "explore_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val context = ExplorationContext(taskId, actualUniverses, System.currentTimeMillis(), mutableListOf())
        activeExplorations[taskId] = context

        val start = System.currentTimeMillis()

        // 并行执行所有宇宙
        val results = coroutineScope {
            actualUniverses.map { universe ->
                async {
                    try {
                        val result = executor.execute(universe, task)
                        // 评估质量
                        val score = if (result.success && evaluator != null) {
                            evaluator.evaluate(result.output, task)
                        } else {
                            result.qualityScore
                        }
                        result.copy(qualityScore = score)
                    } catch (e: Exception) {
                        UniverseResult(
                            universeId = universe.id,
                            success = false,
                            output = "",
                            qualityScore = 0f,
                            durationMs = 0,
                            tokensUsed = 0,
                            error = e.message
                        )
                    }
                }
            }.awaitAll()
        }

        context.results.addAll(results)
        activeExplorations.remove(taskId)

        // 选择最佳宇宙
        val best = results
            .filter { it.success }
            .maxByOrNull { it.qualityScore }
            ?: results.first()

        val totalDuration = System.currentTimeMillis() - start
        val totalTokens = results.sumOf { it.tokensUsed }

        val explorationResult = ExplorationResult(
            taskId = taskId,
            bestUniverse = best,
            allResults = results,
            totalDurationMs = totalDuration,
            totalTokensUsed = totalTokens,
            explorationStrategy = "parallel_${actualUniverses.size}_universes"
        )

        history.add(explorationResult)
        while (history.size > 50) history.removeAt(0)

        return explorationResult
    }

    /**
     * 探索并取首个成功结果（竞速模式）
     */
    suspend fun exploreRacing(
        task: String,
        universes: List<Universe>? = null,
        executor: UniverseExecutor
    ): UniverseResult? {
        val actualUniverses = universes ?: generateDefaultUniverses(task)

        // 第一个成功的宇宙胜出
        return coroutineScope {
            actualUniverses.map { universe ->
                async {
                    try {
                        executor.execute(universe, task)
                    } catch (e: Exception) {
                        UniverseResult(
                            universeId = universe.id, success = false, output = "",
                            qualityScore = 0f, durationMs = 0, tokensUsed = 0, error = e.message
                        )
                    }
                }
            }.awaitAll()
        }.firstOrNull { it.success }
    }

    /**
     * 生成默认宇宙集
     */
    fun generateDefaultUniverses(task: String): List<Universe> {
        return listOf(
            Universe(
                id = "universe_aggressive",
                name = "激进宇宙",
                skillChain = listOf("reasoning.tree-of-thoughts", "extreme_reasoning"),
                llmProvider = "deepseek-pro",
                configOverrides = mapOf("temperature" to 0.8, "maxConcurrency" to 8),
                budget = ResourceBudget(maxTokens = 6000, maxDurationMs = 45_000, maxRetries = 5),
                strategy = UniverseStrategy.AGGRESSIVE
            ),
            Universe(
                id = "universe_balanced",
                name = "平衡宇宙",
                skillChain = listOf("reasoning.react", "reasoning.self-consistency"),
                llmProvider = "deepseek-pro",
                configOverrides = mapOf("temperature" to 0.5, "maxConcurrency" to 4),
                budget = ResourceBudget(maxTokens = 4000, maxDurationMs = 30_000, maxRetries = 3),
                strategy = UniverseStrategy.BALANCED
            ),
            Universe(
                id = "universe_precise",
                name = "精确宇宙",
                skillChain = listOf("reasoning.chain-of-thought", "self_correction"),
                llmProvider = "deepseek-flash",
                configOverrides = mapOf("temperature" to 0.2, "maxConcurrency" to 2),
                budget = ResourceBudget(maxTokens = 3000, maxDurationMs = 20_000, maxRetries = 2),
                strategy = UniverseStrategy.PRECISE
            )
        )
    }

    /**
     * 获取活跃探索
     */
    fun getActiveExplorations(): List<ExplorationContext> = activeExplorations.values.toList()

    /**
     * 获取历史
     */
    fun getHistory(): List<ExplorationResult> = history.toList()

    /**
     * 获取统计
     */
    fun getStats(): UniverseStats {
        return UniverseStats(
            totalExplorations = history.size,
            avgUniverseCount = if (history.isNotEmpty()) history.map { it.allResults.size }.average().toFloat() else 0f,
            avgDurationMs = if (history.isNotEmpty()) history.map { it.totalDurationMs }.average().toLong() else 0L,
            avgTokensUsed = if (history.isNotEmpty()) history.map { it.totalTokensUsed }.average().toInt() else 0,
            bestStrategy = history.groupingBy { it.explorationStrategy }.eachCount().maxByOrNull { it.value }?.key
        )
    }

    data class UniverseStats(
        val totalExplorations: Int,
        val avgUniverseCount: Float,
        val avgDurationMs: Long,
        val avgTokensUsed: Int,
        val bestStrategy: String?
    )
}
