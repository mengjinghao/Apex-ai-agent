package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 事件总线扩展测试
 *
 * 验证粘滞事件、延迟事件和批量事件功能。
 */
class EventBusExtensionsTest : BaseUnitTest {

    private lateinit var bus: SimpleEventBus

    @Before
    override fun setUp() {
        super.setUp()
        bus = SimpleEventBus()
    }

    @Test
    fun `sticky event should be delivered to late subscribers`() {
        bus.postSticky("sticky_event", "sticky_data")
        val received = mutableListOf<String>()
        bus.subscribeSticky("sticky_event") { received.add(it) }
        assertTrue(received.contains("sticky_data"))
    }

    @Test
    fun `regular event should not be sticky`() {
        bus.post("regular_event", "data")
        val received = mutableListOf<String>()
        bus.subscribe("regular_event") { received.add(it) }
        assertFalse(received.contains("data"))
    }

    @Test
    fun `delayed event should fire after delay`() = runTest {
        val received = mutableListOf<String>()
        bus.subscribe("delayed") { received.add(it) }
        bus.postDelayed("delayed", "late_data", delayMs = 50)
        delay(10)
        assertFalse(received.contains("late_data"))
        delay(50)
        assertTrue(received.contains("late_data"))
    }

    @Test
    fun `batch events should deliver all at once`() {
        val received = mutableListOf<String>()
        bus.subscribe("batch") { received.add(it) }
        bus.postBatch("batch", listOf("a", "b", "c"))
        assertEquals(3, received.size)
    }

    @Test
    fun `sticky event should persist until cleared`() {
        bus.postSticky("persistent", "value1")
        bus.postSticky("persistent", "value2")
        bus.clearSticky("persistent")
        val received = mutableListOf<String>()
        bus.subscribeSticky("persistent") { received.add(it) }
        assertTrue(received.isEmpty())
    }

    @Test
    fun `multiple subscribers should all receive`() {
        val r1 = mutableListOf<String>()
        val r2 = mutableListOf<String>()
        bus.subscribe("multi") { r1.add(it) }
        bus.subscribe("multi") { r2.add(it) }
        bus.post("multi", "msg")
        assertTrue(r1.contains("msg"))
        assertTrue(r2.contains("msg"))
    }

    @Test
    fun `unsubscribe should stop notifications`() {
        val received = mutableListOf<String>()
        val sub = bus.subscribe("unsub") { received.add(it) }
        bus.unsubscribe(sub)
        bus.post("unsub", "gone")
        assertTrue(received.isEmpty())
    }

    @Test
    fun `event bus should handle no subscribers gracefully`() {
        bus.post("orphan_event", "data")
        assertTrue(true)
    }
}

typealias EventHandler = (String) -> Unit

data class Subscription(val topic: String, val handler: EventHandler)

class SimpleEventBus {
    private val subscribers = mutableMapOf<String, MutableList<EventHandler>>()
    private val stickyEvents = mutableMapOf<String, String>()

    fun subscribe(topic: String, handler: EventHandler): Subscription {
        subscribers.getOrPut(topic) { mutableListOf() }.add(handler)
        return Subscription(topic, handler)
    }

    fun unsubscribe(sub: Subscription) {
        subscribers[sub.topic]?.remove(sub.handler)
    }

    fun post(topic: String, data: String) {
        subscribers[topic]?.forEach { it(data) }
    }

    fun postSticky(topic: String, data: String) {
        stickyEvents[topic] = data
        post(topic, data)
    }

    fun subscribeSticky(topic: String, handler: EventHandler) {
        stickyEvents[topic]?.let { handler(it) }
        subscribe(topic, handler)
    }

    fun clearSticky(topic: String) { stickyEvents.remove(topic) }

    fun postDelayed(topic: String, data: String, delayMs: Long) {
        Thread { try { Thread.sleep(delayMs); post(topic, data) } catch (_: Exception) {} }.start()
    }

    fun postBatch(topic: String, data: List<String>) {
        data.forEach { post(topic, it) }
    }
}
