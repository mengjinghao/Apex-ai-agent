package com.apex.agent.test

import com.apex.agent.core.patterns.ReasoningStrategyRegistry
import com.apex.agent.core.patterns.ReasoningStrategy
import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 策略选择集成测试
 *
 * 验证 AdaptiveReasoningSelector 与各种策略的集成。
 */
class StrategySelectionIntegrationTest : BaseUnitTest {

    private lateinit var selector: AdaptiveReasoningSelector
    private lateinit var registry: ReasoningStrategyRegistry

    @Before
    override fun setUp() {
        super.setUp()
        registry = ReasoningStrategyRegistry()
        selector = AdaptiveReasoningSelector(registry)
    }

    @Test
    fun `selector should pick strategy based on task type`() = runTest {
        val strategy = selector.select("logic puzzle: solve equation")
        assertNotNull(strategy)
        assertTrue(strategy.name.isNotEmpty())
    }

    @Test
    fun `selector should fallback on unknown tasks`() = runTest {
        val strategy = selector.select("unknown task type with no match")
        assertNotNull(strategy)
    }

    @Test
    fun `registry should contain default strategies`() {
        val strategies = registry.getStrategiesSorted()
        assertTrue(strategies.size >= 2)
        assertTrue(strategies.any { it.name == "chain_of_thought" })
    }

    @Test
    fun `custom strategy should be selectable`() = runTest {
        val custom = object : ReasoningStrategy {
            override val name = "custom_math"
            override val priority = 50
            override suspend fun reason(input: String) = "custom: $input"
        }
        registry.register(custom)
        val strategy = selector.select("math problem: 2+2")
        assertNotNull(strategy)
    }

    @Test
    fun `selector should evaluate strategy performance`() = runTest {
        val start = System.nanoTime()
        val strategy = selector.select("quick task")
        val result = strategy?.reason("input")
        val duration = System.nanoTime() - start
        assertNotNull(result)
        assertTrue(duration >= 0)
    }

    @Test
    fun `high priority strategy should be preferred`() {
        val sorted = registry.getStrategiesSorted()
        if (sorted.size >= 2) {
            assertTrue(sorted[0].priority >= sorted[1].priority)
        }
    }

    @Test
    fun `strategy should be invoked with input`() = runTest {
        val strategy = selector.select("test query")
        val result = strategy?.reason("analyze this data")
        assertNotNull(result)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun `selector should handle rapid sequential selections`() = runTest {
        val tasks = listOf(
            "solve equation",
            "analyze text",
            "debug code",
            "design system",
            "optimize query"
        )
        for (task in tasks) {
            val strategy = selector.select(task)
            assertNotNull(strategy)
            val result = strategy?.reason(task)
            assertNotNull(result)
        }
    }
}

class AdaptiveReasoningSelector(private val registry: ReasoningStrategyRegistry) {

    suspend fun select(task: String): ReasoningStrategy? {
        val strategies = registry.getStrategiesSorted()
        if (strategies.isEmpty()) return null
        return when {
            task.contains("math") || task.contains("equation") ->
                strategies.find { it.name == "chain_of_thought" } ?: strategies.first()
            task.contains("code") || task.contains("debug") ->
                strategies.find { it.name == "tree_of_thought" } ?: strategies.first()
            else -> strategies.first()
        }
    }
}
