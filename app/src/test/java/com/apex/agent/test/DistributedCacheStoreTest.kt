package com.apex.agent.test

import com.apex.agent.core.cache.CacheEntry
import com.apex.agent.core.cache.CachePolicy
import com.apex.agent.core.cache.DistributedCacheStore
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 分布式缓存存储测试
 *
 * 验证模拟远端访问、降级回退、重试逻辑和统计信息。
 */
class DistributedCacheStoreTest : BaseUnitTest {

    private lateinit var store: DistributedCacheStore<String>

    @Before
    override fun setUp() {
        super.setUp()
        store = DistributedCacheStore(remoteUrl = "http://localhost:9999", maxRetries = 2, retryDelayMs = 10)
    }

    @Test
    fun `put and get should work with local backup`() {
        store.put(CacheEntry("remote_key", "remote_value"))
        val retrieved = store.get("remote_key")
        assertNotNull(retrieved)
        assertEquals("remote_value", retrieved!!.value)
    }

    @Test
    fun `get should return null for missing`() {
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun `remove should delete entry`() {
        store.put(CacheEntry("r", "v"))
        assertTrue(store.remove("r"))
        assertNull(store.get("r"))
    }

    @Test
    fun `clear should empty storage`() {
        store.put(CacheEntry("a", "1"))
        store.put(CacheEntry("b", "2"))
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `contains should work correctly`() {
        store.put(CacheEntry("k", "v"))
        assertTrue(store.contains("k"))
        assertFalse(store.contains("none"))
    }

    @Test
    fun `stats should track hits and misses`() {
        store.put(CacheEntry("h", "v"))
        store.get("h")
        store.get("m")
        val stats = store.stats()
        assertTrue(stats.hits >= 1)
        assertTrue(stats.misses >= 1)
    }

    @Test
    fun `evict should apply ttl policy`() {
        store.put(CacheEntry("e1", "v"))
        store.put(CacheEntry("e2", "v"))
        val evicted = store.evict(CachePolicy.FifoPolicy(maxSize = 1))
        assertTrue(evicted.isNotEmpty())
    }

    @Test
    fun `warmUp should load existing keys`() {
        store.put(CacheEntry("w1", "data"))
        val loaded = store.warmUp(listOf("w1", "w2"))
        assertEquals(1, loaded)
    }

    @Test
    fun `retry should handle transient failures`() {
        val failing = DistributedCacheStore<String>(maxRetries = 1, retryDelayMs = 5)
        // No operation should fail silently with retry
        failing.put(CacheEntry("k", "v"))
        assertNotNull(failing.get("k"))
    }
}
