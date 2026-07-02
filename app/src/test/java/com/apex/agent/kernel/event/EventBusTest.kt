package com.apex.agent.kernel.event

import com.apex.agent.kernel.model.SessionError
import com.apex.agent.kernel.model.SessionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EventBusTest {

    private lateinit var eventBus: EventBus
    private val sessionId = "test-session"

    @Before
    fun setUp() {
        eventBus = EventBus(capacity = 64)
    }

    @Test
    fun `publish and subscribe delivers event to subscriber`() = runTest {
        val received = mutableListOf<SessionEvent>()
        val closeable = eventBus.subscribe { received.add(it) }

        val event = SessionEvent.UserInputReceived(sessionId, "hello")
        eventBus.publish(event)

        yield()
        assertEquals(1, received.size)
        assertTrue(received[0] is SessionEvent.UserInputReceived)
        closeable.close()
    }

    @Test
    fun `multiple subscribers receive same event`() = runTest {
        val received1 = mutableListOf<SessionEvent>()
        val received2 = mutableListOf<SessionEvent>()
        val c1 = eventBus.subscribe { received1.add(it) }
        val c2 = eventBus.subscribe { received2.add(it) }

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "broadcast"))

        yield()
        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        c1.close()
        c2.close()
    }

    @Test
    fun `subscribe with type filter delivers only matching events`() = runTest {
        val received = mutableListOf<SessionEvent.ErrorOccurred>()
        val closeable = eventBus.subscribe(
            SessionEvent.ErrorOccurred::class.java
        ) { received.add(it) }

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "hello"))
        eventBus.publish(SessionEvent.ErrorOccurred(sessionId, SessionError("E", "m")))
        eventBus.publish(SessionEvent.TurnCompleted(sessionId, 10, 20, 1))

        yield()
        assertEquals(1, received.size)
        assertTrue(received[0] is SessionEvent.ErrorOccurred)
        closeable.close()
    }

    @Test
    fun `unsubscribe removes listener`() = runTest {
        val count = AtomicInteger(0)
        val handler: (SessionEvent) -> Unit = { count.incrementAndGet() }
        val closeable = eventBus.subscribe(handler)

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "first"))
        yield()
        assertEquals(1, count.get())

        closeable.close()

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "second"))
        yield()
        assertEquals(1, count.get())
    }

    @Test
    fun `unsubscribe by handler reference removes listener`() = runTest {
        val count = AtomicInteger(0)
        val handler: (SessionEvent) -> Unit = { count.incrementAndGet() }
        eventBus.subscribe(handler)

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "first"))
        yield()
        assertEquals(1, count.get())

        eventBus.unsubscribe(handler)

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "second"))
        yield()
        assertEquals(1, count.get())
    }

    @Test
    fun `clear removes all subscribers`() = runTest {
        val count = AtomicInteger(0)
        eventBus.subscribe { count.incrementAndGet() }
        eventBus.subscribe { count.incrementAndGet() }

        eventBus.clear()

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "test"))
        yield()
        assertEquals(0, count.get())
    }

    @Test
    fun `events flow emits published events`() = runTest {
        val flow = eventBus.events()

        val job = launch {
            val event = flow.first()
            assertTrue(event is SessionEvent.ThinkingStarted)
        }

        eventBus.publish(SessionEvent.ThinkingStarted(sessionId, "model-1"))
        yield()
        job.join()
    }

    @Test
    fun `eventsOf filters by type as flow`() = runTest {
        val flow = eventBus.eventsOf(SessionEvent.ToolCallDetected::class.java)

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "hi"))

        val job = launch {
            val event = flow.first()
            assertEquals("my-tool", event.toolName)
        }

        eventBus.publish(SessionEvent.ToolCallDetected(sessionId, "my-tool", emptyMap()))
        yield()
        job.join()
    }

    @Test
    fun `multiple events are delivered in order`() = runTest {
        val received = mutableListOf<String>()
        val closeable = eventBus.subscribe {
            when (it) {
                is SessionEvent.UserInputReceived -> received.add(it.content)
                is SessionEvent.ChunkReceived -> received.add(it.chunk)
                else -> {}
            }
        }

        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "first"))
        eventBus.publish(SessionEvent.ChunkReceived(sessionId, "chunk-1", "c1"))
        eventBus.publish(SessionEvent.UserInputReceived(sessionId, "second"))

        yield()
        assertEquals(3, received.size)
        assertEquals("first", received[0])
        assertEquals("chunk-1", received[1])
        assertEquals("second", received[2])
        closeable.close()
    }

    @Test
    fun `capacity does not drop events under normal load`() = runTest {
        val received = mutableListOf<SessionEvent>()
        val closeable = eventBus.subscribe { received.add(it) }

        repeat(50) { i ->
            eventBus.publish(SessionEvent.UserInputReceived(sessionId, "msg-$i"))
        }

        yield()
        assertEquals(50, received.size)
        closeable.close()
    }
}
