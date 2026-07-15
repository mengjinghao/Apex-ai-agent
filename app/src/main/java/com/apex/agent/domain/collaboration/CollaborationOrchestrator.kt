package com.apex.agent.domain.collaboration

import com.apex.agent.domain.interfaces.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 协作编排器 - 统一的ICollaborationEngine实现
 *
 * 职责：
 * 1. 内存任务队列，支持优先级排序
 * 2. Agent注册表与负载均衡
 * 3. 基于Agent能力和负载的任务分配
 * 4. 监听器通知系统（弱引用防止内存泄漏）
 * 5. 线程安全操作
 * 6. UUID生成任务ID
 * 7. Agent心跳检测（超时标记为OFFLINE）
 */
class CollaborationOrchestrator(
    private val heartbeatTimeoutMs: Long = 30000L,
    private val maxQueueSize: Int = 500,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : ICollaborationEngine {

    private data class PriorityTask(
        val task: CollaborationTask,
        val createdAt: Long = System.currentTimeMillis()
    )
        private val taskQueue = PriorityBlockingQueue<PriorityTask>(maxQueueSize) { a, b ->
        b.task.priority.compareTo(a.task.priority)
            .takeIf { it != 0 } ?: a.createdAt.compareTo(b.createdAt)
    }
        private val taskStatusMap = ConcurrentHashMap<String, TaskStatus>()
        private val agentRegistry = ConcurrentHashMap<String, AgentCapability>()
        private val agentHeartbeats = ConcurrentHashMap<String, Long>()
        private val listeners = ConcurrentHashMap<String, WeakReference<CollaborationListener>>()
        private val listenerCounter = AtomicLong(0)
        private val _processing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _processing.asStateFlow()
        private var processorJob: Job? = null
    private var heartbeatJob: Job? = null

    init {
        startProcessor()
        startHeartbeatChecker()
    }

    /**
     * 启动后台任务处理器
     */
    private fun startProcessor() {
        processorJob = scope.launch {
            _processing.value = true
            while (isActive) {
                try {
                    val priorityTask = taskQueue.poll()
        if (priorityTask != null) {
                        processTask(priorityTask.task)
                    } else {
                        delay(100)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            _processing.value = false
        }
    }

    /**
     * 启动Agent心跳检查协程
     */
    private fun startHeartbeatChecker() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatTimeoutMs / 2)
        val now = System.currentTimeMillis()
                agentRegistry.keys.forEach { agentId ->
                    val lastBeat = agentHeartbeats[agentId] ?: return@forEach
                    if (now - lastBeat > heartbeatTimeoutMs) {
                        agentRegistry.computeIfPresent(agentId) { _, cap ->
                            cap.copy(status = AgentStatus.OFFLINE)
                        }?.also {
                            notifyListeners { it.onAgentStatusChanged(agentId, AgentStatus.OFFLINE) }
                        }
                    }
                }
            }
        }
    }

    /**
     * 提交任务到队列
     * @return 任务ID
     * @throws IllegalStateException 队列已满
     */
    override suspend fun submitTask(task: CollaborationTask): String {
        val taskId = if (task.id.isBlank()) UUID.randomUUID().toString() else task.id
        val finalTask = task.copy(id = taskId)
        val status = TaskStatus(
            taskId = taskId,
            state = TaskState.PENDING,
            createdAt = System.currentTimeMillis()
        )
        taskStatusMap[taskId] = status

        if (!taskQueue.offer(PriorityTask(finalTask))) {
            taskStatusMap.remove(taskId)
        throw IllegalStateException("Task queue is full (max=$maxQueueSize), task $taskId rejected")
        }
        return taskId
    }

    /**
     * 取消任务
     */
    override suspend fun cancelTask(taskId: String): Boolean {
        val status = taskStatusMap[taskId] ?: return false
        if (status.state == TaskState.COMPLETED || status.state == TaskState.FAILED || status.state == TaskState.CANCELLED) {
            return false
        }
        taskStatusMap[taskId] = status.copy(
            state = TaskState.CANCELLED,
            completedAt = System.currentTimeMillis()
        )
        return true
    }

    /**
     * 获取任务状态
     */
    override suspend fun getTaskStatus(taskId: String): TaskStatus? = taskStatusMap[taskId]

    /**
     * 注册Agent
     * @param capability Agent能力描述
     * @return true表示注册成功，false表示已存在
     */
    override suspend fun registerAgent(capability: AgentCapability): Boolean {
        val existing = agentRegistry.putIfAbsent(capability.agentId, capability)
        if (existing == null) {
            agentHeartbeats[capability.agentId] = System.currentTimeMillis()
        return true
        }
        return false
    }

    /**
     * 注销Agent
     */
    override suspend fun unregisterAgent(agentId: String): Boolean {
        agentRegistry.remove(agentId)
        agentHeartbeats.remove(agentId)
        return true
    }

    /**
     * 获取可用Agent列表（仅IDLE状态）
     */
    override suspend fun getAvailableAgents(): List<AgentCapability> {
        return agentRegistry.values.filter { it.status == AgentStatus.IDLE }
    }

    /**
     * 更新Agent心跳时间戳
     */
    fun updateHeartbeat(agentId: String) {
        agentHeartbeats[agentId] = System.currentTimeMillis()
        agentRegistry.computeIfPresent(agentId) { _, cap ->
            if (cap.status == AgentStatus.OFFLINE) {
                cap.copy(status = AgentStatus.IDLE)
            } else cap
        }
    }

    /**
     * 添加监听器（弱引用持有）
     */
    override fun addListener(listener: CollaborationListener) {
        val id = "listener_${listenerCounter.incrementAndGet()}"
        listeners[id] = WeakReference(listener)
    }

    /**
     * 移除监听器
     */
    override fun removeListener(listener: CollaborationListener) {
        val entry = listeners.entries.find { (_, ref) -> ref.get() === listener }
        entry?.let { listeners.remove(it.key) }
    }

    /**
     * 处理单个任务：分配Agent并更新状态
     */
    private suspend fun processTask(task: CollaborationTask) {
        val status = taskStatusMap[task.id] ?: return
        taskStatusMap[task.id] = status.copy(state = TaskState.ASSIGNED)
        val agent = selectAgent(task)
        val agentId = agent?.agentId ?: run {
            taskStatusMap[task.id] = status.copy(
                state = TaskState.FAILED,
                error = "No available agent",
                completedAt = System.currentTimeMillis()
            )
        return
        }

        taskStatusMap[task.id] = status.copy(state = TaskState.RUNNING, assignedAgentId = agentId)
        notifyListeners { it.onTaskStarted(task.id, agentId) }

        try {
            val agentCap = agentRegistry[agentId] ?: return
            val updatedCap = agentCap.copy(load = (agentCap.load + 0.2f).coerceAtMost(1f), status = AgentStatus.BUSY)
            agentRegistry[agentId] = updatedCap
            notifyListeners { it.onAgentStatusChanged(agentId, AgentStatus.BUSY) }
        for (i in 1..5) {
                delay(200)
        val progress = i / 5f
                taskStatusMap[task.id] = status.copy(
                    state = TaskState.RUNNING,
                    assignedAgentId = agentId,
                    progress = progress
                )
                notifyListeners { it.onTaskProgress(task.id, progress, "Processing step $i/5") }
            }
        val result = "Task ${task.id} completed by agent $agentId"
            taskStatusMap[task.id] = status.copy(
                state = TaskState.COMPLETED,
                assignedAgentId = agentId,
                progress = 1f,
                result = result,
                completedAt = System.currentTimeMillis()
            )

            agentRegistry.computeIfPresent(agentId) { _, cap ->
                cap.copy(load = (cap.load - 0.2f).coerceAtLeast(0f), status = AgentStatus.IDLE)
            }
            notifyListeners { it.onTaskCompleted(task.id, result) }
            notifyListeners { it.onAgentStatusChanged(agentId, AgentStatus.IDLE) }
        } catch (e: Exception) {
            taskStatusMap[task.id] = status.copy(
                state = TaskState.FAILED,
                assignedAgentId = agentId,
                error = e.message ?: "Unknown error",
                completedAt = System.currentTimeMillis()
            )
            agentRegistry.computeIfPresent(agentId) { _, cap ->
                cap.copy(load = (cap.load - 0.2f).coerceAtLeast(0f), status = AgentStatus.IDLE)
            }
            notifyListeners { it.onTaskFailed(task.id, e.message ?: "Unknown error") }
            notifyListeners { it.onAgentStatusChanged(agentId, AgentStatus.IDLE) }
        }
    }

    /**
     * 基于Agent能力和负载选择最优Agent
     */
    private fun selectAgent(task: CollaborationTask): AgentCapability? {
        val candidates = agentRegistry.values
            .filter { it.status == AgentStatus.IDLE && it.load < 0.8f }
        if (task.assignedAgentIds.isNotEmpty()) {
            task.assignedAgentIds.forEach { preferredId ->
                candidates.find { it.agentId == preferredId }?.let { return it }
            }
        }
        val skillRequired = extractRequiredSkills(task)
        if (skillRequired.isNotEmpty()) {
            val matched = candidates.filter { cap ->
                cap.skills.any { skill -> skillRequired.any { it.contains(skill, ignoreCase = true) || skill.contains(it, ignoreCase = true) } }
            }
        if (matched.isNotEmpty()) {
                return matched.minByOrNull { it.load }
            }
        }
        return candidates.minByOrNull { it.load }
    }

    /**
     * 从任务载荷中提取所需技能关键字
     */
    private fun extractRequiredSkills(task: CollaborationTask): List<String> {
        val skillKeywords = mutableListOf<String>()
        task.payload.forEach { (key, value) ->
            if (key.equals("skill", ignoreCase = true) || key.equals("skills", ignoreCase = true)) {
                skillKeywords.addAll(value.split(",").map { it.trim() }.filter { it.isNotBlank() })
            }
            skillKeywords.add(key)
        }
        return skillKeywords.distinct()
    }

    /**
     * 通知所有监听器
     */
    private fun notifyListeners(action: (CollaborationListener) -> Unit) {
        val deadEntries = mutableListOf<String>()
        listeners.forEach { (id, ref) ->
            val listener = ref.get()
        if (listener != null) {
                try {
                    action(listener)
                } catch (_: Exception) {
                }
            } else {
                deadEntries.add(id)
            }
        }
        deadEntries.forEach { listeners.remove(it) }
    }

    /**
     * 清理资源，停止后台协程
     */
    fun shutdown() {
        processorJob?.cancel()
        heartbeatJob?.cancel()
        taskQueue.clear()
        taskStatusMap.clear()
        agentRegistry.clear()
        agentHeartbeats.clear()
        listeners.clear()
        _processing.value = false
    }

    /**
     * 获取当前队列深度
     */
    fun getQueueDepth(): Int = taskQueue.size

    /**
     * 获取已注册Agent总数
     */
    fun getAgentCount(): Int = agentRegistry.size

    /**
     * 获取当前任务总数
     */
    fun getTaskCount(): Int = taskStatusMap.size
}
