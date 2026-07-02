package com.apex.agent.core.patterns

/**
 * 访问者模式 - 工作流 DAG 遍历与分析
 * 在不修改工作流节点类的前提下，添加新的验证、指标收集、优化和导出操作
 */

/** 可访问节点接口 */
interface Visitable<T> {
    fun accept(visitor: Visitor<T>): T
}

/** 访问者接口 */
interface Visitor<T> {
    fun visit(node: Visitable<T>): T
}

/** 工作流节点类型 */
data class WorkflowTaskNode(
    val id: String,
    val name: String,
    val type: String,
    val duration: Long = 0,
    val memoryUsage: Long = 0,
    val errorCount: Int = 0,
    val config: Map<String, String> = emptyMap()
) : Visitable<WorkflowTaskNode> {
    override fun accept(visitor: Visitor<WorkflowTaskNode>): WorkflowTaskNode = visitor.visit(this)
}

/** 工作流 DAG */
class WorkflowDAG(private val description: String = "") {
    private val nodes = mutableListOf<WorkflowTaskNode>()
    private val adj = mutableMapOf<String, MutableList<String>>()

    fun addNode(node: WorkflowTaskNode) {
        nodes.add(node)
        adj.putIfAbsent(node.id, mutableListOf())
    }

    fun addEdge(fromId: String, toId: String) {
        adj.getOrPut(fromId) { mutableListOf() }.add(toId)
        adj.putIfAbsent(toId, mutableListOf())
    }

    fun getNodes(): List<WorkflowTaskNode> = nodes.toList()

    fun acceptAll(visitor: Visitor<WorkflowTaskNode>): List<WorkflowTaskNode> {
        return topologicalSort().map { it.accept(visitor) }
    }

    private fun topologicalSort(): List<WorkflowTaskNode> {
        val inDegree = mutableMapOf<String, Int>()
        nodes.forEach { inDegree[it.id] = 0 }
        adj.forEach { (_, targets) -> targets.forEach { t -> inDegree[t] = (inDegree[t] ?: 0) + 1 } }
        val queue = ArrayDeque<String>().apply { addAll(nodes.filter { inDegree[it.id] == 0 }.map { it.id }) }
        val sorted = mutableListOf<WorkflowTaskNode>()
        val nodeMap = nodes.associateBy { it.id }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            nodeMap[id]?.let { sorted.add(it) }
            adj[id]?.forEach { target ->
                inDegree[target] = (inDegree[target] ?: 1) - 1
                if (inDegree[target] == 0) queue.addLast(target)
            }
        }
        return sorted
    }
}

/** 验证访问者 */
class ValidateVisitor : Visitor<WorkflowTaskNode> {
    val errors = mutableListOf<String>()

    override fun visit(node: WorkflowTaskNode): WorkflowTaskNode {
        if (node.name.isBlank()) errors.add("Node ${node.id} has empty name")
        if (node.type.isBlank()) errors.add("Node ${node.id} has empty type")
        if (node.duration < 0) errors.add("Node ${node.id} has negative duration")
        return node
    }
}

/** 指标收集访问者 */
class MetricsCollectorVisitor : Visitor<WorkflowTaskNode> {
    var totalDuration = 0L
    var totalMemory = 0L
    var totalErrors = 0
    var nodeCount = 0

    override fun visit(node: WorkflowTaskNode): WorkflowTaskNode {
        totalDuration += node.duration
        totalMemory += node.memoryUsage
        totalErrors += node.errorCount
        nodeCount++
        return node
    }
}

/** 优化访问者 */
class OptimizationVisitor : Visitor<WorkflowTaskNode> {
    val suggestions = mutableListOf<String>()

    override fun visit(node: WorkflowTaskNode): WorkflowTaskNode {
        if (node.duration > 5000) suggestions.add("Node ${node.id}: duration ${node.duration}ms exceeds threshold")
        if (node.memoryUsage > 1024 * 1024) suggestions.add("Node ${node.id}: memory ${node.memoryUsage} too high")
        if (node.errorCount > 10) suggestions.add("Node ${node.id}: error count ${node.errorCount} too high")
        return node
    }
}

/** 导出访问者 */
class ExportVisitor(private val source: String, private val target: String) : Visitor<WorkflowTaskNode> {
    private val exported = mutableListOf<String>()

    override fun visit(node: WorkflowTaskNode): WorkflowTaskNode {
        exported.add("${node.id}:${node.name}:${node.type}")
        return node
    }

    fun getExportedData(): List<String> = exported.toList()
    fun getFormatDescription(): String = "Export from $source to $target: ${exported.size} nodes"
}
