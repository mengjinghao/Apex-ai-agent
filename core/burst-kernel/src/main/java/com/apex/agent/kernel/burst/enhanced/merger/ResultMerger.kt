package com.apex.agent.kernel.burst.enhanced.merger

import java.util.concurrent.ConcurrentHashMap

/**
 * B48: 智能结果合并器
 *
 * 从多个并行/竞速执行中智能合并结果：
 * - 投票合并（多数决）
 * - 最佳质量合并（评分最高）
 * - 去重合并（合并不同部分）
 * - 置信度加权合并
 */
class ResultMerger {

    enum class MergeStrategy {
        MAJORITY_VOTE,      // 多数投票
        BEST_QUALITY,       // 质量最高
        UNION_DEDUP,        // 合并去重
        WEIGHTED_AVERAGE,   // 加权平均
        FIRST_SUCCESS,      // 首个成功
        LONGEST_RESULT,     // 最长结果
        CONSENSUS           // 共识合并
    }

    data class MergeResult(
        val mergedOutput: String,
        val strategy: MergeStrategy,
        val sourceCount: Int,
        val confidence: Float,
        val agreement: Float,
        val sources: List<MergeSource>
    )

    data class MergeSource(
        val sourceId: String,
        val output: String,
        val success: Boolean,
        val score: Float,
        val durationMs: Long
    )

    private val mergeHistory = mutableListOf<MergeResult>()
    private val strategyStats = ConcurrentHashMap<MergeStrategy, Int>()

    /**
     * 合并结果
     */
    fun merge(sources: List<MergeSource>, strategy: MergeStrategy = MergeStrategy.BEST_QUALITY): MergeResult {
        val successful = sources.filter { it.success }
        if (successful.isEmpty()) {
            return MergeResult("", strategy, sources.size, 0f, 0f, sources)
        }

        val result = when (strategy) {
            MergeStrategy.MAJORITY_VOTE -> majorityVote(successful)
            MergeStrategy.BEST_QUALITY -> bestQuality(successful)
            MergeStrategy.UNION_DEDUP -> unionDedup(successful)
            MergeStrategy.WEIGHTED_AVERAGE -> weightedAverage(successful)
            MergeStrategy.FIRST_SUCCESS -> firstSuccess(successful)
            MergeStrategy.LONGEST_RESULT -> longestResult(successful)
            MergeStrategy.CONSENSUS -> consensus(successful)
        }

        mergeHistory.add(result)
        strategyStats[strategy] = (strategyStats[strategy] ?: 0) + 1
        while (mergeHistory.size > 200) mergeHistory.removeAt(0)

        return result
    }

    private fun majorityVote(sources: List<MergeSource>): MergeResult {
        // 按输出内容分组，取出现最多的
        val grouped = sources.groupBy { normalize(it.output) }
        val largest = grouped.maxByOrNull { it.value.size }!!
        val agreement = largest.value.size.toFloat() / sources.size
        return MergeResult(
            mergedOutput = largest.value.first().output,
            strategy = MergeStrategy.MAJORITY_VOTE,
            sourceCount = sources.size,
            confidence = agreement,
            agreement = agreement,
            sources = sources
        )
    }

    private fun bestQuality(sources: List<MergeSource>): MergeResult {
        val best = sources.maxByOrNull { it.score } ?: sources.first()
        return MergeResult(
            mergedOutput = best.output,
            strategy = MergeStrategy.BEST_QUALITY,
            sourceCount = sources.size,
            confidence = best.score,
            agreement = computeAgreement(sources),
            sources = sources
        )
    }

    private fun unionDedup(sources: List<MergeSource>): MergeResult {
        val allLines = sources.flatMap { it.output.lines() }.distinct()
        return MergeResult(
            mergedOutput = allLines.joinToString("\n"),
            strategy = MergeStrategy.UNION_DEDUP,
            sourceCount = sources.size,
            confidence = 0.8f,
            agreement = computeAgreement(sources),
            sources = sources
        )
    }

    private fun weightedAverage(sources: List<MergeSource>): MergeResult {
        val totalWeight = sources.sumOf { it.score.toDouble() }.toFloat()
        val best = sources.maxByOrNull { it.score } ?: sources.first()
        return MergeResult(
            mergedOutput = best.output,
            strategy = MergeStrategy.WEIGHTED_AVERAGE,
            sourceCount = sources.size,
            confidence = totalWeight / sources.size,
            agreement = computeAgreement(sources),
            sources = sources
        )
    }

    private fun firstSuccess(sources: List<MergeSource>): MergeResult {
        val first = sources.first()
        return MergeResult(
            mergedOutput = first.output,
            strategy = MergeStrategy.FIRST_SUCCESS,
            sourceCount = sources.size,
            confidence = 0.7f,
            agreement = computeAgreement(sources),
            sources = sources
        )
    }

    private fun longestResult(sources: List<MergeSource>): MergeResult {
        val longest = sources.maxByOrNull { it.output.length } ?: sources.first()
        return MergeResult(
            mergedOutput = longest.output,
            strategy = MergeStrategy.LONGEST_RESULT,
            sourceCount = sources.size,
            confidence = 0.6f,
            agreement = computeAgreement(sources),
            sources = sources
        )
    }

    private fun consensus(sources: List<MergeSource>): MergeResult {
        // 共识：取所有源都包含的部分
        val allWords = sources.map { it.output.lowercase().split(Regex("\\s+")).toSet() }
        val commonWords = allWords.reduce { acc, set -> acc.intersect(set) }
        val best = sources.maxByOrNull { it.score } ?: sources.first()
        return MergeResult(
            mergedOutput = best.output,
            strategy = MergeStrategy.CONSENSUS,
            sourceCount = sources.size,
            confidence = if (commonWords.size > 10) 0.9f else 0.6f,
            agreement = commonWords.size.toFloat() / (allWords.firstOrNull()?.size ?: 1).coerceAtLeast(1),
            sources = sources
        )
    }

    private fun computeAgreement(sources: List<MergeSource>): Float {
        if (sources.size < 2) return 1f
        var totalSim = 0f
        var pairs = 0
        for (i in sources.indices) {
            for (j in i + 1 until sources.size) {
                totalSim += similarity(sources[i].output, sources[j].output)
                pairs++
            }
        }
        return if (pairs > 0) totalSim / pairs else 1f
    }

    private fun similarity(a: String, b: String): Float {
        val setA = a.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val setB = b.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        return setA.intersect(setB).size.toFloat() / setA.union(setB).size
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    fun getHistory(): List<MergeResult> = mergeHistory.toList()
    fun getStats(): Map<MergeStrategy, Int> = strategyStats.toMap()
}
