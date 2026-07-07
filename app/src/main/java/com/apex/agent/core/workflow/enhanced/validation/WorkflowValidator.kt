package com.apex.agent.core.workflow.enhanced.validation

import com.apex.agent.core.workflow.enhanced.model.EnhancedConnection
import com.apex.agent.core.workflow.enhanced.model.EnhancedNodeType
import com.apex.agent.core.workflow.enhanced.model.EnhancedWorkflow

/**
 * 工作流验证器 - DAG 环检测 + 拓扑校验
 *
 * 灵感来源：
 * - Apache Airflow 的编译期 DAG 环检测（cycle_check）
 * - Dify 1.9.0 的 Queue-based Graph Engine
 * - LangGraph 的编译时图验证
 *
 * 校验内容：
 * 1. 必须有且仅有一个入口（TRIGGER 节点）
 * 2. 三色 DFS 环检测（White/Gray/Black）
 * 3. 所有节点必须从入口可达（BFS）
 * 4. 所有 FAN_OUT 节点必须有配对 FAN_IN
 * 5. 边的 source/target 必须存在
 * 6. 无悬空节点（非触发节点无入边）
 */
class WorkflowValidator {

    /**
     * 验证工作流，返回校验结果
     */
    fun validate(workflow: EnhancedWorkflow): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()

        // 1. 基础结构校验
        if (workflow.nodes.isEmpty()) {
            errors.add(ValidationError.NoNodes)
            return ValidationResult(errors, warnings, emptyList())
        }

        val nodeIds = workflow.nodes.map { it.id }.toSet()

        // 2. 边的端点校验
        workflow.connections.forEach { conn ->
            if (conn.sourceNodeId !in nodeIds) {
                errors.add(ValidationError.DanglingEdge(conn.id, conn.sourceNodeId, isSource = true))
            }
            if (conn.targetNodeId !in nodeIds) {
                errors.add(ValidationError.DanglingEdge(conn.id, conn.targetNodeId, isSource = false))
            }
        }

        // 3. 入口节点校验
        val triggerNodes = workflow.getTriggerNodes()
        when {
            triggerNodes.isEmpty() -> {
                errors.add(ValidationError.NoStartNode)
                return ValidationResult(errors, warnings, emptyList())
            }
        }

        // 4. 重复节点 ID
        val idCounts = workflow.nodes.groupingBy { it.id }.eachCount()
        idCounts.filter { it.value > 1 }.forEach { (id, count) ->
            errors.add(ValidationError.DuplicateNodeId(id, count))
        }

        // 5. 三色 DFS 环检测
        val cycle = detectCycle(workflow)
        if (cycle != null) {
            errors.add(ValidationError.CycleDetected(cycle))
        }

        // 6. 可达性校验（BFS from triggers）
        val reachable = bfsReachable(workflow, triggerNodes.map { it.id }.toSet())
        val unreachable = workflow.nodes.filter { it.id !in reachable && it.type != EnhancedNodeType.TRIGGER }
        unreachable.forEach { node ->
            warnings.add(ValidationWarning.UnreachableNode(node.id, node.name))
        }

        // 7. FAN_OUT / FAN_IN 配对校验
        val fanOutNodes = workflow.nodes.filter { it.type == EnhancedNodeType.FAN_OUT }
        val fanInNodes = workflow.nodes.filter { it.type == EnhancedNodeType.FAN_IN }.map { it.id }.toSet()
        fanOutNodes.forEach { fo ->
            val downstream = findAllDownstream(workflow, fo.id)
            if (downstream.none { it in fanInNodes }) {
                warnings.add(ValidationWarning.MissingFanIn(fo.id, fo.name))
            }
        }

        // 8. 悬空节点警告（非触发节点无入边）
        val nodesWithIncoming = workflow.connections.map { it.targetNodeId }.toSet()
        workflow.nodes.filter { it.type != EnhancedNodeType.TRIGGER && it.id !in nodesWithIncoming }
            .forEach { node ->
                warnings.add(ValidationWarning.DanglingNode(node.id, node.name))
            }

        // 9. 拓扑排序（Kahn 算法）
        val topologicalOrder = if (errors.isEmpty()) topologicalSort(workflow) else emptyList()

        return ValidationResult(errors, warnings, topologicalOrder)
    }

    /**
     * 三色 DFS 环检测
     * - White(0): 未访问
     * - Gray(1): 正在访问（在当前 DFS 路径上）
     * - Black(2): 已完成
     *
     * @return 检测到的环（节点 ID 列表），无环返回 null
     */
    private fun detectCycle(workflow: EnhancedWorkflow): List<String>? {
        val color = workflow.nodes.associate { it.id to 0 }.toMutableMap()
        val parent = mutableMapOf<String, String?>()
        val adj = workflow.getAdjacencyList()

        var cycleStart: String? = null
        var cycleEnd: String? = null

        fun dfs(u: String): Boolean {
            color[u] = 1
            val neighbors = adj[u] ?: emptyList()
            for (v in neighbors) {
                if (color[v] == 0) {
                    parent[v] = u
                    if (dfs(v)) return true
                } else if (color[v] == 1) {
                    // 找到回边 u -> v，环为 v ... -> u -> v
                    cycleStart = v
                    cycleEnd = u
                    return true
                }
            }
            color[u] = 2
            return false
        }

        for (node in workflow.nodes) {
            if (color[node.id] == 0) {
                if (dfs(node.id)) {
                    // 重建环路径
                    val cycle = mutableListOf<String>()
                    var cur: String? = cycleEnd
                    while (cur != null && cur != cycleStart) {
                        cycle.add(cur)
                        cur = parent[cur]
                    }
                    cycle.add(cycleStart!!)
                    cycle.reverse()
                    cycle.add(cycleStart!!)
                    return cycle
                }
            }
        }
        return null
    }

    /**
     * BFS 可达性分析
     */
    private fun bfsReachable(workflow: EnhancedWorkflow, startIds: Set<String>): Set<String> {
        val visited = mutableSetOf<String>()
        val queue: ArrayDeque<String> = ArrayDeque(startIds)
        visited.addAll(startIds)
        val adj = workflow.getAdjacencyList()

        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            adj[u]?.forEach { v ->
                if (v !in visited) {
                    visited.add(v)
                    queue.addLast(v)
                }
            }
        }
        return visited
    }

    /**
     * 找出某节点的所有下游节点（传递闭包）
     */
    private fun findAllDownstream(workflow: EnhancedWorkflow, startId: String): Set<String> {
        return bfsReachable(workflow, setOf(startId)) - startId
    }

    /**
     * Kahn 拓扑排序
     * @return 拓扑顺序的节点 ID 列表，若存在环返回空列表
     */
    private fun topologicalSort(workflow: EnhancedWorkflow): List<String> {
        val inDegree = workflow.getInDegreeMap().toMutableMap()
        val adj = workflow.getAdjacencyList()
        val queue: ArrayDeque<String> = ArrayDeque()

        inDegree.filter { it.value == 0 }.keys.forEach { queue.addLast(it) }

        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            result.add(u)
            adj[u]?.forEach { v ->
                inDegree[v] = (inDegree[v] ?: 1) - 1
                if (inDegree[v] == 0) queue.addLast(v)
            }
        }

        return if (result.size == workflow.nodes.size) result else emptyList()
    }
}

/**
 * 验证结果
 */
data class ValidationResult(
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val topologicalOrder: List<String>
) {
    val isValid: Boolean get() = errors.isEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * 验证错误（阻塞执行）
 */
sealed class ValidationError {
    data object NoStartNode : ValidationError()
    data object NoNodes : ValidationError()
    data class CycleDetected(val cycle: List<String>) : ValidationError()
    data class DanglingEdge(val edgeId: String, val nodeId: String, val isSource: Boolean) : ValidationError()
    data class DuplicateNodeId(val nodeId: String, val count: Int) : ValidationError()
    data class MultipleStartNodes(val ids: List<String>) : ValidationError()
    data class MissingJoin(val fanOutNodeId: String) : ValidationError()

    override fun toString(): String = when (this) {
        is NoStartNode -> "未找到触发节点"
        is NoNodes -> "工作流没有任何节点"
        is CycleDetected -> "检测到环: ${cycle.joinToString(" -> ")}"
        is DanglingEdge -> "边 $edgeId 的${if (isSource) "源" else "目标"}节点 $nodeId 不存在"
        is DuplicateNodeId -> "节点 ID $nodeId 重复 $count 次"
        is MultipleStartNodes -> "存在多个触发节点: ${ids.joinToString()}"
        is MissingJoin -> "FAN_OUT 节点 $fanOutNodeId 缺少配对的 JOIN"
    }
}

/**
 * 验证警告（不阻塞，但建议修复）
 */
sealed class ValidationWarning {
    data class UnreachableNode(val nodeId: String, val nodeName: String) : ValidationWarning()
    data class DanglingNode(val nodeId: String, val nodeName: String) : ValidationWarning()
    data class MissingFanIn(val fanOutNodeId: String, val fanOutNodeName: String) : ValidationWarning()

    override fun toString(): String = when (this) {
        is UnreachableNode -> "节点 $nodeName ($nodeId) 从入口不可达"
        is DanglingNode -> "节点 $nodeName ($nodeId) 没有入边（悬空）"
        is MissingFanIn -> "FAN_OUT 节点 $fanOutNodeName 缺少配对的 FAN_IN"
    }
}
