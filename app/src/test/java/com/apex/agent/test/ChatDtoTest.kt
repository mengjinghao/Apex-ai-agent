package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * 聊天 DTO 测试
 *
 * 验证序列化、流式块、对话模型和消息处理。
 */
class ChatDtoTest : BaseUnitTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `chat message should have required fields`() {
        val msg = ChatMessage("user", "Hello, world!")
        assertEquals("user", msg.role)
        assertEquals("Hello, world!", msg.content)
        assertTrue(msg.timestamp > 0)
    }

    @Test
    fun `chat request should contain messages`() {
        val messages = listOf(ChatMessage("user", "Hi"), ChatMessage("assistant", "Hello"))
        val request = ChatRequest(messages, model = "gpt-4")
        assertEquals(2, request.messages.size)
        assertEquals("gpt-4", request.model)
    }

    @Test
    fun `chat response should handle streaming chunks`() {
        val chunk = ChatStreamChunk("id_1", "Hel", finishReason = null)
        val chunk2 = ChatStreamChunk("id_1", "lo", finishReason = null)
        val full = chunk.content + chunk2.content
        assertEquals("Hello", full)
    }

    @Test
    fun `streaming chunk should detect completion`() {
        val chunk = ChatStreamChunk("id_2", "", finishReason = "stop")
        assertTrue(chunk.isComplete())
        val nonFinal = ChatStreamChunk("id_3", "more", finishReason = null)
        assertFalse(nonFinal.isComplete())
    }

    @Test
    fun `conversation should manage message history`() {
        val conversation = Conversation("conv_1", mutableListOf())
        conversation.addMessage(ChatMessage("user", "Q1"))
        conversation.addMessage(ChatMessage("assistant", "A1"))
        assertEquals(2, conversation.messages.size)
    }

    @Test
    fun `conversation should enforce max history`() {
        val conversation = Conversation("conv_2", maxHistory = 3)
        repeat(5) { conversation.addMessage(ChatMessage("user", "msg$it")) }
        assertTrue(conversation.messages.size <= 3)
    }

    @Test
    fun `serialization round trip for chat message`() {
        val original = ChatMessage("system", "Be helpful", timestamp = 12345L)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ChatMessage>(encoded)
        assertEquals(original.role, decoded.role)
        assertEquals(original.content, decoded.content)
    }

    @Test
    fun `chat request should serialize with model`() {
        val req = ChatRequest(listOf(ChatMessage("user", "test")), model = "claude-3")
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("claude-3"))
    }

    @Test
    fun `conversation should track token count approximation`() {
        val conv = Conversation("conv_3")
        conv.addMessage(ChatMessage("user", "hello world"))
        val estimated = conv.estimatedTokens()
        assertTrue(estimated > 0)
    }
}

@Serializable
data class ChatMessage(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

@Serializable
data class ChatRequest(val messages: List<ChatMessage>, val model: String = "default")

@Serializable
data class ChatStreamChunk(val id: String, val content: String, val finishReason: String?) {
    fun isComplete(): Boolean = finishReason != null
}

data class Conversation(
    val id: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val maxHistory: Int = 100
) {
    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        if (messages.size > maxHistory) {
            messages.removeAt(0)
        }
    }

    fun estimatedTokens(): Int {
        return messages.sumOf { it.content.split(" ").size }
    }
}
