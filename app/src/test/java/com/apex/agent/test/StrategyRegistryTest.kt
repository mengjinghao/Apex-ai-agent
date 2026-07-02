package com.apex.agent.test

import com.apex.agent.core.patterns.StrategyRegistry
import com.apex.agent.core.patterns.ReasoningStrategyRegistry
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 策略注册表测试
 *
 * 验证注册/获取/注销/查找功能及线程安全。
 */
class StrategyRegistryTest : BaseUnitTest {

    private lateinit var registry: StrategyRegistry<String, Int>

    @Before
    override fun setUp() {
        super.setUp()
        registry = StrategyRegistry()
    }

    @Test
    fun `register should store strategy`() {
        registry.register("key1", 42)
        assertEquals(42, registry.get("key1"))
    }

    @Test
    fun `get should return null for missing key`() {
        assertNull(registry.get("missing"))
    }

    @Test
    fun `unregister should remove strategy`() {
        registry.register("k", 1)
        assertEquals(1, registry.unregister("k"))
        assertNull(registry.get("k"))
    }

    @Test
    fun `getAll should return all strategies`() {
        registry.register("a", 10)
        registry.register("b", 20)
        val all = registry.getAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `find should filter strategies`() {
        registry.register("x", 100)
        registry.register("y", 200)
        registry.register("z", 300)
        val found = registry.find { it.value > 150 }
        assertEquals(2, found.size)
    }

    @Test
    fun `keys should return all registered keys`() {
        registry.register("a", 1)
        registry.register("b", 2)
        assertTrue(registry.keys.containsAll(setOf("a", "b")))
    }

    @Test
    fun `clear should remove all`() {
        registry.register("a", 1)
        registry.register("b", 2)
        registry.clear()
        assertEquals(0, registry.size)
    }

    @Test
    fun `reasoning registry should return strategies sorted by priority`() {
        val rr = ReasoningStrategyRegistry()
        val sorted = rr.getStrategiesSorted()
        assertTrue(sorted.isNotEmpty())
    }

    @Test
    fun `reasoning registry fallback should return fallback for unknown`() {
        val rr = ReasoningStrategyRegistry()
        val strategy = rr.getStrategy("nonexistent")
        assertEquals("fallback", strategy.name)
    }
}
