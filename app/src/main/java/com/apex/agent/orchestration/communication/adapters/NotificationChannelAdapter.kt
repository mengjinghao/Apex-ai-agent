package com.apex.agent.orchestration.communication.adapters

import android.content.Context
import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.orchestration.communication.ChannelAdapter
import com.apex.agent.orchestration.communication.CommunicationChannel
import javax.inject.Inject

class NotificationChannelAdapter @Inject constructor(
    private val context: Context
) : ChannelAdapter {
    override val channel = CommunicationChannel.NOTIFICATION
    override val name = "通知"

    private var messageCallback: ((AgentMessage) -> Unit)? = null
    private var initialized = false

    override suspend fun sendMessage(message: AgentMessage): Result<Boolean> {
        if (!initialized) return Result.Success(false)
        return try {
            Result.Success(true)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override fun receiveMessage(callback: (AgentMessage) -> Unit) {
        this.messageCallback = callback
    }

    override suspend fun isAvailable() = initialized

    override fun initialize() {
        initialized = true
    }

    override fun shutdown() {
        messageCallback = null
        initialized = false
    }
}
