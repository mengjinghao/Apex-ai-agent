package com.apex.lib.multiagent

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 多 Agent 协作引擎 — 多 Agent 模式 APK 的核心库。
 *
 * 设计要点：
 *   - 角色分工：Supervisor / Worker / Reviewer / Critic
 *   - 协作模式：顺序流水线 / 辩论 / 对抗 / 并行竞速 / 层级
 *   - 共享黑板：Agent 间通过 [Blackboard] 通信
 *   - 可被其他 APK（主 APK / 狂暴 APK）通过 Bridge 直接调用，零延迟
 *
 * 本库打包进 [:apk:multi-agent]，其他 APK 通过进程内反射或 AIDL 调用，
 * **不会重复打包**本库的字节码。
 */
class MultiAgentEngine {

    private val _events = MutableSharedFlow<MultiAgentEvent>(extraBufferCapacity = 64)
    val events: Flow<MultiAgentEvent> = _events.asSharedFlow()

    /** 全局共享黑板 — 所有 Agent 可读写。 */
    val blackboard = Blackboard()

    private val agents = linkedMapOf<String, Agent>()

    /** 当前活跃会话。 */
    private val sessions = mutableMapOf<String, CollaborationSession>()

    fun registerAgent(agent: Agent) {
        agents[agent.id] = agent
        ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Engine] registered agent: ${agent.id} (${agent.role})")
    }

    fun unregisterAgent(agentId: String) {
        agents.remove(agentId)
    }

    fun listAgents(): List<Agent> = agents.values.toList()

    fun getAgent(agentId: String): Agent? = agents[agentId]

    /**
     * 启动一次多 Agent 协作会话。
     * @param config 协作配置（角色 + 模式 + 输入）
     * @return sessionId
     */
    suspend fun startSession(config: CollaborationConfig): BridgeResult<String> = bridgeRun {
        val sessionId = Trace.newId("ma")
        val session = CollaborationSession(
            sessionId = sessionId,
            config = config,
            blackboard = blackboard,
            agents = agents.toMap(),
            eventSink = _events
        )
        sessions[sessionId] = session
        ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Engine] session started: $sessionId (mode=${config.mode}, agents=${config.agentIds.size})")
        sessionId
    }

    /**
     * 执行已启动的会话。
     */
    suspend fun executeSession(sessionId: String): BridgeResult<SessionResult> = bridgeRun {
        val session = sessions[sessionId] ?: throw IllegalStateException("session not found: $sessionId")
        val result = session.execute()
        sessions.remove(sessionId)
        result
    }

    /**
     * 一步启动并执行会话。
     */
    suspend fun run(config: CollaborationConfig): BridgeResult<SessionResult> = bridgeRun {
        val sessionId = startSession(config).getOrNull() ?: throw IllegalStateException("failed to start session")
        executeSession(sessionId)
    }

    /**
     * 取消会话。
     */
    fun cancelSession(sessionId: String): Boolean {
        return sessions.remove(sessionId) != null
    }

    /**
     * 列出当前活跃会话。
     */
    fun listSessions(): List<SessionInfo> = sessions.map { (id, s) ->
        SessionInfo(
            sessionId = id,
            mode = s.config.mode,
            agentCount = s.config.agentIds.size,
            initialPrompt = s.config.initialPrompt.take(80),
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * 读取黑板数据。
     */
    fun readBlackboard(key: String): Any? = blackboard.get<Any>(key)

    fun writeBlackboard(key: String, value: Any) = blackboard.put(key, value)

    fun blackboardSnapshot(): Map<String, Any> = blackboard.snapshot()
}

/** 会话信息。 */
data class SessionInfo(
    val sessionId: String,
    val mode: CollaborationMode,
    val agentCount: Int,
    val initialPrompt: String,
    val createdAt: Long
)
