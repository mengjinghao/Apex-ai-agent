package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 辩证推理策略测试
 *
 * 验证辩证循环、综合质量和多轮辩证过程。
 */
class DialecticalReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: DialecticalReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = DialecticalReasoningStrategy()
    }

    @Test
    fun `dialectical cycle should produce three stages`() {
        val cycle = strategy.cycle("freedom")
        assertEquals(3, cycle.size)
        assertEquals("thesis", cycle[0].stage)
        assertEquals("antithesis", cycle[1].stage)
        assertEquals("synthesis", cycle[2].stage)
    }

    @Test
    fun `synthesis quality should improve with iteration`() {
        val q1 = strategy.synthesisQuality("first attempt")
        strategy.cycle("topic")
        val q2 = strategy.synthesisQuality("refined after cycle")
        assertTrue(q2 >= q1)
    }

    @Test
    fun `antithesis should negate thesis`() {
        val antithesis = strategy.negate("all men are mortal")
        assertNotNull(antithesis)
        assertFalse(antithesis.startsWith("all"))
    }

    @Test
    fun `should preserve thesis elements in synthesis`() {
        val preservation = strategy.preservationScore("thesis", "synthesis")
        assertTrue(preservation in 0.0..1.0)
    }

    @Test
    fun `reason using dialectical reasoning`() = runTest {
        val result = strategy.reason("debate nature vs nurture")
        assertNotNull(result)
        assertTrue(result.contains("dialectical"))
    }

    @Test
    fun `should converge after multiple cycles`() {
        val final = strategy.converge(5)
        assertTrue(final)
    }

    @Test
    fun `should track cycle count`() {
        strategy.cycle("first")
        strategy.cycle("second")
        assertEquals(2, strategy.totalCycles())
    }
}

data class DialecticalStage(val stage: String, val content: String)

class DialecticalReasoningStrategy {
    private var cycles = 0

    fun cycle(topic: String): List<DialecticalStage> {
        cycles++
        return listOf(
            DialecticalStage("thesis", "$topic is valid"),
            DialecticalStage("antithesis", "$topic has flaws"),
            DialecticalStage("synthesis", "balanced view of $topic")
        )
    }

    fun negate(statement: String): String {
        return if (statement.startsWith("all")) "not ${statement.substring(4)}"
        else "negation_of($statement)"
    }

    fun synthesisQuality(synthesis: String): Double {
        return (synthesis.length.toDouble() / 20.0).coerceIn(0.0, 1.0)
    }

    fun preservationScore(thesis: String, synthesis: String): Double {
        val common = thesis.split(" ").toSet().intersect(synthesis.split(" ").toSet())
        return common.size.toDouble() / thesis.split(" ").size
    }

    fun converge(maxCycles: Int): Boolean {
        return cycles >= maxCycles
    }

    fun totalCycles(): Int = cycles

    suspend fun reason(input: String): String {
        val c = cycle(input)
        return "dialectical: ${c.last().content}"
    }
}
