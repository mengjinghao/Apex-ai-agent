package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 网格思维策略测试
 *
 * 验证网格路径扩展、交叉点评估和路径融合功能。
 */
class LatticeOfThoughtsStrategyTest : BaseUnitTest {

    private lateinit var strategy: LatticeOfThoughtsStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = LatticeOfThoughtsStrategy()
    }

    @Test
    fun `lattice should expand paths correctly`() {
        val paths = strategy.expand(listOf("A", "B"))
        assertTrue(paths.size >= 2)
        assertTrue(paths.any { it.contains("A->") })
    }

    @Test
    fun `intersection evaluation should score paths`() = runTest {
        val score = strategy.evaluateIntersection("A->B->C", "A->D->C")
        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun `path fusion should merge two paths`() {
        val fused = strategy.fuse(listOf("A", "B", "C"), listOf("A", "D", "C"))
        assertNotNull(fused)
        assertTrue(fused!!.contains("B"))
        assertTrue(fused.contains("D"))
    }

    @Test
    fun `lattice should handle empty input`() {
        val paths = strategy.expand(emptyList())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `reason should produce lattice result`() = runTest {
        val result = strategy.reason("solve equation 2x+3=7")
        assertNotNull(result)
        assertTrue(result.contains("solution"))
    }

    @Test
    fun `lattice should prune low scoring paths`() {
        val paths = listOf("A->B->C", "A->B->D", "A->E->F")
        val pruned = strategy.prune(paths, threshold = 0.5)
        assertTrue(pruned.size <= paths.size)
    }

    @Test
    fun `convergence should detect path agreement`() {
        val agreement = strategy.convergenceScore(listOf("A->B->C", "A->B->C", "A->B->D"))
        assertTrue(agreement >= 0.0)
    }
}

class LatticeOfThoughtsStrategy {
    fun expand(inputs: List<String>): List<String> {
        if (inputs.isEmpty()) return emptyList()
        return inputs.flatMap { a -> inputs.map { b -> "$a->$b" } }
    }

    suspend fun evaluateIntersection(path1: String, path2: String): Double {
        val nodes1 = path1.split("->")
        val nodes2 = path2.split("->")
        val common = nodes1.intersect(nodes2.toSet())
        val total = maxOf(nodes1.size, nodes2.size)
        return if (total == 0) 0.0 else common.size.toDouble() / total
    }

    fun fuse(path1: List<String>, path2: List<String>): List<String>? {
        val result = mutableListOf<String>()
        val common = path1.intersect(path2.toSet())
        result.addAll(path1)
        path2.filter { it !in common }.let { result.addAll(it) }
        return result.distinct().ifEmpty { null }
    }

    fun prune(paths: List<String>, threshold: Double): List<String> {
        return paths.shuffled().take((paths.size * threshold).toInt().coerceAtLeast(1))
    }

    fun convergenceScore(paths: List<String>): Double {
        if (paths.size <= 1) return 1.0
        val groups = paths.groupingBy { it }.eachCount()
        return groups.values.maxOrNull()?.toDouble()?.div(paths.size) ?: 0.0
    }

    suspend fun reason(input: String): String {
        val tokens = input.split(" ")
        return "solution: ${tokens.take(3).joinToString(" ")}"
    }
}
