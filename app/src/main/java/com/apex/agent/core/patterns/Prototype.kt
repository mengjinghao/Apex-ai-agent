package com.apex.agent.core.patterns

/**
 * 原型模式 - 工作流模板克隆
 * 支持深拷贝、浅拷贝和命名模板注册，用于快速创建工作流定义
 */

/** 原型接口 */
interface Prototype<T> {
    /** 克隆（默认浅拷贝） */
    fun clone(): T
    /** 深拷贝 */
    fun deepCopy(): T
    /** 浅拷贝 */
    fun shallowCopy(): T
}

/** 工作流节点 */

/** 工作流边 */

/** 工作流模板 */

    override fun clone(): WorkflowTemplate = copy(
        nodes = nodes.toMutableList(),
        edges = edges.toMutableList(),
        config = config.toMutableMap()
    )

    override fun deepCopy(): WorkflowTemplate {
        val copiedNodes = nodes.map { it.copy(config = it.config.toMap()) }.toMutableList()
        val copiedEdges = edges.map { it.copy() }.toMutableList()
        val copiedConfig = config.mapValues { it.value }.toMutableMap()
        return copy(nodes = copiedNodes, edges = copiedEdges, config = copiedConfig)
    }

    override fun shallowCopy(): WorkflowTemplate = this.copy()

    /** 覆盖配置 */
    fun overrideConfig(key: String, value: Any): WorkflowTemplate {
        config[key] = value
        return this
    }

    /** 转换为工作流定义 */
    fun toWorkflowDefinition(): String {
        return """
            Workflow: $name
            Nodes: ${nodes.joinToString { it.name }}
            Edges: ${edges.size}
            Config: $config
        """.trimIndent()
    }
}

/** Agent 配置原型 */
data class AgentConfigPrototype(
    val model: String = "gpt-4",
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val memorySize: Int = 100,
    val tools: List<String> = emptyList()
) : Prototype<AgentConfigPrototype> {
    override fun clone(): AgentConfigPrototype = this.copy()
    override fun deepCopy(): AgentConfigPrototype = this.copy(tools = tools.toList())
    override fun shallowCopy(): AgentConfigPrototype = this
}

/** 任务原型 */
data class TaskPrototype(
    val type: String,
    val priority: Int = 0,
    val timeoutMs: Long = 30000,
    val retryCount: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) : Prototype<TaskPrototype> {
    override fun clone(): TaskPrototype = this.copy()
    override fun deepCopy(): TaskPrototype = this.copy(metadata = metadata.toMap())
    override fun shallowCopy(): TaskPrototype = this
}

/** 原型注册表 */
class PrototypeRegistry {
    private val templates = mutableMapOf<String, WorkflowTemplate>()

    fun register(name: String, template: WorkflowTemplate) {
        templates[name] = template
    }

    fun get(name: String): WorkflowTemplate? = templates[name]?.deepCopy()

    fun remove(name: String): WorkflowTemplate? = templates.remove(name)

    fun listNames(): List<String> = templates.keys.toList()

    fun clear() = templates.clear()
}
