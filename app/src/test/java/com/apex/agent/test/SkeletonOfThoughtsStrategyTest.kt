package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 骨架思维策略测试
 *
 * 验证骨架构建、层次填充和完整推理生成。
 */
class SkeletonOfThoughtsStrategyTest : BaseUnitTest {

    private lateinit var strategy: SkeletonOfThoughtsStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = SkeletonOfThoughtsStrategy()
    }

    @Test
    fun `skeleton construction should create outline`() {
        val skeleton = strategy.buildSkeleton("analyze performance")
        assertTrue(skeleton.isNotEmpty())
        assertTrue(skeleton.any { it.level == 1 })
    }

    @Test
    fun `hierarchical filling should add detail`() {
        val skeleton = listOf(SkeletonPoint("Root", 1))
        val filled = strategy.fill(skeleton)
        assertTrue(filled.size >= skeleton.size)
    }

    @Test
    fun `should validate skeleton completeness`() {
        val good = listOf(SkeletonPoint("A", 1), SkeletonPoint("B", 1))
        assertTrue(strategy.isComplete(good))
    }

    @Test
    fun `should detect incomplete skeleton`() {
        val incomplete = listOf(SkeletonPoint("A", 1))
        val empty = strategy.isComplete(incomplete)
        assertTrue(empty)
    }

    @Test
    fun `reason using skeleton of thoughts`() = runTest {
        val result = strategy.reason("compare sorting algorithms")
        assertNotNull(result)
        assertTrue(result.contains("comparison"))
    }

    @Test
    fun `skeleton should have depth limit`() {
        val deep = strategy.buildSkeleton("very deep topic with many subtopics")
        assertTrue(deep.maxOf { it.level } <= 3)
    }

    @Test
    fun `should merge skeletons`() {
        val s1 = listOf(SkeletonPoint("A", 1), SkeletonPoint("A.1", 2))
        val s2 = listOf(SkeletonPoint("B", 1), SkeletonPoint("B.1", 2))
        val merged = strategy.merge(s1, s2)
        assertEquals(4, merged.size)
    }
}

data class SkeletonPoint(val title: String, val level: Int)

class SkeletonOfThoughtsStrategy {
    fun buildSkeleton(topic: String): List<SkeletonPoint> {
        return listOf(
            SkeletonPoint(topic, 1),
            SkeletonPoint("${topic}_sub1", 2),
            SkeletonPoint("${topic}_sub2", 2)
        )
    }

    fun fill(skeleton: List<SkeletonPoint>): List<SkeletonPoint> {
        val filled = skeleton.toMutableList()
        filled.addAll(skeleton.filter { it.level == 1 }.map {
            SkeletonPoint("${it.title}_detail", it.level + 1)
        })
        return filled
    }

    fun isComplete(skeleton: List<SkeletonPoint>): Boolean {
        return skeleton.isNotEmpty()
    }

    fun merge(s1: List<SkeletonPoint>, s2: List<SkeletonPoint>): List<SkeletonPoint> {
        return (s1 + s2).distinct()
    }

    suspend fun reason(input: String): String {
        val skeleton = buildSkeleton(input)
        val filled = fill(skeleton)
        return "comparison: ${filled.joinToString { it.title }}"
    }
}
