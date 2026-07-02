package com.apex.agent.kernel.burst.enhanced.dependency

import java.util.concurrent.ConcurrentHashMap

/**
 * B17: 技能依赖图引擎
 *
 * 增强现有 SkillDependencyResolver：
 * - 完整 DAG 拓扑排序
 * - 循环依赖检测
 * - 并行层级识别
 * - 依赖失败传播策略
 * - 动态依赖注入
 */
class SkillDependencyGraphEngine {

    /**
     * 依赖节点
     */
    data class SkillNode(
        val skillId: String,
        val skillName: String,
        val priority: Int = 0,
        val timeout: Long? = null,
        val retryCount: Int = 0,
        val isOptional: Boolean = false,
        val condition: String? = null  // 条件表达式
    )

    /**
     * 依赖边
     */
    data class DependencyEdge(
        val from: String,      // 依赖的 Skill
        val to: String,        // 被依赖的 Skill
        val type: DependencyType,
        val propagationStrategy: FailurePropagation = FailurePropagation.SKIP
    )

    enum class DependencyType {
        HARD,          // 硬依赖：必须先完成
        SOFT,          // 软依赖：最好先完成
        CONDITIONAL,   // 条件依赖：满足条件才依赖
        RESOURCE       // 资源依赖：共享资源
    }

    enum class FailurePropagation {
        SKIP,          // 跳过依赖此节点的任务
        RETRY,         // 重试此节点
        FALLBACK,      // 使用 fallback
        CONTINUE,      // 忽略失败继续
        ABORT_ALL      // 中止整个图
    }

    /**
     * 执行层级
     */
    data class ExecutionLayer(
        val layerIndex: Int,
        val nodes: List<SkillNode>,
        val isParallel: Boolean  // 同层是否可并行
    )

    /**
     * 图分析结果
     */
    data class GraphAnalysis(
        val totalNodes: Int,
        val totalEdges: Int,
        val cycles: List<List<String>>,
        val layers: List<ExecutionLayer>,
        val criticalPath: List<String>,
        val maxParallelism: Int,
        val estimatedDurationMs: Long
    )

    private val nodes = ConcurrentHashMap<String, SkillNode>()
    private val edges = mutableListOf<DependencyEdge>()
    private val adjacency = ConcurrentHashMap<String, MutableSet<String>>()  // from -> [to]
    private val reverseAdjacency = ConcurrentHashMap<String, MutableSet<String>>()  // to -> [from]

    /**
     * 添加节点
     */
    fun addNode(node: SkillNode) {
        nodes[node.skillId] = node
    }

    /**
     * 添加依赖
     */
    fun addDependency(edge: DependencyEdge): Boolean {
        // 检查循环
        if (wouldCreateCycle(edge.from, edge.to)) {
            return false
        }
        edges.add(edge)
        adjacency.computeIfAbsent(edge.from) { mutableSetOf() }.add(edge.to)
        reverseAdjacency.computeIfAbsent(edge.to) { mutableSetOf() }.add(edge.from)
        return true
    }

    /**
     * 检测是否会创建循环
     */
    private fun wouldCreateCycle(from: String, to: String): Boolean {
        // BFS: 从 to 出发能否到达 from
        if (from == to) return true
        val visited = mutableSetOf<String>()
        val queue: ArrayDeque<String> = ArrayDeque(listOf(to))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == from) return true
            if (current in visited) continue
            visited.add(current)
            adjacency[current]?.forEach { queue.add(it) }
        }
        return false
    }

    /**
     * 拓扑排序
     */
    fun topologicalSort(): List<String> {
        val inDegree = nodes.keys.associateWith { 0 }.toMutableMap()
        edges.forEach { e -> inDegree[e.to] = (inDegree[e.to] ?: 0) + 1 }

        val queue: ArrayDeque<String> = ArrayDeque(inDegree.filter { it.value == 0 }.keys)
        val result = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            adjacency[current]?.forEach { neighbor ->
                inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }
        return result
    }

    /**
     * 计算执行层级
     */
    fun computeExecutionLayers(): List<ExecutionLayer> {
        val inDegree = nodes.keys.associateWith { 0 }.toMutableMap()
        edges.filter { it.type == DependencyType.HARD }.forEach { e ->
            inDegree[e.to] = (inDegree[e.to] ?: 0) + 1
        }

        val layers = mutableListOf<ExecutionLayer>()
        val processed = mutableSetOf<String>()
        var layerIndex = 0

        while (processed.size < nodes.size) {
            // 找当前层级（入度为 0 且未处理）
            val currentLayer = nodes.keys
                .filter { it !in processed && (inDegree[it] ?: 0) == 0 }
                .mapNotNull { nodes[it] }
                .sortedByDescending { it.priority }

            if (currentLayer.isEmpty()) {
                // 剩余的都是循环依赖
                break
            }

            layers.add(ExecutionLayer(layerIndex, currentLayer, isParallel = currentLayer.size > 1))
            currentLayer.forEach { node ->
                processed.add(node.skillId)
                adjacency[node.skillId]?.forEach { neighbor ->
                    inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                }
            }
            layerIndex++
        }
        return layers
    }

    /**
     * 检测循环
     */
    fun detectCycles(): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun dfs(node: String) {
            if (node in recursionStack) {
                val cycleStart = path.indexOf(node)
                if (cycleStart >= 0) {
                    cycles.add(path.subList(cycleStart, path.size) + node)
                }
                return
            }
            if (node in visited) return

            visited.add(node)
            recursionStack.add(node)
            path.add(node)

            adjacency[node]?.forEach { dfs(it) }

            path.removeAt(path.size - 1)
            recursionStack.remove(node)
        }

        nodes.keys.forEach { if (it !in visited) dfs(it) }
        return cycles
    }

    /**
     * 计算关键路径（最长路径）
     */
    fun computeCriticalPath(): List<String> {
        val layers = computeExecutionLayers()
        if (layers.isEmpty()) return emptyList()

        // 简化：取每层中优先级最高的节点
        return layers.map { layer ->
            layer.nodes.maxByOrNull { it.priority }?.skillId ?: ""
        }.filter { it.isNotBlank() }
    }

    /**
     * 分析图
     */
    fun analyze(): GraphAnalysis {
        val cycles = detectCycles()
        val layers = computeExecutionLayers()
        val criticalPath = computeCriticalPath()
        val maxParallel = layers.maxOfOrNull { it.nodes.size } ?: 0
        val estimatedDuration = layers.sumOf { layer ->
            layer.nodes.maxOfOrNull { it.timeout ?: 5000L } ?: 5000L
        }

        return GraphAnalysis(
            totalNodes = nodes.size,
            totalEdges = edges.size,
            cycles = cycles,
            layers = layers,
            criticalPath = criticalPath,
            maxParallelism = maxParallel,
            estimatedDurationMs = estimatedDuration
        )
    }

    /**
     * 生成可视化
     */
    fun visualize(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 技能依赖图 ═══")
        sb.appendLine("节点: ${nodes.size} | 边: ${edges.size}")
        sb.appendLine()

        val layers = computeExecutionLayers()
        layers.forEach { layer ->
            sb.append("Layer ${layer.layerIndex}: ")
            layer.nodes.forEachIndexed { i, node ->
                if (i > 0) sb.append(" || ")
                sb.append("[${node.skillId}]")
                if (node.isOptional) sb.append("?")
            }
            sb.appendLine()
        }

        val cycles = detectCycles()
        if (cycles.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("⚠️ 检测到循环:")
            cycles.forEach { sb.appendLine("  ${it.joinToString(" → ")}") }
        }

        sb.appendLine()
        sb.appendLine("关键路径: ${computeCriticalPath().joinToString(" → ")}")
        sb.appendLine("最大并行度: ${layers.maxOfOrNull { it.nodes.size } ?: 0}")
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    /**
     * 清空
     */
    fun clear() {
        nodes.clear()
        edges.clear()
        adjacency.clear()
        reverseAdjacency.clear()
    }
}
