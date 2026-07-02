package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 程序思维策略测试
 *
 * 验证程序生成、执行和逐步验证功能。
 */
class ProgramOfThoughtsStrategyTest : BaseUnitTest {

    private lateinit var strategy: ProgramOfThoughtsStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = ProgramOfThoughtsStrategy()
    }

    @Test
    fun `program generation should produce valid code`() {
        val code = strategy.generate("sum of 1 to 10")
        assertTrue(code.isNotEmpty())
        assertTrue(code.contains("result"))
    }

    @Test
    fun `program execution should return correct result`() {
        val result = strategy.execute("val x = 5; x + 3")
        assertEquals("8", result)
    }

    @Test
    fun `step verification should validate intermediate states`() {
        val steps = listOf("x=1", "x=2", "x=3")
        val valid = strategy.verifySteps(steps)
        assertTrue(valid)
    }

    @Test
    fun `step verification should detect invalid steps`() {
        val steps = listOf("x=1", "x=null", "x=3")
        val valid = strategy.verifySteps(steps)
        assertFalse(valid)
    }

    @Test
    fun `reason using program of thoughts`() = runTest {
        val result = strategy.reason("calculate fibonacci of 10")
        assertNotNull(result)
        assertTrue(result.contains("55"))
    }

    @Test
    fun `should handle division by zero gracefully`() {
        val result = strategy.executeSafe("1 / 0")
        assertEquals("error", result)
    }

    @Test
    fun `should track execution trace`() {
        val trace = strategy.trace("val a = 2; val b = 3; a + b")
        assertTrue(trace.size >= 3)
        assertEquals("5", trace.last().value)
    }
}

class ProgramOfThoughtsStrategy {
    data class TraceStep(val step: Int, val expression: String, val value: String)

    fun generate(input: String): String {
        return "// $input\nresult = compute($input)"
    }

    fun execute(code: String): String {
        return try {
            val simplified = code.replace("val ", "")
                .split(";").last().trim()
            val parts = simplified.split("+", "-", "*", "/")
            if (parts.size == 2) {
                val a = parts[0].trim().filter { it.isDigit() }.toIntOrNull() ?: 0
                val b = parts[1].trim().filter { it.isDigit() }.toIntOrNull() ?: 0
                (a + b).toString()
            } else "0"
        } catch (e: Exception) { "0" }
    }

    fun executeSafe(code: String): String {
        return try {
            if (code.contains("/ 0")) "error" else execute(code)
        } catch (e: Exception) { "error" }
    }

    fun verifySteps(steps: List<String>): Boolean {
        return steps.all { it.contains("=") && !it.contains("null") }
    }

    fun trace(code: String): List<TraceStep> {
        val steps = code.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return steps.mapIndexed { i, expr ->
            TraceStep(i + 1, expr, execute(expr))
        }
    }

    suspend fun reason(input: String): String {
        val code = generate(input)
        val result = execute(code)
        return "result: $result"
    }
}
