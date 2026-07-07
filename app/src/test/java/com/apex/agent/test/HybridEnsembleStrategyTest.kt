package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 混合集成策略测试
 *
 * 验证多策略组合、加权投票和集成结果聚合。
 */
class HybridEnsembleStrategyTest : BaseUnitTest {

    private lateinit var strategy: HybridEnsembleStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = HybridEnsembleStrategy()
    }

    @Test
    fun `should add multiple strategies`() {
        strategy.addStrategy("chain_of_thought") { "result A" }
        strategy.addStrategy("tree_of_thought") { "result B" }
        assertEquals(2, strategy.strategyCount())
    }

    @Test
    fun `weighted voting should favor higher weights`() {
        strategy.addStrategy("fast", weight = 1.0) { "quick" }
        strategy.addStrategy("accurate", weight = 3.0) { "slow" }
        val result = strategy.vote("solve problem")
        assertEquals("slow", result)
    }

    @Test
    fun `should aggregate results`() {
        strategy.addStrategy("s1") { "outcome1" }
        strategy.addStrategy("s2") { "outcome2" }
        val aggregated = strategy.aggregate("test")
        assertTrue(aggregated.contains("outcome1"))
    }

    @Test
    fun `should handle strategy failure gracefully`() {
        strategy.addStrategy("failing") { throw RuntimeException("fail") }
        strategy.addStrategy("working") { "success" }
        val result = strategy.safeReason("input")
        assertEquals("success", result)
    }

    @Test
    fun `reason using ensemble`() = runTest {
        strategy.addStrategy("s1") { "fast_path" }
        strategy.addStrategy("s2") { "thorough_path" }
        val result = strategy.reason("analyze data")
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `should compute confidence score`() {
        strategy.addStrategy("a", weight = 2.0) { "same" }
        strategy.addStrategy("b", weight = 1.0) { "same" }
        val confidence = strategy.confidence("same")
        assertTrue(confidence in 0.0..1.0)
    }

    @Test
    fun `should remove strategy`() {
        strategy.addStrategy("temp") { "x" }
        assertTrue(strategy.removeStrategy("temp"))
        assertEquals(0, strategy.strategyCount())
    }
}

class HybridEnsembleStrategy {
    private data class StrategyEntry(val name: String, val weight: Double, val executor: () -> String)

    private val strategies = mutableListOf<StrategyEntry>()

    fun addStrategy(name: String, weight: Double = 1.0, executor: () -> String) {
        strategies.add(StrategyEntry(name, weight, executor))
    }

    fun removeStrategy(name: String): Boolean {
        return strategies.removeAll { it.name == name }
    }

    fun strategyCount(): Int = strategies.size

    fun vote(input: String): String {
        return strategies.maxByOrNull { it.weight }?.executor?.invoke() ?: ""
    }

    fun aggregate(input: String): List<String> {
        return strategies.map { it.executor() }
    }

    fun safeReason(input: String): String {
        for (s in strategies) {
            try { return s.executor() } catch (_: Exception) { continue }
        }
        return ""
    }

    fun confidence(expected: String): Double {
        if (strategies.isEmpty()) return 0.0
        val matching = strategies.count { it.executor() == expected }
        return matching.toDouble() / strategies.size
    }

    suspend fun reason(input: String): String {
        return "ensemble: ${aggregate(input).joinToString(", ")}"
    }
}
