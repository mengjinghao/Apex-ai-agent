package com.apex.agent.orchestration.communication

import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage

interface ChannelAdapter {
    val channel: CommunicationChannel
    val name: String
    suspend fun sendMessage(message: AgentMessage): Result<Boolean>
    fun receiveMessage(callback: (AgentMessage) -> Unit)
    suspend fun isAvailable(): Boolean
    fun initialize()
    fun shutdown()
}
