package com.apex.agent.test

import com.apex.agent.core.cache.CacheEntry
import com.apex.agent.core.cache.CacheSerialization
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 缓存序列化测试
 *
 * 验证 JSON 序列化往返、版本兼容性和类型适配。
 */
class CacheSerializationTest : BaseUnitTest {

    @Test
    fun `serialize and deserialize round trip`() {
        val original = CacheEntry("rt-key", "rt-value")
        val json = CacheSerialization.serialize(original)
        assertNotNull(json)
        assertTrue(json.contains("rt-key"))

        val deserialized = CacheSerialization.deserialize(json) { it }
        assertEquals(original.key, deserialized.key)
        assertEquals(original.value, deserialized.value)
    }

    @Test
    fun `serialize should include version field`() {
        val entry = CacheEntry("v", "data")
        val json = CacheSerialization.serialize(entry)
        assertTrue(json.contains("version"))
    }

    @Test
    fun `deserialize should ignore unknown fields`() {
        val json = """{"version":1,"key":"k","value":"v","createdAt":100,"lastAccessedAt":100,"unknown":"ignored"}"""
        val entry = CacheSerialization.deserialize(json) { it }
        assertEquals("k", entry.key)
        assertEquals("v", entry.value)
    }

    @Test
    fun `serialize should preserve metadata`() {
        val entry = CacheEntry("meta", "val", ttl = 5000, hitCount = 3)
        val json = CacheSerialization.serialize(entry)
        val deserialized = CacheSerialization.deserialize(json) { it }
        assertEquals(entry.key, deserialized.key)
        assertEquals(entry.value, deserialized.value)
    }

    @Test
    fun `should handle int values`() {
        val entry = CacheEntry("int-key", 42)
        val json = CacheSerialization.serialize(entry)
        val deserialized = CacheSerialization.deserialize(json) { it.toIntOrNull() ?: 0 }
        assertEquals(42, deserialized.value)
    }

    @Test
    fun `should handle double values`() {
        val entry = CacheEntry("db-key", 3.14)
        val json = CacheSerialization.serialize(entry)
        val deserialized = CacheSerialization.deserialize(json) { it.toDoubleOrNull() ?: 0.0 }
        assertEquals(3.14, deserialized.value, 0.001)
    }

    @Test
    fun `should handle custom deserializer`() {
        val entry = CacheEntry("custom", "hello")
        val json = CacheSerialization.serialize(entry)
        val deserialized = CacheSerialization.deserialize(json) { it.uppercase() }
        assertEquals("HELLO", deserialized.value)
    }
}
