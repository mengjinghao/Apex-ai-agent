package com.apex.agent.core.normal.dependency

import java.util.concurrent.ConcurrentHashMap

/**
 * F29: 工具调用链路与依赖分析
 *
 * 分析工具调用之间的关系：
 * - 调用链路追踪（A 调用 B，B 调用 C）
 * - 依赖关系图
 * - 性能瓶颈分析
 * - 失败影响分析
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的工具是并行的
 * - 狂暴的 racing 不形成链路
 * - 本功能分析**单 Agent 串行工具调用**的链路与依赖
 */

/**
 * 工具调用记录
 */
data class ToolInvocationRecord(
    val id: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val result: Any?,
    val success: Boolean,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val parentInvocationId: String? = null,  // 父调用（嵌套调用）
    val triggerSource: TriggerSource,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)

enum class TriggerSource {
    USER_REQUEST,       // 用户请求触发
    AI_DECISION,        // AI 决策调用
    CHAIN_DEPENDENCY,   // 链式依赖触发
    MACRO_EXECUTION,    // 宏执行
    AUTOMATION,         // 自动化
    RETRY,              // 重试
    COMPENSATION        // 补偿
}

/**
 * 调用链
 */
data class ToolCallChain(
    val rootId: String,
    val invocations: List<ToolInvocationRecord>,
    val depth: Int,
    val totalDurationMs: Long,
    val criticalPath: List<String>,
    val bottleneck: ToolInvocationRecord?
)

/**
 * 依赖关系
 */
data class ToolDependency(
    val toolName: String,
    val dependsOn: Set<String>,         // 依赖的工具
    val dependedBy: Set<String>,        // 被依赖的工具
    val producesData: List<String>,     // 产出的数据
    val consumesData: List<String>      // 消费的数据
)

/**
 * 依赖图
 */
data class DependencyGraph(
    val nodes: Set<String>,
    val edges: Set<DependencyEdge>,
    val cycles: List<List<String>>,
    val topologicalOrder: List<String>
)

data class DependencyEdge(val from: String, val to: String, val type: DependencyType)

enum class DependencyType {
    DATA_FLOW,       // 数据流依赖（A 的输出是 B 的输入）
    TEMPORAL,        // 时序依赖（A 必须在 B 之前）
    CONDITIONAL,     // 条件依赖（A 的结果决定 B 是否执行）
    RESOURCE         // 资源依赖（A 和 B 共享资源）
}

/**
 * 性能分析
 */
data class ToolPerformanceAnalysis(
    val toolStats: Map<String, ToolStats>,
    val callFrequency: Map<String, Int>,
    val avgDurationByTool: Map<String, Long>,
    val successRateByTool: Map<String, Float>,
    val bottleneckTool: String?,
    val slowestInvocations: List<ToolInvocationRecord>,
    val failureHotspots: List<ToolInvocationRecord>
)

data class ToolStats(
    val name: String,
    val totalCalls: Int,
    val successCount: Int,
    val failureCount: Int,
    val avgDurationMs: Long,
    val maxDurationMs: Long,
    val minDurationMs: Long,
    val totalRetries: Int,
    val p50DurationMs: Long,
    val p95DurationMs: Long,
    val p99DurationMs: Long
)

/**
 * 工具调用分析器
 */
class ToolCallDependencyAnalyzer {

    private val invocations = ConcurrentHashMap<String, MutableList<ToolInvocationRecord>>()

    /**
     * 记录调用
     */
    fun record(chatId: String, invocation: ToolInvocationRecord) {
        invocations.computeIfAbsent(chatId) { mutableListOf() }.add(invocation)
    }

    /**
     * 构建调用链
     */
    fun buildCallChain(chatId: String, rootId: String): ToolCallChain? {
        val chatInvocations = invocations[chatId] ?: return null
        val root = chatInvocations.find { it.id == rootId } ?: return null

        // BFS 收集所有相关调用
        val chain = mutableListOf<ToolInvocationRecord>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in visited) continue
            visited.add(id)
            val inv = chatInvocations.find { it.id == id } ?: continue
            chain.add(inv)
            // 查找子调用（parentInvocationId == id）
            chatInvocations.filter { it.parentInvocationId == id }.forEach {
                queue.add(it.id)
            }
        }

        val depth = computeDepth(chain, rootId)
        val totalDuration = chain.sumOf { it.durationMs }
        val criticalPath = findCriticalPath(chain, rootId)
        val bottleneck = chain.maxByOrNull { it.durationMs }

        return ToolCallChain(rootId, chain, depth, totalDuration, criticalPath, bottleneck)
    }

    /**
     * 构建依赖图
     */
    fun buildDependencyGraph(chatId: String): DependencyGraph {
        val chatInvocations = invocations[chatId] ?: return DependencyGraph(emptySet(), emptySet(), emptyList(), emptyList())
        val nodes = mutableSetOf<String>()
        val edges = mutableSetOf<DependencyEdge>()

        for (inv in chatInvocations) {
            nodes.add(inv.toolName)

            // 数据流依赖：如果 A 的输出被 B 的参数引用
            for (other in chatInvocations) {
                if (inv.id == other.id) continue
                if (hasDataDependency(inv, other)) {
                    edges.add(DependencyEdge(inv.toolName, other.toolName, DependencyType.DATA_FLOW))
                }
            }

            // 时序依赖：A 在 B 之前完成，且时间接近
            for (other in chatInvocations) {
                if (inv.id == other.id) continue
                if (inv.endTime < other.startTime && other.startTime - inv.endTime < 5000) {
                    edges.add(DependencyEdge(inv.toolName, other.toolName, DependencyType.TEMPORAL))
                }
            }
        }

        val cycles = detectCycles(nodes, edges)
        val topoOrder = topologicalSort(nodes, edges)

        return DependencyGraph(nodes, edges, cycles, topoOrder)
    }

    /**
     * 分析性能
     */
    fun analyzePerformance(chatId: String): ToolPerformanceAnalysis {
        val chatInvocations = invocations[chatId] ?: return ToolPerformanceAnalysis(emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, emptyList(), emptyList())

        val byTool = chatInvocations.groupBy { it.toolName }
        val stats = byTool.mapValues { (name, invs) ->
            val durations = invs.map { it.durationMs }.sorted()
            val successCount = invs.count { it.success }
            ToolStats(
                name = name,
                totalCalls = invs.size,
                successCount = successCount,
                failureCount = invs.size - successCount,
                avgDurationMs = durations.average().toLong(),
                maxDurationMs = durations.maxOrNull() ?: 0,
                minDurationMs = durations.minOrNull() ?: 0,
                totalRetries = invs.sumOf { it.retryCount },
                p50DurationMs = percentile(durations, 50),
                p95DurationMs = percentile(durations, 95),
                p99DurationMs = percentile(durations, 99)
            )
        }

        val frequency = byTool.mapValues { it.value.size }
        val avgDuration = stats.mapValues { it.value.avgDurationMs }
        val successRate = stats.mapValues { if (it.value.totalCalls > 0) it.value.successCount.toFloat() / it.value.totalCalls else 0f }

        val bottleneck = stats.maxByOrNull { it.value.avgDurationMs }?.key
        val slowest = chatInvocations.sortedByDescending { it.durationMs }.take(5)
        val failures = chatInvocations.filter { !it.success }.take(10)

        return ToolPerformanceAnalysis(stats, frequency, avgDuration, successRate, bottleneck, slowest, failures)
    }

    /**
     * 分析失败影响
     */
    fun analyzeFailureImpact(chatId: String, failedInvocationId: String): FailureImpact {
        val chatInvocations = invocations[chatId] ?: return FailureImpact(emptyList(), emptyList())
        val failed = chatInvocations.find { it.id == failedInvocationId } ?: return FailureImpact(emptyList(), emptyList())

        // 找到所有依赖失败调用的后续调用
        val affected = mutableListOf<ToolInvocationRecord>()
        val queue = ArrayDeque<String>()
        queue.add(failedInvocationId)
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in visited) continue
            visited.add(id)
            for (inv in chatInvocations) {
                if (inv.parentInvocationId == id || hasDataDependency(chatInvocations.find { it.id == id }!!, inv)) {
                    affected.add(inv)
                    queue.add(inv.id)
                }
            }
        }

        val recommendations = generateFailureRecommendations(failed, affected)
        return FailureImpact(affected, recommendations)
    }

    data class FailureImpact(
        val affectedInvocations: List<ToolInvocationRecord>,
        val recommendations: List<String>
    )

    /**
     * 生成可视化
     */
    fun visualizeCallChain(chain: ToolCallChain): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 工具调用链 ═══")
        sb.appendLine("根调用: ${chain.rootId}")
        sb.appendLine("深度: ${chain.depth}")
        sb.appendLine("总耗时: ${chain.totalDurationMs}ms")
        sb.appendLine()

        sb.appendLine("调用序列:")
        chain.invocations.forEach { inv ->
            val indent = "  ".repeat(getDepth(chain, inv.id))
            val status = if (inv.success) "✓" else "✗"
            sb.appendLine("$indent$status ${inv.toolName} (${inv.durationMs}ms)")
            if (!inv.success && inv.errorMessage != null) {
                sb.appendLine("$indent  错误: ${inv.errorMessage}")
            }
        }

        sb.appendLine()
        sb.appendLine("关键路径: ${chain.criticalPath.joinToString(" → ")}")
        chain.bottleneck?.let {
            sb.appendLine("瓶颈: ${it.toolName} (${it.durationMs}ms)")
        }
        sb.appendLine("═══════════════")
        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun computeDepth(chain: List<ToolInvocationRecord>, rootId: String): Int {
        var maxDepth = 0
        fun dfs(id: String, depth: Int) {
            if (depth > maxDepth) maxDepth = depth
            chain.filter { it.parentInvocationId == id }.forEach { dfs(it.id, depth + 1) }
        }
        dfs(rootId, 0)
        return maxDepth
    }

    private fun getDepth(chain: ToolCallChain, invocationId: String): Int {
        var depth = 0
        var current = chain.invocations.find { it.id == invocationId }
        while (current?.parentInvocationId != null) {
            depth++
            current = chain.invocations.find { it.id == current.parentInvocationId }
        }
        return depth
    }

    private fun findCriticalPath(chain: List<ToolInvocationRecord>, rootId: String): List<String> {
        // 找到从根到叶子的最长路径
        var longestPath = listOf<String>()
        fun dfs(id: String, path: List<String>) {
            val children = chain.filter { it.parentInvocationId == id }
            if (children.isEmpty()) {
                if (path.size > longestPath.size) longestPath = path
            } else {
                children.forEach { dfs(it.id, path + it.id) }
            }
        }
        dfs(rootId, listOf(rootId))
        return longestPath
    }

    private fun hasDataDependency(a: ToolInvocationRecord, b: ToolInvocationRecord): Boolean {
        // 简化：检查 B 的参数是否包含 A 的 ID 或 A 产出的数据
        val aResult = a.result?.toString() ?: ""
        return b.arguments.values.any { arg ->
            arg?.toString()?.contains(a.id) == true ||
            (aResult.length > 10 && arg?.toString()?.contains(aResult.take(20)) == true)
        }
    }

    private fun detectCycles(nodes: Set<String>, edges: Set<DependencyEdge>): List<List<String>> {
        val adj = edges.groupBy { it.from }.mapValues { it.value.map { e -> e.to } }
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun dfs(node: String) {
            if (node in recursionStack) {
                // 找到环
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

            adj[node]?.forEach { dfs(it) }

            path.removeAt(path.size - 1)
            recursionStack.remove(node)
        }

        nodes.forEach { if (it !in visited) dfs(it) }
        return cycles
    }

    private fun topologicalSort(nodes: Set<String>, edges: Set<DependencyEdge>): List<String> {
        val inDegree = nodes.associateWith { 0 }.toMutableMap()
        val adj = edges.groupBy { it.from }.mapValues { it.value.map { e -> e.to } }

        for (edge in edges) {
            inDegree[edge.to] = (inDegree[edge.to] ?: 0) + 1
        }

        val queue: ArrayDeque<String> = ArrayDeque(inDegree.filter { it.value == 0 }.keys)
        val result = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            adj[node]?.forEach { neighbor ->
                inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }

        return result
    }

    private fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = (sorted.size * p / 100.0).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun generateFailureRecommendations(failed: ToolInvocationRecord, affected: List<ToolInvocationRecord>): List<String> {
        val recs = mutableListOf<String>()
        recs.add("工具 ${failed.toolName} 失败，影响了 ${affected.size} 个后续调用")
        if (failed.retryCount > 0) {
            recs.add("已重试 ${failed.retryCount} 次仍失败，建议检查工具配置")
        }
        if (affected.size > 5) {
            recs.add("影响范围较大，建议启用补偿机制")
        }
        if (failed.errorMessage?.contains("timeout", ignoreCase = true) == true) {
            recs.add("超时错误，建议增加超时时间或优化工具性能")
        }
        if (failed.errorMessage?.contains("permission", ignoreCase = true) == true) {
            recs.add("权限错误，建议检查工具权限配置")
        }
        return recs
    }

    /**
     * 获取统计
     */
    fun getStats(chatId: String): AnalyzerStats {
        val invs = invocations[chatId] ?: return AnalyzerStats(0, 0, 0, 0)
        val total = invs.size
        val success = invs.count { it.success }
        val failure = total - success
        val avgDuration = if (total > 0) invs.sumOf { it.durationMs } / total else 0
        return AnalyzerStats(total, success, failure, avgDuration)
    }

    data class AnalyzerStats(
        val totalInvocations: Int,
        val successCount: Int,
        val failureCount: Int,
        val avgDurationMs: Long
    )
}
