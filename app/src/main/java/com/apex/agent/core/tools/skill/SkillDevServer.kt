package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.agent.util.PortProcessKiller
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SkillDevServer private constructor(
    private val context: Context
) : NanoHTTPD(DEFAULT_PORT) {
    /**
     * 工具执行器接口：业务侧注入以启用真实的工具调用。
     * 不注入时，[executeTool] 返回占位响应。
     */
    interface ToolExecutor {
        suspend fun execute(toolName: String, params: JSONObject?): ToolExecutionResult
    }

    data class ToolExecutionResult(
        val success: Boolean,
        val output: String,
        val error: String? = null
    )

    @Volatile
    private var toolExecutor: ToolExecutor? = null

    /**
     * 注入工具执行器。传入 null 清除注入。
     */
    fun setToolExecutor(executor: ToolExecutor?) {
        toolExecutor = executor
    }



    companion object {
        private const val TAG = "SkillDevServer"

        const val DEFAULT_PORT = 8765
        const val WS_PATH = "/ws"
        const val API_PATH = "/api"

        @Volatile private var INSTANCE: SkillDevServer? = null

        fun getInstance(context: Context): SkillDevServer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillDevServer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class ApiRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val queryParams: Map<String, String>,
        val body: String?
    )

    data class ApiResponse(
        val statusCode: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
        val isJson: Boolean = true
    )

    interface ServerListener {
        fun onServerStarted(port: Int)
        fun onServerStopped()
        fun onApiRequest(request: ApiRequest)
        fun onWebSocketMessage(sessionId: String, message: String)
        fun onError(error: String)
    }

    private val config = DevServerConfig.getInstance(context)
    private val hotReloader = HotReloader.getInstance(context)
    private val skillManager = SkillManager.getInstance(context)

    private val listeners = CopyOnWriteArrayList<ServerListener>()
    private val webSocketSessions = ConcurrentHashMap<String, WebSocketSession>()

    private val isRunning = AtomicBoolean(false)
    private val requestCount = AtomicLong(0)
    private val startTime = AtomicLong(0)

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var localWebSocketServer: LocalWebSocketServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class WebSocketSession(
        val id: String,
        val createdAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis()
    )

    override fun serve(session: IHTTPSession): Response {
        requestCount.incrementAndGet()

        val uri = session.uri
        val method = session.method.name

        val headers = session.headers
        val params = session.parameters

        if (uri.startsWith(WS_PATH)) {
            return handleWebSocketUpgrade(session)
        }

        if (uri.startsWith(API_PATH)) {
            return handleApiRequest(session)
        }

        return handleStaticRequest(session)
    }

    fun startServer(): Boolean {
        if (isRunning.get()) {
            AppLogger.d(TAG, "Server already running")
            return true
        }

        val settings = config.getServerSettings()

        return try {
            PortProcessKiller.killListeners(settings.port)

            super.start(SOCKET_READ_TIMEOUT, false)

            isRunning.set(true)
            startTime.set(System.currentTimeMillis())

            localWebSocketServer = LocalWebSocketServer(settings.port + 1)
            localWebSocketServer?.start()

            hotReloader.startWatching()

            AppLogger.d(TAG, "Skill dev server started on port ${settings.port}")
            notifyServerStarted(settings.port)
            true

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start server", e)
            notifyError("Failed to start server: ${e.message}")
            false
        }
    }

    fun stopServer() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {
            super.stop()

            localWebSocketServer?.stop()
            localWebSocketServer = null

            hotReloader.stopWatching()

            webSocketSessions.clear()

            AppLogger.d(TAG, "Skill dev server stopped")
            notifyServerStopped()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping server", e)
        }
    }

    fun isServerRunning(): Boolean = isRunning.get()

    fun getServerUrl(): String {
        val settings = config.getServerSettings()
        return "http://${settings.host}:${settings.port}"
    }

    fun getWebSocketUrl(): String {
        val settings = config.getServerSettings()
        return "ws://${settings.host}:${settings.port}${WS_PATH}"
    }

    fun broadcastToWebSocketClients(message: String) {
        webSocketSessions.keys().forEach { sessionId ->
            sendWebSocketMessage(sessionId, message)
        }
    }

    fun sendWebSocketMessage(sessionId: String, message: String): Boolean {
        val session = webSocketSessions[sessionId] ?: return false

        return try {
            localWebSocketServer?.sendMessage(sessionId, message) ?: false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error sending WebSocket message", e)
            false
        }
    }

    fun addServerListener(listener: ServerListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeServerListener(listener: ServerListener) {
        listeners.remove(listener)
    }

    private fun handleApiRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method.name

        val headers = session.headers
        val params = session.parameters

        val body = readRequestBody(session)

        val apiRequest = ApiRequest(
            method = method,
            path = uri,
            headers = headers,
            queryParams = params.mapValues { it.value.firstOrNull() ?: "" },
            body = body
        )

        notifyApiRequest(apiRequest)

        return when {
            uri == "${API_PATH}/skills" && method == "GET" -> listSkills()
            uri.startsWith("${API_PATH}/skills/") && method == "GET" -> getSkill(uri.substringAfter("${API_PATH}/skills/"))
            uri == "${API_PATH}/skills/reload" && method == "POST" -> reloadSkill(body)
            uri == "${API_PATH}/config" && method == "GET" -> getConfig()
            uri == "${API_PATH}/stats" && method == "GET" -> getStats()
            uri.startsWith("${API_PATH}/execute/") && method == "POST" -> executeTool(uri.substringAfter("${API_PATH}/execute/"), body)
            else -> notFound()
        }
    }

    private fun handleWebSocketUpgrade(session: IHTTPSession): Response {
        val protocol = session.headers["upgrade"]?.lowercase()

        if (protocol != "websocket") {
            return badRequest("Invalid upgrade protocol")
        }

        val sessionId = "ws_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
        webSocketSessions[sessionId] = WebSocketSession(id = sessionId)

        try {
            val webSocketUri = "ws://${config.getServerSettings().host}:${config.getServerSettings().port + 1}${WS_PATH}"
            establishWebSocketConnection(sessionId, webSocketUri)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to establish WebSocket connection", e)
        }

        return webSocketResponse(sessionId)
    }

    private fun establishWebSocketConnection(sessionId: String, url: String) {
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Session-ID", sessionId)
            .build()

        wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.d(TAG, "WebSocket connected: ${sessionId}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                notifyWebSocketMessage(sessionId, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                notifyWebSocketMessage(sessionId, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                webSocketSessions.remove(sessionId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                webSocketSessions.remove(sessionId)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response) {
                AppLogger.e(TAG, "WebSocket failure: ${t.message}", t)
                webSocketSessions.remove(sessionId)
            }
        })
    }

    private fun handleStaticRequest(session: IHTTPSession): Response {
        val uri = if (session.uri == "/") "/index.html" else session.uri
        val settings = config.getServerSettings()

        val mimeType = getMimeType(uri)

        val workspaceDir = config.getDevWorkspaceDirectory()
        val file = File(workspaceDir, uri)

        if (file.exists() && file.isFile) {
            return serveFile(file, mimeType, settings.enableCors, settings.corsOrigins)
        }

        val skillsDir = config.getSkillsRootDirectory()
        val skillFile = File(skillsDir, uri.trimStart('/'))

        if (skillFile.exists() && skillFile.isFile) {
            return serveFile(skillFile, mimeType, settings.enableCors, settings.corsOrigins)
        }

        return notFound()
    }

    private fun serveFile(file: File, mimeType: String, enableCors: Boolean, corsOrigins: List<String>): Response {
        return try {
            val bytes = file.readBytes()
            val inputStream = ByteArrayInputStream(bytes)
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, bytes.size.toLong())

            if (enableCors) {
                addCorsHeaders(response, corsOrigins)
            }

            response
        } catch (e: IOException) {
            internalError("Failed to read file: ${e.message}")
        }
    }

    private fun listSkills(): Response {
        val skills = skillManager.getAvailableSkills()

        val json = JSONObject()
        val skillsArray = JSONArray()

        skills.forEach { (name, skill) ->
            val skillJson = JSONObject()
            skillJson.put("name", skill.name)
            skillJson.put("description", skill.description)
            skillJson.put("version", skill.version)
            skillJson.put("author", skill.author)
            skillJson.put("directory", skill.directory.absolutePath)
            skillJson.put("loaded", skillManager.isSkillLoaded(name))
            skillsArray.put(skillJson)
        }

        json.put("skills", skillsArray)
        json.put("total", skills.size)

        return jsonResponse(json)
    }

    private fun getSkill(skillName: String): Response {
        val content = skillManager.readSkillContent(skillName)

        if (content == null) {
            return notFound("Skill not found: ${skillName}")
        }

        val skillDir = File(config.getSkillsRootDirectory(), skillName)
        val files = if (skillDir.exists()) {
            skillDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(skillDir).path }
                .toList()
        } else {
            emptyList()
        }

        val json = JSONObject()
        json.put("name", skillName)
        json.put("content", content)
        json.put("files", JSONArray(files))
        json.put("loaded", skillManager.isSkillLoaded(skillName))

        return jsonResponse(json)
    }

    private fun reloadSkill(body: String): Response {
        return try {
            val request = if (body.isNullOrBlank()) null else JSONObject(body)
            val skillName = request?.optString("skillName")

            if (skillName.isNullOrBlank()) {
                return badRequest("Missing skillName parameter")
            }

            val success = hotReloader.reloadSkill(skillName)

            val json = JSONObject()
            json.put("success", success)
            json.put("skillName", skillName)

            jsonResponse(json)

        } catch (e: Exception) {
            internalError("Failed to reload skill: ${e.message}")
        }
    }

    private fun getConfig(): Response {
        val json = JSONObject()

        json.put("server", JSONObject().apply {
            put("port", config.getServerSettings().port)
            put("host", config.getServerSettings().host)
            put("enableCors", config.getServerSettings().enableCors)
        })

        json.put("hotReload", JSONObject().apply {
            put("enabled", config.getHotReloadSettings().enabled)
            put("debounceDelayMs", config.getHotReloadSettings().debounceDelayMs)
            put("watchExtensions", JSONArray(config.getHotReloadSettings().watchExtensions))
        })

        json.put("editor", JSONObject().apply {
            put("theme", config.getEditorSettings().theme)
            put("fontSize", config.getEditorSettings().fontSize)
            put("tabSize", config.getEditorSettings().tabSize)
        })

        return jsonResponse(json)
    }

    private fun getStats(): Response {
        val json = JSONObject()

        json.put("requestCount", requestCount.get())
        json.put("uptime", if (startTime.get() > 0) System.currentTimeMillis() - startTime.get() else 0)
        json.put("webSocketSessions", webSocketSessions.size)
        json.put("hotReload", JSONObject().apply {
            put("totalReloads", hotReloader.getStats().totalReloads)
            put("totalErrors", hotReloader.getStats().totalErrors)
            put("watchedFiles", hotReloader.getStats().watchedFileCount)
        })

        return jsonResponse(json)
    }

private fun executeTool(toolName: String, body: String): Response {
    return try {
        val request = if (body.isNullOrBlank()) null else JSONObject(body)

        // 通过注入的 toolExecutor 执行真实工具调用；如果未注入则返回占位响应。
        val executor = toolExecutor
        if (executor != null) {
            val result = kotlinx.coroutines.runBlocking { executor.execute(toolName, request) }
            val json = JSONObject()
            json.put("toolName", toolName)
            json.put("success", result.success)
            json.put("output", result.output)
            if (!result.error.isNullOrBlank()) {
                json.put("error", result.error)
            }
            jsonResponse(json)
        } else {
            // 未注入 executor：返回明确的占位响应，提示集成方向
            val json = JSONObject()
            json.put("toolName", toolName)
            json.put("success", false)
            json.put("message", "Tool execution placeholder - inject ToolExecutor via setToolExecutor() for actual execution")
            json.put("request", request)
            jsonResponse(json)
        }
    } catch (e: Exception) {
        internalError("Failed to execute tool: ${e.message}")
    }
}

    private fun jsonResponse(json: JSONObject): Response {
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json.toString()
        )
        response.addHeader("Content-Type", "application/json")
        return response
    }

    private fun notFound(message: String = "Not Found"): Response {
        val json = JSONObject().apply {
            put("error", message)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", json.toString())
    }

    private fun badRequest(message: String): Response {
        val json = JSONObject().apply {
            put("error", message)
        }
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", json.toString())
    }

    private fun internalError(message: String): Response {
        val json = JSONObject().apply {
            put("error", message)
        }
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", json.toString())
    }

    private fun webSocketResponse(sessionId: String): Response {
        val response = newFixedLengthResponse(Response.Status.SWITCHING_PROTOCOLS, "text/plain", "")
        response.addHeader("Upgrade", "websocket")
        response.addHeader("Connection", "Upgrade")
        response.addHeader("Sec-WebSocket-Accept", "dGhlIHNhbXBsZSBub25jZQ==")
        response.addHeader("X-Session-ID", sessionId)
        return response
    }

    private fun addCorsHeaders(response: Response, origins: List<String>): Response {
        val origin = if (origins.contains("*")) "*" else origins.firstOrNull() ?: "*"
        response.addHeader("Access-Control-Allow-Origin", origin)
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    private fun readRequestBody(session: IHTTPSession): String? {
        return try {
            val tempFiles = HashMap<String, String>()
            session.parseBody(tempFiles)
            val postDataPath = tempFiles["postData"]
            if (postDataPath != null) {
                File(postDataPath).readText()
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading request body", e)
            null
        }
    }

    private fun getMimeType(uri: String): String {
        return when {
            uri.endsWith(".html") || uri.endsWith(".htm") -> "text/html"
            uri.endsWith(".css") -> "text/css"
            uri.endsWith(".js") -> "application/javascript"
            uri.endsWith(".json") -> "application/json"
            uri.endsWith(".png") -> "image/png"
            uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> "image/jpeg"
            uri.endsWith(".gif") -> "image/gif"
            uri.endsWith(".svg") -> "image/svg+xml"
            uri.endsWith(".md") -> "text/markdown"
            uri.endsWith(".yaml") || uri.endsWith(".yml") -> "text/yaml"
            uri.endsWith(".xml") -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    private fun notifyServerStarted(port: Int) {
        listeners.forEach { listener ->
            runCatching {
                listener.onServerStarted(port)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying server started", e)
            }
        }
    }

    private fun notifyServerStopped() {
        listeners.forEach { listener ->
            runCatching {
                listener.onServerStopped()
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying server stopped", e)
            }
        }
    }

    private fun notifyApiRequest(request: ApiRequest) {
        listeners.forEach { listener ->
            runCatching {
                listener.onApiRequest(request)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying API request", e)
            }
        }
    }

    private fun notifyWebSocketMessage(sessionId: String, message: String) {
        webSocketSessions[sessionId]?.lastActivity = System.currentTimeMillis()

        listeners.forEach { listener ->
            runCatching {
                listener.onWebSocketMessage(sessionId, message)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying WebSocket message", e)
            }
        }
    }

    private fun notifyError(error: String) {
        listeners.forEach { listener ->
            runCatching {
                listener.onError(error)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying error", e)
            }
        }
    }

    inner class LocalWebSocketServer(private val port: Int) {
        private var serverSocket: java.net.ServerSocket? = null
        private val activeConnections = ConcurrentHashMap<String, ConnectionHandler>()
        private val isRunning = AtomicBoolean(false)

        fun start() {
            if (isRunning.getAndSet(true)) return

            scope.launch {
                try {
                    serverSocket = java.net.ServerSocket(port)
                    serverSocket?.soTimeout = 1000

                    while (isRunning.get()) {
                        try {
                            val client = serverSocket?.accept()
                            if (client != null) {
                                val handler = ConnectionHandler(client)
                                activeConnections[handler.id] = handler
                                handler.start()
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            // Continue waiting
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                AppLogger.e(TAG, "Error accepting connection", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "WebSocket server error", e)
                }
            }
        }

        fun stop() {
            isRunning.set(false)
            activeConnections.values.forEach { it.close() }
            activeConnections.clear()
            serverSocket?.close()
        }

        fun sendMessage(sessionId: String, message: String): Boolean {
            val handler = activeConnections[sessionId] ?: return false
            handler.send(message)
            return true
        }

        inner class ConnectionHandler(private val socket: java.net.Socket) : Thread() {
            val id = "conn_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
            private var isHandshakeComplete = false

            override fun run() {
                try {
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()

                    val requestLine = input.readLine() ?: return

                    if (requestLine.contains("GET ${WS_PATH}")) {
                        performHandshake(input, output)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Connection handler error", e)
                } finally {
                    activeConnections.remove(id)
                    socket.close()
                }
            }

            private fun performHandshake(input: BufferedReader, output: BufferedWriter) {
                val headers = mutableMapOf<String, String>()
                var line: String?

                while (input.readLine().also { line = it }?.isNotEmpty() == true) {
                    val parts = line?.split(": ", 2) ?: continue
                    if (parts.size == 2) {
                        headers[parts[0].lowercase()] = parts[1]
                    }
                }

                val webSocketKey = headers["sec-websocket-key"] ?: return

                val response = buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: ${generateAcceptKey(webSocketKey)}\r\n")
                    append("\r\n")
                }

                output.write(response)
                output.flush()

                isHandshakeComplete = true
                handleMessages()
            }

            private fun handleMessages() {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                while (isRunning.get() && isHandshakeComplete) {
                    try {
                        val frame = readFrame(input)
                        if (frame != null && frame.opcode == 0x1) {
                            val message = String(frame.payload, Charsets.UTF_8)
                            notifyWebSocketMessage(id, message)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            private fun readFrame(input: java.io.InputStream): Frame? {
                val firstByte = input.read() ?: return null
                val opcode = firstByte and 0x0F

                val secondByte = input.read()
                val isMasked = (secondByte and 0x80) != 0
                var payloadLength = secondByte and 0x7F

                if (payloadLength == 126) {
                    payloadLength = (input.read() shl 8) or input.read()
                } else if (payloadLength == 127) {
                    payloadLength = 0
                    repeat(8) { payloadLength = (payloadLength shl 8) or input.read() }
                }

                val maskKey = if (isMasked) ByteArray(4) else null
                if (isMasked) {
                    input.read(maskKey)
                }

                val payload = ByteArray(payloadLength)
                input.read(payload)

                if (isMasked && maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                return Frame(opcode, payload)
            }

            fun send(message: String) {
                try {
                    val output = socket.getOutputStream()
                    val payload = message.toByteArray(Charsets.UTF_8)

                    output.write(0x81)
                    output.write(payload.size)

                    if (payload.size > 65535) {
                        output.write(127)
                        repeat(8) { output.write((payload.size shr (56 - it * 8)) and 0xFF) }
                    } else if (payload.size > 125) {
                        output.write(126)
                        output.write(payload.size shr 8)
                        output.write(payload.size and 0xFF)
                    }

                    output.write(payload)
                    output.flush()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error sending WebSocket message", e)
                }
            }

            fun close() {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }

            private data class Frame(val opcode: Int, val payload: ByteArray)

            private fun generateAcceptKey(key: String): String {
                val concat = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                val digest = java.security.MessageDigest.getInstance("SHA-1").digest(concat.toByteArray())
                return java.util.Base64.getEncoder().encodeToString(digest)
            }
        }
    }
}