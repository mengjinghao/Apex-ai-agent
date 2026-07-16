package com.apex.agent.core.multiagent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkflowExecution(
    val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val status: ExecutionStatus = ExecutionStatus.PENDING,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val nodeExecutions: Map<String, NodeExecution> = emptyMap(),
    val outputs: Map<String, Any> = emptyMap(),
    val error: String? = null
)

data class NodeExecution(
    val nodeId: String,
    val status: NodeExecutionStatus = NodeExecutionStatus.PENDING,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val input: Any? = null,
    val output: Any? = null,
    val error: String? = null
)

enum class ExecutionStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}

enum class NodeExecutionStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
}

class WorkflowExecutor(private val context: Context) {

    companion object {
        private const val TAG = "WorkflowExecutor"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _executions = ConcurrentHashMap<String, WorkflowExecution>()
    private val _currentExecution = MutableStateFlow<WorkflowExecution?>(null)
    val currentExecution: StateFlow<WorkflowExecution?> = _currentExecution

    val executions: List<WorkflowExecution>
        get() = _executions.values.toList()

    fun startExecution(
        workflow: Workflow,
        inputVariables: Map<String, Any> = emptyMap(),
        onProgress: ((WorkflowExecution) -> Unit)? = null
    ): WorkflowExecution {
        val executionId = UUID.randomUUID().toString()
        val execution = WorkflowExecution(
            id = executionId,
            workflowId = workflow.id,
            status = ExecutionStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            nodeExecutions = workflow.nodes.associate { node ->
                node.id to NodeExecution(node.id)
            },
            outputs = inputVariables.toMutableMap()
        )

        _executions[executionId] = execution
        _currentExecution.value = execution

        scope.launch {
            executeWorkflow(workflow, execution, onProgress)
        }

        return execution
    }

    private suspend fun executeWorkflow(
        workflow: Workflow,
        execution: WorkflowExecution,
        onProgress: ((WorkflowExecution) -> Unit)?
    ) {
        try {
            updateExecution(execution.id) { it.copy(status = ExecutionStatus.RUNNING) }
            onProgress?.invoke(execution)

            val inputNodes = workflow.getInputNodes()
            if (inputNodes.isEmpty()) {
                throw Exception("工作流没有输入节?)
            }

            val resultVariables = executeNodesRecursively(
                workflow, execution.id, inputNodes, execution.outputs, onProgress
            )

            updateExecution(execution.id) { exec ->
                exec.copy(status = ExecutionStatus.COMPLETED, completedAt = System.currentTimeMillis(), outputs = resultVariables)
            }
        } catch (e: Exception) {
            updateExecution(execution.id) { exec ->
                exec.copy(status = ExecutionStatus.FAILED, completedAt = System.currentTimeMillis(), error = e.message)
            }
        }
    }

    private suspend fun executeNodesRecursively(
        workflow: Workflow,
        executionId: String,
        currentNodes: List<WorkflowNode>,
        currentVariables: Map<String, Any>,
        onProgress: ((WorkflowExecution) -> Unit)?
    ): Map<String, Any> {
        var updatedVariables = currentVariables

        for (node in currentNodes) {
            updateNodeExecution(executionId, node.id) {
                it.copy(status = NodeExecutionStatus.RUNNING, startedAt = System.currentTimeMillis())
            }
            onProgress?.invoke(_executions[executionId] ?: continue)

            try {
                val result = when (node.type) {
                    NodeType.START -> updatedVariables
                    NodeType.AGENT -> executeAgentNode(node, updatedVariables)
                    NodeType.CONDITION -> executeConditionNode(node, updatedVariables)
                    NodeType.PARALLEL -> executeParallelNode(workflow, executionId, node, updatedVariables, onProgress)
                    NodeType.JOIN -> updatedVariables
                    NodeType.DELAY -> { delay(3000); updatedVariables }
                    NodeType.LOOP -> executeLoopNode(workflow, executionId, node, updatedVariables, onProgress)
                    NodeType.END -> return updatedVariables
                    NodeType.CUSTOM -> updatedVariables
                }

                updatedVariables = result
                updateNodeExecution(executionId, node.id) {
                    it.copy(status = NodeExecutionStatus.COMPLETED, output = result, completedAt = System.currentTimeMillis())
                }
                onProgress?.invoke(_executions[executionId] ?: continue)

                val nextNodes = workflow.getNextNodes(node.id)
                if (nextNodes.isNotEmpty()) {
                    updatedVariables = executeNodesRecursively(workflow, executionId, nextNodes, updatedVariables, onProgress)
                }
            } catch (e: Exception) {
                updateNodeExecution(executionId, node.id) {
                    it.copy(status = NodeExecutionStatus.FAILED, error = e.message, completedAt = System.currentTimeMillis())
                }
                onProgress?.invoke(_executions[executionId] ?: continue)
                throw e
            }
        }
        return updatedVariables
    }

    private suspend fun executeAgentNode(node: WorkflowNode, variables: Map<String, Any>): Map<String, Any> {
        delay(1000 + (Math.random() * 2000).toLong())
        return variables.toMutableMap().apply { put("agent_output_${node.id}", "Agent ${node.title} 完成了任?) }
    }

    private fun executeConditionNode(node: WorkflowNode, variables: Map<String, Any>): Map<String, Any> = variables

    private suspend fun executeParallelNode(
        workflow: Workflow, executionId: String, node: WorkflowNode,
        variables: Map<String, Any>, onProgress: ((WorkflowExecution) -> Unit)?
    ): Map<String, Any> {
        val nextNodes = workflow.getNextNodes(node.id)
        val results = nextNodes.map { nextNode ->
            async { executeNodesRecursively(workflow, executionId, listOf(nextNode), variables, onProgress) }
        }.awaitAll()

        val merged = mutableMapOf<String, Any>()
        results.forEach { merged.putAll(it) }
        return merged
    }

    private suspend fun executeLoopNode(
        workflow: Workflow, executionId: String, node: WorkflowNode,
        variables: Map<String, Any>, onProgress: ((WorkflowExecution) -> Unit)?
    ): Map<String, Any> {
        var currentVars = variables
        val loops = (node.config["loops"] as? Int) ?: 3

        repeat(loops) { loopIndex ->
            val nextNodes = workflow.getNextNodes(node.id)
            if (nextNodes.isNotEmpty()) {
                currentVars = executeNodesRecursively(workflow, executionId, nextNodes, currentVars.toMutableMap().apply {
                    put("loop_index", loopIndex)
                }, onProgress)
            }
        }
        return currentVars
    }

    private fun updateExecution(executionId: String, updater: (WorkflowExecution) -> WorkflowExecution) {
        _executions[executionId]?.let { execution ->
            _executions[executionId] = updater(execution)
            _currentExecution.value = _executions[executionId]
        }
    }

    private fun updateNodeExecution(executionId: String, nodeId: String, updater: (NodeExecution) -> NodeExecution) {
        _executions[executionId]?.let { execution ->
            val updatedNodeExecutions = execution.nodeExecutions.toMutableMap().apply {
                this[nodeId]?.let { nodeExec -> this[nodeId] = updater(nodeExec) }
            }
            _executions[executionId] = execution.copy(nodeExecutions = updatedNodeExecutions)
            _currentExecution.value = _executions[executionId]
        }
    }

    fun cancelExecution(executionId: String): Boolean {
        val execution = _executions[executionId] ?: return false
        updateExecution(executionId) { it.copy(status = ExecutionStatus.CANCELLED, completedAt = System.currentTimeMillis()) }
        return true
    }

    fun getExecution(executionId: String): WorkflowExecution? = _executions[executionId]
    fun clearCompletedExecutions() {
        val toRemove = _executions.filter { entry ->
            entry.value.status in listOf(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)
        }.keys
        toRemove.forEach { _executions.remove(it) }
    }
}
