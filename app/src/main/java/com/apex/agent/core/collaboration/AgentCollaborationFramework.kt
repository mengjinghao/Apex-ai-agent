package com.apex.agent.core.collaboration

import android.content.Context
import com.apex.agent.api.chat.EnhancedAIService
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind

class AgentCollaborationFramework(
    private val context: Context,
    private val aiService: AIService
) {

    private val TAG = "AgentCollaboration"

    enum class AgentRole {
        COORDINATOR,
        SPECIALIST,
        ANALYST,
        DESIGNER,
        DEVELOPER,
        TESTER,
        RESEARCHER,
        CRITIC,
        REVIEWER,
        SUPPORT
    }

    enum class TaskStatus {
        PENDING,
        ASSIGNED,
        IN_PROGRESS,
        COMPLETED,
        BLOCKED,
        FAILED,
        CANCELLED
    }

    enum class CollaborationType {
        PARALLEL,
        SEQUENTIAL,
        HIERARCHICAL,
        CONSENSUS,
        MASTER_SLAVE,
        PEER_TO_PEER
    }

    data class Agent(
        val id: String,
        val name: String,
        val role: AgentRole,
        val capabilities: List<String>,
        val specialties: List<String>,
        val isActive: Boolean = true,
        val currentTask: String? = null,
        val taskLoad: Float = 0f
    )

    data class Task(
        val id: String,
        val title: String,
        val description: String,
        val status: TaskStatus,
        val assignedAgent: String?,
        val priority: Int,
        val dependencies: List<String>,
        val subtasks: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val estimatedHours: Float,
        val actualHours: Float = 0f
    )

    data class Message(
        val id: String,
        val senderAgent: String,
        val recipientAgent: String?,
        val timestamp: Long,
        val content: String,
        val messageType: MessageType,
        val attachments: List<String>,
        val isRead: Boolean = false
    )

    enum class MessageType {
        REQUEST,
        RESPONSE,
        PROPOSAL,
        QUESTION,
        ANSWER,
        UPDATE,
        WARNING,
        ERROR,
        FEEDBACK
    }

    data class CollaborationSession(
        val id: String,
        val name: String,
        val type: CollaborationType,
        val agents: List<String>,
        val tasks: List<String>,
        val messages: List<String>,
        val startTime: Long,
        val endTime: Long?,
        val status: SessionStatus,
        val goal: String
    )

    enum class SessionStatus {
        PLANNING,
        ACTIVE,
        PAUSED,
        COMPLETED,
        ABORTED
    }

    data class Negotiation(
        val id: String,
        val topic: String,
        val proposerAgent: String,
        val responses: Map<String, String>,
        val status: NegotiationStatus,
        val deadline: Long,
        val decision: String?
    )

    enum class NegotiationStatus {
        ONGOING,
        ACCEPTED,
        REJECTED,
        COMPROMISE,
        TIMEOUT
    }

    private val agentsDir: File
        get() = File(context.filesDir, "collab_agents").also {
            if (!it.exists()) it.mkdirs()
        }

    private val tasksDir: File
        get() = File(context.filesDir, "collab_tasks").also {
            if (!it.exists()) it.mkdirs()
        }

    private val messagesDir: File
        get() = File(context.filesDir, "collab_messages").also {
            if (!it.exists()) it.mkdirs()
        }

    private val sessionsDir: File
        get() = File(context.filesDir, "collab_sessions").also {
            if (!it.exists()) it.mkdirs()
        }

    private val knowledgeDir: File
        get() = File(context.filesDir, "collab_knowledge").also {
            if (!it.exists()) it.mkdirs()
        }

    private val activeAgents = mutableMapOf<String, Agent>()
    private val activeTasks = mutableMapOf<String, Task>()
    private val activeSessions = mutableMapOf<String, CollaborationSession>()
    private val messageQueue = mutableListOf<Message>()

    suspend fun registerAgent(agent: Agent): Boolean = withContext(Dispatchers.IO) {
        try {
            val agentFile = File(agentsDir, "${agent.id}.json")
            val json = JSONObject().apply {
                put("id", agent.id)
                put("name", agent.name)
                put("role", agent.role.name)
                put("capabilities", JSONArray(agent.capabilities))
                put("specialties", JSONArray(agent.specialties))
                put("isActive", agent.isActive)
                put("currentTask", agent.currentTask ?: JSONObject.NULL)
                put("taskLoad", agent.taskLoad.toDouble())
            }

            agentFile.writeText(json.toString(2))
            activeAgents[agent.id] = agent

            AppLogger.d(TAG, "代理已注�? ${agent.name}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册代理失败", e)
            false
        }
    }

    suspend fun getAgents(): List<Agent> = withContext(Dispatchers.IO) {
        try {
            val loaded = mutableListOf<Agent>()

            agentsDir.listFiles { _, name -> name.endsWith(".json") }
                ?.mapNotNull { file ->
                    try {
                        val json = JSONObject(file.readText())
                        val agent = Agent(
                            id = json.getString("id"),
                            name = json.getString("name"),
                            role = AgentRole.valueOf(json.getString("role")),
                            capabilities = (0 until json.getJSONArray("capabilities").length())
                                .map { json.getJSONArray("capabilities").getString(it) },
                            specialties = (0 until json.getJSONArray("specialties").length())
                                .map { json.getJSONArray("specialties").getString(it) },
                            isActive = json.getBoolean("isActive"),
                            currentTask = if (json.isNull("currentTask")) null else json.getString("currentTask"),
                            taskLoad = json.getDouble("taskLoad").toFloat()
                        )
                        loaded.add(agent)
                        agent
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "解析代理配置失败: ${file.name}", e)
                        null
                    }
                }

            loaded
        } catch (e: Exception) {
            AppLogger.e(TAG, "加载代理列表失败", e)
            emptyList()
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        priority: Int = 3,
        estimatedHours: Float = 1f,
        dependencies: List<String> = emptyList()
    ): Task = withContext(Dispatchers.IO) {
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            status = TaskStatus.PENDING,
            assignedAgent = null,
            priority = priority,
            dependencies = dependencies,
            subtasks = emptyList(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            estimatedHours = estimatedHours
        )

        saveTask(task)
        activeTasks[task.id] = task
        task
    }

    private suspend fun saveTask(task: Task) = withContext(Dispatchers.IO) {
        val taskFile = File(tasksDir, "${task.id}.json")
        val json = JSONObject().apply {
            put("id", task.id)
            put("title", task.title)
            put("description", task.description)
            put("status", task.status.name)
            put("assignedAgent", task.assignedAgent ?: JSONObject.NULL)
            put("priority", task.priority)
            put("dependencies", JSONArray(task.dependencies))
            put("subtasks", JSONArray(task.subtasks))
            put("createdAt", task.createdAt)
            put("updatedAt", task.updatedAt)
            put("estimatedHours", task.estimatedHours.toDouble())
            put("actualHours", task.actualHours.toDouble())
        }

        taskFile.writeText(json.toString(2))
    }

    suspend fun assignTask(taskId: String, agentId: String): Boolean = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: return@withContext false

        val updatedTask = task.copy(
            assignedAgent = agentId,
            status = TaskStatus.ASSIGNED,
            updatedAt = System.currentTimeMillis()
        )

        saveTask(updatedTask)
        activeTasks[taskId] = updatedTask

        sendMessage(
            senderAgent = "SYSTEM",
            recipientAgent = agentId,
            content = "你已被分配新任务: ${task.title}",
            messageType = MessageType.UPDATE
        )

        true
    }

    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): Boolean = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: return@withContext false

        val updatedTask = task.copy(
            status = status,
            updatedAt = System.currentTimeMillis()
        )

        saveTask(updatedTask)
        activeTasks[taskId] = updatedTask

        true
    }

    suspend fun getTasks(filter: TaskStatus? = null): List<Task> = withContext(Dispatchers.IO) {
        val allTasks = mutableListOf<Task>()

        tasksDir.listFiles { _, name -> name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val task = Task(
                        id = json.getString("id"),
                        title = json.getString("title"),
                        description = json.getString("description"),
                        status = TaskStatus.valueOf(json.getString("status")),
                        assignedAgent = if (json.isNull("assignedAgent")) null else json.getString("assignedAgent"),
                        priority = json.getInt("priority"),
                        dependencies = (0 until json.getJSONArray("dependencies").length())
                            .map { json.getJSONArray("dependencies").getString(it) },
                        subtasks = (0 until json.getJSONArray("subtasks").length())
                            .map { json.getJSONArray("subtasks").getString(it) },
                        createdAt = json.getLong("createdAt"),
                        updatedAt = json.getLong("updatedAt"),
                        estimatedHours = json.getDouble("estimatedHours").toFloat(),
                        actualHours = json.getDouble("actualHours").toFloat()
                    )

                    allTasks.add(task)
                    activeTasks[task.id] = task
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析任务配置失败: ${file.name}", e)
                }
            }

        if (filter != null) {
            allTasks.filter { it.status == filter }
        } else {
            allTasks
        }
    }

    suspend fun sendMessage(
        senderAgent: String,
        recipientAgent: String?,
        content: String,
        messageType: MessageType = MessageType.UPDATE,
        attachments: List<String> = emptyList()
    ): Message = withContext(Dispatchers.IO) {
        val message = Message(
            id = UUID.randomUUID().toString(),
            senderAgent = senderAgent,
            recipientAgent = recipientAgent,
            timestamp = System.currentTimeMillis(),
            content = content,
            messageType = messageType,
            attachments = attachments
        )

        messageQueue.add(message)
        saveMessage(message)
        message
    }

    private suspend fun saveMessage(message: Message) = withContext(Dispatchers.IO) {
        val messageFile = File(messagesDir, "${message.id}.json")
        val json = JSONObject().apply {
            put("id", message.id)
            put("senderAgent", message.senderAgent)
            put("recipientAgent", message.recipientAgent ?: JSONObject.NULL)
            put("timestamp", message.timestamp)
            put("content", message.content)
            put("messageType", message.messageType.name)
            put("attachments", JSONArray(message.attachments))
            put("isRead", message.isRead)
        }

        messageFile.writeText(json.toString(2))
    }

    suspend fun getMessages(agentId: String?, limit: Int = 50): List<Message> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<Message>()

        messagesDir.listFiles { _, name -> name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val message = Message(
                        id = json.getString("id"),
                        senderAgent = json.getString("senderAgent"),
                        recipientAgent = if (json.isNull("recipientAgent")) null else json.getString("recipientAgent"),
                        timestamp = json.getLong("timestamp"),
                        content = json.getString("content"),
                        messageType = MessageType.valueOf(json.getString("messageType")),
                        attachments = (0 until json.getJSONArray("attachments").length())
                            .map { json.getJSONArray("attachments").getString(it) },
                        isRead = json.getBoolean("isRead")
                    )

                    if (agentId == null || message.senderAgent == agentId || message.recipientAgent == agentId) {
                        messages.add(message)
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析消息失败: ${file.name}", e)
                }
            }

        messages.sortedByDescending { it.timestamp }
    }

    suspend fun createSession(
        name: String,
        type: CollaborationType,
        goal: String,
        agents: List<String>
    ): CollaborationSession = withContext(Dispatchers.IO) {
        val session = CollaborationSession(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            agents = agents,
            tasks = emptyList(),
            messages = emptyList(),
            startTime = System.currentTimeMillis(),
            endTime = null,
            status = SessionStatus.PLANNING,
            goal = goal
        )

        saveSession(session)
        activeSessions[session.id] = session
        session
    }

    private suspend fun saveSession(session: CollaborationSession) = withContext(Dispatchers.IO) {
        val sessionFile = File(sessionsDir, "${session.id}.json")
        val json = JSONObject().apply {
            put("id", session.id)
            put("name", session.name)
            put("type", session.type.name)
            put("agents", JSONArray(session.agents))
            put("tasks", JSONArray(session.tasks))
            put("messages", JSONArray(session.messages))
            put("startTime", session.startTime)
            put("endTime", session.endTime ?: JSONObject.NULL)
            put("status", session.status.name)
            put("goal", session.goal)
        }

        sessionFile.writeText(json.toString(2))
    }

    suspend fun getSessions(status: SessionStatus? = null): List<CollaborationSession> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<CollaborationSession>()

        sessionsDir.listFiles { _, name -> name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val session = CollaborationSession(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        type = CollaborationType.valueOf(json.getString("type")),
                        agents = (0 until json.getJSONArray("agents").length())
                            .map { json.getJSONArray("agents").getString(it) },
                        tasks = (0 until json.getJSONArray("tasks").length())
                            .map { json.getJSONArray("tasks").getString(it) },
                        messages = (0 until json.getJSONArray("messages").length())
                            .map { json.getJSONArray("messages").getString(it) },
                        startTime = json.getLong("startTime"),
                        endTime = if (json.isNull("endTime")) null else json.getLong("endTime"),
                        status = SessionStatus.valueOf(json.getString("status")),
                        goal = json.getString("goal")
                    )

                    sessions.add(session)
                    activeSessions[session.id] = session
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析会话配置失败: ${file.name}", e)
                }
            }

        if (status != null) {
            sessions.filter { it.status == status }
        } else {
            sessions
        }
    }

    suspend fun startNegotiation(
        topic: String,
        proposerAgent: String,
        deadlineHours: Int = 24
    ): Negotiation = withContext(Dispatchers.IO) {
        val negotiation = Negotiation(
            id = UUID.randomUUID().toString(),
            topic = topic,
            proposerAgent = proposerAgent,
            responses = emptyMap(),
            status = NegotiationStatus.ONGOING,
            deadline = System.currentTimeMillis() + (deadlineHours * 60 * 60 * 1000L),
            decision = null
        )

        negotiation
    }

    suspend fun findBestAgentForTask(capabilities: List<String>): Agent? = withContext(Dispatchers.IO) {
        val agents = getAgents()

        agents.filter { agent ->
            agent.isActive && capabilities.all { cap ->
                agent.capabilities.any { it.equals(cap, ignoreCase = true) }
            }
        }.minByOrNull { it.taskLoad }
    }

    suspend fun generateSessionReport(sessionId: String): String = withContext(Dispatchers.IO) {
        val session = activeSessions[sessionId] ?: return@withContext "会话不存�?

        buildString {
            appendLine("=== 协作会话报告 ===")
            appendLine()
            appendLine("【会话信息�?)
            appendLine("名称: ${session.name}")
            appendLine("类型: ${session.type.name}")
            appendLine("状�? ${session.status.name}")
            appendLine("目标: ${session.goal}")
            appendLine("代理数量: ${session.agents.size}")
            appendLine("任务数量: ${session.tasks.size}")
            appendLine()

            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            appendLine("开始时�? ${timeFormat.format(Date(session.startTime))}")
            session.endTime?.let {
                appendLine("结束时间: ${timeFormat.format(Date(it))}")
            }
        }
    }

    private suspend fun callAI(systemPrompt: String, userPrompt: String): String {
        val history = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, systemPrompt),
            PromptTurn(PromptTurnKind.USER, userPrompt)
        )
        return try {
            val stream = aiService.sendMessage(context, history, stream = false)
            val sb = StringBuilder()
            stream.collect { chunk -> sb.append(chunk) }
            sb.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "AI call failed", e)
            ""
        }
    }

    /**
     * 协作编排器：根据协作类型执行任务
     */
    suspend fun executeSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val session = activeSessions[sessionId] ?: return@withContext false
        AppLogger.d(TAG, "正在执行协作会话: ${session.name}, 类型: ${session.type}")

        val updatedSession = session.copy(status = SessionStatus.ACTIVE)
        saveSession(updatedSession)
        activeSessions[session.id] = updatedSession

        val result = when (session.type) {
            CollaborationType.SEQUENTIAL -> executeSequential(session)
            CollaborationType.PARALLEL -> executeParallel(session)
            else -> {
                AppLogger.w(TAG, "尚未实现协作类型: ${session.type}")
                true
            }
        }

        if (result) {
            val completedSession = updatedSession.copy(
                status = SessionStatus.COMPLETED,
                endTime = System.currentTimeMillis()
            )
            saveSession(completedSession)
            activeSessions[session.id] = completedSession
        }

        result
    }

    private suspend fun executeSequential(session: CollaborationSession): Boolean {
        session.tasks.forEach { taskId ->
            val task = activeTasks[taskId] ?: return@forEach
            AppLogger.d(TAG, "顺序执行任务: ${task.title}")
            updateTaskStatus(taskId, TaskStatus.IN_PROGRESS)
            // 模拟任务执行
            delay(1000)
            updateTaskStatus(taskId, TaskStatus.COMPLETED)
        }
        return true
    }

    private suspend fun executeParallel(session: CollaborationSession): Boolean {
        val jobs = session.tasks.map { taskId ->
            val task = activeTasks[taskId] ?: return@map null
            async {
                AppLogger.d(TAG, "并行执行任务: ${task.title}")
                updateTaskStatus(taskId, TaskStatus.IN_PROGRESS)
                // 模拟任务执行
                delay(1000)
                updateTaskStatus(taskId, TaskStatus.COMPLETED)
            }
        }.filterNotNull()
        jobs.awaitAll()
        return true
    }

    /**
     * 共享知识库：存储和检索代理协作中的知�?
     */
    suspend fun storeKnowledge(
        sessionId: String,
        key: String,
        value: String,
        agentId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sessionKnowledgeDir = File(knowledgeDir, sessionId).also {
                if (!it.exists()) it.mkdirs()
            }
            val knowledgeFile = File(sessionKnowledgeDir, "${UUID.randomUUID()}.json")
            val json = JSONObject().apply {
                put("key", key)
                put("value", value)
                put("agentId", agentId)
                put("timestamp", System.currentTimeMillis())
            }
            knowledgeFile.writeText(json.toString(2))
            AppLogger.d(TAG, "知识已存�? ${key} (来自 ${agentId})")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "存储知识失败", e)
            false
        }
    }

    suspend fun queryKnowledge(sessionId: String, query: String): List<String> = withContext(Dispatchers.IO) {
        val sessionKnowledgeDir = File(knowledgeDir, sessionId)
        if (!sessionKnowledgeDir.exists()) return@withContext emptyList()

        val results = mutableListOf<String>()
        sessionKnowledgeDir.listFiles { _, name -> name.endsWith(".json") }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val key = json.getString("key")
                val value = json.getString("value")
                if (key.contains(query, ignoreCase = true) || value.contains(query, ignoreCase = true)) {
                    results.add("${key}: ${value}")
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        results
    }

    /**
     * 冲突解决器：处理代理之间的意见冲�?
     */
    suspend fun resolveConflict(
        topic: String,
        agentOpinions: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "正在解决冲突: ${topic}")

        val systemPrompt = "你是一个中立且专业的仲裁专家。你的任务是分析多个 AI 代理之间的观点冲突，并给出一个公平、合理的折中方案或最终决定�?
        val userPrompt = buildString {
            appendLine("冲突主题：的${topic}")
            appendLine("各方观点�?)
            agentOpinions.forEach { (agentId, opinion) ->
                appendLine("- [${agentId}]: ${opinion}")
            }
            appendLine("\n请分析这些观点并给出建议的解决方案�?)
        }

        val resolution = callAI(systemPrompt, userPrompt)
        AppLogger.d(TAG, "冲突已解�? ${resolution}")
        resolution
    }

    /**
     * 任务分解器：将复杂任务分解为子任�?
     */
    suspend fun decomposeTask(taskId: String): List<Task> = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: return@withContext emptyList()

        AppLogger.d(TAG, "正在分解任务: ${task.title}")

        val systemPrompt = "你是一个专业的项目经理和任务分解专家。你的任务是将一个复杂任务分解为多个具体的、可执行的子任务�?
        val userPrompt = """
            请将以下任务分解�?3-5 个具体的子任务�?
            任务标题�?{task.title}
            任务描述�?{task.description}

            请严格以 JSON 数组格式返回，每个对象包�?"title" (String) �?"description" (String)�?
            仅返�?JSON，不要有任何其他解释文字�?
        """.trimIndent()

        val response = callAI(systemPrompt, userPrompt)
        val subtasks = mutableListOf<Task>()

        try {
            val jsonArray = if (response.contains("[")) {
                JSONArray(response.substring(response.indexOf("["), response.lastIndexOf("]") + 1))
            } else {
                JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val subtask = createTask(
                    title = obj.getString("title"),
                    description = obj.getString("description"),
                    priority = task.priority,
                    dependencies = emptyList()
                )
                subtasks.add(subtask)
            }

            // 更新原任务，关联子任�?
            val updatedTask = task.copy(
                subtasks = subtasks.map { it.id },
                updatedAt = System.currentTimeMillis()
            )
            saveTask(updatedTask)
            activeTasks[task.id] = updatedTask

            AppLogger.d(TAG, "任务已成功分解为 ${subtasks.size} 个子任务")
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析任务分解结果失败: ${response}", e)
        }

        subtasks
    }

    suspend fun cleanupOldData(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        messagesDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
                AppLogger.d(TAG, "清理旧消�? ${file.name}")
            }
        }

        tasksDir.listFiles()?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val updatedAt = json.getLong("updatedAt")

                if (updatedAt < cutoffTime) {
                    file.delete()
                    AppLogger.d(TAG, "清理旧任�? ${file.name}")
                }
            } catch (e: Exception) {
                file.delete()
            }
        }
    }

    // ===================== AI 驱动增强方法 =====================

    private suspend fun callForCollaboration(prompt: String, system: String = "你是一名专业的多代理协作协调者�?): String {
        return try {
            val ai = EnhancedAIService.getInstance(context)
            val turns = listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = system),
                PromptTurn(kind = PromptTurnKind.USER, content = prompt)
            )
            val result = StringBuilder()
            ai.sendMessage(
                message = turns[1].content,
                functionType = com.apex.data.model.FunctionType.CHAT,
                stream = false,
                maxTokens = 1000,
                tokenUsageThreshold = 0.9f
            ).collect { result.append(it) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Collaboration AI call failed: ${e.message}")
            ""
        }
    }

    /**
     * AI 驱动的任务分解：将复杂任务拆分为 3-5 个可执行子任�?(JSON 解析�?
     */
    suspend fun decomposeTask(taskId: String): List<String> = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId]
            ?: return@withContext emptyList()

        val prompt = """
            请将以下复杂任务分解�?3-5 个可直接执行的子任务，每个子任务一行即可：
            标题: ${task.title}
            描述: ${task.description}

            输出格式:
            {
              "subtasks": [
                "子任�?",
                "子任�?",
                "子任�?"
              ]
            }
        """.trimIndent()

        val response = callForCollaboration(prompt)
        parseJsonStringList(response, "subtasks").ifEmpty {
            listOf(
                "分析任务 ${task.title} 的核心需�?,
                "确定执行 ${task.title} 所需资源和约�?,
                "制定执行计划并分配代理角�?,
                "执行并验�?${task.title} 的结�?
            )
        }
    }

    /**
     * AI 驱动的冲突解决：多代理观点冲突时调用 AI 给出折中方案
     */
    suspend fun resolveConflict(topic: String, agentOpinions: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val prompt = buildString {
                append("关于 '${topic}'，以下是不同代理的观点冲突，请给出合理的折中方案和详细步骤：\n")
                agentOpinions.forEach { (agent, opinion) ->
                    append("- 代理[${agent}]: ${opinion}\n")
                }
                append("请输出：\n1) 共识要点\n2) 折中方案\n3) 执行步骤（可选）")
            }

            callForCollaboration(prompt).ifBlank {
                "冲突暂无法自动解决，建议由协调者代理主持会议或直接由用户决策�?
            }
        }

    /**
     * 会话级共享知识库：写�?JSON 文件
     */
    suspend fun storeKnowledge(
        sessionId: String,
        key: String,
        value: String,
        agentId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sessionFile = File(context.filesDir, "knowledge_${sessionId}.json")
            val json = if (sessionFile.exists()) {
                JSONObject(sessionFile.readText())
            } else {
                JSONObject().put("sessionId", sessionId).put("entries", JSONArray())
            }
            val entry = JSONObject().apply {
                put("key", key)
                put("value", value)
                put("agentId", agentId)
                put("timestamp", System.currentTimeMillis())
            }
            json.getJSONArray("entries").put(entry)
            sessionFile.writeText(json.toString())
            AppLogger.d(TAG, "知识库写�?[${sessionId}/${key}]")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "storeKnowledge failed: ${e.message}")
            false
        }
    }

    /**
     * 关键词搜索知识库
     */
    suspend fun queryKnowledge(sessionId: String, query: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val sessionFile = File(context.filesDir, "knowledge_${sessionId}.json")
                if (!sessionFile.exists()) return@withContext emptyList()

                val json = JSONObject(sessionFile.readText())
                val entries = json.getJSONArray("entries")
                val result = mutableListOf<Pair<String, String>>()
                val keywords = query.split(Regex("[\\s,，]+")).filter { it.isNotBlank() }

                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    val key = e.optString("key", "")
                    val value = e.optString("value", "")
                    if (keywords.any { kw ->
                            key.contains(kw, ignoreCase = true) || value.contains(kw, ignoreCase = true)
                    }) {
                        result.add(key to value)
                    }
                }
                result
            } catch (e: Exception) {
                AppLogger.w(TAG, "queryKnowledge failed: ${e.message}")
                emptyList()
            }
        }

    private fun parseJsonStringList(response: String, arrayKey: String): List<String> {
        return try {
            val start = response.indexOf("{")
            val end = response.lastIndexOf("}")
            if (start < 0 || end <= start) return emptyList()
            val json = JSONObject(response.substring(start, end + 1))
            val array = json.optJSONArray(arrayKey) ?: return emptyList()
            List(array.length()) { i -> array.optString(i, "").trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}