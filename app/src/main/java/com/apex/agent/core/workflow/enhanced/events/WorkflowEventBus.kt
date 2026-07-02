package com.apex.agent.core.workflow.enhanced.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流事件 - 用于事件驱动触发与节点间事件通信
 *
 * 参照 LlamaIndex Workflows 的事件驱动模型
 * 与 n8n 的 Webhook/Event Trigger
 */

/**
 * 工作流事件基类
 */
sealed class WorkflowEvent {
    abstract val type: String
    abstract val payload: Any
    abstract val timestamp: Long

    /** 外部事件（来自 Webhook、系统广播、其他工作流） */
    data class External(
        override val type: String,
        override val payload: Any,
        val source: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowEvent()

    /** 节点输出事件（节点完成后自动发布） */
    data class NodeOutput(
        override val type: String,
        override val payload: Any,
        val nodeId: String,
        val threadId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowEvent()

    /** 自定义事件（用户可在节点内发布） */
    data class Custom(
        override val type: String,
        override val payload: Any,
        val publisherNodeId: String?,
        val threadId: String?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowEvent()
}

/**
 * 事件订阅
 */
data class EventSubscription(
    val subscriptionId: String,
    val eventType: String,
    val filterExpression: String? = null,
    val workflowId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 工作流事件总线
 *
 * 基于 MutableSharedFlow 实现，支持：
 * - 发布事件
 * - 按类型订阅
 * - 按过滤器订阅
 * - 多订阅者广播
 */
class WorkflowEventBus(
    private val extraBufferCapacity: Int = 256
) {
    private val _events = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = extraBufferCapacity)
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    private val subscriptions = ConcurrentHashMap<String, EventSubscription>()
    private val dedupStore = ConcurrentHashMap<String, Long>()
    private val dedupTtlMs: Long = 5 * 60_000L

    /**
     * 发布事件
     */
    suspend fun publish(event: WorkflowEvent) {
        // 去重（基于 dedupKey）
        if (event is WorkflowEvent.External) {
            val dedupKey = event.payload.takeIf { it is String }?.toString()
            if (dedupKey != null && dedupStore.containsKey(dedupKey)) {
                val age = System.currentTimeMillis() - (dedupStore[dedupKey] ?: 0)
                if (age < dedupTtlMs) return  // 跳过重复事件
            }
        }
        _events.emit(event)
    }

    /**
     * 按类型订阅事件
     */
    fun subscribe(eventType: String): kotlinx.coroutines.flow.Flow<WorkflowEvent> {
        return _events.asSharedFlow().filter { it.type == eventType }
    }

    /**
     * 按类型 + 过滤器订阅
     */
    fun subscribe(
        eventType: String,
        filter: (WorkflowEvent) -> Boolean
    ): kotlinx.coroutines.flow.Flow<WorkflowEvent> {
        return _events.asSharedFlow()
            .filter { it.type == eventType && filter(it) }
    }

    /**
     * 注册持久订阅（用于事件触发器）
     */
    fun registerSubscription(sub: EventSubscription) {
        subscriptions[sub.subscriptionId] = sub
    }

    fun unregisterSubscription(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    fun activeSubscriptions(): List<EventSubscription> = subscriptions.values.toList()

    /**
     * 清理过期的去重记录
     */
    fun cleanupDedup() {
        val now = System.currentTimeMillis()
        dedupStore.entries.removeAll { now - it.value > dedupTtlMs }
    }

    /**
     * 发送外部事件（便捷方法）
     */
    suspend fun emitExternal(type: String, payload: Any, source: String) {
        publish(WorkflowEvent.External(type, payload, source))
    }

    /**
     * 发送节点输出事件
     */
    suspend fun emitNodeOutput(type: String, payload: Any, nodeId: String, threadId: String) {
        publish(WorkflowEvent.NodeOutput(type, payload, nodeId, threadId))
    }
}

/**
 * 事件总线持有者 - 全局单例
 */
object EventBusHolder {
    @Volatile
    private var instance: WorkflowEventBus = WorkflowEventBus()

    fun get(): WorkflowEventBus = instance

    fun set(bus: WorkflowEventBus) {
        instance = bus
    }

    fun reset() {
        instance = WorkflowEventBus()
    }
}
