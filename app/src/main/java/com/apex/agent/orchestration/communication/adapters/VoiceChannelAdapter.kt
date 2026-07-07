package com.apex.agent.orchestration.communication.adapters

import android.content.Context
import android.widget.Toast
import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.orchestration.communication.ChannelAdapter
import com.apex.agent.orchestration.communication.CommunicationChannel
import javax.inject.Inject

class VoiceChannelAdapter @Inject constructor(
    private val context: Context
) : ChannelAdapter {
    override val channel = CommunicationChannel.VOICE
    override val name = "语音"

    private var messageCallback: ((AgentMessage) -> Unit)? = null
    private var initialized = false

    override suspend fun sendMessage(message: AgentMessage): Result<Boolean> {
        if (!initialized) return Result.Success(false)
        return try {
            Toast.makeText(context, "语音消息: ${message.content}", Toast.LENGTH_SHORT).show()
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
