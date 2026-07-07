package com.apex.agent.core.workflow.enhanced.dsl

import com.apex.agent.core.workflow.enhanced.model.*
import kotlinx.serialization.Serializable

/**
 * 工作流 DSL 构建器
 *
 * 参照 Kotlin Anko / Gradle Kotlin DSL / Exposed DSL 的设计
 * 提供类型安全的 fluent API 构建工作流
 *
 * 示例：
 * ```kotlin
 * val wf = workflow("我的工作流") {
 *     description = "这是一个示例"
 *     sagaMode = true
 *
 *     node("触发") {
 *         type = EnhancedNodeType.TRIGGER
 *         trigger(TriggerTypeDef.MANUAL)
 *     }
 *
 *     node("创建订单") {
 *         type = EnhancedNodeType.SAGA
 *         execute("create_order", "product_id" to "123")
 *         compensate("cancel_order", "reason" to "rollback")
 *         retryPolicy = RetryPolicyDef(maxAttempts = 3)
 *     }
 *
 *     node("支付") {
 *         type = EnhancedNodeType.SAGA
 *         execute("process_payment", "amount" to "99.9")
 *         compensate("refund_payment")
 *     }
 *
 *     connect("触发" to "创建订单", ConnectionConditionDef.ON_SUCCESS)
 *     connect("创建订单" to "支付", ConnectionConditionDef.ON_SUCCESS)
 * }
 * ```
 */
@DslMarker
annotation class WorkflowDsl

@WorkflowDsl
class WorkflowBuilder(private val name: String) {
    var description: String = ""
    var sagaMode: Boolean = false
    var maxConcurrency: Int = 10
    var defaultRetryPolicy: RetryPolicyDef = RetryPolicyDef()
    var timeoutMs: Long = 30 * 60_000L
    var version: Int = 1
    var tags: List<String> = emptyList()
    var metadata: Map<String, String> = emptyMap()

    private val nodes = mutableListOf<EnhancedNode>()
    private val connections = mutableListOf<EnhancedConnection>()
    private val nodeNameMap = mutableMapOf<String, String>()  // name -> id

    /**
     * 添加节点
     */
    fun node(name: String, block: NodeBuilder.() -> Unit): EnhancedNode {
        val builder = NodeBuilder(name)
        builder.block()
        val node = builder.build()
        nodes.add(node)
        nodeNameMap[name] = node.id
        return node
    }

    /**
     * 便捷方法：触发节点
     */
    fun trigger(name: String = "触发", block: TriggerBuilder.() -> Unit = {}): EnhancedNode {
        val builder = TriggerBuilder(name)
        builder.block()
        val node = builder.build()
        nodes.add(node)
        nodeNameMap[name] = node.id
        return node
    }

    /**
     * 便捷方法：执行节点
     */
    fun execute(
        name: String,
        actionType: String,
        vararg params: Pair<String, String>,
        block: ExecuteBuilder.() -> Unit = {}
    ): EnhancedNode {
        val builder = ExecuteBuilder(name)
        builder.actionType = actionType
        builder.params.putAll(params)
        builder.block()
        val node = builder.build()
        nodes.add(node)
        nodeNameMap[name] = node.id
        return node
    }

    /**
     * 便捷方法：Saga 节点
     */
    fun saga(
        name: String,
        actionType: String,
        compensateActionType: String,
        vararg params: Pair<String, String>,
        block: SagaBuilder.() -> Unit = {}
    ): EnhancedNode {
        val builder = SagaBuilder(name)
        builder.actionType = actionType
        builder.compensateActionType = compensateActionType
        builder.params.putAll(params)
        builder.block()
        val node = builder.build()
        nodes.add(node)
        nodeNameMap[name] = node.id
        return node
    }

    /**
     * 便捷方法：条件节点
     */
    fun condition(
        name: String = "条件判断",
        left: ParameterValueDef,
        operator: String,
        right: ParameterValueDef,
        block: NodeBuilder.() -> Unit = {}
    ): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.CONDITION
            config = EnhancedNodeConfig(left = left, operator = operator, right = right)
            block()
        }
    }

    /**
     * 便捷方法：延时节点
     */
    fun delay(name: String = "延时", delayMs: Long): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.DELAY
            config = EnhancedNodeConfig(delayMs = delayMs)
        }
    }

    /**
     * 便捷方法：人工审批节点
     */
    fun humanInput(
        name: String = "人工审批",
        prompt: String,
        options: List<String> = listOf("approve", "reject"),
        block: HumanInputBuilder.() -> Unit = {}
    ): EnhancedNode {
        val builder = HumanInputBuilder(name)
        builder.prompt = prompt
        builder.options = options
        builder.block()
        val node = builder.build()
        nodes.add(node)
        nodeNameMap[name] = node.id
        return node
    }

    /**
     * 便捷方法：Fan-out 节点
     */
    fun fanOut(
        name: String = "并行扇出",
        itemsExpression: String,
        maxConcurrency: Int = 5,
        failFast: Boolean = false,
        block: NodeBuilder.() -> Unit = {}
    ): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.FAN_OUT
            config = EnhancedNodeConfig(
                fanOutSpec = FanOutSpecDef(itemsExpression, maxConcurrency, failFast)
            )
            block()
        }
    }

    /**
     * 便捷方法：Fan-in 节点
     */
    fun fanIn(
        name: String = "汇合",
        aggregatorType: AggregatorTypeDef = AggregatorTypeDef.ALL,
        block: NodeBuilder.() -> Unit = {}
    ): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.FAN_IN
            config = EnhancedNodeConfig(
                fanInSpec = FanInSpecDef(aggregatorType = aggregatorType)
            )
            block()
        }
    }

    /**
     * 便捷方法：循环节点
     */
    fun loop(
        name: String = "循环",
        loopType: LoopTypeDef,
        times: Int? = null,
        itemsExpression: String? = null,
        bodyNodeIds: List<String> = emptyList(),
        block: NodeBuilder.() -> Unit = {}
    ): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.LOOP
            config = EnhancedNodeConfig(
                loopSpec = LoopSpecDef(
                    loopType = loopType,
                    times = times,
                    itemsExpression = itemsExpression,
                    bodyNodeIds = bodyNodeIds
                )
            )
            block()
        }
    }

    /**
     * 便捷方法：子工作流节点
     */
    fun subWorkflow(
        name: String = "子工作流",
        subWorkflowId: String,
        waitForCompletion: Boolean = true,
        block: NodeBuilder.() -> Unit = {}
    ): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.SUB_WORKFLOW
            config = EnhancedNodeConfig(
                subWorkflowConfig = SubWorkflowConfigDef(
                    subWorkflowId = subWorkflowId,
                    waitForCompletion = waitForCompletion
                )
            )
            block()
        }
    }

    /**
     * 便捷方法：结束节点
     */
    fun end(name: String = "结束"): EnhancedNode {
        return node(name) {
            type = EnhancedNodeType.END
        }
    }

    /**
     * 连接节点（用名称引用）
     */
    fun connect(
        from: Pair<String, String>,
        condition: ConnectionConditionDef = ConnectionConditionDef.ON_SUCCESS
    ) {
        val sourceId = nodeNameMap[from.first] ?: error("未找到节点: ${from.first}")
        val targetId = nodeNameMap[from.second] ?: error("未找到节点: ${from.second}")
        connections.add(EnhancedConnection(sourceNodeId = sourceId, targetNodeId = targetId, condition = condition))
    }

    /**
     * 连接多个节点（链式）
     */
    fun chain(vararg names: String, condition: ConnectionConditionDef = ConnectionConditionDef.ON_SUCCESS) {
        for (i in 0 until names.size - 1) {
            connect(names[i] to names[i + 1], condition)
        }
    }

    /**
     * 构建 EnhancedWorkflow
     */
    fun build(): EnhancedWorkflow {
        return EnhancedWorkflow(
            name = name,
            description = description,
            version = version,
            nodes = nodes,
            connections = connections,
            sagaMode = sagaMode,
            maxConcurrency = maxConcurrency,
            defaultRetryPolicy = defaultRetryPolicy,
            timeoutMs = timeoutMs,
            tags = tags,
            metadata = metadata
        )
    }
}

@WorkflowDsl
open class NodeBuilder(protected val nodeName: String) {
    var type: EnhancedNodeType = EnhancedNodeType.EXECUTE
    var config: EnhancedNodeConfig = EnhancedNodeConfig()
    var retryPolicy: RetryPolicyDef? = null
    var timeoutMs: Long? = null
    var enabled: Boolean = true
    var description: String = ""

    fun trigger(type: TriggerTypeDef, block: TriggerConfigDefBuilder.() -> Unit = {}) {
        this.type = EnhancedNodeType.TRIGGER
        val builder = TriggerConfigDefBuilder(type)
        builder.block()
        config = config.copy(triggerConfig = builder.build())
    }

    fun execute(actionType: String, vararg params: Pair<String, String>) {
        this.type = EnhancedNodeType.EXECUTE
        config = config.copy(
            actionType = actionType,
            actionConfig = params.toMap()
        )
    }

    fun compensate(actionType: String, vararg params: Pair<String, String>) {
        config = config.copy(
            compensateActionType = actionType,
            compensateActionConfig = params.toMap()
        )
    }

    open fun build(): EnhancedNode {
        return EnhancedNode(
            name = nodeName,
            type = type,
            config = config,
            retryPolicy = retryPolicy,
            timeoutMs = timeoutMs,
            enabled = enabled,
            description = description
        )
    }
}

@WorkflowDsl
class TriggerBuilder(name: String) : NodeBuilder(name) {
    init { type = EnhancedNodeType.TRIGGER }

    var triggerType: TriggerTypeDef = TriggerTypeDef.MANUAL
    var cronExpression: String? = null
    var intervalMs: Long? = null
    var specificTime: String? = null
    var eventType: String? = null

    override fun build(): EnhancedNode {
        val scheduleConfig = when {
            cronExpression != null -> ScheduleConfigDef(
                scheduleType = ScheduleTypeDef.CRON,
                cronExpression = cronExpression
            )
            intervalMs != null -> ScheduleConfigDef(
                scheduleType = ScheduleTypeDef.INTERVAL,
                intervalMs = intervalMs
            )
            specificTime != null -> ScheduleConfigDef(
                scheduleType = ScheduleTypeDef.SPECIFIC_TIME,
                specificTime = specificTime
            )
            else -> null
        }

        val eventConfig = eventType?.let { EventTriggerConfigDef(eventType = it) }

        config = config.copy(
            triggerConfig = TriggerConfigDef(
                triggerType = triggerType,
                scheduleConfig = scheduleConfig,
                eventConfig = eventConfig
            )
        )
        return super.build()
    }
}

@WorkflowDsl
class ExecuteBuilder(name: String) : NodeBuilder(name) {
    init { type = EnhancedNodeType.EXECUTE }
    var actionType: String = "log"
    val params = mutableMapOf<String, String>()

    override fun build(): EnhancedNode {
        config = config.copy(actionType = actionType, actionConfig = params.toMap())
        return super.build()
    }
}

@WorkflowDsl
class SagaBuilder(name: String) : NodeBuilder(name) {
    init { type = EnhancedNodeType.SAGA }
    var actionType: String = ""
    var compensateActionType: String = ""
    val params = mutableMapOf<String, String>()

    override fun build(): EnhancedNode {
        config = config.copy(
            actionType = actionType,
            actionConfig = params.toMap(),
            compensateActionType = compensateActionType
        )
        return super.build()
    }
}

@WorkflowDsl
class HumanInputBuilder(name: String) : NodeBuilder(name) {
    init { type = EnhancedNodeType.HUMAN_INPUT }
    var prompt: String = ""
    var options: List<String> = listOf("approve", "reject")
    var timeoutMs: Long = 24 * 60 * 60_000L

    override fun build(): EnhancedNode {
        config = config.copy(
            humanInputConfig = HumanInputConfigDef(
                prompt = prompt,
                options = options,
                timeoutMs = timeoutMs
            )
        )
        return super.build()
    }
}

/**
 * TriggerConfigDef 构建器
 */
class TriggerConfigDefBuilder(private val type: TriggerTypeDef) {
    private var schedule: ScheduleConfigDef? = null
    private var event: EventTriggerConfigDef? = null

    fun schedule(block: ScheduleConfigDefBuilder.() -> Unit) {
        val b = ScheduleConfigDefBuilder()
        b.block()
        schedule = b.build()
    }

    fun event(eventType: String, filter: String? = null) {
        event = EventTriggerConfigDef(eventType = eventType, filterExpression = filter)
    }

    fun build(): TriggerConfigDef {
        return TriggerConfigDef(
            triggerType = type,
            scheduleConfig = schedule,
            eventConfig = event
        )
    }
}

class ScheduleConfigDefBuilder {
    var type: ScheduleTypeDef = ScheduleTypeDef.INTERVAL
    var intervalMs: Long? = null
    var specificTime: String? = null
    var cronExpression: String? = null
    var repeat: Boolean = true

    fun build(): ScheduleConfigDef = ScheduleConfigDef(
        scheduleType = type,
        intervalMs = intervalMs,
        specificTime = specificTime,
        cronExpression = cronExpression,
        repeat = repeat
    )
}

// TriggerConfigDefBuilder 用于 DSL trigger() 方法

/**
 * 顶层 DSL 入口
 */
fun workflow(name: String, block: WorkflowBuilder.() -> Unit): EnhancedWorkflow {
    val builder = WorkflowBuilder(name)
    builder.block()
    return builder.build()
}
