package com.apex.agent.kernel.burst.enhanced.ensemble

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * B12: 多模型混合推理（Multi-Model Ensemble）
 *
 * 模型路由 + 集成投票：
 * - 简单任务用小模型（快/便宜）
 * - 复杂任务用大模型（慢/贵）
 * - 关键决策用多模型投票
 */
class ModelEnsemble {

    enum class LlmProvider {
        DEEPSEEK_FLASH,    // 快速
        DEEPSEEK_PRO,      // 专业
        CLAUDE_3_5_SONNET, // 强推理
        GPT_4O,            // 通用
        GEMINI_PRO         // 多模态
    }

    enum class TaskComplexity { LOW, MEDIUM, HIGH, CRITICAL }

    data class ModelRoute(
        val provider: LlmProvider,
        val complexity: TaskComplexity,
        val maxTokens: Int,
        val temperature: Float,
        val timeoutMs: Long
    )

    data class EnsembleResult(
        val output: String,
        val providers: List<LlmProvider>,
        val confidence: Float,
        val agreement: Float,         // 一致性（0-1）
        val totalTokens: Int,
        val totalDurationMs: Long
    )

    data class ProviderResponse(
        val provider: LlmProvider,
        val output: String,
        val success: Boolean,
        val durationMs: Long,
        val tokensUsed: Int,
        val error: String? = null
    )

    fun interface LlmExecutor {
        suspend fun execute(provider: LlmProvider, prompt: String, maxTokens: Int, temperature: Float): ProviderResponse
    }

    private val routingTable = mapOf(
        TaskComplexity.LOW to ModelRoute(LlmProvider.DEEPSEEK_FLASH, TaskComplexity.LOW, 1000, 0.3f, 10_000L),
        TaskComplexity.MEDIUM to ModelRoute(LlmProvider.DEEPSEEK_PRO, TaskComplexity.MEDIUM, 2000, 0.5f, 20_000L),
        TaskComplexity.HIGH to ModelRoute(LlmProvider.CLAUDE_3_5_SONNET, TaskComplexity.HIGH, 4000, 0.7f, 40_000L),
        TaskComplexity.CRITICAL to ModelRoute(LlmProvider.CLAUDE_3_5_SONNET, TaskComplexity.CRITICAL, 6000, 0.5f, 60_000L)
    )

    private val providerStats = ConcurrentHashMap<LlmProvider, ProviderStats>()

    data class ProviderStats(
        var totalCalls: Int = 0,
        var successCount: Int = 0,
        var totalDurationMs: Long = 0,
        var totalTokens: Int = 0
    ) {
        val successRate: Float get() = if (totalCalls > 0) successCount.toFloat() / totalCalls else 0f
        val avgDuration: Long get() = if (totalCalls > 0) totalDurationMs / totalCalls else 0
    }

    /**
     * 路由任务到合适的模型
     */
    fun route(complexity: TaskComplexity): ModelRoute {
        return routingTable[complexity] ?: routingTable[TaskComplexity.MEDIUM]!!
    }

    /**
     * 评估任务复杂度
     */
    fun assessComplexity(prompt: String, taskType: String = ""): TaskComplexity {
        val length = prompt.length
        val hasCode = prompt.contains("```") || prompt.contains("function") || prompt.contains("class ")
        val hasReasoning = prompt.contains("为什么", true) || prompt.contains("why", true) || prompt.contains("分析", true)
        val hasMultiStep = prompt.contains("步骤", true) || prompt.contains("step", true)

        return when {
            length > 2000 || (hasReasoning && hasMultiStep) -> TaskComplexity.CRITICAL
            length > 500 || hasReasoning || hasCode -> TaskComplexity.HIGH
            length > 100 -> TaskComplexity.MEDIUM
            else -> TaskComplexity.LOW
        }
    }

    /**
     * 单模型执行
     */
    suspend fun execute(
        prompt: String,
        complexity: TaskComplexity,
        executor: LlmExecutor
    ): ProviderResponse {
        val route = route(complexity)
        val response = executor.execute(route.provider, prompt, route.maxTokens, route.temperature)
        recordStats(response)
        return response
    }

    /**
     * 多模型投票
     */
    suspend fun vote(
        prompt: String,
        providers: List<LlmProvider>,
        executor: LlmExecutor,
        maxTokens: Int = 2000,
        temperature: Float = 0.3f
    ): EnsembleResult {
        val start = System.currentTimeMillis()

        val responses = coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        executor.execute(provider, prompt, maxTokens, temperature)
                    } catch (e: Exception) {
                        ProviderResponse(provider, "", false, 0, 0, e.message)
                    }
                }
            }.awaitAll()
        }

        responses.forEach { recordStats(it) }

        val successful = responses.filter { it.success }
        if (successful.isEmpty()) {
            return EnsembleResult("", providers, 0f, 0f, 0, System.currentTimeMillis() - start)
        }

        // 一致性分析（简化：基于输出相似度）
        val agreement = computeAgreement(successful.map { it.output })

        // 选择最佳输出（最长且来自最快模型）
        val best = successful.maxByOrNull { it.output.length }
        val confidence = if (agreement > 0.7f) 0.9f else 0.6f

        return EnsembleResult(
            output = best?.output ?: "",
            providers = successful.map { it.provider },
            confidence = confidence,
            agreement = agreement,
            totalTokens = responses.sumOf { it.tokensUsed },
            totalDurationMs = System.currentTimeMillis() - start
        )
    }

    fun getProviderStats(): Map<LlmProvider, ProviderStats> = providerStats.toMap()

    private fun recordStats(response: ProviderResponse) {
        val stats = providerStats.computeIfAbsent(response.provider) { ProviderStats() }
        stats.totalCalls++
        if (response.success) stats.successCount++
        stats.totalDurationMs += response.durationMs
        stats.totalTokens += response.tokensUsed
    }

    private fun computeAgreement(outputs: List<String>): Float {
        if (outputs.size < 2) return 1.0f
        // 简化：计算平均 Jaccard 相似度
        var totalSim = 0f
        var pairs = 0
        for (i in outputs.indices) {
            for (j in i + 1 until outputs.size) {
                val setA = outputs[i].lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                val setB = outputs[j].lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                val intersection = setA.intersect(setB).size
                val union = setA.union(setB).size
                totalSim += if (union > 0) intersection.toFloat() / union else 0f
                pairs++
            }
        }
        return if (pairs > 0) totalSim / pairs else 1f
    }
}
