package com.apex.agent.data.burstmode.swarm

import android.content.Context
import android.util.Log
import com.apex.agent.data.burstmode.swarm.SwarmSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Swarm 协作框架的抽象接口。
 *
 * [core:burst-kernel] 不直接依赖 [com.apex.agent.core.collaboration.AgentCollaborationFramework]
 *（后者位于 :app 模块，反向依赖会破坏分层）。任何实现了此接口的协作框架都可以
 * 通过 [BurstKernel.start] 的 `collaborationFramework` 参数注入进来。
 *
 * 实现方需要做的：
 * 1) [registerSwarmAgents] —— 为本次 swarm 注册 N 个虚拟 agent
 * 2) [dispatchToAgent] —— 把单个 chunk 派发给指定 agent 执行
 * 3) [releaseSwarm] —— 会话结束清理
 *
 * 如果调用方没有提供实现，[SwarmBurstOrchestrator] 会回退到本地协程池执行，
 * 仍然能跑通（只是失去跨进程协作能力）。
 */
interface IBurstCollaborationFramework {

    /**
     * 为本次 swarm 会话注册一组虚拟 agent，返回 agentId 列表。
     * agentId 将作为 [dispatchToAgent] 的入参之一。
     */
    suspend fun registerSwarmAgents(
        sessionId: String,
        taskId: String,
        agentCount: Int
    ): List<String>

    /**
     * 把一个 chunk 派发给指定 agent 执行。
     * @param sessionId swarm 会话 id
     * @param agentId 由 [registerSwarmAgents] 返回的 agent id
     * @param chunk 待处理的文本片段
     * @param processor 本地处理函数 —— 实现方可以选择"在本地调用 processor 完成实际工作，
     *        仅把 agentId 用于上下文标记"，也可以"远端派发并返回结果"。两种方式都通过
     *        processor 拿到结果，保证调用方语义一致。
     * @return 该 agent 处理后的输出文本
     */
    suspend fun dispatchToAgent(
        sessionId: String,
        agentId: String,
        chunk: String,
        processor: suspend (agentId: String, chunk: String) -> String
    ): String

    /**
     * 会话结束后释放资源。
     */
    suspend fun releaseSwarm(sessionId: String)
}

/**
 * Swarm 会话句柄。
 */
data class SwarmSession(
    val id: String,
    val taskId: String,
    val agentIds: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Swarm 调度器：把一个任务的多个文本 chunk 派发给多个 agent 并行处理，
 * 然后把结果按 chunk 顺序合并返回。
 *
 * 设计：
 * 1) 若注入了 [IBurstCollaborationFramework]，则用框架的 agent 体系
 * 2) 若未注入（collabFramework == null），回退到本地协程池，仍可执行
 * 3) chunk → agent 的映射采用 round-robin，保证负载均衡
 * 4) 单个 chunk 抛异常不影响其它 chunk，但最终结果中该 chunk 对应的 value 为空字符串
 *
 * 该类被 [com.apex.agent.kernel.burst.BurstExecutionEngine.executeSwarm] 使用。
 */
class SwarmBurstOrchestrator(
    private val appContext: Context,
    private val collabFramework: Any? = null
) {
    companion object {
        private const val TAG = "SwarmBurstOrch"
        private const val DEFAULT_AGENT_COUNT = 3
        private const val MAX_AGENT_COUNT = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, SwarmSession>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 兼容性：把 [collabFramework]（可能是 Any?，例如旧版 AgentCollaborationFramework 实例）
     * 适配为 [IBurstCollaborationFramework]。
     *
     * 当前策略：
     * - 若 collabFramework 已实现 IBurstCollaborationFramework，直接返回
     * - 否则返回 null（调用方应回退到本地协程池）
     *
     * 这避免了对 :app 模块的反向依赖，同时不破坏现有 API。
     */
    private val framework: IBurstCollaborationFramework? by lazy {
        (collabFramework as? IBurstCollaborationFramework)?.let { return@lazy it }
        // 未来可通过反射探测 com.apex.agent.core.collaboration.AgentCollaborationFramework
        // 是否有 IBurstCollaborationFramework 适配器；当前版本暂不支持
        null
    }

    /**
     * 初始化 swarm 会话：注册 agent、记录会话句柄。
     * @param taskId 关联的任务 id（用于命名 / 日志 / 框架侧任务追踪）
     * @return 会话句柄
     */
    fun initializeSwarm(taskId: String): SwarmSession {
        val sessionId = "swarm-${UUID.randomUUID()}"
        val agentCount = computeAgentCount()

        // 同步注册（runBlocking 仅在初始化时阻塞，避免在协程外暴露 suspend API）
        val agentIds: List<String> = runBlocking {
            framework?.registerSwarmAgents(sessionId, taskId, agentCount)
                ?: (1..agentCount).map { "local-agent-$it" }
        }

        val session = SwarmSession(
            id = sessionId,
            taskId = taskId,
            agentIds = agentIds
        )
        sessions[sessionId] = session
        sessionLocks[sessionId] = Mutex()
        Log.d(TAG, "initializeSwarm: session=$sessionId taskId=$taskId agents=${agentIds.size}")
        return session
    }

    /**
     * 把 [chunks] 派发给 swarm 中的 agent 并行处理，返回 chunk → 输出文本 的映射。
     *
     * chunk 顺序与返回 Map 的 key 顺序一致（Map 是按插入顺序的 LinkedHashMap）。
     * 单个 chunk 失败时其 value 为空字符串，但不抛异常。
     *
     * @param sessionId [initializeSwarm] 返回的会话 id
     * @param chunks 待处理的文本片段列表
     * @param chunkProcessor 本地处理函数；framework 实现可选择在本地调用它或远端派发
     */
    suspend fun processWithSwarm(
        sessionId: String,
        chunks: List<String>,
        chunkProcessor: suspend (agentId: String, chunk: String) -> String
    ): Map<String, String> = withContext(Dispatchers.Default) {
        val session = sessions[sessionId]
            ?: return@withContext chunks.associateWith { "" }.also {
                Log.w(TAG, "processWithSwarm: session $sessionId not found, returning empty results")
            }

        if (chunks.isEmpty()) return@withContext emptyMap()

        // round-robin 分配 agent
        val agentAssignment: List<Pair<String, String>> = chunks.mapIndexed { index, chunk ->
            val agentId = session.agentIds[index % session.agentIds.size]
            chunk to agentId
        }

        // 并发派发
        val deferredResults: List<Deferred<Pair<String, String>>> = agentAssignment.map { (chunk, agentId) ->
            scope.async {
                try {
                    val output = if (framework != null) {
                        framework!!.dispatchToAgent(sessionId, agentId, chunk, chunkProcessor)
                    } else {
                        // 回退：直接在本地协程调用 processor
                        chunkProcessor(agentId, chunk)
                    }
                    chunk to (output ?: "")
                } catch (e: Exception) {
                    Log.w(TAG, "chunk processing failed: agent=$agentId err=${e.message}")
                    chunk to ""
                }
            }
        }

        val results = deferredResults.awaitAll()
        // 用 LinkedHashMap 保持插入顺序（与 chunks 顺序一致）
        val ordered = LinkedHashMap<String, String>(results.size)
        for ((chunk, output) in results) {
            ordered[chunk] = output
        }
        ordered
    }

    /**
     * 显式结束会话，释放框架侧资源（如注册的 agent）。
     * 不强制调用 —— [release] 时会统一清理所有未结束的会话。
     */
    suspend fun finishSwarm(sessionId: String) {
        sessions.remove(sessionId)
        sessionLocks.remove(sessionId)
        framework?.releaseSwarm(sessionId)
    }

    /**
     * 释放整个 Orchestrator：取消所有协程、清理所有会话。
     * 幂等。
     */
    fun release() {
        val pendingSessions = sessions.keys.toList()
        sessions.clear()
        sessionLocks.clear()
        scope.launch {
            pendingSessions.forEach { sid ->
                runCatching { framework?.releaseSwarm(sid) }
            }
        }
        scope.cancel()
    }

    /**
     * 根据设备能力计算本次 swarm 使用的 agent 数量。
     * 简单策略：min(可用核心数, MAX_AGENT_COUNT)，至少 1。
     */
    private fun computeAgentCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return cores.coerceIn(1, MAX_AGENT_COUNT).let {
            // 单核设备也至少给 2 个 agent，让并行 pipeline 能跑起来
            if (it < DEFAULT_AGENT_COUNT) DEFAULT_AGENT_COUNT else it
        }
    }
}
