package com.apex.agent.burstmode.execution

import com.apex.agent.domain.model.BurstTask
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务依赖图。
 *
 * 描述多个任务之间的依赖关系，支持：
 * - DAG（有向无环图）拓扑排序
 * - 并行执行无依赖任务
 * - 依赖失败传播（下游任务自动跳过）
 * - 循环依赖检测
 *
 * # 使用示例
 *
 * ```
 * val graph = TaskDependencyGraph()
 *
 * // 添加任务节点
 * graph.addNode("t1", task1)
 * graph.addNode("t2", task2)
 * graph.addNode("t3", task3)
 *
 * // 添加依赖：t2 依赖 t1，t3 依赖 t1 和 t2
 * graph.addDependency("t2", "t1")
 * graph.addDependency("t3", "t1")
 * graph.addDependency("t3", "t2")
 *
 * // 拓扑排序
 * val order = graph.topologicalSort()  // [t1, t2, t3]
 *
 * // 获取可并行执行的层级
 * val layers = graph.getExecutionLayers()  // [[t1], [t2], [t3]]
 * ```
 */
class TaskDependencyGraph {

    /** 节点：taskId -> BurstTask */
    private val nodes = ConcurrentHashMap<String, BurstTask>()

    /** 依赖关系：taskId -> 依赖的 taskId 集合 */
    private val dependencies = ConcurrentHashMap<String, MutableSet<String>>()

    /** 反向依赖：taskId -> 依赖此 taskId 的 taskId 集合 */
    private val dependents = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * 添加任务节点。
     *
     * @param taskId 任务 ID（必须唯一）
     * @param task 任务实例
     * @return true 添加成功，false 已存在同 ID
     */
    fun addNode(taskId: String, task: BurstTask): Boolean {
        return nodes.putIfAbsent(taskId, task) == null
    }

    /**
     * 添加依赖关系。
     *
     * @param taskId 依赖方任务 ID
     * @param dependsOn 被依赖的任务 ID
     * @return true 添加成功，false 检测到循环依赖或已存在
     * @throws IllegalArgumentException 如果任一任务不存在
     */
    fun addDependency(taskId: String, dependsOn: String): Boolean {
        require(nodes.containsKey(taskId)) { "Task $taskId not found in graph" }
        require(nodes.containsKey(dependsOn)) { "Task $dependsOn not found in graph" }
        if (taskId == dependsOn) return false  // 不能依赖自己

        // 检查循环依赖
        if (wouldCreateCycle(taskId, dependsOn)) {
            return false
        }

        val deps = dependencies.computeIfAbsent(taskId) { java.util.concurrent.CopyOnWriteArraySet() }
        val result = deps.add(dependsOn)
        if (result) {
            dependents.computeIfAbsent(dependsOn) { java.util.concurrent.CopyOnWriteArraySet() }.add(taskId)
        }
        return result
    }

    /**
     * 移除依赖关系。
     */
    fun removeDependency(taskId: String, dependsOn: String): Boolean {
        val deps = dependencies[taskId] ?: return false
        val removed = deps.remove(dependsOn)
        if (removed) {
            dependents[dependsOn]?.remove(taskId)
        }
        return removed
    }

    /**
     * 移除节点（同时移除所有相关依赖）。
     */
    fun removeNode(taskId: String): BurstTask? {
        dependencies.remove(taskId)
        dependents.remove(taskId)
        for (deps in dependencies.values) deps.remove(taskId)
        for (deps in dependents.values) deps.remove(taskId)
        return nodes.remove(taskId)
    }

    fun getDependencies(taskId: String): Set<String> =
        dependencies[taskId]?.toSet() ?: emptySet()

    fun getDependents(taskId: String): Set<String> =
        dependents[taskId]?.toSet() ?: emptySet()

    fun getAllNodes(): Map<String, BurstTask> = nodes.toMap()

    fun size(): Int = nodes.size

    /**
     * 拓扑排序（Kahn 算法）。
     *
     * @return 拓扑排序后的任务 ID 列表。环中任务不包含在结果中。
     */
    fun topologicalSort(): List<String> {
        val inDegree = HashMap<String, Int>()
        for (taskId in nodes.keys) {
            inDegree[taskId] = dependencies[taskId]?.size ?: 0
        }

        val queue = java.util.ArrayDeque<String>()
        for ((taskId, degree) in inDegree) {
            if (degree == 0) queue.add(taskId)
        }

        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            result.add(current)
            dependents[current]?.forEach { dependent ->
                val newDegree = (inDegree[dependent] ?: 0) - 1
                inDegree[dependent] = newDegree
                if (newDegree == 0) queue.add(dependent)
            }
        }

        return result
    }

    /**
     * 获取可并行执行的层级。
     *
     * 将任务按依赖关系分层，同层任务可并行执行，
     * 必须等上一层全部完成后才能执行下一层。
     *
     * @return 任务 ID 列表的列表，每个内层列表代表一个可并行的层级
     */
    fun getExecutionLayers(): List<List<String>> {
        val inDegree = HashMap<String, Int>()
        for (taskId in nodes.keys) {
            inDegree[taskId] = dependencies[taskId]?.size ?: 0
        }

        val layers = mutableListOf<List<String>>()
        val remaining = inDegree.toMutableMap()

        while (remaining.isNotEmpty()) {
            val currentLayer = remaining.filter { it.value == 0 }.keys.toList()
            if (currentLayer.isEmpty()) break  // 剩余都在环中

            layers.add(currentLayer)

            for (taskId in currentLayer) {
                remaining.remove(taskId)
                dependents[taskId]?.forEach { dependent ->
                    if (dependent in remaining) {
                        remaining[dependent] = (remaining[dependent] ?: 0) - 1
                    }
                }
            }
        }

        return layers
    }

    /**
     * 检测是否存在循环依赖。
     */
    fun hasCycle(): Boolean = topologicalSort().size != nodes.size

    private fun wouldCreateCycle(taskId: String, dependsOn: String): Boolean {
        return canReach(dependsOn, taskId, mutableSetOf())
    }

    private fun canReach(source: String, target: String, visited: MutableSet<String>): Boolean {
        if (source == target) return true
        if (source in visited) return false
        visited.add(source)
        val deps = dependencies[source] ?: return false
        for (dep in deps) {
            if (canReach(dep, target, visited)) return true
        }
        return false
    }

    fun clear() {
        nodes.clear()
        dependencies.clear()
        dependents.clear()
    }
}

/**
 * 依赖图执行结果。
 */
data class DependencyExecutionResult(
    val taskId: String,
    val success: Boolean,
    val skipped: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 依赖图执行策略。
 */
enum class DependencyExecutionStrategy {
    /** 依赖失败时跳过所有下游任务（默认） */
    SKIP_ON_FAILURE,
    /** 依赖失败时仍尝试执行下游任务 */
    CONTINUE_ON_FAILURE,
    /** 依赖失败时立即中止整个图执行 */
    ABORT_ON_FAILURE
}
