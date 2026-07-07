package com.apex.agent.core.multiagent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MessageBus(private val context: android.content.Context) {

    private val messagesDir: File
        get() = File(context.filesDir, "message_bus").also {
            if (!it.exists()) it.mkdirs()
        }

    private val _messages = MutableStateFlow<Map<String, BusMessage>>(emptyMap())
    val messages: StateFlow<List<BusMessage>> = _messages.map { it.values.toList() }.stateIn(
        scope, SharingStarted.Eagerly, emptyList()
    )

    private val _subscribers = ConcurrentHashMap<String, MutableSharedFlow<BusMessage>>()
    private val _pendingMessages = MutableStateFlow<Map<String, BusMessage>>(emptyMap())
    val pendingMessages: StateFlow<List<BusMessage>> = _pendingMessages.map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val batchSaveChannel = Channel<BusMessage>(Channel.BUFFERED)
    private val batchSize = 20
    private val batchIntervalMs = 1000L

    private val cache = object : LinkedHashMap<String, BusMessage>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BusMessage>): Boolean =
            size > 150
    }

    init {
        scope.launch { processBatchSave() }
        scope.launch { loadMessages() }
    }

    private suspend fun loadMessages() {
        val loaded = withContext(Dispatchers.IO) {
            messagesDir.listFiles { _, name -> name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(200)
                ?.mapNotNull { file ->
                    try { parseMessage(JSONObject(file.readText())) }
                    catch (_: Exception) { null }
                } ?: emptyList()
        }
        val map = loaded.associateBy { it.id }
        _messages.value = map
        synchronized(cache) { cache.putAll(map) }
    }

    suspend fun sendMessage(
        senderId: String,
        recipientId: String?,
        content: String,
        type: MessageType,
        attachments: List<String> = emptyList(),
        metadata: Map<String, Any> = emptyMap()
    ): Result<BusMessage> {
        return try {
            val message = BusMessage(
                id = UUID.randomUUID().toString(),
                senderId = senderId,
                recipientId = recipientId,
                content = content,
                type = type,
                attachments = attachments,
                metadata = metadata,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.PENDING
            )
            addToInMemory(message)
            batchSaveChannel.send(message)
            notifyRecipient(message)
            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun addToInMemory(message: BusMessage) {
        synchronized(cache) { cache[message.id] = message }
        _messages.update { it + (message.id to message) }
        if (message.recipientId == null) {
            _pendingMessages.update { it + (message.id to message) }
        }
    }

    private suspend fun processBatchSave() {
        val buffer = mutableListOf<BusMessage>()
        while (true) {
            val result = withTimeoutOrNull(batchIntervalMs) {
                while (buffer.size < batchSize) {
                    buffer.add(batchSaveChannel.receive())
                }
            }
            if (result == null && buffer.isEmpty()) continue
            saveBatch(buffer.toList())
            buffer.clear()
        }
    }

    private fun saveBatch(messages: List<BusMessage>) {
        try {
            messages.forEach { msg ->
                val file = File(messagesDir, "${msg.id}.json")
                if (!file.exists()) {
                    file.writeText(createMessageJson(msg).toString(2))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("MessageBus", "Batch save failed", e)
        }
    }

    private fun notifyRecipient(message: BusMessage) {
        if (message.recipientId != null) {
            _subscribers[message.recipientId]?.tryEmit(message)
        }
        _pendingMessages.update { it - message.senderId }
    }

    fun subscribe(agentId: String): SharedFlow<BusMessage> {
        return _subscribers.getOrPut(agentId) {
            MutableSharedFlow(replay = 10, extraBufferCapacity = 50)
        }
    }

    suspend fun broadcast(
        senderId: String,
        content: String,
        type: MessageType = MessageType.BROADCAST
    ): Result<List<BusMessage>> {
        return try {
            val message = BusMessage(
                id = UUID.randomUUID().toString(),
                senderId = senderId,
                recipientId = null,
                content = content,
                type = type,
                attachments = emptyList(),
                metadata = emptyMap(),
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENT
            )
            addToInMemory(message)
            batchSaveChannel.send(message)
            _subscribers.values.forEach { it.tryEmit(message) }
            Result.success(listOf(message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMessagesForAgent(agentId: String, limit: Int = 50): List<BusMessage> =
        withContext(Dispatchers.Default) {
            _messages.value.values
                .filter { msg ->
                    msg.senderId == agentId || msg.recipientId == agentId || msg.recipientId == null
                }
                .sortedByDescending { it.timestamp }
                .take(limit)
        }

    suspend fun markAsRead(messageId: String): Result<Unit> {
        return try {
            _messages.update { map ->
                map[messageId]?.let { msg ->
                    val updated = msg.copy(status = MessageStatus.READ)
                    map + (messageId to updated)
                } ?: map
            }
            _messages.value[messageId]?.let { msg ->
                scope.launch(Dispatchers.IO) {
                    val file = File(messagesDir, "${msg.id}.json")
                    if (file.exists()) file.writeText(createMessageJson(msg).toString(2))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMessage(messageId: String): Result<Unit> {
        return try {
            synchronized(cache) { cache.remove(messageId) }
            _messages.update { it - messageId }
            _pendingMessages.update { it - messageId }
            scope.launch(Dispatchers.IO) {
                val file = File(messagesDir, "${messageId}.json")
                if (file.exists()) file.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createMessageJson(message: BusMessage): JSONObject {
        return JSONObject().apply {
            put("id", message.id)
            put("senderId", message.senderId)
            put("recipientId", message.recipientId ?: JSONObject.NULL)
            put("content", message.content)
            put("type", message.type.name)
            put("attachments", JSONArray(message.attachments))
            val metadataObj = JSONObject()
            message.metadata.forEach { (key, value) ->
                when (value) {
                    is String -> metadataObj.put(key, value)
                    is Number -> metadataObj.put(key, value)
                    is Boolean -> metadataObj.put(key, value)
                }
            }
            put("metadata", metadataObj)
            put("timestamp", message.timestamp)
            put("status", message.status.name)
        }
    }

    private fun parseMessage(json: JSONObject): BusMessage {
        val metadataMap = mutableMapOf<String, Any>()
        json.optJSONObject("metadata")?.let { metadataObj ->
            metadataObj.keys().forEach { key ->
                metadataMap[key] = metadataObj.get(key)
            }
        }
        return BusMessage(
            id = json.getString("id"),
            senderId = json.getString("senderId"),
            recipientId = if (json.isNull("recipientId")) null else json.getString("recipientId"),
            content = json.getString("content"),
            type = MessageType.valueOf(json.getString("type")),
            attachments = (0 until (json.optJSONArray("attachments")?.length() ?: 0))
                .map { json.getJSONArray("attachments").getString(it) },
            metadata = metadataMap,
            timestamp = json.getLong("timestamp"),
            status = MessageStatus.valueOf(json.getString("status"))
        )
    }

    fun cleanup() {
        scope.cancel()
        batchSaveChannel.close()
        _subscribers.clear()
    }
}

data class BusMessage(
    val id: String,
    val senderId: String,
    val recipientId: String?,
    val content: String,
    val type: MessageType,
    val attachments: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap(),
    val timestamp: Long,
    val status: MessageStatus
)

enum class MessageType {
    REQUEST, RESPONSE, BROADCAST, NOTIFICATION, ERROR, UPDATE, APPROVAL, REJECTION, FEEDBACK
}

enum class MessageStatus {
    PENDING, SENT, DELIVERED, READ, FAILED
}

private object AppLogger {
    fun e(tag: String, msg: String, e: Exception? = null) {
        android.util.Log.e(tag, msg, e)
    }
}
