package com.apex.agent.presentation.multiagent.state

import com.apex.agent.presentation.multiagent.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 多 Agent 模式页面状态。
 *
 * 完整的多 Agent 协作页面状态管理，包含：
 * - Agent 列表管理
 * - 任务列表管理
 * - 消息流管理
 * - 协作模式管理
 * - 拓扑图管理
 * - 统计信息
 *
 * # 使用示例
 *
 * ```
 * val state = MultiAgentPageState()
 *
 * // 添加 Agent
 * state.addAgent(AgentCardData(name = "FileAgent", role = AgentRoleType.WORKER))
 * state.addAgent(AgentCardData(name = "ReviewAgent", role = AgentRoleType.REVIEWER))
 *
 * // 设置协作模式
 * state.setCollaborationMode(CollaborationMode.SUPERVISOR)
 *
 * // 创建任务
 * state.createTask("分析代码", "分析所有源文件的潜在 bug")
 *
 * // 分配任务
 * state.assignTask(taskId, listOf("agent_1", "agent_2"))
 *
 * // 发送消息
 * state.sendAgentMessage(fromId, toId, "开始处理")
 *
 * // 观察状态
 * state.agents.collect { agents -> ... }
 * state.tasks.collect { tasks -> ... }
 * ```
 */
class MultiAgentPageState {

    // === Agent 管理 ===

    private val _agents = MutableStateFlow<List<AgentCardData>>(emptyList())
    val agents: StateFlow<List<AgentCardData>> = _agents.asStateFlow()

    // === 任务管理 ===

    private val _tasks = MutableStateFlow<List<CollaborationTaskCard>>(emptyList())
    val tasks: StateFlow<List<CollaborationTaskCard>> = _tasks.asStateFlow()

    // === 消息流 ===

    private val _messages = MutableStateFlow<List<AgentMessageCard>>(emptyList())
    val messages: StateFlow<List<AgentMessageCard>> = _messages.asStateFlow()

    // === 协作模式 ===

    private val _collaborationMode = MutableStateFlow(CollaborationMode.PARALLEL_EXECUTION)
    val collaborationMode: StateFlow<CollaborationMode> = _collaborationMode.asStateFlow()

    // === 拓扑图 ===

    private val _topology = MutableStateFlow(CollaborationTopology())
    val topology: StateFlow<CollaborationTopology> = _topology.asStateFlow()

    // === 统计 ===

    private val _stats = MutableStateFlow(CollaborationStats())
    val stats: StateFlow<CollaborationStats> = _stats.asStateFlow()

    // === 页面状态 ===

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    // === 内部存储 ===

    private val agentMap = ConcurrentHashMap<String, AgentCardData>()
    private val taskMap = ConcurrentHashMap<String, CollaborationTaskCard>()
    private val startTime = System.currentTimeMillis()

    // ===== Agent 管理 =====

    /**
     * 添加 Agent。
     */
    fun addAgent(agent: AgentCardData): Boolean {
        val existing = agentMap.putIfAbsent(agent.id, agent)
        if (existing != null) return false
        updateAgents()
        updateTopology()
        updateStats()
        return true
    }

    /**
     * 批量添加 Agent。
     */
    fun addAgents(agents: List<AgentCardData>): Int {
        var count = 0
        for (agent in agents) {
            if (agentMap.putIfAbsent(agent.id, agent) == null) count++
        }
        updateAgents()
        updateTopology()
        updateStats()
        return count
    }

    /**
     * 移除 Agent。
     */
    fun removeAgent(agentId: String): Boolean {
        val removed = agentMap.remove(agentId) != null
        if (removed) {
            updateAgents()
            updateTopology()
            updateStats()
        }
        return removed
    }

    /**
     * 更新 Agent 状态。
     */
    fun updateAgentStatus(agentId: String, status: AgentStatus) {
        val agent = agentMap[agentId] ?: return
        agentMap[agentId] = agent.copy(status = status)
        updateAgents()
        updateStats()
    }

    /**
     * 更新 Agent 进度。
     */
    fun updateAgentProgress(agentId: String, progress: Float, currentTask: String? = null) {
        val agent = agentMap[agentId] ?: return
        agentMap[agentId] = agent.copy(
            progress = progress,
            currentTask = currentTask ?: agent.currentTask
        )
        updateAgents()
    }

    /**
     * 选中 Agent。
     */
    fun selectAgent(agentId: String?) {
        _selectedAgentId.value = agentId
    }

    /**
     * 获取选中的 Agent。
     */
    fun getSelectedAgent(): AgentCardData? {
        return _selectedAgentId.value?.let { agentMap[it] }
    }

    // ===== 任务管理 =====

    /**
     * 创建任务。
     */
    fun createTask(title: String, description: String = "", priority: TaskPriority = TaskPriority.NORMAL): String {
        val task = CollaborationTaskCard(
            title = title,
            description = description,
            priority = priority,
            createdAt = System.currentTimeMillis()
        )
        taskMap[task.id] = task
        updateTasks()
        updateStats()
        return task.id
    }

    /**
     * 分配任务给 Agent。
     */
    fun assignTask(taskId: String, agentIds: List<String>): Boolean {
        val task = taskMap[taskId] ?: return false
        taskMap[taskId] = task.copy(
            assignedAgentIds = agentIds,
            status = TaskCardStatus.ASSIGNED
        )
        updateTasks()

        // 更新 Agent 状态
        for (agentId in agentIds) {
            val agent = agentMap[agentId] ?: continue
            agentMap[agentId] = agent.copy(
                status = AgentStatus.THINKING,
                currentTask = task.title
            )
        }
        updateAgents()
        return true
    }

    /**
     * 更新任务进度。
     */
    fun updateTaskProgress(taskId: String, progress: Float) {
        val task = taskMap[taskId] ?: return
        val newStatus = when {
            progress >= 1f -> TaskCardStatus.COMPLETED
            progress > 0f -> TaskCardStatus.IN_PROGRESS
            else -> task.status
        }
        taskMap[taskId] = task.copy(progress = progress, status = newStatus)

        // 如果完成，更新 Agent 状态
        if (newStatus == TaskCardStatus.COMPLETED) {
            for (agentId in task.assignedAgentIds) {
                updateAgentStatus(agentId, AgentStatus.COMPLETED)
            }
        }

        updateTasks()
        updateStats()
    }

    /**
     * 添加子任务。
     */
    fun addSubtask(taskId: String, title: String, assignedAgentId: String? = null): Boolean {
        val task = taskMap[taskId] ?: return false
        val subtask = SubtaskCard(
            title = title,
            assignedAgentId = assignedAgentId,
            order = task.subtasks.size
        )
        taskMap[taskId] = task.copy(subtasks = task.subtasks + subtask)
        updateTasks()
        return true
    }

    /**
     * 更新子任务状态。
     */
    fun updateSubtaskStatus(taskId: String, subtaskId: String, status: TaskCardStatus): Boolean {
        val task = taskMap[taskId] ?: return false
        val subtasks = task.subtasks.map {
            if (it.id == subtaskId) it.copy(status = status) else it
        }
        val progress = subtasks.count { it.status == TaskCardStatus.COMPLETED }.toFloat() / subtasks.size.coerceAtLeast(1)
        taskMap[taskId] = task.copy(subtasks = subtasks, progress = progress)
        updateTasks()
        return true
    }

    /**
     * 取消任务。
     */
    fun cancelTask(taskId: String): Boolean {
        val task = taskMap[taskId] ?: return false
        taskMap[taskId] = task.copy(status = TaskCardStatus.CANCELLED)
        // 释放 Agent
        for (agentId in task.assignedAgentIds) {
            updateAgentStatus(agentId, AgentStatus.IDLE)
        }
        updateTasks()
        updateStats()
        return true
    }

    /**
     * 选中任务。
     */
    fun selectTask(taskId: String?) {
        _selectedTaskId.value = taskId
    }

    // ===== 消息管理 =====

    /**
     * 发送 Agent 间消息。
     */
    fun sendAgentMessage(
        fromAgentId: String,
        toAgentId: String?,
        content: String,
        type: AgentMessageType = AgentMessageType.TEXT
    ): Boolean {
        val fromAgent = agentMap[fromAgentId] ?: return false
        val toAgent = toAgentId?.let { agentMap[it] }

        val message = AgentMessageCard(
            fromAgentId = fromAgentId,
            fromAgentName = fromAgent.name,
            fromAgentRole = fromAgent.role,
            toAgentId = toAgentId,
            toAgentName = toAgent?.name,
            content = content,
            type = type
        )

        val current = _messages.value.toMutableList()
        current.add(message)
        while (current.size > 500) current.removeAt(0)
        _messages.value = current

        // 更新 Agent 消息计数
        agentMap[fromAgentId] = fromAgent.copy(messageCount = fromAgent.messageCount + 1)
        updateAgents()
        updateStats()
        return true
    }

    /**
     * 广播消息。
     */
    fun broadcastMessage(fromAgentId: String, content: String, type: AgentMessageType = AgentMessageType.TEXT) {
        sendAgentMessage(fromAgentId, null, content, type)
    }

    /**
     * 清空消息。
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    // ===== 协作模式 =====

    /**
     * 设置协作模式。
     */
    fun setCollaborationMode(mode: CollaborationMode) {
        _collaborationMode.value = mode
        updateTopology()
    }

    // ===== 运行控制 =====

    /**
     * 开始协作。
     */
    fun start() {
        _isRunning.value = true
        updateStats()
    }

    /**
     * 停止协作。
     */
    fun stop() {
        _isRunning.value = false
        // 所有 Agent 回到空闲
        for ((id, agent) in agentMap) {
            if (agent.status == AgentStatus.EXECUTING || agent.status == AgentStatus.THINKING) {
                agentMap[id] = agent.copy(status = AgentStatus.IDLE)
            }
        }
        updateAgents()
        updateStats()
    }

    /**
     * 重置所有状态。
     */
    fun reset() {
        agentMap.clear()
        taskMap.clear()
        _messages.value = emptyList()
        _collaborationMode.value = CollaborationMode.PARALLEL_EXECUTION
        _topology.value = CollaborationTopology()
        _isRunning.value = false
        _selectedAgentId.value = null
        _selectedTaskId.value = null
        _agents.value = emptyList()
        _tasks.value = emptyList()
        _stats.value = CollaborationStats()
    }

    // ===== 内部更新方法 =====

    private fun updateAgents() {
        _agents.value = agentMap.values.toList().sortedBy { it.role.ordinal }
    }

    private fun updateTasks() {
        _tasks.value = taskMap.values.toList().sortedByDescending { it.priority.weight }
    }

    private fun updateTopology() {
        val count = agentMap.size
        _topology.value = CollaborationTopology(mode = _collaborationMode.value).autoLayout(count)
    }

    private fun updateStats() {
        val allAgents = agentMap.values.toList()
        val allTasks = taskMap.values.toList()
        val allMessages = _messages.value

        _stats.value = CollaborationStats(
            totalAgents = allAgents.size,
            activeAgents = allAgents.count { it.isActive },
            totalTasks = allTasks.size,
            completedTasks = allTasks.count { it.status == TaskCardStatus.COMPLETED },
            failedTasks = allTasks.count { it.status == TaskCardStatus.FAILED },
            inProgressTasks = allTasks.count { it.status == TaskCardStatus.IN_PROGRESS },
            totalMessages = allMessages.size,
            averageResponseTimeMs = if (allAgents.isNotEmpty()) {
                allAgents.sumOf { it.averageResponseTimeMs } / allAgents.size
            } else 0L,
            overallProgress = if (allTasks.isNotEmpty()) {
                allTasks.sumOf { it.progress.toDouble() }.toFloat() / allTasks.size
            } else 0f,
            startTime = startTime,
            elapsedTime = System.currentTimeMillis() - startTime
        )
    }
}
