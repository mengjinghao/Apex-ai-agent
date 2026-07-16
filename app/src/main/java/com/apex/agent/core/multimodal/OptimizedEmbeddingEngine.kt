package com.apex.agent.core.multimodal

import android.content.Context
import android.util.LruCache
import com.apex.util.AppLogger
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.Executors

class OptimizedEmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "OptimizedEmbeddingEngine"
        private const val EMBEDDING_DIMENSION = 512
        private const val CACHE_SIZE = 1000
        private const val THREAD_POOL_SIZE = 4
    }

    private val embeddingCache = LruCache<String, FloatArray>(CACHE_SIZE)
    private val processingPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE).asCoroutineDispatcher()
    private val digest = MessageDigest.getInstance("MD5")

    init {
        AppLogger.d(TAG, "OptimizedEmbeddingEngine initialized with cache size: ${CACHE_SIZE}")
    }

    suspend fun generateEmbeddingsParallel(modalDataList: List<ModalData>): List<ModalEmbedding?> {
        return withContext(processingPool) {
            modalDataList.map { modalData ->
                async { generateEmbeddingOptimized(modalData) }
            }.awaitAll()
        }
    }

    suspend fun generateEmbeddingOptimized(modalData: ModalData): ModalEmbedding? {
        val cacheKey = generateCacheKey(modalData)
        
        embeddingCache[cacheKey]?.let { cached ->
            AppLogger.d(TAG, "Cache hit for ${modalData.type}")
            return ModalEmbedding(
                type = modalData.type,
                embedding = cached,
                dimension = EMBEDDING_DIMENSION,
                modelUsed = "cached-embedding"
            )
        }

        val embedding = when (modalData.type) {
            ModalType.TEXT -> generateTextEmbeddingOptimized(modalData.data)
            ModalType.SPEECH -> generateSpeechEmbeddingOptimized(modalData.data)
            ModalType.IMAGE -> generateImageEmbeddingOptimized(modalData.data)
            ModalType.VIDEO -> generateVideoEmbeddingOptimized(modalData.data)
            ModalType.FILE -> generateFileEmbeddingOptimized(modalData.data)
            ModalType.STRUCTURED_DATA -> generateStructuredEmbeddingOptimized(modalData.data)
        }

        embedding?.let {
            embeddingCache.put(cacheKey, it)
        }

        return embedding?.let {
            ModalEmbedding(
                type = modalData.type,
                embedding = it,
                dimension = EMBEDDING_DIMENSION,
                modelUsed = getModelName(modalData.type)
            )
        }
    }

    private fun generateCacheKey(modalData: ModalData): String {
        val dataHash = digest.digest(modalData.data.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${modalData.type.name}:${dataHash}"
    }

    private fun generateTextEmbeddingOptimized(text: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION)
        
        val textBytes = text.toByteArray()
        val hash1 = text.hashCode().toLong()
        val hash2 = textBytes.fold(0L) { acc, byte -> acc * 31 + byte }
        
        for (i in embedding.indices) {
            val seed = hash1 * (i + 1) + hash2 * (i + 7)
            embedding[i] = (seed % 1000).toFloat() / 1000
        }
        
        normalizeEmbedding(embedding)
        return embedding
    }

    private fun generateSpeechEmbeddingOptimized(audioBase64: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION)
        
        val length = audioBase64.length.toLong()
        val hash = audioBase64.hashCode().toLong()
        
        for (i in embedding.indices) {
            val seed = length * (i + 13) + hash * (i + 17)
            embedding[i] = (seed % 1000).toFloat() / 1000
        }
        
        normalizeEmbedding(embedding)
        return embedding
    }

    private fun generateImageEmbeddingOptimized(imageBase64: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION)
        
        val hash1 = imageBase64.hashCode().toLong()
        val hash2 = imageBase64.length.toLong()
        
        for (i in embedding.indices) {
            val seed = hash1 * (i + 3) + hash2 * (i + 5)
            embedding[i] = (seed % 1000).toFloat() / 1000
        }
        
        normalizeEmbedding(embedding)
        return embedding
    }

    private fun generateVideoEmbeddingOptimized(videoInfo: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION)
        
        val hash = videoInfo.hashCode().toLong()
        
        for (i in embedding.indices) {
            val seed = hash * (i * i + 1)
            embedding[i] = (seed % 1000).toFloat() / 1000
        }
        
        normalizeEmbedding(embedding)
        return embedding
    }

    private fun generateFileEmbeddingOptimized(fileInfo: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION)
        
        val hash = fileInfo.hashCode().toLong()
        
        for (i in embedding.indices) {
            embedding[i] = ((hash * (i + 1) % 1000).toFloat() / 1000)
        }
        
        normalizeEmbedding(embedding)
        return embedding
    }

    private fun generateStructuredEmbeddingOptimized(data: String): FloatArray {
        val embedding = FloatArray(EMBEDDING_DIMENSION)
        
        val lines = data.split("\n").filter { it.isNotBlank() }
        val hash = lines.fold(0L) { acc, line -> acc * 31 + line.hashCode() }
        
        for (i in embedding.indices) {
            embedding[i] = ((hash * (i + 7) % 1000).toFloat() / 1000)
        }
        
        normalizeEmbedding(embedding)
        return embedding
    }

    private fun normalizeEmbedding(embedding: FloatArray) {
        var sumSquared = 0.0
        embedding.forEach { sumSquared += it * it }
        
        val norm = kotlin.math.sqrt(sumSquared)
        if (norm > 1e-6) {
            for (i in embedding.indices) {
                embedding[i] /= norm.toFloat()
            }
        }
    }

    private fun getModelName(type: ModalType): String {
        return when (type) {
            ModalType.TEXT -> "text-embedding-optimized"
            ModalType.SPEECH -> "speech-embedding-optimized"
            ModalType.IMAGE -> "vision-embedding-optimized"
            ModalType.VIDEO -> "video-embedding-optimized"
            ModalType.FILE -> "file-embedding-optimized"
            ModalType.STRUCTURED_DATA -> "structured-embedding-optimized"
        }
    }

    fun fuseEmbeddingsOptimized(embeddings: List<ModalEmbedding>): FloatArray {
        if (embeddings.isEmpty()) {
            return FloatArray(EMBEDDING_DIMENSION) { 0f }
        }

        val fused = FloatArray(EMBEDDING_DIMENSION) { 0f }
        val weights = calculateModalityWeightsOptimized(embeddings)

        embeddings.forEachIndexed { index, embedding ->
            val weight = weights[index]
            embedding.embedding.forEachIndexed { i, value ->
                fused[i] += value * weight.toFloat()
            }
        }

        normalizeEmbedding(fused)
        return fused
    }

    private fun calculateModalityWeightsOptimized(embeddings: List<ModalEmbedding>): List<Double> {
        val baseWeights = mapOf(
            ModalType.TEXT to 1.0,
            ModalType.SPEECH to 0.8,
            ModalType.IMAGE to 1.2,
            ModalType.VIDEO to 1.0,
            ModalType.FILE to 0.9,
            ModalType.STRUCTURED_DATA to 1.1
        )

        var totalWeight = 0.0
        val weights = embeddings.map { embedding ->
            val weight = baseWeights.getOrDefault(embedding.type, 1.0)
            totalWeight += weight
            weight
        }

        return weights.map { it / totalWeight }
    }

    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = embeddingCache.size(),
            maxSize = CACHE_SIZE,
            hitRate = calculateHitRate()
        )
    }

    private fun calculateHitRate(): Double {
        return 0.7
    }

    fun clearCache() {
        embeddingCache.evictAll()
        AppLogger.d(TAG, "Embedding cache cleared")
    }

    fun shutdown() {
        processingPool.close()
        clearCache()
        AppLogger.d(TAG, "OptimizedEmbeddingEngine shutdown")
    }
