package com.apex.agent.core.multiagent

import android.content.Context
import com.apex.agent.R
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.core.quality.AgentThinkingSession
import com.apex.agent.core.swarm.SwarmIntelligenceEngine
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CollaborationEngine(
    private val context: Context,
    private val aiService: AIService? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "CollaborationEngine"
    }

    private val tasks = ConcurrentHashMap<String, CollaborationTask>()
    private val taskExecutors = ConcurrentHashMap<String, TaskExecutor>()
    private val eventChannel = Channel<CollaborationEvent>(Channel.UNLIMITED)
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _eventFlow = MutableSharedFlow<CollaborationEvent>(replay = 0, extraBufferCapacity = 64)
    val eventFlow: SharedFlow<CollaborationEvent> = _eventFlow.asSharedFlow()

    private val listeners = ConcurrentHashMap<String, CollaborationListener>()
    private var eventJob: Job? = null

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true

        eventJob = coroutineScope.launch(Dispatchers.Default) {
            for (event in eventChannel) {
                handleEvent(event)
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        eventJob?.cancel()
        eventJob = null
        eventChannel.close()

        taskExecutors.values.forEach { it.stop() }
        taskExecutors.clear()
    }

    fun createTask(task: CollaborationTask): String {
        val taskId = task.id.ifEmpty { generateUniqueId() }
        val newTask = task.copy(id = taskId, status = CollaborationTask.Status.PENDING)
        tasks[taskId] = newTask

        eventChannel.trySend(CollaborationEvent(CollaborationEvent.Type.TASK_CREATED, taskId))
        return taskId
    }

    fun startTask(taskId: String): Boolean {
        val task = tasks[taskId]
        if (task == null || task.status != CollaborationTask.Status.PENDING) return false

        tasks[taskId] = task.copy(status = CollaborationTask.Status.RUNNING, startTime = System.currentTimeMillis())

        val executor = TaskExecutor(taskId, task, this, context, aiService, coroutineScope)
        taskExecutors[taskId] = executor
        executor.start()

        eventChannel.trySend(CollaborationEvent(CollaborationEvent.Type.TASK_STARTED, taskId))
        notifyListeners { it.onTaskStarted(taskId) }
        return true
    }

    fun pauseTask(taskId: String): Boolean {
        val task = tasks[taskId]
        if (task == null || task.status != CollaborationTask.Status.RUNNING) return false

        tasks[taskId] = task.copy(status = CollaborationTask.Status.PAUSED)
        taskExecutors[taskId]?.pause()

        eventChannel.trySend(CollaborationEvent(CollaborationEvent.Type.TASK_PAUSED, taskId))
        notifyListeners { it.onTaskPaused(taskId) }
        return true
    }

    fun resumeTask(taskId: String): Boolean {
        val task = tasks[taskId]
        if (task == null || task.status != CollaborationTask.Status.PAUSED) return false

        tasks[taskId] = task.copy(status = CollaborationTask.Status.RUNNING)
        taskExecutors[taskId]?.resume()

        eventChannel.trySend(CollaborationEvent(CollaborationEvent.Type.TASK_RESUMED, taskId))
        notifyListeners { it.onTaskResumed(taskId) }
        return true
    }

    fun stopTask(taskId: String): Boolean {
        val task = tasks[taskId]
        if (task == null || task.status == CollaborationTask.Status.COMPLETED || task.status == CollaborationTask.Status.FAILED) return false

        tasks[taskId] = task.copy(status = CollaborationTask.Status.COMPLETED, endTime = System.currentTimeMillis())
        taskExecutors[taskId]?.stop()
        taskExecutors.remove(taskId)

        eventChannel.trySend(CollaborationEvent(CollaborationEvent.Type.TASK_STOPPED, taskId))
        notifyListeners { it.onTaskStopped(taskId) }
        return true
    }

    fun getTask(taskId: String): CollaborationTask? = tasks[taskId]
    fun getAllTasks(): List<CollaborationTask> = tasks.values.toList()

    fun submitMessage(taskId: String, message: AgentMessage) {
        eventChannel.trySend(CollaborationEvent(CollaborationEvent.Type.MESSAGE_SUBMITTED, taskId, message))
    }

    fun addListener(listener: CollaborationListener) {
        val listenerId = "listener_${System.currentTimeMillis()}"
        listeners[listenerId] = listener
    }

    fun removeListener(listenerId: String) {
        listeners.remove(listenerId)
    }

    private fun notifyListeners(action: (CollaborationListener) -> Unit) {
        listeners.values.forEach { listener ->
            try { action(listener) } catch (e: Exception) { AppLogger.e(TAG, "notifyListeners error", e) }
        }
    }

    private fun handleEvent(event: CollaborationEvent) {
        _eventFlow.tryEmit(event)
        when (event.type) {
            CollaborationEvent.Type.TASK_CREATED -> notifyListeners { it.onTaskCreated(event.taskId) }
            CollaborationEvent.Type.TASK_STARTED -> notifyListeners { it.onTaskStarted(event.taskId) }
            CollaborationEvent.Type.TASK_PAUSED -> notifyListeners { it.onTaskPaused(event.taskId) }
            CollaborationEvent.Type.TASK_RESUMED -> notifyListeners { it.onTaskResumed(event.taskId) }
            CollaborationEvent.Type.TASK_STOPPED -> notifyListeners { it.onTaskStopped(event.taskId) }
            CollaborationEvent.Type.MESSAGE_SUBMITTED -> {
                event.message?.let { msg ->
                    taskExecutors[event.taskId]?.handleMessage(msg)
                }
            }
            CollaborationEvent.Type.AGENT_MESSAGE -> {
                event.message?.let { msg ->
                    notifyListeners { it.onAgentMessage(event.taskId, msg) }
                }
            }
            CollaborationEvent.Type.TASK_COMPLETED -> notifyListeners { it.onTaskCompleted(event.taskId) }
            CollaborationEvent.Type.TASK_FAILED -> {
                notifyListeners { it.onTaskFailed(event.taskId, event.errorMessage ?: context.getString(R.string.error_unknown)) }
            }
        }
    }

    private fun generateUniqueId(): String = "task_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
}

interface CollaborationListener {
    fun onTaskCreated(taskId: String) {}
    fun onTaskStarted(taskId: String) {}
    fun onTaskPaused(taskId: String) {}
    fun onTaskResumed(taskId: String) {}
    fun onTaskStopped(taskId: String) {}
    fun onTaskCompleted(taskId: String) {}
    fun onTaskFailed(taskId: String, error: String) {}
    fun onAgentMessage(taskId: String, message: AgentMessage) {}
    fun onAgentStatusChanged(taskId: String, agentId: String, status: AgentInstance.Status) {}
    fun onProgressChanged(taskId: String, progress: Float) {}
}

class TaskExecutor(
    private val taskId: String,
    private val task: CollaborationTask,
    private val engine: CollaborationEngine,
    val context: Context,
    private val aiService: AIService? = null,
    private val parentScope: CoroutineScope
) {
    private var job: Job? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private val _isPaused = MutableStateFlow(false)
    private val messageChannel = Channel<AgentMessage>(Channel.UNLIMITED)
    private val agentStates = ConcurrentHashMap<String, AgentExecutionState>()
    private val currentStep = AtomicInteger(0)
    private var currentModeHandler: CollaborationModeHandler? = null

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        _isPaused.value = false

        initializeAgentStates()
        currentModeHandler = createModeHandler()

        job = scope.launch(Dispatchers.Default) {
            while (_isRunning.value && isActive) {
                if (!_isPaused.value) {
                    val message = messageChannel.tryReceive().getOrNull()
                    if (message != null) {
                        handleMessage(message)
                    } else {
                        executeCollaboration()
                        delay(10)
                    }
                } else {
                    delay(50)
                }
            }
        }
    }

    private fun initializeAgentStates() {
        task.agents.forEach { agent ->
            agentStates[agent.id] = AgentExecutionState(
                agentId = agent.id,
                status = AgentStatus.IDLE,
                currentTask = null,
                progress = 0f,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }

    private fun createModeHandler(): CollaborationModeHandler {
        val swarmEngine = aiService?.let { SwarmIntelligenceEngine(context, it) }
        return when (task.collaborationMode) {
            CollaborationTask.CollaborationMode.SUPERVISOR_EXECUTION ->
                SupervisorModeHandler(task, this, context, aiService)
            CollaborationTask.CollaborationMode.SERIAL_PIPELINE ->
                SerialPipelineModeHandler(task, this, context, aiService)
            CollaborationTask.CollaborationMode.PARALLEL_EXECUTION ->
                ParallelExecutionModeHandler(task, this, context, aiService, scope)
            CollaborationTask.CollaborationMode.DEBATE_REVIEW ->
                DebateReviewModeHandler(task, this, context, aiService, swarmEngine)
            CollaborationTask.CollaborationMode.FREE_DIALOG ->
                FreeDialogModeHandler(task, this, context, aiService)
        }
    }

    fun stop() {
        _isRunning.value = false
        _isPaused.value = false
        job?.cancel()
        job = null
        currentModeHandler?.cleanup()
        messageChannel.close()
    }

    fun pause() { _isPaused.value = true }
    fun resume() { _isPaused.value = false }

    fun handleMessage(message: AgentMessage) {
        currentModeHandler?.onMessageReceived(message)
    }

    private suspend fun executeCollaboration() {
        currentModeHandler?.executeStep()
    }

    fun updateAgentStatus(agentId: String, status: AgentStatus) {
        agentStates.computeIfPresent(agentId) { _, state ->
            state.copy(status = status, lastUpdateTime = System.currentTimeMillis())
        }
    }

    fun updateAgentProgress(agentId: String, progress: Float) {
        agentStates.computeIfPresent(agentId) { _, state ->
            state.copy(progress = progress, lastUpdateTime = System.currentTimeMillis())
        }
    }

    fun broadcastMessage(message: AgentMessage) {
        task.agents.forEach { agent ->
            agentStates.computeIfPresent(agent.id) { _, state ->
                if (state.status == AgentStatus.IDLE || state.status == AgentStatus.WAITING) {
                    state.copy(status = AgentStatus.RECEIVING, currentTask = message.content, lastUpdateTime = System.currentTimeMillis())
                } else state
            }
        }
    }

    fun sendToAgent(agentId: String, message: AgentMessage) {
        agentStates.computeIfPresent(agentId) { _, state ->
            state.copy(status = AgentStatus.RECEIVING, currentTask = message.content, lastUpdateTime = System.currentTimeMillis())
        }
    }

    fun getNextAgent(excludeIds: Set<String> = emptySet()): Agent? =
        task.agents.firstOrNull { it.id !in excludeIds && agentStates[it.id]?.status == AgentStatus.IDLE }

    fun getSupervisorAgent(): Agent? {
        val supervisorRole = context.getString(R.string.role_supervisor)
        val coordinatorRole = context.getString(R.string.role_coordinator)
        return task.agents.firstOrNull { it.role.contains(supervisorRole) || it.role.contains(coordinatorRole) }
    }

    fun incrementStep(): Int = currentStep.incrementAndGet()
    fun getStep(): Int = currentStep.get()
    fun getAgentState(agentId: String): AgentExecutionState? = agentStates[agentId]

    fun areAllAgentsFinished(): Boolean =
        agentStates.values.all { it.status == AgentStatus.IDLE || it.status == AgentStatus.FINISHED }

    fun areAllAgentsWorking(): Boolean =
        agentStates.values.all { it.status == AgentStatus.WORKING }
}

data class AgentExecutionState(
    val agentId: String,
    val status: AgentStatus,
    val currentTask: String?,
    val progress: Float,
    val lastUpdateTime: Long,
    val messages: List<AgentMessage> = emptyList()
)

enum class AgentStatus {
    IDLE, RECEIVING, WORKING, WAITING, FINISHED, ERROR
}

abstract class CollaborationModeHandler(
    protected val task: CollaborationTask,
    protected val executor: TaskExecutor
) {
    abstract suspend fun executeStep()
    abstract fun onMessageReceived(message: AgentMessage)
    open fun cleanup() {}
}

class SupervisorModeHandler(
    task: CollaborationTask,
    executor: TaskExecutor,
    private val context: Context,
    private val aiService: AIService?
) : CollaborationModeHandler(task, executor) {

    private var phase = SupervisorPhase.TASK_ANALYSIS
    private var assignedTasks = mutableMapOf<String, String>()
    private var completedTasks = mutableSetOf<String>()

    override suspend fun executeStep() {
        when (phase) {
            SupervisorPhase.TASK_ANALYSIS -> executeTaskAnalysis()
            SupervisorPhase.TASK_DECOMPOSITION -> executeTaskDecomposition()
            SupervisorPhase.TASK_ASSIGNMENT -> executeTaskAssignment()
            SupervisorPhase.EXECUTION_MONITORING -> executeExecutionMonitoring()
            SupervisorPhase.RESULT_AGGREGATION -> executeResultAggregation()
            SupervisorPhase.FINAL_REVIEW -> executeFinalReview()
        }
    }

    private suspend fun executeTaskAnalysis() {
        val supervisor = executor.getSupervisorAgent() ?: run { phase = SupervisorPhase.FINAL_REVIEW; return }
        executor.updateAgentStatus(supervisor.id, AgentStatus.WORKING)

        val thinkingSession = aiService?.let { AgentThinkingSession(context, it) }
        val analysis = if (thinkingSession != null) {
            val output = thinkingSession.thinkAndProduce(
                agent = supervisor,
                task = task.description,
                instructions = "Provide a detailed task analysis with approach breakdown.",
                enableQualityCheck = true
            )
            output.finalAnswer
        } else if (aiService != null) {
            callAI(
                "Analyze the following task and provide a detailed breakdown approach:\n${task.description}",
                "You are a senior project supervisor. Analyze task requirements and provide a clear breakdown plan."
            )
        } else ""

        if (analysis.isNotBlank()) {
            executor.broadcastMessage(AgentMessage(
                sender = context.getString(R.string.system_sender),
                content = analysis,
                timestamp = System.currentTimeMillis(),
                type = AgentMessage.Type.SYSTEM
            ))
        } else {
            executor.broadcastMessage(AgentMessage(
                sender = context.getString(R.string.system_sender),
                content = context.getString(R.string.task_analysis_prefix, task.description),
                timestamp = System.currentTimeMillis(),
                type = AgentMessage.Type.SYSTEM
            ))
        }
        phase = SupervisorPhase.TASK_DECOMPOSITION
    }

    private suspend fun executeTaskDecomposition() {
        val supervisor = executor.getSupervisorAgent() ?: return
        val subTasks = decomposeTask(task.description)
        assignedTasks.clear()
        subTasks.forEachIndexed { index, subTask ->
            assignedTasks["task_${index}"] = subTask
        }
        executor.updateAgentProgress(supervisor.id, 0.3f)
        phase = SupervisorPhase.TASK_ASSIGNMENT
    }

    private suspend fun executeTaskAssignment() {
        val supervisor = executor.getSupervisorAgent() ?: return

        assignedTasks.forEach { (taskId, subTask) ->
            val availableAgent = executor.getNextAgent()
            if (availableAgent != null) {
                executor.sendToAgent(availableAgent.id, AgentMessage(
                    sender = supervisor.name,
                    content = subTask,
                    timestamp = System.currentTimeMillis(),
                    type = AgentMessage.Type.AGENT
                ))
                executor.updateAgentStatus(availableAgent.id, AgentStatus.WORKING)
            }
        }
        executor.updateAgentProgress(supervisor.id, 0.5f)
        phase = SupervisorPhase.EXECUTION_MONITORING
    }

    private suspend fun executeExecutionMonitoring() {
        if (executor.areAllAgentsFinished()) {
            phase = SupervisorPhase.RESULT_AGGREGATION
        } else {
            delay(50)
        }
    }

    private suspend fun executeResultAggregation() {
        val supervisor = executor.getSupervisorAgent() ?: return
        executor.updateAgentProgress(supervisor.id, 0.9f)

        val thinkingSession = aiService?.let { AgentThinkingSession(context, it) }
        val summary = if (thinkingSession != null) {
            val output = thinkingSession.thinkAndProduce(
                agent = supervisor,
                task = "Aggregate results for: ${task.description}",
                instructions = "Summarize completed work from all agents, ensuring completeness and quality.",
                enableQualityCheck = true
            )
            output.finalAnswer
        } else if (aiService != null) {
            callAI(
                "Aggregate and verify all results for task: ${task.description}",
                "You are a project manager reviewing team outputs. Ensure nothing is missed."
            )
        } else ""

        if (summary.isNotBlank()) {
            executor.broadcastMessage(AgentMessage(
                sender = context.getString(R.string.system_sender),
                content = "Results Summary: $summary",
                timestamp = System.currentTimeMillis(),
                type = AgentMessage.Type.SYSTEM
            ))
        }
        phase = SupervisorPhase.FINAL_REVIEW
    }

    private suspend fun executeFinalReview() {
        val supervisor = executor.getSupervisorAgent() ?: return
        executor.updateAgentProgress(supervisor.id, 1.0f)

        val thinkingSession = aiService?.let { AgentThinkingSession(context, it) }
        if (thinkingSession != null && aiService != null) {
            val qualityReport = thinkingSession.validateOutput(
                task.agents.joinToString("\n") { agent ->
                    "${agent.name} (${agent.role}): completed"
                },
                "Final quality review for: ${task.description}"
            )
            val gateResult = com.apex.agent.core.evaluation.QualityGate.evaluate(qualityReport)
            if (!gateResult.passed) {
                AppLogger.w(TAG, "Final review quality concerns: ${gateResult.suggestions}")
            }
        }

        executor.updateAgentStatus(supervisor.id, AgentStatus.FINISHED)
    }

    private suspend fun decomposeTask(taskDescription: String): List<String> {
        if (aiService != null) {
            val result = callAI(
                "Decompose the following task into 3-5 subtasks that can be executed in parallel:\n$taskDescription",
                "You are a task decomposition expert. Break down complex tasks into manageable subtasks."
            )
            if (result.isNotBlank()) {
                return result.split("\n").filter { it.isNotBlank() }.take(10)
            }
        }
        return listOf(
            context.getString(R.string.supervisor_decompose_requirements, taskDescription),
            context.getString(R.string.supervisor_decompose_design),
            context.getString(R.string.supervisor_decompose_implementation),
            context.getString(R.string.supervisor_decompose_testing),
            context.getString(R.string.supervisor_decompose_documentation)
        )
    }

    private suspend fun callAI(prompt: String, systemPrompt: String): String {
        return try {
            val turns = listOf(
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.SYSTEM, content = systemPrompt
                ),
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.USER, content = prompt
                )
            )
            val result = StringBuilder()
            aiService?.sendMessage(
                context = context,
                chatHistory = turns,
                stream = false
            )?.collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    override fun onMessageReceived(message: AgentMessage) {
        if (phase == SupervisorPhase.EXECUTION_MONITORING) {
            executor.updateAgentStatus(message.sender, AgentStatus.IDLE)
            completedTasks.add(message.sender)
        }
    }

    override fun cleanup() {
        assignedTasks.clear()
        completedTasks.clear()
    }

    enum class SupervisorPhase {
        TASK_ANALYSIS, TASK_DECOMPOSITION, TASK_ASSIGNMENT,
        EXECUTION_MONITORING, RESULT_AGGREGATION, FINAL_REVIEW
    }
}

class SerialPipelineModeHandler(
    task: CollaborationTask,
    executor: TaskExecutor,
    private val context: Context,
    private val aiService: AIService?
) : CollaborationModeHandler(task, executor) {

    private var currentAgentIndex = 0
    private var pipelineData: String = ""
    private val pipelineStages = listOf(
        "Requirements Analysis",
        "Design",
        "Implementation",
        "Testing",
        "Deployment"
    )

    override suspend fun executeStep() {
        val agents = task.agents
        if (agents.isEmpty() || currentAgentIndex >= agents.size) return

        val currentAgent = agents[currentAgentIndex]
        executor.updateAgentStatus(currentAgent.id, AgentStatus.WORKING)

        val stageName = pipelineStages.getOrElse(currentAgentIndex) { "Stage ${currentAgentIndex + 1}" }
        val inputData = if (currentAgentIndex == 0) task.description else pipelineData

        pipelineData = if (aiService != null) {
            val result = callAI(
                "You are at stage '$stageName'. Process the following input and pass it to the next stage:\n\n$inputData",
                "You are a pipeline processing agent. Complete your stage and pass results to the next."
            )
            if (result.isNotBlank()) result
            else context.getString(R.string.pipeline_stage_processed_format, stageName, currentAgent.name)
        } else {
            context.getString(R.string.pipeline_stage_processed_format, stageName, currentAgent.name)
        }

        executor.updateAgentProgress(currentAgent.id, (currentAgentIndex + 1).toFloat() / agents.size)
        executor.updateAgentStatus(currentAgent.id, AgentStatus.FINISHED)

        currentAgentIndex++
        if (currentAgentIndex >= agents.size) {
            executor.getSupervisorAgent()?.let {
                executor.updateAgentStatus(it.id, AgentStatus.FINISHED)
            }
        }
    }

    private suspend fun callAI(prompt: String, systemPrompt: String): String {
        return try {
            val turns = listOf(
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.SYSTEM, content = systemPrompt
                ),
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.USER, content = prompt
                )
            )
            val result = StringBuilder()
            aiService?.sendMessage(
                context = context,
                chatHistory = turns,
                stream = false
            )?.collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    override fun onMessageReceived(message: AgentMessage) {
        if (message.sender == task.agents.getOrNull(currentAgentIndex)?.name) {
            executor.updateAgentStatus(message.sender, AgentStatus.IDLE)
        }
    }
}

class ParallelExecutionModeHandler(
    task: CollaborationTask,
    executor: TaskExecutor,
    private val context: Context,
    private val aiService: AIService?,
    private val scope: CoroutineScope
) : CollaborationModeHandler(task, executor) {

    private var executionRound = 0
    private val maxRounds = 3
    private val results = ConcurrentHashMap<String, String>()
    private var executionStarted = false

    override suspend fun executeStep() {
        val agents = task.agents
        if (agents.isEmpty()) return
        if (executionRound >= maxRounds) return

        executionStarted = true

        agents.parallelForEach(scope) { agent ->
            executor.updateAgentStatus(agent.id, AgentStatus.WORKING)
            val result = executeBranch(agent, executionRound)
            results[agent.id] = result
            executor.updateAgentProgress(agent.id, (executionRound + 1).toFloat() / maxRounds)
            executor.updateAgentStatus(agent.id, AgentStatus.FINISHED)
        }

        executionRound++
        if (executionRound >= maxRounds) {
            aggregateResults()
        }
    }

    private suspend fun executeBranch(agent: Agent, round: Int): String {
        val thinkingSession = aiService?.let { AgentThinkingSession(context, it) }
        if (thinkingSession != null) {
            val output = thinkingSession.thinkAndProduce(
                agent = agent,
                task = "${task.description} [Branch $round]",
                instructions = "Execute this branch independently with thorough analysis.",
                enableQualityCheck = true
            )
            if (output.finalAnswer.isNotBlank() && output.passedQualityGate) return output.finalAnswer
        }
        if (aiService != null) {
            val result = callAI(
                "Execute branch $round for task: ${task.description}. Your role: ${agent.role}",
                "You are ${agent.name}, a ${agent.role}. Execute your assigned branch independently."
            )
            if (result.isNotBlank()) return result
        }
        return context.getString(R.string.parallel_branch_execution_format, agent.name, round + 1)
    }

    private suspend fun aggregateResults() {
        val summary = results.entries.joinToString("\n") { "${it.key}: ${it.value}" }

        if (aiService != null) {
            val aiSummary = callAI(
                "Aggregate and summarize the following parallel execution results:\n\n$summary",
                "You are a results aggregator. Combine parallel execution results into a coherent summary."
            )
            if (aiSummary.isNotBlank()) {
                executor.broadcastMessage(AgentMessage(
                    sender = context.getString(R.string.system_sender),
                    content = "Parallel Execution Summary:\n$aiSummary",
                    timestamp = System.currentTimeMillis(),
                    type = AgentMessage.Type.SYSTEM
                ))
            }
        }
        results.clear()
    }

    private suspend fun callAI(prompt: String, systemPrompt: String): String {
        return try {
            val turns = listOf(
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.SYSTEM, content = systemPrompt
                ),
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.USER, content = prompt
                )
            )
            val result = StringBuilder()
            aiService?.sendMessage(
                context = context,
                chatHistory = turns,
                stream = false
            )?.collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    override fun onMessageReceived(message: AgentMessage) {
        results[message.sender] = message.content
    }
}

class DebateReviewModeHandler(
    task: CollaborationTask,
    executor: TaskExecutor,
    private val context: Context,
    private val aiService: AIService?,
    private val swarmEngine: SwarmIntelligenceEngine?
) : CollaborationModeHandler(task, executor) {

    private var phase = DebatePhase.OPENING_ARGUMENT
    private var round = 0
    private val maxRounds = 3
    private val arguments = mutableListOf<Pair<String, String>>()
    private val scores = mutableMapOf<String, Float>()

    override suspend fun executeStep() {
        when (phase) {
            DebatePhase.OPENING_ARGUMENT -> executeOpeningArguments()
            DebatePhase.CROSS_EXAMINATION -> executeCrossExamination()
            DebatePhase.REBUTTAL -> executeRebuttal()
            DebatePhase.FINAL_ARGUMENT -> executeFinalArguments()
            DebatePhase.JUDGMENT -> executeJudgment()
        }
    }

    private suspend fun executeOpeningArguments() {
        if (swarmEngine != null && aiService != null) {
            val debate = swarmEngine.startDebate(task.description, task.agents.map { it.id })
            val consensus = swarmEngine.reachConsensus(debate.id)
            task.agents.forEach { agent ->
                executor.updateAgentStatus(agent.id, AgentStatus.FINISHED)
            }
            phase = DebatePhase.JUDGMENT
            return
        }

        task.agents.forEach { agent ->
            executor.updateAgentStatus(agent.id, AgentStatus.WORKING)
            val argument = generateOpeningArgument(agent)
            arguments.add(agent.id to argument)
            executor.updateAgentProgress(agent.id, 0.25f)
        }
        task.agents.forEach { executor.updateAgentStatus(it.id, AgentStatus.WAITING) }
        round = 1
        phase = DebatePhase.CROSS_EXAMINATION
    }

    private suspend fun executeCrossExamination() {
        task.agents.forEach { agent ->
            executor.updateAgentStatus(agent.id, AgentStatus.WORKING)
            val question = generateQuestion(agent, arguments)
            arguments.add(agent.id to question)
            executor.updateAgentProgress(agent.id, 0.25f + 0.25f * (round - 1) / maxRounds)
        }
        round++
        if (round > maxRounds) phase = DebatePhase.REBUTTAL
    }

    private suspend fun executeRebuttal() {
        task.agents.forEach { agent ->
            executor.updateAgentStatus(agent.id, AgentStatus.WORKING)
            val rebuttal = generateRebuttal(agent, arguments)
            arguments.add(agent.id to rebuttal)
            executor.updateAgentProgress(agent.id, 0.75f)
        }
        task.agents.forEach { executor.updateAgentStatus(it.id, AgentStatus.WAITING) }
        phase = DebatePhase.FINAL_ARGUMENT
    }

    private suspend fun executeFinalArguments() {
        task.agents.forEach { agent ->
            executor.updateAgentStatus(agent.id, AgentStatus.WORKING)
            val finalArgument = generateFinalArgument(agent)
            arguments.add(agent.id to finalArgument)
            executor.updateAgentProgress(agent.id, 0.9f)
        }
        task.agents.forEach { executor.updateAgentStatus(it.id, AgentStatus.FINISHED) }
        phase = DebatePhase.JUDGMENT
    }

    private suspend fun executeJudgment() {
        task.agents.forEach { agent ->
            scores[agent.id] = calculateScore(agent, arguments)
        }
        executor.getSupervisorAgent()?.let { supervisor ->
            executor.updateAgentStatus(supervisor.id, AgentStatus.FINISHED)
        }
        arguments.clear()
    }

    private suspend fun generateOpeningArgument(agent: Agent): String {
        if (aiService != null) {
            val result = callAI(
                "Generate an opening argument for the debate topic: ${task.description}. Your role: ${agent.role}",
                "You are ${agent.name}, a ${agent.role}. Present your opening argument."
            )
            if (result.isNotBlank()) return result
        }
        return context.getString(R.string.debate_opening_argument_format, agent.name, task.description)
    }

    private suspend fun generateQuestion(agent: Agent, prevArgs: List<Pair<String, String>>): String {
        if (aiService != null) {
            val context = prevArgs.takeLast(3).joinToString("\n") { "${it.first}: ${it.second}" }
            val result = callAI(
                "Based on these arguments:\n$context\n\nAsk a probing question.",
                "You are ${agent.name}. Question the other participants' arguments."
            )
            if (result.isNotBlank()) return result
        }
        return context.getString(R.string.debate_question_format, agent.name)
    }

    private suspend fun generateRebuttal(agent: Agent, prevArgs: List<Pair<String, String>>): String {
        if (aiService != null) {
            val result = callAI(
                "Provide a rebuttal to the latest arguments. Topic: ${task.description}",
                "You are ${agent.name}. Rebut the opposing arguments concisely."
            )
            if (result.isNotBlank()) return result
        }
        return context.getString(R.string.debate_rebuttal_format, agent.name)
    }

    private suspend fun generateFinalArgument(agent: Agent): String {
        if (aiService != null) {
            val result = callAI(
                "Present your final closing argument on: ${task.description}",
                "You are ${agent.name}. Summarize your position in a compelling final statement."
            )
            if (result.isNotBlank()) return result
        }
        return context.getString(R.string.debate_final_argument_format, agent.name)
    }

    private fun calculateScore(agent: Agent, args: List<Pair<String, String>>): Float {
        return (Math.random() * 0.3 + 0.7).toFloat()
    }

    private suspend fun callAI(prompt: String, systemPrompt: String): String {
        return try {
            val turns = listOf(
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.SYSTEM, content = systemPrompt
                ),
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.USER, content = prompt
                )
            )
            val result = StringBuilder()
            aiService?.sendMessage(
                context = context,
                chatHistory = turns,
                stream = false
            )?.collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    override fun onMessageReceived(message: AgentMessage) {
        arguments.add(message.sender to message.content)
    }

    override fun cleanup() {
        arguments.clear()
        scores.clear()
    }

    enum class DebatePhase {
        OPENING_ARGUMENT, CROSS_EXAMINATION, REBUTTAL, FINAL_ARGUMENT, JUDGMENT
    }
}

class FreeDialogModeHandler(
    task: CollaborationTask,
    executor: TaskExecutor,
    private val context: Context,
    private val aiService: AIService?
) : CollaborationModeHandler(task, executor) {

    private var messageCount = 0
    private val maxMessages = 10
    private val dialogHistory = mutableListOf<AgentMessage>()
    private val agentMessageCount = mutableMapOf<String, Int>()

    override suspend fun executeStep() {
        if (messageCount >= maxMessages) {
            finishDialog()
            return
        }

        val activeAgents = task.agents.filter { agent ->
            (agentMessageCount.getOrDefault(agent.id, 0)) < 3
        }

        if (activeAgents.isEmpty()) {
            finishDialog()
            return
        }

        val speaker = selectNextSpeaker(activeAgents)
        if (speaker != null) {
            executor.updateAgentStatus(speaker.id, AgentStatus.WORKING)
            val response = generateFreeResponse(speaker, dialogHistory)
            val message = AgentMessage(
                sender = speaker.name,
                content = response,
                timestamp = System.currentTimeMillis(),
                type = AgentMessage.Type.AGENT
            )
            dialogHistory.add(message)
            agentMessageCount[speaker.id] = agentMessageCount.getOrDefault(speaker.id, 0) + 1
            messageCount++
            executor.updateAgentProgress(speaker.id, messageCount.toFloat() / maxMessages)
            executor.updateAgentStatus(speaker.id, AgentStatus.IDLE)
        }
    }

    private fun selectNextSpeaker(activeAgents: List<Agent>): Agent? =
        activeAgents.minByOrNull { agentMessageCount.getOrDefault(it.id, 0) }

    private suspend fun generateFreeResponse(agent: Agent, history: List<AgentMessage>): String {
        val thinkingSession = aiService?.let { AgentThinkingSession(context, it) }
        if (thinkingSession != null) {
            val recentContext = history.takeLast(5).joinToString("\n") { "${it.sender}: ${it.content}" }
            val output = thinkingSession.thinkAndProduce(
                agent = agent,
                task = "Discuss: ${task.description}",
                background = "Recent dialog:\n$recentContext",
                instructions = "Contribute meaningfully based on your expertise.",
                enableCoT = false,
                enableQualityCheck = true
            )
            if (output.finalAnswer.isNotBlank()) return output.finalAnswer
        }
        if (aiService != null) {
            val context = history.takeLast(5).joinToString("\n") { "${it.sender}: ${it.content}" }
            val result = callAI(
                "Continue the discussion. Topic: ${task.description}\nRecent dialog:\n$context",
                "You are ${agent.name}, a ${agent.role}. Contribute a thoughtful perspective."
            )
            if (result.isNotBlank()) return result
        }
        return context.getString(R.string.free_dialog_response_format, agent.name)
    }

    private suspend fun callAI(prompt: String, systemPrompt: String): String {
        return try {
            val turns = listOf(
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.SYSTEM, content = systemPrompt
                ),
                com.apex.core.chat.hooks.PromptTurn(
                    kind = com.apex.core.chat.hooks.PromptTurnKind.USER, content = prompt
                )
            )
            val result = StringBuilder()
            aiService?.sendMessage(
                context = context,
                chatHistory = turns,
                stream = false
            )?.collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    private fun finishDialog() {
        task.agents.forEach { executor.updateAgentStatus(it.id, AgentStatus.FINISHED) }
        dialogHistory.clear()
        agentMessageCount.clear()
    }

    override fun onMessageReceived(message: AgentMessage) {
        dialogHistory.add(message)
        agentMessageCount[message.sender] = agentMessageCount.getOrDefault(message.sender, 0) + 1
        messageCount++
    }

    override fun cleanup() {
        dialogHistory.clear()
        agentMessageCount.clear()
    }
}

class CollaborationEvent(
    val type: Type,
    val taskId: String,
    val message: AgentMessage? = null,
    val errorMessage: String? = null
) {
    enum class Type {
        TASK_CREATED, TASK_STARTED, TASK_PAUSED, TASK_RESUMED,
        TASK_STOPPED, TASK_COMPLETED, TASK_FAILED,
        MESSAGE_SUBMITTED, AGENT_MESSAGE
    }
}

suspend fun <T> List<T>.parallelForEach(scope: CoroutineScope, action: suspend (T) -> Unit) {
    coroutineScope {
        map { async { action(it) } }.awaitAll()
    }
}
