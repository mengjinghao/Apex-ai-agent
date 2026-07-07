package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 分布式 Skill 支持模块
 *
 * 功能。
 * - 分布式技能注。
 * - 服务发现
 * - 跨实例调。
 * - 负载均衡
 * - 故障转移
 */
class DistributedSkillSupport private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DistributedSkillSupport"
        private const val DEFAULT_PORT = 8977
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val HEARTBEAT_TIMEOUT_MS = 30000L
        private const val SERVICE_CACHE_TTL_MS = 60000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val DISCOVERY_PORT = 8978

        @Volatile private var INSTANCE: DistributedSkillSupport? = null

        fun getInstance(context: Context): DistributedSkillSupport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DistributedSkillSupport(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    /**
     * 节点信息
     */
    data class NodeInfo(
        val nodeId: String,
        val host: String,
        val port: Int,
        val name: String,
        val capabilities: List<String>,
        val status: NodeStatus,
        val lastHeartbeat: Long,
        val registeredSkills: List<String>,
        val load: Float = 0f,
        val version: String = "1.0.0"
    ) {
        val isHealthy: Boolean
            get() = status == NodeStatus.ACTIVE &&
                    (System.currentTimeMillis() - lastHeartbeat) < HEARTBEAT_TIMEOUT_MS
    }

    enum class NodeStatus {
        ACTIVE,
        INACTIVE,
        SUSPECTED,
        FAILED
    }

    /**
     * 服务注册信息
     */
    data class ServiceRegistration(
        val serviceId: String,
        val skillId: String,
        val skillName: String,
        val nodeId: String,
        val endpoint: String,
        val metadata: Map<String, String>,
        val registeredAt: Long,
        val version: String,
        val priority: Int = 0,
        val weight: Int = 100
    )

    /**
     * 服务实例
     */
    data class ServiceInstance(
        val registration: ServiceRegistration,
        val node: NodeInfo,
        val isAvailable: Boolean = true,
        val latencyMs: Long? = null
    )

    /**
     * 远程调用请求
     */
    data class RemoteCallRequest(
        val requestId: String,
        val serviceId: String,
        val method: String,
        val parameters: Map<String, Any?>,
        val timeoutMs: Long = 30000,
        val retryable: Boolean = true
    )

    /**
     * 远程调用响应
     */
    data class RemoteCallResponse(
        val requestId: String,
        val success: Boolean,
        val result: Any?,
        val error: String?,
        val latencyMs: Long,
        val fromNodeId: String
    )

    /**
     * 分布式锁
     */
    data class DistributedLock(
        val lockId: String,
        val ownerNodeId: String,
        val acquiredAt: Long,
        val expiresAt: Long
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() > expiresAt
    }

    /**
     * 负载均衡策略
     */
    enum class LoadBalanceStrategy {
        ROUND_ROBIN,
        LEAST_CONNECTIONS,
        RANDOM,
        WEIGHTED,
        LATENCY_BASED
    }

    // ========== 数据结构 ==========

    private val _localNode = MutableStateFlow<NodeInfo?>(null)
    val localNode: StateFlow<NodeInfo?> = _localNode.asStateFlow()

    private val _registeredNodes = MutableStateFlow<Map<String, NodeInfo>>(emptyMap())
    val registeredNodes: StateFlow<Map<String, NodeInfo>> = _registeredNodes.asStateFlow()

    private val _serviceRegistry = MutableStateFlow<Map<String, List<ServiceRegistration>>>(emptyMap())
    val serviceRegistry: StateFlow<Map<String, List<ServiceRegistration>>> = _serviceRegistry.asStateFlow()

    private val _activeCalls = MutableStateFlow<Map<String, RemoteCallRequest>>(emptyMap())
    val activeCalls: StateFlow<Map<String, RemoteCallRequest>> = _activeCalls.asStateFlow()

    private val _distributedLocks = MutableStateFlow<Map<String, DistributedLock>>(emptyMap())
    val distributedLocks: StateFlow<Map<String, DistributedLock>> = _distributedLocks.asStateFlow()

    private val _loadBalancer = MutableStateFlow<LoadBalanceStrategy>(LoadBalanceStrategy.ROUND_ROBIN)
    val loadBalancer: StateFlow<LoadBalanceStrategy> = _loadBalancer.asStateFlow()

    private val nodeConnections = ConcurrentHashMap<String, Socket>()
    private val serviceCache = ConcurrentHashMap<String, Pair<List<ServiceInstance>, Long>>()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<RemoteCallResponse>>()

    private val serverSocket: ServerSocket? = null
    private val discoverySocket: DatagramSocket? = null

    private val executor = Executors.newFixedThreadPool(10)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverJob: Job? = null
    private var heartbeatJob: Job? = null
    private var cleanupJob: Job? = null

    private var isRunning = false

    private val skillManager by lazy { SkillManager.getInstance(context) }

    // ========== 初始化 API ==========

    /**
     * 启动分布式支。
     */
    fun start(nodeName: String, port: Int = DEFAULT_PORT): Boolean {
        if (isRunning) return true

        val nodeId = generateNodeId()

        _localNode.value = NodeInfo(
            nodeId = nodeId,
            host = getLocalIpAddress(),
            port = port,
            name = nodeName,
            capabilities = listOf("local_execution", "skill_hosting"),
            status = NodeStatus.ACTIVE,
            lastHeartbeat = System.currentTimeMillis(),
            registeredSkills = emptyList()
        )

        // 启动服务
        startServer(port)
        startDiscovery()
        startHeartbeat()
        startCleanup()

        // 启动服务
        registerLocalSkills()

        isRunning = true
        AppLogger.i(TAG, "Distributed support started: ${nodeName} (${_localNode.value?.nodeId})")
        return true
    }

    /**
     * 停止分布式支。
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false

        serverJob?.cancel()
        heartbeatJob?.cancel()
        cleanupJob?.cancel()

        // 启动服务
        nodeConnections.values.forEach {
            try { it.close() } catch (e: Exception) { AppLogger.w(TAG, "Error closing node connection", e) }
        }
        nodeConnections.clear()

        // 启动服务
        _localNode.value?.let { node ->
            broadcastLeave(node.nodeId)
        }

        _registeredNodes.value = emptyMap()
        _serviceRegistry.value = emptyMap()
        _localNode.value = null

        AppLogger.i(TAG, "Distributed support stopped")
    }

    // ========== 服务注册 API ==========

    /**
     * 注册技能服。
     */
    suspend fun registerService(
        skillId: String,
        skillName: String,
        endpoint: String,
        metadata: Map<String, String> = emptyMap(),
        version: String = "1.0.0",
        priority: Int = 0,
        weight: Int = 100
    ): Boolean = withContext(Dispatchers.IO) {
        val node = _localNode.value ?: return@withContext false

        val registration = ServiceRegistration(
            serviceId = "${skillId}_${node.nodeId}",
            skillId = skillId,
            skillName = skillName,
            nodeId = node.nodeId,
            endpoint = endpoint,
            metadata = metadata,
            registeredAt = System.currentTimeMillis(),
            version = version,
            priority = priority,
            weight = weight
        )

        // 更新本地注册
        val currentServices = _serviceRegistry.value.toMutableMap()
        val skillServices = currentServices.getOrPut(skillId) { mutableListOf() }
        skillServices.removeAll { it.skillId == skillId && it.nodeId == node.nodeId }
        skillServices.add(registration)
        currentServices[skillId] = skillServices
        _serviceRegistry.value = currentServices

        // 广播注册
        broadcastServiceRegistration(registration)

        // 清除缓存
        serviceCache.remove(skillId)

        AppLogger.d(TAG, "Service registered: ${skillId} on ${node.nodeId}")
        true
    }

    /**
     * 注销技能服。
     */
    suspend fun unregisterService(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val node = _localNode.value ?: return@withContext false

        val currentServices = _serviceRegistry.value.toMutableMap()
        val skillServices = currentServices[skillId] ?: return@withContext false

        val removed = skillServices.removeAll { it.skillId == skillId && it.nodeId == node.nodeId }
        if (removed) {
            currentServices[skillId] = skillServices
            _serviceRegistry.value = currentServices

            // 广播注销
            broadcastServiceUnregistration(skillId, node.nodeId)

            // 清除缓存
            serviceCache.remove(skillId)
        }

        removed
    }

    /**
     * 发现服务
     */
    suspend fun discoverService(skillId: String, useCache: Boolean = true): List<ServiceInstance> = withContext(Dispatchers.IO) {
        // 启动服务
        if (useCache) {
            val cached = serviceCache[skillId]
            if (cached != null && System.currentTimeMillis() - cached.second < SERVICE_CACHE_TTL_MS) {
                return@withContext cached.first
            }
        }

        // 从注册表获取
        val registrations = _serviceRegistry.value[skillId] ?: emptyList()

        val instances = registrations.mapNotNull { reg ->
            val node = _registeredNodes.value[reg.nodeId]
            if (node != null && node.isHealthy) {
                ServiceInstance(
                    registration = reg,
                    node = node,
                    isAvailable = true
                )
            } else null
        }

        // 缓存结果
        if (instances.isNotEmpty()) {
            serviceCache[skillId] = instances to System.currentTimeMillis()
        }

        instances
    }

    /**
     * 发现所有可用节。
     */
    fun discoverNodes(): List<NodeInfo> {
        return _registeredNodes.value.values.filter { it.isHealthy }.toList()
    }

    // ========== 远程调用 API ==========

    /**
     * 调用远程服务
     */
    suspend fun callRemote(
        skillId: String,
        method: String,
        parameters: Map<String, Any?>,
        timeoutMs: Long = 30000
    ): RemoteCallResponse? = withContext(Dispatchers.IO) {
        val instances = discoverService(skillId)
        if (instances.isEmpty()) {
            return@withContext null
        }

        // 选择实例
        val instance = selectInstance(instances)
        if (instance == null) {
            return@withContext null
        }

        // 执行调用
        executeRemoteCall(instance, method, parameters, timeoutMs)
    }

    /**
     * 执行远程调用
     */
    private suspend fun executeRemoteCall(
        instance: ServiceInstance,
        method: String,
        parameters: Map<String, Any?>,
        timeoutMs: Long
    ): RemoteCallResponse? = withContext(Dispatchers.IO) {
        val requestId = generateRequestId()
        val node = instance.node

        val request = RemoteCallRequest(
            requestId = requestId,
            serviceId = instance.registration.serviceId,
            method = method,
            parameters = parameters,
            timeoutMs = timeoutMs
        )

        _activeCalls.value = _activeCalls.value.toMutableMap().apply {
            put(requestId, request)
        }

        val deferred = CompletableDeferred<RemoteCallResponse>()
        pendingRequests[requestId] = deferred

        val startTime = System.currentTimeMillis()

        try {
            // 广播注销
            val connection = getConnection(node)
            if (connection == null) {
                return@withContext RemoteCallResponse(
                    requestId = requestId,
                    success = false,
                    result = null,
                    error = "Cannot connect to node: ${node.nodeId}",
                    latencyMs = 0,
                    fromNodeId = node.nodeId
                )
            }

            // 广播注销
            val requestJson = serializeRequest(request)
            val requestBytes = requestJson.toByteArray()

            synchronized(connection) {
                val outputStream = DataOutputStream(connection.getOutputStream())
                outputStream.writeInt(requestBytes.size)
                outputStream.write(requestBytes)
                outputStream.flush()
            }

            // 等待响应（简化实现）
            val response = withTimeoutOrNull(timeoutMs) {
                pendingRequests[requestId]?.await()
            } ?: RemoteCallResponse(
                requestId = requestId,
                success = false,
                result = null,
                error = "Request timeout",
                latencyMs = System.currentTimeMillis() - startTime,
                fromNodeId = node.nodeId
            )

            response
        } catch (e: Exception) {
            AppLogger.e(TAG, "Remote call failed", e)
            RemoteCallResponse(
                requestId = requestId,
                success = false,
                result = null,
                error = e.message,
                latencyMs = System.currentTimeMillis() - startTime,
                fromNodeId = node.nodeId
            )
        } finally {
            _activeCalls.value = _activeCalls.value.toMutableMap().apply {
                remove(requestId)
            }
            pendingRequests.remove(requestId)
        }
    }

    // ========== 负载均衡 API ==========

    /**
     * 设置负载均衡策略
     */
    fun setLoadBalanceStrategy(strategy: LoadBalanceStrategy) {
        _loadBalancer.value = strategy
    }

    /**
     * 选择实例
     */
    private fun selectInstance(instances: List<ServiceInstance>): ServiceInstance? {
        if (instances.isEmpty()) return null
        if (instances.size == 1) return instances.first()

        return when (_loadBalancer.value) {
            LoadBalanceStrategy.ROUND_ROBIN -> {
                // 简化实现
                instances.random()
            }
            LoadBalanceStrategy.LEAST_CONNECTIONS -> {
                instances.minByOrNull { _activeCalls.value.count {
                    it.value.serviceId == it.value.serviceId } } ?: instances.first()
            }
            LoadBalanceStrategy.RANDOM -> instances.random()
            LoadBalanceStrategy.WEIGHTED -> {
                val totalWeight = instances.sumOf { it.registration.weight }
                var random = (Math.random() * totalWeight).toInt()
                for (instance in instances) {
                    random -= instance.registration.weight
                    if (random <= 0) return instance
                }
                instances.first()
            }
            LoadBalanceStrategy.LATENCY_BASED -> {
                instances.filter { it.latencyMs != null }
                    .minByOrNull { it.latencyMs!! }
                    ?: instances.first()
            }
        }
    }

    // ========== 分布式锁 API ==========

    /**
     * 尝试获取分布式锁
     */
    suspend fun tryLock(
        lockId: String,
        ttlMs: Long = 30000,
        retryCount: Int = 0,
        retryDelayMs: Long = 100
    ): Boolean = withContext(Dispatchers.IO) {
        val node = _localNode.value ?: return@withContext false

        // 启动服务
        val existingLock = _distributedLocks.value[lockId]
        if (existingLock != null && !existingLock.isExpired) {
            // 尝试竞争
            if (retryCount > 0) {
                delay(retryDelayMs)
                return@withContext tryLock(lockId, ttlMs, retryCount - 1, retryDelayMs)
            }
            return@withContext false
        }

        // 启动服务
        val lock = DistributedLock(
            lockId = lockId,
            ownerNodeId = node.nodeId,
            acquiredAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + ttlMs
        )

        _distributedLocks.value = _distributedLocks.value.toMutableMap().apply {
            put(lockId, lock)
        }

        // 启动服务
        broadcastLockAcquired(lock)

        AppLogger.d(TAG, "Lock acquired: ${lockId} by ${node.nodeId}")
        true
    }

    /**
     * 释放分布式锁
     */
    suspend fun releaseLock(lockId: String): Boolean = withContext(Dispatchers.IO) {
        val node = _localNode.value ?: return@withContext false

        val lock = _distributedLocks.value[lockId]
        if (lock == null || lock.ownerNodeId != node.nodeId) {
            return@withContext false
        }

        _distributedLocks.value = _distributedLocks.value.toMutableMap().apply {
            remove(lockId)
        }

        // 启动服务
        broadcastLockReleased(lockId, node.nodeId)

        AppLogger.d(TAG, "Lock released: ${lockId}")
        true
    }

    /**
     * 检查锁状。
     */
    fun isLocked(lockId: String): Boolean {
        val lock = _distributedLocks.value[lockId]
        return lock != null && !lock.isExpired
    }

    /**
     * 获取锁持有。
     */
    fun getLockOwner(lockId: String): String? {
        return _distributedLocks.value[lockId]?.ownerNodeId
    }

    // ========== 节点管理 API ==========

    /**
     * 连接远程节点
     */
    suspend fun connectToNode(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(host, port)
            socket.soTimeout = 10000
            nodeConnections["${host}:${port}"] = socket

            // 广播注销
            val local = _localNode.value ?: return@withContext false
            val handshake = mapOf(
                "type" to "handshake",
                "nodeId" to local.nodeId,
                "name" to local.name,
                "capabilities" to local.capabilities
            )

            synchronized(socket) {
                val outputStream = DataOutputStream(socket.getOutputStream())
                outputStream.writeUTF(serializeToJson(handshake))
                outputStream.flush()
            }

            AppLogger.d(TAG, "Connected to node: ${host}:${port}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to connect to node: ${host}:${port}", e)
            false
        }
    }

    /**
     * 断开远程节点
     */
    fun disconnectFromNode(nodeId: String) {
        val connection = nodeConnections.entries.find {
            _registeredNodes.value[nodeId]?.let { node -> "${node.host}:${node.port}" == it.key }
        }?.value

        connection?.let {
            try { it.close() } catch (e: Exception) { AppLogger.w(TAG, "Error closing node connection", e) }
            nodeConnections.remove("${it.inetAddress.hostAddress}:${it.port}")
        }
    }

    /**
     * 获取节点状。
     */
    fun getNodeStatus(nodeId: String): NodeInfo? {
        return _registeredNodes.value[nodeId]
    }

    // ========== 私有方法 ==========

    private fun startServer(port: Int) {
        serverJob = scope.launch {
            try {
                val server = ServerSocket(port)
                AppLogger.d(TAG, "Server started on port ${port}")

                while (isRunning && isActive) {
                    try {
                        val client = withContext(Dispatchers.IO) {
                            server.accept()
                        }
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isRunning) {
                            AppLogger.e(TAG, "Error accepting connection", e)
                        }
                    }
                }

                server.close()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Server error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val inputStream = DataInputStream(socket.getInputStream())
                val data = ByteArray(inputStream.readInt())
                inputStream.readFully(data)
                val message = String(data)

                val json = parseJson(message)
                when (json["type"] as? String) {
                    "handshake" -> handleHandshake(socket, json)
                    "service_register" -> handleServiceRegistration(json)
                    "service_unregister" -> handleServiceUnregistration(json)
                    "node_join" -> handleNodeJoin(json)
                    "node_leave" -> handleNodeLeave(json)
                    "heartbeat" -> handleHeartbeat(json)
                    "remote_call" -> handleRemoteCall(socket, json)
                    "remote_response" -> handleRemoteResponse(json)
                    "lock_acquired" -> handleLockAcquired(json)
                    "lock_released" -> handleLockReleased(json)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error handling client", e)
            } finally {
                try { socket.close() } catch (e: Exception) { AppLogger.w(TAG, "Error closing socket", e) }
            }
        }
    }

    private fun handleHandshake(socket: Socket, json: Map<String, Any>) {
        val nodeId = json["nodeId"] as? String ?: return
        val name = json["name"] as? String ?: ""
        val capabilities = json["capabilities"] as? List<String> ?: emptyList()

        val node = NodeInfo(
            nodeId = nodeId,
            host = socket inetAddress.hostAddress,
            port = 0,
            name = name,
            capabilities = capabilities,
            status = NodeStatus.ACTIVE,
            lastHeartbeat = System.currentTimeMillis(),
            registeredSkills = emptyList()
        )

        _registeredNodes.value = _registeredNodes.value.toMutableMap().apply {
            put(nodeId, node)
        }

        AppLogger.d(TAG, "Node joined: ${name} (${nodeId})")
    }

    private fun handleServiceRegistration(json: Map<String, Any>) {
        val skillId = json["skillId"] as? String ?: return
        val nodeId = json["nodeId"] as? String ?: return

        val currentServices = _serviceRegistry.value.toMutableMap()
        val skillServices = currentServices.getOrPut(skillId) { mutableListOf() }

        // 添加注册（简化实现）
        skillServices.add(ServiceRegistration(
            serviceId = "${skillId}_${nodeId}",
            skillId = skillId,
            skillName = json["skillName"] as? String ?: skillId,
            nodeId = nodeId,
            endpoint = json["endpoint"] as? String ?: "",
            metadata = json["metadata"] as? Map<String, String> ?: emptyMap(),
            registeredAt = System.currentTimeMillis(),
            version = json["version"] as? String ?: "1.0.0"
        ))

        _serviceRegistry.value = currentServices
        serviceCache.remove(skillId)
    }

    private fun handleServiceUnregistration(skillId: String, nodeId: String) {
        val currentServices = _serviceRegistry.value.toMutableMap()
        val skillServices = currentServices[skillId] ?: return

        skillServices.removeAll { it.skillId == skillId && it.nodeId == nodeId }
        currentServices[skillId] = skillServices
        _serviceRegistry.value = currentServices
        serviceCache.remove(skillId)
    }

    private fun handleNodeJoin(json: Map<String, Any>) {
        val nodeId = json["nodeId"] as? String ?: return

        val node = NodeInfo(
            nodeId = nodeId,
            host = json["host"] as? String ?: "",
            port = json["port"] as? Int ?: 0,
            name = json["name"] as? String ?: "",
            capabilities = json["capabilities"] as? List<String> ?: emptyList(),
            status = NodeStatus.ACTIVE,
            lastHeartbeat = System.currentTimeMillis(),
            registeredSkills = json["skills"] as? List<String> ?: emptyList()
        )

        _registeredNodes.value = _registeredNodes.value.toMutableMap().apply {
            put(nodeId, node)
        }
    }

    private fun handleNodeLeave(json: Map<String, Any>) {
        val nodeId = json["nodeId"] as? String ?: return

        _registeredNodes.value = _registeredNodes.value.toMutableMap().apply {
            remove(nodeId)
        }

        // 启动服务
        val currentServices = _serviceRegistry.value.toMutableMap()
        currentServices.forEach { (skillId, services) ->
            services.removeAll { it.nodeId == nodeId }
            currentServices[skillId] = services
        }
        _serviceRegistry.value = currentServices
    }

    private fun handleHeartbeat(json: Map<String, Any>) {
        val nodeId = json["nodeId"] as? String ?: return

        _registeredNodes.value[nodeId]?.let { node ->
            _registeredNodes.value = _registeredNodes.value.toMutableMap().apply {
                put(nodeId, node.copy(
                    lastHeartbeat = System.currentTimeMillis(),
                    load = (json["load"] as? Number)?.toFloat() ?: node.load
                ))
            }
        }
    }

    private suspend fun handleRemoteCall(socket: Socket, json: Map<String, Any>) {
        val requestId = json["requestId"] as? String ?: return
        val method = json["method"] as? String ?: return
        val parameters = json["parameters"] as? Map<String, Any?> ?: emptyMap()

        // 简化：直接返回成功响应
        val response = RemoteCallResponse(
            requestId = requestId,
            success = true,
            result = mapOf("status" to "executed"),
            error = null,
            latencyMs = 0,
            fromNodeId = _localNode.value?.nodeId ?: ""
        )

        // 启动服务
        synchronized(socket) {
            val outputStream = DataOutputStream(socket.getOutputStream())
            outputStream.writeUTF(serializeToJson(mapOf(
                "type" to "remote_response",
                "requestId" to response.requestId,
                "success" to response.success,
                "result" to (response.result ?: ""),
                "error" to (response.error ?: ""),
                "latencyMs" to response.latencyMs,
                "fromNodeId" to response.fromNodeId
            )))
            outputStream.flush()
        }
    }

    private fun handleRemoteResponse(json: Map<String, Any>) {
        val requestId = json["requestId"] as? String ?: return

        val response = RemoteCallResponse(
            requestId = requestId,
            success = json["success"] as? Boolean ?: false,
            result = json["result"],
            error = json["error"] as? String,
            latencyMs = (json["latencyMs"] as? Number)?.toLong() ?: 0,
            fromNodeId = json["fromNodeId"] as? String ?: ""
        )

        pendingRequests[requestId]?.complete(response)
    }

    private fun handleLockAcquired(json: Map<String, Any>) {
        val lockId = json["lockId"] as? String ?: return
        val ownerNodeId = json["ownerNodeId"] as? String ?: return

        val lock = DistributedLock(
            lockId = lockId,
            ownerNodeId = ownerNodeId,
            acquiredAt = System.currentTimeMillis(),
            expiresAt = (json["expiresAt"] as? Number)?.toLong() ?: System.currentTimeMillis() + 30000
        )

        _distributedLocks.value = _distributedLocks.value.toMutableMap().apply {
            put(lockId, lock)
        }
    }

    private fun handleLockReleased(lockId: String, nodeId: String) {
        val lock = _distributedLocks.value[lockId]
        if (lock?.ownerNodeId == nodeId) {
            _distributedLocks.value = _distributedLocks.value.toMutableMap().apply {
                remove(lockId)
            }
        }
    }

    private fun startDiscovery() {
        scope.launch {
            try {
                val multicastSocket = DatagramSocket(DISCOVERY_PORT)
                multicastSocket.soTimeout = 5000

                // 监听发现请求
                while (isRunning && isActive) {
                    try {
                        val buffer = ByteArray(1024)
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket.receive(packet)

                        val message = String(packet.data, 0, packet.length)
                        val json = parseJson(message)

                        if (json["type"] == "discovery_request") {
                            // 响应发现请求
                            val local = _localNode.value ?: continue
                            val response = mapOf(
                                "type" to "discovery_response",
                                "nodeId" to local.nodeId,
                                "host" to local.host,
                                "port" to local.port,
                                "name" to local.name
                            )

                            val responseBytes = serializeToJson(response).toByteArray()
                            val responsePacket = DatagramPacket(
                                responseBytes,
                                responseBytes.size,
                                packet.address,
                                DISCOVERY_PORT
                            )
                            multicastSocket.send(responsePacket)
                        }
                    } catch (e: SocketTimeoutException) {
                        // 正常超时
                    }
                }

                multicastSocket.close()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Discovery error", e)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isRunning && isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                // 更新本地心跳
                _localNode.value?.let { node ->
                    _localNode.value = node.copy(lastHeartbeat = System.currentTimeMillis())
                }

                // 简化实现
                val now = System.currentTimeMillis()
                _registeredNodes.value.forEach { (nodeId, node) ->
                    if (now - node.lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                        // 正常超时
                        _registeredNodes.value = _registeredNodes.value.toMutableMap().apply {
                            put(nodeId, node.copy(status = NodeStatus.SUSPECTED))
                        }
                    }
                }

                // 广播心跳
                broadcastHeartbeat()
            }
        }
    }

    private fun startCleanup() {
        cleanupJob = scope.launch {
            while (isRunning && isActive) {
                delay(HEARTBEAT_INTERVAL_MS * 2)

                // 清理过期的锁
                val now = System.currentTimeMillis()
                _distributedLocks.value.forEach { (lockId, lock) ->
                    if (lock.expiresAt < now) {
                        _distributedLocks.value = _distributedLocks.value.toMutableMap().apply {
                            remove(lockId)
                        }
                    }
                }

                // 清理不健康的节点
                _registeredNodes.value.forEach { (nodeId, node) ->
                    if (now - node.lastHeartbeat > HEARTBEAT_TIMEOUT_MS * 3) {
                        _registeredNodes.value = _registeredNodes.value.toMutableMap().apply {
                            remove(nodeId)
                        }
                        disconnectFromNode(nodeId)
                    }
                }

                // 清理服务缓存
                serviceCache.entries.removeAll { (_, pair) ->
                    System.currentTimeMillis() - pair.second > SERVICE_CACHE_TTL_MS
                }
            }
        }
    }

    private fun registerLocalSkills() {
        scope.launch {
            val skills = skillManager.getAvailableSkills()
            skills.forEach { (skillId, skill) ->
                registerService(
                    skillId = skillId,
                    skillName = skill.name,
                    endpoint = "/skill/${skillId}",
                    metadata = mapOf("version" to skill.version)
                )
            }

            // 广播注销
            _localNode.value?.let { node ->
                _localNode.value = node.copy(
                    registeredSkills = skills.keys.toList()
                )
            }
        }
    }

    private fun getConnection(node: NodeInfo): Socket? {
        val key = "${node.host}:${node.port}"
        return nodeConnections.getOrPut(key) {
            Socket(node.host, node.port)
        }
    }

    private fun broadcastServiceRegistration(registration: ServiceRegistration) {
        // 启动服务
        val message = mapOf(
            "type" to "service_register",
            "skillId" to registration.skillId,
            "skillName" to registration.skillName,
            "nodeId" to registration.nodeId,
            "endpoint" to registration.endpoint
        )
        broadcast(message)
    }

    private fun broadcastServiceUnregistration(skillId: String, nodeId: String) {
        val message = mapOf(
            "type" to "service_unregister",
            "skillId" to skillId,
            "nodeId" to nodeId
        )
        broadcast(message)
    }

    private fun broadcastNodeJoin() {
        val node = _localNode.value ?: return
        val message = mapOf(
            "type" to "node_join",
            "nodeId" to node.nodeId,
            "host" to node.host,
            "port" to node.port,
            "name" to node.name,
            "capabilities" to node.capabilities,
            "skills" to node.registeredSkills
        )
        broadcast(message)
    }

    private fun broadcastLeave(nodeId: String) {
        val message = mapOf(
            "type" to "node_leave",
            "nodeId" to nodeId
        )
        broadcast(message)
    }

    private fun broadcastHeartbeat() {
        val node = _localNode.value ?: return
        val message = mapOf(
            "type" to "heartbeat",
            "nodeId" to node.nodeId,
            "load" to node.load
        )
        broadcast(message)
    }

    private fun broadcastLockAcquired(lock: DistributedLock) {
        val message = mapOf(
            "type" to "lock_acquired",
            "lockId" to lock.lockId,
            "ownerNodeId" to lock.ownerNodeId,
            "expiresAt" to lock.expiresAt
        )
        broadcast(message)
    }

    private fun broadcastLockReleased(lockId: String, nodeId: String) {
        val message = mapOf(
            "type" to "lock_released",
            "lockId" to lockId,
            "nodeId" to nodeId
        )
        broadcast(message)
    }

    private fun broadcast(message: Map<String, Any>) {
        scope.launch {
            val data = serializeToJson(message).toByteArray()

            nodeConnections.values.forEach { socket ->
                try {
                    synchronized(socket) {
                        val outputStream = DataOutputStream(socket.getOutputStream())
                        outputStream.writeInt(data.size)
                        outputStream.write(data)
                        outputStream.flush()
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Broadcast failed to one node", e)
                }
            }
        }
    }

    // ========== 工具方法 ==========

    private fun generateNodeId(): String {
        val mac = getLocalMacAddress()
        return "node_${mac}_${(Math.random() * 10000).toInt()}"
    }

    private fun generateRequestId(): String {
        return "req_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    private fun getLocalIpAddress(): String {
        return try {
            InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun getLocalMacAddress(): String {
        return try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                val mac = ni.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    return mac.joinToString(":") { "%02X".format(it) }
                }
            }
            "00:00:00:00:00:00"
        } catch (e: Exception) {
            "00:00:00:00:00:00"
        }
    }

    private fun serializeToJson(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append("{")
        map.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(",")
            sb.append("\"${key}\":")
            when (value) {
                is String -> sb.append("\"${value.replace("\"", "\\\"")}\"")
                is Number, is Boolean -> sb.append(value)
                is List<*> -> sb.append(serializeToJson(value.associate { "v${index}" to it }))
                is Map<*, *> -> sb.append(serializeToJson(value as? Map<String, Any?> ?: emptyMap()))
                null -> sb.append("null")
                else -> sb.append("\"${value.toString().replace("\"", "\\\"")}\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun parseJson(json: String): Map<String, Any> {
        // 简化实现：实际应使用 JSON 库
        return emptyMap()
    }

    // ========== 状态类 ==========

    fun getClusterStats(): ClusterStats {
        return ClusterStats(
            localNode = _localNode.value,
            totalNodes = _registeredNodes.value.size + 1,
            healthyNodes = _registeredNodes.value.count { it.value.isHealthy } + 1,
            totalServices = _serviceRegistry.value.values.sumOf { it.size },
            activeCalls = _activeCalls.value.size,
            locksHeld = _distributedLocks.value.size
        )
    }

    data class ClusterStats(
        val localNode: NodeInfo?,
        val totalNodes: Int,
        val healthyNodes: Int,
        val totalServices: Int,
        val activeCalls: Int,
        val locksHeld: Int
    )
}
