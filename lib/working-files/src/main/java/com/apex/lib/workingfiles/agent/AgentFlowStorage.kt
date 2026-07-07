package com.apex.lib.workingfiles.agent

import com.apex.sdk.common.ApexLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 执行流程存储 — 基于 JSON 文件持久化。
 *
 * **存储结构**：
 *   ```
 *   <storageDir>/
 *   ├── sessions/
 *   │   ├── session-xxx.json          # 会话元数据 + 所有步骤
 *   │   └── ...
 *   └── active.json                    # 当前活跃会话 ID 列表
 *   ```
 *
 * **并发安全**：用 synchronized 保护会话状态。
 */
class AgentFlowStorage(private val storageDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val sessionsDir = File(storageDir, "sessions").apply { mkdirs() }
    private val activeFile = File(storageDir, "active.json")

    private val sessions = ConcurrentHashMap<String, AgentFlow>()
    private val stepCounter = ConcurrentHashMap<String, Int>()

    init {
        loadActiveSessions()
    }

    /**
     * 创建新会话。
     */
    fun createSession(
        agentId: String,
        agentName: String,
        taskDescription: String,
        mode: AgentMode
    ): AgentSession {
        val sessionId = generateSessionId()
        val session = AgentSession(
            id = sessionId,
            agentId = agentId,
            agentName = agentName,
            startTime = System.currentTimeMillis(),
            taskDescription = taskDescription,
            mode = mode,
            status = AgentSessionStatus.RUNNING
        )
        val flow = AgentFlow(session = session, steps = emptyList())
        sessions[sessionId] = flow
        stepCounter[sessionId] = 0
        persistSession(flow)
        updateActiveSessions()
        return session
    }

    /**
     * 添加一个步骤到指定会话。
     */
    fun addStep(
        sessionId: String,
        agentId: String,
        agentName: String,
        type: AgentStepType,
        title: String,
        description: String = "",
        thought: String? = null,
        action: String? = null,
        result: String? = null,
        isSuccess: Boolean = true,
        errorMessage: String? = null,
        affectedFiles: List<String> = emptyList(),
        snapshotIds: List<String> = emptyList(),
        durationMs: Long = 0,
        metadata: Map<String, String> = emptyMap()
    ): AgentStep? {
        val flow = sessions[sessionId] ?: return null
        val order = stepCounter[sessionId] ?: 0
        val step = AgentStep(
            id = generateStepId(sessionId, order),
            sessionId = sessionId,
            agentId = agentId,
            agentName = agentName,
            type = type,
            order = order,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            title = title,
            description = description,
            thought = thought,
            action = action,
            result = result,
            isSuccess = isSuccess,
            errorMessage = errorMessage,
            affectedFiles = affectedFiles,
            snapshotIds = snapshotIds,
            metadata = metadata
        )
        stepCounter[sessionId] = order + 1
        val updatedFlow = flow.copy(
            session = flow.session.copy(stepCount = order + 1, fileCount = flow.steps.flatMap { it.affectedFiles }.toSet().size + affectedFiles.toSet().size),
            steps = flow.steps + step
        )
        sessions[sessionId] = updatedFlow
        persistSession(updatedFlow)
        return step
    }

    /**
     * 结束会话。
     */
    fun finishSession(sessionId: String, finalResult: String? = null, status: AgentSessionStatus = AgentSessionStatus.COMPLETED): Boolean {
        val flow = sessions[sessionId] ?: return false
        val updated = flow.copy(
            session = flow.session.copy(
                endTime = System.currentTimeMillis(),
                status = status,
                finalResult = finalResult
            )
        )
        sessions[sessionId] = updated
        persistSession(updated)
        updateActiveSessions()
        return true
    }

    /**
     * 获取会话的完整流程。
     */
    fun getFlow(sessionId: String): AgentFlow? {
        sessions[sessionId]?.let { return it }
        // 从磁盘加载
        return loadSession(sessionId)?.also { sessions[sessionId] = it }
    }

    /**
     * 获取会话元数据（不含步骤详情）。
     */
    fun getSession(sessionId: String): AgentSession? {
        return getFlow(sessionId)?.session
    }

    /**
     * 列出所有会话（按开始时间降序）。
     */
    fun listSessions(): List<AgentSession> {
        val allSessions = mutableListOf<AgentSession>()
        // 内存中的
        allSessions.addAll(sessions.values.map { it.session })
        // 磁盘上的（不重复）
        sessionsDir.listFiles()?.forEach { file ->
            val sessionId = file.nameWithoutExtension
            if (!sessions.containsKey(sessionId)) {
                loadSession(sessionId)?.let { allSessions.add(it.session) }
            }
        }
        return allSessions.sortedByDescending { it.startTime }
    }

    /**
     * 列出活跃会话（状态为 RUNNING / PAUSED）。
     */
    fun listActiveSessions(): List<AgentSession> {
        return listSessions().filter { it.status == AgentSessionStatus.RUNNING || it.status == AgentSessionStatus.PAUSED }
    }

    /**
     * 获取某会话的所有步骤（按 order 升序）。
     */
    fun listSteps(sessionId: String): List<AgentStep> {
        return getFlow(sessionId)?.steps?.sortedBy { it.order } ?: emptyList()
    }

    /**
     * 获取某会话中影响指定文件的步骤。
     */
    fun listStepsForFile(sessionId: String, filePath: String): List<AgentStep> {
        return listSteps(sessionId).filter { filePath in it.affectedFiles }
    }

    /**
     * 删除会话。
     */
    fun deleteSession(sessionId: String): Boolean {
        sessions.remove(sessionId)
        stepCounter.remove(sessionId)
        File(sessionsDir, "$sessionId.json").delete()
        updateActiveSessions()
        return true
    }

    /**
     * 清空所有会话。
     */
    fun clear() {
        sessions.clear()
        stepCounter.clear()
        sessionsDir.listFiles()?.forEach { it.delete() }
        activeFile.delete()
    }

    private fun persistSession(flow: AgentFlow) {
        try {
            val file = File(sessionsDir, "${flow.session.id}.json")
            val tmp = File(sessionsDir, "${flow.session.id}.json.tmp")
            tmp.writeText(json.encodeToString(flow))
            tmp.renameTo(file)
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[AgentFlowStorage] persistSession failed: ${t.message}")
        }
    }

    private fun loadSession(sessionId: String): AgentFlow? {
        return try {
            val file = File(sessionsDir, "$sessionId.json")
            if (!file.exists()) return null
            json.decodeFromString(AgentFlow.serializer(), file.readText())
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[AgentFlowStorage] loadSession failed: $sessionId, ${t.message}")
            null
        }
    }

    private fun loadActiveSessions() {
        try {
            if (!activeFile.exists()) return
            val ids = json.decodeFromString<List<String>>(activeFile.readText())
            ids.forEach { id ->
                loadSession(id)?.let {
                    sessions[id] = it
                    stepCounter[id] = it.steps.size
                }
            }
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[AgentFlowStorage] loadActiveSessions failed: ${t.message}")
        }
    }

    private fun updateActiveSessions() {
        try {
            val activeIds = sessions.values
                .filter { it.session.status == AgentSessionStatus.RUNNING || it.session.status == AgentSessionStatus.PAUSED }
                .map { it.session.id }
            activeFile.writeText(json.encodeToString(activeIds))
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[AgentFlowStorage] updateActiveSessions failed: ${t.message}")
        }
    }

    private fun generateSessionId(): String {
        val ts = System.currentTimeMillis().toString(36)
        val random = (0 until 6).map { ('a'..'z').random() }.joinToString("")
        return "session-$ts-$random"
    }

    private fun generateStepId(sessionId: String, order: Int): String {
        return "${sessionId}-step-$order"
    }
}
