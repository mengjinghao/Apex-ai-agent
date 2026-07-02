package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import com.apex.util.stream.Stream
import com.apex.util.stream.asStream
import com.apex.util.stream.filter
import com.apex.util.stream.flatMap
import com.apex.util.stream.map
import com.apex.util.stream.rangeStream
import com.apex.util.stream.stream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 流变换器测试
 *
 * 验证 map、filter、flatMap 和 transform 链式操作。
 */
class StreamTransformerTest : BaseUnitTest {

    @Test
    fun `map should transform elements`() = runTest {
        val results = mutableListOf<String>()
        rangeStream(1, 3).map { it.toString() }.collect { results.add(it) }
        assertEquals(listOf("1", "2", "3"), results)
    }

    @Test
    fun `filter should keep matching elements`() = runTest {
        val results = mutableListOf<Int>()
        rangeStream(1, 5).filter { it % 2 == 0 }.collect { results.add(it) }
        assertEquals(listOf(2, 4), results)
    }

    @Test
    fun `flatMap should expand elements`() = runTest {
        val results = mutableListOf<Int>()
        rangeStream(1, 3).flatMap { n ->
            stream { emit(n); emit(n * 10) }
        }.collect { results.add(it) }
        assertEquals(listOf(1, 10, 2, 20, 3, 30), results)
    }

    @Test
    fun `chained transforms should compose`() = runTest {
        val results = mutableListOf<String>()
        rangeStream(1, 4)
            .filter { it > 1 }
            .map { "n=$it" }
            .collect { results.add(it) }
        assertEquals(listOf("n=2", "n=3", "n=4"), results)
    }

    @Test
    fun `empty stream should produce no results`() = runTest {
        val results = mutableListOf<Int>()
        listOf<Int>().asStream().filter { true }.collect { results.add(it) }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `chained flatMap and filter should work`() = runTest {
        val results = mutableListOf<Int>()
        rangeStream(1, 3)
            .flatMap { stream { emit(it); emit(it + 1) } }
            .filter { it > 2 }
            .collect { results.add(it) }
        assertEquals(listOf(3, 3, 4), results)
    }

    @Test
    fun `map with null values should be handled`() = runTest {
        val results = mutableListOf<String?>()
        listOf("a", "b").asStream().map { if (it == "a") null else it }
            .collect { results.add(it) }
        assertEquals(2, results.size)
        assertNull(results[0])
    }

    @Test
    fun `filter all should produce empty`() = runTest {
        val results = mutableListOf<Int>()
        rangeStream(1, 5).filter { false }.collect { results.add(it) }
        assertTrue(results.isEmpty())
    }
}
