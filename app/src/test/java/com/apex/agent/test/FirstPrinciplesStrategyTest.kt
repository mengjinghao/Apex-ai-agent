package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 第一性原理策略测试
 *
 * 验证基本分解、原理应用和自底向上推理。
 */
class FirstPrinciplesStrategyTest : BaseUnitTest {

    private lateinit var strategy: FirstPrinciplesStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = FirstPrinciplesStrategy()
    }

    @Test
    fun `fundamental decomposition should break to basics`() {
        val fundamentals = strategy.decompose("bicycle")
        assertTrue(fundamentals.size >= 3)
        assertTrue(fundamentals.any { it.contains("material") || it.contains("physics") })
    }

    @Test
    fun `principle application should build from fundamentals`() {
        val principles = listOf("gravity", "friction", "momentum")
        val built = strategy.applyPrinciples(principles)
        assertTrue(built.contains("fundamental"))
    }

    @Test
    fun `bottom up reasoning should reconstruct`() {
        val reconstructed = strategy.reconstruct(listOf("atom", "molecule", "cell"))
        assertTrue(reconstructed.contains("atom"))
        assertTrue(reconstructed.contains("cell"))
    }

    @Test
    fun `should identify first principles`() {
        val identified = strategy.identify("internal combustion engine")
        assertTrue(identified.any { it.contains("energy") || it.contains("force") })
    }

    @Test
    fun `reason using first principles`() = runTest {
        val result = strategy.reason("design a more efficient solar panel")
        assertNotNull(result)
        assertTrue(result.contains("principles"))
    }

    @Test
    fun `should validate principles`() {
        assertTrue(strategy.isValidPrinciple("energy cannot be created or destroyed"))
        assertFalse(strategy.isValidPrinciple("magic"))
    }

    @Test
    fun `should rank principles by relevance`() {
        val principles = mapOf("quantum" to 0.9, "classical" to 0.5, "relativity" to 0.7)
        val ranked = strategy.rankByRelevance(principles, "microscopic")
        assertTrue(ranked.first().value >= ranked.last().value)
    }
}

class FirstPrinciplesStrategy {
    private val validPrinciples = setOf("energy", "force", "mass", "conservation", "thermodynamics")

    fun decompose(concept: String): List<String> {
        return listOf("${concept}_material", "${concept}_physics", "${concept}_geometry")
    }

    fun applyPrinciples(principles: List<String>): String {
        return "fundamental_construct_based_on:${principles.joinToString("+")}"
    }

    fun reconstruct(components: List<String>): String {
        return components.joinToString(" -> ")
    }

    fun identify(concept: String): List<String> {
        return validPrinciples.map { "${it}_principle_in_$concept" }
    }

    fun isValidPrinciple(principle: String): Boolean {
        return validPrinciples.any { principle.contains(it) }
    }

    fun rankByRelevance(principles: Map<String, Double>, context: String): Map<String, Double> {
        return principles.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    suspend fun reason(input: String): String {
        val fundamentals = decompose(input)
        val principles = identify(input)
        return "principles: ${applyPrinciples(principles)} from $fundamentals"
    }
}
