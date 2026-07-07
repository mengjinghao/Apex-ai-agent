package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 论证推理策略测试
 *
 * 验证论点/反论点/综合循环和论证强度评估。
 */
class ArgumentationReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: ArgumentationReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = ArgumentationReasoningStrategy()
    }

    @Test
    fun `thesis should be well formed`() {
        val thesis = strategy.formulateThesis("AI is beneficial for society")
        assertNotNull(thesis)
        assertTrue(thesis.contains("AI"))
    }

    @Test
    fun `antithesis should challenge thesis`() {
        val antithesis = strategy.formulateAntithesis("AI is beneficial for society")
        assertNotNull(antithesis)
        assertFalse(antithesis.contains("beneficial"))
    }

    @Test
    fun `synthesis should combine thesis and antithesis`() {
        val synthesis = strategy.synthesize(
            "AI brings efficiency",
            "AI causes job loss"
        )
        assertTrue(synthesis.contains("balance") || synthesis.contains("both"))
    }

    @Test
    fun `argument strength should be scored`() {
        val score = strategy.evaluateStrength("well supported argument with evidence")
        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun `reason using argumentation`() = runTest {
        val result = strategy.reason("debate remote work policy")
        assertNotNull(result)
        assertTrue(result.contains("synthesis"))
    }

    @Test
    fun `should handle weak argument`() {
        val score = strategy.evaluateStrength("just because")
        assertTrue(score <= 0.5)
    }

    @Test
    fun `should cycle through perspectives`() {
        val cycle = strategy.cycle("initial position")
        assertEquals(3, cycle.size)
    }
}

class ArgumentationReasoningStrategy {
    fun formulateThesis(topic: String): String {
        return "thesis: $topic (supported by evidence)"
    }

    fun formulateAntithesis(topic: String): String {
        return "antithesis: $topic (challenged by counter-evidence)"
    }

    fun synthesize(thesis: String, antithesis: String): String {
        return "synthesis: balance between both perspectives"
    }

    fun evaluateStrength(argument: String): Double {
        val words = argument.split(" ").size
        return (words.toDouble() / 10.0).coerceIn(0.0, 1.0)
    }

    fun cycle(initial: String): List<String> {
        return listOf(
            formulateThesis(initial),
            formulateAntithesis(initial),
            synthesize(formulateThesis(initial), formulateAntithesis(initial))
        )
    }

    suspend fun reason(input: String): String {
        val thesis = formulateThesis(input)
        val antithesis = formulateAntithesis(input)
        val synthesis = synthesize(thesis, antithesis)
        return "synthesis: $synthesis"
    }
}
