package com.apex.agent.orchestration.workflow.nodes

import com.apex.agent.common.result.Result
import com.apex.agent.orchestration.workflow.NodeExecutionResult
import com.apex.agent.orchestration.workflow.WorkflowContext
import com.apex.agent.orchestration.workflow.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ConditionNode(
    override val nodeId: String,
    override val name: String,
    override val description: String = "",
    private val expression: String? = null
) : WorkflowNode {
    override val nodeType: String = "CONDITION"

    override suspend fun execute(context: WorkflowContext): Flow<Result<NodeExecutionResult>> = flow {
        val result = evaluate(context)
        emit(Result.Success(NodeExecutionResult(success = true, nextNodeId = null, output = mapOf("conditionResult" to result))))
    }

    private fun evaluate(context: WorkflowContext): Boolean {
        return expression?.let {
            context.variables[it]?.toString()?.toBooleanStrictOrNull() ?: true
        } ?: true
    }
}
