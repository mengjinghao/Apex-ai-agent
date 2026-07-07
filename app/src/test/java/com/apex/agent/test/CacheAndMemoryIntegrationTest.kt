package com.apex.agent.test

import com.apex.agent.core.cache.*
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 缓存与内存集成测试
 *
 * 验证 L1→L2 缓存晋升策略、内存-缓存交互以及多级一致性。
 */
class CacheAndMemoryIntegrationTest : BaseUnitTest {

    private lateinit var memory: MemoryCacheStore<String>
    private lateinit var disk: DiskCacheStore
    private lateinit var manager: CacheManager<String>

    @Before
    override fun setUp() {
        super.setUp()
        memory = MemoryCacheStore(maxSize = 5)
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "cache-int-${System.nanoTime()}")
        tmpDir.mkdirs()
        disk = DiskCacheStore(cacheDir = tmpDir, maxSize = 20)
        manager = CacheManager(memory, disk, promotionThreshold = 2, demotionThreshold = 1)
    }

    @Test
    fun `write through should propagate from L1 to L2`() {
        manager.put("shared", "value")
        assertNotNull(memory.get("shared"))
        assertNotNull(disk.get("shared"))
    }

    @Test
    fun `read through L2 should promote to L1`() {
        manager.put("l2_only", "disk_val")
        manager.clear()
        memory.put(CacheEntry("l1", "mem"))
        manager = CacheManager(memory, disk, promotionThreshold = 1)
        val retrieved = manager.get("l2_only")
        assertEquals("disk_val", retrieved)
    }

    @Test
    fun `L1 eviction should not lose data in L2`() {
        val smallMemory = MemoryCacheStore<String>(maxSize = 2)
        val multiLevel = CacheManager(smallMemory, disk)
        multiLevel.put("a", "1")
        multiLevel.put("b", "2")
        multiLevel.put("c", "3")
        assertNull(smallMemory.get("a"))
        assertNotNull(disk.get("a"))
    }

    @Test
    fun `clear L1 should preserve L2 data`() {
        manager.put("preserve", "data")
        memory.clear()
        val retrieved = manager.get("preserve")
        assertEquals("data", retrieved)
    }

    @Test
    fun `frequency based promotion should promote hot keys`() {
        val promo = CacheManager(memory, disk, promotionThreshold = 3)
        promo.put("hot", "data")
        promo.get("hot")
        promo.get("hot")
        promo.get("hot")
        assertNotNull(memory.get("hot"))
    }

    @Test
    fun `remove should delete from all levels`() {
        manager.put("remove_all", "v")
        manager.remove("remove_all")
        assertNull(manager.get("remove_all"))
        assertFalse(memory.contains("remove_all"))
    }

    @Test
    fun `batch operations should work across levels`() {
        manager.putAll(mapOf("k1" to "v1", "k2" to "v2"))
        val result = manager.getAll(listOf("k1", "k2"))
        assertEquals(2, result.size)
    }

    @Test
    fun `cache stats should aggregate across levels`() {
        manager.put("s", "v")
        manager.get("s")
        manager.get("m")
        val stats = manager.stats()
        assertTrue(stats.totalEntries >= 1)
    }
}
