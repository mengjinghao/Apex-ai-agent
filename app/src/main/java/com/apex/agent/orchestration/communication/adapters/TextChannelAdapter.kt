package com.apex.agent.orchestration.communication.adapters

import android.content.Context
import android.widget.Toast
import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.orchestration.communication.ChannelAdapter
import com.apex.agent.orchestration.communication.CommunicationChannel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TextChannelAdapter constructor(
    private val context: Context
) : ChannelAdapter {
    override val channel = CommunicationChannel.TEXT
    override val name = "文本"

    private var messageCallback: ((AgentMessage) -> Unit)? = null
    private var initialized = false
    private val messageQueue = mutableListOf<AgentMessage>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override suspend fun sendMessage(message: AgentMessage): Result<Boolean> {
        if (!initialized) return Result.Success(false)
        return try {
            coroutineScope.launch {
                delay(500)
                Toast.makeText(context, "收到消息: ${message.content}", Toast.LENGTH_SHORT).show()
                simulateReply(message)
            }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override fun receiveMessage(callback: (AgentMessage) -> Unit) {
        this.messageCallback = callback
        synchronized(messageQueue) {
            messageQueue.forEach { callback(it) }
            messageQueue.clear()
        }
    }

    override suspend fun isAvailable() = initialized

    override fun initialize() {
        initialized = true
    }

    override fun shutdown() {
        messageCallback = null
        messageQueue.clear()
        initialized = false
    }

    private fun simulateReply(originalMessage: AgentMessage) {
        val replyContent = when {
            originalMessage.content.contains("你好", ignoreCase = true) -> "你好！很高兴为你服务�?
            originalMessage.content.contains("谢谢", ignoreCase = true) -> "不客气！有问题随时问我�?
            originalMessage.content.contains("时间", ignoreCase = true) -> "现在时间�?${java.time.LocalTime.now()}"
            else -> "已收到你的消�? \"${originalMessage.content}\""
        }

        val replyMessage = AgentMessage(
            id = UUID.randomUUID().toString(),
            senderId = "system",
            receiverId = originalMessage.senderId,
            content = replyContent,
            timestamp = System.currentTimeMillis()
        )

        synchronized(messageQueue) {
            messageCallback?.invoke(replyMessage) ?: messageQueue.add(replyMessage)
        }
    }
}
