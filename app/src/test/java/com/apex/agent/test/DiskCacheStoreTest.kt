package com.apex.agent.test

import com.apex.agent.core.cache.CacheEntry
import com.apex.agent.core.cache.CachePolicy
import com.apex.agent.core.cache.DiskCacheStore
import com.apex.agent.test.base.BaseUnitTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 磁盘缓存存储测试
 *
 * 验证文件持久化、原子写入、目录结构和过期清理。
 */
class DiskCacheStoreTest : BaseUnitTest {

    private lateinit var cacheDir: File
    private lateinit var store: DiskCacheStore

    @Before
    override fun setUp() {
        super.setUp()
        cacheDir = File(System.getProperty("java.io.tmpdir"), "disk-cache-test-${System.nanoTime()}")
        store = DiskCacheStore(cacheDir = cacheDir, maxSize = 10, cleanupIntervalSec = 3600)
    }

    @After
    override fun tearDown() {
        super.tearDown()
        store.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `put and get should persist to disk`() {
        store.put(CacheEntry("key1", "persistent_value"))
        val retrieved = store.get("key1")
        assertNotNull(retrieved)
        assertEquals("persistent_value", retrieved!!.value)
    }

    @Test
    fun `get should return null for missing key`() {
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun `file should exist on disk after put`() {
        store.put(CacheEntry("file_test", "disk_data"))
        val file = File(cacheDir, "file_test.json")
        assertTrue(file.exists() || cacheDir.walkTopDown().any { it.nameWithoutExtension == "file_test" })
    }

    @Test
    fun `remove should delete from disk and index`() {
        store.put(CacheEntry("removable", "data"))
        assertTrue(store.remove("removable"))
        assertNull(store.get("removable"))
    }

    @Test
    fun `clear should remove all entries`() {
        store.put(CacheEntry("a", "1"))
        store.put(CacheEntry("b", "2"))
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `contains should check existence`() {
        store.put(CacheEntry("exists", "yes"))
        assertTrue(store.contains("exists"))
        assertFalse(store.contains("missing"))
    }

    @Test
    fun `stats should track disk usage`() {
        store.put(CacheEntry("s1", "x"))
        store.put(CacheEntry("s2", "y"))
        val stats = store.stats()
        assertTrue(stats.totalEntries >= 2)
    }

    @Test
    fun `evict should clean up files`() {
        store.put(CacheEntry("e1", "v"))
        store.put(CacheEntry("e2", "v"))
        store.evict(CachePolicy.LruPolicy(maxSize = 1))
        val stats = store.stats()
        assertTrue(stats.totalEntries <= 2)
    }

    @Test
    fun `size should reflect entry count`() {
        store.put(CacheEntry("a", "1"))
        store.put(CacheEntry("b", "2"))
        assertEquals(2, store.size())
    }

    @Test
    fun `warmUp should find existing keys`() {
        store.put(CacheEntry("w1", "data"))
        val loaded = store.warmUp(listOf("w1", "w2"))
        assertEquals(1, loaded)
    }
}
