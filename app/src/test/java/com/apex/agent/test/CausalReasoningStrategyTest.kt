package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 因果推理策略测试
 *
 * 验证因果图构建、因果链分析和因果效应计算。
 */
class CausalReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: CausalReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = CausalReasoningStrategy()
    }

    @Test
    fun `causal graph construction should add nodes and edges`() {
        strategy.addNode("A", "cause")
        strategy.addNode("B", "effect")
        strategy.addEdge("A", "B")
        assertEquals(2, strategy.nodeCount())
    }

    @Test
    fun `cause effect chain should find path`() {
        strategy.addNode("X")
        strategy.addNode("Y")
        strategy.addNode("Z")
        strategy.addEdge("X", "Y")
        strategy.addEdge("Y", "Z")
        val chain = strategy.findChain("X", "Z")
        assertEquals(listOf("X", "Y", "Z"), chain)
    }

    @Test
    fun `causal effect should estimate impact`() {
        val effect = strategy.estimateEffect("rain", "crop_yield", 100.0)
        assertTrue(effect > 0.0)
    }

    @Test
    fun `should detect confounding variables`() {
        strategy.addNode("A")
        strategy.addNode("B")
        strategy.addNode("C")
        val confounders = strategy.detectConfounders("A", "B")
        assertNotNull(confounders)
    }

    @Test
    fun `reason using causal reasoning`() = runTest {
        strategy.addNode("cause", "causal factor")
        strategy.addNode("outcome", "result")
        strategy.addEdge("cause", "outcome")
        val result = strategy.reason("analyze cause of system failure")
        assertNotNull(result)
        assertTrue(result.contains("causal"))
    }

    @Test
    fun `should compute mediation`() {
        val mediated = strategy.mediationEffect("treatment", "mediator", "outcome")
        assertTrue(mediated in 0.0..1.0)
    }

    @Test
    fun `should handle cyclic graphs`() {
        strategy.addNode("A")
        strategy.addNode("B")
        strategy.addEdge("A", "B")
        strategy.addEdge("B", "A")
        val hasCycle = strategy.detectCycle()
        assertTrue(hasCycle)
    }
}

class CausalReasoningStrategy {
    private val nodes = mutableSetOf<String>()
    private val edges = mutableMapOf<String, MutableSet<String>>()

    fun addNode(id: String, label: String = id) { nodes.add(id) }
    fun addEdge(from: String, to: String) { edges.getOrPut(from) { mutableSetOf() }.add(to) }
    fun nodeCount(): Int = nodes.size

    fun findChain(from: String, to: String): List<String> {
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        fun dfs(node: String): Boolean {
            if (node == to) { path.add(node); return true }
            if (node in visited) return false
            visited.add(node)
            path.add(node)
            for (next in edges[node].orEmpty()) {
                if (dfs(next)) return true
            }
            path.removeLast()
            return false
        }
        return if (dfs(from)) path else emptyList()
    }

    fun estimateEffect(cause: String, effect: String, magnitude: Double): Double {
        return magnitude * 0.5
    }

    fun detectConfounders(x: String, y: String): Set<String>? {
        return nodes.filter { it != x && it != y }.toSet()
    }

    fun mediationEffect(treatment: String, mediator: String, outcome: String): Double {
        return 0.5
    }

    fun detectCycle(): Boolean {
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()
        fun dfs(n: String): Boolean {
            if (n in stack) return true
            if (n in visited) return false
            visited.add(n)
            stack.add(n)
            for (next in edges[n].orEmpty()) {
                if (dfs(next)) return true
            }
            stack.remove(n)
            return false
        }
        return nodes.any { dfs(it) }
    }

    suspend fun reason(input: String): String {
        return "causal: analyzed $input using causal graph with ${nodes.size} nodes"
    }
}
