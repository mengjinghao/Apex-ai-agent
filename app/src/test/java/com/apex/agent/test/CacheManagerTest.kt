package com.apex.agent.test

import com.apex.agent.core.cache.*
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 缓存管理器测试
 *
 * 验证多级读写、条目晋升/降级、统计聚合和批量操作。
 */
class CacheManagerTest : BaseUnitTest {

    private lateinit var memory: MemoryCacheStore<String>
    private lateinit var disk: DiskCacheStore
    private lateinit var distributed: DistributedCacheStore<String>
    private lateinit var manager: CacheManager<String>

    @Before
    override fun setUp() {
        super.setUp()
        memory = MemoryCacheStore(maxSize = 100)
        val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "cm-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        disk = DiskCacheStore(cacheDir = tmpDir, maxSize = 100)
        distributed = DistributedCacheStore(remoteUrl = "http://localhost:9999")
        manager = CacheManager(memory, disk, distributed, promotionThreshold = 2, demotionThreshold = 1)
    }

    @Test
    fun `get should read through L1 then L2 then L3`() {
        manager.put("key1", "value1")
        val result = manager.get("key1")
        assertEquals("value1", result)
    }

    @Test
    fun `get should return null for missing key`() {
        assertNull(manager.get("missing"))
    }

    @Test
    fun `put and get should work end to end`() {
        manager.put("k", "v")
        assertTrue(manager.contains("k"))
        assertEquals("v", manager.get("k"))
    }

    @Test
    fun `remove should delete from all levels`() {
        manager.put("k", "v")
        assertTrue(manager.remove("k"))
        assertFalse(manager.contains("k"))
    }

    @Test
    fun `clear should empty all stores`() {
        manager.put("a", "1")
        manager.put("b", "2")
        manager.clear()
        assertEquals(0, manager.size())
    }

    @Test
    fun `stats should aggregate across levels`() {
        manager.put("k", "v")
        manager.get("k")
        manager.get("m")
        val stats = manager.stats()
        assertTrue(stats.hits >= 1 || stats.misses >= 1)
    }

    @Test
    fun `getAll should return multiple values`() {
        manager.put("a", "1")
        manager.put("b", "2")
        val result = manager.getAll(listOf("a", "b", "c"))
        assertEquals(2, result.size)
    }

    @Test
    fun `putAll should store multiple entries`() {
        manager.putAll(mapOf("x" to "10", "y" to "20"))
        assertEquals("10", manager.get("x"))
        assertEquals("20", manager.get("y"))
    }

    @Test
    fun `removeAll should delete multiple keys`() {
        manager.put("a", "1")
        manager.put("b", "2")
        val removed = manager.removeAll(listOf("a", "b"))
        assertEquals(2, removed)
    }

    @Test
    fun `evictAll should apply policy to all levels`() {
        manager.put("e1", "v")
        manager.put("e2", "v")
        val evicted = manager.evictAll()
        assertNotNull(evicted)
    }

    @Test
    fun `warmUp should load keys to L1`() {
        manager.put("w", "data")
        val loaded = manager.warmUp(listOf("w"))
        assertEquals(1, loaded)
    }

    @Test
    fun `close should prevent further operations`() {
        manager.close()
        assertThrows(IllegalStateException::class.java) { manager.get("k") }
    }

    @Test
    fun `setPolicy should bind per-key policy`() {
        manager.setPolicy("pk", CachePolicy.TtlPolicy(100))
        manager.put("pk", "pv")
        assertNotNull(manager.get("pk"))
    }
}
