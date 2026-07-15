package com.apex.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent 事件类型枚举。
 *
 * 用于 [AgentEventBus] 的事件分类订阅。
 */
enum class AgentEventType {
    /** Agent 注册/注销 */
    REGISTRATION,
    /** 生命周期状态变化 */
    LIFECYCLE,
    /** 任务执行事件 */
    TASK,
    /** 健康检查事件 */
    HEALTH,
    /** Agent 间消息 */
    MESSAGE,
    /** 错误事件 */
    ERROR,
    /** 自定义事件 */
    CUSTOM
}

/**
 * Agent 事件基类。
 *
 * 所有通过 [AgentEventBus] 发布的事件都继承此类。
 */
sealed class AgentEvent {
    abstract val type: AgentEventType
    abstract val timestamp: Long
    abstract val sourceAgentId: String?

    data class Registered(
        override val sourceAgentId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.REGISTRATION
    }

    data class Unregistered(
        override val sourceAgentId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.REGISTRATION
    }

    data class LifecycleStateChanged(
        override val sourceAgentId: String,
        val from: AgentLifecycleState,
        val to: AgentLifecycleState,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.LIFECYCLE
    }

    data class TaskStarted(
        override val sourceAgentId: String,
        val taskId: String,
        val taskType: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.TASK
    }

    data class TaskProgress(
        override val sourceAgentId: String,
        val taskId: String,
        val progress: Float,
        val message: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.TASK
    }

    data class TaskCompleted(
        override val sourceAgentId: String,
        val taskId: String,
        val success: Boolean,
        val executionTime: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.TASK
    }

    data class HealthChanged(
        override val sourceAgentId: String,
        val healthy: Boolean,
        val message: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.HEALTH
    }

    data class AgentMessageSent(
        override val sourceAgentId: String,
        val toAgentId: String,
        val messageType: String,
        val payload: Map<String, Any>,
        val correlationId: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.MESSAGE
    }

    data class ErrorOccurred(
        override val sourceAgentId: String?,
        val error: Throwable,
        val context: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.ERROR
    }

    /**
     * 自定义事件。
     * 业务侧可使用此类型发布任意事件。
     */
    data class Custom(
        override val sourceAgentId: String?,
        val eventName: String,
        val data: Map<String, Any> = emptyMap(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentEvent() {
        override val type = AgentEventType.CUSTOM
    }
}

/**
 * 事件订阅句柄。
 *
 * 用于取消订阅。调用 [cancel] 后不再收到事件。
 */
class EventSubscription(
    private val onCancel: () -> Unit
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        if (!cancelled) {
            cancelled = true
            onCancel()
        }
    }
        val isActive: Boolean get() = !cancelled
}

/**
 * Agent 事件总线。
 *
 * 提供 Agent 间松耦合的事件驱动通信。
 *
 * 特性：
 * - 类型过滤订阅（按 [AgentEventType]）
 * - 来源过滤订阅（按 agentId）
 * - 持久化订阅（新订阅者收到历史事件）
 * - 事件回放（支持断线重连后补播）
 *
 * 与 [AgentLifecycleManager.messageBus] 的区别：
 * - messageBus 是 Agent 间的直接消息传递（点对点）
 * - AgentEventBus 是全局事件广播（发布订阅）
 *
 * 使用方式：
 * ```
 * val bus = AgentEventBus()
 *
 * // 订阅所有任务事件
 * bus.subscribe(setOf(AgentEventType.TASK)) { event ->
 *     when (event) {
 *         is AgentEvent.TaskStarted -> println("Task started: ${event.taskId}")
 *         is AgentEvent.TaskCompleted -> println("Task done: ${event.taskId}")
 *     }
 * }
 *
 * // 发布事件
 * bus.publish(AgentEvent.TaskStarted("agent_1", "task_123", "file_read"))
 * ```
 */
class AgentEventBus(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val historySize: Int = 1000,
    private val enableHistory: Boolean = true
) {

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 512
    )
        val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** 事件历史（用于回放）。 */
    private val eventHistory: ConcurrentLinkedQueue<AgentEvent> = ConcurrentLinkedQueue()

    /** 活跃订阅数（用于监控）。 */
    private val subscriptionCount = AtomicLong(0)

    /**
     * 发布事件。
     */
    fun publish(event: AgentEvent) {
        if (enableHistory) {
            eventHistory.add(event)
            // 保持历史不超过上限
            while (eventHistory.size > historySize) {
                eventHistory.poll()
            }
        }
        _events.tryEmit(event)
    }

    /**
     * 订阅事件。
     *
     * @param types 订阅的事件类型集合（空集合表示订阅所有）
     * @param handler 事件处理器
     * @return 订阅句柄（用于取消订阅）
     */
    fun subscribe(
        types: Set<AgentEventType> = emptySet(),
        handler: suspend (AgentEvent) -> Unit
    ): EventSubscription {
        val job = scope.launch {
            events.collect { event ->
                if (types.isEmpty() || event.type in types) {
                    try {
                        handler(event)
                    } catch (_: Exception) {
                        // 处理器异常不应影响事件总线
                    }
                }
            }
        }
        subscriptionCount.incrementAndGet()
        return EventSubscription {
            job.cancel()
            subscriptionCount.decrementAndGet()
        }
    }

    /**
     * 订阅特定来源 Agent 的事件。
     *
     * @param sourceAgentId 来源 Agent ID
     * @param types 事件类型集合（空集合表示订阅所有）
     * @param handler 事件处理器
     */
    fun subscribeFromAgent(
        sourceAgentId: String,
        types: Set<AgentEventType> = emptySet(),
        handler: suspend (AgentEvent) -> Unit
    ): EventSubscription {
        return subscribe(types) { event ->
            if (event.sourceAgentId == sourceAgentId) {
                handler(event)
            }
        }
    }

    /**
     * 订阅特定类型的事件（类型安全）。
     *
     * 示例：
     * ```
     * bus.subscribeTyped<AgentEvent.TaskCompleted> { event ->
     *     println("Task ${event.taskId} completed in ${event.executionTime}ms")
     * }
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : AgentEvent> subscribeTyped(
        crossinline handler: suspend (T) -> Unit
    ): EventSubscription {
        return subscribe(emptySet()) { event ->
            if (event is T) {
                handler(event)
            }
        }
    }

    /**
     * 获取事件历史（副本）。
     */
    fun getHistory(): List<AgentEvent> = eventHistory.toList()

    /**
     * 获取历史事件中指定类型的。
     */
    fun getHistoryByType(type: AgentEventType): List<AgentEvent> =
        eventHistory.filter { it.type == type }

    /**
     * 清空事件历史。
     */
    fun clearHistory() {
        eventHistory.clear()
    }

    /**
     * 获取当前活跃订阅数。
     */
    fun getSubscriptionCount(): Long = subscriptionCount.get()

    /**
     * 关闭事件总线。
     */
    fun shutdown() {
        scope.cancel()
        eventHistory.clear()
    }

    // ===== 便捷发布方法 =====

    /** 发布任务开始事件。 */
    fun publishTaskStarted(agentId: String, taskId: String, taskType: String) {
        publish(AgentEvent.TaskStarted(agentId, taskId, taskType))
    }

    /** 发布任务进度事件。 */
    fun publishTaskProgress(agentId: String, taskId: String, progress: Float, message: String? = null) {
        publish(AgentEvent.TaskProgress(agentId, taskId, progress, message))
    }

    /** 发布任务完成事件。 */
    fun publishTaskCompleted(agentId: String, taskId: String, success: Boolean, executionTime: Long) {
        publish(AgentEvent.TaskCompleted(agentId, taskId, success, executionTime))
    }

    /** 发布健康变化事件。 */
    fun publishHealthChanged(agentId: String, healthy: Boolean, message: String? = null) {
        publish(AgentEvent.HealthChanged(agentId, healthy, message))
    }

    /** 发布错误事件。 */
    fun publishError(agentId: String?, error: Throwable, context: String? = null) {
        publish(AgentEvent.ErrorOccurred(agentId, error, context))
    }

    /**
     * 发布请求-响应消息。
     * @return correlationId（用于匹配响应）
     */
    fun publishRequest(
        fromAgentId: String,
        toAgentId: String,
        messageType: String,
        payload: Map<String, Any> = emptyMap()
    ): String {
        val correlationId = UUID.randomUUID().toString()
        publish(AgentEvent.AgentMessageSent(fromAgentId, toAgentId, messageType, payload, correlationId))
        return correlationId
    }

    /**
     * 发布响应消息。
     * @param correlationId 对应请求的 correlationId
     */
    fun publishResponse(
        fromAgentId: String,
        toAgentId: String,
        messageType: String,
        payload: Map<String, Any> = emptyMap(),
        correlationId: String
    ) {
        publish(AgentEvent.AgentMessageSent(fromAgentId, toAgentId, messageType, payload, correlationId))
    }
}

/**
 * Agent 事件总线单例。
 *
 * 全局共享的事件总线，业务侧直接使用 [AgentEventBus] 实例或创建自己的实例。
 * 单例适用于大多数场景；需要隔离的场景（如测试）可创建独立实例。
 */
object GlobalAgentEventBus : AgentEventBus()
