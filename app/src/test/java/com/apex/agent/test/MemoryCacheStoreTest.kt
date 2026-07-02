package com.apex.agent.test

import com.apex.agent.core.cache.CacheEntry
import com.apex.agent.core.cache.CachePolicy
import com.apex.agent.core.cache.MemoryCacheStore
import com.apex.agent.core.cache.CacheStats
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 内存缓存存储测试
 *
 * 验证 put/get、TTL 过期、LRU 淘汰、并发安全和统计信息。
 */
class MemoryCacheStoreTest : BaseUnitTest {

    private lateinit var store: MemoryCacheStore<String>

    @Before
    override fun setUp() {
        super.setUp()
        store = MemoryCacheStore(maxSize = 5, defaultTtl = -1L)
    }

    @Test
    fun `put and get should store and retrieve values`() {
        store.put(CacheEntry("key1", "value1"))
        val retrieved = store.get("key1")
        assertNotNull(retrieved)
        assertEquals("value1", retrieved!!.value)
    }

    @Test
    fun `get should return null for missing key`() {
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun `get should return null for expired entry`() {
        store.put(CacheEntry("key1", "value1", ttl = -1000L, createdAt = System.currentTimeMillis() - 5000))
        val retrieved = store.get("key1")
        assertNull(retrieved)
    }

    @Test
    fun `lru eviction should remove least recently used`() {
        val small = MemoryCacheStore<String>(maxSize = 2)
        small.put(CacheEntry("a", "1"))
        small.put(CacheEntry("b", "2"))
        small.put(CacheEntry("c", "3"))
        assertNull(small.get("a"))
        assertNotNull(small.get("b"))
        assertNotNull(small.get("c"))
    }

    @Test
    fun `concurrent access should be thread safe`() {
        val threads = (1..10).map { i ->
            Thread { store.put(CacheEntry("key$i", "val$i")) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        for (i in 1..10) {
            val entry = store.get("key$i")
            if (entry != null) assertEquals("val$i", entry.value)
        }
    }

    @Test
    fun `stats should track hits and misses`() {
        store.put(CacheEntry("k", "v"))
        store.get("k")
        store.get("missing")
        val stats = store.stats()
        assertTrue(stats.hits >= 1)
        assertTrue(stats.misses >= 1)
    }

    @Test
    fun `remove should delete entry`() {
        store.put(CacheEntry("k", "v"))
        assertTrue(store.remove("k"))
        assertNull(store.get("k"))
    }

    @Test
    fun `clear should remove all entries`() {
        store.put(CacheEntry("a", "1"))
        store.put(CacheEntry("b", "2"))
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `contains should return true for existing key`() {
        store.put(CacheEntry("k", "v"))
        assertTrue(store.contains("k"))
    }

    @Test
    fun `evict should apply policy`() {
        store.put(CacheEntry("a", "1"))
        store.put(CacheEntry("b", "2"))
        val evicted = store.evict(CachePolicy.FifoPolicy(maxSize = 1))
        assertTrue(evicted.isNotEmpty())
    }

    @Test
    fun `warmUp should report loaded count`() {
        store.put(CacheEntry("a", "1"))
        val loaded = store.warmUp(listOf("a", "b"))
        assertEquals(1, loaded)
    }

    @Test
    fun `stats should reflect total entries`() {
        store.put(CacheEntry("x", "data"))
        store.put(CacheEntry("y", "data"))
        val stats = store.stats()
        assertTrue(stats.totalEntries >= 2)
    }
}
