package com.apex.agent.core.normal.shortcut

import java.util.concurrent.ConcurrentHashMap

/**
 * F26: 对话模板与快捷指令
 *
 * 用户可定义快捷指令和对话模板：
 * - 快捷指令：/ 系命令（/translate /summarize /explain）
 * - 对话模板：预设的 prompt 模板，带参数
 * - 代码片段快捷键：常用代码片段的快速插入
 * - 自定义命令：用户定义的命令别名
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的命令是 Agent 调度
 * - 狂暴是策略触发
 * - 本功能是**用户效率工具**，提升单 Agent 输入效率
 */

/**
 * 快捷指令
 */
data class ShortcutCommand(
    val id: String,
    val name: String,           // 命令名（不含 /）
    val displayName: String,
    val description: String,
    val icon: String,
    val category: CommandCategory,
    val template: CommandTemplate,
    val aliases: List<String> = emptyList(),
    val parameters: List<CommandParameter> = emptyList(),
    val usage: String = "",     // 用法说明
    val example: String = ""    // 示例
)

enum class CommandCategory {
    AI_OPERATION,    // AI 操作（翻译/总结/解释）
    CONVERSATION,    // 对话管理（清空/分支/导出）
    TOOL,            // 工具调用
    NAVIGATION,      // 导航
    UTILITY,         // 实用工具
    CUSTOM           // 用户自定义
}

/**
 * 命令模板
 */
sealed class CommandTemplate {
    /** 文本模板：直接替换参数 */
    data class Text(val template: String) : CommandTemplate() {
        fun resolve(params: Map<String, String>): String {
            var result = template
            params.forEach { (k, v) -> result = result.replace("{$k}", v) }
            return result
        }
    }

    /** Prompt 模板：作为用户消息发送 */
    data class Prompt(val systemPrompt: String?, val userPrompt: String) : CommandTemplate()

    /** 动作模板：执行特定动作 */
    data class Action(val actionType: String, val defaultArgs: Map<String, Any> = emptyMap()) : CommandTemplate()

    /** 组合模板：多个步骤 */
    data class Composite(val steps: List<CommandTemplate>) : CommandTemplate()
}

/**
 * 命令参数
 */
data class CommandParameter(
    val name: String,
    val displayName: String,
    val type: ParameterType,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val description: String = "",
    val options: List<String>? = null
)

enum class ParameterType {
    TEXT, NUMBER, BOOLEAN, FILE_PATH, URL, LANGUAGE, DATE, SELECT
}

/**
 * 解析后的命令
 */
data class ParsedCommand(
    val command: ShortcutCommand,
    val arguments: Map<String, String>,
    val rawInput: String
)

/**
 * 命令执行结果
 */
sealed class CommandExecutionResult {
    data class SendMessage(val text: String, val systemPrompt: String? = null) : CommandExecutionResult()
    data class ExecuteAction(val actionType: String, val args: Map<String, Any>) : CommandExecutionResult()
    data class CompositeResult(val results: List<CommandExecutionResult>) : CommandExecutionResult()
    data class Error(val message: String) : CommandExecutionResult()
    data object NoOp : CommandExecutionResult()
}

/**
 * 对话模板
 */
data class ConversationTemplate(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val starterPrompt: String? = null,
    val suggestedFollowups: List<String> = emptyList(),
    val category: String = "general",
    val tags: List<String> = emptyList()
)

/**
 * 快捷指令注册表
 */
class ShortcutCommandRegistry {

    private val commands = ConcurrentHashMap<String, ShortcutCommand>()
    private val templates = ConcurrentHashMap<String, ConversationTemplate>()

    init {
        registerBuiltinCommands()
        registerBuiltinTemplates()
    }

    /**
     * 注册命令
     */
    fun register(command: ShortcutCommand) {
        commands[command.name] = command
        for (alias in command.aliases) {
            commands[alias] = command
        }
    }

    /**
     * 解析用户输入
     */
    fun parse(input: String): ParsedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.removePrefix("/").split(Regex("\\s+"), limit = 2)
        val cmdName = parts[0].lowercase()
        val argsStr = if (parts.size > 1) parts[1] else ""

        val command = commands[cmdName] ?: return null
        val arguments = parseArguments(argsStr, command.parameters)

        return ParsedCommand(command, arguments, input)
    }

    /**
     * 执行命令
     */
    fun execute(parsed: ParsedCommand): CommandExecutionResult {
        val cmd = parsed.command
        return try {
            when (val template = cmd.template) {
                is CommandTemplate.Text -> {
                    val resolved = template.resolve(parsed.arguments)
                    CommandExecutionResult.SendMessage(resolved)
                }
                is CommandTemplate.Prompt -> {
                    CommandExecutionResult.SendMessage(
                        text = template.userPrompt,
                        systemPrompt = template.systemPrompt
                    )
                }
                is CommandTemplate.Action -> {
                    val args = template.defaultArgs + parsed.arguments.mapValues { it.value as Any }
                    CommandExecutionResult.ExecuteAction(template.actionType, args)
                }
                is CommandTemplate.Composite -> {
                    val results = template.steps.map { step ->
                        executeStep(step, parsed.arguments)
                    }
                    CommandExecutionResult.CompositeResult(results)
                }
            }
        } catch (e: Exception) {
            CommandExecutionResult.Error(e.message ?: "执行失败")
        }
    }

    private fun executeStep(template: CommandTemplate, args: Map<String, String>): CommandExecutionResult {
        return when (template) {
            is CommandTemplate.Text -> CommandExecutionResult.SendMessage(template.resolve(args))
            is CommandTemplate.Prompt -> CommandExecutionResult.SendMessage(template.userPrompt, template.systemPrompt)
            is CommandTemplate.Action -> CommandExecutionResult.ExecuteAction(template.actionType, template.defaultArgs)
            is CommandTemplate.Composite -> CommandExecutionResult.CompositeResult(template.steps.map { executeStep(it, args) })
        }
    }

    /**
     * 获取命令列表
     */
    fun listCommands(category: CommandCategory? = null): List<ShortcutCommand> {
        return commands.values
            .distinctBy { it.name }
            .filter { category == null || it.category == category }
            .sortedBy { it.name }
    }

    /**
     * 搜索命令
     */
    fun search(query: String): List<ShortcutCommand> {
        val q = query.lowercase()
        return commands.values
            .distinctBy { it.name }
            .filter { cmd ->
                cmd.name.contains(q) ||
                cmd.displayName.contains(q, ignoreCase = true) ||
                cmd.description.contains(q, ignoreCase = true) ||
                cmd.aliases.any { it.contains(q) }
            }
    }

    /**
     * 注册对话模板
     */
    fun registerTemplate(template: ConversationTemplate) {
        templates[template.id] = template
    }

    fun getTemplate(id: String): ConversationTemplate? = templates[id]
    fun listTemplates(category: String? = null): List<ConversationTemplate> {
        return templates.values
            .filter { category == null || it.category == category }
            .sortedBy { it.name }
    }

    /**
     * 生成命令帮助
     */
    fun generateHelp(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 可用命令 ═══")
        val byCategory = commands.values.distinctBy { it.name }.groupBy { it.category }
        byCategory.forEach { (category, cmds) ->
            sb.appendLine()
            sb.appendLine("【${category.name}】")
            cmds.sortedBy { it.name }.forEach { cmd ->
                sb.appendLine("  /${cmd.name} - ${cmd.description}")
                if (cmd.aliases.isNotEmpty()) {
                    sb.appendLine("    别名: ${cmd.aliases.joinToString { "/$it" }}")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("═════════════")
        return sb.toString()
    }

    // ============ 参数解析 ============

    private fun parseArguments(argsStr: String, params: List<CommandParameter>): Map<String, String> {
        if (argsStr.isBlank()) return emptyMap()
        if (params.isEmpty()) return mapOf("input" to argsStr)

        val result = mutableMapOf<String, String>()
        val tokens = tokenizeArgs(argsStr)

        // 简化：按位置赋值
        var paramIdx = 0
        var i = 0
        while (i < tokens.size && paramIdx < params.size) {
            val param = params[paramIdx]
            if (tokens[i].startsWith("--")) {
                // 命名参数 --name value
                val name = tokens[i].removePrefix("--")
                val paramDef = params.find { it.name == name }
                if (paramDef != null && i + 1 < tokens.size) {
                    result[name] = tokens[i + 1]
                    i += 2
                } else {
                    i++
                }
            } else {
                // 位置参数
                result[param.name] = tokens[i]
                paramIdx++
                i++
            }
        }

        // 填充默认值
        for (param in params) {
            if (param.name !in result && param.defaultValue != null) {
                result[param.name] = param.defaultValue
            }
        }

        return result
    }

    private fun tokenizeArgs(argsStr: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false

        for (c in argsStr) {
            when {
                c == '"' -> inQuote = !inQuote
                c.isWhitespace() && !inQuote -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    // ============ 预置命令 ============

    private fun registerBuiltinCommands() {
        // AI 操作
        register(ShortcutCommand(
            id = "cmd_translate",
            name = "translate",
            displayName = "翻译",
            description = "翻译文本到指定语言",
            icon = "🌐",
            category = CommandCategory.AI_OPERATION,
            template = CommandTemplate.Prompt(
                systemPrompt = "你是专业翻译，请准确翻译，保持原文语气。",
                userPrompt = "请将以下内容翻译为{language}：\n\n{text}"
            ),
            aliases = listOf("tr", "翻译"),
            parameters = listOf(
                CommandParameter("text", "待翻译文本", ParameterType.TEXT, required = true),
                CommandParameter("language", "目标语言", ParameterType.LANGUAGE, defaultValue = "中文")
            ),
            usage = "/translate <text> [--language <lang>]",
            example = "/translate Hello World --language 中文"
        ))

        register(ShortcutCommand(
            id = "cmd_summarize",
            name = "summarize",
            displayName = "总结",
            description = "总结文本要点",
            icon = "📝",
            category = CommandCategory.AI_OPERATION,
            template = CommandTemplate.Prompt(
                systemPrompt = "请用简洁的语言总结要点，使用分点形式。",
                userPrompt = "请总结以下内容：\n\n{input}"
            ),
            aliases = listOf("sum", "总结"),
            parameters = listOf(
                CommandParameter("input", "待总结文本", ParameterType.TEXT, required = true)
            ),
            usage = "/summarize <text>",
            example = "/summarize 长文本..."
        ))

        register(ShortcutCommand(
            id = "cmd_explain",
            name = "explain",
            displayName = "解释",
            description = "详细解释概念或代码",
            icon = "💡",
            category = CommandCategory.AI_OPERATION,
            template = CommandTemplate.Prompt(
                systemPrompt = "请用通俗易懂的方式解释，多用类比和示例。",
                userPrompt = "请解释：{input}"
            ),
            aliases = listOf("exp", "解释"),
            parameters = listOf(
                CommandParameter("input", "待解释内容", ParameterType.TEXT, required = true)
            )
        ))

        register(ShortcutCommand(
            id = "cmd_rewrite",
            name = "rewrite",
            displayName = "改写",
            description = "改写文本风格",
            icon = "✏️",
            category = CommandCategory.AI_OPERATION,
            template = CommandTemplate.Prompt(
                systemPrompt = "请按照指定风格改写文本，保持原意。",
                userPrompt = "请用{style}风格改写：\n\n{input}"
            ),
            aliases = listOf("rw"),
            parameters = listOf(
                CommandParameter("input", "原文", ParameterType.TEXT, required = true),
                CommandParameter("style", "风格", ParameterType.SELECT, defaultValue = "正式", options = listOf("正式", "口语", "学术", "营销"))
            )
        ))

        // 对话管理
        register(ShortcutCommand(
            id = "cmd_clear",
            name = "clear",
            displayName = "清空对话",
            description = "清空当前对话历史",
            icon = "🧹",
            category = CommandCategory.CONVERSATION,
            template = CommandTemplate.Action("clear_conversation"),
            aliases = listOf("cls")
        ))

        register(ShortcutCommand(
            id = "cmd_branch",
            name = "branch",
            displayName = "对话分支",
            description = "从当前位置创建对话分支",
            icon = "🌿",
            category = CommandCategory.CONVERSATION,
            template = CommandTemplate.Action("create_branch"),
            parameters = listOf(
                CommandParameter("label", "分支标签", ParameterType.TEXT, defaultValue = "新分支")
            )
        ))

        register(ShortcutCommand(
            id = "cmd_export",
            name = "export",
            displayName = "导出对话",
            description = "导出对话为指定格式",
            icon = "📤",
            category = CommandCategory.CONVERSATION,
            template = CommandTemplate.Action("export_conversation"),
            aliases = listOf("exp"),
            parameters = listOf(
                CommandParameter("format", "格式", ParameterType.SELECT, defaultValue = "markdown", options = listOf("markdown", "json", "html", "txt"))
            )
        ))

        // 工具
        register(ShortcutCommand(
            id = "cmd_search",
            name = "search",
            displayName = "搜索历史",
            description = "搜索对话历史",
            icon = "🔍",
            category = CommandCategory.TOOL,
            template = CommandTemplate.Action("search_history"),
            aliases = listOf("find"),
            parameters = listOf(
                CommandParameter("query", "搜索词", ParameterType.TEXT, required = true)
            )
        ))

        // 实用工具
        register(ShortcutCommand(
            id = "cmd_help",
            name = "help",
            displayName = "帮助",
            description = "显示所有可用命令",
            icon = "❓",
            category = CommandCategory.UTILITY,
            template = CommandTemplate.Action("show_help"),
            aliases = listOf("?", "h")
        ))

        register(ShortcutCommand(
            id = "cmd_health",
            name = "health",
            displayName = "健康度",
            description = "显示对话健康度报告",
            icon = "❤️",
            category = CommandCategory.UTILITY,
            template = CommandTemplate.Action("show_health")
        ))

        register(ShortcutCommand(
            id = "cmd_scene",
            name = "scene",
            displayName = "切换场景",
            description = "切换对话场景模板",
            icon = "🎭",
            category = CommandCategory.UTILITY,
            template = CommandTemplate.Action("switch_scene"),
            parameters = listOf(
                CommandParameter("name", "场景名", ParameterType.SELECT, options = listOf("programming", "writing", "translation", "learning"))
            )
        ))
    }

    private fun registerBuiltinTemplates() {
        registerTemplate(ConversationTemplate(
            id = "tpl_code_review",
            name = "代码审查",
            description = "审查代码质量、潜在问题、改进建议",
            systemPrompt = "你是一位资深代码审查专家。请从代码质量、潜在 bug、性能、可读性、安全性等角度审查代码，给出具体改进建议。",
            starterPrompt = "请粘贴要审查的代码：",
            suggestedFollowups = listOf("如何修复这些问题？", "有更好的实现方式吗？"),
            category = "programming"
        ))

        registerTemplate(ConversationTemplate(
            id = "tpl_brainstorm",
            name = "头脑风暴",
            description = "创意发想与方案探索",
            systemPrompt = "你是一位创意伙伴。请大胆提出想法，不急于否定，鼓励多样性，帮助组合和演进想法。",
            starterPrompt = "你想要头脑风暴什么主题？",
            suggestedFollowups = listOf("还有其他方向吗？", "结合这几个想法如何？"),
            category = "creative"
        ))

        registerTemplate(ConversationTemplate(
            id = "tpl_learning",
            name = "学习导师",
            description = "循序渐进的概念讲解",
            systemPrompt = "你是一位耐心的学习导师。由浅入深，多用类比和示例，主动检测理解程度。",
            starterPrompt = "你想学习什么概念？",
            suggestedFollowups = listOf("能举个例子吗？", "这个概念有什么应用？"),
            category = "learning"
        ))
    }
}
