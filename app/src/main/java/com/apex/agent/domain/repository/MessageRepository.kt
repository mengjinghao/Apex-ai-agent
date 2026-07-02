package com.apex.agent.domain.repository

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun send(message: AgentMessage): Result<Unit>
    fun getMessagesForTask(taskId: String): Flow<Result<List<AgentMessage>>>
}
