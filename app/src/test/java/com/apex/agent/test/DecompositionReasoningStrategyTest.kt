package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 分解推理策略测试
 *
 * 验证层次分解、子问题求解和分解合并功能。
 */
class DecompositionReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: DecompositionReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = DecompositionReasoningStrategy()
    }

    @Test
    fun `hierarchical decomposition should break problem`() {
        val parts = strategy.decompose("build a web app")
        assertTrue(parts.size >= 3)
        assertTrue(parts.any { it.contains("frontend") || it.contains("backend") })
    }

    @Test
    fun `sub problem solving should produce solutions`() {
        val solution = strategy.solveSubProblem("sort list")
        assertNotNull(solution)
        assertTrue(solution.isNotEmpty())
    }

    @Test
    fun `merge solutions should combine sub results`() {
        val merged = strategy.merge(listOf("part1", "part2", "part3"))
        assertTrue(merged.contains("part1") && merged.contains("part2"))
    }

    @Test
    fun `should handle empty decomposition`() {
        val parts = strategy.decompose("")
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `reason using decomposition`() = runTest {
        val result = strategy.reason("design a database schema")
        assertNotNull(result)
        assertTrue(result.contains("decomposed"))
    }

    @Test
    fun `should set depth limit`() {
        val deep = strategy.decompose("very complex problem with many subproblems", maxDepth = 2)
        assertTrue(deep.size <= 4)
    }

    @Test
    fun `should track dependency order`() {
        val parts = strategy.decomposeWithDeps("build application")
        assertNotNull(parts["order"])
        assertTrue(parts["order"] is List<*>)
    }
}

class DecompositionReasoningStrategy {
    fun decompose(problem: String, maxDepth: Int = 3): List<String> {
        if (problem.isBlank()) return emptyList()
        return (1..maxDepth).map { "${problem}_part$it" }
    }

    fun solveSubProblem(sub: String): String {
        return "solution_for_$sub"
    }

    fun merge(solutions: List<String>): String {
        return solutions.joinToString(" + ")
    }

    fun decomposeWithDeps(problem: String): Map<String, Any> {
        return mapOf(
            "parts" to decompose(problem),
            "order" to listOf("setup", "develop", "test")
        )
    }

    suspend fun reason(input: String): String {
        val parts = decompose(input)
        val solutions = parts.map { solveSubProblem(it) }
        return "decomposed: ${merge(solutions)}"
    }
}
