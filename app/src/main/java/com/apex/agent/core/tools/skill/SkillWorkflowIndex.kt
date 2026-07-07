package com.apex.agent.core.tools.skill

object SkillWorkflowSystem {

    fun getEventBus(): SkillEventBus = SkillEventBus.getInstance()

    fun getWorkflowEngine(): WorkflowEngine = WorkflowEngine.getInstance()

    fun getWorkflowEditor(): SkillWorkflowEditor = SkillWorkflowEditor.getInstance()

    fun getTaskScheduler(): SkillScheduler = SkillScheduler.getInstance()

    fun getEventTrigger(): SkillEventTrigger = SkillEventTrigger.getInstance()
}

@JvmField
val EVENT_TYPE_SKILL_LOADED = "SkillLoaded"
@JvmField
val EVENT_TYPE_SKILL_UNLOADED = "SkillUnloaded"
@JvmField
val EVENT_TYPE_SKILL_INVOKED = "SkillInvoked"
@JvmField
val EVENT_TYPE_SKILL_COMPLETED = "SkillCompleted"
@JvmField
val EVENT_TYPE_WORKFLOW_TRIGGERED = "WorkflowTriggered"
@JvmField
val EVENT_TYPE_WORKFLOW_NODE_EXECUTED = "WorkflowNodeExecuted"
@JvmField
val EVENT_TYPE_WORKFLOW_COMPLETED = "WorkflowCompleted"
@JvmField
val EVENT_TYPE_TASK_SCHEDULED = "TaskScheduled"
@JvmField
val EVENT_TYPE_TASK_EXECUTED = "TaskExecuted"
@JvmField
val EVENT_TYPE_CUSTOM = "CustomEvent"

@JvmField
val NODE_TYPE_TRIGGER = NodeType.TRIGGER
@JvmField
val NODE_TYPE_EXECUTE = NodeType.EXECUTE
@JvmField
val NODE_TYPE_CONDITION = NodeType.CONDITION
@JvmField
val NODE_TYPE_LOGIC = NodeType.LOGIC
@JvmField
val NODE_TYPE_EXTRACT = NodeType.EXTRACT

@JvmField
val TRIGGER_TYPE_MANUAL = TriggerType.MANUAL
@JvmField
val TRIGGER_TYPE_SCHEDULE = TriggerType.SCHEDULE
@JvmField
val TRIGGER_TYPE_TASKER = TriggerType.TASKER
@JvmField
val TRIGGER_TYPE_INTENT = TriggerType.INTENT
@JvmField
val TRIGGER_TYPE_SPEECH = TriggerType.SPEECH

@JvmField
val EXTRACT_MODE_REGEX = ExtractMode.REGEX
@JvmField
val EXTRACT_MODE_JSON = ExtractMode.JSON
@JvmField
val EXTRACT_MODE_SUB = ExtractMode.SUB
@JvmField
val EXTRACT_MODE_CONCAT = ExtractMode.CONCAT
@JvmField
val EXTRACT_MODE_RANDOM_INT = ExtractMode.RANDOM_INT
@JvmField
val EXTRACT_MODE_RANDOM_STRING = ExtractMode.RANDOM_STRING

@JvmField
val CONDITION_ON_SUCCESS = ConnectionCondition.ON_SUCCESS
@JvmField
val CONDITION_ON_ERROR = ConnectionCondition.ON_ERROR
@JvmField
val CONDITION_TRUE = ConnectionCondition.TRUE
@JvmField
val CONDITION_FALSE = ConnectionCondition.FALSE

object ConditionOperators {
    const val EQ = "EQ"
    const val NE = "NE"
    const val GT = "GT"
    const val GTE = "GTE"
    const val LT = "LT"
    const val LTE = "LTE"
    const val CONTAINS = "CONTAINS"
    const val NOT_CONTAINS = "NOT_CONTAINS"
    const val IN = "IN"
    const val NOT_IN = "NOT_IN"

    const val AND = "AND"
    const val OR = "OR"
}

object TaskScheduleTypes {
    const val INTERVAL = "INTERVAL"
    const val SPECIFIC_TIME = "SPECIFIC_TIME"
    const val CRON = "CRON"
    const val ONE_TIME = "ONE_TIME"
}

object TriggerConditionTypes {
    const val ALWAYS = "ALWAYS"
    const val EQUALS = "EQUALS"
    const val NOT_EQUALS = "NOT_EQUALS"
    const val CONTAINS = "CONTAINS"
    const val REGEX = "REGEX"
    const val GREATER_THAN = "GREATER_THAN"
    const val LESS_THAN = "LESS_THAN"
    const val STARTS_WITH = "STARTS_WITH"
    const val ENDS_WITH = "ENDS_WITH"
}

fun createTriggerNode(
    name: String,
    triggerType: TriggerType,
    position: NodePosition = NodePosition()
): WorkflowNode {
    val config = TriggerConfig(triggerType = triggerType)
    return WorkflowNode(
        name = name,
        type = NodeType.TRIGGER,
        position = position,
        config = NodeConfig(triggerConfig = config)
    )
}

fun createExecuteNode(
    name: String,
    actionType: String,
    actionConfig: Map<String, ParameterValue> = emptyMap(),
    position: NodePosition = NodePosition()
): WorkflowNode {
    return WorkflowNode(
        name = name,
        type = NodeType.EXECUTE,
        position = position,
        config = NodeConfig(
            actionType = actionType,
            actionConfig = actionConfig
        )
    )
}

fun createConditionNode(
    name: String,
    left: ParameterValue,
    operator: String,
    right: ParameterValue? = null,
    position: NodePosition = NodePosition()
): WorkflowNode {
    return WorkflowNode(
        name = name,
        type = NodeType.CONDITION,
        position = position,
        config = NodeConfig(
            left = left,
            operator = operator,
            right = right
        )
    )
}

fun createLogicNode(
    name: String,
    operator: String,
    inputs: List<ParameterValue> = emptyList(),
    position: NodePosition = NodePosition()
): WorkflowNode {
    return WorkflowNode(
        name = name,
        type = NodeType.LOGIC,
        position = position,
        config = NodeConfig(
            operator = operator,
            inputs = inputs
        )
    )
}

fun createExtractNode(
    name: String,
    mode: ExtractMode,
    source: ParameterValue? = null,
    expression: String? = null,
    position: NodePosition = NodePosition()
): WorkflowNode {
    return WorkflowNode(
        name = name,
        type = NodeType.EXTRACT,
        position = position,
        config = NodeConfig(
            mode = mode,
            source = source,
            expression = expression
        )
    )
}

fun createScheduleTrigger(
    scheduleType: ScheduleType,
    intervalMs: Long? = null,
    specificTime: String? = null,
    cronExpression: String? = null,
    repeat: Boolean = true
): TriggerConfig {
    return TriggerConfig(
        triggerType = TriggerType.SCHEDULE,
        scheduleConfig = ScheduleConfig(
            scheduleType = scheduleType,
            intervalMs = intervalMs,
            specificTime = specificTime,
            cronExpression = cronExpression,
            repeat = repeat
        )
    )
}

fun createTaskerTrigger(command: String): TriggerConfig {
    return TriggerConfig(
        triggerType = TriggerType.TASKER,
        taskerConfig = TaskerConfig(command = command)
    )
}

fun createIntentTrigger(action: String): TriggerConfig {
    return TriggerConfig(
        triggerType = TriggerType.INTENT,
        intentConfig = IntentConfig(action = action)
    )
}

fun createSpeechTrigger(
    pattern: String,
    ignoreCase: Boolean = true,
    cooldownMs: Long = 3000
): TriggerConfig {
    return TriggerConfig(
        triggerType = TriggerType.SPEECH,
        speechConfig = SpeechConfig(
            pattern = pattern,
            ignoreCase = ignoreCase,
            cooldownMs = cooldownMs
        )
    )
}

data class WorkflowDefinitionJson(
    val id: String,
    val name: String,
    val description: String,
    val nodes: List<NodeJson>,
    val connections: List<ConnectionJson>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class NodeJson(
    val id: String,
    val name: String,
    val type: String,
    val position: PositionJson,
    val config: ConfigJson
)

data class PositionJson(val x: Float, val y: Float)

data class ConfigJson(
    val triggerConfig: TriggerConfigJson? = null,
    val actionType: String? = null,
    val actionConfig: Map<String, Any>? = null,
    val left: Any? = null,
    val right: Any? = null,
    val operator: String? = null,
    val mode: String? = null
)

data class TriggerConfigJson(
    val triggerType: String,
    val scheduleConfig: ScheduleConfigJson? = null,
    val taskerConfig: TaskerConfigJson? = null,
    val intentConfig: IntentConfigJson? = null,
    val speechConfig: SpeechConfigJson? = null
)

data class ScheduleConfigJson(
    val scheduleType: String,
    val intervalMs: Long? = null,
    val specificTime: String? = null,
    val cronExpression: String? = null,
    val repeat: Boolean = true
)

data class TaskerConfigJson(val command: String)
data class IntentConfigJson(val action: String)
data class SpeechConfigJson(val pattern: String, val ignoreCase: Boolean, val cooldownMs: Long)

data class ConnectionJson(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String,
    val condition: String
)
