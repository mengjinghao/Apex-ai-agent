package com.apex.agent.domain.model

/**
 * 统一工作流定义
 * 包含完整的DAG节点与边信息，作为所有工作流执行器的通用数据模型
 */

/**
 * 工作流节点
 * @param id 节点唯一标识
 * @param type 节点类型（START/END/ACTION/CONDITION/PARALLEL/LOOP/DELAY/SCRIPT/SUB_WORKFLOW/NOTIFICATION）
 * @param label 节点显示名称
 * @param config 节点配置参数键值对
 * @param position 节点在画布上的位置（仅用于可视化）
 */

/**
 * 节点类型枚举
 */

/**
 * 节点在画布上的坐标位置
 */

/**
 * 工作流边（有向连接）
 * @param id 边唯一标识
 * @param sourceNodeId 源节点ID
 * @param targetNodeId 目标节点ID
 * @param condition 可选的条件表达式（用于条件分支）
 * @param label 边的显示标签
 */

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

/**
 * 工作流执行状态枚举
 */

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
