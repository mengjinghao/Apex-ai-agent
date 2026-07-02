package com.apex.lib.multiagent

import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 多 Agent 协作引擎 — 多 Agent 模式 APK 的核心库。
 *
 * 设计要点：
 *   - 角色分工：Supervisor / Worker / Reviewer / Critic
 *   - 协作模式：顺序流水线 / 辩论 / 对抗 / 并行竞速
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

    private val agents = mutableMapOf<String, Agent>()

    fun registerAgent(agent: Agent) {
        agents[agent.id] = agent
    }

    fun unregisterAgent(agentId: String) {
        agents.remove(agentId)
    }

    /**
     * 启动一次多 Agent 协作会话。
     * @param config 协作配置（角色 + 模式 + 输入）
     */
    suspend fun run(config: CollaborationConfig): BridgeResult<SessionResult> = bridgeRun {
        val session = CollaborationSession(
            sessionId = com.apex.sdk.common.Trace.newId("ma"),
            config = config,
            blackboard = blackboard,
            agents = agents.toMap(),
            eventSink = _events
        )
        session.execute()
    }

    fun listAgents(): List<Agent> = agents.values.toList()
}
