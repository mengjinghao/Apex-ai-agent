package com.apex.agent.domain.model

import kotlinx.serialization.Serializable

/**
 * 执行计划
 */
@Serializable
data class ExecutionPlan(
    val planId: String,
    val taskId: String,
    val taskType: String,
    val nodes: List<PlanNode>,
    val edges: List<PlanEdge>,
    val modelRoutes: Map<String, String> = emptyMap(),
    val contextAnchors: List<ContextAnchor> = emptyList(),
    val complexityScore: Int = 0,
    val estimatedTotalTimeMs: Long = 0
)

/**
 * 计划节点
 */
@Serializable
data class PlanNode(
    val nodeId: String,
    val type: String,
    val description: String,
    val estimatedTimeMs: Long,
    val priority: Int,
    val requiredTools: List<String>,
    val complexity: Int,
    val level: Int
)

/**
 * 计划边
 */
@Serializable
data class PlanEdge(
    val fromNodeId: String,
    val toNodeId: String
)

/**
 * 上下文锚点
 */
@Serializable
data class ContextAnchor(
    val anchorId: String,
    val content: String,
    val keyEntities: List<String> = emptyList(),
    val dependencies: List<String> = emptyList()
)

/**
 * 任务类型
 */
enum class TaskType {
    SOFTWARE_DEVELOPMENT,
    SYSTEM_ARCHITECTURE,
    DATABASE_DESIGN,
    FRONTEND_DEVELOPMENT,
    BACKEND_DEVELOPMENT,
    DEVOPS,
    CODE_GENERATION,
    TEXT_ANALYSIS,
    REPOSITORY_PROCESSING,
    MULTIMODAL_REASONING,
    DATA_ANALYSIS,
    RESEARCH,
    WRITING,
    PROBLEM_SOLVING,
    UNKNOWN
}

/**
 * 节点类型
 */
enum class NodeType {
    GOAL,
    MILESTONE,
    SUBTASK,
    ACTION,
    SYSTEM_LEVEL,
    MODULE_LEVEL,
    FILE_TYPE_LEVEL,
    FILE_LEVEL,
    IMPLEMENTATION_LEVEL
}

/**
 * 复杂度评估
 */
@Serializable
data class ComplexityAssessment(
    val estimatedLines: Long = 0,
    val moduleCount: Int = 1,
    val recommendedDepth: Int = 3,
    val estimatedDurationMs: Long = 0,
    val complexityScore: Int = 0
)

/**
 * 推理路径
 */
@Serializable
data class ReasoningPath(
    val pathId: String,
    val content: String,
    val confidence: Float = 0.5f,
    val critiques: List<String> = emptyList(),
    val refinedContent: String? = null
)
