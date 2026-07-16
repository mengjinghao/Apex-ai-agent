package com.apex.agent.orchestration.collaboration

import com.apex.agent.common.result.Result
import com.apex.agent.AgentMessage
import com.apex.agent.core.task.Task
import kotlinx.coroutines.flow.Flow


    suspend fun onMessageReceived(taskId: String, message: AgentMessage): Result<Unit> = Result.Success(Unit)
}
