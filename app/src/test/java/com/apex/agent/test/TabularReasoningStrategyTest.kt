package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 表格推理策略测试
 *
 * 验证表格创建、单元格分析和比较评估功能。
 */
class TabularReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: TabularReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = TabularReasoningStrategy()
    }

    @Test
    fun `table creation should produce correct structure`() {
        val table = strategy.createTable(listOf("Name", "Value"), listOf("A", "B"))
        assertEquals(2, table.rows.size)
        assertEquals(2, table.columns.size)
    }

    @Test
    fun `cell analysis should extract value`() {
        val table = strategy.createTable(listOf("Item", "Score"), listOf("X", "95"))
        val score = strategy.analyzeCell(table, 1, 1)
        assertEquals("95", score)
    }

    @Test
    fun `comparative evaluation should rank rows`() {
        val table = strategy.createTable(listOf("Name", "Score"), listOf("A", "80"), listOf("B", "90"))
        val ranked = strategy.evaluate(table, "Score")
        assertEquals("B", ranked.first())
    }

    @Test
    fun `should handle empty table`() {
        val table = strategy.createTable(emptyList())
        assertEquals(0, table.rows.size)
    }

    @Test
    fun `reason using tabular reasoning`() = runTest {
        val result = strategy.reason("compare products")
        assertNotNull(result)
        assertTrue(result.contains("table"))
    }

    @Test
    fun `should filter rows by condition`() {
        val table = strategy.createTable(listOf("Name", "Pass"), listOf("A", "true"), listOf("B", "false"))
        val filtered = strategy.filter(table, "Pass", "true")
        assertEquals(1, filtered.rows.size)
    }

    @Test
    fun `should compute column statistics`() {
        val table = strategy.createTable(listOf("X"), listOf("10"), listOf("20"), listOf("30"))
        val avg = strategy.columnAverage(table, "X")
        assertEquals(20.0, avg, 0.001)
    }
}

data class Table(val columns: List<String>, val rows: List<Map<String, String>>)

class TabularReasoningStrategy {
    fun createTable(columns: List<String>, vararg data: List<String>): Table {
        val rows = data.map { row -> columns.zip(row).toMap() }
        return Table(columns, rows)
    }

    fun analyzeCell(table: Table, row: Int, col: Int): String? {
        return table.rows.getOrNull(row)?.values?.elementAtOrNull(col)
    }

    fun evaluate(table: Table, column: String): List<String> {
        val sorted = table.rows.sortedByDescending { it[column]?.toDoubleOrNull() ?: 0.0 }
        return sorted.mapNotNull { it[table.columns.first()] }
    }

    fun filter(table: Table, column: String, value: String): Table {
        val filtered = table.rows.filter { it[column] == value }
        return Table(table.columns, filtered)
    }

    fun columnAverage(table: Table, column: String): Double {
        val values = table.rows.mapNotNull { it[column]?.toDoubleOrNull() }
        return if (values.isEmpty()) 0.0 else values.average()
    }

    suspend fun reason(input: String): String {
        return "table: tabular analysis of $input"
    }
}
