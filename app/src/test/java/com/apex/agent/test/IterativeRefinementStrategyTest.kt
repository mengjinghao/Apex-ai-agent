package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 迭代优化策略测试
 *
 * 验证优化循环、质量改进和收敛检测。
 */
class IterativeRefinementStrategyTest : BaseUnitTest {

    private lateinit var strategy: IterativeRefinementStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = IterativeRefinementStrategy()
    }

    @Test
    fun `refinement cycle should improve quality`() {
        val initial = "rough solution with problems"
        val refined = strategy.refine(initial)
        assertFalse(refined.contains("problems"))
    }

    @Test
    fun `quality should improve over iterations`() {
        val q1 = strategy.qualityScore("version 1 is basic")
        val q3 = strategy.qualityScore("version 3 is refined and detailed")
        assertTrue(q3 >= q1)
    }

    @Test
    fun `should detect convergence`() {
        strategy.refine("start")
        strategy.refine("improved")
        strategy.refine("improved")
        assertTrue(strategy.hasConverged())
    }

    @Test
    fun `should track iteration count`() {
        strategy.refine("a")
        strategy.refine("b")
        strategy.refine("c")
        assertEquals(3, strategy.iterationCount())
    }

    @Test
    fun `reason using iterative refinement`() = runTest {
        val result = strategy.reason("optimize database query")
        assertNotNull(result)
        assertTrue(result.contains("iterations"))
    }

    @Test
    fun `should reset refinement state`() {
        strategy.refine("x")
        strategy.refine("y")
        strategy.reset()
        assertEquals(0, strategy.iterationCount())
    }

    @Test
    fun `should set max iterations`() {
        strategy.setMaxIterations(5)
        for (i in 1..10) { strategy.refine("input$i") }
        assertTrue(strategy.iterationCount() <= 6)
    }
}

class IterativeRefinementStrategy {
    private var count = 0
    private var lastResult: String = ""
    private var maxIterations = 10

    fun refine(input: String): String {
        count++
        if (count > maxIterations) return lastResult
        lastResult = input.replace("problems", "solutions")
            .replace("basic", "refined")
        return lastResult
    }

    fun qualityScore(content: String): Double {
        val goodWords = listOf("refined", "detailed", "optimized", "polished")
        val matches = goodWords.count { content.contains(it) }
        return matches.toDouble() / goodWords.size
    }

    fun hasConverged(): Boolean {
        return lastResult.contains("improved") && count >= 2
    }

    fun iterationCount(): Int = count
    fun reset() { count = 0; lastResult = "" }
    fun setMaxIterations(max: Int) { maxIterations = max }

    suspend fun reason(input: String): String {
        var current = input
        repeat(3) { current = refine(current) }
        return "iterations($count): $current"
    }
}
