package com.apex.agent.orchestration.workflow

import com.apex.agent.common.result.Result
import kotlinx.coroutines.flow.Flow

interface WorkflowNode {
    val nodeId: String
    val nodeType: String
    val name: String
    val description: String
    suspend fun execute(context: WorkflowContext): Flow<Result<NodeExecutionResult>>
}

data class WorkflowContext(
    val workflowId: String,
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val executionHistory: MutableList<NodeExecutionRecord> = mutableListOf()
)

data class NodeExecutionResult(
    val success: Boolean,
    val nextNodeId: String?,
    val output: Map<String, Any> = emptyMap()
)

data class NodeExecutionRecord(
    val nodeId: String,
    val nodeType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean,
    val output: Map<String, Any> = emptyMap()
)
