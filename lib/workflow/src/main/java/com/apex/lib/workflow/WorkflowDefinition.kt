package com.apex.lib.workflow

import kotlinx.serialization.Serializable

/**
 * 工作流节点类型。
 */
@Serializable
sealed class WorkflowNodeSpec {
    abstract val id: String
    abstract val displayName: String

    @Serializable
    data class LlmCall(
        override val id: String,
        override val displayName: String,
        val promptTemplate: String,
        val modelProvider: String = "",
        val modelName: String = ""
    ) : WorkflowNodeSpec()

    @Serializable
    data class ToolCall(
        override val id: String,
        override val displayName: String,
        val toolName: String,
        val argumentsJson: String = "{}"
    ) : WorkflowNodeSpec()

    @Serializable
    data class Condition(
        override val id: String,
        override val displayName: String,
        val expression: String,
        val trueBranch: String,
        val falseBranch: String
    ) : WorkflowNodeSpec()

    @Serializable
    data class Loop(
        override val id: String,
        override val displayName: String,
        val bodyNodeId: String,
        val iterations: Int = 10
    ) : WorkflowNodeSpec()

    @Serializable
    data class Parallel(
        override val id: String,
        override val displayName: String,
        val branchNodeIds: List<String>
    ) : WorkflowNodeSpec()

    @Serializable
    data class HttpRequest(
        override val id: String,
        override val displayName: String,
        val url: String,
        val method: String = "GET",
        val headersJson: String = "{}",
        val bodyJson: String = "{}"
    ) : WorkflowNodeSpec()

    @Serializable
    data class Terminal(
        override val id: String,
        override val displayName: String,
        val command: String
    ) : WorkflowNodeSpec()

    @Serializable
    data class Code(
        override val id: String,
        override val displayName: String,
        val language: String,
        val source: String
    ) : WorkflowNodeSpec()
}

@Serializable
data class WorkflowEdge(
    val from: String,
    val to: String,
    val condition: String? = null
)

@Serializable
data class WorkflowDefinition(
    val id: String,
    val displayName: String,
    val nodes: List<WorkflowNodeSpec>,
    val edges: List<WorkflowEdge>,
    val entryNodeId: String
)
