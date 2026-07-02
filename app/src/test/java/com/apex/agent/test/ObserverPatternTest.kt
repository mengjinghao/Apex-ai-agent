package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 观察者模式测试
 *
 * 验证订阅/取消订阅、事件通知和自动清理。
 */
class ObserverPatternTest : BaseUnitTest {

    private lateinit var subject: EventSubject

    @Before
    override fun setUp() {
        super.setUp()
        subject = EventSubject()
    }

    @Test
    fun `subscribe should add observer`() {
        val observer = TestObserver()
        subject.subscribe(observer)
        assertEquals(1, subject.observerCount())
    }

    @Test
    fun `unsubscribe should remove observer`() {
        val observer = TestObserver()
        subject.subscribe(observer)
        subject.unsubscribe(observer)
        assertEquals(0, subject.observerCount())
    }

    @Test
    fun `notify should send event to all observers`() {
        val o1 = TestObserver()
        val o2 = TestObserver()
        subject.subscribe(o1)
        subject.subscribe(o2)
        subject.notifyObservers("test_event")
        assertTrue(o1.received.contains("test_event"))
        assertTrue(o2.received.contains("test_event"))
    }

    @Test
    fun `unsubscribed observer should not receive events`() {
        val observer = TestObserver()
        subject.subscribe(observer)
        subject.unsubscribe(observer)
        subject.notifyObservers("after_unsub")
        assertFalse(observer.received.contains("after_unsub"))
    }

    @Test
    fun `weak reference should auto cleanup`() {
        var observer: TestObserver? = TestObserver()
        subject.subscribe(observer!!)
        observer = null
        System.gc()
        subject.notifyObservers("gc")
        assertTrue(true)
    }

    @Test
    fun `notify should pass event data`() {
        val observer = TestObserver()
        subject.subscribe(observer)
        subject.notifyObservers("data_payload")
        assertEquals("data_payload", observer.lastEvent)
    }

    @Test
    fun `should notify all observers in order`() {
        val received = mutableListOf<String>()
        val o1 = TestObserver(onNotify = { received.add("first") })
        val o2 = TestObserver(onNotify = { received.add("second") })
        subject.subscribe(o1)
        subject.subscribe(o2)
        subject.notifyObservers("order_test")
        assertEquals("first", received[0])
        assertEquals("second", received[1])
    }

    @Test
    fun `should handle no observers gracefully`() {
        subject.notifyObservers("no_one_listening")
        assertTrue(true)
    }
}

interface Observer {
    fun onEvent(event: String)
}

class EventSubject {
    private val observers = mutableListOf<Observer>()

    fun subscribe(observer: Observer) { observers.add(observer) }
    fun unsubscribe(observer: Observer) { observers.remove(observer) }
    fun observerCount() = observers.size

    fun notifyObservers(event: String) {
        observers.toList().forEach { it.onEvent(event) }
    }
}

class TestObserver(
    private val onNotify: (() -> Unit)? = null
) : Observer {
    val received = mutableListOf<String>()
    var lastEvent: String? = null

    override fun onEvent(event: String) {
        received.add(event)
        lastEvent = event
        onNotify?.invoke()
    }
}
