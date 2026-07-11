package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.CommandHistoryManager
import com.ai.assistance.aiterminal.terminal.TerminalSession
import com.ai.assistance.aiterminal.terminal.TerminalManager
import com.ai.assistance.aiterminal.terminal.ai.TerminalCommandTemplates.getTemplatesByCategory
import com.ai.assistance.aiterminal.terminal.ai.TerminalCommandTemplates.searchTemplates
import com.ai.assistance.aiterminal.terminal.ai.TerminalCommandTemplates.getTemplateById
import com.ai.assistance.aiterminal.terminal.ai.TerminalToolDefinition
import com.ai.assistance.aiterminal.terminal.ai.TerminalToolCallHandler
import com.ai.assistance.aiterminal.terminal.ai.TerminalToolExecutor
import com.ai.assistance.aiterminal.terminal.ai.ToolExecutionResult
import com.ai.assistance.aiterminal.terminal.model.ToolPrompt
import com.ai.assistance.aiterminal.terminal.ai.DangerousCommandPatterns
import com.ai.assistance.aiterminal.terminal.ai.CommandRiskAssessor
import com.ai.assistance.aiterminal.terminal.ai.RiskAssessmentResult
import com.ai.assistance.aiterminal.terminal.ai.OutputSummarizer
import com.ai.assistance.aiterminal.terminal.ai.OutputSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

interface LLMAPI {
    suspend fun generate(prompt: String): String
}

class AITerminalHelper(
    private val context: Context,
    private val onlineLlmApi: LLMAPI
) {
    enum class Mode {
        ONLINE,
        OFFLINE,
        AUTO
    }

    enum class ReasoningMode {
        DEEPSEEK_REASONING,
        STANDARD
    }
    
    var mode: Mode = Mode.AUTO
    var reasoningMode: ReasoningMode = ReasoningMode.DEEPSEEK_REASONING
    
    private val contextCollector = TerminalContextCollector(context)
    private val dialogExecutionManager by lazy {
        DialogExecutionManager(context, onlineLlmApi)
    }
    private val toolCallHandler by lazy {
        TerminalToolCallHandler(context)
    }
    
    private fun getIntelligentHelper(): IntelligentTerminalHelper {
        return IntelligentTerminalHelper(context, contextCollector, getCurrentLlmApi())
    }
    
    private fun getRiskAssessor(): CommandRiskAssessor {
        return CommandRiskAssessor(context, getCurrentLlmApi())
    }
    
    private fun getOutputSummarizer(): OutputSummarizer {
        return OutputSummarizer(context, getCurrentLlmApi())
    }
    
    fun wrapForReasoning(prompt: String): String {
        return if (reasoningMode == ReasoningMode.DEEPSEEK_REASONING && mode != Mode.OFFLINE) {
            buildString {
                appendLine("[系统指令] 你正在 DeepSeek V4 深度推理模式下运行。")
                appendLine("请逐步分析问题，展示你的推理过程，然后给出最终答案。")
                appendLine("对于终端命令任务：先分析系统环境→评估安全风险→规划执行步骤→执行→验证。")
                appendLine()
                appendLine("---")
                appendLine()
                append(prompt)
            }
        } else {
            prompt
        }
    }

    private fun getCurrentLlmApi(): LLMAPI {
        // 本地模型已移除，始终使用在线 API
        return onlineLlmApi
    }
    
    @JvmName("updateMode")
    fun setMode(mode: Mode) {
        this.mode = mode
    }
    
    fun isOfflineMode(): Boolean {
        // 本地模型已移除，不支持离线模式
        return false
    }

    suspend fun naturalLanguageToCommand(prompt: String): String {
        val finalPrompt = wrapForReasoning("""
            将以下自然语言转换为Android终端命令（仅返回命令，不要解释）：
            $prompt
            """.trimIndent()
        )
        val response = getCurrentLlmApi().generate(finalPrompt)
        return response.trim()
    }

    suspend fun naturalLanguageToCommandWithContext(
        userIntent: String,
        sessionId: String? = null
    ): CommandGenerationResult = withContext(Dispatchers.IO) {
        val terminalContext = contextCollector.collectContext(sessionId)
        val request = CommandGenerationRequest(
            userIntent = userIntent,
            context = terminalContext
        )
        getIntelligentHelper().generateCommand(request)
    }

    suspend fun fixCommandError(command: String, errorOutput: String): String? {
        val response = getCurrentLlmApi().generate(
            """
            以下终端命令执行报错，请分析错误原因并提供修复后的命令（仅返回修复后的命令，无法修复则返回null）：
            命令：$command
            错误输出：$errorOutput
            """.trimIndent()
        )
        return if (response.lowercase() != "null") response.trim() else null
    }

    suspend fun fixCommandErrorWithContext(
        failedCommand: String,
        errorOutput: String,
        sessionId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val response = getCurrentLlmApi().generate(
                buildString {
                    appendLine("命令执行失败，请分析原因并提供修复方案。")
                    appendLine()
                    appendLine("【失败的命令】")
                    appendLine(failedCommand)
                    appendLine()
                    appendLine("【错误输出】")
                    appendLine(errorOutput)
                    appendLine()
                    appendLine("请仅返回修复后的命令，不要其他解释。如果无法修复，返回 FAILED")
                }
            )
            extractFixedCommand(response)
        } catch (e: Exception) {
            fixCommandError(failedCommand, errorOutput)
        }
    }

    private fun buildFixPrompt(command: String, error: String, context: TerminalContext): String {
        return buildString {
            appendLine("命令执行失败，请分析原因并提供修复方案。")
            appendLine()
            appendLine("【失败的命令】")
            appendLine(command)
            appendLine()
            appendLine("【错误输出】")
            appendLine(error)
            appendLine()
            appendLine("【当前上下文】")
            appendLine("当前目录: ${context.currentDirectory}")
            appendLine("Root权限: ${if (context.isRootAvailable) "可用" else "不可用"}")
            appendLine()
            appendLine("请仅返回修复后的命令，不要其他解释。如果无法修复，返回 FAILED")
        }
    }

    private fun extractFixedCommand(response: String): String? {
        val lines = response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .filter { !it.startsWith("命令") && !it.startsWith("错误") && !it.startsWith("修复") }

        return lines.firstOrNull { cmd ->
            cmd.length > 1 &&
            !cmd.equals("FAILED", ignoreCase = true) &&
            !cmd.equals("NULL", ignoreCase = true) &&
            (cmd.contains(" ") || cmd.contains("/") || cmd.contains("-"))
        }?.takeIf { it.length < 500 }
    }

    suspend fun explainCommand(command: String): String {
        return getCurrentLlmApi().generate(
            """
            解释以下Android终端命令的功能和使用场景：
            $command
            """.trimIndent()
        )
    }

    suspend fun explainCommandWithContext(
        command: String,
        sessionId: String? = null
    ): CommandExplanation = withContext(Dispatchers.IO) {
        val terminalContext = contextCollector.collectContext(sessionId)
        getIntelligentHelper().explainCommand(command, terminalContext)
    }

    suspend fun suggestNextCommands(currentOutput: String): List<String> {
        val response = getCurrentLlmApi().generate(
            """
            基于以下终端输出，建议接下来可能需要的命令（每行一个命令，仅返回命令列表）：
            $currentOutput
            """.trimIndent()
        )
        return response.lines().filter { it.isNotBlank() }
    }

    suspend fun suggestNextCommandsWithContext(
        currentOutput: String,
        sessionId: String? = null
    ): NextCommandSuggestion = withContext(Dispatchers.IO) {
        val terminalContext = contextCollector.collectContext(sessionId)
        getIntelligentHelper().suggestNextCommands(currentOutput, terminalContext)
    }

    suspend fun getCommandSuggestions(
        query: String? = null,
        category: TemplateCategory? = null
    ): List<TerminalCommandTemplate> = withContext(Dispatchers.IO) {
        when {
            category != null -> getTemplatesByCategory(category)
            !query.isNullOrBlank() -> searchTemplates(query)
            else -> TerminalCommandTemplates.allTemplates.take(20)
        }
    }

    suspend fun getCommandTemplateById(id: String): TerminalCommandTemplate? {
        return getTemplateById(id)
    }

    suspend fun getRelatedTemplates(templateId: String): List<TerminalCommandTemplate> {
        return TerminalCommandTemplates.getRelatedTemplates(templateId)
    }

    suspend fun executeFromTemplate(
        templateId: String,
        params: Map<String, String>,
        sessionId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val template = getTemplateById(templateId)
            ?: throw IllegalArgumentException("Template not found: $templateId")

        val command = getIntelligentHelper().fillTemplate(template, params)
        getIntelligentHelper().updateContext(contextCollector.collectContext(sessionId))

        command
    }

    suspend fun refreshContext(sessionId: String? = null) = withContext(Dispatchers.IO) {
        contextCollector.collectContext(sessionId, forceRefresh = true)
    }

    fun getCurrentContext(): TerminalContext? {
        return null
    }

    // 对话式执行相关方法
    suspend fun startDialogExecution(
        userPrompt: String,
        session: TerminalSession?
    ) {
        dialogExecutionManager.startExecution(userPrompt, session)
    }

    suspend fun respondToDialog(
        userInput: String,
        session: TerminalSession?
    ) {
        dialogExecutionManager.respondToDialog(userInput, session)
    }

    fun getDialogState() = dialogExecutionManager.dialogState

    fun hasActiveDialog() = dialogExecutionManager.hasActiveDialog()

    fun isDialogWaitingForUser() = dialogExecutionManager.isWaitingForUser()

    fun isDialogExecuting() = dialogExecutionManager.isExecuting()

    fun resetDialog() = dialogExecutionManager.resetDialog()

    fun getDialogMessages() = dialogExecutionManager.getDialogMessages()

    suspend fun executeTaskPlan(
        task: String,
        steps: List<String>,
        session: TerminalSession?
    ) {
        dialogExecutionManager.executeTaskPlan(task, steps, session)
    }

    // Tool Calling 相关方法
    suspend fun getToolDefinitions(): String? {
        return toolCallHandler.buildToolDefinitions()
    }

    suspend fun handleToolCalls(toolCallsJson: String): String {
        return toolCallHandler.handleToolCalls(toolCallsJson)
    }

    suspend fun processToolCallResponse(response: String): String {
        return toolCallHandler.processToolCallResponse(response)
    }

    fun getTerminalTools(): List<ToolPrompt> {
        return TerminalToolDefinition.terminalTools
    }

    fun getToolByName(name: String): ToolPrompt? {
        return TerminalToolDefinition.getToolByName(name)
    }

    suspend fun executeTerminalTool(
        toolName: String,
        parameters: Map<String, Any>
    ): ToolExecutionResult {
        val executor = TerminalToolExecutor(context)
        return executor.executeTool(toolName, parameters)
    }

    // 风险评估相关方法
    suspend fun assessCommandRisk(
        command: String,
        sessionId: String? = null
    ): RiskAssessmentResult {
        val context = sessionId?.let { contextCollector.collectContext(it) }
        return getRiskAssessor().assessRisk(command, context)
    }

    suspend fun assessCommandRiskWithAI(
        command: String,
        sessionId: String? = null
    ): RiskAssessmentResult {
        val context = sessionId?.let { contextCollector.collectContext(it) }
        return getRiskAssessor().assessWithAI(command, context)
    }

    fun shouldBlockCommand(result: RiskAssessmentResult): Boolean {
        return getRiskAssessor().shouldBlockCommand(result)
    }

    fun formatRiskReport(result: RiskAssessmentResult): String {
        return getRiskAssessor().formatRiskReport(result)
    }

    fun isHighRiskCommand(command: String): Boolean {
        return DangerousCommandPatterns.isHighRisk(command)
    }

    fun isCriticalRiskCommand(command: String): Boolean {
        return DangerousCommandPatterns.isCriticalRisk(command)
    }

    // 输出摘要相关方法
    suspend fun summarizeOutput(
        output: String,
        command: String? = null,
        maxLength: Int = 500
    ): OutputSummary {
        return getOutputSummarizer().summarize(output, command, maxLength)
    }

    suspend fun quickSummary(output: String): String {
        return getOutputSummarizer().quickSummary(output)
    }

    fun formatSummaryReport(summary: OutputSummary): String {
        return getOutputSummarizer().formatSummaryReport(summary)
    }

    // ==================== 智能代码分析与优化 ====================

    /**
     * 分析命令执行结果并提供优化建议
     */
    suspend fun analyzeAndOptimizeCommand(command: String, output: String): OptimizationSuggestion {
        val prompt = buildString {
            appendLine("分析以下命令及其输出，并提供优化建议：")
            appendLine()
            appendLine("【命令】")
            appendLine(command)
            appendLine()
            appendLine("【输出】")
            appendLine(output)
            appendLine()
            appendLine("请按以下JSON格式返回分析结果：")
            appendLine("""
                {
                    "optimizedCommand": "优化后的命令或null",
                    "explanation": "优化原因说明",
                    "alternatives": ["备选命令1", "备选命令2"],
                    "warnings": ["警告信息"],
                    "performanceTips": ["性能优化建议"]
                }
            """.trimIndent())
        }

        try {
            val response = getCurrentLlmApi().generate(prompt)
            return parseOptimizationResponse(response)
        } catch (e: Exception) {
            return OptimizationSuggestion(
                optimizedCommand = null,
                explanation = "分析失败",
                alternatives = emptyList(),
                warnings = emptyList(),
                performanceTips = emptyList()
            )
        }
    }

    /**
     * 生成 shell 脚本
     */
    suspend fun generateShellScript(requirement: String): ScriptGenerationResult {
        val prompt = buildString {
            appendLine("根据以下需求生成 Android Shell 脚本：")
            appendLine()
            appendLine("【需求】")
            appendLine(requirement)
            appendLine()
            appendLine("请按以下JSON格式返回：")
            appendLine("""
                {
                    "script": "生成的脚本内容",
                    "explanation": "脚本说明",
                    "usage": "使用方法",
                    "dependencies": ["依赖命令或工具"],
                    "riskLevel": "LOW/MEDIUM/HIGH/CRITICAL"
                }
            """.trimIndent())
        }

        try {
            val response = getCurrentLlmApi().generate(prompt)
            return parseScriptGenerationResponse(response)
        } catch (e: Exception) {
            return ScriptGenerationResult(
                script = "",
                explanation = "生成失败",
                usage = "",
                dependencies = emptyList(),
                riskLevel = "HIGH"
            )
        }
    }

    /**
     * 代码解释
     */
    suspend fun explainCode(code: String, language: String = "shell"): CodeExplanation {
        val prompt = buildString {
            appendLine("解释以下${language}代码：")
            appendLine()
            appendLine("```${language}")
            appendLine(code)
            appendLine("```")
            appendLine()
            appendLine("请按以下JSON格式返回详细解释：")
            appendLine("""
                {
                    "summary": "代码功能概述",
                    "detailedExplanation": ["步骤1解释", "步骤2解释"],
                    "keyComponents": {"组件名": "作用说明"},
                    "potentialIssues": ["潜在问题1", "潜在问题2"],
                    "improvementSuggestions": ["改进建议1", "改进建议2"]
                }
            """.trimIndent())
        }

        try {
            val response = getCurrentLlmApi().generate(prompt)
            return parseCodeExplanationResponse(response)
        } catch (e: Exception) {
            return CodeExplanation(
                summary = "无法解释代码",
                detailedExplanation = emptyList(),
                keyComponents = emptyMap(),
                potentialIssues = emptyList(),
                improvementSuggestions = emptyList()
            )
        }
    }

    /**
     * 智能自动补全建议
     */
    suspend fun getAutocompleteSuggestions(prefix: String, currentDirectory: String = ""): List<String> {
        // 首先从历史记录获取建议
        val historyResults = CommandHistoryManager.instance.smartSearch(prefix, 10, currentDirectory)
        val historySuggestions = historyResults.map { it.command }.toMutableList()

        // 如果历史记录不足，使用AI生成建议
        if (historySuggestions.size < 5) {
            val prompt = buildString {
                appendLine("根据前缀 '$prefix' 建议可能的终端命令：")
                appendLine("当前目录: $currentDirectory")
                appendLine("请仅返回命令列表，每行一个，不超过10个。")
            }

            try {
                val response = getCurrentLlmApi().generate(prompt)
                val aiSuggestions = response.lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(10)
                historySuggestions.addAll(aiSuggestions)
            } catch (e: Exception) {
                // 忽略错误，使用历史记录建议
            }
        }

        return historySuggestions.distinct().take(10)
    }

    /**
     * 多步骤任务规划
     */
    suspend fun planMultiStepTask(goal: String): TaskPlanResult {
        val prompt = wrapForReasoning(buildString {
            appendLine("你是一个 Android 任务规划专家。请将以下任务分解为终端命令步骤。")
            appendLine("先分析任务的安全性和可行性，再输出计划。")
            appendLine()
            appendLine("【目标】")
            appendLine(goal)
            appendLine()
            appendLine("请按以下JSON格式返回任务计划：")
            appendLine("""
                {
                    "steps": [
                        {"step": 1, "command": "命令1", "description": "步骤说明", "requiresRoot": false},
                        {"step": 2, "command": "命令2", "description": "步骤说明", "requiresRoot": false}
                    ],
                    "estimatedTime": "估计时间",
                    "notes": ["注意事项1", "注意事项2"],
                    "totalSteps": 2
                }
            """.trimIndent())
        })

        try {
            val response = getCurrentLlmApi().generate(prompt)
            return parseTaskPlanResponse(response)
        } catch (e: Exception) {
            return TaskPlanResult(
                steps = emptyList(),
                estimatedTime = "未知",
                notes = listOf("任务规划失败"),
                totalSteps = 0
            )
        }
    }

    // ==================== 解析方法 ====================

    private fun parseOptimizationResponse(response: String): OptimizationSuggestion {
        return try {
            val jsonStr = extractJson(response)
            val json = org.json.JSONObject(jsonStr)
            OptimizationSuggestion(
                optimizedCommand = json.optString("optimizedCommand", null).takeIf { it.isNotEmpty() && it != "null" },
                explanation = json.optString("explanation", ""),
                alternatives = parseJsonArray(json.optJSONArray("alternatives")),
                warnings = parseJsonArray(json.optJSONArray("warnings")),
                performanceTips = parseJsonArray(json.optJSONArray("performanceTips"))
            )
        } catch (e: Exception) {
            OptimizationSuggestion(
                optimizedCommand = null,
                explanation = response,
                alternatives = emptyList(),
                warnings = emptyList(),
                performanceTips = emptyList()
            )
        }
    }

    private fun parseScriptGenerationResponse(response: String): ScriptGenerationResult {
        return try {
            val jsonStr = extractJson(response)
            val json = org.json.JSONObject(jsonStr)
            ScriptGenerationResult(
                script = json.optString("script", ""),
                explanation = json.optString("explanation", ""),
                usage = json.optString("usage", ""),
                dependencies = parseJsonArray(json.optJSONArray("dependencies")),
                riskLevel = json.optString("riskLevel", "MEDIUM")
            )
        } catch (e: Exception) {
            ScriptGenerationResult(
                script = response,
                explanation = "",
                usage = "",
                dependencies = emptyList(),
                riskLevel = "HIGH"
            )
        }
    }

    private fun parseCodeExplanationResponse(response: String): CodeExplanation {
        return try {
            val jsonStr = extractJson(response)
            val json = org.json.JSONObject(jsonStr)
            
            val components = mutableMapOf<String, String>()
            json.optJSONObject("keyComponents")?.let { compJson ->
                val keys = compJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    components[key] = compJson.optString(key, "")
                }
            }

            CodeExplanation(
                summary = json.optString("summary", ""),
                detailedExplanation = parseJsonArray(json.optJSONArray("detailedExplanation")),
                keyComponents = components,
                potentialIssues = parseJsonArray(json.optJSONArray("potentialIssues")),
                improvementSuggestions = parseJsonArray(json.optJSONArray("improvementSuggestions"))
            )
        } catch (e: Exception) {
            CodeExplanation(
                summary = response,
                detailedExplanation = emptyList(),
                keyComponents = emptyMap(),
                potentialIssues = emptyList(),
                improvementSuggestions = emptyList()
            )
        }
    }

    private fun parseTaskPlanResponse(response: String): TaskPlanResult {
        return try {
            val jsonStr = extractJson(response)
            val json = org.json.JSONObject(jsonStr)
            
            val steps = mutableListOf<TaskStep>()
            json.optJSONArray("steps")?.let { stepsArray ->
                for (i in 0 until stepsArray.length()) {
                    val stepJson = stepsArray.getJSONObject(i)
                    steps.add(TaskStep(
                        step = stepJson.optInt("step", i + 1),
                        command = stepJson.optString("command", ""),
                        description = stepJson.optString("description", ""),
                        requiresRoot = stepJson.optBoolean("requiresRoot", false)
                    ))
                }
            }

            TaskPlanResult(
                steps = steps,
                estimatedTime = json.optString("estimatedTime", "未知"),
                notes = parseJsonArray(json.optJSONArray("notes")),
                totalSteps = steps.size
            )
        } catch (e: Exception) {
            TaskPlanResult(
                steps = emptyList(),
                estimatedTime = "未知",
                notes = listOf("解析失败: $response"),
                totalSteps = 0
            )
        }
    }

    private fun extractJson(response: String): String {
        val startIdx = response.indexOf('{')
        val endIdx = response.lastIndexOf('}')
        return if (startIdx >= 0 && endIdx > startIdx) {
            response.substring(startIdx, endIdx + 1)
        } else {
            response
        }
    }

    private fun parseJsonArray(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    // ==================== 数据类 ====================

    data class OptimizationSuggestion(
        val optimizedCommand: String?,
        val explanation: String,
        val alternatives: List<String>,
        val warnings: List<String>,
        val performanceTips: List<String>
    )

    data class ScriptGenerationResult(
        val script: String,
        val explanation: String,
        val usage: String,
        val dependencies: List<String>,
        val riskLevel: String
    )

    data class CodeExplanation(
        val summary: String,
        val detailedExplanation: List<String>,
        val keyComponents: Map<String, String>,
        val potentialIssues: List<String>,
        val improvementSuggestions: List<String>
    )

    data class TaskStep(
        val step: Int,
        val command: String,
        val description: String,
        val requiresRoot: Boolean
    )

    data class TaskPlanResult(
        val steps: List<TaskStep>,
        val estimatedTime: String,
        val notes: List<String>,
        val totalSteps: Int
    )
}

suspend fun executeWithAI(
    session: TerminalSession,
    prompt: String,
    onlineLlmApi: LLMAPI,
    context: Context,
    mode: AITerminalHelper.Mode = AITerminalHelper.Mode.AUTO
) {
    val aiHelper = AITerminalHelper(context, onlineLlmApi)
    aiHelper.setMode(mode)
    val command = aiHelper.naturalLanguageToCommand(prompt)
    TerminalManager.getInstance(context).executeCommand(session.sessionId, command)
}
