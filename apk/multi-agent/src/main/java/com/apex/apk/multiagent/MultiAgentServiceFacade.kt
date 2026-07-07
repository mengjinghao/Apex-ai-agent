package com.apex.apk.multiagent

import android.content.Context
import com.apex.lib.multiagent.Agent
import com.apex.lib.multiagent.AgentInput
import com.apex.lib.multiagent.AgentInvocation
import com.apex.lib.multiagent.AgentMessage
import com.apex.lib.multiagent.AgentOutput
import com.apex.lib.multiagent.AgentRole
import com.apex.lib.multiagent.AgentState
import com.apex.lib.multiagent.AgentTemplate
import com.apex.lib.multiagent.AgentTemplates
import com.apex.lib.multiagent.Blackboard
import com.apex.lib.multiagent.CollaborationConfig
import com.apex.lib.multiagent.CollaborationMode
import com.apex.lib.multiagent.CollaborationRecommendation
import com.apex.lib.multiagent.CollaborationRecommender
import com.apex.lib.multiagent.CollaborationSession
import com.apex.lib.multiagent.MultiAgentEngine
import com.apex.lib.multiagent.MultiAgentEvent
import com.apex.lib.multiagent.SessionResult
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Multi-Agent APK 的核心服务实现（增强版）。
 *
 * **7 种协作模式**：
 *   1. PIPELINE — 顺序流水线
 *   2. DEBATE — 辩论（多轮交锋 + 主持人裁决）
 *   3. ADVERSARIAL — 对抗（Generator vs Discriminator）
 *   4. PARALLEL_RACING — 并行竞速（真实并行 + 取置信度最高）
 *   5. HIERARCHICAL — 层级（Supervisor 分派 + Reviewer 检查）
 *   6. VOTING — 投票表决
 *   7. CONSENSUS — 共识达成
 *
 * **10 种预设角色模板**：
 *   代码审查员 / 测试生成器 / 文档撰写者 / 架构师 / 调试专家 /
 *   安全审计员 / 性能优化师 / 翻译官 / 总结者 / 创意顾问
 *
 * **增强能力**：
 *   - Agent 能力声明 + 依赖 + 状态机
 *   - Blackboard 类型安全 + 订阅 + TTL
 *   - Agent 间消息传递
 *   - 协作推荐器
 *   - 详细调用历史
 *   - 协作指标
 */
class MultiAgentServiceFacade(private val context: Context) {

    private val engine = MultiAgentEngine()
    private val _events = MutableSharedFlow<MultiAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MultiAgentEvent> = _events.asSharedFlow()

    init {
        registerBuiltinAgents()
    }

    // ============================================================
    // Agent 管理
    // ============================================================

    /**
     * 注册一个 Agent（带执行体）。
     */
    suspend fun registerAgent(
        agentId: String,
        displayName: String,
        role: String,
        capabilities: List<String> = emptyList(),
        executeBlock: suspend (AgentInput, Blackboard) -> AgentOutput
    ): BridgeResult<Unit> = bridgeRun {
        val agentRole = runCatching { AgentRole.valueOf(role) }.getOrDefault(AgentRole.WORKER)
        engine.registerAgent(Agent(
            id = agentId,
            displayName = displayName,
            role = agentRole,
            capabilities = capabilities,
            execute = executeBlock
        ))
    }

    /**
     * 从模板注册 Agent。
     */
    suspend fun registerFromTemplate(
        templateId: String,
        customId: String? = null,
        executeBlock: suspend (AgentInput, Blackboard) -> AgentOutput
    ): BridgeResult<String> = bridgeRun {
        val template = AgentTemplates.ALL.firstOrNull { it.id == templateId }
            ?: throw IllegalArgumentException("template not found: $templateId")
        val agent = template.create(customId, executeBlock)
        engine.registerAgent(agent)
        agent.id
    }

    /**
     * 注册所有内置 stub Agent（5 种角色）。
     */
    private fun registerBuiltinAgents() {
        engine.registerAgent(Agent(
            id = "builtin.supervisor",
            displayName = "总指挥",
            role = AgentRole.SUPERVISOR,
            capabilities = listOf("planning", "decomposition", "coordination"),
            priority = 10
        ) { input, _ ->
            AgentOutput(
                result = "[Supervisor] 任务已接收：${input.prompt.take(200)}。开始拆分并分派给 Worker。",
                confidence = 0.95f
            )
        })
        engine.registerAgent(Agent(
            id = "builtin.worker",
            displayName = "执行者",
            role = AgentRole.WORKER,
            capabilities = listOf("execution", "implementation"),
            priority = 50
        ) { input, _ ->
            AgentOutput(result = "[Worker] 已执行：${input.prompt.take(200)}", confidence = 0.85f)
        }
        engine.registerAgent(Agent(
            id = "builtin.reviewer",
            displayName = "审查者",
            role = AgentRole.REVIEWER,
            capabilities = listOf("review", "quality_check"),
            priority = 30
        ) { input, _ ->
            AgentOutput(result = "[Reviewer] 审查通过：${input.prompt.take(200)}", confidence = 0.9f)
        })
        engine.registerAgent(Agent(
            id = "builtin.critic",
            displayName = "批评者",
            role = AgentRole.CRITIC,
            capabilities = listOf("critique", "adversarial"),
            priority = 60
        ) { input, _ ->
            AgentOutput(result = "[Critic] 质疑：${input.prompt.take(200)} 需进一步验证", confidence = 0.7f)
        }
        engine.registerAgent(Agent(
            id = "builtin.observer",
            displayName = "旁观者",
            role = AgentRole.OBSERVER,
            capabilities = listOf("observation", "logging"),
            priority = 100
        ) { input, _ ->
            AgentOutput(result = "[Observer] 记录：${input.prompt.take(200)}", confidence = 1.0f)
        })
        ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Facade] builtin agents registered: ${engine.listAgents().size}")
    }

    fun unregisterAgent(agentId: String) = engine.unregisterAgent(agentId)

    suspend fun listAgents(): BridgeResult<List<AgentInfo>> = bridgeRun {
        engine.listAgents().map { a ->
            AgentInfo(a.id, a.displayName, a.role.name, a.capabilities, a.priority, engine.getAgentState(a.id).name)
        }
    }

    suspend fun findAgentsByCapability(capability: String): BridgeResult<List<AgentInfo>> = bridgeRun {
        engine.findAgentsByCapability(capability).map { a ->
            AgentInfo(a.id, a.displayName, a.role.name, a.capabilities, a.priority, engine.getAgentState(a.id).name)
        }
    }

    suspend fun findAgentsByRole(role: String): BridgeResult<List<AgentInfo>> = bridgeRun {
        val r = runCatching { AgentRole.valueOf(role) }.getOrDefault(AgentRole.WORKER)
        engine.findAgentsByRole(r).map { a ->
            AgentInfo(a.id, a.displayName, a.role.name, a.capabilities, a.priority, engine.getAgentState(a.id).name)
        }
    }

    // ============================================================
    // 协作执行（7 种模式）
    // ============================================================

    suspend fun runCollaboration(
        mode: String,
        agentIds: List<String>,
        initialPrompt: String,
        maxRounds: Int = 10,
        timeoutMs: Long = 60_000L,
        moderatorId: String? = null,
        consensusThreshold: Float = 1.0f,
        votingThreshold: Float = 0.5f,
        continueOnFailure: Boolean = false
    ): BridgeResult<SessionResult> = bridgeRun {
        val m = runCatching { CollaborationMode.valueOf(mode) }.getOrDefault(CollaborationMode.PIPELINE)
        val config = CollaborationConfig(
            mode = m,
            agentIds = agentIds,
            initialPrompt = initialPrompt,
            maxRounds = maxRounds,
            timeoutMs = timeoutMs,
            moderatorId = moderatorId,
            consensusThreshold = consensusThreshold,
            votingThreshold = votingThreshold,
            continueOnFailure = continueOnFailure,
            recordInvocations = true
        )
        engine.run(config)
    }

    // ============================================================
    // 协作推荐
    // ============================================================

    /**
     * 根据任务描述推荐协作模式 + Agent 组合。
     */
    fun recommendCollaboration(taskDescription: String): CollaborationRecommendation {
        return CollaborationRecommender.recommend(taskDescription)
    }

    /**
     * 列出所有可用模板。
     */
    fun listTemplates(): List<AgentTemplate> = AgentTemplates.ALL

    // ============================================================
    // 会话管理
    // ============================================================

    fun listSessions() = engine.listSessions()
    fun cancelSession(sessionId: String): Boolean = engine.cancelSession(sessionId)

    /**
     * 获取会话的调用历史。
     */
    suspend fun getSessionInvocations(sessionId: String): BridgeResult<List<AgentInvocation>> = bridgeRun {
        // 从最近的 SessionResult 中获取
        emptyList<AgentInvocation>()  // TODO: 缓存最近结果
    }

    // ============================================================
    // Blackboard
    // ============================================================

    fun readBlackboard(key: String): Any? = engine.readBlackboard(key)
    fun writeBlackboard(key: String, value: Any) = engine.writeBlackboard(key, value)
    fun blackboardSnapshot(): Map<String, Any> = engine.blackboardSnapshot()
    fun blackboardKeys(): Set<String> = engine.blackboardKeys()
    fun clearBlackboard() = engine.clearBlackboard()

    // ============================================================
    // 消息
    // ============================================================

    fun sendMessage(sessionId: String, fromAgentId: String, toAgentId: String, content: String, type: String = "DIRECT"): Boolean {
        val msgType = runCatching { com.apex.lib.multiagent.MessageType.valueOf(type) }.getOrDefault(com.apex.lib.multiagent.MessageType.DIRECT)
        return engine.sendMessage(sessionId, AgentMessage(
            fromAgentId = fromAgentId,
            toAgentId = toAgentId,
            content = content,
            type = msgType
        ))
    }

    fun getSessionMessages(sessionId: String): List<AgentMessage> = engine.getSessionMessages(sessionId)
}

/** Agent 信息。 */
data class AgentInfo(
    val id: String,
    val displayName: String,
    val role: String,
    val capabilities: List<String>,
    val priority: Int,
    val state: String
)
