package com.apex.agent.core.patterns

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 代理模式 - 代理的多种代理实现
 * 通过代理控制对真实代理的访问，支持懒加载、远程、访问控制、日志和虚拟代理
 */

/** 代理接口 */
    fun isAvailable(): Boolean
}

/** 真实代理 */
class RealAgent(override val name: String) : Agent {
    override val status: String get() = "running"
    private val initialized = AtomicBoolean(true)

    override suspend fun process(input: String): String {
        return "$name processed: $input"
    }

    override suspend fun getCapabilities(): List<String> {
        return listOf("reasoning", "planning", "tool_execution")
    }

    override fun isAvailable(): Boolean = initialized.get()
}

/** 延迟初始化代理 */
class LazyAgentProxy(private val agentName: String) : Agent {
    override val name: String get() = agentName
    override val status: String get() = if (isInitialized) realAgent!!.status else "lazy_init"

    private var realAgent: RealAgent? = null
    private val isInitialized get() = realAgent != null

    private fun getOrCreateAgent(): RealAgent {
        if (realAgent == null) {
            synchronized(this) {
                if (realAgent == null) {
                    realAgent = RealAgent(agentName)
                }
            }
        }
        return realAgent!!
    }

    override suspend fun process(input: String): String = getOrCreateAgent().process(input)
    override suspend fun getCapabilities(): List<String> = getOrCreateAgent().getCapabilities()
    override fun isAvailable(): Boolean = getOrCreateAgent().isAvailable()
}

/** 虚拟代理（远程 Agent 的本地代表） */
class VirtualAgentProxy(private val agentId: String) : Agent {
    override val name: String get() = "Virtual:$agentId"
    override val status: String get() = "virtual"

    override suspend fun process(input: String): String {
        return "Virtual agent $agentId forwarding: $input"
    }

    override suspend fun getCapabilities(): List<String> {
        return listOf("remote_execution")
    }

    override fun isAvailable(): Boolean = true
}

/** 保护代理（访问控制） */
class ProtectionProxy(
    private val realAgent: Agent,
    private val allowedUsers: Set<String>,
    private val currentUser: String
) : Agent {
    override val name: String get() = realAgent.name
    override val status: String get() = if (hasAccess()) realAgent.status else "restricted"

    private fun hasAccess(): Boolean = currentUser in allowedUsers

    override suspend fun process(input: String): String {
        if (!hasAccess()) throw SecurityException("User $currentUser not allowed to access ${realAgent.name}")
        return realAgent.process(input)
    }

    override suspend fun getCapabilities(): List<String> {
        if (!hasAccess()) throw SecurityException("User $currentUser not allowed to access ${realAgent.name}")
        return realAgent.getCapabilities()
    }

    override fun isAvailable(): Boolean = hasAccess() && realAgent.isAvailable()
}

/** 日志代理 */
class LoggingProxy(private val realAgent: Agent) : Agent {
    override val name: String get() = realAgent.name
    override val status: String get() = realAgent.status

    private val requestCounter = AtomicLong(0)

    override suspend fun process(input: String): String {
        val requestId = requestCounter.incrementAndGet()
        android.util.Log.d("Proxy", "[$requestId] process called with input=$input")
        val result = realAgent.process(input)
        android.util.Log.d("Proxy", "[$requestId] result=$result")
        return result
    }

    override suspend fun getCapabilities(): List<String> {
        android.util.Log.d("Proxy", "getCapabilities called")
        return realAgent.getCapabilities()
    }

    override fun isAvailable(): Boolean = realAgent.isAvailable()
}

/** 远程代理（网络通信） */
class RemoteAgentProxy(private val endpoint: String) : Agent {
    override val name: String get() = "Remote:$endpoint"
    override val status: String get() = "remote"

    override suspend fun process(input: String): String {
        return "Remote agent at $endpoint processed: $input"
    }

    override suspend fun getCapabilities(): List<String> {
        return listOf("remote_capabilities")
    }

    override fun isAvailable(): Boolean {
        return endpoint.isNotBlank()
    }
}
