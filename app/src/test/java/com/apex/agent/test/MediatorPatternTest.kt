package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 中介者模式测试
 *
 * 验证智能体协调、事件排队和消息投递。
 */
class MediatorPatternTest : BaseUnitTest {

    private lateinit var mediator: AgentMediator

    @Before
    override fun setUp() {
        super.setUp()
        mediator = AgentMediator()
    }

    @Test
    fun `agents should register with mediator`() {
        val agent = TestAgent("agent1")
        mediator.register(agent)
        assertTrue(mediator.isRegistered("agent1"))
    }

    @Test
    fun `mediator should deliver messages between agents`() {
        val sender = TestAgent("sender")
        val receiver = TestAgent("receiver")
        mediator.register(sender)
        mediator.register(receiver)
        mediator.send("receiver", "hello", "sender")
        assertTrue(receiver.receivedMessages.contains("hello"))
    }

    @Test
    fun `mediator should queue events`() {
        val agent = TestAgent("a1")
        mediator.register(agent)
        mediator.queue("a1", "queued_event")
        assertEquals(1, mediator.pendingCount())
    }

    @Test
    fun `mediator should deliver queued events`() {
        val agent = TestAgent("a2")
        mediator.register(agent)
        mediator.queue("a2", "event1")
        mediator.queue("a2", "event2")
        mediator.flush()
        assertEquals(2, agent.receivedMessages.size)
    }

    @Test
    fun `broadcast should send to all agents`() {
        val a1 = TestAgent("broadcast1")
        val a2 = TestAgent("broadcast2")
        mediator.register(a1)
        mediator.register(a2)
        mediator.broadcast("announcement")
        assertTrue(a1.receivedMessages.contains("announcement"))
        assertTrue(a2.receivedMessages.contains("announcement"))
    }

    @Test
    fun `unregistered agent should not receive messages`() {
        val a1 = TestAgent("agent_x")
        mediator.register(a1)
        mediator.unregister("agent_x")
        mediator.send("agent_x", "msg", "sender")
        assertFalse(a1.receivedMessages.contains("msg"))
    }

    @Test
    fun `delivery to unknown agent should fail`() {
        val result = mediator.send("unknown", "msg", "sender")
        assertFalse(result)
    }

    @Test
    fun `queued events should be delivered in order`() {
        val agent = TestAgent("ordered")
        mediator.register(agent)
        mediator.queue("ordered", "first")
        mediator.queue("ordered", "second")
        mediator.queue("ordered", "third")
        mediator.flush()
        assertEquals(listOf("first", "second", "third"), agent.receivedMessages)
    }
}

interface Agent {
    val id: String
    fun onMessage(message: String, sender: String)
}

class TestAgent(override val id: String) : Agent {
    val receivedMessages = mutableListOf<String>()

    override fun onMessage(message: String, sender: String) {
        receivedMessages.add(message)
    }
}

class AgentMediator {
    private val agents = mutableMapOf<String, Agent>()
    private val eventQueue = mutableMapOf<String, MutableList<String>>()

    fun register(agent: Agent) { agents[agent.id] = agent }
    fun unregister(id: String) { agents.remove(id) }
    fun isRegistered(id: String) = id in agents
    fun pendingCount() = eventQueue.values.sumOf { it.size }

    fun send(to: String, message: String, from: String): Boolean {
        val agent = agents[to] ?: return false
        agent.onMessage(message, from)
        return true
    }

    fun broadcast(message: String) {
        agents.values.forEach { it.onMessage(message, "system") }
    }

    fun queue(agentId: String, event: String) {
        eventQueue.getOrPut(agentId) { mutableListOf() }.add(event)
    }

    fun flush() {
        for ((id, events) in eventQueue) {
            val agent = agents[id] ?: continue
            events.forEach { agent.onMessage(it, "queue") }
        }
        eventQueue.clear()
    }
}
