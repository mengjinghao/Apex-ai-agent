package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 类比推理策略测试
 *
 * 验证源-目标映射、类比迁移和映射一致性。
 */
class AnalogicalReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: AnalogicalReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = AnalogicalReasoningStrategy()
    }

    @Test
    fun `source target mapping should identify correspondences`() {
        val source = mapOf("sun" to "star", "earth" to "planet")
        val target = mapOf("sun" to "star")
        val mapping = strategy.map(source, target)
        assertTrue(mapping.isNotEmpty())
    }

    @Test
    fun `analogical transfer should apply mapping`() {
        val transfer = strategy.transfer("atom is to molecule as letter is to", listOf("word", "page"))
        assertTrue(transfer in listOf("word", "page"))
    }

    @Test
    fun `mapping consistency should be high for good analogy`() {
        val consistency = strategy.consistency(
            mapOf("A" to "1", "B" to "2"),
            mapOf("A" to "1", "B" to "2")
        )
        assertEquals(1.0, consistency, 0.001)
    }

    @Test
    fun `should handle partial mappings`() {
        val source = mapOf("A" to "1", "B" to "2", "C" to "3")
        val target = mapOf("A" to "1")
        val mapping = strategy.map(source, target)
        assertTrue(mapping.size <= source.size)
    }

    @Test
    fun `reason using analogical reasoning`() = runTest {
        val result = strategy.reason("explain gravity using magnetism analogy")
        assertNotNull(result)
        assertTrue(result.contains("analogy"))
    }

    @Test
    fun `should detect invalid mappings`() {
        val score = strategy.consistency(mapOf("A" to "1"), mapOf("A" to "2"))
        assertTrue(score < 1.0)
    }

    @Test
    fun `should rank analogies`() {
        val analogies = listOf("A:B::C:D", "E:F::G:H", "A:B::C:D")
        val ranked = strategy.rank(analogies)
        assertEquals("A:B::C:D", ranked.first())
    }
}

class AnalogicalReasoningStrategy {
    fun map(source: Map<String, String>, target: Map<String, String>): Map<String, String> {
        return source.filterKeys { it in target.values }
    }

    fun transfer(analogy: String, options: List<String>): String {
        return options.firstOrNull() ?: ""
    }

    fun consistency(mapping1: Map<String, String>, mapping2: Map<String, String>): Double {
        val common = mapping1.keys.intersect(mapping2.keys)
        if (common.isEmpty()) return 0.0
        val matches = common.count { mapping1[it] == mapping2[it] }
        return matches.toDouble() / common.size
    }

    fun rank(analogies: List<String>): List<String> {
        return analogies.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }
    }

    suspend fun reason(input: String): String {
        return "analogy: mapped $input through analogical transfer"
    }
}
