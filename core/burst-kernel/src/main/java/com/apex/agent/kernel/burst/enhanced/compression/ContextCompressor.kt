package com.apex.agent.kernel.burst.enhanced.compression

import java.util.concurrent.ConcurrentHashMap

/**
 * B13: 上下文压缩策略库（Context Compression Library）
 *
 * 多种压缩策略，根据上下文长度自动选最优：
 * - 摘要压缩
 * - 关键句提取
 * - 实体抽取
 * - 滑动窗口
 * - RAG 检索增强
 */
class ContextCompressor {

    interface CompressionStrategy {
        val name: String
        suspend fun compress(context: String, targetTokens: Int): CompressedContext
        fun estimateCompressionRatio(context: String, targetTokens: Int): Float
    }

    data class CompressedContext(
        val original: String,
        val compressed: String,
        val strategy: String,
        val originalTokens: Int,
        val compressedTokens: Int,
        val compressionRatio: Float,
        val metadata: Map<String, Any> = emptyMap()
    )

    private val strategies = mutableListOf<CompressionStrategy>(
        SummarizationCompression(),
        KeySentenceExtraction(),
        EntityExtractionCompression(),
        SlidingWindowCompression(),
        RAGCompression()
    )

    private val stats = ConcurrentHashMap<String, CompressionStats>()

    data class CompressionStats(
        var totalCompressions: Int = 0,
        var totalOriginalTokens: Long = 0,
        var totalCompressedTokens: Long = 0,
        var avgRatio: Float = 0f
    )

    /**
     * 压缩上下文
     */
    suspend fun compress(context: String, targetTokens: Int = 4000): CompressedContext {
        val originalTokens = estimateTokens(context)
        if (originalTokens <= targetTokens) {
            return CompressedContext(context, context, "none", originalTokens, originalTokens, 1.0f)
        }

        val strategy = selectStrategy(context, targetTokens)
        val result = strategy.compress(context, targetTokens)

        // 记录统计
        val s = stats.computeIfAbsent(strategy.name) { CompressionStats() }
        s.totalCompressions++
        s.totalOriginalTokens += result.originalTokens
        s.totalCompressedTokens += result.compressedTokens
        s.avgRatio = s.totalCompressedTokens.toFloat() / s.totalOriginalTokens.coerceAtLeast(1)

        return result
    }

    /**
     * 选择最优策略
     */
    private fun selectStrategy(context: String, targetTokens: Int): CompressionStrategy {
        val originalTokens = estimateTokens(context)
        val ratio = targetTokens.toFloat() / originalTokens

        return when {
            ratio > 0.7f -> strategies.find { it.name == "SlidingWindow" }!!  // 轻度压缩
            ratio > 0.4f -> strategies.find { it.name == "KeySentence" }!!   // 中度压缩
            ratio > 0.2f -> strategies.find { it.name == "Summarization" }!! // 重度压缩
            else -> strategies.find { it.name == "EntityExtraction" }!!      // 极度压缩
        }
    }

    fun getStats(): Map<String, CompressionStats> = stats.toMap()

    fun registerStrategy(strategy: CompressionStrategy) {
        strategies.add(strategy)
    }

    private fun estimateTokens(text: String): Int {
        val chinese = text.count { it.code in 0x4e00..0x9fff }
        val english = text.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotEmpty() && it.all { c -> c.code !in 0x4e00..0x9fff } }
            .size
        return (chinese * 1.5 + english * 1.3).toInt()
    }

    // ============ 内置策略 ============

    class SummarizationCompression : CompressionStrategy {
        override val name = "Summarization"
        override suspend fun compress(context: String, targetTokens: Int): CompressedContext {
            val originalTokens = (context.length * 0.6).toInt()
            // 简化：取前 30% + 中间 20% + 后 30%
            val parts = context.length / 10
            val compressed = buildString {
                append(context.take(parts * 3))
                append("\n...\n")
                append(context.substring(parts * 4, parts * 6))
                append("\n...\n")
                append(context.takeLast(parts * 3))
            }
            val compressedTokens = (compressed.length * 0.6).toInt()
            return CompressedContext(
                context, compressed, name,
                originalTokens, compressedTokens,
                compressedTokens.toFloat() / originalTokens.coerceAtLeast(1)
            )
        }
        override fun estimateCompressionRatio(context: String, targetTokens: Int) = 0.3f
    }

    class KeySentenceExtraction : CompressionStrategy {
        override val name = "KeySentence"
        override suspend fun compress(context: String, targetTokens: Int): CompressedContext {
            val originalTokens = (context.length * 0.6).toInt()
            val sentences = context.split(Regex("[。.！!？?\\n]+")).filter { it.isNotBlank() }
            // 按长度+关键词打分
            val scored = sentences.map { s ->
                var score = s.length.toFloat()
                if (s.contains("重要") || s.contains("关键") || s.contains("必须")) score *= 2
                if (s.contains("警告") || s.contains("错误")) score *= 1.5
                s to score
            }.sortedByDescending { it.second }
            val targetLen = targetTokens * 5 / 3
            val compressed = scored.takeWhile { acc -> acc.second > 10 }
                .joinToString(" ") { it.first }
                .take(targetLen)
            val compressedTokens = (compressed.length * 0.6).toInt()
            return CompressedContext(
                context, compressed, name,
                originalTokens, compressedTokens,
                compressedTokens.toFloat() / originalTokens.coerceAtLeast(1)
            )
        }
        override fun estimateCompressionRatio(context: String, targetTokens: Int) = 0.5f
    }

    class EntityExtractionCompression : CompressionStrategy {
        override val name = "EntityExtraction"
        override suspend fun compress(context: String, targetTokens: Int): CompressedContext {
            val originalTokens = (context.length * 0.6).toInt()
            // 提取实体（简化：大写词/数字/专有名词）
            val entities = mutableListOf<String>()
            Regex("[A-Z][a-z]+").findAll(context).forEach { entities.add(it.value) }
            Regex("\\d+").findAll(context).forEach { entities.add(it.value) }
            Regex("[\\u4e00-\\u9fa5]{2,4}").findAll(context).forEach { entities.add(it.value) }
            val compressed = "实体: " + entities.distinct().take(50).joinToString(", ")
            val compressedTokens = (compressed.length * 0.6).toInt()
            return CompressedContext(
                context, compressed, name,
                originalTokens, compressedTokens,
                compressedTokens.toFloat() / originalTokens.coerceAtLeast(1)
            )
        }
        override fun estimateCompressionRatio(context: String, targetTokens: Int) = 0.1f
    }

    class SlidingWindowCompression : CompressionStrategy {
        override val name = "SlidingWindow"
        override suspend fun compress(context: String, targetTokens: Int): CompressedContext {
            val originalTokens = (context.length * 0.6).toInt()
            val targetLen = targetTokens * 5 / 3
            // 保留最近的内容
            val compressed = context.takeLast(targetLen)
            val compressedTokens = (compressed.length * 0.6).toInt()
            return CompressedContext(
                context, compressed, name,
                originalTokens, compressedTokens,
                compressedTokens.toFloat() / originalTokens.coerceAtLeast(1)
            )
        }
        override fun estimateCompressionRatio(context: String, targetTokens: Int) = 0.7f
    }

    class RAGCompression : CompressionStrategy {
        override val name = "RAG"
        override suspend fun compress(context: String, targetTokens: Int): CompressedContext {
            val originalTokens = (context.length * 0.6).toInt()
            // 简化：分段 + 检索 top-K
            val chunks = context.split(Regex("\n\n+")).filter { it.isNotBlank() }
            val targetLen = targetTokens * 5 / 3
            var compressed = ""
            for (chunk in chunks) {
                if (compressed.length + chunk.length > targetLen) break
                compressed += chunk + "\n\n"
            }
            val compressedTokens = (compressed.length * 0.6).toInt()
            return CompressedContext(
                context, compressed, name,
                originalTokens, compressedTokens,
                compressedTokens.toFloat() / originalTokens.coerceAtLeast(1)
            )
        }
        override fun estimateCompressionRatio(context: String, targetTokens: Int) = 0.4f
    }
}
