package com.apex.core.tools

import com.apex.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale


/**
 * Workflow domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class WorkflowResultData(
    val id: String,
    val name: String,
    val description: String,
    val nodeCount: Int,
    val connectionCount: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastExecutionTime: Long? = null,
    val lastExecutionStatus: String? = null,
    val totalExecutions: Int = 0,
    val successfulExecutions: Int = 0,
    val failedExecutions: Int = 0
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("ID: ${id}")
        sb.appendLine("Name: ${name}")
        sb.appendLine("Description: ${description}")
        sb.appendLine("Status: ${if (enabled) "Enabled" else "Disabled"}")
        sb.appendLine("Node Count: ${nodeCount}")
        sb.appendLine("Connection Count: ${connectionCount}")
        sb.appendLine("Total Executions: ${totalExecutions}")
        sb.appendLine("Successful Executions: ${successfulExecutions}")
        sb.appendLine("Failed Executions: ${failedExecutions}")
        if (lastExecutionTime != null) {
            sb.appendLine("Last Execution Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastExecutionTime))}")
            sb.appendLine("Last Execution Status: ${lastExecutionStatus ?: "Unknown"}")
        }
        return sb.toString().trim()
    }
}

/** 工作流列表结果数�*/

@Serializable
data class WorkflowListResultData(
    val workflows: List<WorkflowResultData>,
    val totalCount: Int
) : ToolResultData() {
    override fun toString(): String {
        if (workflows.isEmpty()) {
            return "No workflows"
        }
        val sb = StringBuilder()
        sb.appendLine("Workflow List (${totalCount} total):")
        sb.appendLine()
        workflows.forEach { workflow ->
            sb.appendLine("ID: ${workflow.id}")
            sb.appendLine("Name: ${workflow.name}")
            sb.appendLine("Description: ${workflow.description}")
            sb.appendLine("Status: ${if (workflow.enabled) "Enabled" else "Disabled"}")
            sb.appendLine("Node Count: ${workflow.nodeCount}")
            sb.appendLine("Connection Count: ${workflow.connectionCount}")
            sb.appendLine("Total Executions: ${workflow.totalExecutions}")
            sb.appendLine("---")
        }
        return sb.toString().trim()
    }
    
    companion object {
        /**
         * 创建一个空的WorkflowListResultData，用于错误情�?        */
        fun empty() = WorkflowListResultData(
            workflows = emptyList(),
            totalCount = 0
        )
    }
}

/** 工作流详细信息结果数据（包含完整的节点和连接信息�*/

@Serializable
data class WorkflowDetailResultData(
    val id: String,
    val name: String,
    val description: String,
    val nodes: List<com.apex.data.model.WorkflowNode>,
    val connections: List<com.apex.data.model.WorkflowNodeConnection>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastExecutionTime: Long? = null,
    val lastExecutionStatus: String? = null,
    val totalExecutions: Int = 0,
    val successfulExecutions: Int = 0,
    val failedExecutions: Int = 0
) : ToolResultData() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Workflow Details:")
        sb.appendLine("ID: ${id}")
        sb.appendLine("Name: ${name}")
        sb.appendLine("Description: ${description}")
        sb.appendLine("Status: ${if (enabled) "Enabled" else "Disabled"}")
        sb.appendLine()

        sb.appendLine("Nodes (${nodes.size}):")
        nodes.forEach { node ->
            when (node) {
                is com.apex.data.model.TriggerNode -> {
                    sb.appendLine("  - [Trigger] ${node.name} (${node.id})")
                    sb.appendLine("    Type: ${node.triggerType}")
                    if (node.description.isNotBlank()) {
                        sb.appendLine("    Description: ${node.description}")
                    }
                }
                is com.apex.data.model.ExecuteNode -> {
                    sb.appendLine("  - [Execute] ${node.name} (${node.id})")
                    sb.appendLine("    Action: ${node.actionType}")
                    if (node.description.isNotBlank()) {
                        sb.appendLine("    Description: ${node.description}")
                    }
                }
                is com.apex.data.model.ConditionNode -> {
                    sb.appendLine("  - [Condition] ${node.name} (${node.id})")
                    sb.appendLine("    Operator: ${node.operator}")
                    if (node.description.isNotBlank()) {
                        sb.appendLine("    Description: ${node.description}")
                    }
                }
                is com.apex.data.model.LogicNode -> {
                    sb.appendLine("  - [Logic] ${node.name} (${node.id})")
                    sb.appendLine("    Operator: ${node.operator}")
                    if (node.description.isNotBlank()) {
                        sb.appendLine("    Description: ${node.description}")
                    }
                }
                is com.apex.data.model.ExtractNode -> {
                    sb.appendLine("  - [Extract] ${node.name} (${node.id})")
                    sb.appendLine("    Mode: ${node.mode}")
                    if (node.expression.isNotBlank()) {
                        sb.appendLine("    Expression: ${node.expression}")
                    }
                    if (node.description.isNotBlank()) {
                        sb.appendLine("    Description: ${node.description}")
                    }
                }
            }
        }
        sb.appendLine()

        sb.appendLine("Connections (${connections.size}):")
        connections.forEach { conn ->
            val sourceName = nodes.find { it.id == conn.sourceNodeId }?.name ?: conn.sourceNodeId
            val targetName = nodes.find { it.id == conn.targetNodeId }?.name ?: conn.targetNodeId
            sb.append("  - ${sourceName} ，的${targetName}")
            if (conn.condition != null) {
                sb.append(" (Condition: ${conn.condition})")
            }
            sb.appendLine()
        }
        sb.appendLine()

        sb.appendLine("Execution Statistics:")
        sb.appendLine("  Total Executions: ${totalExecutions}")
        sb.appendLine("  Successful Executions: ${successfulExecutions}")
        sb.appendLine("  Failed Executions: ${failedExecutions}")
        if (lastExecutionTime != null) {
            sb.appendLine("  Last Execution Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastExecutionTime))}")
            sb.appendLine("  Last Execution Status: ${lastExecutionStatus ?: "Unknown"}")
        }

        return sb.toString().trim()
    }
    
    companion object {
        /**
         * 创建一个空的WorkflowDetailResultData，用于错误情�?        */
        fun empty() = WorkflowDetailResultData(
            id = "",
            name = "",
            description = "",
            nodes = emptyList(),
            connections = emptyList(),
            enabled = false,
            createdAt = 0L,
            updatedAt = 0L,
            lastExecutionTime = null,
            lastExecutionStatus = null,
            totalExecutions = 0,
            successfulExecutions = 0,
            failedExecutions = 0
        )
    }
}

/** 对话服务启动结果数据 */

