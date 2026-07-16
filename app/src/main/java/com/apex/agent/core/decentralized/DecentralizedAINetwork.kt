package com.apex.agent.core.decentralized

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

class DecentralizedAINetwork(private val context: Context) {

    private val TAG = "DecentralizedAI"

    enum class NodeType {
        PEER,
        SERVER,
        BRIDGE,
        RELAY,
        BOOTSTRAP
    }

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
        BLOCKED
    }

    enum class MessagePriority {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }

    data class Node(
        val id: String,
        val name: String,
        val nodeType: NodeType,
        val address: String,
        val publicKey: String,
        val reputation: Float = 0.5f,
        val lastSeen: Long = 0L,
        val isOnline: Boolean = false,
        val capabilities: List<String>,
        val connectionCount: Int = 0,
        val version: String = "1.0.0"
    )

    data class NetworkMessage(
        val id: String,
        val type: MessageType,
        val senderId: String,
        val recipientId: String?,
        val content: String,
        val timestamp: Long,
        val signature: String,
        val priority: MessagePriority = MessagePriority.NORMAL,
        val isBroadcast: Boolean = false,
        val ttl: Int = 10
    )

    enum class MessageType {
        PING,
        PONG,
        JOIN,
        LEAVE,
        PEER_DISCOVERY,
        DATA_REQUEST,
        DATA_RESPONSE,
        MODEL_SHARE,
        INFERENCE_REQUEST,
        INFERENCE_RESPONSE,
        CONSENSUS_PROPOSAL,
        CONSENSUS_VOTE,
        STATUS_UPDATE,
        WARNING,
        ERROR
    }

    data class PeerConnection(
        val id: String,
        val node: Node,
        val status: ConnectionStatus,
        val establishedAt: Long,
        val lastActiveAt: Long,
        val bytesSent: Long = 0,
        val bytesReceived: Long = 0,
        val latency: Long = 0
    )

    data class SharedModel(
        val id: String,
        val name: String,
        val creatorNodeId: String,
        val sizeBytes: Long,
        val version: String,
        val downloadCount: Int = 0,
        val hash: String,
        val tags: List<String>,
        val availableAt: List<String>
    )

    data class InferenceJob(
        val id: String,
        val requesterId: String,
        val modelId: String,
        val inputData: String,
        val assignedNodes: List<String>,
        val status: JobStatus,
        val priority: MessagePriority,
        val submittedAt: Long,
        val completedAt: Long? = null,
        val results: Map<String, String> = emptyMap(),
        val consensusReached: Boolean = false,
        val reward: Float = 0f
    )

    enum class JobStatus {
        PENDING,
        ASSIGNED,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class ReputationTransaction(
        val id: String,
        val fromNode: String,
        val toNode: String,
        val amount: Float,
        val reason: String,
        val timestamp: Long,
        val signature: String
    )

    data class LedgerEntry(
        val id: String,
        val type: LedgerType,
        val data: String,
        val timestamp: Long,
        val hash: String,
        val previousHash: String,
        val signers: List<String>
    )

    enum class LedgerType {
        INFERENCE,
        REPUTATION,
        MODEL_SHARE,
        JOIN_LEAVE,
        CONSENSUS
    }

    private val nodesDir: File
        get() = File(context.filesDir, "network_nodes").also {
            if (!it.exists()) it.mkdirs()
        }

    private val messagesDir: File
        get() = File(context.filesDir, "network_messages").also {
            if (!it.exists()) it.mkdirs()
        }

    private val ledgerDir: File
        get() = File(context.filesDir, "network_ledger").also {
            if (!it.exists()) it.mkdirs()
        }

    private val modelsDir: File
        get() = File(context.filesDir, "network_models").also {
            if (!it.exists()) it.mkdirs()
        }

    private val knownNodes = mutableMapOf<String, Node>()
    private val activeConnections = mutableMapOf<String, PeerConnection>()
    private val messageQueue = mutableListOf<NetworkMessage>()
    private val localLedger = mutableListOf<LedgerEntry>()
    private val sharedModels = mutableMapOf<String, SharedModel>()

    private var localNode: Node? = null
    private var bootstrapPeers: List<String> = emptyList()

    suspend fun initializeLocalNode(
        name: String,
        type: NodeType = NodeType.PEER,
        capabilities: List<String>,
        publicKey: String = ""
    ): Node = withContext(Dispatchers.IO) {
        val nodeId = UUID.randomUUID().toString()

        val node = Node(
            id = nodeId,
            name = name,
            nodeType = type,
            address = "local:${nodeId}",
            publicKey = publicKey.ifEmpty { nodeId },
            capabilities = capabilities,
            isOnline = true
        )

        saveNode(node)
        knownNodes[nodeId] = node
        localNode = node

        node
    }

    private suspend fun saveNode(node: Node) = withContext(Dispatchers.IO) {
        val nodeFile = File(nodesDir, "${node.id}.json")
        val json = JSONObject().apply {
            put("id", node.id)
            put("name", node.name)
            put("nodeType", node.nodeType.name)
            put("address", node.address)
            put("publicKey", node.publicKey)
            put("reputation", node.reputation.toDouble())
            put("lastSeen", node.lastSeen)
            put("isOnline", node.isOnline)
            put("capabilities", JSONArray(node.capabilities))
            put("connectionCount", node.connectionCount)
            put("version", node.version)
        }

        nodeFile.writeText(json.toString(2))
    }

    suspend fun addKnownNode(node: Node): Boolean = withContext(Dispatchers.IO) {
        if (node.id in knownNodes) {
            val existing = knownNodes[node.id]
            if (existing?.lastSeen ?: 0 >= node.lastSeen) {
                return@withContext false
            }
        }

        saveNode(node)
        knownNodes[node.id] = node
        true
    }

    suspend fun getKnownNodes(onlineOnly: Boolean = false): List<Node> = withContext(Dispatchers.IO) {
        val nodes = mutableListOf<Node>()

        nodesDir.listFiles { _, name -> name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val node = Node(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        nodeType = NodeType.valueOf(json.getString("nodeType")),
                        address = json.getString("address"),
                        publicKey = json.getString("publicKey"),
                        reputation = json.getDouble("reputation").toFloat(),
                        lastSeen = json.getLong("lastSeen"),
                        isOnline = json.getBoolean("isOnline"),
                        capabilities = (0 until json.getJSONArray("capabilities").length())
                            .map { json.getJSONArray("capabilities").getString(it) },
                        connectionCount = json.getInt("connectionCount"),
                        version = json.getString("version")
                    )

                    if (!onlineOnly || node.isOnline) {
                        nodes.add(node)
                        knownNodes[node.id] = node
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析节点配置失败: ${file.name}", e)
                }
            }

        nodes
    }

    suspend fun connectToNode(nodeId: String): PeerConnection? = withContext(Dispatchers.IO) {
        val node = knownNodes[nodeId] ?: return@withContext null

        val connection = PeerConnection(
            id = UUID.randomUUID().toString(),
            node = node,
            status = ConnectionStatus.CONNECTING,
            establishedAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis()
        )

        activeConnections[connection.id] = connection

        broadcastMessage(
            type = MessageType.PING,
            content = "ping",
            recipientId = nodeId
        )

        connection
    }

    suspend fun disconnectFromNode(connectionId: String): Boolean = withContext(Dispatchers.IO) {
        val connection = activeConnections[connectionId] ?: return@withContext false

        val updatedConnection = connection.copy(
            status = ConnectionStatus.DISCONNECTED
        )

        activeConnections.remove(connectionId)

        broadcastMessage(
            type = MessageType.LEAVE,
            content = "disconnect",
            recipientId = connection.node.id
        )

        true
    }

    suspend fun broadcastMessage(
        type: MessageType,
        content: String,
        recipientId: String? = null,
        priority: MessagePriority = MessagePriority.NORMAL
    ): NetworkMessage = withContext(Dispatchers.IO) {
        val sender = localNode ?: throw IllegalStateException("本地节点未初始化")

        val message = NetworkMessage(
            id = UUID.randomUUID().toString(),
            type = type,
            senderId = sender.id,
            recipientId = recipientId,
            content = content,
            timestamp = System.currentTimeMillis(),
            signature = calculateSignature(sender.id, content),
            priority = priority,
            isBroadcast = recipientId == null
        )

        messageQueue.add(message)
        saveMessage(message)

        message
    }

    private suspend fun saveMessage(message: NetworkMessage) = withContext(Dispatchers.IO) {
        val messageFile = File(messagesDir, "${message.id}.json")
        val json = JSONObject().apply {
            put("id", message.id)
            put("type", message.type.name)
            put("senderId", message.senderId)
            put("recipientId", message.recipientId ?: JSONObject.NULL)
            put("content", message.content)
            put("timestamp", message.timestamp)
            put("signature", message.signature)
            put("priority", message.priority.name)
            put("isBroadcast", message.isBroadcast)
            put("ttl", message.ttl)
        }

        messageFile.writeText(json.toString(2))
    }

    suspend fun getMessages(
        type: MessageType? = null,
        limit: Int = 50
    ): List<NetworkMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<NetworkMessage>()

        messagesDir.listFiles { _, name -> name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val message = NetworkMessage(
                        id = json.getString("id"),
                        type = MessageType.valueOf(json.getString("type")),
                        senderId = json.getString("senderId"),
                        recipientId = if (json.isNull("recipientId")) null else json.getString("recipientId"),
                        content = json.getString("content"),
                        timestamp = json.getLong("timestamp"),
                        signature = json.getString("signature"),
                        priority = MessagePriority.valueOf(json.getString("priority")),
                        isBroadcast = json.getBoolean("isBroadcast"),
                        ttl = json.getInt("ttl")
                    )

                    if (type == null || message.type == type) {
                        messages.add(message)
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析消息失败: ${file.name}", e)
                }
            }

        messages.sortedByDescending { it.timestamp }
    }

    suspend fun shareModel(
        name: String,
        modelData: String,
        tags: List<String> = emptyList()
    ): SharedModel = withContext(Dispatchers.IO) {
        val local = localNode ?: throw IllegalStateException("本地节点未初始化")

        val modelId = UUID.randomUUID().toString()
        val hash = calculateHash(modelData)

        val model = SharedModel(
            id = modelId,
            name = name,
            creatorNodeId = local.id,
            sizeBytes = modelData.length.toLong(),
            version = "1.0.0",
            hash = hash,
            tags = tags,
            availableAt = listOf(local.id)
        )

        sharedModels[modelId] = model
        saveModel(model)

        broadcastMessage(
            type = MessageType.MODEL_SHARE,
            content = modelId,
            priority = MessagePriority.HIGH
        )

        addToLedger(
            type = LedgerType.MODEL_SHARE,
            data = modelId,
            signers = listOf(local.id)
        )

        model
    }

    private suspend fun saveModel(model: SharedModel) = withContext(Dispatchers.IO) {
        val modelFile = File(modelsDir, "${model.id}.json")
        val json = JSONObject().apply {
            put("id", model.id)
            put("name", model.name)
            put("creatorNodeId", model.creatorNodeId)
            put("sizeBytes", model.sizeBytes)
            put("version", model.version)
            put("downloadCount", model.downloadCount)
            put("hash", model.hash)
            put("tags", JSONArray(model.tags))
            put("availableAt", JSONArray(model.availableAt))
        }

        modelFile.writeText(json.toString(2))
    }

    suspend fun requestInference(
        modelId: String,
        inputData: String,
        priority: MessagePriority = MessagePriority.NORMAL
    ): InferenceJob = withContext(Dispatchers.IO) {
        val local = localNode ?: throw IllegalStateException("本地节点未初始化")

        val availableNodes = knownNodes.values.filter {
            it.isOnline && it.reputation > 0.3f
        }

        val assignedNodes = availableNodes.take(3).map { it.id }

        val job = InferenceJob(
            id = UUID.randomUUID().toString(),
            requesterId = local.id,
            modelId = modelId,
            inputData = inputData,
            assignedNodes = assignedNodes,
            status = JobStatus.PENDING,
            priority = priority,
            submittedAt = System.currentTimeMillis(),
            reward = if (priority == MessagePriority.HIGH) 0.1f else 0.05f
        )

        broadcastMessage(
            type = MessageType.INFERENCE_REQUEST,
            content = "${job.id}|${modelId}|${inputData}",
            priority = priority
        )

        job
    }

    suspend fun updateReputation(
        nodeId: String,
        delta: Float,
        reason: String
    ): Float = withContext(Dispatchers.IO) {
        val node = knownNodes[nodeId] ?: return@withContext -1f

        val newReputation = (node.reputation + delta).coerceIn(0f, 1f)

        val updatedNode = node.copy(
            reputation = newReputation
        )

        knownNodes[nodeId] = updatedNode
        saveNode(updatedNode)

        val local = localNode
        local?.let {
            addToLedger(
                type = LedgerType.REPUTATION,
                data = "${nodeId}|${newReputation}",
                signers = listOf(local.id)
            )
        }

        newReputation
    }

    private suspend fun addToLedger(
        type: LedgerType,
        data: String,
        signers: List<String>
    ) = withContext(Dispatchers.IO) {
        val lastEntry = localLedger.lastOrNull()
        val previousHash = lastEntry?.hash ?: "0"

        val entry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            type = type,
            data = data,
            timestamp = System.currentTimeMillis(),
            hash = calculateHash("${previousHash}|${type}|${data}|${System.currentTimeMillis()}"),
            previousHash = previousHash,
            signers = signers
        )

        localLedger.add(entry)
        saveLedgerEntry(entry)
    }

    private suspend fun saveLedgerEntry(entry: LedgerEntry) = withContext(Dispatchers.IO) {
        val entryFile = File(ledgerDir, "${entry.id}.json")
        val json = JSONObject().apply {
            put("id", entry.id)
            put("type", entry.type.name)
            put("data", entry.data)
            put("timestamp", entry.timestamp)
            put("hash", entry.hash)
            put("previousHash", entry.previousHash)
            put("signers", JSONArray(entry.signers))
        }

        entryFile.writeText(json.toString(2))
    }

    suspend fun getLedger(limit: Int = 100): List<LedgerEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<LedgerEntry>()

        ledgerDir.listFiles { _, name -> name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val entry = LedgerEntry(
                        id = json.getString("id"),
                        type = LedgerType.valueOf(json.getString("type")),
                        data = json.getString("data"),
                        timestamp = json.getLong("timestamp"),
                        hash = json.getString("hash"),
                        previousHash = json.getString("previousHash"),
                        signers = (0 until json.getJSONArray("signers").length())
                            .map { json.getJSONArray("signers").getString(it) }
                    )

                    entries.add(entry)
                    localLedger.add(entry)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析账本失败: ${file.name}", e)
                }
            }

        entries.sortedBy { it.timestamp }
    }

    private fun calculateHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateSignature(
        nodeId: String,
        content: String
    ): String {
        return calculateHash("${nodeId}|${content}|${System.currentTimeMillis()}")
    }

    suspend fun generateNetworkReport(): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("=== 去中心化 AI 网络报告 ===")
            appendLine()
            appendLine("【节点统计�?)
            appendLine("已知节点: ${knownNodes.size}")
            appendLine("活跃连接: ${activeConnections.size}")
            appendLine("在线节点: ${knownNodes.values.count { it.isOnline }}")
            appendLine()

            appendLine("【共享模型�?)
            appendLine("模型数量: ${sharedModels.size}")
            appendLine()

            appendLine("【账本状态�?)
            appendLine("账本条目: ${localLedger.size}")
            appendLine("消息数量: ${messageQueue.size}")
            appendLine()

            if (localNode != null) {
                appendLine("【本地节点�?)
                appendLine("名称: ${localNode?.name}")
                appendLine("类型: ${localNode?.nodeType?.name}")
                appendLine("信誉: ${String.format("%.2f", localNode?.reputation ?: 0f)}")
            }
        }
    }

    suspend fun cleanupOldData(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        messagesDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }

        ledgerDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }

        messageQueue.removeIf { it.timestamp < cutoffTime }
    }
}