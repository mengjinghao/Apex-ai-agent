package com.apex.agent.core.patterns

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 中介者模式 - 代理间通信协调
 * 解耦多个 Agent 之间的直接引用，通过中介者统一管理消息路由和事件分发
 */

/** 事件优先级 */
enum class EventPriority { LOW, NORMAL, HIGH, CRITICAL }

/** 中介者事件 */
data class MediatorEvent(
    val senderId: String,
    val type: String,
    val data: Any? = null,
    val priority: EventPriority = EventPriority.NORMAL,
    val timestamp: Long = System.currentTimeMillis()
)

/** 投递保证级别 */
enum class DeliveryGuarantee { AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE }

/** Agent 组件接口 */
interface AgentComponent {
    val name: String
    val capabilities: Set<String>

    fun onEvent(event: MediatorEvent): Boolean
    fun getName(): String = name
    fun getCapabilities(): Set<String> = capabilities
}

/** 中介者接口 */
interface Mediator<T : AgentComponent> {
    fun register(component: T)
    fun unregister(component: T)
    fun notify(sender: T, event: MediatorEvent)
    fun sendTo(sender: T, target: T, event: MediatorEvent): Boolean
}

/** Agent 中介者实现 */
class AgentMediator(
    private val deliveryGuarantee: DeliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE
) : Mediator<AgentComponent> {

    private val components = CopyOnWriteArrayList<AgentComponent>()
    private val eventQueue = ConcurrentLinkedQueue<MediatorEvent>()
    private val failedDeliveries = AtomicInteger(0)

    override fun register(component: AgentComponent) {
        if (components.none { it.name == component.name }) {
            components.add(component)
        }
    }

    override fun unregister(component: AgentComponent) {
        components.remove(component)
    }

    override fun notify(sender: AgentComponent, event: MediatorEvent) {
        when (deliveryGuarantee) {
            DeliveryGuarantee.AT_MOST_ONCE -> {
                components.filter { it.name != sender.name }.forEach { it.onEvent(event) }
            }
            DeliveryGuarantee.AT_LEAST_ONCE -> {
                eventQueue.offer(event)
                processQueue(sender)
            }
            DeliveryGuarantee.EXACTLY_ONCE -> {
                val receivers = components.filter { it.name != sender.name }
                val allDelivered = receivers.all { it.onEvent(event) }
                if (!allDelivered) {
                    eventQueue.offer(event)
                    failedDeliveries.incrementAndGet()
                }
            }
        }
    }

    override fun sendTo(sender: AgentComponent, target: AgentComponent, event: MediatorEvent): Boolean {
        val component = components.find { it.name == target.name } ?: return false
        return component.onEvent(event)
    }

    private fun processQueue(sender: AgentComponent) {
        val processed = mutableListOf<MediatorEvent>()
        for (event in eventQueue) {
            val delivered = components.filter { it.name != sender.name }.all { it.onEvent(event) }
            if (delivered) processed.add(event)
        }
        eventQueue.removeAll(processed)
    }

    fun getRegisteredComponents(): List<AgentComponent> = components.toList()
    fun getQueuedEventCount(): Int = eventQueue.size
    fun getFailedDeliveryCount(): Int = failedDeliveries.get()
}

/** AgentA */
class AgentA : AgentComponent {
    override val name = "AgentA"
    override val capabilities = setOf("reasoning", "planning")

    override fun onEvent(event: MediatorEvent): Boolean {
        return when (event.type) {
            "task_assigned" -> { true }
            "status_request" -> { true }
            else -> false
        }
    }
}

/** AgentB */
class AgentB : AgentComponent {
    override val name = "AgentB"
    override val capabilities = setOf("tool_execution", "code_generation")

    override fun onEvent(event: MediatorEvent): Boolean {
        return when (event.type) {
            "execute_tool" -> { true }
            "generate_code" -> { true }
            else -> false
        }
    }
}

/** AgentC */
class AgentC : AgentComponent {
    override val name = "AgentC"
    override val capabilities = setOf("memory", "knowledge_retrieval")

    override fun onEvent(event: MediatorEvent): Boolean {
        return when (event.type) {
            "query_knowledge" -> { true }
            "store_memory" -> { true }
            else -> false
        }
    }
}
