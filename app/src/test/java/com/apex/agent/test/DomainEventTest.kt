package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * 领域事件测试
 *
 * 验证事件创建、元数据和序列化。
 */
class DomainEventTest : BaseUnitTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `event should have unique id`() {
        val event1 = TestDomainEvent("type1")
        val event2 = TestDomainEvent("type1")
        assertNotEquals(event1.eventId, event2.eventId)
    }

    @Test
    fun `event should have timestamp`() {
        val before = System.currentTimeMillis()
        val event = TestDomainEvent("ts_test")
        val after = System.currentTimeMillis()
        assertTrue(event.timestamp in before..after)
    }

    @Test
    fun `event meta data should be accessible`() {
        val event = TestDomainEvent("order_created", metadata = mapOf("orderId" to "123"))
        assertEquals("order_created", event.type)
        assertEquals("123", event.metadata["orderId"])
    }

    @Test
    fun `event should serialize to JSON`() {
        val event = TestDomainEvent("serialize_test")
        val encoded = json.encodeToString(event)
        assertTrue(encoded.contains("serialize_test"))
    }

    @Test
    fun `event should deserialize from JSON`() {
        val original = TestDomainEvent("deser", metadata = mapOf("k" to "v"))
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<TestDomainEvent>(encoded)
        assertEquals(original.type, decoded.type)
    }

    @Test
    fun `metadata should be immutable`() {
        val event = TestDomainEvent("immutable", metadata = mapOf("key" to "val"))
        assertThrows(UnsupportedOperationException::class.java) {
            (event.metadata as MutableMap)["new"] = "value"
        }
    }

    @Test
    fun `event should support correlation id`() {
        val correlationId = UUID.randomUUID().toString()
        val event = TestDomainEvent("correlated", correlationId = correlationId)
        assertEquals(correlationId, event.correlationId)
    }
}

@Serializable
data class TestDomainEvent(
    val type: String,
    val eventId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
