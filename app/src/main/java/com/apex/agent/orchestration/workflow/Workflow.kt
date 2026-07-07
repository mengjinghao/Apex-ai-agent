package com.apex.agent.orchestration.workflow

import android.os.Parcelable
import com.google.gson.Gson
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新工作流",
    val description: String = "",
    val nodes: MutableList<WorkflowNodeEntity> = mutableListOf(),
    val edges: MutableList<WorkflowEdge> = mutableListOf(),
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val isEnabled: Boolean = true,
    val executionCount: Int = 0,
    val lastExecution: Long? = null,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val tags: Set<String> = emptySet(),
    val category: String = "通用"
) : Parcelable {
    fun toJson(): String = Gson().toJson(this)

    fun addNode(node: WorkflowNodeEntity) {
        nodes.add(node)
        updated = System.currentTimeMillis()
    }

    fun addEdge(edge: WorkflowEdge) {
        edges.add(edge)
        updated = System.currentTimeMillis()
    }

    fun removeNode(nodeId: String) {
        nodes.removeAll { it.id == nodeId }
        edges.removeAll { it.fromNodeId == nodeId || it.toNodeId == nodeId }
        updated = System.currentTimeMillis()
    }

    fun getNode(nodeId: String): WorkflowNodeEntity? = nodes.find { it.id == nodeId }

    fun getNextNodes(nodeId: String): List<WorkflowNodeEntity> {
        return edges.filter { it.fromNodeId == nodeId }.mapNotNull { getNode(it.toNodeId) }
    }

    fun getInputNodes(): List<WorkflowNodeEntity> {
        val allToNodeIds = edges.map { it.toNodeId }.toSet()
        return nodes.filter { it.id !in allToNodeIds }
    }

    companion object {
        fun fromJson(json: String): Workflow? = try {
            Gson().fromJson(json, Workflow::class.java)
        } catch (e: Exception) {
            null
        }
    }
}

@Parcelize
data class WorkflowNodeEntity(
    val id: String = UUID.randomUUID().toString(),
    val type: NodeType = NodeType.AGENT,
    val x: Float = 0f,
    val y: Float = 0f,
    val title: String = "节点",
    val description: String = "",
    val agentId: String? = null,
    val agentRole: String? = null,
    val config: MutableMap<String, Any> = mutableMapOf(),
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList()
) : Parcelable

enum class NodeType(val displayName: String) {
    START("开�?),
    AGENT("Agent"),
    CONDITION("条件判断"),
    PARALLEL("并行执行"),
    JOIN("汇聚"),
    DELAY("延迟"),
    LOOP("循环"),
    END("结束"),
    CUSTOM("自定�?)
}

@Parcelize
data class WorkflowEdge(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val condition: String? = null,
    val label: String? = null,
    val type: EdgeType = EdgeType.NORMAL
) : Parcelable

enum class EdgeType {
    NORMAL, CONDITIONAL, PARALLEL
}
