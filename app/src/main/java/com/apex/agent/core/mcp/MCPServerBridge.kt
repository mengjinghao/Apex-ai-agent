package com.apex.agent.core.mcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MCPServerBridge {

    private val logger = LoggerFactory.getLogger(MCPServerBridge::class.java)
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = ConcurrentHashMap<String, ClientHandler>()

    companion object {
        const val DEFAULT_PORT = 4732
        const val MODULE_VERSION = "1.0.0"

        @Volatile
        private var instance: MCPServerBridge? = null
        @Volatile
        private var appContext: Context? = null

        fun initialize(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    instance = MCPServerBridge()
                    appContext = context.applicationContext
                }
            }
        }
        fun getInstance(): MCPServerBridge {
            return instance ?: throw IllegalStateException("MCPServerBridge not initialized")
        }
        fun startServer(port: Int = DEFAULT_PORT) {
            getInstance().start(port)
        }
        fun stopServer() {
            getInstance().stop()
        }
        fun isInitialized(): Boolean {
            return instance != null
        }
    }
        fun start(port: Int) {
        if (isRunning) {
            logger.warn("MCP Server is already running")
        return
        }

        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                logger.info("MCP Server started on port ${port}")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept()
                    clientSocket?.let {
                        val handler = ClientHandler(it)
                        clients[it.remoteSocketAddress.toString()] = handler
                        handler.start()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logger.error("MCP Server error", e)
                }
            }
        }
    }
        fun stop() {
        isRunning = false
        serverSocket?.close()
        clients.values.forEach { it.stop() }
        clients.clear()
        scope.cancel()
        logger.info("MCP Server stopped")
    }
        private inner class ClientHandler(private val socket: Socket) : Thread() {
        private val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val output = OutputStreamWriter(socket.getOutputStream())
        private val json = Json { ignoreUnknownKeys = true }
        private var isHandling = true

        override fun run() {
            try {
                while (isHandling) {
                    val line = input.readLine() ?: break
                    handleRequest(line)
                }
            } catch (e: Exception) {
                logger.warn("Client handler error", e)
            } finally {
                cleanup()
            }
        }
        private fun handleRequest(jsonStr: String) {
            try {
                val request = json.decodeFromString<MCPRequest>(jsonStr)
        val response = when (request.method) {
                    "initialize" -> handleInitialize(request)
                    "tools/list" -> handleToolsList(request)
                    "tools/call" -> handleToolsCall(request)
                    else -> MCPResponse.error("Unknown method: ${request.method}")
                }
                sendResponse(response)
            } catch (e: Exception) {
                sendResponse(MCPResponse.error(e.message ?: "Unknown error"))
            }
        }
        private fun handleInitialize(request: MCPRequest): MCPResponse {
            return MCPResponse.success(
                mapOf(
                    "name" to "Apex/Agent MCP Server",
                    "version" to MODULE_VERSION,
                    "protocol_version" to "2.0"
                )
            )
        }
        private fun handleToolsList(request: MCPRequest): MCPResponse {
            val tools = listOf(
                mapOf(
                    "name" to "conversations_list",
                    "description" to "List all conversations",
                    "parameters" to emptyList<Any>()
                ),
                mapOf(
                    "name" to "conversation_get",
                    "description" to "Get a specific conversation",
                    "parameters" to listOf(
                        mapOf("name" to "id", "type" to "string", "required" to true)
                    )
                ),
                mapOf(
                    "name" to "messages_read",
                    "description" to "Read messages from a conversation",
                    "parameters" to listOf(
                        mapOf("name" to "conversation_id", "type" to "string", "required" to true)
                    )
                ),
                mapOf(
                    "name" to "messages_send",
                    "description" to "Send a message to a conversation",
                    "parameters" to listOf(
                        mapOf("name" to "conversation_id", "type" to "string", "required" to true),
                        mapOf("name" to "content", "type" to "string", "required" to true),
                        mapOf("name" to "role", "type" to "string", "required" to false)
                    )
                ),
                mapOf(
                    "name" to "events_poll",
                    "description" to "Poll for new events",
                    "parameters" to emptyList<Any>()
                ),
                mapOf(
                    "name" to "permissions_list_open",
                    "description" to "List open permission requests",
                    "parameters" to emptyList<Any>()
                ),
                mapOf(
                    "name" to "permissions_respond",
                    "description" to "Respond to a permission request",
                    "parameters" to listOf(
                        mapOf("name" to "id", "type" to "string", "required" to true),
                        mapOf("name" to "approved", "type" to "boolean", "required" to true)
                    )
                )
            )
        return MCPResponse.success(mapOf("tools" to tools))
        }
        private fun handleToolsCall(request: MCPRequest): MCPResponse {
            val params = request.params as? Map<String, Any> ?: return MCPResponse.error("Invalid params")
        val toolName = params["name"] as? String ?: return MCPResponse.error("Tool name required")
        val context = appContext ?: return MCPResponse.error("MCPServerBridge not initialized with context")
        val tools = ConversationBridgeTools.getInstance(context)
        return when (toolName) {
                "conversations_list" -> runBlocking(Dispatchers.IO) {
                    val result = tools.conversationsList()
        if (result.success) {
                        MCPResponse.success(mapOf(
                            "conversations" to result.conversations.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "created_at" to it.createdAt,
                                    "updated_at" to it.updatedAt,
                                    "is_active" to it.isActive,
                                    "message_count" to it.messageCount
                                )
                            },
                            "total" to result.total
                        ))
                    } else {
                        MCPResponse.error(result.error ?: "Failed to list conversations")
                    }
                }
                "conversation_get" -> {
                    val id = params["id"] as? String ?: return MCPResponse.error("ID required")
                    runBlocking(Dispatchers.IO) {
                        val result = tools.conversationGet(id)
        if (result.success && result.conversation != null) {
                            val conversation = result.conversation
                            MCPResponse.success(mapOf(
                                "id" to conversation.id,
                                "title" to conversation.title,
                                "created_at" to conversation.createdAt,
                                "updated_at" to conversation.updatedAt,
                                "is_active" to conversation.isActive,
                                "parent_session_id" to conversation.parentSessionId,
                                "summary" to conversation.summary,
                                "model_name" to conversation.modelName,
                                "metadata" to conversation.metadata
                            ))
                        } else {
                            MCPResponse.error(result.error ?: "Conversation not found")
                        }
                    }
                }
                "messages_read" -> {
                    val conversationId = params["conversation_id"] as? String ?: return MCPResponse.error("Conversation ID required")
        val limit = (params["limit"] as? Number)?.toInt() ?: 50
                    runBlocking(Dispatchers.IO) {
                        val result = tools.messagesRead(conversationId, limit)
        if (result.success) {
                            MCPResponse.success(mapOf(
                                "messages" to result.messages.map {
                                    mapOf(
                                        "id" to it.id,
                                        "role" to it.role,
                                        "content" to it.content,
                                        "created_at" to it.createdAt,
                                        "parent_message_id" to it.parentMessageId,
                                        "tool_calls" to it.toolCalls
                                    )
                                },
                                "total" to result.total
                            ))
                        } else {
                            MCPResponse.error(result.error ?: "Failed to read messages")
                        }
                    }
                }
                "messages_send" -> {
                    val conversationId = params["conversation_id"] as? String ?: return MCPResponse.error("Conversation ID required")
        val content = params["content"] as? String ?: return MCPResponse.error("Content required")
        val role = params["role"] as? String ?: "user"
                    runBlocking(Dispatchers.IO) {
                        val result = tools.messagesSend(conversationId, content, role)
        if (result.success) {
                            MCPResponse.success(mapOf(
                                "message_id" to result.messageId,
                                "created_at" to result.createdAt
                            ))
                        } else {
                            MCPResponse.error(result.error ?: "Failed to send message")
                        }
                    }
                }
                "events_poll" -> {
                    val timeout = (params["timeout"] as? Number)?.toInt() ?: 1000
                    runBlocking(Dispatchers.IO) {
                        val result = tools.eventsPoll(timeout)
        if (result.success) {
                            MCPResponse.success(mapOf("events" to result.events.map {
                                mapOf(
                                    "type" to it.type,
                                    "conversation_id" to it.conversationId,
                                    "message_id" to it.messageId,
                                    "timestamp" to it.timestamp,
                                    "data" to it.data
                                )
                            }))
                        } else {
                            MCPResponse.error(result.error ?: "Failed to poll events")
                        }
                    }
                }
                "permissions_list_open" -> runBlocking(Dispatchers.IO) {
                    val result = tools.permissionsListOpen()
        if (result.success) {
                        MCPResponse.success(mapOf("permissions" to result.permissions.map {
                            mapOf(
                                "id" to it.id,
                                "type" to it.type,
                                "conversation_id" to it.conversationId,
                                "description" to it.description,
                                "created_at" to it.createdAt
                            )
                        }))
                    } else {
                        MCPResponse.error(result.error ?: "Failed to list permissions")
                    }
                }
                "permissions_respond" -> {
                    val id = params["id"] as? String ?: return MCPResponse.error("ID required")
        val approved = params["approved"] as? Boolean ?: return MCPResponse.error("Approved required")
                    runBlocking(Dispatchers.IO) {
                        val result = tools.permissionsRespond(id, approved)
        if (result.success) {
                            MCPResponse.success(mapOf(
                                "permission_id" to result.permissionId,
                                "approved" to result.approved
                            ))
                        } else {
                            MCPResponse.error(result.error ?: "Failed to respond to permission")
                        }
                    }
                }
                else -> MCPResponse.error("Unknown tool: ${toolName}")
            }
        }
        private fun sendResponse(response: MCPResponse) {
            try {
                val jsonStr = json.encodeToString(response)
                output.write("${jsonStr}\n")
                output.flush()
            } catch (e: Exception) {
                logger.warn("Failed to send response", e)
            }
        }
        fun stop() {
            isHandling = false
        }
        private fun cleanup() {
            clients.remove(socket.remoteSocketAddress.toString())
            try {
                output.close()
            } catch (e: Exception) {
                logger.warn("Failed to close output stream", e)
            }
            try {
                input.close()
            } catch (e: Exception) {
                logger.warn("Failed to close input stream", e)
            }
            try {
                socket.close()
            } catch (e: Exception) {
                logger.warn("Failed to close socket", e)
            }
        }
    }

    @Serializable
    data class MCPRequest(
        val id: String,
        val method: String,
        val params: Any? = null
    )

    @Serializable
    data class MCPResponse(
        val id: String? = null,
        val result: Any? = null,
        val error: String? = null
    ) {
        companion object {
            fun success(result: Any): MCPResponse {
                return MCPResponse(result = result)
            }
        fun error(message: String): MCPResponse {
                return MCPResponse(error = message)
            }
        }
    }
}

object MCPCatalog {

    private val logger = LoggerFactory.getLogger(MCPCatalog::class.java)
        private val catalog = mutableListOf<MCPEntry>()
        private var isLoaded = false

    fun loadCatalog() {
        if (isLoaded) return
        logger.info("Loading Nous-approved MCP catalog")

        catalog.addAll(listOf(
            MCPEntry(
                name = "CodeLlama",
                description = "Code generation and analysis",
                category = "Development",
                recommended = true,
                url = "https://github.com/facebookresearch/codellama"
            ),
            MCPEntry(
                name = "WolframAlpha",
                description = "Mathematical computations and knowledge",
                category = "Knowledge",
                recommended = true,
                url = "https://www.wolframalpha.com"
            ),
            MCPEntry(
                name = "Search",
                description = "Web search capabilities",
                category = "Search",
                recommended = true
            ),
            MCPEntry(
                name = "Terminal",
                description = "Command line access",
                category = "System",
                recommended = false
            ),
            MCPEntry(
                name = "FileManager",
                description = "File system operations",
                category = "System",
                recommended = false
            ),
            MCPEntry(
                name = "GitHub",
                description = "GitHub API integration",
                category = "Development",
                recommended = false,
                url = "https://github.com"
            ),
            MCPEntry(
                name = "Calendar",
                description = "Calendar and scheduling",
                category = "Productivity",
                recommended = false
            ),
            MCPEntry(
                name = "Email",
                description = "Email sending and reading",
                category = "Productivity",
                recommended = false
            )
        ))

        isLoaded = true
        logger.info("MCP catalog loaded: ${catalog.size} entries")
    }
        fun search(query: String): List<MCPEntry> {
        return catalog.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
        }
    }
        fun getByCategory(category: String): List<MCPEntry> {
        return catalog.filter { it.category == category }
    }
        fun getRecommended(): List<MCPEntry> {
        return catalog.filter { it.recommended }
    }
        fun getAll(): List<MCPEntry> {
        return catalog
    }
        fun getCategories(): List<String> {
        return catalog.map { it.category }.distinct()
    }

    data class MCPEntry(
        val name: String,
        val description: String,
        val category: String,
        val recommended: Boolean = false,
        val url: String = ""
    )
}
