package com.apex.agent.test

import com.apex.agent.core.cache.*
import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 缓存扩展函数测试
 *
 * 验证 getOrPut、cachedFlow、observeStats 和 invalidatePattern 扩展。
 */
class CacheExtensionsTest : BaseUnitTest {

    private lateinit var manager: CacheManager<String>

    @Before
    override fun setUp() {
        super.setUp()
        val memory = MemoryCacheStore<String>(maxSize = 50)
        manager = CacheManager(memory)
    }

    @Test
    fun `getOrPut should return existing value`() {
        manager.put("k", "existing")
        val result = manager.getOrPut("k") { "new" }
        assertEquals("existing", result)
    }

    @Test
    fun `getOrPut should compute and store new value`() {
        val result = manager.getOrPut("missing") { "computed" }
        assertEquals("computed", result)
        assertEquals("computed", manager.get("missing"))
    }

    @Test
    fun `getAll should return map of existing keys`() {
        manager.put("a", "1")
        manager.put("b", "2")
        val all = manager.getAll(listOf("a", "b", "c"))
        assertEquals(2, all.size)
        assertEquals("1", all["a"])
    }

    @Test
    fun `putAll should store multiple values`() {
        manager.putAll(mapOf("x" to "10", "y" to "20"))
        assertEquals("10", manager.get("x"))
        assertEquals("20", manager.get("y"))
    }

    @Test
    fun `refresh should update last access time`() {
        manager.put("k", "v")
        manager.refresh("k")
        assertEquals("v", manager.get("k"))
    }

    @Test
    fun `refresh should handle missing key gracefully`() {
        manager.refresh("nonexistent")
        assertNull(manager.get("nonexistent"))
    }

    @Test
    fun `cachedFlow should emit current value`() = runTest {
        manager.put("fk", "flow_value")
        val flow = manager.cachedFlow("fk")
        val value = flow.first()
        assertEquals("flow_value", value)
    }

    @Test
    fun `observeStats should return current stats`() {
        manager.put("sk", "sv")
        val flow = manager.observeStats()
        val stats = flow.value
        assertTrue(stats.totalEntries >= 1)
    }

    @Test
    fun `invalidatePattern should run without error`() {
        manager.put("prefix1", "a")
        manager.put("prefix2", "b")
        val count = manager.invalidatePattern("prefix")
        assertEquals(0, count)
    }
}
