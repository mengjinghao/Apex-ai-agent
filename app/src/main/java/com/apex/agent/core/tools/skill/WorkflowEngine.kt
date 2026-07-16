package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class WorkflowEngine private constructor() {

    companion object {
        private const val TAG = "WorkflowEngine"
        private const val MAX_CONCURRENT_EXECUTIONS = 10
        private const val NODE_EXECUTION_TIMEOUT_MS = 300000L

        @Volatile private var INSTANCE: WorkflowEngine? = null

        fun getInstance(): WorkflowEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkflowEngine().also { INSTANCE = it }
            }
        }
    }

    sealed class ExecutionState {
        data object Idle : ExecutionState()
        data class Running(val progress: Float) : ExecutionState()
        data class Completed(val success: Boolean, val result: Any) : ExecutionState()
        data class Failed(val error: String) : ExecutionState()
    }

    data class ExecutionContext(
        val workflowId: String,
        val executionId: String,
        val startTime: Long = System.currentTimeMillis(),
        val nodeOutputs: MutableMap<String, Any> = mutableMapOf(),
        val nodeStates: MutableMap<String, NodeExecutionState> = mutableMapOf(),
        val currentNodeId: String? = null,
        var cancelled: Boolean = false
    ) {
        fun getNodeOutput(nodeId: String): Any? = nodeOutputs[nodeId]

        fun setNodeOutput(nodeId: String, output: Any) {
            nodeOutputs[nodeId] = output
        }

        fun getNodeState(nodeId: String): NodeExecutionState =
            nodeStates.getOrPut(nodeId) { NodeExecutionState.Pending }
    }

    enum class NodeExecutionState {
        Pending,
        Running,
        Success,
        Failed,
        Skipped
    }

    data class ExecutionResult(
        val executionId: String,
        val workflowId: String,
        val success: Boolean,
        val startTime: Long,
        val endTime: Long,
        val totalExecutionTimeMs: Long,
        val nodeResults: Map<String, NodeResult>,
        val errorMessage: String? = null
    ) {
        val nodeCount: Int get() = nodeResults.size
        val successCount: Int get() = nodeResults.values.count { it.success }
        val failedCount: Int get() = nodeResults.values.count { !it.success }
    }

    data class NodeResult(
        val nodeId: String,
        val nodeName: String,
        val nodeType: NodeType,
        val success: Boolean,
        val executionTimeMs: Long,
        val output: Any? = null,
        val errorMessage: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val workflows = ConcurrentHashMap<String, WorkflowDefinition>()
    private val runningExecutions = ConcurrentHashMap<String, ExecutionContext>()
    private val executionHistory = ConcurrentHashMap<String, List<ExecutionResult>>()

    private val _executionState = MutableStateFlow<Map<String, ExecutionState>>(emptyMap())
    val executionState: StateFlow<Map<String, ExecutionState>> = _executionState.asStateFlow()

    private val _executionEvents = MutableSharedFlow<ExecutionEvent>()
    val executionEvents: SharedFlow<ExecutionEvent> = _executionEvents.asSharedFlow()

    private val eventBus = SkillEventBus.getInstance()

    private val toolExecutor: WorkflowToolExecutor = WorkflowToolExecutor()

    private val statsTotalExecutions = AtomicLong(0)
    private val statsSuccessfulExecutions = AtomicLong(0)
    private val statsFailedExecutions = AtomicLong(0)
    private val statsTotalExecutionTime = AtomicLong(0)

    sealed class ExecutionEvent {
        data class Started(val workflowId: String, val executionId: String) : ExecutionEvent()
        data class NodeStarted(val workflowId: String, val executionId: String, val nodeId: String) : ExecutionEvent()
        data class NodeCompleted(val workflowId: String, val executionId: String, val nodeId: String, val success: Boolean) : ExecutionEvent()
        data class Completed(val workflowId: String, val executionId: String, val success: Boolean) : ExecutionEvent()
        data class Cancelled(val workflowId: String, val executionId: String) : ExecutionEvent()
        data class Failed(val workflowId: String, val executionId: String, val error: String) : ExecutionEvent()
    }

    suspend fun registerWorkflow(workflow: WorkflowDefinition): RegistrationResult {
        val validation = workflow.validate()

        if (!validation.isValid) {
            AppLogger.e(TAG, "Workflow validation failed: ${validation.errors}")
            return RegistrationResult.Invalid(validation.errors, validation.warnings)
        }

        workflows[workflow.id] = workflow
        AppLogger.i(TAG, "Workflow registered: ${workflow.name} [${workflow.id}]")

        if (validation.warnings.isNotEmpty()) {
            AppLogger.w(TAG, "Workflow warnings: ${validation.warnings}")
        }

        return RegistrationResult.Success(validation.warnings)
    }

    fun unregisterWorkflow(workflowId: String): Boolean {
        val removed = workflows.remove(workflowId) != null
        if (removed) {
            AppLogger.i(TAG, "Workflow unregistered: ${workflowId}")
        }
        return removed
    }

    fun getWorkflow(workflowId: String): WorkflowDefinition? = workflows[workflowId]

    fun getAllWorkflows(): List<WorkflowDefinition> = workflows.values.toList()

    fun isWorkflowRegistered(workflowId: String): Boolean = workflows.containsKey(workflowId)

    suspend fun executeWorkflow(
        workflowId: String,
        triggerType: String = "manual",
        initialContext: Map<String, Any> = emptyMap()
    ): ExecutionResult? {
        val workflow = workflows[workflowId] ?: run {
            AppLogger.e(TAG, "Workflow not found: ${workflowId}")
            return null
        }

        if (!workflow.enabled) {
            AppLogger.w(TAG, "Workflow is disabled: ${workflowId}")
            return null
        }

        if (runningExecutions.size >= MAX_CONCURRENT_EXECUTIONS) {
            AppLogger.w(TAG, "Max concurrent executions reached")
            return null
        }

        val executionId = "exec_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val startTime = System.currentTimeMillis()

        val context = ExecutionContext(
            workflowId = workflowId,
            executionId = executionId
        )
        context.nodeOutputs.putAll(initialContext)

        runningExecutions[executionId] = context
        updateExecutionState(executionId, ExecutionState.Running(0f))

        scope.launch {
            eventBus.emit(SkillEventBus.SkillEvent.WorkflowTriggered(
                source = TAG,
                workflowId = workflowId,
                workflowName = workflow.name,
                triggerType = triggerType
            ))
        }

        _executionEvents.emit(ExecutionEvent.Started(workflowId, executionId))

        try {
            val triggerNodes = workflow.getTriggerNodes()
            if (triggerNodes.isEmpty()) {
                throw IllegalStateException("No trigger node found in workflow")
            }

            val entryNode = triggerNodes.first()
            val result = executeNode(workflow, entryNode, context)

            val endTime = System.currentTimeMillis()
            val success = !context.cancelled && result.isSuccess

            val executionResult = ExecutionResult(
                executionId = executionId,
                workflowId = workflowId,
                success = success,
                startTime = startTime,
                endTime = endTime,
                totalExecutionTimeMs = endTime - startTime,
                nodeResults = context.nodeStates.mapValues { (nodeId, state) ->
                    val node = workflow.getNodeById(nodeId)
                    val output = context.getNodeOutput(nodeId)
                    NodeResult(
                        nodeId = nodeId,
                        nodeName = node?.name ?: "Unknown",
                        nodeType = node?.type ?: NodeType.EXECUTE,
                        success = state == NodeExecutionState.Success,
                        executionTimeMs = 0,
                        output = output
                    )
                },
                errorMessage = if (success) null else "Execution failed"
            )

            runningExecutions.remove(executionId)
            updateExecutionState(executionId, ExecutionState.Completed(success, executionResult))

            statsTotalExecutions.incrementAndGet()
            if (success) {
                statsSuccessfulExecutions.incrementAndGet()
            } else {
                statsFailedExecutions.incrementAndGet()
            }
            statsTotalExecutionTime.addAndGet(executionResult.totalExecutionTimeMs)

            val history = executionHistory.getOrPut(workflowId) { emptyList() }.toMutableList()
            history.add(executionResult)
            if (history.size > 100) {
                history.removeAt(0)
            }
            executionHistory[workflowId] = history

            scope.launch {
                eventBus.emit(SkillEventBus.SkillEvent.WorkflowCompleted(
                    source = TAG,
                    workflowId = workflowId,
                    success = success,
                    totalExecutionTimeMs = executionResult.totalExecutionTimeMs
                ))
            }

            _executionEvents.emit(ExecutionEvent.Completed(workflowId, executionId, success))

            return executionResult

        } catch (e: Exception) {
            AppLogger.e(TAG, "Workflow execution failed: ${executionId}", e)

            runningExecutions.remove(executionId)
            updateExecutionState(executionId, ExecutionState.Failed(e.message ?: "Unknown error"))

            statsTotalExecutions.incrementAndGet()
            statsFailedExecutions.incrementAndGet()

            _executionEvents.emit(ExecutionEvent.Failed(workflowId, executionId, e.message ?: "Unknown error"))

            return ExecutionResult(
                executionId = executionId,
                workflowId = workflowId,
                success = false,
                startTime = startTime,
                endTime = System.currentTimeMillis(),
                totalExecutionTimeMs = System.currentTimeMillis() - startTime,
                nodeResults = emptyMap(),
                errorMessage = e.message
            )
        }
    }

    private suspend fun executeNode(
        workflow: WorkflowDefinition,
        node: WorkflowNode,
        context: ExecutionContext
    ): Result<Any> {
        if (context.cancelled) {
            return Result.failure(CancelledException("Workflow execution was cancelled"))
        }

        context.currentNodeId = node.id
        context.nodeStates[node.id] = NodeExecutionState.Running

        _executionEvents.emit(ExecutionEvent.NodeStarted(workflow.id, context.executionId, node.id))

        scope.launch {
            eventBus.emit(SkillEventBus.SkillEvent.WorkflowNodeExecuted(
                source = TAG,
                workflowId = workflow.id,
                nodeId = node.id,
                nodeType = node.type.name,
                success = true,
                executionTimeMs = 0
            ))
        }

        val startTime = System.currentTimeMillis()

        return try {
            val result = withContext(Dispatchers.Default) {
                when (node.type) {
                    NodeType.TRIGGER -> executeTriggerNode(node, context)
                    NodeType.EXECUTE -> executeExecuteNode(node, context)
                    NodeType.CONDITION -> executeConditionNode(node, context)
                    NodeType.LOGIC -> executeLogicNode(node, context)
                    NodeType.EXTRACT -> executeExtractNode(node, context)
                }
            }

            val executionTime = System.currentTimeMillis() - startTime

            context.nodeStates[node.id] = NodeExecutionState.Success
            context.setNodeOutput(node.id, result.getOrNull() ?: "null")

            updateExecutionProgress(context)

            _executionEvents.emit(ExecutionEvent.NodeCompleted(workflow.id, context.executionId, node.id, true))

            result

        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime

            context.nodeStates[node.id] = NodeExecutionState.Failed

            _executionEvents.emit(ExecutionEvent.NodeCompleted(workflow.id, context.executionId, node.id, false))

            AppLogger.e(TAG, "Node execution failed: ${node.id}", e)

            Result.failure(e)
        }
    }

    private suspend fun executeTriggerNode(node: WorkflowNode, context: ExecutionContext): Result<Any> {
        val triggerConfig = node.config.triggerConfig ?: return Result.success("Trigger executed")

        val output = when (triggerConfig.triggerType) {
            TriggerType.MANUAL -> "Manual trigger"
            TriggerType.SCHEDULE -> {
                val scheduleConfig = triggerConfig.scheduleConfig
                "Scheduled trigger: ${scheduleConfig?.scheduleType}"
            }
            TriggerType.TASKER -> {
                val taskerConfig = triggerConfig.taskerConfig
                "Tasker trigger: ${taskerConfig?.command}"
            }
            TriggerType.INTENT -> {
                val intentConfig = triggerConfig.intentConfig
                "Intent trigger: ${intentConfig?.action}"
            }
            TriggerType.SPEECH -> {
                val speechConfig = triggerConfig.speechConfig
                "Speech trigger: ${speechConfig?.pattern}"
            }
        }

        return Result.success(output)
    }

    private suspend fun executeExecuteNode(node: WorkflowNode, context: ExecutionContext): Result<Any> {
        val actionType = node.config.actionType ?: return Result.failure(IllegalArgumentException("actionType is required"))
        val actionConfig = node.config.actionConfig ?: emptyMap()

        val resolvedConfig = resolveParameters(actionConfig, context)

        return try {
            val result = toolExecutor.execute(actionType, resolvedConfig)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeConditionNode(node: WorkflowNode, context: ExecutionContext): Result<Boolean> {
        val left = node.config.left ?: return Result.failure(IllegalArgumentException("left value is required"))
        val operator = node.config.operator ?: return Result.failure(IllegalArgumentException("operator is required"))
        val right = node.config.right

        val leftValue = resolveParameterValue(left, context)
        val rightValue = right?.let { resolveParameterValue(it, context) }

        val result = evaluateCondition(leftValue, operator, rightValue)

        return Result.success(result)
    }

    private suspend fun executeLogicNode(node: WorkflowNode, context: ExecutionContext): Result<Boolean> {
        val operator = node.config.operator ?: return Result.failure(IllegalArgumentException("operator is required"))
        val inputs = node.config.inputs ?: return Result.failure(IllegalArgumentException("inputs are required"))

        if (inputs.isEmpty()) {
            return Result.success(true)
        }

        val resolvedInputs = inputs.map { resolveParameterValue(it, context) }.map { it.toBoolean() }

        val result = when (operator.uppercase()) {
            "AND" -> resolvedInputs.all { it }
            "OR" -> resolvedInputs.any { it }
            else -> return Result.failure(IllegalArgumentException("Unknown operator: ${operator}"))
        }

        return Result.success(result)
    }

    private suspend fun executeExtractNode(node: WorkflowNode, context: ExecutionContext): Result<Any> {
        val mode = node.config.mode ?: return Result.failure(IllegalArgumentException("mode is required"))

        return try {
            val result = when (mode) {
                ExtractMode.REGEX -> extractRegex(node, context)
                ExtractMode.JSON -> extractJson(node, context)
                ExtractMode.SUB -> extractSubstring(node, context)
                ExtractMode.CONCAT -> extractConcat(node, context)
                ExtractMode.RANDOM_INT -> generateRandomInt(node, context)
                ExtractMode.RANDOM_STRING -> generateRandomString(node, context)
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractRegex(node: WorkflowNode, context: ExecutionContext): String {
        val source = node.config.source ?: throw IllegalArgumentException("source is required for REGEX mode")
        val expression = node.config.expression ?: throw IllegalArgumentException("expression is required for REGEX mode")
        val group = node.config.group ?: 0
        val defaultValue = node.config.defaultValue

        val sourceValue = resolveParameterValue(source, context)
        val regex = Regex(expression)

        return regex.find(sourceValue)?.groupValues?.getOrNull(group) ?: defaultValue ?: ""
    }

    private fun extractJson(node: WorkflowNode, context: ExecutionContext): String {
        val source = node.config.source ?: throw IllegalArgumentException("source is required for JSON mode")
        val expression = node.config.expression ?: throw IllegalArgumentException("expression is required for JSON mode")
        val defaultValue = node.config.defaultValue

        val sourceValue = resolveParameterValue(source, context)

        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(sourceValue)
            json.toString()
        } catch (e: Exception) {
            defaultValue ?: ""
        }
    }

    private fun extractSubstring(node: WorkflowNode, context: ExecutionContext): String {
        val source = node.config.source ?: throw IllegalArgumentException("source is required for SUB mode")
        val startIndex = node.config.startIndex ?: 0
        val length = node.config.length ?: -1
        val defaultValue = node.config.defaultValue

        val sourceValue = resolveParameterValue(source, context)

        return try {
            if (length < 0) {
                sourceValue.substring(startIndex)
            } else {
                sourceValue.substring(startIndex, minOf(startIndex + length, sourceValue.length))
            }
        } catch (e: Exception) {
            defaultValue ?: ""
        }
    }

    private fun extractConcat(node: WorkflowNode, context: ExecutionContext): String {
        val others = node.config.others ?: emptyList()

        return others.joinToString("") { resolveParameterValue(it, context) }
    }

    private fun generateRandomInt(node: WorkflowNode, context: ExecutionContext): String {
        val min = node.config.randomMin ?: 0
        val max = node.config.randomMax ?: Int.MAX_VALUE
        val useFixed = node.config.useFixed ?: false
        val fixedValue = node.config.fixedValue

        return if (useFixed && fixedValue != null) {
            fixedValue
        } else {
            (min + Math.random() * (max - min)).toInt().toString()
        }
    }

    private fun generateRandomString(node: WorkflowNode, context: ExecutionContext): String {
        val length = node.config.randomStringLength ?: 8
        val charset = node.config.randomStringCharset ?: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val useFixed = node.config.useFixed ?: false
        val fixedValue = node.config.fixedValue

        return if (useFixed && fixedValue != null) {
            fixedValue
        } else {
            (1..length).map { charset[(Math.random() * charset.length).toInt()] }.joinToString("")
        }
    }

    private suspend fun resolveParameters(
        config: Map<String, ParameterValue>,
        context: ExecutionContext
    ): Map<String, Any> {
        return config.mapValues { (_, value) ->
            resolveParameterValue(value, context)
        }
    }

    private fun resolveParameterValue(value: ParameterValue, context: ExecutionContext): String {
        return when (value) {
            is ParameterValue.StaticValue -> value.value
            is ParameterValue.NodeReference -> {
                context.getNodeOutput(value.nodeId)?.toString() ?: ""
            }
        }
    }

    private fun evaluateCondition(leftValue: String, operator: String, rightValue: String): Boolean {
        val left = leftValue.toDoubleOrNull()
        val right = rightValue?.toDoubleOrNull()

        return when (operator.uppercase()) {
            "EQ", "==" -> leftValue == rightValue
            "NE", "!=" -> leftValue != rightValue
            "GT", ">" -> {
                if (left != null && right != null) left > right else leftValue > (rightValue ?: "")
            }
            "GTE", ">=" -> {
                if (left != null && right != null) left >= right else leftValue >= (rightValue ?: "")
            }
            "LT", "<" -> {
                if (left != null && right != null) left < right else leftValue < (rightValue ?: "")
            }
            "LTE", "<=" -> {
                if (left != null && right != null) left <= right else leftValue <= (rightValue ?: "")
            }
            "CONTAINS" -> leftValue.contains(rightValue ?: "")
            "NOT_CONTAINS" -> !leftValue.contains(rightValue ?: "")
            "IN" -> rightValue?.split(",")?.map { it.trim() }?.contains(leftValue) ?: false
            "NOT_IN" -> !(rightValue?.split(",")?.map { it.trim() }?.contains(leftValue) ?: false)
            else -> false
        }
    }

    private suspend fun processNextNodes(
        workflow: WorkflowDefinition,
        currentNode: WorkflowNode,
        context: ExecutionContext,
        lastResult: Result<Any>
    ) {
        val outgoingConnections = workflow.getOutgoingConnections(currentNode.id)

        for (connection in outgoingConnections) {
            val condition = ConnectionCondition.fromString(connection.condition.name)

            val shouldProceed = when {
                lastResult.isSuccess && (condition == ConnectionCondition.OnSuccess || condition == ConnectionCondition.TRUE) -> true
                lastResult.isFailure && condition == ConnectionCondition.OnError -> true
                condition == ConnectionCondition.TRUE && lastResult.getOrNull() == true -> true
                condition == ConnectionCondition.FALSE && lastResult.getOrNull() == false -> true
                else -> false
            }

            if (shouldProceed) {
                val nextNode = workflow.getNodeById(connection.targetNodeId)
                if (nextNode != null && context.getNodeState(nextNode.id) == NodeExecutionState.Pending) {
                    executeNode(workflow, nextNode, context)
                }
            } else {
                context.nodeStates[connection.targetNodeId] = NodeExecutionState.Skipped
            }
        }
    }

    private fun updateExecutionProgress(context: ExecutionContext) {
        val workflow = workflows[context.workflowId] ?: return
        val totalNodes = workflow.nodes.size
        val completedNodes = context.nodeStates.count { it.value != NodeExecutionState.Pending && it.value != NodeExecutionState.Running }
        val progress = if (totalNodes > 0) completedNodes.toFloat() / totalNodes else 0f

        updateExecutionState(context.executionId, ExecutionState.Running(progress))
    }

    private fun updateExecutionState(executionId: String, state: ExecutionState) {
        scope.launch {
            mutex.withLock {
                val currentStates = _executionState.value.toMutableMap()
                currentStates[executionId] = state
                _executionState.value = currentStates
            }
        }
    }

    fun getExecutionState(executionId: String): ExecutionState? = _executionState.value[executionId]

    fun getRunningExecutions(): Map<String, ExecutionContext> = runningExecutions.toMap()

    fun getExecutionHistory(workflowId: String): List<ExecutionResult> = executionHistory[workflowId] ?: emptyList()

    fun getStats(): EngineStats {
        val avgExecutionTime = if (statsTotalExecutions.get() > 0) {
            statsTotalExecutionTime.get() / statsTotalExecutions.get()
        } else 0L

        return EngineStats(
            totalWorkflows = workflows.size,
            runningExecutions = runningExecutions.size,
            totalExecutions = statsTotalExecutions.get(),
            successfulExecutions = statsSuccessfulExecutions.get(),
            failedExecutions = statsFailedExecutions.get(),
            averageExecutionTimeMs = avgExecutionTime
        )
    }

    data class EngineStats(
        val totalWorkflows: Int,
        val runningExecutions: Int,
        val totalExecutions: Long,
        val successfulExecutions: Long,
        val failedExecutions: Long,
        val averageExecutionTimeMs: Long
    )

    data class RegistrationResult(
        val isSuccess: Boolean,
        val warnings: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        companion object {
            fun Success(warnings: List<String> = emptyList()) = RegistrationResult(true, warnings)
            fun Invalid(errors: List<String>, warnings: List<String> = emptyList()) = RegistrationResult(false, warnings, errors)
        }
    }

    class CancelledException(message: String) : Exception(message)

    suspend fun cancelExecution(executionId: String): Boolean {
        val context = runningExecutions[executionId] ?: return false
        context.cancelled = true

        _executionEvents.emit(ExecutionEvent.Cancelled(context.workflowId, executionId))
        AppLogger.i(TAG, "Execution cancelled: ${executionId}")

        return true
    }
}
