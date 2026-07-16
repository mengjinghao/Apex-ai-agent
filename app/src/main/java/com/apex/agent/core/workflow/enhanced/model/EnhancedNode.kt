package com.apex.agent.core.workflow.enhanced.model

import kotlinx.serialization.Serializable

/**
 * 增强版工作流节点类型枚举
 * 融合 LangGraph / Temporal / Airflow / Dify / n8n / PocketFlow 的节点能力
 */
@Serializable
enum class EnhancedNodeType {
    /** 触发器节点 - 工作流入口 */
    TRIGGER,

    /** 执行节点 - 调用工具/AI/HTTP */
    EXECUTE,

    /** 条件判断节点 - if/else 分支 */
    CONDITION,

    /** 逻辑组合节点 - AND/OR/NOT */
    LOGIC,

    /** 数据提取节点 - regex/json/substring */
    EXTRACT,

    /** 并行扇出节点 - 将输入列表分裂为 N 个并行分支 */
    FAN_OUT,

    /** 并行汇合节点 - barrier 等待所有分支完成 */
    FAN_IN,

    /** 循环节点 - count/forEach/while/mapReduce */
    LOOP,

    /** 子工作流节点 - 嵌套调用其他工作流 */
    SUB_WORKFLOW,

    /** 人工审批节点 - 暂停等待人类输入 */
    HUMAN_INPUT,

    /** Saga 事务节点 - 带补偿动作的执行 */
    SAGA,

    /** 延时节点 */
    DELAY,

    /** 结束节点 */
    END
}

/**
 * 增强版工作流节点
 */
@Serializable
data class EnhancedNode(
    val id: String = generateNodeId(),
    val name: String,
    val type: EnhancedNodeType,
    val config: EnhancedNodeConfig = EnhancedNodeConfig(),
    val position: NodePositionDef = NodePositionDef(),
    val retryPolicy: RetryPolicyDef? = null,
    val timeoutMs: Long? = null,
    val enabled: Boolean = true,
    val description: String = ""
) {
    companion object {
        fun generateNodeId(): String =
            "enode_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (id.isBlank()) errors.add("节点 ID 不能为空")
        if (name.isBlank()) errors.add("节点名称不能为空")

        when (type) {
            EnhancedNodeType.TRIGGER -> {
                if (config.triggerConfig == null) errors.add("触发节点需要 triggerConfig")
            }
            EnhancedNodeType.EXECUTE -> {
                if (config.actionType.isNullOrBlank()) errors.add("执行节点需要 actionType")
            }
            EnhancedNodeType.CONDITION -> {
                if (config.left == null || config.operator == null) {
                    errors.add("条件节点需要 left/right 和 operator")
                }
            }
            EnhancedNodeType.LOGIC -> {
                if (config.operator == null) errors.add("逻辑节点需要 operator (AND/OR/NOT)")
            }
            EnhancedNodeType.EXTRACT -> {
                if (config.extractMode == null) errors.add("提取节点需要 extractMode")
            }
            EnhancedNodeType.FAN_OUT -> {
                if (config.fanOutSpec == null) errors.add("FAN_OUT 节点需要 fanOutSpec")
            }
            EnhancedNodeType.FAN_IN -> {
                if (config.fanInSpec == null) errors.add("FAN_IN 节点需要 fanInSpec")
            }
            EnhancedNodeType.LOOP -> {
                if (config.loopSpec == null) errors.add("循环节点需要 loopSpec")
            }
            EnhancedNodeType.SUB_WORKFLOW -> {
                if (config.subWorkflowConfig == null) errors.add("子工作流节点需要 subWorkflowConfig")
            }
            EnhancedNodeType.HUMAN_INPUT -> {
                if (config.humanInputConfig == null) errors.add("人工输入节点需要 humanInputConfig")
            }
            EnhancedNodeType.SAGA -> {
                if (config.actionType.isNullOrBlank()) errors.add("Saga 节点需要 actionType")
                if (config.compensateActionType.isNullOrBlank()) {
                    errors.add("Saga 节点需要 compensateActionType（补偿动作）")
                }
            }
            EnhancedNodeType.DELAY -> {
                if (config.delayMs == null || config.delayMs <= 0) {
                    errors.add("延时节点需要正数 delayMs")
                }
            }
            EnhancedNodeType.END -> { /* no required config */ }
        }

        return if (errors.isEmpty()) ValidationResult.Valid
        else ValidationResult.Invalid(errors)
    }

        data object Valid : ValidationResult()

@Serializable
data class NodePositionDef(
    val x: Float = 0f,
    val y: Float = 0f
)

@Serializable
data class EnhancedNodeConfig(
    /** 触发器配置 */
    val triggerConfig: TriggerConfigDef? = null,
    /** 执行动作类型 */
    val actionType: String? = null,
    /** 执行动作参数 */
    val actionConfig: Map<String, String> = emptyMap(),
    /** Saga 补偿动作类型 */
    val compensateActionType: String? = null,
    /** Saga 补偿动作参数 */
    val compensateActionConfig: Map<String, String> = emptyMap(),
    /** 条件左值 */
    val left: ParameterValueDef? = null,
    /** 条件右值 */
    val right: ParameterValueDef? = null,
    /** 操作符 */
    val operator: String? = null,
    /** 提取模式 */
    val extractMode: ExtractModeDef? = null,
    /** 表达式 */
    val expression: String? = null,
    /** 延时毫秒 */
    val delayMs: Long? = null,
    /** Fan-out 配置 */
    val fanOutSpec: FanOutSpecDef? = null,
    /** Fan-in 配置 */
    val fanInSpec: FanInSpecDef? = null,
    /** 循环配置 */
    val loopSpec: LoopSpecDef? = null,
    /** 子工作流配置 */
    val subWorkflowConfig: SubWorkflowConfigDef? = null,
    /** 人工输入配置 */
    val humanInputConfig: HumanInputConfigDef? = null,
    /** 输入参数列表 */
    val inputs: List<ParameterValueDef> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class TriggerConfigDef(
    val triggerType: TriggerTypeDef,
    val scheduleConfig: ScheduleConfigDef? = null,
    val eventConfig: EventTriggerConfigDef? = null,
    val intentConfig: IntentConfigDef? = null,
    val speechConfig: SpeechConfigDef? = null,
    val webhookConfig: WebhookConfigDef? = null
)

@Serializable
enum class TriggerTypeDef {
    MANUAL, SCHEDULE, EVENT, INTENT, SPEECH, WEBHOOK
}

@Serializable
data class ScheduleConfigDef(
    val scheduleType: ScheduleTypeDef,
    val intervalMs: Long? = null,
    val specificTime: String? = null,
    val cronExpression: String? = null,
    val repeat: Boolean = true,
    val enabled: Boolean = true
)

@Serializable
enum class ScheduleTypeDef {
    INTERVAL, SPECIFIC_TIME, CRON
}

@Serializable
data class EventTriggerConfigDef(
    val eventType: String,
    val filterExpression: String? = null,
    val debounceMs: Long = 0L,
    val dedupKey: String? = null
)

@Serializable
data class IntentConfigDef(val action: String)

@Serializable
data class SpeechConfigDef(
    val pattern: String,
    val ignoreCase: Boolean = true,
    val requireFinal: Boolean = true,
    val cooldownMs: Long = 3000
)

@Serializable
data class WebhookConfigDef(
    val path: String,
    val method: String = "POST",
    val authRequired: Boolean = true
)

@Serializable
enum class ExtractModeDef {
    REGEX, JSON, SUBSTRING, CONCAT, RANDOM_INT, RANDOM_STRING, JQ_PATH
}

@Serializable
data class FanOutSpecDef(
    val itemsExpression: String,
    val maxConcurrency: Int = 5,
    val failFast: Boolean = false,
    val branchTemplate: String? = null
)

@Serializable
data class FanInSpecDef(
    val waitForAll: Boolean = true,
    val aggregatorType: AggregatorTypeDef = AggregatorTypeDef.MERGE_BY_KEY,
    val customAggregatorExpr: String? = null
)

@Serializable
enum class AggregatorTypeDef {
    FIRST, LAST, ALL, MERGE_BY_KEY, MERGE_LIST, MAP_REDUCE, CUSTOM
}

@Serializable
data class LoopSpecDef(
    val loopType: LoopTypeDef,
    val times: Int? = null,
    val itemsExpression: String? = null,
    val conditionExpression: String? = null,
    val maxIterations: Int = 1000,
    val maxConcurrency: Int = 4,
    val bodyNodeIds: List<String> = emptyList(),
    val breakCondition: String? = null
)

@Serializable
enum class LoopTypeDef {
    COUNT, FOR_EACH, WHILE, MAP_REDUCE
}

@Serializable
data class SubWorkflowConfigDef(
    val subWorkflowId: String,
    val subWorkflowVersion: Int? = null,
    val inputs: Map<String, String> = emptyMap(),
    val waitForCompletion: Boolean = true,
    val timeoutMs: Long = 5 * 60_000L,
    val inheritContext: Boolean = false
)

@Serializable
data class HumanInputConfigDef(
    val prompt: String,
    val options: List<String> = listOf("approve", "reject"),
    val timeoutMs: Long = 24 * 60 * 60_000L,
    val approvalRequired: Boolean = true,
    val notificationTitle: String = "工作流等待审批",
    val notificationText: String? = null
)

@Serializable
data class RetryPolicyDef(
    val maxAttempts: Int = 3,
    val initialIntervalMs: Long = 500L,
    val backoffCoefficient: Double = 2.0,
    val maxIntervalMs: Long = 30_000L,
    val jitterRatio: Float = 0.2f,
    val retryableErrorCategories: Set<String> = setOf("TRANSIENT", "TIMEOUT", "RATE_LIMIT", "NETWORK"),
    val nonRetryableErrorCategories: Set<String> = setOf("VALIDATION", "PERMISSION")
)

/**
 * 参数值 - 支持静态值与节点引用
 */
@Serializable
sealed class ParameterValueDef {
    @Serializable
    data class StaticValue(val value: String) : ParameterValueDef()

    @Serializable
    data class NodeReference(val nodeId: String, val jsonPath: String? = null) : ParameterValueDef()

    @Serializable
    data class Expression(val expr: String) : ParameterValueDef()

    companion object {
        fun static(value: String): ParameterValueDef = StaticValue(value)
        fun ref(nodeId: String, jsonPath: String? = null): ParameterValueDef = NodeReference(nodeId, jsonPath)
        fun expr(expr: String): ParameterValueDef = Expression(expr)
    }

    fun resolve(context: Map<String, Any>): String = when (this) {
        is StaticValue -> value
        is NodeReference -> {
            val v = context[nodeId]
            when {
                jsonPath.isNullOrBlank() -> v?.toString() ?: ""
                v is Map<*, *> -> resolveJsonPath(v, jsonPath)?.toString() ?: ""
                else -> v?.toString() ?: ""
            }
        }
        is Expression -> evalExpression(expr, context)
    }

    private fun resolveJsonPath(obj: Map<*, *>, path: String): Any? {
        var current: Any? = obj
        for (segment in path.split(".")) {
            current = when (current) {
                is Map<*, *> -> current[segment]
                else -> return null
            }
        }
        return current
    }

    private fun evalExpression(expr: String, context: Map<String, Any>): String {
        // 简易表达式：${nodeId.field} 替换
        var result = expr
        val regex = Regex("\\$\\{([^}]+)}")
        regex.findAll(expr).forEach { m ->
            val (ref) = m.destructured
            val parts = ref.split(".", limit = 2)
            val v = context[parts[0]]
            val resolved = if (parts.size == 2 && v is Map<*, *>) v[parts[1]]?.toString() ?: ""
                          else v?.toString() ?: ""
            result = result.replace("\${$ref}", resolved)
        }
        return result
    }
}
