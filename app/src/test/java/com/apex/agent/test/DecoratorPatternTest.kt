package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 装饰器模式测试
 *
 * 验证装饰器链式调用、指标收集和重试逻辑。
 */
class DecoratorPatternTest : BaseUnitTest {

    private lateinit var baseComponent: TestComponent

    @Before
    override fun setUp() {
        super.setUp()
        baseComponent = TestComponent()
    }

    @Test
    fun `base component should perform operation`() {
        val result = baseComponent.operate("input")
        assertEquals("input", result)
    }

    @Test
    fun `logging decorator should wrap with logs`() {
        val logged = LoggingDecorator(baseComponent)
        val result = logged.operate("data")
        assertTrue(result.contains("data"))
    }

    @Test
    fun `metrics decorator should track calls`() {
        val metrics = MetricsDecorator(baseComponent)
        metrics.operate("a")
        metrics.operate("b")
        assertEquals(2, metrics.callCount)
    }

    @Test
    fun `retry decorator should retry on failure`() {
        val failing = FailingComponent()
        val retry = RetryDecorator(failing, maxRetries = 3)
        val result = retry.operate("try")
        assertNotNull(result)
    }

    @Test
    fun `decorator chain should apply all`() {
        val chain = LoggingDecorator(MetricsDecorator(RetryDecorator(baseComponent)))
        val result = chain.operate("chained")
        assertEquals("chained", result)
    }

    @Test
    fun `timing decorator should measure duration`() {
        val timing = TimingDecorator(baseComponent)
        timing.operate("measure")
        assertTrue(timing.lastDuration >= 0)
    }

    @Test
    fun `caching decorator should cache results`() {
        val caching = CacheDecorator(baseComponent)
        val first = caching.operate("same_key")
        val second = caching.operate("same_key")
        assertEquals(first, second)
    }

    @Test
    fun `rate limiter decorator should throttle`() {
        val limited = RateLimiterDecorator(baseComponent, maxCalls = 2)
        assertTrue(limited.operate("1") != null)
        assertTrue(limited.operate("2") != null)
        assertTrue(limited.operate("3") == null)
    }

    @Test
    fun `circuit breaker decorator should open after failures`() {
        val breaker = CircuitBreakerDecorator(FailingComponent(), threshold = 2)
        breaker.operate("f1")
        breaker.operate("f2")
        val result = breaker.operate("f3")
        assertNull(result)
    }

    @Test
    fun `auth decorator should validate token`() {
        val auth = AuthDecorator(baseComponent, validToken = "secret")
        val allowed = auth.operate("data", token = "secret")
        assertNotNull(allowed)
    }
}

interface Component {
    fun operate(input: String): String?
}

open class TestComponent : Component {
    override fun operate(input: String): String? = input
}

class FailingComponent : Component {
    private var attempts = 0
    override fun operate(input: String): String? {
        attempts++
        if (attempts <= 2) throw RuntimeException("fail")
        return input
    }
}

open class ComponentDecorator(protected val wrapped: Component) : Component {
    override fun operate(input: String): String? = wrapped.operate(input)
}

class LoggingDecorator(wrapped: Component) : ComponentDecorator(wrapped) {
    override fun operate(input: String): String? {
        val result = super.operate(input)
        return "logged($result)"
    }
}

class MetricsDecorator(wrapped: Component) : ComponentDecorator(wrapped) {
    var callCount = 0
    override fun operate(input: String): String? {
        callCount++
        return super.operate(input)
    }
}

class RetryDecorator(wrapped: Component, private val maxRetries: Int) : ComponentDecorator(wrapped) {
    override fun operate(input: String): String? {
        repeat(maxRetries) {
            try { return super.operate(input) } catch (_: Exception) {}
        }
        return super.operate(input)
    }
}

class TimingDecorator(wrapped: Component) : ComponentDecorator(wrapped) {
    var lastDuration: Long = 0
    override fun operate(input: String): String? {
        val start = System.nanoTime()
        val result = super.operate(input)
        lastDuration = System.nanoTime() - start
        return result
    }
}

class CacheDecorator(wrapped: Component) : ComponentDecorator(wrapped) {
    private val cache = mutableMapOf<String, String?>()
    override fun operate(input: String): String? {
        return cache.getOrPut(input) { super.operate(input) }
    }
}

class RateLimiterDecorator(wrapped: Component, private val maxCalls: Int) : ComponentDecorator(wrapped) {
    private var calls = 0
    override fun operate(input: String): String? {
        if (calls >= maxCalls) return null
        calls++
        return super.operate(input)
    }
}

class CircuitBreakerDecorator(wrapped: Component, private val threshold: Int) : ComponentDecorator(wrapped) {
    private var failures = 0
    private var open = false
    override fun operate(input: String): String? {
        if (open) return null
        return try {
            super.operate(input)
        } catch (e: Exception) {
            failures++
            if (failures >= threshold) open = true
            null
        }
    }
}

class AuthDecorator(wrapped: Component, private val validToken: String) : ComponentDecorator(wrapped) {
    fun operate(input: String, token: String): String? {
        if (token != validToken) return null
        return super.operate(input)
    }
}
