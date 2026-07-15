package com.apex.agent.mts.optimizer

import com.apex.agent.mts.observer.ToolCallRecord
import com.apex.agent.mts.registry.ToolRegistry
import com.apex.agent.mts.schema.*
import kotlinx.coroutines.runBlocking

data class OptimizationContext(
    val availableTokens: Int = 4096,
    val strategy: OptimizationStrategy = OptimizationStrategy.BALANCED,
    val recentCalls: List<String> = emptyList(),
    val userLanguage: String = "en",
    val sessionHistory: List<String> = emptyList(),
    val maxToolsPerCategory: Int = 10,
    /** 当前Agent模式，用于过滤可用工具 */
    val agentMode: AgentMode = AgentMode.NORMAL
)

data class OptimizationResult(
    val includedTools: List<ToolPromptDef>,
    val excludedTools: List<String>,
    val totalTokens: Int,
    val strategy: OptimizationStrategy,
    val reasoning: String
)

class PromptOptimizer(
    private val registry: ToolRegistry
) {
    companion object {
        private const val BASE_OVERHEAD_TOKENS = 200
        private const val NAME_TOKEN_COST = 5
        private const val DESC_TOKEN_COST_PER_CHAR = 0.25
        private const val PARAM_BASE_COST = 15
        private const val CATEGORY_HEADER_COST = 10

        private val essentialTools = setOf(
            "read_file", "list_files", "write_file", "visit_web",
            "click_element", "capture_screenshot", "device_info",
            "query_memory", "calculate", "execute_shell"
        )
        private val highValueTools = setOf(
            "grep_code", "find_files", "apply_file", "tap", "swipe",
            "http_request", "download_file", "install_app",
            "start_app", "set_input_text", "press_key",
            "browser_snapshot", "browser_navigate", "browser_click",
            "send_message_to_ai", "get_all_workflows",
            "create_memory", "update_memory", "delete_memory"
        )
    }
        fun optimize(
        tools: List<ToolSpec>,
        context: OptimizationContext = OptimizationContext()
    ): OptimizationResult {
        val filtered = filterByMode(tools, context.agentMode)
        val strategy = context.strategy
        val ranked = rankByRelevance(filtered, context)
        return when (strategy) {
            OptimizationStrategy.MINIMAL -> buildMinimal(ranked, context)
            OptimizationStrategy.BALANCED -> buildBalanced(ranked, context)
            OptimizationStrategy.DETAILED -> buildDetailed(ranked, context)
        }
    }
        fun optimizeByTokens(
        tools: List<ToolSpec>,
        maxTokens: Int,
        context: OptimizationContext = OptimizationContext()
    ): OptimizationResult {
        val filtered = filterByMode(tools, context.agentMode)
        val ranked = rankByRelevance(filtered, context.copy(availableTokens = maxTokens))
        return buildTokenAware(ranked, maxTokens, context)
    }
        fun rankByRelevance(
        tools: List<ToolSpec>,
        context: OptimizationContext
    ): List<ScoredTool> {
        val modeFiltered = filterByMode(tools, context.agentMode)
        val scored = modeFiltered.map { tool ->
            var score = 0.0

            if (tool.name in essentialTools) score += 100.0
            else if (tool.name in highValueTools) score += 50.0

            if (tool.metadata.experimental) score -= 20.0
            if (tool.metadata.deprecated) score -= 100.0

            val recency = context.recentCalls.indexOf(tool.name)
        if (recency >= 0) {
                score += (context.recentCalls.size - recency).toDouble()
            }
        val history = context.sessionHistory.count { it == tool.name }
            score += history * 2.0

            if (tool.category.priority < 50) score += 10.0

            ScoredTool(tool, score, "Relevance ranking")
        }
        return scored.sortedByDescending { it.score }
    }
        fun createSmartPrompt(
        tools: List<ToolSpec>,
        userQuery: String,
        context: OptimizationContext = OptimizationContext()
    ): OptimizationResult {
        val router = com.apex.agent.mts.router.IntelligentRouter(registry)
        val plan = runBlocking { router.route(userQuery) }
        val primaryName = plan.primaryTool?.name
        val alternativeNames = plan.alternatives.map { it.tool.name }.toSet()
        val chainNames = plan.suggestedChain.map { it.name }.toSet()
        val relevantNames = (setOfNotNull(primaryName) + alternativeNames + chainNames)
        val boosted = tools.map { tool ->
            var score = 0.0
            if (tool.name in relevantNames) score += 200.0
            if (tool.name in chainNames) score += 150.0
            if (tool.name == primaryName) score += 300.0
            ScoredTool(tool, score, "Query relevance")
        }.sortedByDescending { it.score }
        return buildTokenAware(boosted.map { it.tool }, context.availableTokens, context)
    }
        fun summarizeToolForPrompt(
        tool: ToolSpec,
        strategy: OptimizationStrategy
    ): ToolPromptDef {
        return when (strategy) {
            OptimizationStrategy.MINIMAL -> ToolPromptDef(
                name = tool.name,
                description = tool.description.take(80),
                parameters = tool.parameters.filter { it.required }.map {
                    ParameterSpec(it.name, it.type, "", required = true)
                },
                details = null
            )
            OptimizationStrategy.BALANCED -> ToolPromptDef(
                name = tool.name,
                description = tool.description.take(200),
                parameters = tool.parameters.map {
                    ParameterSpec(it.name, it.type, it.description.take(80), it.required, it.defaultValue, it.enumValues)
                },
                details = if (tool.detailedDescription.length > tool.description.length) tool.detailedDescription.take(150) else null
            )
            OptimizationStrategy.DETAILED -> ToolPromptDef(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters,
                details = tool.detailedDescription.takeIf { it != tool.description }
            )
        }
    }
        private fun buildMinimal(
        ranked: List<ScoredTool>,
        context: OptimizationContext
    ): OptimizationResult {
        val essential = ranked.filter { it.score >= 100 || it.tool.name in essentialTools }
        val prompts = essential.map { summarizeToolForPrompt(it.tool, OptimizationStrategy.MINIMAL) }
        val excluded = ranked.filter { it !in essential }.map { it.tool.name }
        val tokens = estimateTokens(prompts)
        return OptimizationResult(
            includedTools = prompts,
            excludedTools = excluded,
            totalTokens = tokens,
            strategy = OptimizationStrategy.MINIMAL,
            reasoning = "Minimal mode: included ${prompts.size} essential tools, excluded ${excluded.size}"
        )
    }
        private fun buildBalanced(
        ranked: List<ScoredTool>,
        context: OptimizationContext
    ): OptimizationResult {
        val selected = mutableListOf<ToolSpec>()
        val tokenBudget = context.availableTokens - BASE_OVERHEAD_TOKENS
        var usedTokens = 0

        val byCategory = ranked.groupBy { it.tool.category.id }
        val orderedCategories = byCategory.entries.sortedBy {
            it.value.maxOfOrNull { s -> s.score } ?: 0.0
        }.reversed()
        for ((_, tools) in orderedCategories) {
            val sorted = tools.sortedByDescending { it.score }
        val categoryTools = sorted.take(context.maxToolsPerCategory)
        for (scored in categoryTools) {
                val cost = estimateSingleToolCost(scored.tool, OptimizationStrategy.BALANCED)
        if (usedTokens + cost <= tokenBudget) {
                    selected.add(scored.tool)
                    usedTokens += cost
                }
            }
        }
        if (selected.size < 5) {
            val essential = ranked.filter { it.tool.name in essentialTools && it.tool !in selected }
        for (scored in essential) {
                val cost = estimateSingleToolCost(scored.tool, OptimizationStrategy.BALANCED)
        if (usedTokens + cost <= tokenBudget) {
                    selected.add(scored.tool)
                    usedTokens += cost
                }
            }
        }
        val prompts = selected.map { summarizeToolForPrompt(it, OptimizationStrategy.BALANCED) }
        val excluded = ranked.filter { it.tool !in selected }.map { it.tool.name }
        return OptimizationResult(
            includedTools = prompts,
            excludedTools = excluded,
            totalTokens = usedTokens + BASE_OVERHEAD_TOKENS,
            strategy = OptimizationStrategy.BALANCED,
            reasoning = "Balanced mode: included ${prompts.size} tools across ${orderedCategories.size} categories"
        )
    }
        private fun buildDetailed(
        ranked: List<ScoredTool>,
        context: OptimizationContext
    ): OptimizationResult {
        val selected = ranked.take(50).map { it.tool }
        val prompts = selected.map { summarizeToolForPrompt(it, OptimizationStrategy.DETAILED) }
        val tokens = estimateTokens(prompts)
        return OptimizationResult(
            includedTools = prompts,
            excludedTools = ranked.drop(50).map { it.tool.name },
            totalTokens = tokens,
            strategy = OptimizationStrategy.DETAILED,
            reasoning = "Detailed mode: included ${prompts.size} tools with full descriptions"
        )
    }
        private fun buildTokenAware(
        tools: List<ToolSpec>,
        maxTokens: Int,
        context: OptimizationContext
    ): OptimizationResult {
        val budget = maxTokens - BASE_OVERHEAD_TOKENS
        val scored = tools.map { t ->
            val cost = estimateSingleToolCost(t, context.strategy)
            ScoredTool(t, if (t.name in essentialTools) 1000.0 else 1.0, "Token aware")
        }.sortedByDescending { it.score }
        val selected = mutableListOf<ToolSpec>()
        var used = 0

        for (s in scored) {
            val cost = when (context.strategy) {
                OptimizationStrategy.MINIMAL -> estimateSingleToolCost(s.tool, OptimizationStrategy.MINIMAL)
                OptimizationStrategy.BALANCED -> estimateSingleToolCost(s.tool, OptimizationStrategy.BALANCED)
                OptimizationStrategy.DETAILED -> estimateSingleToolCost(s.tool, OptimizationStrategy.DETAILED)
            }
        if (used + cost <= budget) {
                selected.add(s.tool)
                used += cost
            }
        }
        val prompts = selected.map { summarizeToolForPrompt(it, context.strategy) }
        return OptimizationResult(
            includedTools = prompts,
            excludedTools = tools.filter { it !in selected }.map { it.name },
            totalTokens = used + BASE_OVERHEAD_TOKENS,
            strategy = context.strategy,
            reasoning = "Token-aware: included ${prompts.size}/${tools.size} tools using ${used + BASE_OVERHEAD_TOKENS}/$maxTokens tokens"
        )
    }
        private fun estimateTokenCost(def: ToolPromptDef): Int {
        var cost = NAME_TOKEN_COST
        cost += (def.description.length * DESC_TOKEN_COST_PER_CHAR).toInt()
        cost += def.parameters.sumOf { PARAM_BASE_COST + it.name.length + (it.description.length * DESC_TOKEN_COST_PER_CHAR).toInt() }
        if (def.details != null) {
            cost += (def.details.length * DESC_TOKEN_COST_PER_CHAR).toInt()
        }
        return cost
    }
        fun estimateTokens(defs: List<ToolPromptDef>): Int {
        return defs.sumOf { estimateTokenCost(it) } + CATEGORY_HEADER_COST * 3 + BASE_OVERHEAD_TOKENS
    }
        private fun estimateSingleToolCost(tool: ToolSpec, strategy: OptimizationStrategy): Int {
        return when (strategy) {
            OptimizationStrategy.MINIMAL -> NAME_TOKEN_COST + 20 + tool.parameters.count { it.required } * PARAM_BASE_COST
            OptimizationStrategy.BALANCED -> NAME_TOKEN_COST + 50 + tool.parameters.size * PARAM_BASE_COST
            OptimizationStrategy.DETAILED -> NAME_TOKEN_COST + 80 + tool.parameters.size * PARAM_BASE_COST + 30
        }
    }

    /** 根据Agent模式过滤可用工具 */
    fun filterByMode(tools: List<ToolSpec>, mode: AgentMode): List<ToolSpec> {
        return tools.filter { mode in it.modeConfig.allowedModes }
    }
}

