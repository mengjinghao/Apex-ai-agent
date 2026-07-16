package com.apex.agent.orchestration.collaboration

import com.apex.agent.common.result.Result
import com.apex.agent.domain.repository.TaskRepository
import com.apex.agent.infrastructure.eventbus.EventBus
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Singleton
class TaskLifecycleManager constructor(
    private val taskRepository: TaskRepository,
    private val eventBus: EventBus
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskStates = ConcurrentHashMap<String, TaskState>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeExecutors = ConcurrentHashMap<String, TaskExecutor>()

    fun getTaskState(taskId: String): TaskState {
        return taskStates[taskId] ?: TaskState.PENDING
    }

    suspend fun start(taskId: String, executor: TaskExecutor): Result<Unit> {
        if (activeJobs.containsKey(taskId)) {
            return Result.Success(Unit)
        }

        val taskResult = taskRepository.getById(taskId)
        val task = when (taskResult) {
            is Result.Success -> taskResult.data
            is Result.Failure -> return Result.Failure(taskResult.error)
        }

        if (taskStates[taskId] == TaskState.RUNNING) {
            return Result.Success(Unit)
        }

        updateTaskState(task, TaskState.RUNNING)
        taskStates[taskId] = TaskState.RUNNING
        eventBus.publish(CollaborationEvent.TaskStarted(taskId))

        activeExecutors[taskId] = executor

        val job = scope.launch {
            try {
                executor.execute(task).collect { result ->
                    when (result) {
                        is Result.Success -> {
                            updateTaskState(result.data, TaskState.RUNNING)
                        }
                        is Result.Failure -> {
                            updateTaskState(task.copy(status = TaskState.FAILED.name), TaskState.FAILED)
                            eventBus.publish(CollaborationEvent.TaskFailed(taskId, result.error.message ?: "Unknown error"))
                        }
                    }
                }

                if (taskStates[taskId] != TaskState.FAILED && taskStates[taskId] != TaskState.CANCELLED) {
                    updateTaskState(task.copy(status = TaskState.COMPLETED.name), TaskState.COMPLETED)
                    eventBus.publish(CollaborationEvent.TaskCompleted(taskId))
                }
            } catch (e: CancellationException) {
                // 任务被取消，不标记为失败
            } catch (e: Exception) {
                updateTaskState(task.copy(status = TaskState.FAILED.name), TaskState.FAILED)
                eventBus.publish(CollaborationEvent.TaskFailed(taskId, e.message ?: "Unknown error"))
            } finally {
                activeJobs.remove(taskId)
                activeExecutors.remove(taskId)
            }
        }

        activeJobs[taskId] = job
        return Result.Success(Unit)
    }

    suspend fun pause(taskId: String): Result<Unit> {
        val executor = activeExecutors[taskId]
            ?: return Result.Failure(IllegalStateException("No active executor for task: ${taskId}"))

        return when (val result = executor.pause(taskId)) {
            is Result.Success -> {
                taskStates[taskId] = TaskState.PAUSED
                eventBus.publish(CollaborationEvent.TaskPaused(taskId))
                Result.Success(Unit)
            }
            is Result.Failure -> result
        }
    }

    suspend fun resume(taskId: String): Result<Unit> {
        val executor = activeExecutors[taskId]
            ?: return Result.Failure(IllegalStateException("No active executor for task: ${taskId}"))

        return when (val result = executor.resume(taskId)) {
            is Result.Success -> {
                taskStates[taskId] = TaskState.RUNNING
                eventBus.publish(CollaborationEvent.TaskResumed(taskId))
                Result.Success(Unit)
            }
            is Result.Failure -> result
        }
    }

    suspend fun cancel(taskId: String): Result<Unit> {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        activeExecutors.remove(taskId)
        taskStates[taskId] = TaskState.CANCELLED

        val taskResult = taskRepository.getById(taskId)
        if (taskResult is Result.Success) {
            updateTaskState(taskResult.data.copy(status = TaskState.CANCELLED.name), TaskState.CANCELLED)
        }
        eventBus.publish(CollaborationEvent.TaskStopped(taskId))
        return Result.Success(Unit)
    }

    private suspend fun updateTaskState(task: com.apex.agent.domain.entity.Task, state: TaskState) {
        taskStates[task.id] = state
        val updated = task.copy(
            status = state.name,
            updatedAt = System.currentTimeMillis()
        )
        withContext(Dispatchers.IO) {
            taskRepository.update(updated)
        }
    }
}
