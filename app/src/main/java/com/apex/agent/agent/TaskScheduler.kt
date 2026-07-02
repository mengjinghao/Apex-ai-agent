package com.apex.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class TaskScheduler(
    private val subAgents: List<SubAgent> = emptyList(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrentTasks: Int = 5,
    private val taskTimeoutMs: Long = 300000
) {
    private val _taskState = MutableStateFlow<TaskState>(TaskState.Idle)
    val taskState: StateFlow<TaskState> = _taskState.asStateFlow()

    private val agentRegistry = DynamicAgentRegistry()
    private val taskCounter = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher.limitedParallelism(maxConcurrentTasks))

    private val agentPool: MutableMap<String, SubAgent> by lazy {
        subAgents.associateBy { it.agentType }.toMutableMap()
    }
    private val semaphore = Semaphore(maxConcurrentTasks)

    init {
        subAgents.forEach { agent -> agentRegistry.registerAgent(agent) }
    }

    fun registerAgent(agent: SubAgent): Boolean {
        val result = agentRegistry.registerAgent(agent)
        if (result) agentPool[agent.agentType] = agent
        return result
    }

    fun unregisterAgent(agentId: String): Boolean {
        val agent = agentRegistry.getAgent(agentId)
        if (agent != null) agentPool.remove(agent.agentType)
        return agentRegistry.unregisterAgent(agentId)
    }

    suspend fun executeComplexTask(
        mainTask: MainTask,
        subtaskStrategy: SubtaskDecompositionStrategy
    ): TaskResult = withContext(scope.coroutineContext) {
        _taskState.value = TaskState.Decomposing
        val subtasks = subtaskStrategy.decompose(mainTask)

        if (subtasks.isEmpty()) {
            _taskState.value = TaskState.Failed("No subtasks generated")
            return@withContext TaskResult(false, emptyList(), 0, mainTask.taskId)
        }

        _taskState.value = TaskState.Executing(0, subtasks.size)
        val results = executeSubTasksWithPool(subtasks)
        _taskState.value = TaskState.Completed

        TaskResult(
            success = results.all { it.success },
            subtaskResults = results,
            totalExecutionTime = results.sumOf { it.executionTime },
            taskId = mainTask.taskId
        )
    }

    suspend fun executeSubTasksParallel(subtasks: List<SubTask>): List<SubTaskResult> =
        withContext(scope.coroutineContext) {
            _taskState.value = TaskState.Executing(0, subtasks.size)
            val results = executeSubTasksWithPool(subtasks)
            _taskState.value = TaskState.Completed
            results
        }

    suspend fun executeSubTasksSequential(subtasks: List<SubTask>): List<SubTaskResult> =
        withContext(ioDispatcher) {
            _taskState.value = TaskState.Executing(0, subtasks.size)
            val results = mutableListOf<SubTaskResult>()
            for ((index, subtask) in subtasks.withIndex()) {
                results.add(executeSubTaskWithTimeout(subtask))
                _taskState.value = TaskState.Executing(index + 1, subtasks.size)
            }
            _taskState.value = TaskState.Completed
            results
        }

    private suspend fun executeSubTasksWithPool(subtasks: List<SubTask>): List<SubTaskResult> =
        coroutineScope {
            val results = ConcurrentHashMap.newKeySet<SubTaskResult>()
            val completedCount = AtomicInteger(0)

            subtasks.map { subtask ->
                async(scope.coroutineContext) {
                    semaphore.withPermit {
                        val result = executeSubTaskWithTimeout(subtask)
                        results.add(result)
                        _taskState.value = TaskState.Executing(completedCount.incrementAndGet(), subtasks.size)
                        result
                    }
                }
            }.awaitAll()

            results.toList()
        }

    private suspend fun executeSubTaskWithTimeout(subtask: SubTask): SubTaskResult {
        val agent = findAgentForTask(subtask) ?: return SubTaskResult(
            taskId = subtask.taskId, success = false, executionTime = 0,
            errorMessage = "No matching agent for type: ${subtask.taskType}"
        )
        val startTime = System.currentTimeMillis()
        return try {
            withTimeoutOrNull(taskTimeoutMs) { agent.execute(subtask) }
                ?: SubTaskResult(subtask.taskId, false, taskTimeoutMs, errorMessage = "Task timed out after ${taskTimeoutMs}ms")
        } catch (e: Exception) {
            SubTaskResult(subtask.taskId, false, System.currentTimeMillis() - startTime, errorMessage = e.message, errorStack = e.stackTraceToString())
        }
    }

    private fun findAgentForTask(subtask: SubTask): SubAgent? =
        agentRegistry.getAgentByType(subtask.taskType)
            ?: agentPool[subtask.taskType]
            ?: agentRegistry.getAgentByType("general")
            ?: agentPool["general"]

    fun getAvailableAgents(): List<SubAgent> = agentRegistry.getAllAgents()
    fun getAgentByType(agentType: String): SubAgent? = agentRegistry.getAgentByType(agentType)
    fun getAgentMetrics(agentId: String): AgentMetrics? = agentRegistry.getMetrics(agentId)
    fun getAllAgentMetrics(): Map<String, AgentMetrics> = agentRegistry.getAllMetrics()

    fun resetAgentMetrics(agentId: String? = null) { agentRegistry.resetMetrics(agentId) }
    fun resetState() { _taskState.value = TaskState.Idle }
    fun shutdown() { scope.cancel() }
    fun getTaskCount(): Int = taskCounter.get()
    fun generateTaskId(): String = "task_${taskCounter.incrementAndGet()}_${System.currentTimeMillis()}"
}

class DefaultSubtaskDecompositionStrategy : SubtaskDecompositionStrategy {
    private val taskTypeMapping = mapOf(
        "coding" to listOf("SearchTask", "DataTask", "WritingTask"),
        "writing" to listOf("SearchTask", "WritingTask"),
        "research" to listOf("SearchTask", "DataTask"),
        "data" to listOf("DataTask", "FileTask"),
        "design" to listOf("SearchTask", "WritingTask", "DataTask"),
        "other" to listOf("general")
    )

    override fun decompose(mainTask: MainTask): List<SubTask> {
        val taskTypes = taskTypeMapping[mainTask.taskType] ?: listOf("general")
        return taskTypes.mapIndexed { index, taskType ->
            SubTask(
                taskId = "subtask_${mainTask.taskId}_${index}",
                taskType = taskType,
                description = "Subtask $index for ${mainTask.taskType}",
                inputData = mainTask.inputData,
                priority = index,
                estimatedTime = 60000
            )
        }
    }
}

class LLMBasedDecompositionStrategy(
    private val llmApi: ((String) -> String)? = null
) : SubtaskDecompositionStrategy {

    override fun decompose(mainTask: MainTask): List<SubTask> {
        val llmResult = llmApi?.invoke(mainTask.description)
        return if (llmResult != null) {
            parseLlmDecomposition(llmResult, mainTask)
        } else {
            DefaultSubtaskDecompositionStrategy().decompose(mainTask)
        }
    }

    private fun parseLlmDecomposition(result: String, mainTask: MainTask): List<SubTask> {
        return try {
            val cleanResult = result.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val regex = """\{[^}]+\}""".toRegex()
            val matches = regex.findAll(cleanResult).toList()
            if (matches.isEmpty()) {
                DefaultSubtaskDecompositionStrategy().decompose(mainTask)
            } else {
                matches.mapIndexed { index, match ->
                    SubTask(
                        taskId = "subtask_${mainTask.taskId}_${index}",
                        taskType = "general",
                        description = match.value,
                        inputData = mapOf("raw" to match.value),
                        priority = index
                    )
                }
            }
        } catch (_: Exception) {
            DefaultSubtaskDecompositionStrategy().decompose(mainTask)
        }
    }
}
