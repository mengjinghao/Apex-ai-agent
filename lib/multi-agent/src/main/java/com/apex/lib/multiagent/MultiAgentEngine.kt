package com.apex.lib.multiagent

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 多 Agent 协作引擎（增强版）。
 */
class MultiAgentEngine {

    private val _events = MutableSharedFlow<MultiAgentEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MultiAgentEvent> = _events.asSharedFlow()

    val blackboard = Blackboard()
    private val agents = linkedMapOf<String, Agent>()
    private val sessions = mutableMapOf<String, CollaborationSession>()
    private val agentStates = mutableMapOf<String, AgentState>()

    // ===== Agent 管理 =====

    fun registerAgent(agent: Agent) {
        agents[agent.id] = agent
        agentStates[agent.id] = AgentState.IDLE
        ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Engine] registered agent: ${agent.id} (${agent.role})")
    }

    fun unregisterAgent(agentId: String) {
        agents.remove(agentId)
        agentStates.remove(agentId)
    }

    fun listAgents(): List<Agent> = agents.values.toList()
    fun getAgent(agentId: String): Agent? = agents[agentId]
    fun getAgentState(agentId: String): AgentState = agentStates[agentId] ?: AgentState.IDLE

    fun findAgentsByCapability(capability: String): List<Agent> =
        agents.values.filter { it.hasCapability(capability) }

    fun findAgentsByRole(role: AgentRole): List<Agent> =
        agents.values.filter { it.role == role }

    // ===== 会话管理 =====

    suspend fun startSession(config: CollaborationConfig): BridgeResult<String> = bridgeRun {
        val sessionId = Trace.newId("ma")
        val session = CollaborationSession(sessionId, config, blackboard, agents.toMap(), _events)
        sessions[sessionId] = session
        sessionId
    }

    suspend fun executeSession(sessionId: String): SessionResult = bridgeRun {
        val session = sessions[sessionId] ?: throw IllegalStateException("session not found: $sessionId")
        session.execute()
    }

    suspend fun run(config: CollaborationConfig): SessionResult = bridgeRun {
        val sessionId = startSession(config).getOrNull() ?: throw IllegalStateException("failed to start session")
        executeSession(sessionId)
    }

    fun cancelSession(sessionId: String): Boolean {
        return sessions.remove(sessionId) != null
    }

    fun listSessions(): List<SessionInfo> = sessions.map { (id, s) ->
        SessionInfo(id, s.config.mode, s.config.agentIds.size, s.config.initialPrompt.take(80), System.currentTimeMillis())
    }

    // ===== 黑板 =====

    fun readBlackboard(key: String): Any? = blackboard.get<Any>(key)
    fun writeBlackboard(key: String, value: Any, writer: String? = null) = blackboard.put(key, value, writer = writer)
    fun blackboardSnapshot(): Map<String, Any> = blackboard.snapshot()
    fun blackboardKeys(): Set<String> = blackboard.keys()
    fun clearBlackboard() = blackboard.clear()

    // ===== 消息 =====

    fun sendMessage(sessionId: String, message: AgentMessage): Boolean {
        val session = sessions[sessionId] ?: return false
        session.sendMessage(message)
        return true
    }

    fun getSessionMessages(sessionId: String): List<AgentMessage> {
        return sessions[sessionId]?.getMessages() ?: emptyList()
    }

    // ===== 内部辅助 =====

    private fun ensureSession(sessionId: String): CollaborationSession {
        return sessions[sessionId] ?: throw IllegalStateException("session not found: $sessionId")
    }
}

/** 会话信息。 */
data class SessionInfo(
    val sessionId: String,
    val mode: CollaborationMode,
    val agentCount: Int,
    val initialPrompt: String,
    val createdAt: Long
)
