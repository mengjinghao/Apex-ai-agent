package com.apex.agent

import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 工厂接口。
 *
 * 业务侧实现此接口以支持自定义 Agent 的延迟创建。
 * 注册到 [AgentFactoryRegistry] 后，可通过 agentType 字符串创建 Agent 实例。
 *
 * 适用场景：
 * - 根据 LLM 配置动态选择 Agent 实现
 * - 根据设备能力（如是否有 NPU）选择不同 Agent
 * - 插件化 Agent 加载
 */
interface AgentFactory {

    /** 此工厂能创建的 agentType 列表。 */
    val supportedTypes: Set<String>

    /** 创建 Agent 实例。返回 null 表示创建失败。 */
    fun create(agentType: String, config: Map<String, Any> = emptyMap()): SubAgent?

    /** 检查此工厂是否能创建指定类型的 Agent。 */
    fun supports(agentType: String): Boolean = agentType in supportedTypes
}

/**
 * Agent 工厂注册表。
 *
 * 管理多个 [AgentFactory]，按 agentType 路由创建请求。
 *
 * 使用方式：
 * ```
 * AgentFactoryRegistry.register(MyAgentFactory())
 * val agent = AgentFactoryRegistry.create("custom_type", mapOf("key" to "value"))
 * ```
 */
object AgentFactoryRegistry {

    private val factories = ConcurrentHashMap<String, AgentFactory>()

    /**
     * 注册工厂。相同 supportedTypes 的工厂会被覆盖。
     */
    fun register(factory: AgentFactory) {
        for (type in factory.supportedTypes) {
            factories[type] = factory
        }
    }

    /**
     * 注销工厂。
     */
    fun unregister(factory: AgentFactory) {
        for (type in factory.supportedTypes) {
            factories.remove(type, factory)
        }
    }

    /**
     * 创建 Agent。
     * @param agentType Agent 类型
     * @param config 创建配置
     * @return Agent 实例，找不到工厂时返回 null
     */
    fun create(agentType: String, config: Map<String, Any> = emptyMap()): SubAgent? {
        val factory = factories[agentType] ?: return null
        return runCatching { factory.create(agentType, config) }.getOrNull()
    }

    /**
     * 获取所有已注册支持的 agentType。
     */
    fun getSupportedTypes(): Set<String> = factories.keys.toSet()

    /**
     * 检查是否支持指定类型。
     */
    fun supports(agentType: String): Boolean = agentType in factories

    /**
     * 清空所有注册的工厂。
     */
    fun clear() {
        factories.clear()
    }

    /**
     * 通过 Java ServiceLoader 自动发现并注册工厂。
     * 业务侧在 META-INF/services/com.apex.agent.AgentFactory 文件中列出工厂实现类。
     */
    fun autoDiscover() {
        try {
            val loader = ServiceLoader.load(AgentFactory::class.java)
            for (factory in loader) {
                register(factory)
            }
        } catch (_: Exception) {
            // ServiceLoader 不可用时静默降级
        }
    }
}

/**
 * 内置 Agent 工厂。
 *
 * 创建项目自带的 Agent（FileAgent / GeneralAgent）。
 * 业务侧可注册自己的工厂覆盖默认实现。
 */
class BuiltinAgentFactory : AgentFactory {

    override val supportedTypes: Set<String> = setOf("file", "general")

    override fun create(agentType: String, config: Map<String, Any>): SubAgent? {
        return when (agentType) {
            "file" -> FileAgent()
            "general" -> GeneralAgent()
            else -> null
        }
    }
}

/**
 * Agent 单例池。
 *
 * 管理全局共享的 Agent 实例，避免重复创建。
 * 与 [DynamicAgentRegistry] 的区别：
 * - DynamicAgentRegistry 关注注册和发现
 * - AgentPool 关注实例复用和生命周期
 *
 * 使用方式：
 * ```
 * val agent = AgentPool.getOrCreate("file") { FileAgent() }
 * AgentPool.release("file")
 * ```
 */
object AgentPool {

    private val pool = ConcurrentHashMap<String, SubAgent>()
    private val refCount = ConcurrentHashMap<String, Int>()

    /**
     * 获取或创建 Agent。
     * @param agentType Agent 类型
     * @param factory 创建工厂（pool 中不存在时调用）
     * @return Agent 实例
     */
    @Synchronized
    fun getOrCreate(agentType: String, factory: () -> SubAgent): SubAgent {
        refCount.compute(agentType) { _, count -> (count ?: 0) + 1 }
        return pool.getOrPut(agentType) {
            factory().also { agent ->
                // 用 agentId 而非 agentType 作为 key 的二次索引，避免冲突
                pool[agent.agentId] = agent
            }
        }
    }

    /**
     * 释放 Agent 引用。
     * 引用计数归零时从池中移除。
     */
    @Synchronized
    fun release(agentType: String) {
        refCount.compute(agentType) { _, count ->
            val newCount = (count ?: 1) - 1
            if (newCount <= 0) {
                pool.remove(agentType)
                null
            } else {
                newCount
            }
        }
    }

    /**
     * 获取池中所有 Agent。
     */
    fun getAll(): List<SubAgent> = pool.values.toList()

    /**
     * 清空池（不调用 Agent 的 onDestroy，由调用方负责）。
     */
    @Synchronized
    fun clear() {
        pool.clear()
        refCount.clear()
    }

    /**
     * 获取池大小。
     */
    fun size(): Int = pool.size
}
