package com.apex.apk.multiagent

import android.content.Context
import com.apex.lib.multiagent.Agent
import com.apex.lib.multiagent.AgentInput
import com.apex.lib.multiagent.AgentOutput
import com.apex.lib.multiagent.AgentRole
import com.apex.lib.multiagent.Blackboard
import com.apex.lib.multiagent.CollaborationConfig
import com.apex.lib.multiagent.CollaborationMode
import com.apex.lib.multiagent.MultiAgentEngine
import com.apex.lib.multiagent.SessionResult
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Multi-Agent APK 的核心服务实现。
 *
 * 包装 [MultiAgentEngine]，对其他 APK 暴露统一的 Kotlin API。
 */
class MultiAgentServiceFacade(private val context: Context) {

    private val engine = MultiAgentEngine()

    init {
        // 注册几个内置的 stub agent，方便测试
        registerBuiltinAgents()
    }

    /**
     * 注册一个 Agent。
     */
    suspend fun registerAgent(
        agentId: String,
        displayName: String,
        role: String,
        executeBlock: suspend (AgentInput, Blackboard) -> AgentOutput
    ): BridgeResult<Unit> = bridgeRun {
        val agentRole = runCatching { AgentRole.valueOf(role) }.getOrDefault(AgentRole.WORKER)
        engine.registerAgent(
            Agent(
                id = agentId,
                displayName = displayName,
                role = agentRole,
                execute = executeBlock
            )
        )
    }

    /**
     * 启动并执行一次多 Agent 协作。
     */
    suspend fun runCollaboration(
        mode: CollaborationMode,
        agentIds: List<String>,
        initialPrompt: String,
        maxRounds: Int = 10,
        timeoutMs: Long = 60_000L
    ): BridgeResult<SessionResult> = bridgeRun {
        val config = CollaborationConfig(
            mode = mode,
            agentIds = agentIds,
            initialPrompt = initialPrompt,
            maxRounds = maxRounds,
            timeoutMs = timeoutMs
        )
        engine.run(config)
    }

    /**
     * 列出所有已注册的 Agent。
     */
    suspend fun listAgents(): BridgeResult<List<AgentInfo>> = bridgeRun {
        engine.listAgents().map { a ->
            AgentInfo(
                id = a.id,
                displayName = a.displayName,
                role = a.role.name,
                accepts = a.accepts
            )
        }
    }

    fun unregisterAgent(agentId: String) {
        engine.unregisterAgent(agentId)
    }

    /**
     * 读取黑板数据。
     */
    fun readBlackboard(key: String): Any? = engine.readBlackboard(key)

    fun writeBlackboard(key: String, value: Any) = engine.writeBlackboard(key, value)

    fun blackboardSnapshot(): Map<String, Any> = engine.blackboardSnapshot()

    fun listSessions() = engine.listSessions()

    fun cancelSession(sessionId: String): Boolean = engine.cancelSession(sessionId)

    private fun registerBuiltinAgents() {
        // 注册一个 Supervisor
        engine.registerAgent(
            Agent(
                id = "builtin.supervisor",
                displayName = "总指挥",
                role = AgentRole.SUPERVISOR,
                accepts = listOf("*")
            ) { input, _ ->
                AgentOutput(
                    result = "[Supervisor] 任务已接收：${input.prompt.take(200)}。开始拆分并分派给 Worker。",
                    confidence = 0.95f,
                    nextAgentId = null
                )
            }
        )
        // 注册一个 Worker
        engine.registerAgent(
            Agent(
                id = "builtin.worker",
                displayName = "执行者",
                role = AgentRole.WORKER,
                accepts = listOf("*")
            ) { input, _ ->
                AgentOutput(
                    result = "[Worker] 已执行任务：${input.prompt.take(200)}。结果：操作完成。",
                    confidence = 0.85f
                )
            }
        )
        // 注册一个 Reviewer
        engine.registerAgent(
            Agent(
                id = "builtin.reviewer",
                displayName = "审查者",
                role = AgentRole.REVIEWER,
                accepts = listOf("*")
            ) { input, _ ->
                AgentOutput(
                    result = "[Reviewer] 已审查输入：${input.prompt.take(200)}。质量合格，可继续。",
                    confidence = 0.9f
                )
            }
        )
        // 注册一个 Critic
        engine.registerAgent(
            Agent(
                id = "builtin.critic",
                displayName = "批评者",
                role = AgentRole.CRITIC,
                accepts = listOf("*")
            ) { input, _ ->
                AgentOutput(
                    result = "[Critic] 对输入：${input.prompt.take(200)} 提出质疑：需进一步验证假设。",
                    confidence = 0.7f
                )
            }
        )
        // 注册一个 Observer
        engine.registerAgent(
            Agent(
                id = "builtin.observer",
                displayName = "旁观者",
                role = AgentRole.OBSERVER,
                accepts = listOf("*")
            ) { input, _ ->
                AgentOutput(
                    result = "[Observer] 记录事件：${input.prompt.take(200)}",
                    confidence = 1.0f
                )
            }
        )

        ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Facade] builtin agents registered: ${engine.listAgents().size}")
    }
}

/** Agent 信息。 */
data class AgentInfo(
    val id: String,
    val displayName: String,
    val role: String,
    val accepts: List<String>
)
