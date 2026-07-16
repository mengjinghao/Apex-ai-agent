package com.apex.agent.core.workflow.enhanced.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 增强版工作流定义 - 融合 LangGraph / Temporal / Airflow / Dify / n8n 等顶级工作流系统的设计模式
 *
 * 支持能力：
 * - 完整节点类型：TRIGGER / EXECUTE / CONDITION / LOGIC / EXTRACT / FAN_OUT / FAN_IN / LOOP / SUB_WORKFLOW / HUMAN_INPUT / SAGA / DELAY / END
 * - 连接条件：ON_SUCCESS / ON_ERROR / TRUE / FALSE / ALWAYS
 * - 节点级 RetryPolicy + Circuit Breaker
 * - Checkpoint & Durable Execution
 * - 子工作流嵌套
 * - 并行 Fan-out / Fan-in + Barrier 同步
 * - 循环（Count / ForEach / While / MapReduce）
 * - Saga 补偿事务
 * - 人在回路审批
 * - 事件驱动触发
 * - 版本管理
 *
 * @see com.apex.agent.core.workflow.enhanced.EnhancedWorkflowExecutor
 */
@Serializable
data class EnhancedWorkflow(
    val id: String = generateWorkflowId(),
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val nodes: List<EnhancedNode>,
    val connections: List<EnhancedConnection>,
    val variables: Map<String, String> = emptyMap(),
    val sagaMode: Boolean = false,
    val maxConcurrency: Int = 10,
    val defaultRetryPolicy: RetryPolicyDef = RetryPolicyDef(),
    val timeoutMs: Long = 30 * 60_000L,
    val enabled: Boolean = true,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        private const val TAG = "EnhancedWorkflow"

        fun generateWorkflowId(): String =
            "ewf_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        fun fromJson(json: String): EnhancedWorkflow? = try {
            Json.decodeFromString<EnhancedWorkflow>(json)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse EnhancedWorkflow from JSON", e)
            null
        }
    }

    fun toJson(): String = Json.encodeToString(this)

    /** 获取所有触发节点（工作流入口） */
    fun getTriggerNodes(): List<EnhancedNode> = nodes.filter { it.type == EnhancedNodeType.TRIGGER }

    /** 根据 ID 获取节点 */
    fun getNodeById(nodeId: String): EnhancedNode? = nodes.find { it.id == nodeId }

    /** 获取某节点的所有出边 */
    fun getOutgoingConnections(nodeId: String): List<EnhancedConnection> =
        connections.filter { it.sourceNodeId == nodeId }

    /** 获取某节点的所有入边 */
    fun getIncomingConnections(nodeId: String): List<EnhancedConnection> =
        connections.filter { it.targetNodeId == nodeId }

    /** 获取邻接表（用于拓扑排序与环检测） */
    fun getAdjacencyList(): Map<String, List<String>> =
        nodes.associate { node ->
            node.id to connections.filter { it.sourceNodeId == node.id }.map { it.targetNodeId }
        }

    /** 获取入度表 */
    fun getInDegreeMap(): Map<String, Int> {
        val inDegree = nodes.associate { it.id to 0 }.toMutableMap()
        connections.forEach { conn ->
            inDegree[conn.targetNodeId] = (inDegree[conn.targetNodeId] ?: 0) + 1
        }
        return inDegree
    }

    /** 校验工作流完整性 */
    fun validate(): EnhancedValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (id.isBlank()) errors.add("工作流 ID 不能为空")
        if (name.isBlank()) errors.add("工作流名称不能为空")
        if (nodes.isEmpty()) {
            errors.add("工作流至少需要一个节点")
            return EnhancedValidationResult(false, errors, warnings)
        }

        val nodeIds = nodes.map { it.id }.toSet()
        if (nodeIds.size != nodes.size) errors.add("存在重复节点 ID")

        val triggerNodes = getTriggerNodes()
        when {
            triggerNodes.isEmpty() -> warnings.add("未找到触发节点 - 工作流无法自动启动")
            triggerNodes.size > 1 -> warnings.add("存在多个触发节点 - 仅第一个会被用作入口")
        }

        connections.forEach { conn ->
            if (conn.sourceNodeId !in nodeIds) {
                errors.add("连接 ${conn.id} 的源节点不存在: ${conn.sourceNodeId}")
            }
            if (conn.targetNodeId !in nodeIds) {
                errors.add("连接 ${conn.id} 的目标节点不存在: ${conn.targetNodeId}")
            }
        }

        nodes.forEach { node ->
            val r = node.validate()
            if (r is EnhancedNode.ValidationResult.Invalid) {
                errors.addAll(r.errors.map { "节点 ${node.id} (${node.name}): $it" })
            }
        }

        // 检查 FAN_OUT / FAN_IN 配对
        val fanOutNodes = nodes.filter { it.type == EnhancedNodeType.FAN_OUT }
        val fanInNodes = nodes.filter { it.type == EnhancedNodeType.FAN_IN }
        fanOutNodes.forEach { fo ->
            val hasMatchingFanIn = fanInNodes.any { fi ->
                getOutgoingConnections(fo.id).any { it.targetNodeId == fi.id } ||
                    connections.any { it.sourceNodeId == fo.id && getOutgoingConnections(it.targetNodeId).any { c -> c.targetNodeId == fi.id } }
            }
            if (!hasMatchingFanIn) {
                warnings.add("FAN_OUT 节点 ${fo.name} 未找到配对的 FAN_IN 节点")
            }
        }

        return EnhancedValidationResult(errors.isEmpty(), errors, warnings)
    }
}

@Serializable
data class EnhancedValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)
