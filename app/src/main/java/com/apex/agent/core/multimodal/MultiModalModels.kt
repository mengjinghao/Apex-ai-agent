package com.apex.agent.core.multimodal

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MultiModalInput(
    val id: String = UUID.randomUUID().toString(),
    val modalities: List<ModalData>,
    val context: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ModalData(
    val type: ModalType,
    val data: String,
    val metadata: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

enum class ModalType {
    TEXT,
    SPEECH,
    IMAGE,
    VIDEO,
    FILE,
    STRUCTURED_DATA
}

@Serializable
data class FusionResult(
    val inputId: String,
    val fusedEmbedding: FloatArray,
    val insights: List<Insight>,
    val confidence: Float,
    val reasoning: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FusionResult
        return fusedEmbedding.contentEquals(other.fusedEmbedding)
    }

    override fun hashCode(): Int {
        return fusedEmbedding.contentHashCode()
    }
}

@Serializable
data class Insight(
    val id: String = UUID.randomUUID().toString(),
    val type: InsightType,
    val content: String,
    val sourceModal: ModalType,
    val confidence: Float,
    val relatedInsights: List<String> = emptyList()
)

enum class InsightType {
    FACTS,
    RELATIONSHIP,
    INFERENCE,
    ACTION_ITEM,
    QUESTION,
    SUMMARY
}

@Serializable
data class CrossModalAttention(
    val sourceModal: ModalType,
    val targetModal: ModalType,
    val attentionWeights: List<Float>,
    val alignedFeatures: List<String>
)

@Serializable
data class FusionReport(
    val inputId: String,
    val summary: String,
    val keyFindings: List<String>,
    val recommendations: List<String>,
    val modalityContributions: Map<ModalType, Double>,
    val overallConfidence: Float
)

@Serializable
data class ModalEmbedding(
    val type: ModalType,
    val embedding: FloatArray,
    val dimension: Int,
    val modelUsed: String
) {
    override fun equals(other: Any): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModalEmbedding
        return embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        return embedding.contentHashCode()
    }
}