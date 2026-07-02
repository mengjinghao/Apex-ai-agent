package com.apex.agent.core.workflow.enhanced

import com.apex.agent.core.workflow.enhanced.checkpoint.CheckpointManager
import com.apex.agent.core.workflow.enhanced.checkpoint.CheckpointNodeState
import com.apex.agent.core.workflow.enhanced.checkpoint.InMemoryCheckpointer
import com.apex.agent.core.workflow.enhanced.events.EventBusHolder
import com.apex.agent.core.workflow.enhanced.events.WorkflowEvent
import com.apex.agent.core.workflow.enhanced.hitl.ApprovalGatewayHolder
import com.apex.agent.core.workflow.enhanced.hitl.HumanRejectedByUserException
import com.apex.agent.core.workflow.enhanced.hitl.InterruptPayload
import com.apex.agent.core.workflow.enhanced.hitl.ResumeCommand
import com.apex.agent.core.workflow.enhanced.loop.LoopExecutor
import com.apex.agent.core.workflow.enhanced.loop.LoopSpec
import com.apex.agent.core.workflow.enhanced.model.*
import com.apex.agent.core.workflow.enhanced.observability.InMemoryTracer
import com.apex.agent.core.workflow.enhanced.observability.Span
import com.apex.agent.core.workflow.enhanced.observability.SpanStatus
import com.apex.agent.core.workflow.enhanced.observability.TracerHolder
import com.apex.agent.core.workflow.enhanced.observability.WorkflowTracer
import com.apex.agent.core.workflow.enhanced.parallel.Aggregators
import com.apex.agent.core.workflow.enhanced.parallel.ParallelExecutor
import com.apex.agent.core.workflow.enhanced.retry.CircuitBreakerRegistry
import com.apex.agent.core.workflow.enhanced.retry.RetryExecutor
import com.apex.agent.core.workflow.enhanced.saga.Saga
import com.apex.agent.core.workflow.enhanced.saga.SagaBuilder
import com.apex.agent.core.workflow.enhanced.saga.SagaResult
import com.apex.agent.core.workflow.enhanced.subworkflow.DelegatingSubWorkflowExecutor
import com.apex.agent.core.workflow.enhanced.subworkflow.SubWorkflowInvocation
import com.apex.agent.core.workflow.enhanced.subworkflow.SubWorkflowResult
import com.apex.agent.core.workflow.enhanced.validation.WorkflowValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 增强版工作流执行器
 *
 * 融合 LangGraph / Temporal / Airflow / Dify / n8n / PocketFlow 等顶级工作流系统的设计模式
 *
 * 核心能力：
 * 1. DAG 拓扑校验 + 环检测（WorkflowValidator）
 * 2. 节点级 RetryPolicy + Circuit Breaker（RetryExecutor）
 * 3. Checkpoint & Durable Execution（CheckpointManager）
 * 4. 并行 Fan-out / Fan-in + Barrier（ParallelExecutor）
 * 5. 子工作流嵌套（DelegatingSubWorkflowExecutor）
 * 6. 循环（Count/ForEach/While/MapReduce）（LoopExecutor）
 * 7. Saga 补偿事务（Saga）
 * 8. 人在回路审批（ApprovalGateway）
 * 9. 事件驱动触发（WorkflowEventBus）
 * 10. 实时 Observability（InMemoryTracer）
 * 11. 版本管理（WorkflowVersionRegistry）
 * 12. 模板市场（WorkflowTemplateRegistry）
 *
 * 用法：
 * ```kotlin
 * val executor = EnhancedWorkflowExecutor.Builder()
 *     .withTracer(InMemoryTracer())
 *     .withCheckpointer(InMemoryCheckpointer())
 *     .withActionHandler("send_message", SendMessageHandler())
 *     .build()
 *
 * val result = executor.execute(workflow, inputs = mapOf("text" to "hello"))
 * ```
 */
class EnhancedWorkflowExecutor private constructor(
    private val tracer: WorkflowTracer,
    private val checkpointManager: CheckpointManager,
    private val retryExecutor: RetryExecutor,
    private val parallelExecutor: ParallelExecutor,
    private val loopExecutor: LoopExecutor,
    private val validator: WorkflowValidator,
    private val actionHandlers: Map<String, ActionHandler>,
    private val compensateHandlers: Map<String, CompensateHandler>,
    private val triggerHandlers: Map<TriggerTypeDef, TriggerHandler>,
    private val subWorkflowExecutor: DelegatingSubWorkflowExecutor?,
    private val defaultTimeoutMs: Long
) {
    /**
     * 动作处理器 - 业务侧注册，负责执行具体的 EXECUTE 节点逻辑
     */
    fun interface ActionHandler {
        suspend fun execute(actionType: String, params: Map<String, String>, context: ExecutionContext): ActionResult
    }

    /** 补偿动作处理器 - 用于 Saga 模式 */
    fun interface CompensateHandler {
        suspend fun compensate(actionType: String, params: Map<String, String>, result: Any?, context: ExecutionContext)
    }

    /** 触发器处理器 */
    fun interface TriggerHandler {
        suspend fun evaluate(config: TriggerConfigDef, context: ExecutionContext): TriggerResult
    }

    sealed class ActionResult {
        data class Success(val output: Any, val metadata: Map<String, Any> = emptyMap()) : ActionResult()
        data class Failure(val error: String, val throwable: Throwable? = null) : ActionResult()
    }

    sealed class TriggerResult {
        data class Fired(val payload: Map<String, Any> = emptyMap()) : TriggerResult()
        data object NotMet : TriggerResult()
        data class Error(val message: String) : TriggerResult()
    }

    /**
     * 执行上下文 - 贯穿整个工作流执行
     */
    data class ExecutionContext(
        val threadId: String,
        val workflow: EnhancedWorkflow,
        val variables: MutableMap<String, Any>,
        val nodeResults: MutableMap<String, NodeResult>,
        val executionPath: MutableList<String>,
        val parentSpanId: String?,
        val parentThreadId: String?
    )

    sealed class NodeResult {
        data class Success(val output: Any, val durationMs: Long) : NodeResult()
        data class Failure(val error: Throwable, val durationMs: Long) : NodeResult()
        data class Skipped(val reason: String) : NodeResult()
        data object WaitingHuman : NodeResult()
    }

    /**
     * 工作流执行结果
     */
    data class ExecutionResult(
        val threadId: String,
        val workflowId: String,
        val workflowVersion: Int,
        val success: Boolean,
        val outputs: Map<String, Any>,
        val nodeResults: Map<String, NodeResult>,
        val executionPath: List<String>,
        val durationMs: Long,
        val error: String? = null,
        val sagaResult: SagaResult<Any?>? = null
    )

    /** 执行事件流（实时） */
    private val _events = MutableSharedFlow<ExecutionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ExecutionEvent> = _events.asSharedFlow()

    /** 活跃执行状态 */
    private val _activeExecutions = MutableStateFlow<Map<String, ExecutionState>>(emptyMap())
    val activeExecutions: StateFlow<Map<String, ExecutionState>> = _activeExecutions.asStateFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()

    /**
     * 执行工作流
     *
     * @param workflow 工作流定义
     * @param inputs 输入变量
     * @param parentThreadId 父线程 ID（子工作流时传入）
     * @return 执行结果
     */
    suspend fun execute(
        workflow: EnhancedWorkflow,
        inputs: Map<String, Any> = emptyMap(),
        parentThreadId: String? = null
    ): ExecutionResult {
        val threadId = "thread_${UUID.randomUUID()}"
        return executeInternal(workflow, inputs, threadId, parentThreadId, null)
    }

    /**
     * 内部执行（可指定 threadId，用于恢复）
     */
    private suspend fun executeInternal(
        workflow: EnhancedWorkflow,
        inputs: Map<String, Any>,
        threadId: String,
        parentThreadId: String?,
        resumeFromCheckpoint: String?
    ): ExecutionResult = coroutineScope {
        val startTime = System.currentTimeMillis()

        // 1. 校验
        val validation = validator.validate(workflow)
        if (!validation.isValid) {
            return@coroutineScope ExecutionResult(
                threadId = threadId,
                workflowId = workflow.id,
                workflowVersion = workflow.version,
                success = false,
                outputs = emptyMap(),
                nodeResults = emptyMap(),
                executionPath = emptyList(),
                durationMs = System.currentTimeMillis() - startTime,
                error = "工作流校验失败: ${validation.errors.joinToString("; ")}"
            )
        }

        // 2. 创建根 span
        val rootSpan = tracer.startSpan(
            name = "workflow:${workflow.name}",
            threadId = threadId,
            nodeId = null,
            parentSpanId = null,
            attributes = mapOf(
                "workflow.id" to workflow.id,
                "workflow.version" to workflow.version,
                "workflow.sagaMode" to workflow.sagaMode,
                "inputs.keys" to inputs.keys.toString()
            )
        )

        // 3. 初始化上下文
        val context = ExecutionContext(
            threadId = threadId,
            workflow = workflow,
            variables = inputs.toMutableMap(),
            nodeResults = mutableMapOf(),
            executionPath = mutableListOf(),
            parentSpanId = rootSpan.spanId,
            parentThreadId = parentThreadId
        )

        // 4. 设置活跃状态
        _activeExecutions.value = _activeExecutions.value + (threadId to ExecutionState.Running(workflow.id, 0f))

        try {
            // 5. 根据 sagaMode 选择执行策略
            val result = if (workflow.sagaMode) {
                executeAsSaga(workflow, context, rootSpan)
            } else {
                executeNormal(workflow, context, rootSpan, resumeFromCheckpoint)
            }

            rootSpan.end(SpanStatus.OK)
            _events.emit(ExecutionEvent.WorkflowCompleted(threadId, result.success, System.currentTimeMillis() - startTime))

            result
        } catch (e: CancellationException) {
            rootSpan.recordException(e)
            rootSpan.end(SpanStatus.ERROR)
            _events.emit(ExecutionEvent.WorkflowCancelled(threadId, e.message ?: ""))
            throw e
        } catch (e: Throwable) {
            rootSpan.recordException(e)
            rootSpan.end(SpanStatus.ERROR)
            _events.emit(ExecutionEvent.WorkflowFailed(threadId, e))
            ExecutionResult(
                threadId = threadId,
                workflowId = workflow.id,
                workflowVersion = workflow.version,
                success = false,
                outputs = context.variables.toMap(),
                nodeResults = context.nodeResults.toMap(),
                executionPath = context.executionPath.toList(),
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        } finally {
            _activeExecutions.value = _activeExecutions.value - threadId
        }
    }

    /**
     * 正常执行（非 Saga 模式）
     */
    private suspend fun executeNormal(
        workflow: EnhancedWorkflow,
        context: ExecutionContext,
        rootSpan: Span,
        resumeFromCheckpoint: String?
    ): ExecutionResult = coroutineScope {
        val startTime = System.currentTimeMillis()

        // 从触发节点开始
        val triggerNodes = workflow.getTriggerNodes()
        val startNodes = if (triggerNodes.isNotEmpty()) triggerNodes else workflow.nodes.take(1)

        // 执行所有起始节点
        for (node in startNodes) {
            executeNodeCascade(node, context, rootSpan.spanId)
        }

        ExecutionResult(
            threadId = context.threadId,
            workflowId = workflow.id,
            workflowVersion = workflow.version,
            success = context.nodeResults.values.all {
                it is NodeResult.Success || it is NodeResult.Skipped
            },
            outputs = context.variables.toMap(),
            nodeResults = context.nodeResults.toMap(),
            executionPath = context.executionPath.toList(),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Saga 模式执行 - 正向执行 + 失败补偿
     */
    private suspend fun executeAsSaga(
        workflow: EnhancedWorkflow,
        context: ExecutionContext,
        rootSpan: Span
    ): ExecutionResult = coroutineScope {
        val startTime = System.currentTimeMillis()

        // 收集所有 EXECUTE/SAGA 节点作为 saga steps（按拓扑顺序）
        val sortedNodes = validator.validate(workflow).topologicalOrder.mapNotNull { id ->
            workflow.getNodeById(id)
        }.filter { it.type == EnhancedNodeType.EXECUTE || it.type == EnhancedNodeType.SAGA }

        val sagaBuilder = SagaBuilder<Any?>()
        for (node in sortedNodes) {
            sagaBuilder.step(
                nodeId = node.id,
                name = node.name,
                execute = {
                    executeNodeInternal(node, context, rootSpan.spanId)
                    (context.nodeResults[node.id] as? NodeResult.Success)?.output
                },
                compensate = { result ->
                    val compType = node.config.compensateActionType
                    if (compType != null) {
                        val handler = compensateHandlers[compType]
                        handler?.compensate(
                            compType,
                            node.config.compensateActionConfig,
                            result,
                            context
                        )
                    }
                }
            )
        }

        val saga = sagaBuilder.build()
        val sagaResult = saga.run()

        when (sagaResult) {
            is SagaResult.Success -> ExecutionResult(
                threadId = context.threadId,
                workflowId = workflow.id,
                workflowVersion = workflow.version,
                success = true,
                outputs = context.variables.toMap(),
                nodeResults = context.nodeResults.toMap(),
                executionPath = context.executionPath.toList(),
                durationMs = System.currentTimeMillis() - startTime,
                sagaResult = sagaResult
            )
            is SagaResult.Compensated -> ExecutionResult(
                threadId = context.threadId,
                workflowId = workflow.id,
                workflowVersion = workflow.version,
                success = false,
                outputs = context.variables.toMap(),
                nodeResults = context.nodeResults.toMap(),
                executionPath = context.executionPath.toList(),
                durationMs = System.currentTimeMillis() - startTime,
                error = "Saga 失败: ${sagaResult.failure.message}, 补偿 ${sagaResult.compensationResults.size} 步",
                sagaResult = sagaResult
            )
        }
    }

    /**
     * 级联执行节点（沿出边递归）
     */
    private suspend fun executeNodeCascade(
        node: EnhancedNode,
        context: ExecutionContext,
        parentSpanId: String
    ) {
        if (!node.enabled) {
            context.nodeResults[node.id] = NodeResult.Skipped("节点已禁用")
            return
        }
        if (node.id in context.executionPath) return  // 防止重复执行
        context.executionPath.add(node.id)

        // 保存检查点
        kotlinx.coroutines.coroutineScope {
            launch {
                checkpointManager.saveCheckpoint(
                    threadId = context.threadId,
                    parentCheckpointId = null,
                    workflow = context.workflow,
                    nodeId = node.id,
                    nodeState = CheckpointNodeState.RUNNING,
                    variables = context.variables.toMap(),
                    executionPath = context.executionPath.toList()
                )
            }
        }

        // 执行当前节点
        val nodeResult = executeNodeInternal(node, context, parentSpanId)

        // 检查人工审批中断
        if (nodeResult is NodeResult.WaitingHuman) {
            return  // 等待 resume
        }

        // 沿出边级联
        val outgoing = context.workflow.getOutgoingConnections(node.id)
        for (conn in outgoing.sortedBy { it.priority }) {
            val shouldTraverse = when (conn.condition) {
                ConnectionConditionDef.ON_SUCCESS -> nodeResult is NodeResult.Success
                ConnectionConditionDef.ON_ERROR -> nodeResult is NodeResult.Failure
                ConnectionConditionDef.TRUE -> nodeResult is NodeResult.Success
                ConnectionConditionDef.FALSE -> nodeResult is NodeResult.Failure
                ConnectionConditionDef.ALWAYS -> true
            }
            if (shouldTraverse) {
                val nextNode = context.workflow.getNodeById(conn.targetNodeId) ?: continue
                executeNodeCascade(nextNode, context, parentSpanId)
            }
        }
    }

    /**
     * 执行单个节点（含重试 + 追踪）
     */
    private suspend fun executeNodeInternal(
        node: EnhancedNode,
        context: ExecutionContext,
        parentSpanId: String
    ): NodeResult {
        val span = tracer.startSpan(
            name = "node:${node.name}",
            threadId = context.threadId,
            nodeId = node.id,
            parentSpanId = parentSpanId,
            attributes = mapOf(
                "node.type" to node.type.name,
                "node.actionType" to (node.config.actionType ?: ""),
                "node.retryPolicy" to (node.retryPolicy?.toString() ?: "default")
            )
        )

        val startTime = System.currentTimeMillis()
        var result: NodeResult

        try {
            result = withTimeout(node.timeoutMs ?: defaultTimeoutMs) {
                val policy = node.retryPolicy ?: context.workflow.defaultRetryPolicy
                val breaker = CircuitBreakerRegistry.get(
                    "${context.workflow.id}:${node.id}"
                )
                retryExecutor.runWithRetry(policy, breaker) {
                    executeNodeByType(node, context, span.spanId)
                }
            }
            span.setAttribute("node.result", result::class.simpleName ?: "unknown")
            span.end(SpanStatus.OK)
        } catch (e: CancellationException) {
            span.recordException(e)
            span.end(SpanStatus.ERROR)
            throw e
        } catch (e: Throwable) {
            result = NodeResult.Failure(e, System.currentTimeMillis() - startTime)
            span.recordException(e)
            span.end(SpanStatus.ERROR)
        }

        context.nodeResults[node.id] = result

        // 发布节点输出事件
        if (result is NodeResult.Success) {
            context.variables["__node_${node.id}_output"] = result.output
            EventBusHolder.get().emitNodeOutput(
                type = "node.output",
                payload = result.output ?: "",
                nodeId = node.id,
                threadId = context.threadId
            )
        }

        _events.emit(
            ExecutionEvent.NodeCompleted(
                threadId = context.threadId,
                nodeId = node.id,
                nodeName = node.name,
                success = result is NodeResult.Success,
                durationMs = System.currentTimeMillis() - startTime
            )
        )

        return result
    }

    /**
     * 按节点类型分发执行
     */
    private suspend fun executeNodeByType(
        node: EnhancedNode,
        context: ExecutionContext,
        spanId: String
    ): NodeResult {
        val start = System.currentTimeMillis()
        return when (node.type) {
            EnhancedNodeType.TRIGGER -> executeTriggerNode(node, context)
            EnhancedNodeType.EXECUTE -> executeActionNode(node, context)
            EnhancedNodeType.CONDITION -> executeConditionNode(node, context)
            EnhancedNodeType.LOGIC -> executeLogicNode(node, context)
            EnhancedNodeType.EXTRACT -> executeExtractNode(node, context)
            EnhancedNodeType.FAN_OUT -> executeFanOutNode(node, context, spanId)
            EnhancedNodeType.FAN_IN -> executeFanInNode(node, context)
            EnhancedNodeType.LOOP -> executeLoopNode(node, context, spanId)
            EnhancedNodeType.SUB_WORKFLOW -> executeSubWorkflowNode(node, context)
            EnhancedNodeType.HUMAN_INPUT -> executeHumanInputNode(node, context)
            EnhancedNodeType.SAGA -> executeActionNode(node, context)  // Saga 节点复用 action 执行
            EnhancedNodeType.DELAY -> executeDelayNode(node, context)
            EnhancedNodeType.END -> NodeResult.Success("end", System.currentTimeMillis() - start)
        }
    }

    // ============ 节点类型执行实现 ============

    private suspend fun executeTriggerNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val config = node.config.triggerConfig ?: return NodeResult.Failure(
            IllegalStateException("触发节点缺少配置"), 0
        )
        val handler = triggerHandlers[config.triggerType]
        return if (handler != null) {
            when (val r = handler.evaluate(config, context)) {
                is TriggerResult.Fired -> {
                    context.variables.putAll(r.payload)
                    NodeResult.Success("triggered", System.currentTimeMillis() - start)
                }
                is TriggerResult.NotMet -> NodeResult.Skipped("条件未满足")
                is TriggerResult.Error -> NodeResult.Failure(RuntimeException(r.message), System.currentTimeMillis() - start)
            }
        } else {
            // 无 handler，默认手动触发
            NodeResult.Success("manual", System.currentTimeMillis() - start)
        }
    }

    private suspend fun executeActionNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val actionType = node.config.actionType
            ?: return NodeResult.Failure(IllegalStateException("缺少 actionType"), 0)
        val handler = actionHandlers[actionType]
            ?: return NodeResult.Failure(IllegalStateException("未注册 action handler: $actionType"), 0)

        // 解析参数（支持节点引用）
        val resolvedParams = node.config.actionConfig.mapValues { (_, v) ->
            resolveParameterValue(v, context)
        }

        return when (val r = handler.execute(actionType, resolvedParams, context)) {
            is ActionResult.Success -> {
                context.variables["__last_output"] = r.output
                NodeResult.Success(r.output, System.currentTimeMillis() - start)
            }
            is ActionResult.Failure -> NodeResult.Failure(
                r.throwable ?: RuntimeException(r.error),
                System.currentTimeMillis() - start
            )
        }
    }

    private suspend fun executeConditionNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val left = node.config.left?.resolve(context.variables)
            ?: return NodeResult.Failure(IllegalStateException("条件缺少 left"), 0)
        val right = node.config.right?.resolve(context.variables) ?: ""
        val op = node.config.operator ?: "="

        val result = when (op.uppercase()) {
            "=", "==" -> left == right
            "!=", "<>" -> left != right
            ">", "<", ">=", "<=" -> {
                val ln = left.toDoubleOrNull()
                val rn = right.toDoubleOrNull()
                if (ln != null && rn != null) {
                    when (op) {
                        ">" -> ln > rn
                        "<" -> ln < rn
                        ">=" -> ln >= rn
                        "<=" -> ln <= rn
                        else -> false
                    }
                } else false
            }
            "CONTAINS" -> left.contains(right, ignoreCase = true)
            "STARTS_WITH" -> left.startsWith(right, ignoreCase = true)
            "ENDS_WITH" -> left.endsWith(right, ignoreCase = true)
            "MATCHES" -> left.matches(Regex(right))
            else -> false
        }

        context.variables["__cond_${node.id}"] = result
        return NodeResult.Success(result.toString(), System.currentTimeMillis() - start)
    }

    private suspend fun executeLogicNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val op = node.config.operator?.uppercase() ?: "AND"
        // 简化实现：对 inputs 列表求布尔值
        val bools = node.config.inputs.map { p ->
            val v = p.resolve(context.variables)
            v.lowercase() in setOf("true", "1", "yes", "on")
        }
        val result = when (op) {
            "AND" -> bools.all { it }
            "OR" -> bools.any { it }
            "NOT" -> bools.firstOrNull()?.not() ?: true
            else -> false
        }
        return NodeResult.Success(result.toString(), System.currentTimeMillis() - start)
    }

    private suspend fun executeExtractNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val mode = node.config.extractMode ?: return NodeResult.Failure(
            IllegalStateException("缺少 extractMode"), 0
        )
        val source = node.config.inputs.firstOrNull()?.resolve(context.variables) ?: ""
        val result: String = when (mode) {
            ExtractModeDef.REGEX -> {
                val pattern = node.config.expression ?: ""
                Regex(pattern).find(source)?.value ?: node.config.actionConfig["default"] ?: ""
            }
            ExtractModeDef.JSON -> {
                val path = node.config.expression ?: ""
                try {
                    val json = Json.parseToJsonElement(source)
                    var current = json
                    for (seg in path.split(".")) {
                        current = current.jsonObject[seg] ?: return@executeExtractNode NodeResult.Success(
                            "", System.currentTimeMillis() - start
                        )
                    }
                    current.toString()
                } catch (e: Exception) { "" }
            }
            ExtractModeDef.SUBSTRING -> {
                val startIdx = node.config.actionConfig["startIndex"]?.toIntOrNull() ?: 0
                val len = node.config.actionConfig["length"]?.toIntOrNull() ?: source.length
                source.drop(startIdx).take(len)
            }
            ExtractModeDef.CONCAT -> {
                node.config.inputs.joinToString("") { it.resolve(context.variables) }
            }
            ExtractModeDef.RANDOM_INT -> {
                val min = node.config.actionConfig["min"]?.toIntOrNull() ?: 0
                val max = node.config.actionConfig["max"]?.toIntOrNull() ?: 100
                kotlin.random.Random.nextInt(min, max + 1).toString()
            }
            ExtractModeDef.RANDOM_STRING -> {
                val len = node.config.actionConfig["length"]?.toIntOrNull() ?: 16
                val charset = node.config.actionConfig["charset"] ?: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                (1..len).map { charset.random() }.joinToString("")
            }
            ExtractModeDef.JQ_PATH -> {
                // 简化的 jq 路径实现
                node.config.expression?.let { expr ->
                    try {
                        val json = Json.parseToJsonElement(source)
                        var current = json
                        for (seg in expr.removePrefix(".").split(".")) {
                            current = current.jsonObject[seg] ?: return@executeExtractNode NodeResult.Success("", System.currentTimeMillis() - start)
                        }
                        current.toString()
                    } catch (e: Exception) { "" }
                } ?: ""
            }
        }
        return NodeResult.Success(result, System.currentTimeMillis() - start)
    }

    private suspend fun executeFanOutNode(
        node: EnhancedNode,
        context: ExecutionContext,
        spanId: String
    ): NodeResult {
        val start = System.currentTimeMillis()
        val spec = node.config.fanOutSpec ?: return NodeResult.Failure(
            IllegalStateException("缺少 fanOutSpec"), 0
        )
        val itemsExpr = spec.itemsExpression
        val itemsRaw = context.variables[itemsExpr] ?: context.variables["__last_output"]
        val items: List<Any> = when (itemsRaw) {
            is List<*> -> itemsRaw.filterNotNull()
            is String -> itemsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(itemsRaw ?: "")
        }

        val downstreamNodes = context.workflow.getOutgoingConnections(node.id)
            .mapNotNull { context.workflow.getNodeById(it.targetNodeId) }
            .filter { it.type != EnhancedNodeType.FAN_IN }

        val result = parallelExecutor.fanOut(
            items = items,
            maxConcurrency = spec.maxConcurrency,
            failFast = spec.failFast
        ) { idx, item ->
            // 为每个分支执行下游节点
            val branchContext = context.variables.toMutableMap()
            branchContext["__fanout_item"] = item
            branchContext["__fanout_index"] = idx
            for (dn in downstreamNodes) {
                executeNodeCascade(dn, context.copy(variables = branchContext), spanId)
            }
            branchContext["__last_output"]
        }

        context.variables["__fanout_${node.id}_result"] = result.outputs
        return NodeResult.Success(result.outputs, System.currentTimeMillis() - start)
    }

    private suspend fun executeFanInNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val spec = node.config.fanInSpec ?: return NodeResult.Failure(
            IllegalStateException("缺少 fanInSpec"), 0
        )
        // 从上游 fan-out 结果收集
        val upstreamResults = context.nodeResults
            .filterKeys { nodeId ->
                context.workflow.getIncomingConnections(node.id).any { it.sourceNodeId == nodeId }
            }
            .mapNotNull { (id, r) ->
                (r as? NodeResult.Success)?.output?.let { id to it }
            }.toMap()

        val aggregator = when (spec.aggregatorType) {
            AggregatorTypeDef.FIRST -> Aggregators.First
            AggregatorTypeDef.LAST -> Aggregators.Last
            AggregatorTypeDef.ALL -> Aggregators.All
            AggregatorTypeDef.MERGE_BY_KEY -> Aggregators.MergeByKey
            AggregatorTypeDef.MERGE_LIST -> Aggregators.MergeList
            else -> Aggregators.All
        }

        @Suppress("UNCHECKED_CAST")
        val merged = aggregator.merge(upstreamResults as Map<Int, Any?>)
        context.variables["__fanin_${node.id}_result"] = merged
        return NodeResult.Success(merged, System.currentTimeMillis() - start)
    }

    private suspend fun executeLoopNode(
        node: EnhancedNode,
        context: ExecutionContext,
        spanId: String
    ): NodeResult {
        val start = System.currentTimeMillis()
        val spec = node.config.loopSpec ?: return NodeResult.Failure(
            IllegalStateException("缺少 loopSpec"), 0
        )

        val bodyNodes = spec.bodyNodeIds.mapNotNull { context.workflow.getNodeById(it) }
        val loopSpec: LoopSpec = when (spec.loopType) {
            LoopTypeDef.COUNT -> LoopSpec.Count(spec.times ?: 1, spec.maxConcurrency > 1)
            LoopTypeDef.FOR_EACH -> {
                val items = (context.variables[spec.itemsExpression ?: ""] as? List<*>).orEmpty()
                LoopSpec.ForEach(items.filterNotNull(), spec.maxConcurrency)
            }
            LoopTypeDef.WHILE -> LoopSpec.While(
                condition = { _, _ ->
                    val cond = context.variables["__loop_cond_${node.id}"] as? Boolean ?: false
                    cond
                },
                maxIterations = spec.maxIterations
            )
            LoopTypeDef.MAP_REDUCE -> {
                val items = (context.variables[spec.itemsExpression ?: ""] as? List<*>).orEmpty()
                LoopSpec.MapReduce(
                    items = items.filterNotNull(),
                    maxConcurrency = spec.maxConcurrency,
                    reducer = { acc, cur -> listOfNotNull(acc, cur) }
                )
            }
        }

        val result = loopExecutor.execute(loopSpec) { loopCtx ->
            context.variables["__loop_iter"] = loopCtx.iteration
            context.variables["__loop_item"] = loopCtx.item
            for (bn in bodyNodes) {
                executeNodeCascade(bn, context, spanId)
            }
            context.variables["__last_output"]
        }

        context.variables["__loop_${node.id}_result"] = result.outputs
        return NodeResult.Success(result.outputs, System.currentTimeMillis() - start)
    }

    private suspend fun executeSubWorkflowNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val config = node.config.subWorkflowConfig ?: return NodeResult.Failure(
            IllegalStateException("缺少 subWorkflowConfig"), 0
        )
        if (subWorkflowExecutor == null) {
            return NodeResult.Failure(IllegalStateException("未配置子工作流执行器"), 0)
        }

        val invocation = SubWorkflowInvocation(
            subWorkflowId = config.subWorkflowId,
            subWorkflowVersion = config.subWorkflowVersion,
            inputs = config.inputs.mapValues { (_, v) -> v as Any },
            waitForCompletion = config.waitForCompletion,
            timeoutMs = config.timeoutMs,
            inheritContext = config.inheritContext,
            parentThreadId = context.threadId,
            parentNodeId = node.id
        )

        return when (val r = subWorkflowExecutor.invoke(invocation)) {
            is SubWorkflowResult.Completed -> {
                context.variables.putAll(r.outputs)
                NodeResult.Success(r.outputs, System.currentTimeMillis() - start)
            }
            is SubWorkflowResult.TimedOut -> NodeResult.Failure(
                RuntimeException("子工作流超时"), System.currentTimeMillis() - start
            )
            is SubWorkflowResult.Failed -> NodeResult.Failure(
                r.error, System.currentTimeMillis() - start
            )
            is SubWorkflowResult.AsyncStarted -> NodeResult.Success(
                mapOf("subThreadId" to r.subThreadId, "async" to true),
                System.currentTimeMillis() - start
            )
        }
    }

    private suspend fun executeHumanInputNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val config = node.config.humanInputConfig ?: return NodeResult.Failure(
            IllegalStateException("缺少 humanInputConfig"), 0
        )

        val payload = InterruptPayload(
            nodeId = node.id,
            threadId = context.threadId,
            workflowId = context.workflow.id,
            workflowName = context.workflow.name,
            prompt = config.prompt,
            options = config.options,
            timeoutMs = config.timeoutMs
        )

        return try {
            val cmd = ApprovalGatewayHolder.get().awaitApproval(payload)
            context.variables["__approval_${node.id}"] = cmd.reason ?: "approved"
            NodeResult.Success(cmd.reason ?: "approved", System.currentTimeMillis() - start)
        } catch (e: HumanRejectedByUserException) {
            NodeResult.Failure(e, System.currentTimeMillis() - start)
        }
    }

    private suspend fun executeDelayNode(node: EnhancedNode, context: ExecutionContext): NodeResult {
        val start = System.currentTimeMillis()
        val ms = node.config.delayMs ?: 0L
        if (ms > 0) delay(ms)
        return NodeResult.Success("delayed ${ms}ms", System.currentTimeMillis() - start)
    }

    /**
     * 解析参数值（支持 ${nodeId} 引用）
     */
    private fun resolveParameterValue(value: String, context: ExecutionContext): String {
        if (!value.contains("\${")) return value
        var result = value
        val regex = Regex("\\$\\{([^}]+)}")
        regex.findAll(value).forEach { m ->
            val (ref) = m.destructured
            val parts = ref.split(".", limit = 2)
            val v = context.variables["__node_${parts[0]}_output"] ?: context.variables[parts[0]]
            val resolved = if (parts.size == 2 && v is Map<*, *>) v[parts[1]]?.toString() ?: ""
                          else v?.toString() ?: ""
            result = result.replace("\${$ref}", resolved)
        }
        return result
    }

    /**
     * 恢复被中断的执行（从检查点）
     */
    suspend fun resume(threadId: String): ExecutionResult? {
        val cp = checkpointManager.let { mgr ->
            mgr.checkpointer.latest(threadId) ?: return null
        }
        val workflow = EnhancedWorkflow.fromJson(
            // 需要从版本注册表加载，这里简化
            cp.variables.getOrDefault("__workflow_json", "")
        ) ?: return null
        return executeInternal(workflow, emptyMap(), threadId, null, cp.checkpointId)
    }

    /**
     * 恢复所有活跃执行（进程重启后调用）
     */
    suspend fun resumeAll(): List<String> {
        val threads = checkpointManager.checkpointer.activeThreads()
        return threads.mapNotNull { tid -> resume(tid)?.threadId }
    }

    /**
     * 取消执行
     */
    fun cancel(threadId: String): Boolean {
        val job = runningJobs[threadId] ?: return false
        job.cancel()
        return true
    }

    // ============ 执行事件 ============

    sealed class ExecutionEvent {
        data class NodeStarted(val threadId: String, val nodeId: String, val nodeName: String) : ExecutionEvent()
        data class NodeCompleted(val threadId: String, val nodeId: String, val nodeName: String, val success: Boolean, val durationMs: Long) : ExecutionEvent()
        data class WorkflowCompleted(val threadId: String, val success: Boolean, val durationMs: Long) : ExecutionEvent()
        data class WorkflowFailed(val threadId: String, val error: Throwable) : ExecutionEvent()
        data class WorkflowCancelled(val threadId: String, val reason: String) : ExecutionEvent()
        data class CheckpointSaved(val threadId: String, val nodeId: String) : ExecutionEvent()
    }

    sealed class ExecutionState {
        data class Running(val workflowId: String, val progress: Float) : ExecutionState()
        data class Completed(val success: Boolean) : ExecutionState()
        data class Failed(val error: String) : ExecutionState()
    }

    // ============ Builder ============

    class Builder {
        private var tracer: WorkflowTracer = TracerHolder.get()
        private var checkpointer = InMemoryCheckpointer()
        private var retryExecutor = RetryExecutor()
        private var parallelExecutor = ParallelExecutor()
        private var loopExecutor = LoopExecutor()
        private var validator = WorkflowValidator()
        private val actionHandlers = mutableMapOf<String, ActionHandler>()
        private val compensateHandlers = mutableMapOf<String, CompensateHandler>()
        private val triggerHandlers = mutableMapOf<TriggerTypeDef, TriggerHandler>()
        private var subWorkflowExecutor: DelegatingSubWorkflowExecutor? = null
        private var defaultTimeoutMs: Long = 5 * 60_000L

        fun withTracer(tracer: WorkflowTracer) = apply { this.tracer = tracer }
        fun withCheckpointer(cp: InMemoryCheckpointer) = apply { this.checkpointer = cp }
        fun withRetryExecutor(re: RetryExecutor) = apply { this.retryExecutor = re }
        fun withDefaultTimeout(ms: Long) = apply { this.defaultTimeoutMs = ms }
        fun withSubWorkflowExecutor(exec: DelegatingSubWorkflowExecutor) = apply {
            this.subWorkflowExecutor = exec
        }

        fun withActionHandler(actionType: String, handler: ActionHandler) = apply {
            actionHandlers[actionType] = handler
        }

        fun withCompensateHandler(actionType: String, handler: CompensateHandler) = apply {
            compensateHandlers[actionType] = handler
        }

        fun withTriggerHandler(type: TriggerTypeDef, handler: TriggerHandler) = apply {
            triggerHandlers[type] = handler
        }

        fun build(): EnhancedWorkflowExecutor {
            return EnhancedWorkflowExecutor(
                tracer = tracer,
                checkpointManager = CheckpointManager(checkpointer),
                retryExecutor = retryExecutor,
                parallelExecutor = parallelExecutor,
                loopExecutor = loopExecutor,
                validator = validator,
                actionHandlers = actionHandlers.toMap(),
                compensateHandlers = compensateHandlers.toMap(),
                triggerHandlers = triggerHandlers.toMap(),
                subWorkflowExecutor = subWorkflowExecutor,
                defaultTimeoutMs = defaultTimeoutMs
            )
        }
    }

    companion object {
        private const val TAG = "EnhancedWorkflowExecutor"
    }
}
