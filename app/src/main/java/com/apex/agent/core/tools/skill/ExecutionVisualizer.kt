package com.apex.agent.core.tools.skill

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

class ExecutionVisualizer
data class VisualizationData(val data: String = "")
data class NodeVisualState(val data: String = "")
data class ConnectionVisual(val data: String = "")
data class ExecutionStatistics(val data: String = "")
data class PerformanceAnalysis(val data: String = "")
data class Bottleneck(val data: String = "")
enum class BottleneckSeverity { DEFAULT }
data class PerformanceRecommendation(val data: String = "")
enum class RecommendationType { DEFAULT }
