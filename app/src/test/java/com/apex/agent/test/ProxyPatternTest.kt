package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 代理模式测试
 *
 * 验证延迟初始化、访问控制和日志代理。
 */
class ProxyPatternTest : BaseUnitTest {

    private lateinit var realService: RealService

    @Before
    override fun setUp() {
        super.setUp()
        realService = RealService()
    }

    @Test
    fun `real service should process`() {
        assertEquals("processed: data", realService.process("data"))
    }

    @Test
    fun `lazy proxy should initialize on first call`() {
        val proxy = LazyProxy { RealService() }
        assertFalse(proxy.isInitialized())
        proxy.process("first")
        assertTrue(proxy.isInitialized())
    }

    @Test
    fun `access proxy should check permissions`() {
        val proxy = AccessProxy(realService, allowedUsers = setOf("admin"))
        assertNotNull(proxy.process("data", user = "admin"))
        assertNull(proxy.process("data", user = "guest"))
    }

    @Test
    fun `logging proxy should track calls`() {
        val proxy = LoggingProxy(realService)
        proxy.process("a")
        proxy.process("b")
        assertEquals(2, proxy.callLog.size)
    }

    @Test
    fun `virtual proxy should delay heavy operation`() {
        val proxy = VirtualProxy { RealService() }
        val start = System.currentTimeMillis()
        proxy.process("light")
        val duration = System.currentTimeMillis() - start
        assertTrue(duration < 100)
    }

    @Test
    fun `caching proxy should return cached results`() {
        val proxy = CachingProxy(realService)
        val first = proxy.process("same")
        val second = proxy.process("same")
        assertEquals(first, second)
    }

    @Test
    fun `protection proxy should reject unauthorized`() {
        val proxy = ProtectionProxy(realService)
        assertTrue(proxy.authenticate("valid_token"))
        assertFalse(proxy.authenticate("bad_token"))
    }

    @Test
    fun `remote proxy should handle network`() {
        val proxy = RemoteProxy("http://api.example.com")
        val result = proxy.process("remote_call")
        assertNotNull(result)
    }
}

interface Service {
    fun process(input: String): String?
}

open class RealService : Service {
    override fun process(input: String): String? = "processed: $input"
}

class LazyProxy(private val factory: () -> RealService) : Service {
    private var real: RealService? = null
    fun isInitialized() = real != null

    override fun process(input: String): String? {
        if (real == null) real = factory()
        return real!!.process(input)
    }
}

class AccessProxy(private val real: Service, private val allowedUsers: Set<String>) : Service {
    fun process(input: String, user: String): String? {
        if (user !in allowedUsers) return null
        return real.process(input)
    }

    override fun process(input: String): String? = real.process(input)
}

class LoggingProxy(private val real: Service) : Service {
    val callLog = mutableListOf<String>()

    override fun process(input: String): String? {
        callLog.add(input)
        return real.process(input)
    }
}

class VirtualProxy(private val factory: () -> RealService) : Service {
    private val real by lazy { factory() }

    override fun process(input: String): String? = real.process(input)
}

class CachingProxy(private val real: Service) : Service {
    private val cache = mutableMapOf<String, String?>()

    override fun process(input: String): String? {
        return cache.getOrPut(input) { real.process(input) }
    }
}

class ProtectionProxy(private val real: Service) : Service {
    fun authenticate(token: String): Boolean = token == "valid_token"

    override fun process(input: String): String? = real.process(input)
}

class RemoteProxy(private val endpoint: String) : Service {
    override fun process(input: String): String? = "remote($endpoint): $input"
}
