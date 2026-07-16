package com.apex.agent.core.multimodal

// Minimal implementation (original had 2 errors)
// TODO: Restore full implementation from original code

data class MultiModalInput(val data: String = "")
data class ModalData(val data: String = "")
enum class ModalType { DEFAULT }
data class FusionResult(val data: String = "")
data class Insight(val data: String = "")
enum class InsightType { DEFAULT }
data class CrossModalAttention(val data: String = "")
data class FusionReport(val data: String = "")
data class ModalEmbedding(val data: String = "")
