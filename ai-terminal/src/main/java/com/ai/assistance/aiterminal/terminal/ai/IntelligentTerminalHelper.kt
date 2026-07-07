package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandGenerationRequest(
    val userIntent: String,
    val context: TerminalContext? = null,
    val suggestedTemplates: List<TerminalCommandTemplate> = emptyList(),
    val preferRoot: Boolean = false,
    val conversationHistory: List<Pair<String, String>> = emptyList()
)

data class CommandGenerationResult(
    val command: String,
    val confidence: Float,
    val matchedTemplate: TerminalCommandTemplate? = null,
    val explanation: String,
    val alternativeCommands: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class CommandExplanation(
    val command: String,
    val explanation: String,
    val detailedSteps: List<String>,
    val relatedCommands: List<String>,
    val riskAssessment: RiskAssessment
)

data class RiskAssessment(
    val level: RiskLevel,
    val warnings: List<String>,
    val precautions: List<String>
)

data class NextCommandSuggestion(
    val commands: List<String>,
    val reasons: List<String>,
    val basedOnOutput: String
)

class IntelligentTerminalHelper(
    private val context: Context,
    private val contextCollector: TerminalContextCollector,
    private val llmApi: LLMAPI
) {
    constructor(context: Context, contextCollector: TerminalContextCollector) : this(
        context = context,
        contextCollector = contextCollector,
        llmApi = object : LLMAPI {
            override suspend fun generate(prompt: String): String = "LLM not configured"
        }
    )

    private var lastContext: TerminalContext? = null
    private var lastGeneratedCommands: List<String> = emptyList()

    suspend fun generateCommand(request: CommandGenerationRequest): CommandGenerationResult = withContext(Dispatchers.IO) {
        val context = request.context ?: lastContext ?: contextCollector.collectContext()

        val templateMatches = matchTemplates(request.userIntent)
        val suggestedTemplates = if (templateMatches.isNotEmpty()) {
            templateMatches
        } else {
            TerminalCommandTemplates.searchTemplates(request.userIntent).take(3)
        }

        val prompt = buildGenerationPrompt(request, context, suggestedTemplates)

        try {
            val response = llmApi.generate(prompt)
            parseAIResponse(response, suggestedTemplates)
        } catch (e: Exception) {
            fallbackToTemplate(request, suggestedTemplates)
        }
    }

    private fun matchTemplates(intent: String): List<TerminalCommandTemplate> {
        val lowerIntent = intent.lowercase()
        val keywords = mapOf(
            "文件" to listOf("file", "list", "find", "search", "copy", "move", "delete", "ls", "cat", "cd"),
            "应用" to listOf("app", "package", "install", "uninstall", "pm", "apk"),
            "系统" to listOf("system", "info", "property", "getprop", "version"),
            "网络" to listOf("network", "wifi", "ping", "dns", "ip", "connect"),
            "进程" to listOf("process", "ps", "kill", "top", "cpu", "memory"),
            "权限" to listOf("permission", "grant", "revoke", "allow", "deny"),
            "屏幕" to listOf("screen", "screenshot", "record", "tap", "touch"),
            "日志" to listOf("log", "logcat", "debug", "error", "crash"),
            "备份" to listOf("backup", "restore", "save", "export", "import"),
            "设备" to listOf("device", "battery", "power", "reboot", "shutdown")
        )

        val matchedCategories = keywords.filter { (_, kws) ->
            kws.any { kw -> lowerIntent.contains(kw) }
        }.keys

        return if (matchedCategories.isNotEmpty()) {
            matchedCategories.flatMap { category ->
                when (category) {
                    "文件" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.FILE_OPERATIONS)
                    "应用" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.PACKAGE_MANAGEMENT)
                    "系统" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.SYSTEM_INFO)
                    "网络" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.NETWORK)
                    "进程" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.PROCESS)
                    "权限" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.PERMISSIONS)
                    "屏幕" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.DEVICE_CONTROL)
                    "日志" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.DEVELOPMENT)
                    "备份" -> TerminalCommandTemplates.getTemplatesByCategory(TemplateCategory.BACKUP)
                    "设备" -> listOf(
                        TerminalCommandTemplates.getTemplateById("battery_stats"),
                        TerminalCommandTemplates.getTemplateById("sys_info")
                    ).filterNotNull()
                    else -> emptyList()
                }
            }.distinctBy { it.id }.take(5)
        } else {
            emptyList()
        }
    }

    private fun buildGenerationPrompt(
        request: CommandGenerationRequest,
        context: TerminalContext,
        templates: List<TerminalCommandTemplate>
    ): String {
        return buildString {
            appendLine("你是一个专业的 Android 终端命令生成助手。")
            appendLine("请先分析用户意图的安全性和可行性，再生成精确的终端命令。")
            appendLine()
            appendLine("【推理步骤】")
            appendLine("1. 分析用户意图属于哪类操作（文件/应用/系统/网络/进程）")
            appendLine("2. 检查是否有匹配的命令模板可以复用")
            appendLine("3. 评估命令是否需要 root 权限及风险等级")
            appendLine("4. 生成最终的 Shell 命令")
            appendLine()
            appendLine("【用户意图】")
            appendLine(request.userIntent)
            appendLine()

            appendLine("【当前终端上下文】")
            appendLine(contextCollector.buildContextPrompt(context, includeHistory = true))
            appendLine()

            if (templates.isNotEmpty()) {
                appendLine("【相关命令模板】")
                templates.forEach { template ->
                    appendLine("- ${template.name}: ${template.command}")
                    appendLine("  说明: ${template.description}")
                    if (template.parameters.isNotEmpty()) {
                        appendLine("  参数: ${template.parameters.joinToString { "${it.name}(${it.description})" }}")
                    }
                    appendLine("  风险: ${template.riskLevel.displayName}")
                    appendLine()
                }
                appendLine()
            }

            appendLine("【生成要求】")
            appendLine("1. 根据用户意图和上下文，生成最合适的终端命令")
            appendLine("2. 如果有匹配的命令模板，优先使用模板中的命令格式")
            appendLine("3. 命令必须适配 Android Shell 环境")
            appendLine("4. Root 命令使用 su -c 包装，并确认设备有 root")
            appendLine("5. 考虑当前目录、环境变量和历史命令的上下文相关性")
            appendLine("6. 若命令有破坏性，必须在 warnings 中明确提示")
            appendLine()
            appendLine("请按以下 JSON 格式返回（不要包含 markdown 代码块标记）：")
            appendLine("""
                {
                    "command": "生成的完整 Shell 命令",
                    "confidence": 0.0-1.0,
                    "explanation": "命令作用和原理的简要说明",
                    "alternativeCommands": ["备选命令1"],
                    "warnings": ["安全或兼容性警告"]
                }
            """.trimIndent())
        }
    }

    private fun parseAIResponse(response: String, templates: List<TerminalCommandTemplate>): CommandGenerationResult {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            val command = json.optString("command", "").trim()
            val confidence = json.optDouble("confidence", 0.5).toFloat()
            val explanation = json.optString("explanation", "AI生成")
            val warnings = json.optJSONArray("warnings")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val alternatives = json.optJSONArray("alternativeCommands")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            val matchedTemplate = templates.find { t ->
                command.startsWith(t.command.split(" ")[0]) ||
                t.command.split(" ").any { part -> part.isNotEmpty() && command.contains(part.take(4)) }
            }

            lastGeneratedCommands = listOf(command) + alternatives

            CommandGenerationResult(
                command = command,
                confidence = confidence,
                matchedTemplate = matchedTemplate,
                explanation = explanation,
                alternativeCommands = alternatives,
                warnings = warnings
            )
        } catch (e: Exception) {
            fallbackParse(response, templates)
        }
    }

    private fun extractJsonFromResponse(response: String): String {
        val startIdx = response.indexOf('{')
        val endIdx = response.lastIndexOf('}')
        return if (startIdx >= 0 && endIdx > startIdx) {
            response.substring(startIdx, endIdx + 1)
        } else {
            response
        }
    }

    private fun fallbackParse(response: String, templates: List<TerminalCommandTemplate>): CommandGenerationResult {
        val lines = response.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val potentialCommand = lines.firstOrNull { !it.contains(":") && (it.contains(" ") || it.contains("/")) } ?: "echo 'Parsing failed'"
        val matchedTemplate = templates.find { potentialCommand.startsWith(it.command.split(" ")[0]) }

        return CommandGenerationResult(
            command = potentialCommand.trim(),
            confidence = 0.3f,
            matchedTemplate = matchedTemplate,
            explanation = "基于模板匹配生成",
            warnings = listOf("AI响应解析失败，使用降级方案")
        )
    }

    private fun fallbackToTemplate(
        request: CommandGenerationRequest,
        templates: List<TerminalCommandTemplate>
    ): CommandGenerationResult {
        if (templates.isNotEmpty()) {
            val bestMatch = templates.first()
            return CommandGenerationResult(
                command = bestMatch.command,
                confidence = 0.6f,
                matchedTemplate = bestMatch,
                explanation = "基于模板: ${bestMatch.name}",
                warnings = if (bestMatch.riskLevel != RiskLevel.LOW) listOf("${bestMatch.riskLevel.displayName}操作") else emptyList()
            )
        }

        return CommandGenerationResult(
            command = "echo '${request.userIntent}'",
            confidence = 0.1f,
            explanation = "无法生成命令，请手动输入",
            warnings = listOf("未找到匹配模板")
        )
    }

    suspend fun explainCommand(command: String, context: TerminalContext? = null): CommandExplanation = withContext(Dispatchers.IO) {
        val terminalContext = context ?: lastContext ?: contextCollector.collectContext()

        val template = TerminalCommandTemplates.allTemplates.find {
            command.startsWith(it.command.split(" ")[0]) ||
            it.relatedCommands.any { related -> command.startsWith(related.split(" ")[0]) }
        }

        val prompt = buildExplanationPrompt(command, terminalContext, template)

        try {
            val response = llmApi.generate(prompt)
            parseExplanationResponse(response, command, template)
        } catch (e: Exception) {
            fallbackExplanation(command, template)
        }
    }

    private fun buildExplanationPrompt(
        command: String,
        context: TerminalContext,
        template: TerminalCommandTemplate?
    ): String {
        return buildString {
            appendLine("你是一个 Android Shell 命令解释专家。请分步解释以下命令的工作原理和影响。")
            appendLine()
            appendLine("【命令】")
            appendLine(command)
            appendLine()

            if (template != null) {
                appendLine("【模板信息】")
                appendLine("名称: ${template.name}")
                appendLine("说明: ${template.description}")
                appendLine("风险等级: ${template.riskLevel.displayName}")
                appendLine()
            }

            appendLine("【当前上下文】")
            appendLine("当前目录: ${context.currentDirectory}")
            appendLine("Android API: ${context.sdkVersion}")
            appendLine("Root权限: ${if (context.isRootAvailable) "可用" else "不可用"}")
            appendLine("SELinux: ${if (context.isSELinuxEnforcing) "Enforcing" else "Permissive"}")
            appendLine()

            appendLine("请按以下 JSON 格式返回解释：")
            appendLine("""
                {
                    "explanation": "简洁的功能说明（一句话）",
                    "detailedSteps": ["命令组成部分的逐项说明"],
                    "relatedCommands": ["功能相似或互补的命令"],
                    "riskAssessment": {
                        "level": "LOW/MEDIUM/HIGH/CRITICAL",
                        "warnings": ["执行该命令的潜在风险"],
                        "precautions": ["安全执行该命令的注意事项"]
                    }
                }
            """.trimIndent())
        }
    }

    private fun parseExplanationResponse(
        response: String,
        command: String,
        template: TerminalCommandTemplate?
    ): CommandExplanation {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            val riskJson = json.optJSONObject("riskAssessment")
            val riskLevel = riskJson?.optString("level")?.let {
                RiskLevel.valueOf(it.uppercase())
            } ?: template?.riskLevel ?: RiskLevel.LOW

            CommandExplanation(
                command = command,
                explanation = json.optString("explanation", "AI生成解释"),
                detailedSteps = json.optJSONArray("detailedSteps")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                relatedCommands = json.optJSONArray("relatedCommands")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: template?.relatedCommands ?: emptyList(),
                riskAssessment = RiskAssessment(
                    level = riskLevel,
                    warnings = riskJson?.optJSONArray("warnings")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    precautions = riskJson?.optJSONArray("precautions")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                )
            )
        } catch (e: Exception) {
            fallbackExplanation(command, template)
        }
    }

    private fun fallbackExplanation(command: String, template: TerminalCommandTemplate?): CommandExplanation {
        return CommandExplanation(
            command = command,
            explanation = template?.description ?: "终端命令",
            detailedSteps = listOf(
                "这是一个 Android Shell 命令",
                "执行前请确认操作风险",
                "重要操作建议先备份数据"
            ),
            relatedCommands = template?.relatedCommands ?: emptyList(),
            riskAssessment = RiskAssessment(
                level = template?.riskLevel ?: RiskLevel.LOW,
                warnings = if (template?.riskLevel?.let { it.ordinal >= RiskLevel.HIGH.ordinal } == true) {
                    listOf("${template.riskLevel.displayName}操作，请谨慎执行")
                } else emptyList(),
                precautions = listOf("建议先在小范围测试", "重要数据提前备份")
            )
        )
    }

    suspend fun suggestNextCommands(
        currentOutput: String,
        context: TerminalContext? = null
    ): NextCommandSuggestion = withContext(Dispatchers.IO) {
        val terminalContext = context ?: lastContext ?: contextCollector.collectContext()
        val historyCommands = lastGeneratedCommands.take(5)

        val prompt = buildSuggestionPrompt(currentOutput, terminalContext, historyCommands)

        try {
            val response = llmApi.generate(prompt)
            parseSuggestionResponse(response, currentOutput)
        } catch (e: Exception) {
            fallbackSuggestion(currentOutput, terminalContext)
        }
    }

    private fun buildSuggestionPrompt(
        output: String,
        context: TerminalContext,
        historyCommands: List<String>
    ): String {
        return buildString {
            appendLine("你是 Android 终端操作专家。基于以下输出和操作历史，推荐下一步最合理的命令。")
            appendLine()
            appendLine("【输出内容】")
            appendLine(output.take(1500))
            appendLine()

            appendLine("【当前状态】")
            appendLine("当前目录: ${context.currentDirectory}")
            appendLine("历史命令: ${historyCommands.joinToString(" -> ")}")
            appendLine("Root权限: ${if (context.isRootAvailable) "可用" else "不可用"}")
            appendLine()

            appendLine("请按以下 JSON 格式返回（推荐 1-3 个命令）：")
            appendLine("""
                {
                    "commands": ["命令1", "命令2"],
                    "reasons": ["为什么推荐该命令"]
                }
            """.trimIndent())
        }
    }

    private fun parseSuggestionResponse(response: String, output: String): NextCommandSuggestion {
        return try {
            val jsonStr = extractJsonFromResponse(response)
            val json = org.json.JSONObject(jsonStr)

            NextCommandSuggestion(
                commands = json.optJSONArray("commands")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                reasons = json.optJSONArray("reasons")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                basedOnOutput = output
            )
        } catch (e: Exception) {
            fallbackSuggestion(output, null)
        }
    }

    private fun fallbackSuggestion(output: String, context: TerminalContext?): NextCommandSuggestion {
        val suggestions = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        when {
            output.contains("No such file") || output.contains("Not found") -> {
                suggestions.add("ls -la")
                reasons.add("查看当前目录文件")
                suggestions.add("pwd")
                reasons.add("确认当前路径")
            }
            output.contains("permission denied") -> {
                suggestions.add("ls -la")
                reasons.add("检查文件权限")
                if (context?.isRootAvailable == true) {
                    suggestions.add("su -c \"ls -la\"")
                    reasons.add("使用root权限查看")
                }
            }
            output.contains("list of") || output.contains("packages") -> {
                suggestions.add("pm list packages -3")
                reasons.add("查看第三方应用")
                suggestions.add("pm list packages | wc -l")
                reasons.add("统计应用数量")
            }
            output.contains("device") || output.contains("offline") -> {
                suggestions.add("adb devices")
                reasons.add("检查设备连接")
                suggestions.add("adb kill-server && adb start-server")
                reasons.add("重启ADB服务")
            }
            else -> {
                suggestions.add("echo \$?")
                reasons.add("检查上一条命令的退出状态")
                suggestions.add("ls -la")
                reasons.add("查看当前目录")
            }
        }

        return NextCommandSuggestion(
            commands = suggestions.take(3),
            reasons = reasons.take(3),
            basedOnOutput = output
        )
    }

    fun updateContext(context: TerminalContext) {
        lastContext = context
    }

    fun clearContext() {
        lastContext = null
        lastGeneratedCommands = emptyList()
    }

    fun getTemplatesByCategory(category: TemplateCategory): List<TerminalCommandTemplate> {
        return TerminalCommandTemplates.getTemplatesByCategory(category)
    }

    fun searchTemplates(query: String): List<TerminalCommandTemplate> {
        return TerminalCommandTemplates.searchTemplates(query)
    }

    fun getTemplateById(id: String): TerminalCommandTemplate? {
        return TerminalCommandTemplates.getTemplateById(id)
    }

    fun fillTemplate(template: TerminalCommandTemplate, params: Map<String, String>): String {
        return TerminalCommandTemplates.fillTemplate(template, params)
    }
}