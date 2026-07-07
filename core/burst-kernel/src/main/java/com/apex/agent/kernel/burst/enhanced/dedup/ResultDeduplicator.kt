package com.apex.agent.kernel.burst.enhanced.dedup

import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * B55: 结果去重器
 *
 * 对并行/竞速执行的结果去重：
 * - 精确去重
 * - 语义去重（相似度）
 * - 部分去重（保留差异部分）
 */
class ResultDeduplicator(
    private val similarityThreshold: Float = 0.85f
) {

    data class DedupResult(
        val uniqueResults: List<String>,
        val duplicateCount: Int,
        val totalChecked: Int,
        val duplicates: List<DuplicatePair>,
        val strategy: DedupStrategy
    )

    data class DuplicatePair(
        val resultA: String,
        val resultB: String,
        val similarity: Float
    )

    enum class DedupStrategy {
        EXACT,          // 精确匹配
        NORMALIZED,     // 归一化后匹配
        SEMANTIC,       // 语义相似
        HASH_BASED      // 哈希匹配
    }

    private val seenHashes = ConcurrentHashMap<String, String>()  // hash -> result
    private val dedupHistory = mutableListOf<DedupResult>()

    /**
     * 去重
     */
    fun deduplicate(results: List<String>, strategy: DedupStrategy = DedupStrategy.SEMANTIC): DedupResult {
        val unique = mutableListOf<String>()
        val duplicates = mutableListOf<DuplicatePair>()

        for (result in results) {
            val isDup = when (strategy) {
                DedupStrategy.EXACT -> checkExact(result, unique, duplicates)
                DedupStrategy.NORMALIZED -> checkNormalized(result, unique, duplicates)
                DedupStrategy.SEMANTIC -> checkSemantic(result, unique, duplicates)
                DedupStrategy.HASH_BASED -> checkHash(result, unique, duplicates)
            }
            if (!isDup) unique.add(result)
        }

        val result = DedupResult(
            uniqueResults = unique,
            duplicateCount = results.size - unique.size,
            totalChecked = results.size,
            duplicates = duplicates,
            strategy = strategy
        )
        dedupHistory.add(result)
        while (dedupHistory.size > 200) dedupHistory.removeAt(0)
        return result
    }

    /**
     * 检查是否已存在（精确）
     */
    private fun checkExact(result: String, unique: List<String>, duplicates: MutableList<DuplicatePair>): Boolean {
        for (existing in unique) {
            if (result == existing) {
                duplicates.add(DuplicatePair(existing, result, 1f))
                return true
            }
        }
        return false
    }

    /**
     * 检查是否已存在（归一化）
     */
    private fun checkNormalized(result: String, unique: List<String>, duplicates: MutableList<DuplicatePair>): Boolean {
        val normalized = normalize(result)
        for (existing in unique) {
            val existingNorm = normalize(existing)
            if (normalized == existingNorm) {
                duplicates.add(DuplicatePair(existing, result, 1f))
                return true
            }
        }
        return false
    }

    /**
     * 检查是否已存在（语义）
     */
    private fun checkSemantic(result: String, unique: List<String>, duplicates: MutableList<DuplicatePair>): Boolean {
        for (existing in unique) {
            val sim = computeSimilarity(result, existing)
            if (sim >= similarityThreshold) {
                duplicates.add(DuplicatePair(existing, result, sim))
                return true
            }
        }
        return false
    }

    /**
     * 检查是否已存在（哈希）
     */
    private fun checkHash(result: String, unique: List<String>, duplicates: MutableList<DuplicatePair>): Boolean {
        val hash = computeHash(result)
        for (existing in unique) {
            val existingHash = computeHash(existing)
            if (hash == existingHash) {
                duplicates.add(DuplicatePair(existing, result, 1f))
                return true
            }
        }
        seenHashes[hash] = result
        return false
    }

    /**
     * 增量去重（检查单个结果是否重复）
     */
    fun isDuplicate(result: String, strategy: DedupStrategy = DedupStrategy.SEMANTIC): Boolean {
        return when (strategy) {
            DedupStrategy.EXACT -> seenHashes.values.any { it == result }
            DedupStrategy.NORMALIZED -> seenHashes.values.any { normalize(it) == normalize(result) }
            DedupStrategy.SEMANTIC -> seenHashes.values.any { computeSimilarity(it, result) >= similarityThreshold }
            DedupStrategy.HASH_BASED -> seenHashes.containsKey(computeHash(result))
        }
    }

    /**
     * 添加到已见集合
     */
    fun addToSeen(result: String) {
        seenHashes[computeHash(result)] = result
    }

    fun clearSeen() { seenHashes.clear() }
    fun getHistory(): List<DedupResult> = dedupHistory.toList()

    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun computeHash(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun computeSimilarity(a: String, b: String): Float {
        val setA = a.lowercase().split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】]+"))
            .filter { it.length >= 2 }.toSet()
        val setB = b.lowercase().split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】]+"))
            .filter { it.length >= 2 }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return intersection.toFloat() / union
    }
}
