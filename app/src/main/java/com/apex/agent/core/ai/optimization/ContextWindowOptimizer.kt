package com.apex.agent.core.ai.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class ContextWindowConfig(
    val maxTokens: Int = 8192,
    val reservedTokens: Int = 512,
    val compressionEnabled: Boolean = true,
    val summarizationThreshold: Int = 4096,
    val slidingWindowSize: Int = 2048,
    val pruneStrategy: PruneStrategy = PruneStrategy.SUMMARIZE_OLDEST
)

enum class PruneStrategy {
    SUMMARIZE_OLDEST, DROP_OLDEST, DROP_LOWEST_SCORE,
    KEEP_RECENT, SEMANTIC_CLUSTER
}

data class ContextSegment(
    val id: String,
    val content: String,
    val tokenCount: Int,
    val timestampMs: Long,
    val relevanceScore: Double = 1.0,
    val segmentType: SegmentType = SegmentType.CONVERSATION,
    val metadata: Map<String, Any> = emptyMap()
)

enum class SegmentType {
    CONVERSATION, SYSTEM_PROMPT, TOOL_RESULT, CODE_BLOCK,
    ANALYSIS, ERROR_LOG, USER_INPUT, MODEL_OUTPUT, MEMORY
}

data class CompressedContext(
    val originalTokens: Int,
    val compressedTokens: Int,
    val compressionRatio: Double,
    val segments: List<ContextSegment>,
    val summary: String? = null,
    val pruningApplied: List<String> = emptyList()
)

data class ContextMetrics(
    val totalSegmentsTracked: Long,
    val totalTokensProcessed: Long,
    val averageCompressionRatio: Double,
    val cacheHitRate: Double,
    val pruneEvents: Long,
    val currentTokenCount: Int,
    val maxTokenCount: Int,
    val averageRelevanceScore: Double
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val estimatedCost: Double,
    val modelName: String = "default"
)

data class SemanticScore(
    val segmentId: String,
    val score: Double,
    val keywords: List<String> = emptyList(),
    val confidence: Double
)

class ContextWindowOptimizer private constructor() {

    private val contextSegments = CopyOnWriteArrayList<ContextSegment>()
        private val segmentIndex = ConcurrentHashMap<String, ContextSegment>()
        private val compressionCache = ConcurrentHashMap<String, CompressedContext>()
        private val config = ContextWindowConfig()
        private val totalSegmentsTracked = AtomicLong(0)
        private val totalTokensProcessed = AtomicLong(0)
        private val pruneEventsCount = AtomicLong(0)
        private val cacheHits = AtomicLong(0)
        private val cacheMisses = AtomicLong(0)
        private var scope: CoroutineScope? = null
    private val mutex = Mutex()

    companion object {
        @Volatile
        private var instance: ContextWindowOptimizer? = null

        fun getInstance(): ContextWindowOptimizer {
            return instance ?: synchronized(this) {
                instance ?: ContextWindowOptimizer().also { instance = it }
            }
        }
        private const val MAX_CACHE_SIZE = 200
        private const val MAX_SEGMENTS = 500
    }
        fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(60000L)
                maintenanceCycle()
            }
        }
    }
        fun addSegment(content: String, segmentType: SegmentType = SegmentType.CONVERSATION, relevanceScore: Double = 1.0): String {
        val id = "seg_${System.currentTimeMillis()}_${segmentType.name}"
        val tokenCount = estimateTokens(content)
        val segment = ContextSegment(
            id = id,
            content = content,
            tokenCount = tokenCount,
            timestampMs = System.currentTimeMillis(),
            relevanceScore = relevanceScore,
            segmentType = segmentType
        )
        contextSegments.add(segment)
        segmentIndex[id] = segment
        totalSegmentsTracked.incrementAndGet()
        totalTokensProcessed.addAndGet(tokenCount.toLong())
        id
    }
        fun addSegments(segments: List<Pair<String, SegmentType>>) {
        for ((content, type) in segments) {
            addSegment(content, type)
        }
    }
        fun getSegment(id: String): ContextSegment? = segmentIndex[id]

    fun getAllSegments(): List<ContextSegment> = contextSegments.toList()
        fun getSegmentsByType(type: SegmentType): List<ContextSegment> {
        contextSegments.filter { it.segmentType == type }.sortedByDescending { it.timestampMs }
    }
        fun getRecentSegments(count: Int = 10): List<ContextSegment> {
        contextSegments.sortedByDescending { it.timestampMs }.take(count)
    }
        fun getCurrentTokenCount(): Int {
        contextSegments.sumOf { it.tokenCount }
    }
        fun isOverCapacity(): Boolean {
        getCurrentTokenCount() > config.maxTokens - config.reservedTokens
    }
        fun compressContext(): CompressedContext {
        val currentTokens = getCurrentTokenCount()
        val appliedPrunes = mutableListOf<String>()
        if (!isOverCapacity()) {
            return CompressedContext(currentTokens, currentTokens, 1.0, contextSegments.toList(), null, emptyList())
        }
        var segments = contextSegments.toList()
        val targetSize = config.maxTokens - config.reservedTokens

        while (segments.sumOf { it.tokenCount } > targetSize && segments.isNotEmpty()) {
            when (config.pruneStrategy) {
                PruneStrategy.SUMMARIZE_OLDEST -> {
                    val oldestNonSystem = segments
                        .filter { it.segmentType != SegmentType.SYSTEM_PROMPT }
                        .minByOrNull { it.timestampMs }
        if (oldestNonSystem != null) {
                        val summary = summarizeText(oldestNonSystem.content)
        val summaryTokens = estimateTokens(summary)
                        segments = segments.map {
                            if (it.id == oldestNonSystem.id) it.copy(content = summary, tokenCount = summaryTokens)
                            else it
                        }
                        appliedPrunes.add("summarized:${oldestNonSystem.id}")
                    }
                }
                PruneStrategy.DROP_OLDEST -> {
                    val oldest = segments.minByOrNull { it.timestampMs }
        if (oldest != null && oldest.segmentType != SegmentType.SYSTEM_PROMPT) {
                        segments = segments.filter { it.id != oldest.id }
                        appliedPrunes.add("dropped:${oldest.id}")
                    }
                }
                PruneStrategy.DROP_LOWEST_SCORE -> {
                    val lowest = segments.minByOrNull { it.relevanceScore }
        if (lowest != null && lowest.segmentType != SegmentType.SYSTEM_PROMPT) {
                        segments = segments.filter { it.id != lowest.id }
                        appliedPrunes.add("dropped_low_score:${lowest.id}")
                    }
                }
                PruneStrategy.KEEP_RECENT -> {
                    val toDrop = segments.sortedBy { it.timestampMs }.take(segments.size / 4)
                    segments = segments.filter { it !in toDrop || it.segmentType == SegmentType.SYSTEM_PROMPT }
                    appliedPrunes.add("kept_recent:dropped_${toDrop.size}")
                }
                PruneStrategy.SEMANTIC_CLUSTER -> {
                    val toCluster = segments.filter { it.segmentType != SegmentType.SYSTEM_PROMPT }
        if (toCluster.size >= 3) {
                        val clusterSize = (toCluster.size / 2).coerceAtLeast(1)
        val toRemove = toCluster.take(clusterSize)
                        segments = segments.filter { it !in toRemove }
                        appliedPrunes.add("semantic_cluster:removed_${clusterSize}")
                    }
                }
            }
        }
        val compressedTokens = segments.sumOf { it.tokenCount }
        val summary = if (appliedPrunes.isNotEmpty()) {
            "Context compressed: ${appliedPrunes.joinToString(", ")}"
        } else null

        pruneEventsCount.incrementAndGet()
        contextSegments.clear()
        contextSegments.addAll(segments)
        segmentIndex.clear()
        segments.forEach { segmentIndex[it.id] = it }
        val compressed = CompressedContext(
            originalTokens = currentTokens,
            compressedTokens = compressedTokens,
            compressionRatio = if (currentTokens > 0) compressedTokens.toDouble() / currentTokens else 1.0,
            segments = segments,
            summary = summary,
            pruningApplied = appliedPrunes
        )
        if (compressionCache.size < MAX_CACHE_SIZE) {
            compressionCache["ctx_${System.currentTimeMillis()}"] = compressed
        }
        compressed
    }
        fun getOptimizedContext(targetTokenCount: Int? = null): List<ContextSegment> {
        val target = targetTokenCount ?: (config.maxTokens - config.reservedTokens)
        var segments = contextSegments.toList()
        var tokenCount = segments.sumOf { it.tokenCount }
        if (tokenCount <= target) return segments

        segments = segments.sortedByDescending { it.relevanceScore }
            .thenByDescending { it.timestampMs }
        val result = mutableListOf<ContextSegment>()
        var runningTotal = 0
        for (segment in segments) {
            if (runningTotal + segment.tokenCount <= target) {
                result.add(segment)
                runningTotal += segment.tokenCount
            }
        }
        result.sortedBy { it.timestampMs }
    }
        fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val words = text.split(Regex("\\s+")).size
        val chars = text.length
        (words * 1.3 + chars * 0.04).toInt().coerceAtLeast(1)
    }
        private fun summarizeText(text: String): String {
        if (text.length <= 200) return text
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        when {
            sentences.size <= 2 -> text.take(150) + "..."
            sentences.size <= 4 -> sentences.take(2).joinToString(" ") + "..."
            else -> {
                val first = sentences.first()
        val last = sentences.last()
                "$first ... $last"
            }
        }
    }
        fun computeRelevance(query: String): List<SemanticScore> {
        val queryWords = query.lowercase().split(Regex("\\s+")).toSet()
        contextSegments.map { segment ->
            val contentWords = segment.content.lowercase().split(Regex("\\s+")).toSet()
        val common = queryWords.intersect(contentWords)
        val score = if (queryWords.isNotEmpty()) common.size.toDouble() / queryWords.size else 0.0
            SemanticScore(
                segmentId = segment.id,
                score = score.coerceIn(0.0, 1.0),
                keywords = common.take(5).toList(),
                confidence = min(1.0, score * 1.5)
            )
        }.sortedByDescending { it.score }
    }
        fun getMetrics(): ContextMetrics {
        val avgRelevance = if (contextSegments.isNotEmpty()) {
            contextSegments.map { it.relevanceScore }.average()
        } else 0.0
        val compressions = compressionCache.values
        val avgCompression = if (compressions.isNotEmpty()) {
            compressions.map { it.compressionRatio }.average()
        } else 1.0
        val totalAccesses = cacheHits.get() + cacheMisses.get()
        val hitRate = if (totalAccesses > 0) cacheHits.get().toDouble() / totalAccesses else 0.0
        ContextMetrics(
            totalSegmentsTracked = totalSegmentsTracked.get(),
            totalTokensProcessed = totalTokensProcessed.get(),
            averageCompressionRatio = avgCompression,
            cacheHitRate = hitRate,
            pruneEvents = pruneEventsCount.get(),
            currentTokenCount = getCurrentTokenCount(),
            maxTokenCount = config.maxTokens,
            averageRelevanceScore = avgRelevance
        )
    }
        fun getMemoryUsage(): Long {
        contextSegments.sumOf { it.content.toByteArray().size.toLong() }
    }
        fun clear() {
        contextSegments.clear()
        segmentIndex.clear()
        compressionCache.clear()
    }
        fun clearSegmentsByType(type: SegmentType) {
        val toRemove = contextSegments.filter { it.segmentType == type }
        contextSegments.removeAll(toRemove)
        toRemove.forEach { segmentIndex.remove(it.id) }
    }
        fun updateSegmentRelevance(id: String, score: Double) {
        val segment = segmentIndex[id] ?: return
        val updated = segment.copy(relevanceScore = score)
        val idx = contextSegments.indexOfFirst { it.id == id }
        if (idx >= 0) {
            contextSegments[idx] = updated
            segmentIndex[id] = updated
        }
    }
        fun updateConfig(newConfig: ContextWindowConfig): ContextWindowConfig { newConfig }
        fun resetMetrics() {
        totalSegmentsTracked.set(0)
        totalTokensProcessed.set(0)
        pruneEventsCount.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        compressionCache.clear()
    }
        private fun maintenanceCycle() {
        val now = System.currentTimeMillis()
        if (contextSegments.size > MAX_SEGMENTS) {
            val toRemove = contextSegments
                .filter { it.segmentType != SegmentType.SYSTEM_PROMPT }
                .sortedBy { it.timestampMs }
                .take(contextSegments.size - MAX_SEGMENTS)
            contextSegments.removeAll(toRemove)
            toRemove.forEach { segmentIndex.remove(it.id) }
        }
    }
}

private fun <T> List<T>.thenBy(selector: (T) -> Comparable<*>): List<T> {
    return this
}

private fun <T> List<T>.thenByDescending(selector: (T) -> Comparable<*>): List<T> {
    return this
}
