package com.apex.agent.core.patterns

/**
 * 抽象工厂模式 - Agent 组件族创建
 * 提供创建相关组件家族的接口，无需指定具体类
 */

/** 记忆接口 */
interface Memory {
    fun store(key: String, value: Any)
    fun retrieve(key: String): Any?
    fun clear()
}

/** 存储接口 */
interface Storage {
    fun save(id: String, data: ByteArray)
    fun load(id: String): ByteArray?
    fun delete(id: String): Boolean
}

/** 工具执行器接口 */
interface ToolExecutor {
    suspend fun execute(name: String, params: Map<String, Any>): Any
}

/** 推理引擎接口 */
interface ReasoningEngine {
    suspend fun reason(input: String, context: Map<String, Any>): String
}

/** 抽象工厂接口 - 创建一组相关的 Agent 组件 */
interface AgentComponentFactory {
    fun createMemory(): Memory
    fun createStorage(): Storage
    fun createToolExecutor(): ToolExecutor
    fun createReasoningEngine(): ReasoningEngine
}

/** RAM 记忆 */
class InMemoryMemory : Memory {
    private val store = mutableMapOf<String, Any>()
    override fun store(key: String, value: Any) { store[key] = value }
    override fun retrieve(key: String): Any? = store[key]
    override fun clear() = store.clear()
}

/** 本地文件存储 */
class LocalFileStorage : Storage {
    private val store = mutableMapOf<String, ByteArray>()
    override fun save(id: String, data: ByteArray) { store[id] = data }
    override fun load(id: String): ByteArray? = store[id]
    override fun delete(id: String): Boolean = store.remove(id) != null
}

/** 生产环境工厂 */
class ProductionAgentFactory : AgentComponentFactory {
    override fun createMemory(): Memory = InMemoryMemory()
    override fun createStorage(): Storage = LocalFileStorage()
    override fun createToolExecutor(): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(name: String, params: Map<String, Any>): Any = "Production:$name"
    }
    override fun createReasoningEngine(): ReasoningEngine = object : ReasoningEngine {
        override suspend fun reason(input: String, context: Map<String, Any>): String = "Production reasoning: $input"
    }
}

/** 测试环境工厂 */
class TestingAgentFactory : AgentComponentFactory {
    override fun createMemory(): Memory = InMemoryMemory()
    override fun createStorage(): Storage = object : Storage {
        override fun save(id: String, data: ByteArray) {}
        override fun load(id: String): ByteArray? = null
        override fun delete(id: String): Boolean = true
    }
    override fun createToolExecutor(): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(name: String, params: Map<String, Any>): Any = "Mock:$name"
    }
    override fun createReasoningEngine(): ReasoningEngine = object : ReasoningEngine {
        override suspend fun reason(input: String, context: Map<String, Any>): String = "Mock reasoning"
    }
}

/** 轻量级工厂 */
class LightweightAgentFactory : AgentComponentFactory {
    override fun createMemory(): Memory = InMemoryMemory()
    override fun createStorage(): Storage = LocalFileStorage()
    override fun createToolExecutor(): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(name: String, params: Map<String, Any>): Any = "Lightweight:$name"
    }
    override fun createReasoningEngine(): ReasoningEngine = object : ReasoningEngine {
        override suspend fun reason(input: String, context: Map<String, Any>): String = input
    }
}

/** 高性能工厂 */
class HighPerformanceAgentFactory : AgentComponentFactory {
    override fun createMemory(): Memory = InMemoryMemory()
    override fun createStorage(): Storage = LocalFileStorage()
    override fun createToolExecutor(): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(name: String, params: Map<String, Any>): Any = "HighPerf:$name"
    }
    override fun createReasoningEngine(): ReasoningEngine = object : ReasoningEngine {
        override suspend fun reason(input: String, context: Map<String, Any>): String = "Optimized: $input"
    }
}

/** 工厂注册表 */
class FactoryRegistry {
    private val factories = mutableMapOf<String, AgentComponentFactory>()

    init {
        register("production", ProductionAgentFactory())
        register("testing", TestingAgentFactory())
        register("lightweight", LightweightAgentFactory())
        register("high_performance", HighPerformanceAgentFactory())
    }

    fun register(name: String, factory: AgentComponentFactory) { factories[name] = factory }
    fun get(name: String): AgentComponentFactory? = factories[name]

    /** 自动检测配置 */
    fun autoDetect(config: Map<String, String>): AgentComponentFactory {
        val mode = config["mode"]?.lowercase() ?: "lightweight"
        return factories[mode] ?: LightweightAgentFactory()
    }
}
