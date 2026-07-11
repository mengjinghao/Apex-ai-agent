package com.apex.lib.multiagent

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

/**
 * Agent 定义（增强版）。
 *
 * **增强点**：
 *   - 能力声明（capabilities）— 用于任务路由
 *   - 依赖声明（dependencies）— 该 Agent 依赖哪些其他 Agent 的输出
 *   - 状态机（state）— IDLE / RUNNING / PAUSED / COMPLETED / FAILED
 *   - 优先级（priority）— HIERARCHICAL 模式下使用
 *   - 元数据（metadata）— 业务自定义
 *
 * @property id Agent 唯一 ID
 * @property displayName 显示名
 * @property role 角色（SUPERVISOR / WORKER / REVIEWER / CRITIC / OBSERVER）
 * @property capabilities 能力标签列表（如 "code_review" / "test_generation" / "doc_writing"）
 * @property accepts 接收的输入类型（未匹配则跳过，"*" 表示接受所有）
 * @property dependencies 依赖的其他 Agent ID（其输出会作为本 Agent 的输入上下文）
 * @property priority 优先级（0=最高，HIERARCHICAL 模式下决定执行顺序）
 * @property maxRetries 最大重试次数（失败时）
 * @property timeoutMs 超时时间（毫秒，0=不超时）
 * @property metadata 业务自定义元数据
 * @property execute 实际执行体（由业务侧注入）
 */
data class Agent(
    val id: String,
    val displayName: String,
    val role: AgentRole,
    val capabilities: List<String> = emptyList(),
    val accepts: List<String> = listOf("*"),
    val dependencies: List<String> = emptyList(),
    val priority: Int = 100,
    val maxRetries: Int = 0,
    val timeoutMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
    val execute: suspend (AgentInput, Blackboard) -> AgentOutput
) {
    /** 是否有能力处理某标签。 */
    fun hasCapability(capability: String): Boolean = capability in capabilities || "*" in capabilities

    /** 是否接受某输入类型。 */
    fun acceptsInput(type: String): Boolean = type in accepts || "*" in accepts
}

/** Agent 角色。 */
enum class AgentRole(val displayName: String, val icon: String) {
    SUPERVISOR("总指挥", "👑"),  // 拆分任务、分配给 Worker、汇总结果
    WORKER("执行者", "🔧"),      // 完成具体子任务
    REVIEWER("审查者", "🔍"),    // 检查 Worker 输出
    CRITIC("批评者", "⚔️"),      // 从对抗角度挑刺
    OBSERVER("旁观者", "👁️")    // 记录但不参与执行
}

/** Agent 运行时状态。 */
@Serializable
enum class AgentState(val displayName: String) {
    IDLE("空闲"),
    RUNNING("运行中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("已失败"),
    CANCELLED("已取消")
}

/** Agent 输入。 */
data class AgentInput(
    val prompt: String,
    val context: Map<String, Any> = emptyMap(),
    val fromAgentId: String? = null,
    val inputType: String = "*",
    val round: Int = 0,
    val sessionId: String? = null
)

/** Agent 输出。 */
data class AgentOutput(
    val result: String,
    val confidence: Float = 1.0f,
    val metadata: Map<String, Any> = emptyMap(),
    val nextAgentId: String? = null,
    val shouldStop: Boolean = false,  // 是否应该停止整个会话
    val score: Float? = null,         // 评分（DEBATE/ADVERSARIAL 模式用）
    val vote: Boolean? = null         // 投票（VOTING 模式用）
)

/** Agent 执行记录。 */
@Serializable
data class AgentInvocation(
    val agentId: String,
    val agentName: String,
    val role: String,
    val round: Int,
    val order: Int,
    val timestamp: Long,
    val durationMs: Long,
    val inputPrompt: String,
    val outputResult: String,
    val confidence: Float,
    val success: Boolean,
    val errorMessage: String? = null
)

/** 多 Agent 事件。 */
sealed class MultiAgentEvent {
    data class SessionStarted(val sessionId: String, val mode: String, val agentCount: Int) : MultiAgentEvent()
    data class AgentStarted(val sessionId: String, val agentId: String, val agentName: String, val round: Int, val order: Int) : MultiAgentEvent()
    data class AgentFinished(val sessionId: String, val agentId: String, val output: AgentOutput, val durationMs: Long) : MultiAgentEvent()
    data class AgentFailed(val sessionId: String, val agentId: String, val error: String) : MultiAgentEvent()
    data class BlackboardUpdated(val sessionId: String, val entry: BlackboardEntry) : MultiAgentEvent()
    data class MessageSent(val sessionId: String, val fromAgentId: String, val toAgentId: String, val message: AgentMessage) : MultiAgentEvent()
    data class RoundCompleted(val sessionId: String, val round: Int, val agentCount: Int) : MultiAgentEvent()
    data class SessionCompleted(val sessionId: String, val result: SessionResult) : MultiAgentEvent()
    data class SessionFailed(val sessionId: String, val error: String) : MultiAgentEvent()
    data class SessionCancelled(val sessionId: String) : MultiAgentEvent()
}

/** 会话结果。 */
@Serializable
data class SessionResult(
    val sessionId: String,
    val finalOutput: String,
    val agentInvocations: Int,
    val durationMs: Long,
    @kotlinx.serialization.Transient val blackboardSnapshot: Map<String, Any> = emptyMap(),
    val rounds: Int = 0,
    val successRate: Float = 1.0f,
    val agentResults: Map<String, String> = emptyMap(),
    val invocations: List<AgentInvocation> = emptyList()
)

/** Agent 间消息。 */
@Serializable
data class AgentMessage(
    val fromAgentId: String,
    val toAgentId: String,
    val content: String,
    val type: MessageType = MessageType.DIRECT,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

/** 消息类型。 */
@Serializable
enum class MessageType(val displayName: String) {
    DIRECT("直接消息"),       // 点对点
    BROADCAST("广播"),       // 所有人收到
    REQUEST("请求"),         // 需要回复
    RESPONSE("响应"),        // 回复请求
    NOTIFICATION("通知")     // 单向通知
}
