package com.apex.agent.core.patterns

/**
 * 流式构建器模式 - 工作流、管道和通知构建
 * 提供类型安全的构建器，在 build 时进行完整验证
 */

/** 工作流阶段 */
enum class StageType { INPUT, PROCESS, OUTPUT, TOOL, DECISION }

/** 工作流阶段 */
data class Stage(val name: String, val type: StageType, val config: Map<String, String> = emptyMap())

/** 管道定义 */
data class Pipeline(val name: String, val stages: List<Stage>, val parallel: Boolean = false)

/** 通知 */
data class RichNotification(
    val title: String,
    val body: String,
    val priority: Int = 0,
    val icon: String? = null,
    val actions: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/** 工作流构建器 - 流式 API */
class WorkflowBuilder(private val name: String) {
    private val stages = mutableListOf<Stage>()
    private var parallel: Boolean = false
    private val errors = mutableListOf<String>()

    fun stage(name: String, type: StageType, config: Map<String, String> = emptyMap()): WorkflowBuilder {
        stages.add(Stage(name, type, config))
        return this
    }

    fun parallel(enabled: Boolean = true): WorkflowBuilder {
        parallel = enabled
        return this
    }

    fun inputStage(name: String = "input"): WorkflowBuilder {
        return stage(name, StageType.INPUT)
    }

    fun processStage(name: String = "process"): WorkflowBuilder {
        return stage(name, StageType.PROCESS)
    }

    fun outputStage(name: String = "output"): WorkflowBuilder {
        return stage(name, StageType.OUTPUT)
    }

    private fun validate() {
        if (name.isBlank()) errors.add("Workflow name cannot be blank")
        if (stages.isEmpty()) errors.add("At least one stage is required")
        if (stages.count { it.type == StageType.INPUT } > 1) errors.add("Only one input stage allowed")
        if (stages.count { it.type == StageType.OUTPUT } > 1) errors.add("Only one output stage allowed")
        stages.groupBy { it.name }.filter { it.value.size > 1 }.forEach {
            errors.add("Duplicate stage name: ${it.key}")
        }
    }

    fun build(): Pipeline {
        validate()
        if (errors.isNotEmpty()) throw IllegalStateException("Build failed: ${errors.joinToString("; ")}")
        return Pipeline(name, stages.toList(), parallel)
    }
}

/** 管道构建器 */
class AgentPipelineBuilder(private val pipelineName: String) {
    private val stages = mutableListOf<Stage>()
    private val errors = mutableListOf<String>()

    fun addStage(name: String, type: StageType, block: StageBuilder.() -> Unit = {}): AgentPipelineBuilder {
        val builder = StageBuilder()
        builder.block()
        stages.add(Stage(name, type, builder.config))
        return this
    }

    fun build(): Pipeline {
        if (pipelineName.isBlank()) errors.add("Pipeline name required")
        if (stages.isEmpty()) errors.add("Pipeline must have at least one stage")
        if (errors.isNotEmpty()) throw IllegalStateException("Pipeline build failed: ${errors.joinToString("; ")}")
        return Pipeline(pipelineName, stages.toList())
    }

    class StageBuilder {
        val config = mutableMapOf<String, String>()

        fun config(key: String, value: String) { config[key] = value }
        fun timeout(ms: Long) { config["timeout"] = ms.toString() }
        fun retry(count: Int) { config["retry"] = count.toString() }
        fun model(name: String) { config["model"] = name }
    }
}

/** 通知构建器 */
class NotificationBuilder {
    private var title: String = ""
    private var body: String = ""
    private var priority: Int = 0
    private var icon: String? = null
    private val actions = mutableListOf<String>()
    private val metadata = mutableMapOf<String, String>()
    private val errors = mutableListOf<String>()

    fun title(title: String) = apply { this.title = title }
    fun body(body: String) = apply { this.body = body }
    fun priority(priority: Int) = apply { this.priority = priority.coerceIn(0, 10) }
    fun icon(icon: String) = apply { this.icon = icon }
    fun action(action: String) = apply { this.actions.add(action) }
    fun metadata(key: String, value: String) = apply { this.metadata[key] = value }

    fun build(): RichNotification {
        if (title.isBlank()) errors.add("Title required")
        if (body.isBlank()) errors.add("Body required")
        if (title.length > 200) errors.add("Title exceeds 200 chars")
        if (body.length > 4000) errors.add("Body exceeds 4000 chars")
        if (actions.size > 5) errors.add("Max 5 actions allowed")
        if (errors.isNotEmpty()) throw IllegalStateException("Notification build failed: ${errors.joinToString("; ")}")
        return RichNotification(title, body, priority, icon, actions.toList(), metadata.toMap())
    }
}
