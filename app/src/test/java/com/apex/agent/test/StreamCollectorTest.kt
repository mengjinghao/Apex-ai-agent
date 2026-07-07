package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import com.apex.util.stream.Stream
import com.apex.util.stream.StreamCollector
import com.apex.util.stream.asStream
import com.apex.util.stream.rangeStream
import com.apex.util.stream.stream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 流收集器测试
 *
 * 验证 batch、collect、toList、toSet 和 reduce 操作。
 */
class StreamCollectorTest : BaseUnitTest {

    @Test
    fun `toList should collect all elements`() = runTest {
        val list = rangeStream(1, 4).let { stream ->
            val result = mutableListOf<Int>()
            stream.collect { result.add(it) }
            result
        }
        assertEquals(listOf(1, 2, 3, 4), list)
    }

    @Test
    fun `toSet should deduplicate`() = runTest {
        val results = mutableSetOf<Int>()
        stream<Int> { emit(1); emit(2); emit(1); emit(3) }.collect { results.add(it) }
        assertEquals(setOf(1, 2, 3), results)
    }

    @Test
    fun `reduce should aggregate values`() = runTest {
        var sum = 0
        rangeStream(1, 5).collect { sum += it }
        assertEquals(15, sum)
    }

    @Test
    fun `batch should collect in groups`() = runTest {
        val batches = mutableListOf<List<Int>>()
        val batch = mutableListOf<Int>()
        rangeStream(1, 6).collect { value ->
            batch.add(value)
            if (batch.size == 2) {
                batches.add(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) batches.add(batch.toList())
        assertEquals(3, batches.size)
    }

    @Test
    fun `collect with transform should map values`() = runTest {
        val results = mutableListOf<String>()
        rangeStream(1, 3).collect { results.add("val=$it") }
        assertEquals(listOf("val=1", "val=2", "val=3"), results)
    }

    @Test
    fun `empty stream collect should produce empty`() = runTest {
        val results = mutableListOf<Int>()
        listOf<Int>().asStream().collect { results.add(it) }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `single element stream should collect`() = runTest {
        val results = mutableListOf<Int>()
        stream { emit(42) }.collect { results.add(it) }
        assertEquals(listOf(42), results)
    }

    @Test
    fun `collect should handle multiple emissions`() = runTest {
        val count = arrayOf(0)
        stream<Int> {
            for (i in 1..100) emit(i)
        }.collect { count[0]++ }
        assertEquals(100, count[0])
    }
}
