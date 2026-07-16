package com.apex.agent.orchestration.workflow.nodes

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.workflow.NodeExecutionResult
import com.apex.agent.orchestration.workflow.WorkflowContext
import com.apex.agent.orchestration.workflow.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CustomNode(
    override val nodeId: String,
    override val name: String,
    override val description: String = "",
    private val config: Map<String, Any> = emptyMap()
) : WorkflowNode {
    override val nodeType: String = "CUSTOM"

    override suspend fun execute(context: WorkflowContext): Flow<Result<NodeExecutionResult>> = flow {
        emit(Result.Success(NodeExecutionResult(success = true, nextNodeId = null, output = config)))
    }
}
