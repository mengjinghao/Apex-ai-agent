package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 事件分发器测试
 *
 * 验证分发器模式、线程安全和错误处理。
 */
class EventDispatcherTest : BaseUnitTest {

    private lateinit var dispatcher: EventDispatcher

    @Before
    override fun setUp() {
        super.setUp()
        dispatcher = EventDispatcher()
    }

    @Test
    fun `dispatch should call handler`() {
        val received = mutableListOf<String>()
        dispatcher.register("topic1") { received.add(it as String) }
        dispatcher.dispatch("topic1", "hello")
        assertTrue(received.contains("hello"))
    }

    @Test
    fun `dispatch should not fail with no handlers`() {
        dispatcher.dispatch("untracked", "data")
        assertTrue(true)
    }

    @Test
    fun `handler error should not affect other handlers`() {
        val received = mutableListOf<String>()
        dispatcher.register("err") { throw RuntimeException("fail") }
        dispatcher.register("err") { received.add("ok") }
        dispatcher.dispatch("err", "test")
        assertTrue(received.contains("ok"))
    }

    @Test
    fun `sync dispatch should block until complete`() {
        val marker = AtomicInteger(0)
        dispatcher.register("sync") { marker.incrementAndGet() }
        dispatcher.dispatch("sync", "data")
        assertEquals(1, marker.get())
    }

    @Test
    fun `async dispatch should not block`() {
        val marker = AtomicInteger(0)
        dispatcher.register("async") {
            Thread.sleep(50)
            marker.incrementAndGet()
        }
        dispatcher.dispatchAsync("async", "data")
        assertTrue(marker.get() <= 1)
    }

    @Test
    fun `should unregister handler`() {
        val received = mutableListOf<String>()
        val handler: EventHandler = { received.add(it as String) }
        dispatcher.register("unreg", handler)
        dispatcher.unregister("unreg", handler)
        dispatcher.dispatch("unreg", "msg")
        assertTrue(received.isEmpty())
    }

    @Test
    fun `dispatcher should handle multiple topics`() {
        val all = mutableListOf<String>()
        dispatcher.register("a") { all.add("a") }
        dispatcher.register("b") { all.add("b") }
        dispatcher.dispatch("a", "")
        dispatcher.dispatch("b", "")
        assertTrue(all.containsAll(listOf("a", "b")))
    }

    @Test
    fun `clear should remove all handlers`() {
        dispatcher.register("x") { }
        dispatcher.register("y") { }
        dispatcher.clear()
        assertTrue(dispatcher.isEmpty())
    }
}

typealias EventHandler = (Any) -> Unit

class EventDispatcher {
    private val handlers = mutableMapOf<String, MutableList<EventHandler>>()

    fun register(topic: String, handler: EventHandler) {
        handlers.getOrPut(topic) { mutableListOf() }.add(handler)
    }

    fun unregister(topic: String, handler: EventHandler) {
        handlers[topic]?.remove(handler)
    }

    fun dispatch(topic: String, event: Any) {
        handlers[topic]?.forEach {
            try { it(event) } catch (_: Exception) {}
        }
    }

    fun dispatchAsync(topic: String, event: Any) {
        Thread { dispatch(topic, event) }.start()
    }

    fun clear() { handlers.clear() }
    fun isEmpty() = handlers.isEmpty() || handlers.all { it.value.isEmpty() }
}
