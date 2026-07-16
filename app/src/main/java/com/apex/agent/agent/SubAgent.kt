package com.apex.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface SubAgent {
    val agentId: String
    val agentType: String
    val displayName: String
    val description: String

    suspend fun execute(task: SubTask): SubTaskResult

    fun canHandle(taskType: String): Boolean {
        return agentType == taskType || agentType == "general"
    }

    fun getExecutionConfig(): AgentExecutionConfig {
        return AgentExecutionConfig()
    }
}

data class AgentExecutionConfig(
    val timeoutMs: Long = 60000,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000,
    val backoffMultiplier: Float = 2.0f,
    val enableCircuitBreaker: Boolean = true,
    val circuitBreakerThreshold: Int = 5,
    val circuitBreakerTimeoutMs: Long = 30000
)

data class AgentMetrics(
    val agentId: String,
    var totalExecutions: Int = 0,
    var successfulExecutions: Int = 0,
    var failedExecutions: Int = 0,
    var totalExecutionTime: Long = 0,
    var averageExecutionTime: Long = 0,
    var timeoutCount: Int = 0,
    var retryCount: Int = 0,
    var circuitBreakerOpenCount: Int = 0,
    var lastExecutionTime: Long = 0,
    var lastFailureTime: Long = 0,
    var consecutiveFailures: Int = 0,
    var isCircuitBreakerOpen: Boolean = false,
    var circuitBreakerOpenedAt: Long = 0
) {
    val successRate: Float
        get() = if (totalExecutions > 0) successfulExecutions.toFloat() / totalExecutions else 0f

    val failureRate: Float
        get() = if (totalExecutions > 0) failedExecutions.toFloat() / totalExecutions else 0f

    fun recordExecution(success: Boolean, executionTime: Long, wasTimeout: Boolean = false, usedRetry: Boolean = false) {
        totalExecutions++
        totalExecutionTime += executionTime
        averageExecutionTime = totalExecutionTime / totalExecutions
        lastExecutionTime = System.currentTimeMillis()

        if (success) {
            successfulExecutions++
            consecutiveFailures = 0
        } else {
            failedExecutions++
            consecutiveFailures++
            lastFailureTime = System.currentTimeMillis()
        }

        if (wasTimeout) timeoutCount++
        if (usedRetry) retryCount++
    }

    fun shouldOpenCircuitBreaker(threshold: Int): Boolean {
        return consecutiveFailures >= threshold
    }

    fun shouldAllowRequest(circuitBreakerTimeoutMs: Long): Boolean {
        if (!isCircuitBreakerOpen) return true

        val timeSinceOpened = System.currentTimeMillis() - circuitBreakerOpenedAt
        if (timeSinceOpened >= circuitBreakerTimeoutMs) {
            isCircuitBreakerOpen = false
            circuitBreakerOpenedAt = 0
            return true
        }
        return false
    }

    fun openCircuitBreaker() {
        isCircuitBreakerOpen = true
        circuitBreakerOpenedAt = System.currentTimeMillis()
        circuitBreakerOpenCount++
    }

    fun reset() {
        totalExecutions = 0
        successfulExecutions = 0
        failedExecutions = 0
        totalExecutionTime = 0
        averageExecutionTime = 0
        timeoutCount = 0
        retryCount = 0
        consecutiveFailures = 0
        isCircuitBreakerOpen = false
        circuitBreakerOpenedAt = 0
    }
}

abstract class BaseSubAgent(
    override val agentId: String,
    override val agentType: String,
    override val displayName: String,
    override val description: String = ""
) : SubAgent

class ResilientSubAgentWrapper(
    private val delegate: SubAgent,
    private val metrics: AgentMetrics = AgentMetrics(delegate.agentId),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : SubAgent by delegate {

    private val _metricsFlow = MutableStateFlow(metrics)
    val metricsFlow: StateFlow<AgentMetrics> = _metricsFlow.asStateFlow()

    override suspend fun execute(task: SubTask): SubTaskResult {
        val config = delegate.getExecutionConfig()

        if (config.enableCircuitBreaker && !metrics.shouldAllowRequest(config.circuitBreakerTimeoutMs)) {
            return SubTaskResult(
                taskId = task.taskId,
                success = false,
                executionTime = 0,
                errorMessage = "Circuit breaker is open",
                errorStack = "Agent ${delegate.agentId} circuit breaker is open. Please try again later."
            )
        }

        var lastException: Exception? = null
        var currentDelay = config.retryDelayMs

        repeat(config.maxRetries) { attempt ->
            val startTime = System.currentTimeMillis()
            var wasTimeout = false

            val result = withTimeoutOrNull(config.timeoutMs) {
                try {
                    delegate.execute(task)
                } catch (e: Exception) {
                    lastException = e
                    null
                }
            }

            val executionTime = System.currentTimeMillis() - startTime

            if (result != null) {
                val usedRetry = attempt > 0
                metrics.recordExecution(result.success, executionTime, false, usedRetry)
                _metricsFlow.value = metrics.copy()

                if (result.success || !config.enableCircuitBreaker) {
                    return result
                }

                if (config.enableCircuitBreaker && metrics.shouldOpenCircuitBreaker(config.circuitBreakerThreshold)) {
                    metrics.openCircuitBreaker()
                    _metricsFlow.value = metrics.copy()
                    return result.copy(
                        errorMessage = "${result.errorMessage ?: ""} Circuit breaker opened after ${metrics.consecutiveFailures} consecutive failures"
                    )
                }
            } else {
                wasTimeout = true
                metrics.recordExecution(false, executionTime, wasTimeout, attempt > 0)
                _metricsFlow.value = metrics.copy()
            }

            if (attempt < config.maxRetries - 1) {
                delay(currentDelay)
                currentDelay = (currentDelay * config.backoffMultiplier).toLong()
            }
        }

        return SubTaskResult(
            taskId = task.taskId,
            success = false,
            executionTime = metrics.averageExecutionTime,
            errorMessage = lastException?.message ?: "Max retries exceeded",
            errorStack = lastException?.stackTraceToString() ?: "Execution failed after ${config.maxRetries} attempts"
        )
    }

    fun getMetrics(): AgentMetrics = metrics

    fun resetCircuitBreaker() {
        metrics.isCircuitBreakerOpen = false
        metrics.circuitBreakerOpenedAt = 0
        metrics.consecutiveFailures = 0
        _metricsFlow.value = metrics.copy()
    }

    fun resetAllMetrics() {
        metrics.reset()
        _metricsFlow.value = metrics.copy()
    }
}

class DynamicAgentRegistry {

    private val _agents = MutableStateFlow<Map<String, SubAgent>>(emptyMap())
    val agents: StateFlow<Map<String, SubAgent>> = _agents.asStateFlow()

    private val _agentMetrics = MutableStateFlow<Map<String, AgentMetrics>>(emptyMap())
    val agentMetrics: StateFlow<Map<String, AgentMetrics>> = _agentMetrics.asStateFlow()

    fun registerAgent(agent: SubAgent): Boolean {
        val currentAgents = _agents.value.toMutableMap()
        if (agent.agentId in currentAgents) {
            return false
        }

        currentAgents[agent.agentId] = ResilientSubAgentWrapper(agent)
        _agents.value = currentAgents

        val currentMetrics = _agentMetrics.value.toMutableMap()
        currentMetrics[agent.agentId] = AgentMetrics(agent.agentId)
        _agentMetrics.value = currentMetrics

        return true
    }

    fun unregisterAgent(agentId: String): Boolean {
        val currentAgents = _agents.value.toMutableMap()
        if (agentId !in currentAgents) {
            return false
        }

        currentAgents.remove(agentId)
        _agents.value = currentAgents

        val currentMetrics = _agentMetrics.value.toMutableMap()
        currentMetrics.remove(agentId)
        _agentMetrics.value = currentMetrics

        return true
    }

    fun getAgent(agentId: String): SubAgent? {
        return _agents.value[agentId]
    }

    fun getAgentByType(agentType: String): SubAgent? {
        return _agents.value.values.find { it.agentType == agentType }
    }

    fun getAllAgents(): List<SubAgent> {
        return _agents.value.values.toList()
    }

    fun getAgentTypes(): Set<String> {
        return _agents.value.values.map { it.agentType }.toSet()
    }

    fun getMetrics(agentId: String): AgentMetrics? {
        return _agentMetrics.value[agentId]
    }

    fun getAllMetrics(): Map<String, AgentMetrics> {
        return _agentMetrics.value
    }

    fun resetMetrics(agentId: String? = null) {
        if (agentId != null) {
            _agentMetrics.value[agentId]?.reset()
            _agentMetrics.value = _agentMetrics.value.toMutableMap()
        } else {
            _agentMetrics.value.values.forEach { it.reset() }
            _agentMetrics.value = _agentMetrics.value.toMutableMap()
        }
    }

    fun resetCircuitBreaker(agentId: String) {
        (_agents.value[agentId] as? ResilientSubAgentWrapper)?.resetCircuitBreaker()
    }

    fun clear() {
        _agents.value = emptyMap()
        _agentMetrics.value = emptyMap()
    }

    fun size(): Int = _agents.value.size
}
