package com.apex.agent.test

import com.apex.agent.core.cache.CacheEntry
import com.apex.agent.core.cache.CachePolicy
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 缓存淘汰策略测试
 *
 * 验证 TTL、LRU、LFU、FIFO 和混合策略的行为。
 */
class CachePolicyTest : BaseUnitTest {

    @Test
    fun `ttl policy should detect expired entries`() {
        val policy = CachePolicy.TtlPolicy(duration = 100)
        val expired = CacheEntry("k", "v", createdAt = System.currentTimeMillis() - 200)
        assertTrue(policy.isExpired(expired))
    }

    @Test
    fun `ttl policy should accept fresh entries`() {
        val policy = CachePolicy.TtlPolicy(duration = 60000)
        val fresh = CacheEntry("k", "v")
        assertFalse(policy.isExpired(fresh))
    }

    @Test
    fun `ttl policy constructor should validate positive duration`() {
        assertThrows(IllegalArgumentException::class.java) {
            CachePolicy.TtlPolicy(duration = -1)
        }
    }

    @Test
    fun `lru policy should evict least recently used`() {
        val policy = CachePolicy.LruPolicy(maxSize = 2)
        val entries = listOf(
            CacheEntry("a", "1", lastAccessedAt = 100),
            CacheEntry("b", "2", lastAccessedAt = 200),
            CacheEntry("c", "3", lastAccessedAt = 300)
        )
        val toEvict = policy.evictCandidates(entries)
        assertEquals(1, toEvict.size)
        assertEquals("a", toEvict[0].key)
    }

    @Test
    fun `lru policy should not evict when within limit`() {
        val policy = CachePolicy.LruPolicy(maxSize = 10)
        val entries = listOf(CacheEntry("a", "1"), CacheEntry("b", "2"))
        assertTrue(policy.evictCandidates(entries).isEmpty())
    }

    @Test
    fun `lfu policy should evict least frequent`() {
        val policy = CachePolicy.LfuPolicy(minFrequency = 1)
        val entries = listOf(
            CacheEntry("a", "1", hitCount = 0),
            CacheEntry("b", "2", hitCount = 5)
        )
        val toEvict = policy.evictCandidates(entries)
        assertEquals(1, toEvict.size)
        assertEquals("a", toEvict[0].key)
    }

    @Test
    fun `fifo policy should evict oldest`() {
        val policy = CachePolicy.FifoPolicy(maxSize = 1)
        val entries = listOf(
            CacheEntry("old", "1", createdAt = 100),
            CacheEntry("new", "2", createdAt = 200)
        )
        val toEvict = policy.evictCandidates(entries)
        assertEquals(1, toEvict.size)
        assertEquals("old", toEvict[0].key)
    }

    @Test
    fun `fifo policy constructor should validate positive maxSize`() {
        assertThrows(IllegalArgumentException::class.java) {
            CachePolicy.FifoPolicy(maxSize = 0)
        }
    }

    @Test
    fun `hybrid policy should compute eviction score`() {
        val policy = CachePolicy.HybridPolicy(mapOf(
            CachePolicy.TtlPolicy(1000) to 0.5,
            CachePolicy.LruPolicy(10) to 0.5
        ))
        val entry = CacheEntry("k", "v", createdAt = System.currentTimeMillis() - 500)
        val score = policy.evictionScore(entry)
        assertTrue(score > 0.0)
    }

    @Test
    fun `hybrid policy should reject empty policies`() {
        assertThrows(IllegalArgumentException::class.java) {
            CachePolicy.HybridPolicy(emptyMap())
        }
    }
}
