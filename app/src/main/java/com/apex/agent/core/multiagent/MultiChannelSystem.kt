package com.apex.agent.core.multiagent

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class CommunicationChannel {
    TEXT, VOICE, NOTIFICATION, PUSH, WEBHOOK
}

data class ChannelMessage(
    val id: String = UUID.randomUUID().toString(),
    val channel: CommunicationChannel,
    val content: String,
    val sender: String,
    val receiver: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
)

interface ChannelAdapter {
    val channel: CommunicationChannel
    val name: String
    fun sendMessage(message: ChannelMessage): Boolean
    fun receiveMessage(callback: (ChannelMessage) -> Unit)
    fun isAvailable(): Boolean
    fun initialize()
    fun shutdown()
}

class MultiChannelSystem(private val context: Context) {

    companion object {
        private const val TAG = "MultiChannelSystem"
    }

    private val adapters = mutableMapOf<CommunicationChannel, ChannelAdapter>()
    private val _messages = MutableStateFlow<List<ChannelMessage>>(emptyList())
    val messages: StateFlow<List<ChannelMessage>> = _messages

    fun registerAdapter(adapter: ChannelAdapter) {
        adapters[adapter.channel] = adapter
        adapter.initialize()
    }

    fun unregisterAdapter(channel: CommunicationChannel) {
        adapters[channel]?.shutdown()
        adapters.remove(channel)
    }

    fun sendMessage(
        content: String,
        channel: CommunicationChannel = CommunicationChannel.TEXT,
        receiver: String = "user"
    ): Boolean {
        val message = ChannelMessage(
            channel = channel,
            content = content,
            sender = "assistant",
            receiver = receiver
        )

        val adapter = adapters[channel]
        val success = adapter?.isAvailable() == true && adapter.sendMessage(message)

        if (success) {
            addMessage(message)
        }

        return success
    }

    fun broadcastMessage(content: String) {
        adapters.values
            .filter { it.isAvailable() }
            .forEach { sendMessage(content, it.channel) }
    }

    private fun addMessage(message: ChannelMessage) {
        val current = _messages.value.toMutableList()
        current.add(message)
        if (current.size > 100) {
            current.removeAt(0)
        }
        _messages.value = current
    }

    fun getAvailableChannels(): List<CommunicationChannel> {
        return adapters.values.filter { it.isAvailable() }.map { it.channel }
    }
}

class TextChannelAdapter(private val context: Context) : ChannelAdapter {
    override val channel = CommunicationChannel.TEXT
    override val name = "文本"

    private var messageCallback: ((ChannelMessage) -> Unit)? = null
    private var initialized = false
    private val messageQueue = mutableListOf<ChannelMessage>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun sendMessage(message: ChannelMessage): Boolean {
        if (!initialized) {
            return false
        }
        
        try {
            coroutineScope.launch {
                delay(500)
                
                Toast.makeText(context, "收到消息: ${message.content}", Toast.LENGTH_SHORT).show()
                
                simulateReply(message)
            }
            
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun receiveMessage(callback: (ChannelMessage) -> Unit) {
        this.messageCallback = callback
        
        synchronized(messageQueue) {
            messageQueue.forEach { callback(it) }
            messageQueue.clear()
        }
    }

    override fun isAvailable() = initialized

    override fun initialize() {
        initialized = true
    }

    override fun shutdown() {
        messageCallback = null
        messageQueue.clear()
        initialized = false
    }

    private fun simulateReply(originalMessage: ChannelMessage) {
        val replyContent = when {
            originalMessage.content.contains("你好", ignoreCase = true) -> "你好！很高兴为你服务�?            originalMessage.content.contains("谢谢", ignoreCase = true) -> "不客气！有问题随时问我，
            originalMessage.content.contains("时间", ignoreCase = true) -> "现在时间�?{java.time.LocalTime.now()}"
            else -> "已收到你的消�?\"${originalMessage.content}\""
        }

        val replyMessage = ChannelMessage(
            channel = CommunicationChannel.TEXT,
            content = replyContent,
            sender = "system",
            receiver = originalMessage.sender,
            timestamp = System.currentTimeMillis()
        )

        synchronized(messageQueue) {
            messageCallback?.invoke(replyMessage) ?: messageQueue.add(replyMessage)
        }
    }
}

class VoiceChannelAdapter(private val context: Context) : ChannelAdapter {
    override val channel = CommunicationChannel.VOICE
    override val name = "语音"

    private var messageCallback: ((ChannelMessage) -> Unit)? = null
    private var initialized = false

    override fun sendMessage(message: ChannelMessage): Boolean {
        if (!initialized) {
            return false
        }
        
        try {
            Toast.makeText(context, "语音消息: ${message.content}", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun receiveMessage(callback: (ChannelMessage) -> Unit) {
        this.messageCallback = callback
    }

    override fun isAvailable() = initialized

    override fun initialize() {
        initialized = true
    }

    override fun shutdown() {
        messageCallback = null
        initialized = false
    }
}