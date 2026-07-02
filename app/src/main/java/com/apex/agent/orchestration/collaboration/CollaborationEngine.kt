package com.apex.agent.orchestration.collaboration

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.domain.entity.Task
import com.apex.agent.domain.repository.TaskRepository
import com.apex.agent.infrastructure.eventbus.EventBus
import com.apex.agent.orchestration.agent.AgentManager
import com.apex.agent.orchestration.core.AllocationModels.AllocationRequest
import com.apex.agent.orchestration.core.IntelligentTaskAllocator
import com.apex.agent.orchestration.core.TaskComplexityQuantifier
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

@Singleton
class CollaborationEngine @Inject constructor(
    private val taskRepository: TaskRepository,
    private val agentManager: AgentManager,
    private val eventBus: EventBus,
    private val lifecycleManager: TaskLifecycleManager,
    @Named("supervisor") private val supervisorExecutor: TaskExecutor,
    @Named("serial") private val serialExecutor: TaskExecutor,
    @Named("parallel") private val parallelExecutor: TaskExecutor,
    @Named("debate") private val debateExecutor: TaskExecutor,
    @Named("free") private val freeExecutor: TaskExecutor,
    private val taskAllocator: IntelligentTaskAllocator,
    private val complexityQuantifier: TaskComplexityQuantifier
) {

    suspend fun createTask(task: Task): Result<String> {
        val taskId = task.id.ifEmpty { generateUniqueId() }
        val newTask = task.copy(
            id = taskId,
            status = TaskState.PENDING.name,
            updatedAt = System.currentTimeMillis()
        )

        return when (val result = taskRepository.save(newTask)) {
            is Result.Success -> {
                eventBus.publish(CollaborationEvent.TaskCreated(taskId))
                Result.Success(taskId)
            }
            is Result.Failure -> result
        }
    }

    suspend fun startTask(taskId: String): Result<Unit> {
        val taskResult = taskRepository.getById(taskId)
        val task = when (taskResult) {
            is Result.Success -> taskResult.data
            is Result.Failure -> return Result.Failure(taskResult.error)
        }

        val executor = resolveExecutor(task.collaborationMode)
            ?: return Result.Failure(IllegalArgumentException("Unknown collaboration mode: ${task.collaborationMode}"))

        return lifecycleManager.start(taskId, executor)
    }

    suspend fun pauseTask(taskId: String): Result<Unit> {
        return lifecycleManager.pause(taskId)
    }

    suspend fun resumeTask(taskId: String): Result<Unit> {
        return lifecycleManager.resume(taskId)
    }

    suspend fun stopTask(taskId: String): Result<Unit> {
        return lifecycleManager.cancel(taskId)
    }

    suspend fun getTask(taskId: String): Result<Task> {
        return taskRepository.getById(taskId)
    }

    suspend fun getAllTasks(): Result<List<Task>> {
        return taskRepository.list()
    }

    suspend fun submitMessage(taskId: String, message: AgentMessage): Result<Unit> {
        eventBus.publish(CollaborationEvent.MessageSubmitted(taskId, message))
        val executor = resolveExecutorForTask(taskId)
        executor?.onMessageReceived(taskId, message)
        return Result.Success(Unit)
    }

    fun observeEvents(): Flow<CollaborationEvent> {
        return eventBus.subscribe(CollaborationEvent::class.java).filterIsInstance<CollaborationEvent>()
    }

    private fun resolveExecutor(mode: String): TaskExecutor? {
        return when (mode.lowercase()) {
            "supervisor", "supervisor_execution" -> supervisorExecutor
            "serial", "serial_pipeline" -> serialExecutor
            "parallel", "parallel_execution" -> parallelExecutor
            "debate", "debate_review" -> debateExecutor
            "free", "free_dialog" -> freeExecutor
            else -> null
        }
    }

    private suspend fun resolveExecutorForTask(taskId: String): TaskExecutor? {
        val taskResult = taskRepository.getById(taskId)
        val task = taskResult as? Result.Success<Task> ?: return null
        return resolveExecutor(task.data.collaborationMode)
    }

    suspend fun autoAllocateAgents(taskDescription: String, excludedAgentIds: List<String> = emptyList()): Result<com.apex.agent.orchestration.core.AllocationModels.AllocationResult> {
        val complexity = complexityQuantifier.quantifyTask(taskDescription)
        return taskAllocator.allocate(
            AllocationRequest(
                taskDescription = taskDescription,
                requiredSkills = complexity.requiredSkills,
                complexityReport = complexity,
                excludedAgentIds = excludedAgentIds
            )
        )
    }

    suspend fun createTaskWithAllocation(task: Task, description: String): Result<String> {
        val taskResult = createTask(task)
        if (taskResult is Result.Failure) return taskResult
        val taskId = when (taskResult) {
            is Result.Success -> taskResult.data
            is Result.Failure -> task.id
        }
        val allocation = autoAllocateAgents(description)
        if (allocation is Result.Success) {
            eventBus.publish(
                CollaborationEvent.MessageSubmitted(
                    taskId,
                    com.apex.agent.domain.entity.AgentMessage(
                        senderId = "system",
                        recipientId = allocation.data.selectedAgentId,
                        content = "Auto-allocated: ${allocation.data.reasoning}"
                    )
                )
            )
        }
        return taskResult
    }

    private fun generateUniqueId(): String {
        return "task_" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt()
    }
}
