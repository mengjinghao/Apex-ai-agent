package com.apex.agent.core.multimodal

import android.content.Context
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MultiModalFusionEngine(
    private val context: Context,
    private val aiService: AIService
) {

    companion object {
        private const val TAG = "MultiModalFusionEngine"
        private const val FUSED_EMBEDDING_DIMENSION = 512
    }

    private val embeddingCache = mutableMapOf<String, ModalEmbedding>()

    suspend fun processMultiModalInput(input: MultiModalInput): FusionResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Processing multi-modal input with ${input.modalities.size} modalities")

        val embeddings = input.modalities.mapNotNull { modalData ->
            embedModalData(modalData)
        }

        val fusedEmbedding = fuseEmbeddings(embeddings)
        val insights = extractInsights(input, embeddings)
        val reasoning = generateReasoning(input, insights)
        val confidence = calculateConfidence(embeddings, insights)

        FusionResult(
            inputId = input.id,
            fusedEmbedding = fusedEmbedding,
            insights = insights,
            confidence = confidence,
            reasoning = reasoning
        )
    }

    private suspend fun embedModalData(modalData: ModalData): ModalEmbedding? {
        val cacheKey = "${modalData.type.name}:${modalData.data.hashCode()}"
        
        embeddingCache[cacheKey]?.let {
            AppLogger.d(TAG, "Cache hit for ${modalData.type}")
            return it
        }

        val embedding = when (modalData.type) {
            ModalType.TEXT -> embedText(modalData.data)
            ModalType.SPEECH -> embedSpeech(modalData.data)
            ModalType.IMAGE -> embedImage(modalData.data)
            ModalType.VIDEO -> embedVideo(modalData.data)
            ModalType.FILE -> embedFile(modalData.data)
            ModalType.STRUCTURED_DATA -> embedStructuredData(modalData.data)
        }

        embedding?.let {
            embeddingCache[cacheKey] = it
        }

        return embedding
    }

    private fun embedText(text: String): ModalEmbedding {
        val embedding = FloatArray(FUSED_EMBEDDING_DIMENSION) { i ->
            (text.hashCode() * (i + 1) % 1000).toFloat() / 1000
        }
        normalizeEmbedding(embedding)
        
        return ModalEmbedding(
            type = ModalType.TEXT,
            embedding = embedding,
            dimension = FUSED_EMBEDDING_DIMENSION,
            modelUsed = "text-embedding"
        )
    }

    private fun embedSpeech(audioBase64: String): ModalEmbedding {
        val embedding = FloatArray(FUSED_EMBEDDING_DIMENSION) { i ->
            (audioBase64.length * (i + 7) % 1000).toFloat() / 1000
        }
        normalizeEmbedding(embedding)
        
        return ModalEmbedding(
            type = ModalType.SPEECH,
            embedding = embedding,
            dimension = FUSED_EMBEDDING_DIMENSION,
            modelUsed = "speech-embedding"
        )
    }

    private fun embedImage(imageBase64: String): ModalEmbedding {
        val embedding = FloatArray(FUSED_EMBEDDING_DIMENSION) { i ->
            (imageBase64.hashCode() * (i + 13) % 1000).toFloat() / 1000
        }
        normalizeEmbedding(embedding)
        
        return ModalEmbedding(
            type = ModalType.IMAGE,
            embedding = embedding,
            dimension = FUSED_EMBEDDING_DIMENSION,
            modelUsed = "vision-embedding"
        )
    }

    private fun embedVideo(videoInfo: String): ModalEmbedding {
        val embedding = FloatArray(FUSED_EMBEDDING_DIMENSION) { i ->
            (videoInfo.length * (i + 17) % 1000).toFloat() / 1000
        }
        normalizeEmbedding(embedding)
        
        return ModalEmbedding(
            type = ModalType.VIDEO,
            embedding = embedding,
            dimension = FUSED_EMBEDDING_DIMENSION,
            modelUsed = "video-embedding"
        )
    }

    private fun embedFile(fileInfo: String): ModalEmbedding {
        val embedding = FloatArray(FUSED_EMBEDDING_DIMENSION) { i ->
            (fileInfo.hashCode() * (i + 3) % 1000).toFloat() / 1000
        }
        normalizeEmbedding(embedding)
        
        return ModalEmbedding(
            type = ModalType.FILE,
            embedding = embedding,
            dimension = FUSED_EMBEDDING_DIMENSION,
            modelUsed = "file-embedding"
        )
    }

    private fun embedStructuredData(data: String): ModalEmbedding {
        val embedding = FloatArray(FUSED_EMBEDDING_DIMENSION) { i ->
            (data.length * (i + 5) % 1000).toFloat() / 1000
        }
        normalizeEmbedding(embedding)
        
        return ModalEmbedding(
            type = ModalType.STRUCTURED_DATA,
            embedding = embedding,
            dimension = FUSED_EMBEDDING_DIMENSION,
            modelUsed = "structured-data-embedding"
        )
    }

    private fun normalizeEmbedding(embedding: FloatArray) {
        var sum = 0.0f
        embedding.forEach { sum += it * it }
        val norm = kotlin.math.sqrt(sum.toDouble()).toFloat()
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
    }

    private fun fuseEmbeddings(embeddings: List<ModalEmbedding>): FloatArray {
        if (embeddings.isEmpty()) {
            return FloatArray(FUSED_EMBEDDING_DIMENSION) { 0f }
        }

        val fused = FloatArray(FUSED_EMBEDDING_DIMENSION) { 0f }
        val weights = calculateModalityWeights(embeddings)

        embeddings.forEachIndexed { index, embedding ->
            val weight = weights[index]
            for (i in fused.indices) {
                fused[i] += embedding.embedding[i] * weight
            }
        }

        normalizeEmbedding(fused)
        return fused
    }

    private fun calculateModalityWeights(embeddings: List<ModalEmbedding>): List<Double> {
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

    private fun extractInsights(input: MultiModalInput, embeddings: List<ModalEmbedding>): List<Insight> {
        val insights = mutableListOf<Insight>()

        input.modalities.forEach { modalData ->
            when (modalData.type) {
                ModalType.TEXT -> {
                    insights.addAll(extractTextInsights(modalData))
                }
                ModalType.IMAGE -> {
                    insights.addAll(extractImageInsights(modalData))
                }
                ModalType.SPEECH -> {
                    insights.addAll(extractSpeechInsights(modalData))
                }
                ModalType.VIDEO -> {
                    insights.addAll(extractVideoInsights(modalData))
                }
                else -> {}
            }
        }

        insights.addAll(findCrossModalRelationships(input))

        return insights
    }

    private fun extractTextInsights(modalData: ModalData): List<Insight> {
        val text = modalData.data
        val insights = mutableListOf<Insight>()

        if (text.contains("?", ignoreCase = true)) {
            insights.add(Insight(
                type = InsightType.QUESTION,
                content = "检测到问题: ${text}",
                sourceModal = ModalType.TEXT,
                confidence = 0.9f
            ))
        }

        if (text.contains("需�?, ignoreCase = true) || text.contains("应该", ignoreCase = true)) {
            insights.add(Insight(
                type = InsightType.ACTION_ITEM,
                content = "检测到行动需�? ${text}",
                sourceModal = ModalType.TEXT,
                confidence = 0.85f
            ))
        }

        insights.add(Insight(
            type = InsightType.FACTS,
            content = "文本内容: ${text.take(100)}${if (text.length > 100) "..." else ""}",
            sourceModal = ModalType.TEXT,
            confidence = 1.0f
        ))

        return insights
    }

    private fun extractImageInsights(modalData: ModalData): List<Insight> {
        val metadata = modalData.metadata
        
        return listOf(Insight(
            type = InsightType.FACTS,
            content = "图像信息 - 尺寸: ${metadata["width"]}x${metadata["height"]}, 格式: ${metadata["format"]}",
            sourceModal = ModalType.IMAGE,
            confidence = modalData.confidence
        ))
    }

    private fun extractSpeechInsights(modalData: ModalData): List<Insight> {
        return listOf(Insight(
            type = InsightType.FACTS,
            content = "语音输入 - 时长: ${modalData.metadata["duration"]}�?,
            sourceModal = ModalType.SPEECH,
            confidence = modalData.confidence
        ))
    }

    private fun extractVideoInsights(modalData: ModalData): List<Insight> {
        return listOf(Insight(
            type = InsightType.FACTS,
            content = "视频输入 - 时长: ${modalData.metadata["duration"]}�? 分辨�? ${modalData.metadata["resolution"]}",
            sourceModal = ModalType.VIDEO,
            confidence = modalData.confidence
        ))
    }

    private fun findCrossModalRelationships(input: MultiModalInput): List<Insight> {
        val insights = mutableListOf<Insight>()
        val textModals = input.modalities.filter { it.type == ModalType.TEXT }
        val imageModals = input.modalities.filter { it.type == ModalType.IMAGE }

        if (textModals.isNotEmpty() && imageModals.isNotEmpty()) {
            insights.add(Insight(
                type = InsightType.RELATIONSHIP,
                content = "检测到文本+图像组合输入，可能需要图像理解配合文本分�?,
                sourceModal = ModalType.TEXT,
                confidence = 0.8f,
                relatedInsights = listOf("text_analysis", "image_analysis")
            ))
        }

        return insights
    }

    private fun generateReasoning(input: MultiModalInput, insights: List<Insight>): String {
        return buildString {
            appendLine("多模态融合推理过�?")
            appendLine("输入模�? ${input.modalities.joinToString { it.type.name }}")
            appendLine()
            appendLine("提取的洞�?")
            insights.forEachIndexed { index, insight ->
                appendLine("${index + 1}. [${insight.type.name}] ${insight.content}")
            }
            appendLine()
            appendLine("推理逻辑:")
            appendLine("- 分析各模态数据，提取关键信息")
            appendLine("- 建立跨模态关�?)
            appendLine("- 综合所有信息生成结�?)
        }
    }

    private fun calculateConfidence(embeddings: List<ModalEmbedding>, insights: List<Insight>): Float {
        val embeddingConfidence = if (embeddings.isEmpty()) 0.5f else {
            embeddings.map { 1.0f }.average().toFloat()
        }
        
        val insightConfidence = if (insights.isEmpty()) 0.5f else {
            insights.map { it.confidence }.average()
        }

        return (embeddingConfidence * 0.4f + insightConfidence * 0.6f).coerceIn(0.1f, 1.0f)
    }

    suspend fun generateFusionReport(input: MultiModalInput): FusionReport = withContext(Dispatchers.IO) {
        val result = processMultiModalInput(input)
        
        val modalityContributions = mutableMapOf<ModalType, Double>()
        input.modalities.forEach { modalData ->
            modalityContributions[modalData.type] = modalityContributions.getOrDefault(modalData.type, 0.0) + 1.0
        }
        val total = modalityContributions.values.sum()
        modalityContributions.replaceAll { _, v -> v / total }

        FusionReport(
            inputId = input.id,
            summary = "多模态输入处理完成，�?${input.modalities.size} 种模态，生成 ${result.insights.size} 条洞�?,
            keyFindings = result.insights.filter { it.type == InsightType.FACTS || it.type == InsightType.RELATIONSHIP }
                .map { it.content },
            recommendations = result.insights.filter { it.type == InsightType.ACTION_ITEM }
                .map { it.content },
            modalityContributions = modalityContributions,
            overallConfidence = result.confidence
        )
    }

    fun clearCache() {
        embeddingCache.clear()
    }

    fun getCacheSize(): Int {
        return embeddingCache.size
    }
}