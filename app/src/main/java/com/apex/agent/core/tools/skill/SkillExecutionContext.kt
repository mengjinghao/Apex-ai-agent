package com.apex.agent.core.tools.skill

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class SkillExecutionContext(
    val contextId: String,
    val parentContextId: String? = null
) {

    companion object {
        private val contextCounter = AtomicLong(0)
        fun generateContextId(): String {
            return "ctx-${System.currentTimeMillis()}-${contextCounter.incrementAndGet()}"
        }
    }

    enum class ContextState {
        CREATED,
        INITIALIZED,
        RUNNING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        ERROR
    }

    data class ExecutionMetrics(
        val totalTasks: Int = 0,
        val completedTasks: Int = 0,
        val failedTasks: Int = 0,
        val cancelledTasks: Int = 0,
        val totalDurationMs: Long = 0,
        val averageTaskDurationMs: Long = 0,
        val peakMemoryUsageMb: Long = 0,
        val cpuTimeMs: Long = 0
    )

    data class ContextConfig(
        val maxConcurrentTasks: Int = 32,
        val maxQueueSize: Int = 1000,
        val taskTimeoutMs: Long = 120000L,
        val enableRetry: Boolean = true,
        val maxRetries: Int = 10,
        val retryDelayMs: Long = 1000L,
        val enableAdaptiveTimeout: Boolean = true,
        val enableCircuitBreaker: Boolean = true
    )
        private var state = ContextState.CREATED
    private val startTime = AtomicLong(0L)
        private val endTime = AtomicLong(0L)
        private val taskMetrics = ConcurrentHashMap<String, TaskMetrics>()
        private val childContexts = ConcurrentHashMap<String, SkillExecutionContext>()
        private val sharedData = ConcurrentHashMap<String, Any>()
        private val stateListeners = mutableListOf<StateListener>()
        var config: ContextConfig = ContextConfig()
        private set

    var error: String? = null
        private set

    interface StateListener {
        fun onStateChanged(oldState: ContextState, newState: ContextState)
    }
        fun initialize(config: ContextConfig = ContextConfig()) {
        this.config = config
        state = ContextState.INITIALIZED
        notifyStateChange(ContextState.CREATED, ContextState.INITIALIZED)
    }
        fun start() {
        if (state != ContextState.INITIALIZED && state != ContextState.PAUSED) {
            throw IllegalStateException("Cannot start context in state: ${state}")
        }
        startTime.set(System.currentTimeMillis())
        state = ContextState.RUNNING
        notifyStateChange(ContextState.INITIALIZED, ContextState.RUNNING)
    }
        fun pause() {
        if (state != ContextState.RUNNING) {
            throw IllegalStateException("Cannot pause context in state: ${state}")
        }
        state = ContextState.PAUSED
        notifyStateChange(ContextState.RUNNING, ContextState.PAUSED)
    }
        fun resume() {
        if (state != ContextState.PAUSED) {
            throw IllegalStateException("Cannot resume context in state: ${state}")
        }
        state = ContextState.RUNNING
        notifyStateChange(ContextState.PAUSED, ContextState.RUNNING)
    }
        fun complete() {
        endTime.set(System.currentTimeMillis())
        state = ContextState.COMPLETED
        notifyStateChange(ContextState.RUNNING, ContextState.COMPLETED)
    }
        fun cancel() {
        endTime.set(System.currentTimeMillis())
        state = ContextState.CANCELLED
        notifyStateChange(ContextState.RUNNING, ContextState.CANCELLED)
    }
        fun setError(errorMessage: String) {
        this.error = errorMessage
        state = ContextState.ERROR
        endTime.set(System.currentTimeMillis())
        notifyStateChange(ContextState.RUNNING, ContextState.ERROR)
    }
        fun addStateListener(listener: StateListener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener)
        }
    }
        fun removeStateListener(listener: StateListener) {
        stateListeners.remove(listener)
    }
        private fun notifyStateChange(oldState: ContextState, newState: ContextState) {
        stateListeners.forEach { listener ->
            try {
                listener.onStateChanged(oldState, newState)
            } catch (e: Exception) {
                // Ignore listener errors
            }
        }
    }
        fun recordTaskStart(taskId: String) {
        taskMetrics[taskId] = TaskMetrics(
            taskId = taskId,
            startTime = System.currentTimeMillis(),
            state = TaskState.RUNNING
        )
    }
        fun recordTaskCompletion(taskId: String, success: Boolean, result: Any? = null) {
        val metrics = taskMetrics[taskId]
        if (metrics != null) {
            val endTime = System.currentTimeMillis()
            taskMetrics[taskId] = metrics.copy(
                endTime = endTime,
                durationMs = endTime - metrics.startTime,
                state = if (success) TaskState.COMPLETED else TaskState.FAILED,
                result = result
            )
        }
    }
        fun recordTaskCancellation(taskId: String) {
        val metrics = taskMetrics[taskId]
        if (metrics != null) {
            val endTime = System.currentTimeMillis()
            taskMetrics[taskId] = metrics.copy(
                endTime = endTime,
                durationMs = endTime - metrics.startTime,
                state = TaskState.CANCELLED
            )
        }
    }
        fun getTaskMetrics(taskId: String): TaskMetrics? {
        return taskMetrics[taskId]
    }
        fun getAllTaskMetrics(): Map<String, TaskMetrics> {
        return taskMetrics.toMap()
    }
        fun addChildContext(childContext: SkillExecutionContext) {
        childContexts[childContext.contextId] = childContext
    }
        fun removeChildContext(contextId: String) {
        childContexts.remove(contextId)
    }
        fun getChildContexts(): Map<String, SkillExecutionContext> {
        return childContexts.toMap()
    }
        fun putSharedData(key: String, value: Any) {
        sharedData[key] = value
    }
        fun getSharedData(key: String): Any? {
        return sharedData[key]
    }
        fun removeSharedData(key: String) {
        sharedData.remove(key)
    }
        fun getSharedDataKeys(): Set<String> {
        return sharedData.keys.toSet()
    }
        fun getState(): ContextState {
        return state
    }
        fun getStartTime(): Long {
        return startTime.get()
    }
        fun getEndTime(): Long {
        return endTime.get()
    }
        fun getDurationMs(): Long {
        val start = startTime.get()
        val end = endTime.get()
        if (start == 0L) return 0
        return if (end == 0L) System.currentTimeMillis() - start else end - start
    }
        fun getMetrics(): ExecutionMetrics {
        val completed = taskMetrics.values.count { it.state == TaskState.COMPLETED }
        val failed = taskMetrics.values.count { it.state == TaskState.FAILED }
        val cancelled = taskMetrics.values.count { it.state == TaskState.CANCELLED }
        val totalDuration = taskMetrics.values
            .filter { it.endTime > 0 }
            .sumOf { it.durationMs }
        val avgDuration = if (completed > 0) totalDuration / completed else 0L

        val peakMemory = taskMetrics.values
            .map { it.memoryUsageMb }
            .maxOrNull() ?: 0L

        val totalCpuTime = taskMetrics.values.sumOf { it.cpuTimeMs }
        return ExecutionMetrics(
            totalTasks = taskMetrics.size,
            completedTasks = completed,
            failedTasks = failed,
            cancelledTasks = cancelled,
            totalDurationMs = totalDuration,
            averageTaskDurationMs = avgDuration,
            peakMemoryUsageMb = peakMemory,
            cpuTimeMs = totalCpuTime
        )
    }
        fun isActive(): Boolean {
        return state == ContextState.RUNNING || state == ContextState.PAUSED
    }
        fun isCompleted(): Boolean {
        return state == ContextState.COMPLETED || state == ContextState.CANCELLED || state == ContextState.ERROR
    }

    data class TaskMetrics(
        val taskId: String,
        val startTime: Long,
        val endTime: Long = 0,
        val durationMs: Long = 0,
        val state: TaskState = TaskState.PENDING,
        val result: Any? = null,
        val memoryUsageMb: Long = 0,
        val cpuTimeMs: Long = 0
    )

    enum class TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
        fun getActiveTaskCount(): Int {
        return taskMetrics.values.count { it.state == TaskState.RUNNING }
    }
        fun getPendingTaskCount(): Int {
        return taskMetrics.values.count { it.state == TaskState.PENDING }
    }
        fun getProgress(): Float {
        val total = taskMetrics.size
        if (total == 0) return 0f

        val completed = taskMetrics.values.count {
            it.state == TaskState.COMPLETED || it.state == TaskState.FAILED || it.state == TaskState.CANCELLED
        }
        return completed.toFloat() / total
    }
        class ContextBuilder {
        private var parentContextId: String? = null
        private var config: ContextConfig = ContextConfig()
        fun setParentContext(parentContextId: String): ContextBuilder {
            this.parentContextId = parentContextId
            return this
        }
        fun setConfig(config: ContextConfig): ContextBuilder {
            this.config = config
            return this
        }
        fun build(): SkillExecutionContext {
            val context = SkillExecutionContext(
                contextId = generateContextId(),
                parentContextId = parentContextId
            )
            context.initialize(config)
        return context
        }
    }

    companion object {
        fun builder(): ContextBuilder {
            return ContextBuilder()
        }
    }
}