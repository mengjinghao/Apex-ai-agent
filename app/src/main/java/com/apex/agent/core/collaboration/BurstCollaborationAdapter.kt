package com.apex.agent.core.collaboration

import android.util.Log
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.data.burstmode.swarm.IBurstCollaborationFramework
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 :app 模块的 [AgentCollaborationFramework] 适配为 :core:burst-kernel 的
 * [IBurstCollaborationFramework] 接口，让 [com.apex.agent.kernel.burst.BurstKernel]
 * 的 SWARM 执行模式可以真正派发到协作框架侧的 agent 体系。
 *
 * ## 适配策略
 *
 * [AgentCollaborationFramework] 本身没有"派发 chunk 给 agent 真正执行"的 API
 * （它的 [AgentCollaborationFramework.executeSession] 只是模拟执行），所以本 adapter
 * 采用"会话 + agent 注册 + 本地调用 processor"的混合方案：
 *
 * - [registerSwarmAgents]：调用 framework.createSession 建立一次会话，并 registerAgent
 *   N 个虚拟 agent（role=SPECIALIST, capabilities=["burst-chunk"]）。
 *   返回的 agentId 后续作为 [dispatchToAgent] 的入参，也用于 [AgentCollaborationFramework.sendMessage]
 *   留痕派发轨迹。
 *
 * - [dispatchToAgent]：在本地 [withContext] 调用 [processor] 拿到结果（framework 没有
 *   真正派发执行的能力，processor 才是真正的本地业务逻辑），同时调用 framework.sendMessage
 *   留一条 REQUEST 消息和一条 RESPONSE 消息，便于通过 framework.generateSessionReport
 *   追溯每次派发。
 *
 * - [releaseSwarm]：从内部 map 移除 sessionId；调用 framework.updateTaskStatus 让所有
 *   关联 task 进入 COMPLETED（兜底清理，幂等）。
 *
 * ## 线程安全
 *
 * - [sessions] 用 ConcurrentHashMap 维护 sessionId → SwarmSessionState 映射
 * - 同一 sessionId 的并发 dispatchToAgent 调用安全（framework 内部所有方法都 withContext(IO)）
 *
 * ## 生命周期
 *
 * adapter 由 Hilt @Singleton 提供，与 [AgentCollaborationFramework] 同生命周期。
 * 不持有任何需要 release 的资源（framework 内部的广播/协程由它自己管理）。
 */
class BurstCollaborationAdapter(
    private val framework: AgentCollaborationFramework
) : IBurstCollaborationFramework {

    companion object {
        private const val TAG = "BurstCollabAdapter"
        private const val SYSTEM_AGENT_ID = "SYSTEM"
    }

    /**
     * 单次 swarm 会话在 adapter 侧的本地状态。
     */
    private data class SwarmSessionState(
        val sessionId: String,
        val taskId: String,
        val agentIds: List<String>,
        /** 该会话内创建的 framework Task id 列表，releaseSwarm 时统一标记完成 */
        val frameworkTaskIds: MutableList<String> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis()
    )
        private val sessions = ConcurrentHashMap<String, SwarmSessionState>()

    override suspend fun registerSwarmAgents(
        sessionId: String,
        taskId: String,
        agentCount: Int
    ): List<String> = withContext(Dispatchers.IO) {
        // 1) 在 framework 侧建立一次协作会话（PARALLEL 类型最契合 swarm 语义）
    val session = runCatching {
            framework.createSession(
                name = "burst-swarm-$sessionId",
                type = AgentCollaborationFramework.CollaborationType.PARALLEL,
                goal = "swarm for task=$taskId",
                agents = emptyList()  // agents 在 registerAgent 后通过 activeAgents 关联
            )
        }.getOrNull()
        if (session == null) {
            Log.w(TAG, "registerSwarmAgents: createSession failed, using local-only mode")
            // 降级：仍然返回本地虚拟 agentId，dispatchToAgent 走纯本地路径
    val fallbackIds = (1..agentCount.coerceAtLeast(1)).map { "local-$sessionId-$it" }
            sessions[sessionId] = SwarmSessionState(
                sessionId = sessionId,
                taskId = taskId,
                agentIds = fallbackIds
            )
            return@withContext fallbackIds
        }

        // 2) 在 framework 侧注册 N 个虚拟 agent
    val agentIds = (1..agentCount.coerceAtLeast(1)).map { idx ->
            val agentId = "swarm-$sessionId-$idx"
        val agent = AgentCollaborationFramework.Agent(
                id = agentId,
                name = "Burst Swarm Agent #$idx",
                role = AgentCollaborationFramework.AgentRole.SPECIALIST,
                capabilities = listOf("burst-chunk", "text-processing"),
                specialties = listOf("chunk-processing", "swarm-collaboration"),
                isActive = true,
                taskLoad = 0f
            )
            runCatching { framework.registerAgent(agent) }
                .onFailure { Log.w(TAG, "registerAgent $agentId failed: ${it.message}") }
            agentId
        }

        sessions[sessionId] = SwarmSessionState(
            sessionId = sessionId,
            taskId = taskId,
            agentIds = agentIds
        )

        Log.d(TAG, "registerSwarmAgents: session=$sessionId agents=${agentIds.size} fwSession=${session.id}")
        agentIds
    }

    override suspend fun dispatchToAgent(
        sessionId: String,
        agentId: String,
        chunk: String,
        processor: suspend (agentId: String, chunk: String) -> String
    ): String = withContext(Dispatchers.IO) {
        // 留痕：派发请求
        runCatching {
            framework.sendMessage(
                senderAgent = SYSTEM_AGENT_ID,
                recipientAgent = agentId,
                content = chunk.take(2000),  // 防止超长消息
                messageType = AgentCollaborationFramework.MessageType.REQUEST,
                attachments = emptyList()
            )
        }.onFailure { Log.w(TAG, "dispatch sendMessage REQUEST failed: ${it.message}") }

        // 真正执行：本地调用 processor
    val output = runCatching { processor(agentId, chunk) }
            .onFailure { Log.w(TAG, "processor failed for agent=$agentId: ${it.message}") }
            .getOrDefault("")

        // 留痕：派发响应
        runCatching {
            framework.sendMessage(
                senderAgent = agentId,
                recipientAgent = SYSTEM_AGENT_ID,
                content = output.take(2000),
                messageType = AgentCollaborationFramework.MessageType.RESPONSE,
                attachments = emptyList()
            )
        }.onFailure { Log.w(TAG, "dispatch sendMessage RESPONSE failed: ${it.message}") }

        output
    }

    override suspend fun releaseSwarm(sessionId: String) = withContext(Dispatchers.IO) {
        val state = sessions.remove(sessionId)
        if (state == null) {
            Log.d(TAG, "releaseSwarm: session=$sessionId not found (idempotent)")
            return@withContext
        }

        // 把所有 framework task 标记为 COMPLETED（兜底清理）
        state.frameworkTaskIds.forEach { taskId ->
            runCatching {
                framework.updateTaskStatus(
                    taskId,
                    AgentCollaborationFramework.TaskStatus.COMPLETED
                )
            }.onFailure { Log.w(TAG, "updateTaskStatus $taskId failed: ${it.message}") }
        }

        // 让 framework session 进入 COMPLETED
        // 注意：framework 没有 endSession/releaseSession API，只能通过 executeSession 兜底
        runCatching { framework.executeSession(sessionId) }
            .onFailure { Log.w(TAG, "executeSession $sessionId failed: ${it.message}") }

        Log.d(TAG, "releaseSwarm: session=$sessionId released, agents=${state.agentIds.size}")
    }
}
