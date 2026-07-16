package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WorkflowNode(
    val id: String = generateNodeId(),
    val name: String,
    val type: NodeType,
    val position: NodePosition = NodePosition(),
    val config: NodeConfig
) {
    companion object {
        private const val TAG = "WorkflowNode"

        fun generateNodeId(): String = "node_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        fun fromJson(json: String): WorkflowNode? {
            return try {
                Json.decodeFromString<WorkflowNode>(json)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse WorkflowNode from JSON", e)
                null
            }
        }
    }

    fun toJson(): String = Json.encodeToString(this)

    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (id.isBlank()) {
            errors.add("Node ID cannot be blank")
        }

        if (name.isBlank()) {
            errors.add("Node name cannot be blank")
        }

        when (type) {
            NodeType.TRIGGER -> {
                if (config.triggerConfig == null) {
                    errors.add("Trigger node requires triggerConfig")
                }
            }
            NodeType.EXECUTE -> {
                if (config.actionType.isNullOrBlank()) {
                    errors.add("Execute node requires actionType")
                }
            }
            NodeType.CONDITION -> {
                if (config.left == null || config.operator == null) {
                    errors.add("Condition node requires left/right values and operator")
                }
            }
            NodeType.LOGIC -> {
                if (config.operator == null) {
                    errors.add("Logic node requires operator (AND/OR)")
                }
            }
            NodeType.EXTRACT -> {
                if (config.mode == null) {
                    errors.add("Extract node requires mode")
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val errors: List<String>) : ValidationResult()
    }
}

@Serializable
data class NodePosition(
    val x: Float = 0f,
    val y: Float = 0f
)

@Serializable
data class NodeConfig(
    val triggerConfig: TriggerConfig? = null,
    val actionType: String? = null,
    val actionConfig: Map<String, ParameterValue>? = null,
    val left: ParameterValue? = null,
    val right: ParameterValue? = null,
    val operator: String? = null,
    val mode: ExtractMode? = null,
    val expression: String? = null,
    val source: ParameterValue? = null,
    val startIndex: Int? = null,
    val length: Int? = null,
    val defaultValue: String? = null,
    val group: Int? = null,
    val others: List<ParameterValue>? = null,
    val randomMin: Int? = null,
    val randomMax: Int? = null,
    val useFixed: Boolean? = null,
    val fixedValue: String? = null,
    val randomStringLength: Int? = null,
    val randomStringCharset: String? = null,
    val inputs: List<ParameterValue>? = null,
    val enabled: Boolean = true
)

@Serializable
enum class NodeType {
    TRIGGER,
    EXECUTE,
    CONDITION,
    LOGIC,
    EXTRACT
}

@Serializable
data class TriggerConfig(
    val triggerType: TriggerType,
    val scheduleConfig: ScheduleConfig? = null,
    val taskerConfig: TaskerConfig? = null,
    val intentConfig: IntentConfig? = null,
    val speechConfig: SpeechConfig? = null
)

@Serializable
enum class TriggerType {
    MANUAL,
    SCHEDULE,
    TASKER,
    INTENT,
    SPEECH
}

@Serializable
data class ScheduleConfig(
    val scheduleType: ScheduleType,
    val intervalMs: Long? = null,
    val specificTime: String? = null,
    val cronExpression: String? = null,
    val repeat: Boolean = true,
    val enabled: Boolean = true
)

@Serializable
enum class ScheduleType {
    INTERVAL,
    SPECIFIC_TIME,
    CRON
}

@Serializable
data class TaskerConfig(
    val command: String
)

@Serializable
data class IntentConfig(
    val action: String
)

@Serializable
data class SpeechConfig(
    val pattern: String,
    val ignoreCase: Boolean = true,
    val requireFinal: Boolean = true,
    val cooldownMs: Long = 3000
)

@Serializable
enum class ExtractMode {
    REGEX,
    JSON,
    SUB,
    CONCAT,
    RANDOM_INT,
    RANDOM_STRING
}

@Serializable
sealed class ParameterValue {
    @Serializable
    data class StaticValue(val value: String) : ParameterValue()

    @Serializable
    data class NodeReference(val nodeId: String) : ParameterValue()

    companion object {
        fun static(value: String): ParameterValue = StaticValue(value)
        fun ref(nodeId: String): ParameterValue = NodeReference(nodeId)

        fun fromAny(value: Any): ParameterValue? {
            return when (value) {
                is String -> StaticValue(value)
                is Number -> StaticValue(value.toString())
                is Boolean -> StaticValue(value.toString())
                is Map<*, *> -> {
                    val nodeId = value["nodeId"] ?: value["ref"] ?: value["refNodeId"]
                    if (nodeId != null) {
                        NodeReference(nodeId.toString())
                    } else {
                        StaticValue(value.toString())
                    }
                }
                else -> null
            }
        }
    }

    fun getStringValue(context: Map<String, Any> = emptyMap()): String {
        return when (this) {
            is StaticValue -> value
            is NodeReference -> {
                val nodeOutput = context[nodeId]
                nodeOutput?.toString() ?: ""
            }
        }
    }

    fun isReference(): Boolean = this is NodeReference
}

@Serializable
data class WorkflowConnection(
    val id: String = generateConnectionId(),
    val sourceNodeId: String,
    val targetNodeId: String,
    val condition: ConnectionCondition = ConnectionCondition.OnSuccess
) {
    companion object {
        fun generateConnectionId(): String = "conn_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        fun fromJson(json: String): WorkflowConnection? {
            return try {
                Json.decodeFromString<WorkflowConnection>(json)
            } catch (e: Exception) {
                AppLogger.e("WorkflowConnection", "Failed to parse from JSON", e)
                null
            }
        }
    }

    fun toJson(): String = Json.encodeToString(this)
}

@Serializable
enum class ConnectionCondition {
    ON_SUCCESS,
    ON_ERROR,
    TRUE,
    FALSE;

    companion object {
        fun fromString(value: String): ConnectionCondition {
            if (value == null) return ON_SUCCESS

            return when (value.lowercase()) {
                "on_success", "success", "ok" -> ON_SUCCESS
                "on_error", "error", "failed" -> ON_ERROR
                "true" -> TRUE
                "false" -> FALSE
                else -> ON_SUCCESS
            }
        }
    }
}

@Serializable
data class WorkflowDefinition(
    val id: String = generateWorkflowId(),
    val name: String,
    val description: String = "",
    val nodes: List<WorkflowNode>,
    val connections: List<WorkflowConnection>,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        private const val TAG = "WorkflowDefinition"

        fun generateWorkflowId(): String = "wf_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

        fun fromJson(json: String): WorkflowDefinition? {
            return try {
                Json.decodeFromString<WorkflowDefinition>(json)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse WorkflowDefinition from JSON", e)
                null
            }
        }
    }

    fun toJson(): String = Json.encodeToString(this)

    fun validate(): WorkflowValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (id.isBlank()) errors.add("Workflow ID cannot be blank")
        if (name.isBlank()) errors.add("Workflow name cannot be blank")
        if (nodes.isEmpty()) errors.add("Workflow must have at least one node")

        val nodeIds = nodes.map { it.id }.toSet()
        if (nodeIds.size != nodes.size) {
            errors.add("Duplicate node IDs found")
        }

        val triggerNodes = nodes.filter { it.type == NodeType.TRIGGER }
        if (triggerNodes.isEmpty()) {
            warnings.add("No trigger node found - workflow cannot be automatically triggered")
        } else if (triggerNodes.size > 1) {
            warnings.add("Multiple trigger nodes found - only one will be used as entry point")
        }

        for (connection in connections) {
            if (connection.sourceNodeId !in nodeIds) {
                errors.add("Connection ${connection.id} has invalid source node: ${connection.sourceNodeId}")
            }
            if (connection.targetNodeId !in nodeIds) {
                errors.add("Connection ${connection.id} has invalid target node: ${connection.targetNodeId}")
            }
        }

        for (node in nodes) {
            val nodeValidation = node.validate()
            if (nodeValidation is WorkflowNode.ValidationResult.Invalid) {
                errors.addAll(nodeValidation.errors.map { "Node ${node.id} (${node.name}): ${it}" })
            }
        }

        val danglingNodes = nodes.filter { node ->
            val isSource = connections.any { it.sourceNodeId == node.id }
            val isTrigger = node.type == NodeType.TRIGGER
            !isSource && !isTrigger
        }
        if (danglingNodes.isNotEmpty() && danglingNodes.size != nodes.size) {
            warnings.add("Some nodes are not connected: ${danglingNodes.map { it.name }}")
        }

        return WorkflowValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    fun getTriggerNodes(): List<WorkflowNode> = nodes.filter { it.type == NodeType.TRIGGER }

    fun getNodeById(nodeId: String): WorkflowNode? = nodes.find { it.id == nodeId }

    fun getOutgoingConnections(nodeId: String): List<WorkflowConnection> =
        connections.filter { it.sourceNodeId == nodeId }

    fun getIncomingConnections(nodeId: String): List<WorkflowConnection> =
        connections.filter { it.targetNodeId == nodeId }
}

data class WorkflowValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)
