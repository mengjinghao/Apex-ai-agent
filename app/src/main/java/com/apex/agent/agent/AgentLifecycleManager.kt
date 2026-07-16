package com.apex.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent 生命周期状态。
 *
 * 状态流转：
 *   CREATED → INITIALIZING → ACTIVE ⇄ PAUSED → STOPPING → TERMINATED
 *                       ↓                              ↑
 *                   FAILED ←─────────────────────────┘
 */
enum class AgentLifecycleState {
    CREATED,
    INITIALIZING,
    ACTIVE,
    PAUSED,
    STOPPING,
    TERMINATED,
    FAILED
}

/**
 * Agent 生命周期事件。
 */
sealed class AgentLifecycleEvent {
    data class StateChanged(val agentId: String, val from: AgentLifecycleState, val to: AgentLifecycleState) : AgentLifecycleEvent()
    data class HealthCheck(val agentId: String, val healthy: Boolean, val message: String?) : AgentLifecycleEvent()
    data class Error(val agentId: String, val error: Throwable) : AgentLifecycleEvent()
    data class MessageReceived(val fromAgentId: String, val toAgentId: String, val message: AgentMessage) : AgentLifecycleEvent()
}

/**
 * Agent 间通信消息。
 *
 * @param fromId 发送方 agent ID
 * @param toId 接收方 agent ID（"broadcast" 表示广播）
 * @param type 消息类型（用户自定义）
 * @param payload 消息内容
 * @param timestamp 发送时间戳
 * @param correlationId 关联 ID，用于请求-响应模式
 */
data class AgentMessage(
    val fromId: String,
    val toId: String,
    val type: String,
    val payload: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val correlationId: String? = null
)

/**
 * Agent 健康状态。
 */
data class AgentHealth(
    val agentId: String,
    val healthy: Boolean,
    val state: AgentLifecycleState,
    val lastCheckTime: Long,
    val message: String? = null,
    val metrics: Map<String, Any> = emptyMap()
)

/**
 * Agent 生命周期回调接口。
 *
 * SubAgent 实现此接口以接收生命周期事件。
 * 所有回调都是可选的（默认实现为空），SubAgent 按需 override。
 */
interface AgentLifecycleCallbacks {

    /**
     * Agent 初始化时调用。
     * 在此执行资源分配、配置加载等操作。
     * 抛出异常会导致初始化失败，状态变为 FAILED。
     */
    suspend fun onInitialize() {}

    /**
     * Agent 启动时调用（状态变为 ACTIVE）。
     * 在此启动后台任务、建立连接等。
     */
    suspend fun onStart() {}

    /**
     * Agent 暂停时调用（状态变为 PAUSED）。
     * 在此暂停后台任务、释放临时资源。
     */
    suspend fun onPause() {}

    /**
     * Agent 恢复时调用（状态从 PAUSED 变为 ACTIVE）。
     */
    suspend fun onResume() {}

    /**
     * Agent 停止时调用（状态变为 STOPPING）。
     * 在此清理资源、关闭连接。
     */
    suspend fun onStop() {}

    /**
     * Agent 销毁时调用（状态变为 TERMINATED）。
     * 最终清理，不可恢复。
     */
    suspend fun onDestroy() {}

    /**
     * 健康检查。返回 true 表示健康。
     * 默认实现根据当前状态判断。
     */
    suspend fun healthCheck(): Boolean = true

    /**
     * 接收其他 Agent 发来的消息。
     * @param message 消息内容
     */
    suspend fun onMessage(message: AgentMessage) {}

    /**
     * 任务进度回调。
     * @param taskId 任务 ID
     * @param progress 0..1
     * @param message 可选进度描述
     */
    fun onProgress(taskId: String, progress: Float, message: String? = null) {}

    /**
     * 任务取消回调。
     * @param taskId 被取消的任务 ID
     * @param reason 取消原因
     */
    suspend fun onTaskCancelled(taskId: String, reason: String? = null) {}
}

/**
 * Agent 生命周期管理器。
 *
 * 负责：
 * - 管理 SubAgent 的生命周期状态流转
 * - 提供健康检查接口
 * - 提供 Agent 间通信总线
 * - 提供任务进度和取消通知
 *
 * 使用方式：
 * ```
 * val manager = AgentLifecycleManager()
 * manager.register(fileAgent)
 * manager.initialize(fileAgent.agentId)
 * manager.start(fileAgent.agentId)
 * manager.healthCheck(fileAgent.agentId)
 * manager.pause(fileAgent.agentId)
 * manager.resume(fileAgent.agentId)
 * manager.stop(fileAgent.agentId)
 * manager.unregister(fileAgent.agentId)
 * ```
 */
class AgentLifecycleManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _states = ConcurrentHashMap<String, MutableStateFlow<AgentLifecycleState>>()
    private val _healths = ConcurrentHashMap<String, MutableStateFlow<AgentHealth>>()

    private val _events = MutableSharedFlow<AgentLifecycleEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AgentLifecycleEvent> = _events.asSharedFlow()

    private val _messageBus = MutableSharedFlow<AgentMessage>(extraBufferCapacity = 512)
    val messageBus: SharedFlow<AgentMessage> = _messageBus.asSharedFlow()

    /** 已注册的 Agent（agentId -> SubAgent） */
    private val agents = ConcurrentHashMap<String, SubAgent>()

    /**
     * 注册 Agent。
     * 注册后状态为 CREATED。
     */
    fun register(agent: SubAgent): Boolean {
        val existing = agents.putIfAbsent(agent.agentId, agent)
        if (existing != null) {
            return false  // 已存在
        }
        _states[agent.agentId] = MutableStateFlow(AgentLifecycleState.CREATED)
        _healths[agent.agentId] = MutableStateFlow(
            AgentHealth(
                agentId = agent.agentId,
                healthy = false,
                state = AgentLifecycleState.CREATED,
                lastCheckTime = System.currentTimeMillis()
            )
        )
        return true
    }

    /**
     * 注销 Agent。会先调用 stop/destroy。
     */
    suspend fun unregister(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        val currentState = _states[agentId]?.value ?: AgentLifecycleState.TERMINATED

        // 确保先停止
        if (currentState == AgentLifecycleState.ACTIVE || currentState == AgentLifecycleState.PAUSED) {
            stop(agentId)
        }
        if (currentState != AgentLifecycleState.TERMINATED) {
            destroy(agentId)
        }

        agents.remove(agentId)
        _states.remove(agentId)
        _healths.remove(agentId)
        return true
    }

    /** 获取 Agent 当前状态。 */
    fun getState(agentId: String): AgentLifecycleState? = _states[agentId]?.value

    /** 观察 Agent 状态变化。 */
    fun observeState(agentId: String): StateFlow<AgentLifecycleState>? = _states[agentId]?.asStateFlow()

    /** 获取 Agent 健康状态。 */
    fun getHealth(agentId: String): AgentHealth? = _healths[agentId]?.value

    /** 观察 Agent 健康状态。 */
    fun observeHealth(agentId: String): StateFlow<AgentHealth>? = _healths[agentId]?.asStateFlow()

    /**
     * 初始化 Agent。
     * CREATED → INITIALIZING → ACTIVE
     */
    suspend fun initialize(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        val stateFlow = _states[agentId] ?: return false

        if (stateFlow.value != AgentLifecycleState.CREATED) {
            return false  // 状态不对
        }

        transitionState(agentId, AgentLifecycleState.CREATED, AgentLifecycleState.INITIALIZING)

        try {
            (agent as? AgentLifecycleCallbacks)?.onInitialize()
            transitionState(agentId, AgentLifecycleState.INITIALIZING, AgentLifecycleState.ACTIVE)
            (agent as? AgentLifecycleCallbacks)?.onStart()
            updateHealth(agentId, healthy = true, "Initialized successfully")
            return true
        } catch (e: Exception) {
            transitionState(agentId, AgentLifecycleState.INITIALIZING, AgentLifecycleState.FAILED)
            updateHealth(agentId, healthy = false, "Initialization failed: ${e.message}")
            _events.tryEmit(AgentLifecycleEvent.Error(agentId, e))
            return false
        }
    }

    /**
     * 暂停 Agent。
     * ACTIVE → PAUSED
     */
    suspend fun pause(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        val stateFlow = _states[agentId] ?: return false

        if (stateFlow.value != AgentLifecycleState.ACTIVE) {
            return false
        }

        try {
            (agent as? AgentLifecycleCallbacks)?.onPause()
            transitionState(agentId, AgentLifecycleState.ACTIVE, AgentLifecycleState.PAUSED)
            return true
        } catch (e: Exception) {
            _events.tryEmit(AgentLifecycleEvent.Error(agentId, e))
            return false
        }
    }

    /**
     * 恢复 Agent。
     * PAUSED → ACTIVE
     */
    suspend fun resume(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        val stateFlow = _states[agentId] ?: return false

        if (stateFlow.value != AgentLifecycleState.PAUSED) {
            return false
        }

        try {
            (agent as? AgentLifecycleCallbacks)?.onResume()
            transitionState(agentId, AgentLifecycleState.PAUSED, AgentLifecycleState.ACTIVE)
            return true
        } catch (e: Exception) {
            _events.tryEmit(AgentLifecycleEvent.Error(agentId, e))
            return false
        }
    }

    /**
     * 停止 Agent。
     * ACTIVE/PAUSED → STOPPING → TERMINATED
     */
    suspend fun stop(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        val stateFlow = _states[agentId] ?: return false
        val currentState = stateFlow.value

        if (currentState != AgentLifecycleState.ACTIVE && currentState != AgentLifecycleState.PAUSED) {
            return false
        }

        transitionState(agentId, currentState, AgentLifecycleState.STOPPING)

        try {
            (agent as? AgentLifecycleCallbacks)?.onStop()
            transitionState(agentId, AgentLifecycleState.STOPPING, AgentLifecycleState.TERMINATED)
            updateHealth(agentId, healthy = false, "Agent stopped")
            return true
        } catch (e: Exception) {
            transitionState(agentId, AgentLifecycleState.STOPPING, AgentLifecycleState.FAILED)
            _events.tryEmit(AgentLifecycleEvent.Error(agentId, e))
            return false
        }
    }

    /**
     * 销毁 Agent。
     * 任何状态 → TERMINATED（如果还未终止）
     */
    suspend fun destroy(agentId: String): Boolean {
        val agent = agents[agentId] ?: return false
        val stateFlow = _states[agentId] ?: return false

        if (stateFlow.value == AgentLifecycleState.TERMINATED) {
            return true  // 已销毁
        }

        try {
            (agent as? AgentLifecycleCallbacks)?.onDestroy()
            transitionState(agentId, stateFlow.value, AgentLifecycleState.TERMINATED)
            return true
        } catch (e: Exception) {
            _events.tryEmit(AgentLifecycleEvent.Error(agentId, e))
            return false
        }
    }

    /**
     * 执行健康检查。
     */
    suspend fun healthCheck(agentId: String): AgentHealth? {
        val agent = agents[agentId] ?: return null
        val stateFlow = _states[agentId] ?: return null

        val healthy = try {
            (agent as? AgentLifecycleCallbacks)?.healthCheck() ?: (stateFlow.value == AgentLifecycleState.ACTIVE)
        } catch (e: Exception) {
            false
        }

        val health = AgentHealth(
            agentId = agentId,
            healthy = healthy,
            state = stateFlow.value,
            lastCheckTime = System.currentTimeMillis()
        )
        _healths[agentId]?.value = health
        _events.tryEmit(AgentLifecycleEvent.HealthCheck(agentId, healthy, null))
        return health
    }

    /**
     * 批量健康检查所有 Agent。
     */
    suspend fun healthCheckAll(): Map<String, AgentHealth> {
        val result = mutableMapOf<String, AgentHealth>()
        for (agentId in agents.keys) {
            healthCheck(agentId)?.let { result[agentId] = it }
        }
        return result
    }

    /**
     * 发送消息给指定 Agent。
     */
    suspend fun sendMessage(message: AgentMessage): Boolean {
        val targetAgent = agents[message.toId] ?: return false

        // 发布到消息总线供观察者订阅
        _messageBus.tryEmit(message)
        _events.tryEmit(
            AgentLifecycleEvent.MessageReceived(message.fromId, message.toId, message)
        )

        // 直接调用目标 Agent 的 onMessage
        return try {
            (targetAgent as? AgentLifecycleCallbacks)?.onMessage(message)
            true
        } catch (e: Exception) {
            _events.tryEmit(AgentLifecycleEvent.Error(message.toId, e))
            false
        }
    }

    /**
     * 广播消息给所有 ACTIVE 状态的 Agent。
     * @param fromId 发送方 agent ID
     * @param type 消息类型
     * @param payload 消息内容
     */
    suspend fun broadcast(fromId: String, type: String, payload: Map<String, Any> = emptyMap()) {
        for ((agentId, _) in agents) {
            if (agentId == fromId) continue  // 不发给自己
            val state = _states[agentId]?.value
            if (state == AgentLifecycleState.ACTIVE) {
                sendMessage(
                    AgentMessage(
                        fromId = fromId,
                        toId = agentId,
                        type = type,
                        payload = payload
                    )
                )
            }
        }
    }

    /**
     * 通知 Agent 任务进度。
     */
    fun notifyProgress(agentId: String, taskId: String, progress: Float, message: String? = null) {
        val agent = agents[agentId] ?: return
        try {
            (agent as? AgentLifecycleCallbacks)?.onProgress(taskId, progress, message)
        } catch (_: Exception) {
            // 进度通知失败不应影响主流程
        }
    }

    /**
     * 通知 Agent 任务被取消。
     */
    suspend fun notifyTaskCancelled(agentId: String, taskId: String, reason: String? = null) {
        val agent = agents[agentId] ?: return
        try {
            (agent as? AgentLifecycleCallbacks)?.onTaskCancelled(taskId, reason)
        } catch (e: Exception) {
            _events.tryEmit(AgentLifecycleEvent.Error(agentId, e))
        }
    }

    /**
     * 获取所有已注册的 Agent ID。
     */
    fun getRegisteredAgentIds(): Set<String> = agents.keys.toSet()

    /**
     * 获取所有处于指定状态的 Agent ID。
     */
    fun getAgentsByState(state: AgentLifecycleState): Set<String> {
        return _states.entries
            .filter { it.value.value == state }
            .map { it.key }
            .toSet()
    }

    /**
     * 关闭生命周期管理器，停止所有 Agent。
     */
    suspend fun shutdown() {
        for (agentId in agents.keys.toList()) {
            try {
                stop(agentId)
                destroy(agentId)
            } catch (_: Exception) {
                // 关闭阶段忽略单个 Agent 异常
            }
        }
        agents.clear()
        _states.clear()
        _healths.clear()
    }

    // ===== 内部方法 =====

    private fun transitionState(
        agentId: String,
        expectedFrom: AgentLifecycleState,
        to: AgentLifecycleState
    ) {
        val stateFlow = _states[agentId] ?: return
        val from = stateFlow.value
        stateFlow.value = to
        _events.tryEmit(AgentLifecycleEvent.StateChanged(agentId, from, to))
    }

    private fun updateHealth(agentId: String, healthy: Boolean, message: String?) {
        val stateFlow = _states[agentId] ?: return
        _healths[agentId]?.value = AgentHealth(
            agentId = agentId,
            healthy = healthy,
            state = stateFlow.value,
            lastCheckTime = System.currentTimeMillis(),
            message = message
        )
    }
}

/**
 * 可组合的 SubAgent + 生命周期回调基类。
 *
 * 业务 Agent 继承此类即可同时获得 SubAgent 能力和生命周期回调。
 *
 * 示例：
 * ```
 * class MyAgent : LifecycleAwareBaseSubAgent(
 *     agentId = "my_agent",
 *     agentType = "custom",
 *     displayName = "My Agent"
 * ) {
 *     override suspend fun onInitialize() {
 *         // 加载配置
 *     }
 *     override suspend fun execute(task: SubTask): SubTaskResult {
 *         // 执行任务
 *     }
 * }
 * ```
 */
abstract class LifecycleAwareBaseSubAgent(
    agentId: String,
    agentType: String,
    displayName: String,
    description: String = ""
) : BaseSubAgent(agentId, agentType, displayName, description), AgentLifecycleCallbacks {

    /**
     * 默认健康检查：状态为 ACTIVE 即健康。
     * 子类可 override 添加自定义检查（如连接状态、资源可用性）。
     */
    override suspend fun healthCheck(): Boolean {
        return true  // 默认健康；子类可覆盖
    }
}
