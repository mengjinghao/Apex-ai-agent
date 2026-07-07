package com.apex.agent.orchestration.workflow

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.workflow.nodes.AgentNode
import com.apex.agent.orchestration.workflow.nodes.ConditionNode
import com.apex.agent.orchestration.workflow.nodes.CustomNode
import com.apex.agent.orchestration.workflow.nodes.DelayNode
import com.apex.agent.orchestration.workflow.nodes.EndNode
import com.apex.agent.orchestration.workflow.nodes.JoinNode
import com.apex.agent.orchestration.workflow.nodes.LoopNode
import com.apex.agent.orchestration.workflow.nodes.ParallelNode
import com.apex.agent.orchestration.workflow.nodes.StartNode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

@Singleton
class WorkflowExecutor @Inject constructor() {

    fun execute(workflow: Workflow): Flow<Result<NodeExecutionResult>> = flow {
        val context = WorkflowContext(workflowId = workflow.id, variables = workflow.variables)
        val startNode = workflow.getInputNodes().firstOrNull() ?: workflow.nodes.firstOrNull()
        var currentNodeId: String? = startNode?.id

        while (currentNodeId != null) {
            val nodeEntity = workflow.getNode(currentNodeId)
            if (nodeEntity == null) {
                emit(Result.Failure(IllegalStateException("Node not found: ${currentNodeId}")))
                break
            }

            val node = createExecutableNode(nodeEntity)
            val results = node.execute(context)

            var nextId: String? = null
            var success = true
            results.collect { result ->
                when (result) {
                    is Result.Success -> {
                        nextId = result.data.nextNodeId
                        success = result.data.success
                        context.executionHistory.add(
                            NodeExecutionRecord(
                                nodeId = node.nodeId,
                                nodeType = node.nodeType,
                                success = result.data.success,
                                output = result.data.output
                            )
                        )
                        emit(result)
                    }
                    is Result.Failure -> {
                        success = false
                        emit(result)
                    }
                }
            }

            if (!success) break
            currentNodeId = nextId ?: resolveNextNodeId(workflow, nodeEntity.id)
        }
    }

    private fun createExecutableNode(entity: WorkflowNodeEntity): WorkflowNode {
        return when (entity.type) {
            NodeType.START -> StartNode(entity.id, entity.title, entity.description)
            NodeType.AGENT -> AgentNode(entity.id, entity.title, entity.description, entity.agentId)
            NodeType.CONDITION -> ConditionNode(entity.id, entity.title, entity.description, entity.config["expression"] as? String)
            NodeType.PARALLEL -> ParallelNode(entity.id, entity.title, entity.description)
            NodeType.JOIN -> JoinNode(entity.id, entity.title, entity.description)
            NodeType.DELAY -> DelayNode(entity.id, entity.title, entity.description, (entity.config["duration"] as? Number)?.toLong() ?: 1000L)
            NodeType.LOOP -> LoopNode(entity.id, entity.title, entity.description, entity.config)
            NodeType.END -> EndNode(entity.id, entity.title, entity.description)
            NodeType.CUSTOM -> CustomNode(entity.id, entity.title, entity.description, entity.config)
        }
    }

    private fun resolveNextNodeId(workflow: Workflow, currentNodeId: String): String? {
        return workflow.getNextNodes(currentNodeId).firstOrNull()?.id
    }

    private val workflowStore = ConcurrentHashMap<String, Workflow>()

    fun registerWorkflow(workflow: Workflow) {
        workflowStore[workflow.id] = workflow
    }

    fun unregisterWorkflow(workflowId: String) {
        workflowStore.remove(workflowId)
    }

    /**
     * 通过工作流ID执行工作流
     */
    fun executeById(workflowId: String): Flow<Result<NodeExecutionResult>> = flow {
        val workflow = workflowStore[workflowId]
        if (workflow == null) {
            emit(Result.Failure(IllegalArgumentException("Workflow not found: $workflowId")))
            return@flow
        }
        emitAll(execute(workflow))
    }

    /**
     * 列出所有已注册的可执行工作流
     */
    fun listWorkflows(): Result<List<Workflow>> {
        return Result.Success(workflowStore.values.toList())
    }
}
