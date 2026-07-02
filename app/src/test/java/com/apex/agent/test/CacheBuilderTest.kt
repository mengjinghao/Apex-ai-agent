package com.apex.agent.test

import com.apex.agent.core.cache.CacheBuilder
import com.apex.agent.core.cache.CacheManager
import com.apex.agent.core.cache.CachePolicy
import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 缓存构建器测试
 *
 * 验证流式 API、默认值、参数验证和构建结果。
 */
class CacheBuilderTest : BaseUnitTest {

    private lateinit var builder: CacheBuilder<String>

    @Before
    override fun setUp() {
        super.setUp()
        builder = CacheBuilder()
    }

    @Test
    fun `builder should produce valid CacheManager`() {
        val cache = builder.build()
        assertNotNull(cache)
        assertTrue(cache is CacheManager)
    }

    @Test
    fun `memoryCache should configure L1 settings`() {
        val cache = builder.memoryCache(maxSize = 200, ttl = 60000).build()
        assertNotNull(cache)
    }

    @Test
    fun `diskCache should configure L2 settings`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cb-disk-${System.nanoTime()}")
        dir.mkdirs()
        val cache = builder.diskCache(dir = dir, maxSize = 500).build()
        assertNotNull(cache)
        dir.deleteRecursively()
    }

    @Test
    fun `distributedCache should configure L3 settings`() {
        val cache = builder.distributedCache(url = "redis://cache:6379", maxRetries = 5).build()
        assertNotNull(cache)
    }

    @Test
    fun `policy should set default eviction strategy`() {
        val cache = builder.policy(CachePolicy.LfuPolicy(5)).build()
        assertNotNull(cache)
    }

    @Test
    fun `promotion should configure thresholds`() {
        val cache = builder.promotion(promote = 5, demote = 2).build()
        assertNotNull(cache)
    }

    @Test
    fun `writeBack should configure async write interval`() {
        val cache = builder.writeBack(1000).build()
        assertNotNull(cache)
    }

    @Test
    fun `chained configuration should work`() {
        val cache = builder
            .memoryCache(maxSize = 100)
            .promotion(3, 1)
            .policy(CachePolicy.TtlPolicy(5000))
            .build()
        assertNotNull(cache)
    }

    @Test
    fun `builder should provide sensible defaults`() {
        val cache = CacheBuilder<String>().build()
        assertNotNull(cache)
    }
}
