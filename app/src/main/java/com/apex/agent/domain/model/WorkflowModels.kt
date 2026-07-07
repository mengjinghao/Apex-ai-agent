package com.apex.agent.domain.model

/**
 * 统一工作流定义
 * 包含完整的DAG节点与边信息，作为所有工作流执行器的通用数据模型
 */
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 工作流节点
 * @param id 节点唯一标识
 * @param type 节点类型（START/END/ACTION/CONDITION/PARALLEL/LOOP/DELAY/SCRIPT/SUB_WORKFLOW/NOTIFICATION）
 * @param label 节点显示名称
 * @param config 节点配置参数键值对
 * @param position 节点在画布上的位置（仅用于可视化）
 */
data class WorkflowNode(
    val id: String,
    val type: NodeType,
    val label: String = "",
    val config: Map<String, String> = emptyMap(),
    val position: NodePosition? = null
)

/**
 * 节点类型枚举
 */
enum class NodeType {
    START, END, ACTION, CONDITION, PARALLEL, LOOP, DELAY, SCRIPT, SUB_WORKFLOW, NOTIFICATION
}

/**
 * 节点在画布上的坐标位置
 */
data class NodePosition(val x: Float, val y: Float)

/**
 * 工作流边（有向连接）
 * @param id 边唯一标识
 * @param sourceNodeId 源节点ID
 * @param targetNodeId 目标节点ID
 * @param condition 可选的条件表达式（用于条件分支）
 * @param label 边的显示标签
 */
data class WorkflowEdge(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val condition: String? = null,
    val label: String = ""
)

/**
 * 工作流执行状态
 * @param workflowId 工作流ID
 * @param state 当前执行状态
 * @param currentNodeId 当前执行的节点ID
 * @param startedAt 开始执行时间戳
 * @param completedAt 完成时间戳（可能为null）
 * @param progress 执行进度（0.0 ~ 1.0）
 * @param error 错误信息（执行失败时设置）
 * @param results 各节点输出结果的键值对
 */
data class WorkflowExecution(
    val workflowId: String,
    val state: ExecutionState,
    val currentNodeId: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val results: Map<String, String> = emptyMap()
)

/**
 * 工作流执行状态枚举
 */
enum class ExecutionState {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

/**
 * 工作流执行结果
 * @param workflowId 工作流ID
 * @param success 是否成功
 * @param executionTimeMs 总执行耗时（毫秒）
 * @param nodeResults 各节点的执行结果映射
 * @param error 整体错误信息（失败时设置）
 */
data class WorkflowResult(
    val workflowId: String,
    val success: Boolean,
    val executionTimeMs: Long,
    val nodeResults: Map<String, NodeResult>,
    val error: String? = null
)

/**
 * 单节点执行结果
 * @param nodeId 节点ID
 * @param success 是否成功
 * @param output 节点输出内容
 * @param error 节点错误信息
 * @param executionTimeMs 节点执行耗时（毫秒）
 */
data class NodeResult(
    val nodeId: String,
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0
)
