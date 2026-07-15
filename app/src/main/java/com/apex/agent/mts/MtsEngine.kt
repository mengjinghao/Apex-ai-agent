package com.apex.agent.mts

import com.apex.agent.mts.adapter.ProtocolConfig
import com.apex.agent.mts.adapter.ToolCallProtocolAdapter
import com.apex.agent.mts.bridge.BerserkModeOrchestrator
import com.apex.agent.mts.executor.ExecutionConfig
import com.apex.agent.mts.executor.ExecutionSummary
import com.apex.agent.mts.executor.SmartExecutor
import com.apex.agent.mts.executor.ToolInvoker
import com.apex.agent.mts.observer.ToolObservability
import com.apex.agent.mts.optimizer.OptimizationContext
import com.apex.agent.mts.optimizer.OptimizationResult
import com.apex.agent.mts.optimizer.PromptOptimizer
import com.apex.agent.mts.registry.ToolRegistry
import com.apex.agent.mts.router.ExecutionContext
import com.apex.agent.mts.router.IntelligentRouter
import com.apex.agent.mts.router.RoutingPlan
import com.apex.agent.mts.schema.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import com.apex.agent.mts.router.IntentAnalysis

/**
 * MtsEngine - Meta Tool System unified entry point.
 *
 * Usage:
 *   val engine = MtsEngine.create(context)
 *   val plan = engine.route("list files in download")
 *   val result = engine.execute(plan)
 */
class MtsEngine private constructor(
    private val registry: ToolRegistry,
    private val router: IntelligentRouter,
    private val executor: SmartExecutor,
    private val optimizer: PromptOptimizer,
    private val protocolAdapter: ToolCallProtocolAdapter,
    private val observability: ToolObservability,
    private val berserkOrchestrator: BerserkModeOrchestrator? = null
) {
    val tools: ToolRegistry get() = registry
    val metrics: ToolObservability get() = observability

    /** 当前运行模式 */
    var currentMode: AgentMode = AgentMode.NORMAL
        private set

    /** 切换运行模式 */
    fun setMode(mode: AgentMode): MtsEngine {
        currentMode = mode
        return this
    }

    /** 获取当前模式下可用的所有工具 */
    fun getToolsByMode(mode: AgentMode = currentMode): List<ToolSpec> {
        return if (mode == AgentMode.BERSERK) {
            registry.getAll()
        } else {
            optimizer.filterByMode(registry.getAll(), mode)
        }
    }

    /** 获取当前模式下可用的工具数量 */
    fun getToolCountByMode(mode: AgentMode = currentMode): Int = getToolsByMode(mode).size

    /** 获取各模式的工具分布 */
    val modeDistribution: Map<AgentMode, Int>
        get() = AgentMode.values().associateWith { getToolCountByMode(it) }

    suspend fun route(userInput: String, context: ExecutionContext = ExecutionContext()): RoutingPlan {
        val effectiveContext = context.copy(agentMode = currentMode)
        return router.route(userInput, effectiveContext)
    }

    suspend fun execute(
        calls: List<ParsedToolCall>,
        context: ExecutionContext = ExecutionContext()
    ): ExecutionSummary {
        val effectiveContext = context.copy(agentMode = currentMode)
        val summary = executor.execute(calls, effectiveContext)
        summary.results.forEach { result ->
            observability.recordCall(
                com.apex.agent.mts.observer.ToolCallRecord(
                    toolName = result.toolName,
                    success = result.outcome is ToolOutcome.Success,
                    durationMs = result.durationMs,
                    errorCode = (result.outcome as? ToolOutcome.Failure)?.code
                )
            )
        }
        return summary
    }
        fun analyzeIntent(userInput: String): IntentAnalysis {
        return router.analyzeIntent(userInput)
    }
        fun searchTools(query: String, topK: Int = 5) = registry.search(query, topK)
        fun optimizePrompts(
        userInput: String? = null,
        context: OptimizationContext = OptimizationContext()
    ): OptimizationResult {
        val modeTools = getToolsByMode(context.agentMode)
        val effectiveContext = context.copy(agentMode = currentMode)
        return if (userInput != null) {
            optimizer.createSmartPrompt(modeTools, userInput, effectiveContext)
        } else {
            optimizer.optimize(modeTools, effectiveContext)
        }
    }
        fun buildDefinitions(
        tools: List<ToolSpec>? = null,
        config: ProtocolConfig = ProtocolConfig()
    ) = protocolAdapter.buildDefinitions(tools ?: getToolsByMode(), config)
        fun parseToolCalls(
        response: String,
        protocol: com.apex.agent.mts.adapter.Protocol = com.apex.agent.mts.adapter.Protocol.AUTO
    ) = protocolAdapter.parseToolCalls(response, { registry.getByName(it) }, protocol)
        fun registerTool(tool: ToolSpec) = registry.register(tool)
        fun registerTools(tools: List<ToolSpec>) = registry.registerBatch(tools)
        fun unregisterTool(toolId: String) = registry.unregister(toolId)
        fun getTool(name: String) = registry.getByName(name)
        val isBerserk: Boolean get() = currentMode == AgentMode.BERSERK

    suspend fun berserkExecute(
        calls: List<ParsedToolCall>,
        timeoutMinutes: Long = 5
    ): ExecutionSummary {
        val orchestrator = berserkOrchestrator
        if (orchestrator != null) {
            return withTimeout(timeoutMinutes * 60_000L) {
                val startTime = System.currentTimeMillis()
        val results = coroutineScope {
                    calls.map { call ->
                        async { orchestrator.executeBerserk(call) }
                    }.map { it.await() }
                }
        val succeeded = results.count { it.outcome is ToolOutcome.Success }
        val failed = results.count { it.outcome is ToolOutcome.Failure }
        val cancelled = results.count { it.outcome is ToolOutcome.Cancelled }
                results.forEach { r ->
                    observability.recordCall(
                        com.apex.agent.mts.observer.ToolCallRecord(
                            toolName = r.toolName,
                            success = r.outcome is ToolOutcome.Success,
                            durationMs = r.durationMs,
                            errorCode = (r.outcome as? ToolOutcome.Failure)?.code
                        )
                    )
                }
                ExecutionSummary(
                    totalCalls = calls.size,
                    succeeded = succeeded,
                    failed = failed,
                    cancelled = cancelled,
                    totalDurationMs = System.currentTimeMillis() - startTime,
                    results = results
                )
            }
        }
        val oldMode = currentMode
        setMode(AgentMode.BERSERK)
        try {
            return withTimeout(timeoutMinutes * 60_000L) {
                execute(calls)
            }
        } finally {
            setMode(oldMode)
        }
    }

    suspend fun berserkRoute(userInput: String): RoutingPlan {
        return route(userInput, ExecutionContext(agentMode = AgentMode.BERSERK))
    }

    suspend fun bruteForceExecute(
        toolName: String,
        vararg argumentSets: Map<String, Any?>
    ): List<ExecutionResult> {
        val spec = registry.getByName(toolName) ?: return emptyList()
        return coroutineScope {
            argumentSets.map { args ->
                async {
                    val call = ParsedToolCall(
                        id = java.util.UUID.randomUUID().toString(),
                        toolSpec = spec,
                        arguments = args,
                        rawName = toolName
                    )
        val plan = listOf(call)
        val summary = execute(plan, ExecutionContext(agentMode = AgentMode.BERSERK))
                    summary.results.firstOrNull()
                }
            }.mapNotNull { it.await() }
        }
    }
        val summary: String
        get() {
            val toolCount = registry.size()
        val dist = modeDistribution
            val metrics = observability.summary
            return """
MTS Engine Status
=================
Current mode: ${currentMode.name}
Mode distribution: ${dist.entries.joinToString(", ") { "${it.key.name}=${it.value}" }}
Registered tools (total): $toolCount
$metrics
            """.trimIndent()
        }
        class Builder(private val invoker: ToolInvoker) {
        private val registry = ToolRegistry()
        private val router = IntelligentRouter(registry)
        private val observability = ToolObservability()
        fun withDefaultConfig(): Builder {
            executorConfig = ExecutionConfig()
            protocolConfig = ProtocolConfig()
        return this
        }
        private var executorConfig: ExecutionConfig = ExecutionConfig()
        private var protocolConfig: ProtocolConfig = ProtocolConfig()
        fun executorConfig(config: ExecutionConfig): Builder {
            executorConfig = config
            return this
        }
        fun protocolConfig(config: ProtocolConfig): Builder {
            protocolConfig = config
            return this
        }
        fun registerAll(tools: List<ToolSpec>): Builder {
            registry.registerBatch(tools)
        return this
        }
        fun build(): MtsEngine {
            val executor = SmartExecutor(registry, invoker, executorConfig)
        val optimizer = PromptOptimizer(registry)
        val adapter = ToolCallProtocolAdapter()
        val orchestrator = BerserkModeOrchestrator(registry, invoker)
        return MtsEngine(registry, router, executor, optimizer, adapter, observability, orchestrator)
        }
    }

    companion object {
        fun create(
            invoker: ToolInvoker,
            tools: List<ToolSpec> = emptyList(),
            executionConfig: ExecutionConfig = ExecutionConfig()
        ): MtsEngine {
            return Builder(invoker)
                .withDefaultConfig()
                .executorConfig(executionConfig)
                .registerAll(tools)
                .build()
        }
    }
}
