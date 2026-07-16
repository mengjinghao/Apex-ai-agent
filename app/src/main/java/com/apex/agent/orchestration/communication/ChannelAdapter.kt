package com.apex.agent.orchestration.communication

import com.apex.agent.common.result.Result
import com.apex.agent.AgentMessage

    fun receiveMessage(callback: (AgentMessage) -> Unit)
    suspend fun isAvailable(): Boolean
    fun initialize()
    fun shutdown()
}
