package com.apex.agent.core.multiagent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.collaboration.AgentCollaborationFramework
import com.apex.agent.data.agent.multi.MultiAgentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MultiAgentWorkspaceViewModel(
    private val context: Context
) : ViewModel() {

    private val collaborationFramework = AgentCollaborationFramework(context)
    private val multiAgentManager = MultiAgentManager(context)

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WorkspaceEvent>()
    val events: SharedFlow<WorkspaceEvent> = _events.asSharedFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                val agents = collaborationFramework.getAgents()
                val sessions = collaborationFramework.getSessions()
                val tasks = collaborationFramework.getTasks()
                
                _uiState.update { state ->
                    state.copy(
                        agents = agents.map { it.toAgentModel() },
                        sessions = sessions.map { it.toSessionModel() },
                        tasks = tasks.map { it.toTaskModel() },
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun selectSession(session: SessionModel) {
        _uiState.update { it.copy(currentSession = session) }
        if (session != null) {
            loadSessionData(session.id)
        }
    }

    private fun loadSessionData(sessionId: String) {
        viewModelScope.launch {
            try {
                val messages = collaborationFramework.getMessages(sessionId)
                _uiState.update { state ->
                    state.copy(messages = messages.map { it.toMessageModel() })
                }
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "加载会话数据失败"))
            }
        }
    }

    fun createAgent(name: String, role: String, capabilities: List<String>) {
        viewModelScope.launch {
            try {
                val agent = collaborationFramework.Agent(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    role = AgentCollaborationFramework.AgentRole.valueOf(role.uppercase()),
                    capabilities = capabilities,
                    specialties = capabilities,
                    isActive = true
                )
                
                val success = collaborationFramework.registerAgent(agent)
                if (success) {
                    _uiState.update { state ->
                        state.copy(agents = state.agents + agent.toAgentModel())
                    }
                    _events.emit(WorkspaceEvent.AgentCreated(agent.toAgentModel()))
                }
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "创建Agent失败"))
            }
        }
    }

    fun deleteAgent(agentId: String) {
        viewModelScope.launch {
            try {
                val updatedAgents = _uiState.value.agents.filter { it.id != agentId }
                _uiState.update { it.copy(agents = updatedAgents) }
                _events.emit(WorkspaceEvent.AgentDeleted(agentId))
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "删除Agent失败"))
            }
        }
    }

    fun updateAgentStatus(agentId: String, status: AgentStatus) {
        _uiState.update { state ->
            state.copy(
                agents = state.agents.map { agent ->
                    if (agent.id == agentId) agent.copy(status = status) else agent
                }
            )
        }
    }

    fun createTask(
        title: String,
        description: String,
        priority: Int,
        dependencies: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            try {
                val task = collaborationFramework.createTask(
                    title = title,
                    description = description,
                    priority = priority,
                    dependencies = dependencies
                )
                
                _uiState.update { state ->
                    state.copy(tasks = state.tasks + task.toTaskModel())
                }
                _events.emit(WorkspaceEvent.TaskCreated(task.toTaskModel()))
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "创建任务失败"))
            }
        }
    }

    fun assignTask(taskId: String, agentId: String) {
        viewModelScope.launch {
            try {
                val success = collaborationFramework.assignTask(taskId, agentId)
                if (success) {
                    val agent = _uiState.value.agents.find { it.id == agentId }
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.map { task ->
                                if (task.id == taskId) task.copy(assignedAgent = agent) else task
                            }
                        )
                    }
                    _events.emit(WorkspaceEvent.TaskAssigned(taskId, agentId))
                }
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "分配任务失败"))
            }
        }
    }

    fun updateTaskStatus(taskId: String, status: TaskStatus) {
        viewModelScope.launch {
            try {
                val frameworkStatus = when (status) {
                    TaskStatus.PENDING -> AgentCollaborationFramework.TaskStatus.PENDING
                    TaskStatus.IN_PROGRESS -> AgentCollaborationFramework.TaskStatus.IN_PROGRESS
                    TaskStatus.BLOCKED -> AgentCollaborationFramework.TaskStatus.BLOCKED
                    TaskStatus.COMPLETED -> AgentCollaborationFramework.TaskStatus.COMPLETED
                }
                
                val success = collaborationFramework.updateTaskStatus(taskId, frameworkStatus)
                if (success) {
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.map { task ->
                                if (task.id == taskId) task.copy(status = status) else task
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "更新任务状态失�?))
            }
        }
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            try {
                val currentSession = _uiState.value.currentSession ?: return@launch
                
                val message = collaborationFramework.sendMessage(
                    senderAgent = "USER",
                    recipientAgent = null,
                    content = content,
                    messageType = AgentCollaborationFramework.MessageType.REQUEST
                )
                
                _uiState.update { state ->
                    state.copy(messages = state.messages + message.toMessageModel())
                }
                _events.emit(WorkspaceEvent.MessageSent(message.toMessageModel()))
                
                processAgentResponse(content)
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "发送消息失�?))
            }
        }
    }

    private fun processAgentResponse(userMessage: String) {
        viewModelScope.launch {
            val activeAgents = _uiState.value.agents.filter { it.status == AgentStatus.ACTIVE }
            
            activeAgents.forEach { agent ->
                updateAgentStatus(agent.id, AgentStatus.BUSY)
                
                delay((500..1500).random().toLong())
                
                val response = generateAgentResponse(agent, userMessage)
                
                val message = collaborationFramework.sendMessage(
                    senderAgent = agent.id,
                    recipientAgent = "USER",
                    content = response,
                    messageType = AgentCollaborationFramework.MessageType.RESPONSE
                )
                
                _uiState.update { state ->
                    state.copy(messages = state.messages + message.toMessageModel())
                }
                _events.emit(WorkspaceEvent.MessageReceived(message.toMessageModel()))
                
                updateAgentStatus(agent.id, AgentStatus.ACTIVE)
            }
        }
    }

    private fun generateAgentResponse(agent: Agent, userMessage: String): String {
        return when (agent.role.uppercase()) {
            "COORDINATOR" -> "我已经收到您的请求，正在协调团队处理..."
            "ANALYST" -> "${userMessage}"
            "DEVELOPER" -> "我来帮您实现这个功能�?
            "DESIGNER" -> "基于您的需求，我有以下设计方案..."
            "TESTER" -> "我来测试一下这个功能的边界情况�?
            else -> "收到您的消息，我会尽力帮助您�?
        }
    }

    fun createSession(name: String, type: CollaborationType, goal: String) {
        viewModelScope.launch {
            try {
                val frameworkType = when (type) {
                    CollaborationType.PARALLEL -> AgentCollaborationFramework.CollaborationType.PARALLEL
                    CollaborationType.SEQUENTIAL -> AgentCollaborationFramework.CollaborationType.SEQUENTIAL
                    CollaborationType.HIERARCHICAL -> AgentCollaborationFramework.CollaborationType.HIERARCHICAL
                    CollaborationType.CONSENSUS -> AgentCollaborationFramework.CollaborationType.CONSENSUS
                    CollaborationType.MASTER_SLAVE -> AgentCollaborationFramework.CollaborationType.MASTER_SLAVE
                    CollaborationType.PEER_TO_PEER -> AgentCollaborationFramework.CollaborationType.PEER_TO_PEER
                }
                
                val agentIds = _uiState.value.agents.map { it.id }
                val session = collaborationFramework.createSession(name, frameworkType, goal, agentIds)
                
                _uiState.update { state ->
                    state.copy(sessions = state.sessions + session.toSessionModel())
                }
                _events.emit(WorkspaceEvent.SessionCreated(session.toSessionModel()))
            } catch (e: Exception) {
                _events.emit(WorkspaceEvent.Error(e.message ?: "创建会话失败"))
            }
        }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun toggleConfigPanel() {
        _uiState.update { it.copy(showConfigPanel = !it.showConfigPanel) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun delay(timeMillis: Long) {
        withContext(Dispatchers.Default) {
            kotlinx.coroutines.delay(timeMillis)
        }
    }
}

data class WorkspaceUiState(
    val session: CollaborationSession? = null,
    val sessions: List<SessionModel> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val messages: List<MessageModel> = emptyList(),
    val connections: List<AgentConnection> = emptyList(),
    val viewMode: ViewMode = ViewMode.CHAT_FLOW,
    val currentSession: SessionModel? = null,
    val inputText: String = "",
    val showConfigPanel: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed class WorkspaceEvent {
    data class AgentCreated(val agent: Agent) : WorkspaceEvent()
    data class AgentDeleted(val agentId: String) : WorkspaceEvent()
    data class TaskCreated(val task: Task) : WorkspaceEvent()
    data class TaskAssigned(val taskId: String, val agentId: String) : WorkspaceEvent()
    data class MessageSent(val message: MessageModel) : WorkspaceEvent()
    data class MessageReceived(val message: MessageModel) : WorkspaceEvent()
    data class SessionCreated(val session: SessionModel) : WorkspaceEvent()
    data class Error(val message: String) : WorkspaceEvent()
}

enum class ViewMode {
    CHAT_FLOW, TOPOLOGY, TASK_BOARD
}

enum class CollaborationType {
    PARALLEL, SEQUENTIAL, HIERARCHICAL, CONSENSUS, MASTER_SLAVE, PEER_TO_PEER
}

data class SessionModel(
    val id: String,
    val name: String,
    val type: CollaborationType,
    val agentCount: Int,
    val status: String,
    val goal: String,
    val createdAt: Long
)

data class MessageModel(
    val id: String,
    val senderAgent: String,
    val recipientAgent: String?,
    val timestamp: Long,
    val content: String,
    val type: String
)

data class AgentConnection(
    val fromAgentId: String,
    val toAgentId: String,
    val type: ConnectionType
)

enum class ConnectionType {
    COMMAND, COLLABORATION, FEEDBACK, DEPENDENCY
}

fun AgentCollaborationFramework.Agent.toAgentModel() = Agent(
    id = id,
    name = name,
    role = role.name,
    specialties = specialties,
    status = if (isActive) AgentStatus.ACTIVE else AgentStatus.OFFLINE,
    taskLoad = taskLoad
)

fun AgentCollaborationFramework.Task.toTaskModel() = Task(
    id = id,
    title = title,
    description = description,
    status = when (status) {
        AgentCollaborationFramework.TaskStatus.PENDING -> TaskStatus.PENDING
        AgentCollaborationFramework.TaskStatus.ASSIGNED,
        AgentCollaborationFramework.TaskStatus.IN_PROGRESS -> TaskStatus.IN_PROGRESS
        AgentCollaborationFramework.TaskStatus.BLOCKED -> TaskStatus.BLOCKED
        AgentCollaborationFramework.TaskStatus.COMPLETED -> TaskStatus.COMPLETED
        else -> TaskStatus.PENDING
    },
    priority = priority,
    assignedAgent = null,
    createdAt = createdAt,
    dueDate = null
)

fun AgentCollaborationFramework.Message.toMessageModel() = MessageModel(
    id = id,
    senderAgent = senderAgent,
    recipientAgent = recipientAgent,
    timestamp = timestamp,
    content = content,
    type = messageType.name
)

fun AgentCollaborationFramework.CollaborationSession.toSessionModel() = SessionModel(
    id = id,
    name = name,
    type = when (type) {
        AgentCollaborationFramework.CollaborationType.PARALLEL -> CollaborationType.PARALLEL
        AgentCollaborationFramework.CollaborationType.SEQUENTIAL -> CollaborationType.SEQUENTIAL
        AgentCollaborationFramework.CollaborationType.HIERARCHICAL -> CollaborationType.HIERARCHICAL
        AgentCollaborationFramework.CollaborationType.CONSENSUS -> CollaborationType.CONSENSUS
        AgentCollaborationFramework.CollaborationType.MASTER_SLAVE -> CollaborationType.MASTER_SLAVE
        AgentCollaborationFramework.CollaborationType.PEER_TO_PEER -> CollaborationType.PEER_TO_PEER
    },
    agentCount = agents.size,
    status = status.name,
    goal = goal,
    createdAt = startTime
)

class MultiAgentWorkspaceViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MultiAgentWorkspaceViewModel::class.java)) {
            return MultiAgentWorkspaceViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
