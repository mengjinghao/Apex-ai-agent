package com.apex.agent.infrastructure.eventbus

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class EventBusTest {

    private val eventBus: EventBus = EventBus.Default()

    @Test
    fun `publish and subscribe should deliver event to subscriber`() = runBlocking {
        val flow: SharedFlow<TestEvent> = eventBus.subscribe(TestEvent::class.java)

        eventBus.publish(TestEvent("hello"))

        val received = withTimeout(1000L) { flow.first() }
        assertEquals("hello", received.payload)
    }

    @Test
    fun `multiple subscribers should receive same event`() = runBlocking {
        val flow1: SharedFlow<TestEvent> = eventBus.subscribe(TestEvent::class.java)
        val flow2: SharedFlow<TestEvent> = eventBus.subscribe(TestEvent::class.java)

        eventBus.publish(TestEvent("broadcast"))

        val received1 = withTimeout(1000L) { flow1.first() }
        val received2 = withTimeout(1000L) { flow2.first() }
        assertEquals("broadcast", received1.payload)
        assertEquals("broadcast", received2.payload)
    }

    @Test
    fun `subscriber should only receive events of subscribed type`() = runBlocking {
        val eventAFlow: SharedFlow<EventA> = eventBus.subscribe(EventA::class.java)

        eventBus.publish(EventB("ignored"))
        eventBus.publish(EventA("received"))

        val received = withTimeout(1000L) { eventAFlow.first() }
        assertEquals("received", received.payload)
    }

    private data class TestEvent(val payload: String)
    private data class EventA(val payload: String)
    private data class EventB(val payload: String)
}
