package com.apex.agent.core.workflow.enhanced.model

import kotlinx.serialization.Serializable

/**
 * 增强版工作流连接（边）
 */
@Serializable
data class EnhancedConnection(
    val id: String = generateConnectionId(),
    val sourceNodeId: String,
    val targetNodeId: String,
    val condition: ConnectionConditionDef = ConnectionConditionDef.ON_SUCCESS,
    val label: String = "",
    val priority: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun generateConnectionId(): String =
            "econn_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
}

/**
 * 连接条件 - 决定何时走这条边
 */
@Serializable
enum class ConnectionConditionDef {
    /** 源节点成功时走 */
    ON_SUCCESS,
    /** 源节点失败时走 */
    ON_ERROR,
    /** 条件为真时走 */
    TRUE,
    /** 条件为假时走 */
    FALSE,
    /** 无条件走（用于并行汇合后） */
    ALWAYS;

    companion object {
        fun fromString(value: String?): ConnectionConditionDef {
            if (value == null) return ON_SUCCESS
            return when (value.lowercase()) {
                "on_success", "success", "ok" -> ON_SUCCESS
                "on_error", "error", "failed" -> ON_ERROR
                "true" -> TRUE
                "false" -> FALSE
                "always", "any" -> ALWAYS
                else -> ON_SUCCESS
            }
        }
    }
}
