package com.apex.lib.multiagent

import kotlinx.coroutines.flow.SharedFlow

/**
 * 单个 Agent 定义。
 */
data class Agent(
    val id: String,
    val displayName: String,
    val role: AgentRole,
    /** 该 Agent 接收的输入 schema，未匹配则跳过。 */
    val accepts: List<String> = listOf("*"),
    /** 实际执行体（由业务侧注入）。 */
    val execute: suspend (AgentInput, Blackboard) -> AgentOutput
)

enum class AgentRole {
    SUPERVISOR,  // 总指挥：拆分任务、分配给 Worker
    WORKER,      // 执行者：完成具体子任务
    REVIEWER,    // 审查者：检查 Worker 输出
    CRITIC,      // 批评者：从对抗角度挑刺
    OBSERVER     // 旁观者：记录但不参与执行
}

data class AgentInput(
    val prompt: String,
    val context: Map<String, Any> = emptyMap(),
    val fromAgentId: String? = null
)

data class AgentOutput(
    val result: String,
    val confidence: Float = 1.0f,
    val metadata: Map<String, Any> = emptyMap(),
    val nextAgentId: String? = null
)

sealed class MultiAgentEvent {
    data class AgentStarted(val sessionId: String, val agentId: String) : MultiAgentEvent()
    data class AgentFinished(val sessionId: String, val agentId: String, val output: AgentOutput) : MultiAgentEvent()
    data class BlackboardUpdated(val sessionId: String, val entry: BlackboardEntry) : MultiAgentEvent()
    data class SessionCompleted(val sessionId: String, val result: SessionResult) : MultiAgentEvent()
    data class SessionFailed(val sessionId: String, val error: String) : MultiAgentEvent()
}

data class SessionResult(
    val sessionId: String,
    val finalOutput: String,
    val agentInvocations: Int,
    val durationMs: Long,
    val blackboardSnapshot: Map<String, Any>
)
