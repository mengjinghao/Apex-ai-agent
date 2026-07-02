package com.apex.agent.orchestration.communication

import android.content.Context
import com.apex.agent.common.result.Result
import com.apex.agent.domain.entity.AgentMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class MultiChannelSystemTest {

    private lateinit var context: Context
    private lateinit var system: MultiChannelSystem

    @Before
    fun setup() {
        context = Mockito.mock(Context::class.java)
        system = MultiChannelSystem(context)
    }

    @Test
    fun registerAdapter_initializesAndExposesChannel() {
        val adapter = FakeChannelAdapter(CommunicationChannel.TEXT)

        system.registerAdapter(adapter)

        assertTrue(adapter.initialized)
        assertTrue(system.getAvailableChannels().contains(CommunicationChannel.TEXT))
    }

    @Test
    fun sendMessage_withRegisteredAdapter_returnsTrueAndStoresMessage() = runBlocking {
        val adapter = FakeChannelAdapter(CommunicationChannel.TEXT)
        system.registerAdapter(adapter)

        val sent = system.sendMessage("hello", CommunicationChannel.TEXT)

        assertTrue(sent)
        assertEquals(1, adapter.sentMessages.size)
        assertEquals("hello", adapter.sentMessages.first().content)
        assertEquals(1, system.messages.value.size)
    }

    @Test
    fun sendMessage_withoutAdapter_returnsFalse() = runBlocking {
        val sent = system.sendMessage("hello", CommunicationChannel.TEXT)

        assertFalse(sent)
        assertTrue(system.messages.value.isEmpty())
    }

    @Test
    fun sendMessage_withUnavailableAdapter_returnsFalse() = runBlocking {
        val adapter = FakeChannelAdapter(CommunicationChannel.TEXT)
        system.registerAdapter(adapter)
        adapter.shutdown()

        val sent = system.sendMessage("hello", CommunicationChannel.TEXT)

        assertFalse(sent)
    }

    @Test
    fun broadcastMessage_sendsToAllAvailableAdapters() = runBlocking {
        val textAdapter = FakeChannelAdapter(CommunicationChannel.TEXT)
        val notificationAdapter = FakeChannelAdapter(CommunicationChannel.NOTIFICATION)
        val pushAdapter = FakeChannelAdapter(CommunicationChannel.PUSH)
        pushAdapter.shutdown()

        system.registerAdapter(textAdapter)
        system.registerAdapter(notificationAdapter)
        system.registerAdapter(pushAdapter)

        system.broadcastMessage("broadcast")

        assertEquals(1, textAdapter.sentMessages.size)
        assertEquals(1, notificationAdapter.sentMessages.size)
        assertEquals(0, pushAdapter.sentMessages.size)
        assertEquals(2, system.messages.value.size)
    }

    private class FakeChannelAdapter(
        override val channel: CommunicationChannel
    ) : ChannelAdapter {
        override val name: String = "Fake ${channel.name}"
        var initialized = false
        val sentMessages = mutableListOf<AgentMessage>()

        override suspend fun sendMessage(message: AgentMessage): Result<Boolean> {
            if (!initialized) return Result.Success(false)
            sentMessages.add(message)
            return Result.Success(true)
        }

        override fun receiveMessage(callback: (AgentMessage) -> Unit) {
            // no-op for tests
        }

        override suspend fun isAvailable(): Boolean = initialized

        override fun initialize() {
            initialized = true
        }

        override fun shutdown() {
            initialized = false
        }
    }
}
