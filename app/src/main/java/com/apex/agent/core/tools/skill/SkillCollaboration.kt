package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill 间协作机�?
 *
 * 功能�?
 * - �?Skill 状态共�?
 * - 事件总线通信
 * - 数据交换管道
 * - 协作调度
 * - 依赖追踪
 */
class SkillCollaboration private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillCollaboration"
        private const val SHARED_STATE_DIR = "shared_state"
        private const val PIPE_DIR = "pipes"
        private const val MAX_PIPE_BUFFER_SIZE = 1000

        @Volatile private var INSTANCE: SkillCollaboration? = null

        fun getInstance(context: Context): SkillCollaboration {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillCollaboration(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    /**
     * 共享状态项
     */
    data class SharedState(
        val key: String,
        val value: Any?,
        val skillId: String,
        val timestamp: Long,
        val scope: StateScope,
        val accessCount: Long = 0,
        val expiresAt: Long? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    enum class StateScope {
        PRIVATE,      // 仅创建者可访问
        SKILL,        // �?Skill 内共�?
        LOCAL,        // 本地所�?Skill 可共�?
        GLOBAL        // 全局共享（跨进程�?
    }

    /**
     * 事件
     */
    data class SkillEvent(
        val id: String,
        val type: EventType,
        val sourceSkillId: String,
        val targetSkillId: String?,  // null 表示广播
        val payload: Any?,
        val timestamp: Long,
        val correlationId: String?,  // 用于关联请求/响应
        val replyTo: String?        // 回复地址
    )

    enum class EventType {
        STATE_CHANGED,
        DATA_REQUEST,
        DATA_RESPONSE,
        WORKFLOW_TRIGGER,
        WORKFLOW_COMPLETE,
        ERROR,
        PING,
        PONG,
        SUBSCRIBE,
        UNSUBSCRIBE,
        CUSTOM
    }

    /**
     * 数据管道
     */
    data class Pipe(
        val id: String,
        val name: String,
        val sourceSkillId: String,
        val targetSkillId: String?,
        val capacity: Int,
        val isOpen: Boolean,
        val createdAt: Long,
        val messageCount: Long = 0
    )

    data class PipeMessage(
        val id: String,
        val pipeId: String,
        val content: Any?,
        val metadata: Map<String, String>,
        val timestamp: Long,
        val isEndOfStream: Boolean = false
    )

    /**
     * 协作任务
     */
    data class CollaborationTask(
        val id: String,
        val name: String,
        val participatingSkills: List<String>,
        val workflow: CollaborationWorkflow,
        val status: TaskStatus,
        val createdAt: Long,
        val startedAt: Long? = null,
        val completedAt: Long? = null,
        val results: Map<String, Any?> = emptyMap()
    )

    enum class TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    data class CollaborationWorkflow(
        val steps: List<CollaborationStep>
    )

    data class CollaborationStep(
        val skillId: String,
        val action: String,
        val inputs: Map<String, ParameterValue>,
        val outputs: List<String>,
        val dependsOn: List<String> = emptyList(),  // 依赖的前置步�?ID
        val timeout: Long = 30000
    )

    /**
     * Skill 依赖关系
     */
    data class SkillDependency(
        val skillId: String,
        val dependsOn: String,
        val type: DependencyType,
        val isOptional: Boolean
    )

    enum class DependencyType {
        DATA_DEPENDENCY,    // 数据依赖
        RESULT_DEPENDENCY,  // 结果依赖
        STATE_DEPENDENCY,   // 状态依�?
        EVENT_DEPENDENCY    // 事件依赖
    }

    // ========== 状�?==========

    private val _sharedStates = ConcurrentHashMap<String, SharedState>()
    private val _eventBus = MutableSharedFlow<SkillEvent>(replay = 0, extraBufferCapacity = 100)
    val eventBus: SharedFlow<SkillEvent> = _eventBus.asSharedFlow()

    private val _pipes = ConcurrentHashMap<String, Pipe>()
    private val _pipeBuffers = ConcurrentHashMap<String, ArrayDeque<PipeMessage>>()

    private val _activeTasks = ConcurrentHashMap<String, CollaborationTask>()
    private val _skillSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()  // skillId -> subscribed event types

    private val _collaborationGraph = MutableStateFlow<CollaborationGraph?>(null)
    val collaborationGraph: StateFlow<CollaborationGraph?> = _collaborationGraph.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val skillManager by lazy { SkillManager.getInstance(context) }

    init {
        initializeDirectories()
        startEventProcessing()
    }

    // ========== 共享状�?API ==========

    /**
     * 设置共享状�?
     */
    suspend fun setSharedState(
        key: String,
        value: Any?,
        skillId: String,
        scope: StateScope = StateScope.LOCAL,
        ttlMs: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val state = SharedState(
                key = key,
                value = value,
                skillId = skillId,
                timestamp = System.currentTimeMillis(),
                scope = scope,
                expiresAt = ttlMs?.let { System.currentTimeMillis() + it }
            )

            _sharedStates[key] = state

            // 持久�?
            persistState(key, state)

            // 发送事�?
            emitEvent(SkillEvent(
                id = generateId(),
                type = EventType.STATE_CHANGED,
                sourceSkillId = skillId,
                targetSkillId = null,
                payload = mapOf("key" to key, "scope" to scope.name),
                timestamp = System.currentTimeMillis(),
                correlationId = null,
                replyTo = null
            ))

            // 更新协作�?
            updateCollaborationGraph()

            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to set shared state: ${key}", e)
            false
        }
    }

    /**
     * 获取共享状�?
     */
    suspend fun getSharedState(key: String, requesterSkillId: String): Any? = withContext(Dispatchers.IO) {
        val state = _sharedStates[key] ?: loadPersistedState(key)

        if (state == null) {
            return@withContext null
        }

        // 检查范围权�?
        if (!hasReadPermission(state, requesterSkillId)) {
            AppLogger.w(TAG, "No read permission for state: ${key} by ${requesterSkillId}")
            return@withContext null
        }

        // 检查过�?
        if (state.expiresAt != null && state.expiresAt < System.currentTimeMillis()) {
            _sharedStates.remove(key)
            return@withContext null
        }

        state
    }

    /**
     * 删除共享状�?
     */
    suspend fun removeSharedState(key: String, skillId: String): Boolean = withContext(Dispatchers.IO) {
        val state = _sharedStates[key]

        if (state == null || state.skillId != skillId) {
            return@withContext false
        }

        _sharedStates.remove(key)
        deletePersistedState(key)
        true
    }

    /**
     * 获取所有可读的状态键
     */
    fun getAccessibleStateKeys(skillId: String, scope: StateScope? = null): List<String> {
        return _sharedStates.filter { (_, state) ->
            val scopeMatch = scope == null || state.scope == scope
            val permissionMatch = hasReadPermission(state, skillId)
            val notExpired = state.expiresAt == null || state.expiresAt > System.currentTimeMillis()
            scopeMatch && permissionMatch && notExpired
        }.keys.toList()
    }

    /**
     * 观察状态变�?
     */
    fun observeStateChanges(skillId: String): Flow<SharedState> = flow {
        val knownKeys = mutableSetOf<String>()

        _eventBus.filter { event ->
            event.type == EventType.STATE_CHANGED && event.sourceSkillId != skillId
        }.collect { event ->
            val key = (event.payload as? Map<*, *>)?.get("key") as? String
            if (key != null && key !in knownKeys) {
                knownKeys.add(key)
                _sharedStates[key]?.let { emit(it) }
            }
        }
    }

    private fun hasReadPermission(state: SharedState, skillId: String): Boolean {
        return when (state.scope) {
            StateScope.PRIVATE -> state.skillId == skillId
            StateScope.SKILL -> state.skillId == skillId  // 简化：�?ID
            StateScope.LOCAL, StateScope.GLOBAL -> true
        }
    }

    // ========== 事件总线 API ==========

    /**
     * 发送事�?
     */
    suspend fun sendEvent(event: SkillEvent): Boolean {
        return try {
            _eventBus.emit(event)
            AppLogger.d(TAG, "Event sent: ${event.type} from ${event.sourceSkillId} to ${event.targetSkillId ?: "broadcast"}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to send event", e)
            false
        }
    }

    /**
     * 发送定向事�?
     */
    suspend fun sendToSkill(
        sourceSkillId: String,
        targetSkillId: String,
        type: EventType,
        payload: Any?,
        correlationId: String? = null
    ): Boolean {
        val event = SkillEvent(
            id = generateId(),
            type = type,
            sourceSkillId = sourceSkillId,
            targetSkillId = targetSkillId,
            payload = payload,
            timestamp = System.currentTimeMillis(),
            correlationId = correlationId,
            replyTo = null
        )
        return sendEvent(event)
    }

    /**
     * 广播事件
     */
    suspend fun broadcast(
        sourceSkillId: String,
        type: EventType,
        payload: Any?
    ): Boolean {
        val event = SkillEvent(
            id = generateId(),
            type = type,
            sourceSkillId = sourceSkillId,
            targetSkillId = null,
            payload = payload,
            timestamp = System.currentTimeMillis(),
            correlationId = null,
            replyTo = null
        )
        return sendEvent(event)
    }

    /**
     * 订阅事件
     */
    fun subscribe(skillId: String, eventTypes: Set<EventType>) {
        _skillSubscriptions.getOrPut(skillId) { mutableSetOf() }.addAll(eventTypes.map { it.name })
        AppLogger.d(TAG, "Skill ${skillId} subscribed to: ${eventTypes}")
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(skillId: String, eventTypes: Set<EventType>) {
        _skillSubscriptions[skillId]?.removeAll(eventTypes.map { it.name }.toSet())
    }

    /**
     * 获取技能订�?
     */
    fun getSubscriptions(skillId: String): Set<EventType> {
        return _skillSubscriptions[skillId]?.mapNotNull {
            runCatching { EventType.valueOf(it) }.getOrNull()
        }?.toSet() ?: emptySet()
    }

    /**
     * 观察事件�?
     */
    fun observeEvents(skillId: String): Flow<SkillEvent> = _eventBus.filter { event ->
        val subscribedTypes = _skillSubscriptions[skillId] ?: emptySet()
        event.targetSkillId == null ||  // 广播
        event.targetSkillId == skillId ||  // 定向
        event.type.name in subscribedTypes  // 订阅的类�?
    }

    // ========== 数据管道 API ==========

    /**
     * 创建管道
     */
    fun createPipe(
        name: String,
        sourceSkillId: String,
        targetSkillId: String?,
        capacity: Int = MAX_PIPE_BUFFER_SIZE
    ): Pipe? {
        val pipeId = generateId()

        val pipe = Pipe(
            id = pipeId,
            name = name,
            sourceSkillId = sourceSkillId,
            targetSkillId = targetSkillId,
            capacity = capacity,
            isOpen = true,
            createdAt = System.currentTimeMillis()
        )

        _pipes[pipeId] = pipe
        _pipeBuffers[pipeId] = ArrayDeque(capacity)

        AppLogger.d(TAG, "Pipe created: ${name} (${pipeId})")
        return pipe
    }

    /**
     * 发送消息到管道
     */
    suspend fun sendToPipe(pipeId: String, content: Any?, metadata: Map<String, String> = emptyMap()): Boolean {
        val pipe = _pipes[pipeId] ?: return false
        if (!pipe.isOpen) return false

        val buffer = _pipeBuffers[pipeId] ?: return false

        val message = PipeMessage(
            id = generateId(),
            pipeId = pipeId,
            content = content,
            metadata = metadata,
            timestamp = System.currentTimeMillis()
        )

        synchronized(buffer) {
            if (buffer.size >= pipe.capacity) {
                buffer.removeFirst()  // 丢弃最老的
            }
            buffer.addLast(message)
        }

        // 更新计数
        _pipes[pipeId] = pipe.copy(messageCount = pipe.messageCount + 1)

        return true
    }

    /**
     * 从管道读取消�?
     */
    suspend fun readFromPipe(pipeId: String, timeoutMs: Long = 5000): PipeMessage? = withContext(Dispatchers.IO) {
        val pipe = _pipes[pipeId] ?: return@withContext null
        val buffer = _pipeBuffers[pipeId] ?: return@withContext null

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val message = synchronized(buffer) {
                buffer.pollFirst()
            }

            if (message != null) {
                return@withContext message
            }

            // 等待新消�?
            delay(100)
        }

        null
    }

    /**
     * 关闭管道
     */
    fun closePipe(pipeId: String): Boolean {
        val pipe = _pipes[pipeId] ?: return false

        // 发�?EOS 消息
        scope.launch {
            val buffer = _pipeBuffers[pipeId]
            synchronized(buffer!!) {
                buffer.addLast(PipeMessage(
                    id = generateId(),
                    pipeId = pipeId,
                    content = null,
                    metadata = emptyMap(),
                    timestamp = System.currentTimeMillis(),
                    isEndOfStream = true
                ))
            }
        }

        _pipes[pipeId] = pipe.copy(isOpen = false)
        return true
    }

    /**
     * 获取管道状�?
     */
    fun getPipeStatus(pipeId: String): Pipe? = _pipes[pipeId]

    /**
     * 获取技能的所有管�?
     */
    fun getPipesForSkill(skillId: String): List<Pipe> {
        return _pipes.values.filter {
            it.sourceSkillId == skillId || it.targetSkillId == skillId
        }
    }

    // ========== 协作任务 API ==========

    /**
     * 创建协作任务
     */
    suspend fun createCollaborationTask(
        name: String,
        participatingSkills: List<String>,
        workflow: CollaborationWorkflow
    ): CollaborationTask = withContext(Dispatchers.IO) {
        val task = CollaborationTask(
            id = generateId(),
            name = name,
            participatingSkills = participatingSkills,
            workflow = workflow,
            status = TaskStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )

        _activeTasks[task.id] = task
        updateCollaborationGraph()

        task
    }

    /**
     * 启动协作任务
     */
    suspend fun startCollaborationTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        val task = _activeTasks[taskId] ?: return@withContext false

        if (task.status != TaskStatus.PENDING) {
            return@withContext false
        }

        _activeTasks[taskId] = task.copy(
            status = TaskStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )

        // 触发工作流执�?
        executeCollaborationWorkflow(task)

        true
    }

    /**
     * 取消协作任务
     */
    suspend fun cancelCollaborationTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        val task = _activeTasks[taskId] ?: return@withContext false

        if (task.status !in listOf(TaskStatus.PENDING, TaskStatus.RUNNING)) {
            return@withContext false
        }

        _activeTasks[taskId] = task.copy(
            status = TaskStatus.CANCELLED,
            completedAt = System.currentTimeMillis()
        )

        // 通知所有参与技�?
        task.participatingSkills.forEach { skillId ->
            sendToSkill("system", skillId, EventType.ERROR, "Task cancelled: ${task.name}")
        }

        true
    }

    /**
     * 获取任务状�?
     */
    fun getTaskStatus(taskId: String): CollaborationTask? = _activeTasks[taskId]

    /**
     * 获取技能参与的所有任�?
     */
    fun getTasksForSkill(skillId: String): List<CollaborationTask> {
        return _activeTasks.values.filter {
            skillId in it.participatingSkills
        }
    }

    /**
     * 获取任务结果
     */
    fun getTaskResults(taskId: String): Map<String, Any?> {
        return _activeTasks[taskId]?.results ?: emptyMap()
    }

    private suspend fun executeCollaborationWorkflow(task: CollaborationTask) {
        val results = mutableMapOf<String, Any?>()
        val completedSteps = mutableSetOf<String>()

        for (step in task.workflow.steps) {
            // 检查依赖是否满�?
            val dependenciesMet = step.dependsOn.all { depId ->
                completedSteps.contains(depId)
            }

            if (!dependenciesMet) {
                AppLogger.w(TAG, "Dependencies not met for step: ${step.skillId}.${step.action}")
                continue
            }

            // 发送执行请�?
            val requestId = generateId()
            val responseReceived = CompletableDeferred<Boolean>()

            // 监听响应
            val responseJob = scope.launch {
                _eventBus.filter { event ->
                    event.type == EventType.DATA_RESPONSE &&
                    event.correlationId == requestId
                }.collect { event ->
                    results["${step.skillId}.${step.action}"] = event.payload
                    completedSteps.add("${step.skillId}.${step.action}")
                    responseReceived.complete(true)
                }
            }

            // 发送执行请�?
            sendToSkill(
                sourceSkillId = "collaboration_manager",
                targetSkillId = step.skillId,
                type = EventType.DATA_REQUEST,
                payload = mapOf(
                    "action" to step.action,
                    "inputs" to step.inputs.mapValues { it.value.toString() }
                ),
                correlationId = requestId
            )

            // 等待响应或超�?
            val completed = withTimeoutOrNull(step.timeout) {
                responseReceived.await()
            } ?: false

            responseJob.cancel()

            if (!completed) {
                AppLogger.e(TAG, "Step timeout: ${step.skillId}.${step.action}")
                _activeTasks[task.id] = task.copy(
                    status = TaskStatus.FAILED,
                    completedAt = System.currentTimeMillis()
                )
                return
            }
        }

        // 任务完成
        _activeTasks[task.id] = task.copy(
            status = TaskStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
            results = results
        )

        // 通知所有参与技�?
        task.participatingSkills.forEach { skillId ->
            sendToSkill(
                sourceSkillId = "collaboration_manager",
                targetSkillId = skillId,
                type = EventType.WORKFLOW_COMPLETE,
                payload = mapOf("taskId" to task.id, "results" to results)
            )
        }

        updateCollaborationGraph()
    }

    // ========== 协作�?==========

    data class CollaborationGraph(
        val nodes: List<CollaborationNode>,
        val edges: List<CollaborationEdge>,
        val centralSkills: List<String>,
        val isolatedSkills: List<String>
    )

    data class CollaborationNode(
        val skillId: String,
        val skillName: String,
        val connectionCount: Int,
        val isActive: Boolean
    )

    data class CollaborationEdge(
        val from: String,
        val to: String,
        val type: DependencyType,
        val strength: Float  // 0-1, based on interaction frequency
    )

    private fun updateCollaborationGraph() {
        val nodes = mutableListOf<CollaborationNode>()
        val edges = mutableListOf<CollaborationEdge>()
        val interactionCounts = mutableMapOf<Pair<String, String>, Long>()

        // 收集所有共享状态创建�?
        val stateOwners = _sharedStates.values.groupBy { it.skillId }

        // 收集事件交互
        // 简化：基于参与任务的次�?
        _activeTasks.values.forEach { task ->
            task.participatingSkills.forEach { skillId ->
                val node = CollaborationNode(
                    skillId = skillId,
                    skillName = skillId,
                    connectionCount = task.participatingSkills.size - 1,
                    isActive = task.status == TaskStatus.RUNNING
                )
                if (nodes.none { it.skillId == skillId }) {
                    nodes.add(node)
                }
            }
        }

        // 构建�?
        _activeTasks.values.forEach { task ->
            val skills = task.participatingSkills
            for (i in skills.indices) {
                for (j in i + 1 until skills.size) {
                    val pair = Pair(skills[i], skills[j])
                    interactionCounts[pair] = (interactionCounts[pair] ?: 0) + 1
                }
            }
        }

        interactionCounts.forEach { (pair, count) ->
            val strength = min(1f, count / 10f)
            edges.add(CollaborationEdge(
                from = pair.first,
                to = pair.second,
                type = DependencyType.DATA_DEPENDENCY,
                strength = strength
            ))
        }

        // 找出中心技�?
        val centralSkills = nodes.sortedByDescending { it.connectionCount }
            .take(3)
            .map { it.skillId }

        // 找出孤立技�?
        val isolatedSkills = nodes.filter { it.connectionCount == 0 }
            .map { it.skillId }

        _collaborationGraph.value = CollaborationGraph(
            nodes = nodes,
            edges = edges,
            centralSkills = centralSkills,
            isolatedSkills = isolatedSkills
        )
    }

    // ========== 事件处理 ==========

    private fun startEventProcessing() {
        scope.launch {
            _eventBus.collect { event ->
                processEvent(event)
            }
        }
    }

    private suspend fun processEvent(event: SkillEvent) {
        when (event.type) {
            EventType.SUBSCRIBE -> {
                val types = (event.payload as? List<*>)?.mapNotNull {
                    runCatching { EventType.valueOf(it.toString()) }.getOrNull()
                }?.toSet() ?: emptySet()
                event.sourceSkillId.let { subscribe(it, types) }
            }
            EventType.UNSUBSCRIBE -> {
                val types = (event.payload as? List<*>)?.mapNotNull {
                    runCatching { EventType.valueOf(it.toString()) }.getOrNull()
                }?.toSet() ?: emptySet()
                event.sourceSkillId.let { unsubscribe(it, types) }
            }
            EventType.PING -> {
                sendToSkill(
                    sourceSkillId = event.sourceSkillId,
                    targetSkillId = event.sourceSkillId,
                    type = EventType.PONG,
                    payload = null,
                    correlationId = event.correlationId
                )
            }
            else -> { /* 其他事件类型由订阅者处�?*/ }
        }
    }

    // ========== 持久�?==========

    private fun initializeDirectories() {
        File(context.filesDir, SHARED_STATE_DIR).mkdirs()
        File(context.filesDir, PIPE_DIR).mkdirs()
    }

    private fun persistState(key: String, state: SharedState) {
        try {
            val file = File(File(context.filesDir, SHARED_STATE_DIR), "${key}.json")
            file.writeText(serializeState(state))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist state: ${key}", e)
        }
    }

    private fun loadPersistedState(key: String): SharedState? {
        return try {
            val file = File(File(context.filesDir, SHARED_STATE_DIR), "${key}.json")
            if (file.exists()) {
                deserializeState(file.readText())
            } else null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load persisted state: ${key}", e)
            null
        }
    }

    private fun deletePersistedState(key: String) {
        try {
            val file = File(File(context.filesDir, SHARED_STATE_DIR), "${key}.json")
            file.delete()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete persisted state: ${key}", e)
        }
    }

    private fun serializeState(state: SharedState): String {
        return "${state}"
    }

    private fun deserializeState(data: String): SharedState {
        // 简化实�?
        return SharedState(
            key = "",
            value = null,
            skillId = "",
            timestamp = System.currentTimeMillis(),
            scope = StateScope.LOCAL
        )
    }

    // ========== 工具方法 ==========

    private fun generateId(): String = "id_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    fun getCollaborationStats(): CollaborationStats {
        return CollaborationStats(
            sharedStateCount = _sharedStates.size,
            activePipeCount = _pipes.count { it.value.isOpen },
            activeTaskCount = _activeTasks.count { it.value.status == TaskStatus.RUNNING },
            pendingTaskCount = _activeTasks.count { it.value.status == TaskStatus.PENDING },
            totalEventsProcessed = 0, // 简�?
            skillConnectionCount = _collaborationGraph.value?.edges?.size ?: 0
        )
    }

    data class CollaborationStats(
        val sharedStateCount: Int,
        val activePipeCount: Int,
        val activeTaskCount: Int,
        val pendingTaskCount: Int,
        val totalEventsProcessed: Long,
        val skillConnectionCount: Int
    )

    fun shutdown() {
        scope.cancel()
        _activeTasks.clear()
        _pipes.clear()
        _pipeBuffers.clear()
        _sharedStates.clear()
        AppLogger.d(TAG, "SkillCollaboration shutdown complete")
    }
}
