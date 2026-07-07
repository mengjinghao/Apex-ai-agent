package com.apex.agent.orchestration.workflow.nodes

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.workflow.NodeExecutionResult
import com.apex.agent.orchestration.workflow.WorkflowContext
import com.apex.agent.orchestration.workflow.WorkflowNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DelayNode(
    override val nodeId: String,
    override val name: String,
    override val description: String = "",
    private val durationMillis: Long = 1000L
) : WorkflowNode {
    override val nodeType: String = "DELAY"

    override suspend fun execute(context: WorkflowContext): Flow<Result<NodeExecutionResult>> = flow {
        delay(durationMillis)
        emit(Result.Success(NodeExecutionResult(success = true, nextNodeId = null)))
    }
}
