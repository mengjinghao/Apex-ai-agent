package com.apex.agent.orchestration.collaboration

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.domain.entity.Task
import kotlinx.coroutines.flow.Flow

interface TaskExecutor {
    suspend fun execute(task: Task): Flow<Result<Task>>
    suspend fun pause(taskId: String): Result<Unit>
    suspend fun resume(taskId: String): Result<Unit>
    suspend fun cancel(taskId: String): Result<Unit>

    suspend fun onMessageReceived(taskId: String, message: AgentMessage): Result<Unit> = Result.Success(Unit)
}
