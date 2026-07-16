package com.apex.agent.domain.workflow

import com.apex.agent.domain.interfaces.WorkflowExecutor
import com.apex.agent.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流引擎
 * 统一的DAG工作流执行器，支持拓扑排序、条件分支、并行执行、循环、延迟等节点类型
 *
 * 特性：
 * - DAG拓扑排序与逐节点遍历
 * - 条件分支：根据边上的condition表达式决定走向
 * - 并行执行：PARALLEL节点触发多路同时执行
 * - 循环执行：LOOP节点按配置次数重复执行子路径
 * - 延迟执行：DELAY节点等待指定时长
 * - 执行进度追踪与错误处理
 * - Job级别的取消/暂停/恢复控制
 */
class WorkflowEngine : WorkflowExecutor {

    private val registeredWorkflows = ConcurrentHashMap<String, WorkflowDefinition>()
    private val activeExecutions = ConcurrentHashMap<String, WorkflowExecution>()
    private val executionJobs = ConcurrentHashMap<String, Job>()
    private val results = ConcurrentHashMap<String, WorkflowResult>()
    private val pausedExecutions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val _executionHistory = MutableStateFlow<List<WorkflowExecution>>(emptyList())
    val executionHistory: StateFlow<List<WorkflowExecution>> = _executionHistory.asStateFlow()

    override fun register(workflow: WorkflowDefinition) {
        registeredWorkflows[workflow.id] = workflow
    }

    override fun unregister(workflowId: String): Boolean {
        return registeredWorkflows.remove(workflowId) != null
    }

    override fun getRegistered(): List<WorkflowDefinition> {
        return registeredWorkflows.values.toList()
    }

    /**
     * 执行工作流
     * 1. 校验DAG完整性
     * 2. 拓扑排序
     * 3. 从START节点开始遍历
     * 4. 按节点类型分派执行
     * 5. 收集执行结果
     */
    override suspend fun execute(workflow: WorkflowDefinition): WorkflowResult {
        val startTime = System.currentTimeMillis()
        val executionId = workflow.id
        val execution = WorkflowExecution(
            workflowId = executionId,
            state = ExecutionState.RUNNING,
            startedAt = startTime
        )
        activeExecutions[executionId] = execution
        appendHistory(execution)

        val nodeResults = ConcurrentHashMap<String, NodeResult>()
        val context = mutableMapOf<String, String>()
        var finalError: String? = null

        try {
            val sorted = topologicalSort(workflow.nodes, workflow.edges)
            val startNode = findStartNode(workflow.nodes) ?: sorted.firstOrNull()
            if (startNode == null) {
                val err = "工作流中未找到任何节点"
                return completeWithError(executionId, nodeResults, err, startTime)
            }

            val visited = mutableSetOf<String>()

            suspend fun traverse(nodeId: String) {
                if (!isActive()) return
                if (nodeId in visited) return
                visited.add(nodeId)

                checkPaused(executionId)

                val node = workflow.nodes.find { it.id == nodeId } ?: return
                if (node.type == NodeType.END) {
                    nodeResults[node.id] = NodeResult(nodeId = node.id, success = true, output = "终止节点", executionTimeMs = System.currentTimeMillis() - startTime)
                    updateProgress(executionId, workflow.nodes.size, visited.size)
                    return
                }

                val nodeStart = System.currentTimeMillis()
                val nodeResult = executeNode(node, context)
                nodeResult.executionTimeMs.let { System.currentTimeMillis() - nodeStart }
                nodeResults[node.id] = nodeResult.copy(executionTimeMs = System.currentTimeMillis() - nodeStart)

                if (!nodeResult.success) {
                    finalError = nodeResult.error ?: "节点 ${node.label} (${node.id}) 执行失败"
                    return
                }

                if (node.type == NodeType.PARALLEL) {
                    val branches = getOutgoingEdges(nodeId, workflow.edges)
                    val deferredResults = branches.map { edge ->
                        CoroutineScope(currentCoroutineContext()).async {
                            val branchResults = mutableMapOf<String, NodeResult>()
                            traverseBranch(edge.targetNodeId, workflow, context, branchResults, visited.toMutableSet(), executionId, startTime)
                            branchResults
                        }
                    }
                    deferredResults.awaitAll().forEach { nodeResults.putAll(it) }
                    return
                }

                if (node.type == NodeType.LOOP) {
                    val loopCount = (node.config["count"]?.toIntOrNull()) ?: 3
                    val loopBodyEdges = getOutgoingEdges(nodeId, workflow.edges)
                    if (loopBodyEdges.isNotEmpty()) {
                        repeat(loopCount) { i ->
                            if (!isActive()) return@repeat
                            context["loop_index"] = i.toString()
                            context["loop_count"] = loopCount.toString()
                            for (edge in loopBodyEdges) {
                                traverseBranch(edge.targetNodeId, workflow, context, nodeResults, visited.toMutableSet(), executionId, startTime)
                            }
                        }
                    }
                    updateProgress(executionId, workflow.nodes.size, visited.size)
                    return
                }

                val outgoing = getOutgoingEdges(nodeId, workflow.edges)
                if (outgoing.isEmpty()) return

                if (node.type == NodeType.CONDITION) {
                    for (edge in outgoing) {
                        val condition = edge.condition ?: "true"
                        if (evaluateCondition(condition, context)) {
                            traverse(edge.targetNodeId)
                            return
                        }
                    }
                    return
                }

                for (edge in outgoing) {
                    context["last_edge"] = edge.label
                    traverse(edge.targetNodeId)
                }
            }

            traverse(startNode.id)

        } catch (e: CancellationException) {
            val executionState = activeExecutions[executionId]
            if (executionState?.state == ExecutionState.PAUSED) {
                throw e
            }
            nodeResults["__cancel__"] = NodeResult(
                nodeId = "__cancel__",
                success = false,
                error = "工作流被取消",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            val exec = activeExecutions[executionId]
            if (exec != null && exec.state != ExecutionState.PAUSED) {
                val result = WorkflowResult(
                    workflowId = executionId,
                    success = false,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    nodeResults = nodeResults.toMap(),
                    error = "工作流被取消"
                )
                results[executionId] = result
                activeExecutions[executionId] = exec.copy(
                    state = ExecutionState.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    progress = 1f,
                    error = "工作流被取消"
                )
                appendHistory(activeExecutions[executionId]!!)
            }
            return results[executionId] ?: WorkflowResult(
                workflowId = executionId, success = false,
                executionTimeMs = System.currentTimeMillis() - startTime,
                nodeResults = nodeResults.toMap(), error = "工作流被取消"
            )
        } catch (e: Exception) {
            finalError = e.message ?: "未知执行错误"
        }

        val exec = activeExecutions[executionId] ?: return WorkflowResult(
            workflowId = executionId, success = false,
            executionTimeMs = System.currentTimeMillis() - startTime,
            nodeResults = nodeResults.toMap(), error = finalError
        )

        val success = finalError == null
        val finalState = if (success) ExecutionState.COMPLETED else ExecutionState.FAILED
        val result = WorkflowResult(
            workflowId = executionId,
            success = success,
            executionTimeMs = System.currentTimeMillis() - startTime,
            nodeResults = nodeResults.toMap(),
            error = finalError
        )
        results[executionId] = result
        activeExecutions[executionId] = exec.copy(
            state = finalState,
            completedAt = System.currentTimeMillis(),
            progress = 1f,
            error = finalError,
            results = nodeResults.entries.associate { it.key to (it.value.output ?: "") }
        )
        appendHistory(activeExecutions[executionId]!!)
        return result
    }

    /**
     * 递归遍历分支（用于PARALLEL和LOOP的子路径）
     */
    private suspend fun traverseBranch(
        nodeId: String,
        workflow: WorkflowDefinition,
        context: MutableMap<String, String>,
        nodeResults: MutableMap<String, NodeResult>,
        visited: MutableSet<String>,
        executionId: String,
        startTime: Long
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)

        val node = workflow.nodes.find { it.id == nodeId } ?: return
        if (node.type == NodeType.END) return

        val nodeStart = System.currentTimeMillis()
        val nodeResult = executeNode(node, context)
        nodeResults[node.id] = nodeResult.copy(executionTimeMs = System.currentTimeMillis() - nodeStart)

        if (!nodeResult.success) return

        if (node.type == NodeType.CONDITION) {
            val outgoing = getOutgoingEdges(nodeId, workflow.edges)
            for (edge in outgoing) {
                val condition = edge.condition ?: "true"
                if (evaluateCondition(condition, context)) {
                    traverseBranch(edge.targetNodeId, workflow, context, nodeResults, visited, executionId, startTime)
                    return
                }
            }
            return
        }

        val outgoing = getOutgoingEdges(nodeId, workflow.edges)
        for (edge in outgoing) {
            traverseBranch(edge.targetNodeId, workflow, context, nodeResults, visited, executionId, startTime)
        }
    }

    /**
     * 执行单个节点
     */
    private suspend fun executeNode(node: WorkflowNode, context: MutableMap<String, String>): NodeResult {
        return when (node.type) {
            NodeType.START -> {
                context.putAll(node.config)
                NodeResult(nodeId = node.id, success = true, output = "开始节点")
            }
            NodeType.END -> {
                NodeResult(nodeId = node.id, success = true, output = "结束节点")
            }
            NodeType.ACTION -> {
                val action = node.config["action"] ?: "noop"
                val input = node.config["input"] ?: context[node.id] ?: ""
                context[node.id] = simulateAction(action, input)
                NodeResult(nodeId = node.id, success = true, output = context[node.id])
            }
            NodeType.CONDITION -> {
                val expr = node.config["expression"] ?: "true"
                val result = evaluateCondition(expr, context)
                context["${node.id}_result"] = result.toString()
                NodeResult(nodeId = node.id, success = true, output = result.toString())
            }
            NodeType.PARALLEL -> {
                NodeResult(nodeId = node.id, success = true, output = "并行分支已触发")
            }
            NodeType.LOOP -> {
                NodeResult(nodeId = node.id, success = true, output = "循环已触发")
            }
            NodeType.DELAY -> {
                val durationMs = node.config["duration"]?.toLongOrNull() ?: 1000L
                delay(durationMs)
                NodeResult(nodeId = node.id, success = true, output = "延迟 ${durationMs}ms")
            }
            NodeType.SCRIPT -> {
                val script = node.config["script"] ?: ""
                context[node.id] = "脚本结果: ${script.take(50)}"
                NodeResult(nodeId = node.id, success = true, output = context[node.id])
            }
            NodeType.SUB_WORKFLOW -> {
                val subId = node.config["workflowId"] ?: ""
                val subWf = registeredWorkflows[subId]
                if (subWf != null) {
                    val subResult = execute(subWf)
                    context["${node.id}_sub_success"] = subResult.success.toString()
                    context["${node.id}_sub_error"] = subResult.error ?: ""
                    NodeResult(nodeId = node.id, success = subResult.success, output = subResult.nodeResults.values.joinToString { it.output ?: "" })
                } else {
                    NodeResult(nodeId = node.id, success = false, error = "子工作流 $subId 未注册")
                }
            }
            NodeType.NOTIFICATION -> {
                val message = node.config["message"] ?: ""
                NodeResult(nodeId = node.id, success = true, output = "通知: $message")
            }
        }
    }

    /**
     * 模拟动作执行
     */
    private fun simulateAction(action: String, input: String): String {
        return when {
            action == "echo" -> input
            action.startsWith("transform:") -> {
                val transformer = action.removePrefix("transform:")
                "$transformer($input)"
            }
            else -> "执行动作: $action，输入: $input"
        }
    }

    /**
     * 计算条件表达式
     * 支持格式：
     * - "${key} == value"
     * - "${key} != value"
     * - "true" / "false" 字面量
     * - "${key}" 非空即真
     */
    private fun evaluateCondition(condition: String, context: Map<String, String>): Boolean {
        val trimmed = condition.trim()
        if (trimmed == "true") return true
        if (trimmed == "false") return false

        val eqMatch = Regex("""\$\{(\w+)}\s*==\s*(.+)""").find(trimmed)
        if (eqMatch != null) {
            val key = eqMatch.groupValues[1]
            val expected = eqMatch.groupValues[2].trim().removeSurrounding("\"")
            return context[key] == expected
        }

        val neqMatch = Regex("""\$\{(\w+)}\s*!=\s*(.+)""").find(trimmed)
        if (neqMatch != null) {
            val key = neqMatch.groupValues[1]
            val expected = neqMatch.groupValues[2].trim().removeSurrounding("\"")
            return context[key] != expected
        }

        val refMatch = Regex("""\$\{(\w+)}""").find(trimmed)
        if (refMatch != null && trimmed == refMatch.value) {
            return context[refMatch.groupValues[1]]?.isNotBlank() == true
        }

        return trimmed.toBooleanStrictOrNull() ?: false
    }

    /**
     * 拓扑排序（Kahn算法）
     * 按依赖关系对节点排序，确保父节点先于子节点执行
     */
    private fun topologicalSort(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>): List<WorkflowNode> {
        val inDegree = mutableMapOf<String, Int>()
        val adj = mutableMapOf<String, MutableList<String>>()

        nodes.forEach { node ->
            inDegree[node.id] = 0
            adj[node.id] = mutableListOf()
        }

        edges.forEach { edge ->
            adj[edge.sourceNodeId]?.add(edge.targetNodeId)
            inDegree[edge.targetNodeId] = (inDegree[edge.targetNodeId] ?: 0) + 1
        }

        val queue = ArrayDeque<String>()
        inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

        val sorted = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted.add(current)
            adj[current]?.forEach { neighbor ->
                inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                if (inDegree[neighbor] == 0) {
                    queue.add(neighbor)
                }
            }
        }

        val nodeMap = nodes.associateBy { it.id }
        return sorted.mapNotNull { nodeMap[it] }
    }

    /**
     * 查找START类型节点
     */
    private fun findStartNode(nodes: List<WorkflowNode>): WorkflowNode? {
        return nodes.find { it.type == NodeType.START }
    }

    /**
     * 获取指定节点的所有出边
     */
    private fun getOutgoingEdges(nodeId: String, edges: List<WorkflowEdge>): List<WorkflowEdge> {
        return edges.filter { it.sourceNodeId == nodeId }
    }

    override suspend fun cancel(workflowId: String): Boolean {
        val job = executionJobs[workflowId] ?: return false
        val exec = activeExecutions[workflowId] ?: return false
        activeExecutions[workflowId] = exec.copy(
            state = ExecutionState.CANCELLED,
            completedAt = System.currentTimeMillis(),
            progress = 1f,
            error = "用户取消"
        )
        appendHistory(activeExecutions[workflowId]!!)
        job.cancel(CancellationException("工作流 $workflowId 被用户取消"))
        executionJobs.remove(workflowId)
        return true
    }

    override suspend fun pause(workflowId: String): Boolean {
        val exec = activeExecutions[workflowId] ?: return false
        if (exec.state != ExecutionState.RUNNING) return false
        val pauseSignal = CompletableDeferred<Unit>()
        pausedExecutions[workflowId] = pauseSignal
        activeExecutions[workflowId] = exec.copy(state = ExecutionState.PAUSED)
        appendHistory(activeExecutions[workflowId]!!)
        return true
    }

    override suspend fun resume(workflowId: String): Boolean {
        val pauseSignal = pausedExecutions.remove(workflowId) ?: return false
        val exec = activeExecutions[workflowId] ?: return false
        activeExecutions[workflowId] = exec.copy(state = ExecutionState.RUNNING)
        appendHistory(activeExecutions[workflowId]!!)
        pauseSignal.complete(Unit)
        return true
    }

    override suspend fun getStatus(workflowId: String): WorkflowExecution? {
        return activeExecutions[workflowId]
    }

    override suspend fun listExecutions(): List<WorkflowExecution> {
        return _executionHistory.value
    }

    /**
     * 获取工作流执行结果
     */
    fun getResult(workflowId: String): WorkflowResult? = results[workflowId]

    /**
     * 清除指定工作流的执行记录
     */
    fun clearExecution(workflowId: String) {
        activeExecutions.remove(workflowId)
        executionJobs.remove(workflowId)
        results.remove(workflowId)
        pausedExecutions.remove(workflowId)
        _executionHistory.value = _executionHistory.value.filter { it.workflowId != workflowId }
    }

    /**
     * 清除所有执行记录
     */
    fun clearAll() {
        executionJobs.values.forEach { it.cancel(CancellationException("引擎重置")) }
        activeExecutions.clear()
        executionJobs.clear()
        results.clear()
        pausedExecutions.clear()
        _executionHistory.value = emptyList()
    }

    private fun isActive(): Boolean = true

    private suspend fun checkPaused(executionId: String) {
        val pauseSignal = pausedExecutions[executionId]
        if (pauseSignal != null) {
            try {
                withTimeout(Long.MAX_VALUE) { pauseSignal.await() }
            } catch (_: CancellationException) {
                throw CancellationException("工作流 $executionId 在暂停期间被取消")
            }
        }
    }

    private fun updateProgress(executionId: String, total: Int, completed: Int) {
        val exec = activeExecutions[executionId] ?: return
        val progress = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f
        activeExecutions[executionId] = exec.copy(progress = progress)
    }

    private fun completeWithError(
        executionId: String,
        nodeResults: ConcurrentHashMap<String, NodeResult>,
        error: String,
        startTime: Long
    ): WorkflowResult {
        val result = WorkflowResult(
            workflowId = executionId, success = false,
            executionTimeMs = System.currentTimeMillis() - startTime,
            nodeResults = nodeResults.toMap(), error = error
        )
        results[executionId] = result
        val exec = activeExecutions[executionId] ?: return result
        activeExecutions[executionId] = exec.copy(
            state = ExecutionState.FAILED, completedAt = System.currentTimeMillis(),
            progress = 1f, error = error
        )
        appendHistory(activeExecutions[executionId]!!)
        return result
    }

    private fun appendHistory(execution: WorkflowExecution) {
        val current = _executionHistory.value.toMutableList()
        val idx = current.indexOfFirst { it.workflowId == execution.workflowId }
        if (idx >= 0) {
            current[idx] = execution
        } else {
            current.add(execution)
        }
        _executionHistory.value = current
    }
}
