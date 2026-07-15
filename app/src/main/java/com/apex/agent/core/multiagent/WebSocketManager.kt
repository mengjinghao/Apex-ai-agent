package com.apex.agent.core.multiagent

import com.apex.util.AppLogger
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WebSocketManager {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PING_INTERVAL_MS = 30000L
    }
        private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private var currentTaskId: String? = null

    private val messageListeners = ConcurrentHashMap<String, MessageListener>()
        private val pendingMessages = ConcurrentHashMap<String, QueuedMessage>()
        private val sentMessages = ConcurrentHashMap<String, Long>()
        private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()
        fun connect(taskId: String, serverUrl: String): Boolean {
        currentTaskId = taskId

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Task-ID", taskId)
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener())
        return true
    }
        fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        currentTaskId = null
        reconnectAttempts = 0
        pendingMessages.clear()
        sentMessages.clear()
    }
        fun sendMessage(message: WebSocketMessage): Boolean {
        if (!isConnected) {
            queueMessage(message)
        return false
        }
        val messageJson = message.toJson()
        val sent = webSocket?.send(messageJson) ?: false

        if (sent) {
            sentMessages[message.messageId] = System.currentTimeMillis()
        if (message.requiresAck) {
                pendingMessages[message.messageId] = QueuedMessage(message, System.currentTimeMillis())
            }
        } else {
            queueMessage(message)
        }
        return sent
    }
        fun sendAgentStatusUpdate(agentId: String, status: AgentStatus, taskId: String) {
        val message = WebSocketMessage(
            type = MessageType.AGENT_STATUS_UPDATE,
            taskId = taskId,
            payload = mapOf(
                "agentId" to agentId,
                "status" to status.name,
                "timestamp" to System.currentTimeMillis()
            )
        )
        sendMessage(message)
    }
        fun sendTaskProgress(taskId: String, progress: Float) {
        val message = WebSocketMessage(
            type = MessageType.TASK_PROGRESS,
            taskId = taskId,
            payload = mapOf(
                "progress" to progress,
                "timestamp" to System.currentTimeMillis()
            )
        )
        sendMessage(message)
    }
        fun sendAgentMessage(message: AgentMessage, taskId: String) {
        val webSocketMessage = WebSocketMessage(
            type = MessageType.AGENT_MESSAGE,
            taskId = taskId,
            payload = mapOf(
                "sender" to message.sender,
                "content" to message.content,
                "timestamp" to message.timestamp,
                "messageType" to message.type.name
            ),
            requiresAck = true
        )
        sendMessage(webSocketMessage)
    }
        fun sendResourceRequest(agentId: String, resourceType: String, taskId: String) {
        val message = WebSocketMessage(
            type = MessageType.RESOURCE_REQUEST,
            taskId = taskId,
            payload = mapOf(
                "agentId" to agentId,
                "resourceType" to resourceType,
                "timestamp" to System.currentTimeMillis()
            ),
            requiresAck = true
        )
        sendMessage(message)
    }
        fun addMessageListener(taskId: String, listener: MessageListener) {
        messageListeners[taskId] = listener
    }
        fun removeMessageListener(taskId: String) {
        messageListeners.remove(taskId)
    }
        private fun queueMessage(message: WebSocketMessage) {
        pendingMessages[message.messageId] = QueuedMessage(message, System.currentTimeMillis())
    }
        private fun retryPendingMessages() {
        val currentTime = System.currentTimeMillis()
        val timeout = 30000L

        pendingMessages.entries.removeIf { (messageId, queuedMessage) ->
            if (currentTime - queuedMessage.timestamp > timeout) {
                if (queuedMessage.retryCount < MAX_RECONNECT_ATTEMPTS) {
                    pendingMessages[messageId] = queuedMessage.copy(retryCount = queuedMessage.retryCount + 1)
                    webSocket?.send(queuedMessage.message.toJson())
                    false
                } else {
                    messageListeners.values.forEach { it.onMessageSendFailed(queuedMessage.message)
                        true
                    }
                }
            } else {
                false
            }
        }
    }
        private fun handleAck(messageId: String) {
        pendingMessages.remove(messageId)
        sentMessages.remove(messageId)
    }
        private fun handleNack(messageId: String, reason: String) {
        val queuedMessage = pendingMessages[messageId]
        if (queuedMessage != null && queuedMessage.retryCount < MAX_RECONNECT_ATTEMPTS) {
            pendingMessages[messageId] = queuedMessage.copy(retryCount = queuedMessage.retryCount + 1)
            webSocket?.send(queuedMessage.message.toJson())
        } else {
            messageListeners.values.forEach { it.onMessageSendFailed(queuedMessage?.message ?: return@forEach) }
            pendingMessages.remove(messageId)
        }
    }
        private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.d(TAG, "WebSocket connected")
                isConnected = true
                reconnectAttempts = 0

                currentTaskId?.let { taskId ->
                    messageListeners[taskId]?.onConnected()
                }

                resendPendingMessages()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLogger.d(TAG, "Received message: ${text}")
                handleReceivedMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                AppLogger.d(TAG, "Received binary message: ${bytes.hex()}")
                handleReceivedMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.d(TAG, "WebSocket closing: ${code} - ${reason}")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.d(TAG, "WebSocket closed: ${code} - ${reason}")
                isConnected = false
                handleDisconnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response) {
                AppLogger.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                handleDisconnection()
            }
        }
    }
        private fun handleReceivedMessage(text: String) {
        try {
            val json = JSONObject(text)
        val type = json.getString("type")
        val messageId = json.optString("messageId", "")
        val taskId = json.optString("taskId", "")
        when (type) {
                MessageType.ACK.name -> {
                    handleAck(messageId)
                }
                MessageType.NACK.name -> {
                    val reason = json.optString("reason", "Unknown error")
                    handleNack(messageId, reason)
                }
                else -> {
                    val payload = json.optJSONObject("payload")
        val message = WebSocketMessage(
                        type = MessageType.valueOf(type),
                        messageId = messageId,
                        taskId = taskId,
                        payload = payload?.toMap() ?: emptyMap()
                    )
                    messageListeners[taskId]?.onMessageReceived(message)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling received message: ${e.message}")
        }
    }
        private fun handleDisconnection() {
        currentTaskId?.let { taskId ->
            messageListeners[taskId]?.onDisconnected()
        }
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            AppLogger.d(TAG, "Attempting to reconnect... (${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})")

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                currentTaskId?.let { taskId ->
                    connect(taskId, "wss://default-server.com/ws")
                }
            }, RECONNECT_DELAY_MS * reconnectAttempts)
        } else {
            AppLogger.e(TAG, "Max reconnect attempts reached")
            currentTaskId?.let { taskId ->
                messageListeners[taskId]?.onConnectionFailed("Max reconnect attempts reached")
            }
        }
    }
        private fun resendPendingMessages() {
        pendingMessages.values.forEach { queuedMessage ->
            webSocket?.send(queuedMessage.message.toJson())
        }
    }

    interface MessageListener {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionFailed(error: String)
        fun onMessageReceived(message: WebSocketMessage)
        fun onMessageSendFailed(message: WebSocketMessage)
    }

    data class QueuedMessage(
        val message: WebSocketMessage,
        val timestamp: Long,
        val retryCount: Int = 0
    )
}

enum class MessageType {
    AGENT_STATUS_UPDATE,
    TASK_PROGRESS,
    AGENT_MESSAGE,
    RESOURCE_REQUEST,
    ACK,
    NACK,
    PING,
    PONG
}

data class WebSocketMessage(
    val type: MessageType,
    val messageId: String = "msg_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}",
    val taskId: String = "",
    val payload: Map<String, Any> = emptyMap(),
    val requiresAck: Boolean = false
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type.name)
        json.put("messageId", messageId)
        json.put("taskId", taskId)
        json.put("requiresAck", requiresAck)
        val payloadJson = JSONObject()
        payload.forEach { (key, value) ->
            payloadJson.put(key, value)
        }
        json.put("payload", payloadJson)
        return json.toString()
    }
}

fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        map[key] = get(key)
    }
        return map
}