package com.apex.agent.core.reflection

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Reflection(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val taskGoal: String,
    val executionSteps: List<ExecutionStep>,
    val outcome: TaskOutcome,
    val analysis: ReflectionAnalysis,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ExecutionStep(
    val stepNumber: Int,
    val action: String,
    val result: String,
    val success: Boolean,
    val durationMs: Long
)

@Serializable
data class TaskOutcome(
    val success: Boolean,
    val errorMessage: String? = null,
    val metrics: TaskMetrics
)

@Serializable
data class TaskMetrics(
    val totalSteps: Int,
    val successfulSteps: Int,
    val failedSteps: Int,
    val totalDurationMs: Long,
    val resourceUsage: ResourceUsage
)

@Serializable
data class ResourceUsage(
    val cpuUsagePercent: Double,
    val memoryUsageMb: Double,
    val apiCalls: Int
)

@Serializable
data class ReflectionAnalysis(
    val keyFactors: List<KeyFactor>,
    val suggestions: List<ImprovementSuggestion>,
    val confidence: Float,
    val learnings: List<Learning>
)

@Serializable
data class KeyFactor(
    val factor: String,
    val impact: ImpactLevel,
    val explanation: String
)

enum class ImpactLevel {
    HIGH, MEDIUM, LOW, NEGLIGIBLE
}

@Serializable
data class ImprovementSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val category: SuggestionCategory,
    val description: String,
    val priority: SuggestionPriority,
    val estimatedImpact: Float,
    val actionableSteps: List<String>
)

enum class SuggestionCategory {
    TASK_PLANNING, TOOL_SELECTION, ERROR_HANDLING, PERFORMANCE, RESOURCE_MANAGEMENT, OTHER
}

enum class SuggestionPriority {
    CRITICAL, HIGH, MEDIUM, LOW
}

@Serializable
data class Learning(
    val insight: String,
    val applicableScenarios: List<String>,
    val confidence: Float
)

@Serializable
data class ReflectionSummary(
    val reflectionId: String,
    val taskId: String,
    val overallAssessment: Assessment,
    val keyTakeaways: List<String>,
    val recommendedChanges: List<String>,
    val timestamp: Long
)

enum class Assessment {
    EXCELLENT, GOOD, FAIR, POOR
}