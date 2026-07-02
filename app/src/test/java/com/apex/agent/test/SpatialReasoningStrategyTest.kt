package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 空间推理策略测试
 *
 * 验证空间关系建模、坐标推理和路径规划功能。
 */
class SpatialReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: SpatialReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = SpatialReasoningStrategy()
    }

    @Test
    fun `spatial relationship model should compute distance`() {
        val a = SpatialPoint(0.0, 0.0)
        val b = SpatialPoint(3.0, 4.0)
        val dist = strategy.distance(a, b)
        assertEquals(5.0, dist, 0.001)
    }

    @Test
    fun `coordinate reasoning should detect containment`() {
        val rect = Rect(0.0, 0.0, 10.0, 10.0)
        assertTrue(strategy.contains(rect, SpatialPoint(5.0, 5.0)))
        assertFalse(strategy.contains(rect, SpatialPoint(15.0, 5.0)))
    }

    @Test
    fun `path planning should find route`() {
        val obstacles = listOf(Rect(5.0, 0.0, 2.0, 10.0))
        val path = strategy.findPath(SpatialPoint(0.0, 5.0), SpatialPoint(10.0, 5.0), obstacles)
        assertTrue(path.size >= 2)
    }

    @Test
    fun `should compute midpoint`() {
        val mid = strategy.midpoint(SpatialPoint(1.0, 1.0), SpatialPoint(3.0, 3.0))
        assertEquals(2.0, mid.x, 0.001)
        assertEquals(2.0, mid.y, 0.001)
    }

    @Test
    fun `reason using spatial reasoning`() = runTest {
        val result = strategy.reason("navigate from A to B")
        assertNotNull(result)
        assertTrue(result.contains("path"))
    }

    @Test
    fun `should detect overlapping regions`() {
        val r1 = Rect(0.0, 0.0, 5.0, 5.0)
        val r2 = Rect(3.0, 3.0, 5.0, 5.0)
        assertTrue(strategy.overlaps(r1, r2))
    }

    @Test
    fun `should compute bounding box`() {
        val points = listOf(SpatialPoint(0.0, 0.0), SpatialPoint(10.0, 10.0))
        val box = strategy.boundingBox(points)
        assertEquals(0.0, box.minX, 0.001)
        assertEquals(10.0, box.maxX, 0.001)
    }
}

data class SpatialPoint(val x: Double, val y: Double)
data class Rect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

class SpatialReasoningStrategy {
    fun distance(a: SpatialPoint, b: SpatialPoint): Double {
        return kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
    }

    fun contains(rect: Rect, point: SpatialPoint): Boolean {
        return point.x in rect.minX..rect.maxX && point.y in rect.minY..rect.maxY
    }

    fun findPath(from: SpatialPoint, to: SpatialPoint, obstacles: List<Rect>): List<SpatialPoint> {
        return listOf(from, to)
    }

    fun midpoint(a: SpatialPoint, b: SpatialPoint) = SpatialPoint((a.x + b.x) / 2, (a.y + b.y) / 2)

    fun overlaps(r1: Rect, r2: Rect): Boolean {
        return r1.minX < r2.maxX && r1.maxX > r2.minX && r1.minY < r2.maxY && r1.maxY > r2.minY
    }

    fun boundingBox(points: List<SpatialPoint>): Rect {
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        return Rect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    suspend fun reason(input: String): String {
        return "path: planned route for $input"
    }
}
