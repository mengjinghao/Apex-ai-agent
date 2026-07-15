package com.apex.agent.orchestration.collaboration.modes

import android.content.Context
import com.apex.agent.R
import com.apex.agent.common.result.Result
import com.apex.agent.core.memory.unified.SharedMemoryEntry
import com.apex.agent.core.memory.unified.SharedMemoryPool
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.domain.entity.Task
import com.apex.agent.orchestration.agent.AgentManager
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.collaboration.AgentExecutionState
import com.apex.agent.orchestration.collaboration.AgentStatus
import com.apex.agent.orchestration.collaboration.TaskExecutor
import com.apex.agent.orchestration.collaboration.TaskState
import com.apex.agent.orchestration.memory.ArtifactType
import com.apex.agent.orchestration.memory.ContextCategory
import com.apex.agent.orchestration.memory.SessionMemoryBridge
import com.apex.agent.orchestration.memory.WorkingMemoryManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

abstract class AbstractCollaborationMode<State : AbstractCollaborationMode.ExecutionState>(
    @ApplicationContext protected val context: Context,
    protected val agentManager: AgentManager
) : TaskExecutor {

    private val sharedMemoryPool: SharedMemoryPool = SharedMemoryPool.getInstance()
    protected val workingMemory: WorkingMemoryManager = WorkingMemoryManager()
    protected var memoryBridge: SessionMemoryBridge? = null

    fun attachMemoryBridge(bridge: SessionMemoryBridge) {
        memoryBridge = bridge
    }

    protected open class ExecutionState(
        open val task: Task,
        open val agents: List<Agent>,
        val agentStates: ConcurrentHashMap<String, AgentExecutionState> = ConcurrentHashMap(),
        val running: AtomicBoolean = AtomicBoolean(true),
        val paused: AtomicBoolean = AtomicBoolean(false),
        val currentStep: AtomicInteger = AtomicInteger(0)
    )

    protected val executions = ConcurrentHashMap<String, State>()

    protected abstract fun createState(task: Task, agents: List<Agent>): State
    protected abstract suspend fun runStep(state: State)

    override suspend fun execute(task: Task): Flow<Result<Task>> = flow {
        val agentsResult = agentManager.getAllAgents()
        val allAgents = when (agentsResult) {
            is Result.Success -> agentsResult.data
            is Result.Failure -> emptyList()
        }
        val agents = task.agentIds.mapNotNull { id -> allAgents.find { it.id == id } }
        if (agents.isEmpty()) {
            emit(Result.Failure(IllegalStateException("No agents available for task ${task.id}")))
            return@flow
        }
        val state = createState(task, agents)
        initializeAgentStates(state)
        executions[task.id] = state

        workingMemory.createSession(task.id, task.id, task.description)
        agents.forEach { a ->
            workingMemory.registerAgent(task.id, a.id, a.name, a.role)
            workingMemory.updateAgentStatus(task.id, a.id, "ready")
        }
        memoryBridge?.loadContextForNewSession(task.id, agents.firstOrNull()?.id ?: "", "").let { ctx ->
            if (ctx.isNotEmpty()) {
                workingMemory.addContext(task.id, "inherited_context", ctx, "system", ContextCategory.FACT)
            }
        }

        emit(Result.Success(task.copy(status = TaskState.RUNNING.name, updatedAt = System.currentTimeMillis())))

        while (state.running.get() && currentCoroutineContext().isActive) {
            if (state.paused.get()) {
                delay(100)
                continue
            }

            try {
                pullSharedMemories(state)
                runStep(state)
                emit(Result.Success(task.copy(status = TaskState.RUNNING.name, updatedAt = System.currentTimeMillis())))
            } catch (e: Exception) {
                emit(Result.Failure(e))
                break
            }

            delay(100)
        }

        state.agentStates.keys.forEach { agentId ->
            val current = state.agentStates[agentId]
            if (current != null && current.status != AgentStatus.FINISHED && current.status != AgentStatus.ERROR) {
                state.agentStates[agentId] = current.copy(status = AgentStatus.FINISHED)
                workingMemory.updateAgentStatus(task.id, agentId, "finished")
            }
        }

        memoryBridge?.consolidateSessionToKG(task.id)
        workingMemory.removeSession(task.id)
        executions.remove(task.id)
    }.flowOn(Dispatchers.Default)

    override suspend fun pause(taskId: String): Result<Unit> {
        executions[taskId]?.paused?.set(true)
        return Result.Success(Unit)
    }

    override suspend fun resume(taskId: String): Result<Unit> {
        executions[taskId]?.paused?.set(false)
        return Result.Success(Unit)
    }

    override suspend fun cancel(taskId: String): Result<Unit> {
        executions[taskId]?.running?.set(false)
        sharedMemoryPool.clearTaskMemory(taskId)
        memoryBridge?.consolidateSessionToKG(taskId)
        workingMemory.clearSession(taskId)
        executions.remove(taskId)
        return Result.Success(Unit)
    }

    protected open suspend fun onMessage(state: State, message: AgentMessage) {}

    override suspend fun onMessageReceived(taskId: String, message: AgentMessage): Result<Unit> {
        val state = executions[taskId]
            ?: return Result.Failure(NoSuchElementException("Task not running: ${taskId}"))
        onMessage(state, message)
        return Result.Success(Unit)
    }

    protected fun initializeAgentStates(state: State) {
        state.agents.forEach { agent ->
            state.agentStates[agent.id] = AgentExecutionState(
                agentId = agent.id,
                status = AgentStatus.IDLE,
                currentTask = null,
                progress = 0f,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }

    protected fun updateAgentStatus(state: State, agentId: String, status: AgentStatus) {
        val current = state.agentStates[agentId] ?: return
        state.agentStates[agentId] = current.copy(
            status = status,
            lastUpdateTime = System.currentTimeMillis()
        )
        workingMemory.updateAgentStatus(state.task.id, agentId, status.name.lowercase())
    }

    protected fun updateAgentProgress(state: State, agentId: String, progress: Float) {
        val current = state.agentStates[agentId] ?: return
        state.agentStates[agentId] = current.copy(
            progress = progress,
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    protected fun broadcastMessage(state: State, content: String, senderId: String = "") {
        state.agents.forEach { agent ->
            val current = state.agentStates[agent.id] ?: return@forEach
            if (current.status == AgentStatus.IDLE || current.status == AgentStatus.WAITING) {
                state.agentStates[agent.id] = current.copy(
                    status = AgentStatus.RECEIVING,
                    currentTask = content,
                    lastUpdateTime = System.currentTimeMillis(),
                    messages = current.messages + createAgentMessage(senderId, agent.id, content)
                )
            }
        }
        val entry = SharedMemoryEntry(
            entryId = UUID.randomUUID().toString(),
            taskId = state.task.id,
            content = content,
            agentRole = senderId.ifEmpty { "system" },
            priority = 60
        )
        sharedMemoryPool.writeSharedMemory(entry)
        workingMemory.recordMessage(state.task.id, senderId.ifEmpty { "system" }, null)
    }

    protected fun sendToAgent(state: State, agentId: String, content: String, senderId: String = "") {
        val current = state.agentStates[agentId] ?: return
        state.agentStates[agentId] = current.copy(
            status = AgentStatus.RECEIVING,
            currentTask = content,
            lastUpdateTime = System.currentTimeMillis(),
            messages = current.messages + createAgentMessage(senderId, agentId, content)
        )
        val entry = SharedMemoryEntry(
            entryId = UUID.randomUUID().toString(),
            taskId = state.task.id,
            content = content,
            agentRole = senderId.ifEmpty { "system" },
            priority = 70
        )
        sharedMemoryPool.writeSharedMemory(entry)
        workingMemory.recordMessage(state.task.id, senderId.ifEmpty { "system" }, agentId)
    }

    protected fun recordDecision(state: State, description: String, proposedBy: String, reasoning: String = "") {
        workingMemory.addDecision(state.task.id, description, proposedBy, reasoning)
    }

    protected fun recordContext(state: State, key: String, value: String, source: String = "", category: ContextCategory = ContextCategory.GENERAL) {
        workingMemory.addContext(state.task.id, key, value, source, category)
    }

    protected fun recordProgress(state: State, agentId: String, taskRef: String, status: String, progress: Float, note: String = "") {
        workingMemory.recordProgress(state.task.id, agentId, taskRef, status, progress, note)
        updateAgentProgress(state, agentId, progress)
    }

    protected fun recordArtifact(state: State, name: String, type: ArtifactType, content: String, createdBy: String) {
        workingMemory.addArtifact(state.task.id, name, type, content, createdBy)
    }

    protected fun getSessionSummary(state: State): String {
        return workingMemory.generateSummary(state.task.id)
    }

    protected fun getNextAgent(state: State, excludeIds: Set<String> = emptySet()): Agent? {
        return state.agents.firstOrNull { it.id !in excludeIds && state.agentStates[it.id]?.status == AgentStatus.IDLE }
    }

    protected fun getSupervisorAgent(state: State): Agent? {
        val supervisorRole = context.getString(R.string.role_supervisor)
        val coordinatorRole = context.getString(R.string.role_coordinator)
        return state.agents.firstOrNull { it.role.contains(supervisorRole) || it.role.contains(coordinatorRole) }
    }

    protected fun incrementStep(state: State): Int {
        return state.currentStep.incrementAndGet()
    }

    protected fun getStep(state: State): Int {
        return state.currentStep.get()
    }

    protected fun getAgentState(state: State, agentId: String): AgentExecutionState? {
        return state.agentStates[agentId]
    }

    protected fun areAllAgentsFinished(state: State): Boolean {
        return state.agentStates.values.all { it.status == AgentStatus.IDLE || it.status == AgentStatus.FINISHED }
    }

    protected fun areAllAgentsWorking(state: State): Boolean {
        return state.agentStates.values.all { it.status == AgentStatus.WORKING }
    }

    protected fun createAgentMessage(senderId: String, receiverId: String, content: String): AgentMessage {
        return AgentMessage(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            receiverId = receiverId,
            content = content,
            timestamp = System.currentTimeMillis()
        )
    }
        private suspend fun pullSharedMemories(state: State) {
        val taskMemories = sharedMemoryPool.getUnreadMemoriesForAgent(
            state.task.id, "collaboration_mode"
        )
        for (entry in taskMemories) {
            val targetAgent = state.agents.firstOrNull {
                it.role.equals(entry.agentRole, ignoreCase = true) ||
                state.agentStates[it.id]?.status == AgentStatus.IDLE
            }
        if (targetAgent != null) {
                val current = state.agentStates[targetAgent.id] ?: continue
                state.agentStates[targetAgent.id] = current.copy(
                    messages = current.messages + createAgentMessage(
                        entry.agentRole, targetAgent.id, entry.content
                    )
                )
            }
        }
    }
}
