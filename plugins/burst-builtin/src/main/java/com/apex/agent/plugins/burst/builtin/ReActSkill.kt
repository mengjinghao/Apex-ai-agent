package com.apex.agent.plugins.burst.builtin

import android.util.Log
import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class ReActSkill : IBurstSkill {
    companion object {
        private const val TAG = "ReActSkill"
    }
    override lateinit var manifest: BurstSkillManifest

    private lateinit var context: BurstSkillContext
    // 修复 R8：isPaused 跨线程读写需 @Volatile 保证可见性
    @Volatile private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val maxIterations = 10
    // 修复 R7：reasoningSteps 原为实例字段，并发调用 execute() 时 clear()+add() 交叉
    // 改为方法局部变量，不挂在实例上。这里仅保留作为类型声明的占位（实际使用见 execute 内局部变量）

    // 修复 R7：统计字段改为原子，避免并发更新丢失
    private val totalExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    private val successfulExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalExecutionTimeMs = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        manifest = BurstSkillManifest(
            skillId = "reasoning.react",
            skillName = "ReAct (Reasoning + Acting)",
            version = "1.0.0",
            description = "ReAct推理策略，结合推理和工具调用，适用于需要外部信息检索的任务",
            author = "Apex Agent",
            tags = listOf("reasoning", "react", "tool-calling", "information-retrieval"),
            priority = 90,
            capabilities = listOf(
                "reasoned_action_planning",
                "tool_execution",
                "observation_processing",
                "iterative_refinement"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.context = context
        registerConfig(context)
    }

    private fun registerConfig(context: BurstSkillContext) {
        val configService = context.configService ?: return
        configService.registerSchema(manifest.skillId, ConfigSchema(
            pluginId = manifest.skillId,
            sections = listOf(
                ConfigSection(
                    name = "general",
                    label = "General",
                    description = "Basic ReAct settings",
                    fields = listOf(
                        ConfigFieldDef("max_iterations", "Max Iterations", "Maximum reasoning iterations", ConfigType.INT, "10", false, "", emptyList(), 1.0, 50.0, false),
                        ConfigFieldDef("temperature", "Temperature", "LLM sampling temperature", ConfigType.FLOAT, "0.7", false, "", emptyList(), 0.0, 2.0, false),
                        ConfigFieldDef("max_tokens", "Max Tokens", "Max tokens per LLM call", ConfigType.INT, "512", false, "", emptyList(), 64.0, 4096.0, false),
                        ConfigFieldDef("enable_tool_calling", "Enable Tool Calling", "Allow the skill to call external tools", ConfigType.BOOLEAN, "true", false, "", emptyList(), 0.0, 0.0, false)
                    )
                ),
                ConfigSection(
                    name = "advanced",
                    label = "Advanced",
                    description = "Advanced configuration options",
                    fields = listOf(
                        ConfigFieldDef("model_override", "Model Override", "Override default LLM model", ConfigType.STRING, "", false, "e.g., claude-3.5-sonnet", emptyList(), 0.0, 0.0, false),
                        ConfigFieldDef("api_key", "API Key", "Optional API key for external services", ConfigType.PASSWORD, "", false, "", emptyList(), 0.0, 0.0, true),
                        ConfigFieldDef("reasoning_mode", "Reasoning Mode", "Reasoning strategy to use", ConfigType.ENUM, "balanced", false, "", listOf("fast", "balanced", "thorough"), 0.0, 0.0, false)
                    )
                )
            )
        ))
    }

    private fun loadConfig(): ReActConfig {
        val cs = context.configService ?: return ReActConfig()
        val sid = manifest.skillId
        return ReActConfig(
            maxIterations = cs.getInt(sid, "max_iterations", 10),
            temperature = cs.getFloat(sid, "temperature", 0.7f),
            maxTokens = cs.getInt(sid, "max_tokens", 512),
            enableToolCalling = cs.getBoolean(sid, "enable_tool_calling", true),
            modelOverride = cs.getString(sid, "model_override", ""),
            reasoningMode = cs.getString(sid, "reasoning_mode", "balanced")
        )
    }

    private data class ReActConfig(
        val maxIterations: Int = 10,
        val temperature: Float = 0.7f,
        val maxTokens: Int = 512,
        val enableToolCalling: Boolean = true,
        val modelOverride: String = "",
        val reasoningMode: String = "balanced"
    )

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()

        try {
            if (isPaused) {
                return@runBlocking BurstSkillResult(success = false, errorMessage = "Skill paused")
            }

            val config = loadConfig()
            // 修复 R7：reasoningSteps 改为局部变量，避免并发调用交叉污染
            val reasoningSteps = mutableListOf<ReActStep>()
            val llm = context.llmService
            var currentThought = task.description
            var finalAnswer: String? = null

            // 修复 R2：旧版 repeat{... return@repeat ...} 不会跳出循环，
            // finalAnswer 被后续迭代覆盖。改为 for + break@loop 显式跳出。
            loop@ for (iteration in 0 until config.maxIterations) {
                if (isPaused) break@loop

                val thought = think(currentThought, iteration, llm, config)
                reasoningSteps.add(ReActStep.Thought(thought))

                val action = decideAction(thought, llm, config)

                if (action == null) {
                    finalAnswer = thought
                    break@loop
                }

                val actionResult = executeAction(action, task)
                reasoningSteps.add(ReActStep.Action(action, actionResult))

                val observation = observe(actionResult, llm, config)
                reasoningSteps.add(ReActStep.Observation(observation))

                currentThought = """
                    之前的推理：$thought
                    执行的行动：${action.name}
                    观察到的结果：$observation
                    基于以上信息，继续推理：
                """.trimIndent()
            }

            val totalTime = System.currentTimeMillis() - startTime

            totalExecutions.incrementAndGet()
            if (finalAnswer != null) successfulExecutions.incrementAndGet()
            totalExecutionTimeMs.addAndGet(totalTime)

            val reasoningLog = formatReasoningLog(reasoningSteps, task.id)

            BurstSkillResult(
                success = finalAnswer != null,
                output = finalAnswer ?: "未能得出最终答案",
                logs = reasoningLog,
                metrics = SkillMetrics(
                    executionTimeMs = totalTime,
                    stepsCompleted = reasoningSteps.size,
                    tokensProcessed = estimateTokens(finalAnswer ?: "")
                )
            )

        } catch (e: Exception) {
            // 修复 R5：旧版 catch(Exception) 会吞掉 CancellationException，
            // 破坏结构化并发。显式重新抛出。
            if (e is kotlinx.coroutines.CancellationException) throw e
            totalExecutions.incrementAndGet()
            BurstSkillResult(success = false, errorMessage = "ReAct推理出错：${e.message}")
        }
    }

    private suspend fun think(context: String, step: Int, llm: ILLMService?, config: ReActConfig): String {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("你是一个ReAct推理Agent。当前是第${step + 1}步推理。")
                appendLine()
                appendLine("任务上下文：")
                appendLine(context)
                appendLine()
                appendLine("请分析当前状态，思考下一步应该做什么。")
                appendLine("如果需要使用工具，请明确说明。如果已经得到答案，请直接给出最终答案。")
            }
            return llm.generate(prompt, maxTokens = config.maxTokens)
        }
        return "步骤${step + 1} 的推理分析：基于给定的信息进行逻辑推理"
    }

    private suspend fun decideAction(thought: String, llm: ILLMService?, config: ReActConfig): ReActAction? {
        if (!config.enableToolCalling) return null

        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("根据以下推理，判断是否需要调用工具：")
                appendLine()
                appendLine(thought)
                appendLine()
                appendLine("如果需要工具，请输出 JSON 格式：{\"tool\": \"tool_name\", \"params\": {...}}")
                appendLine("可用工具：search, calculate, get_time, file_read, web_fetch")
                appendLine("如果不需要工具，请输出：NO_TOOL_NEEDED")
            }
            val response = llm.generate(prompt, maxTokens = 128)
            return parseActionFromLLM(response)
        }

        val toolKeywords = listOf(
            "查找", "搜索", "获取", "查询", "计算",
            "search", "find", "lookup", "calculate", "get"
        )
        val needsTool = toolKeywords.any { thought.contains(it, ignoreCase = true) }
        return if (needsTool) {
            ReActAction(name = "search", parameters = mapOf("query" to extractQuery(thought)))
        } else null
    }

    private fun parseActionFromLLM(response: String): ReActAction? {
        if (response.contains("NO_TOOL_NEEDED", ignoreCase = true)) return null

        try {
            val jsonRegex = """\{[^}]+\}""".toRegex()
            val match = jsonRegex.find(response)
            if (match != null) {
                val json = match.value
                val toolName = Regex(""""tool"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                if (toolName != null) {
                    val paramsMap = mutableMapOf<String, String>()
                    val paramsRegex = Regex(""""(\w+)"\s*:\s*"([^"]+)"""")
                    paramsRegex.findAll(json).forEach { paramMatch ->
                        val key = paramMatch.groupValues[1]
                        val value = paramMatch.groupValues[2]
                        if (key != "tool") {
                            paramsMap[key] = value
                        }
                    }
                    return ReActAction(name = toolName, parameters = paramsMap)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "extractActionFromXml failed", e) }

        return null
    }

    private fun extractQuery(thought: String): String {
        val patterns = listOf(
            Regex("查找(.+)"),
            Regex("搜索(.+)"),
            Regex("查询(.+)"),
            Regex("find (.+)"),
            Regex("search (.+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(thought)
            if (match != null) return match.groupValues[1].trim()
        }
        return thought.take(50)
    }

    /**
     * 修复 R1：旧版 executeAction 所有工具调用都返回硬编码 mock 字符串，
     * ReAct 闭环基于假数据推理。新版优先转发到 BurstKernel 的真实 skill
     * （通过 context.kernel.executeSkill("tool_$name", task.copy(...)) ），
     * 失败时降级到 mock（带 [mock] 前缀让调用方能识别）。
     */
    private suspend fun executeAction(action: ReActAction, originalTask: BurstTask): String {
        // 1) 尝试转发到 BurstKernel 真实 skill（skillId 形如 tool_search / tool_calculate）
        val kernel = context.kernel
        if (kernel != null) {
            val toolSkillId = if (action.name.startsWith("tool_")) action.name else "tool_${action.name}"
            val toolTask = originalTask.copy(
                id = "${originalTask.id}_tool_${action.name}",
                name = action.name,
                description = action.parameters.entries.joinToString(", ") { "${it.key}=${it.value}" },
                input = BurstInput(
                    text = action.parameters["query"] ?: action.parameters["expression"]
                              ?: action.parameters["url"] ?: action.parameters["path"] ?: "",
                    parameters = action.parameters
                )
            )
            val result = runCatching { kernel.executeSkill(toolSkillId, toolTask) }
                .getOrNull()
            if (result != null && result.success) {
                val output = result.output
                if (!output.isNullOrBlank()) {
                    return output
                }
            }
            // result 失败则降级到下面的 mock（保留 ReAct 闭环可运行性）
        }

        // 2) 降级：mock 字符串（与原版行为一致），但加上 [mock] 前缀让调用方能识别
        return when (action.name) {
            "search" -> "[mock] 搜索「${action.parameters["query"]}」的结果：找到10条相关信息。"
            "calculate" -> {
                val expr = action.parameters["expression"] ?: action.parameters["query"] ?: "0"
                runCatching { "[mock] 计算结果：${evalExpression(expr)}" }
                    .getOrDefault("[mock] 计算结果：42")
            }
            "get_time" -> "[mock] 当前时间：${System.currentTimeMillis()}"
            "file_read" -> "[mock] 文件内容：从${action.parameters["path"]}读取的模拟内容"
            "web_fetch" -> "[mock] 网页抓取：已获取${action.parameters["url"]}的模拟内容"
            else -> "[mock] 执行完成"
        }
    }

    private fun evalExpression(expr: String): String {
        return try {
            val cleaned = expr.replace(" ", "")
            if (cleaned.matches(Regex("[0-9+\\-*/().]+"))) {
                val result = evalSimple(cleaned)
                result.toString()
            } else "42"
        } catch (_: Exception) { "42" }
    }

    private fun evalSimple(expr: String): Double {
        val list = mutableListOf<String>()
        var current = ""
        for (ch in expr) {
            if (ch in "+-*/()") {
                if (current.isNotEmpty()) list.add(current)
                list.add(ch.toString())
                current = ""
            } else {
                current += ch
            }
        }
        if (current.isNotEmpty()) list.add(current)
        return evaluateTokens(list)
    }

    private fun evaluateTokens(tokens: MutableList<String>): Double {
        var i = 0
        lateinit var parseExpr: () -> Double
        lateinit var parseTerm: () -> Double
        lateinit var parseFactor: () -> Double

        parseFactor = {
            if (tokens[i] == "(") { i++; val r = parseExpr(); i++; r }
            else tokens[i].toDouble().also { i++ }
        }
        parseTerm = {
            var result = parseFactor()
            while (i < tokens.size) {
                when (tokens[i]) {
                    "*" -> { i++; result *= parseFactor() }
                    "/" -> { i++; result /= parseFactor() }
                    else -> break
                }
            }
            result
        }
        parseExpr = {
            var result = parseTerm()
            while (i < tokens.size) {
                when (tokens[i]) {
                    "+" -> { i++; result += parseTerm() }
                    "-" -> { i++; result -= parseTerm() }
                    else -> break
                }
            }
            result
        }
        return parseExpr()
    }

    private suspend fun observe(result: String, llm: ILLMService?, config: ReActConfig): String {
        if (llm != null && llm.isAvailable()) {
            return llm.generate("分析以下工具执行结果并提取关键信息：\n$result", maxTokens = config.maxTokens / 2)
        }
        return "已获取结果：$result"
    }

    private fun formatReasoningLog(reasoningSteps: List<ReActStep>, taskId: String): List<ExecutionLog> {
        return reasoningSteps.mapIndexed { index, step ->
            val message = when (step) {
                is ReActStep.Thought -> "Thought: ${step.content}"
                is ReActStep.Action -> "Action: ${step.action.name} -> ${step.result}"
                is ReActStep.Observation -> "Observation: ${step.content}"
            }
            ExecutionLog(
                id = "react_log_$index",
                taskId = taskId,
                skillId = "reasoning.react",
                level = LogLevel.INFO,
                message = message,
                timestamp = System.currentTimeMillis(),
                metadata = emptyMap()
            )
        }
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it in '\u4e00'..'\u9fff' }
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val otherChars = text.length - chineseChars - englishChars
        return (chineseChars * 1.5 + englishChars * 0.25 + otherChars * 0.5).toInt()
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this

    override fun evaluate(): Float {
        val total = totalExecutions.get()
        if (total == 0) return 0.5f
        val successRate = successfulExecutions.get().toFloat() / total
        val avgTime = totalExecutionTimeMs.get().toFloat() / total
        val timeEfficiency = (10000f / (avgTime + 1)).coerceIn(0f, 1f)
        return successRate * 0.8f + timeEfficiency * 0.2f
    }

    data class ReActAction(
        val name: String,
        val parameters: Map<String, String>
    )

    sealed class ReActStep {
        data class Thought(val content: String) : ReActStep()
        data class Action(val action: ReActAction, val result: String) : ReActStep()
        data class Observation(val content: String) : ReActStep()
    }
}
