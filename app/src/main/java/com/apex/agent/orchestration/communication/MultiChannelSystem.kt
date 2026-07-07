package com.apex.agent.orchestration.communication

import android.content.Context
import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class MultiChannelSystem @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val MAX_MESSAGES = 100
    }

    private val adapters = mutableMapOf<CommunicationChannel, ChannelAdapter>()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    fun registerAdapter(adapter: ChannelAdapter) {
        adapters[adapter.channel] = adapter
        adapter.initialize()
    }

    fun unregisterAdapter(channel: CommunicationChannel) {
        adapters[channel]?.shutdown()
        adapters.remove(channel)
    }

    suspend fun sendMessage(
        content: String,
        channel: CommunicationChannel = CommunicationChannel.TEXT,
        receiverId: String = "user",
        senderId: String = "assistant"
    ): Boolean {
        val agentMessage = AgentMessage(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            receiverId = receiverId,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        return sendAgentMessage(agentMessage, channel)
    }

    suspend fun sendAgentMessage(
        agentMessage: AgentMessage,
        channel: CommunicationChannel = CommunicationChannel.TEXT
    ): Boolean {
        val adapter = adapters[channel] ?: return false
        if (!adapter.isAvailable()) return false

        return when (val result = adapter.sendMessage(agentMessage)) {
            is Result.Success -> {
                if (result.data) {
                    addMessage(Message(channel = channel, agentMessage = agentMessage))
                }
                result.data
            }
            is Result.Failure -> false
        }
    }

    suspend fun broadcastMessage(
        content: String,
        receiverId: String = "user",
        senderId: String = "assistant"
    ) {
        adapters.values
            .filter { it.isAvailable() }
            .forEach { adapter ->
                sendMessage(content, adapter.channel, receiverId, senderId)
            }
    }

    private fun addMessage(message: Message) {
        val current = _messages.value.toMutableList()
        current.add(message)
        if (current.size > MAX_MESSAGES) {
            current.removeAt(0)
        }
        _messages.value = current
    }

    fun getAvailableChannels(): List<CommunicationChannel> {
        return adapters.values.filter { it.isAvailable() }.map { it.channel }
    }
}
