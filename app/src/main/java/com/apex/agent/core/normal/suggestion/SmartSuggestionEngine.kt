package com.apex.agent.core.normal.suggestion

import java.util.concurrent.ConcurrentHashMap

/**
 * F19: 智能补全与建议系统
 *
 * 为用户提供输入补全和操作建议：
 * - 输入补全：根据上下文预测用户输入
 * - 问题建议：基于当前话题推荐后续问题
 * - 操作建议：识别可执行的动作并推荐
 * - 命令补全：/ 命令快捷输入
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不面向终端用户输入
 * - 狂暴不关心输入体验
 * - 本功能是**单 Agent 输入体验**的核心，提升用户效率
 */

/**
 * 建议类型
 */
enum class SuggestionType {
    INPUT_COMPLETION,    // 输入补全
    FOLLOWUP_QUESTION,   // 后续问题
    ACTION_SUGGESTION,   // 操作建议
    COMMAND_COMPLETION,  // 命令补全
    TOPIC_SUGGESTION,    // 话题建议
    CLARIFICATION_OPTION // 澄清选项
}

/**
 * 建议
 */
data class Suggestion(
    val id: String,
    val type: SuggestionType,
    val text: String,
    val displayText: String = text,
    val description: String = "",
    val icon: String? = null,
    val confidence: Float,
    val category: String = "",
    val action: SuggestionAction? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 建议动作
 */
sealed class SuggestionAction {
    data class InsertText(val text: String, val replaceRange: IntRange? = null) : SuggestionAction()
    data class SendMessage(val text: String) : SuggestionAction()
    data class ExecuteCommand(val command: String, val args: Map<String, Any> = emptyMap()) : SuggestionAction()
    data class SwitchScene(val sceneId: String) : SuggestionAction()
    data class TriggerTool(val toolName: String, val args: Map<String, Any>) : SuggestionAction()
}

/**
 * 建议上下文
 */
data class SuggestionContext(
    val currentInput: String = "",
    val cursorPosition: Int = 0,
    val recentMessages: List<RecentMessage> = emptyList(),
    val currentTopic: String = "",
    val userId: String = "default",
    val activeScene: String? = null,
    val availableTools: List<String> = emptyList()
)

data class RecentMessage(val role: String, val content: String, val timestamp: Long)

/**
 * 建议响应
 */
data class SuggestionResponse(
    val suggestions: List<Suggestion>,
    val context: SuggestionContext,
    val generatedAt: Long = System.currentTimeMillis()
)

/**
 * 智能建议引擎
 */
class SmartSuggestionEngine {

    private val suggestionProviders = ConcurrentHashMap<SuggestionType, SuggestionProvider>()
    private val userHistory = ConcurrentHashMap<String, MutableList<UserInputRecord>>()
    private val commandRegistry = ConcurrentHashMap<String, CommandDef>()

    init {
        registerBuiltinProviders()
        registerBuiltinCommands()
    }

    /**
     * 注册建议提供者
     */
    fun registerProvider(type: SuggestionType, provider: SuggestionProvider) {
        suggestionProviders[type] = provider
    }

    /**
     * 生成建议
     */
    fun suggest(context: SuggestionContext, types: Set<SuggestionType> = allTypes): SuggestionResponse {
        val allSuggestions = mutableListOf<Suggestion>()

        for (type in types) {
            val provider = suggestionProviders[type] ?: continue
            val suggestions = provider.suggest(context)
            allSuggestions.addAll(suggestions)
        }

        // 按置信度排序，去重
        val deduped = allSuggestions
            .sortedByDescending { it.confidence }
            .distinctBy { it.text.lowercase() }
            .take(10)

        return SuggestionResponse(deduped, context)
    }

    /**
     * 记录用户输入（用于学习）
     */
    fun recordInput(userId: String, input: String, accepted: Boolean, suggestionText: String? = null) {
        val history = userHistory.computeIfAbsent(userId) { mutableListOf() }
        history.add(UserInputRecord(input, accepted, suggestionText, System.currentTimeMillis()))
        if (history.size > 1000) history.removeAt(0)
    }

    /**
     * 注册命令
     */
    fun registerCommand(command: CommandDef) {
        commandRegistry[command.name] = command
    }

    // ============ 内置提供者 ============

    private fun registerBuiltinProviders() {
        registerProvider(SuggestionType.INPUT_COMPLETION, InputCompletionProvider(userHistory))
        registerProvider(SuggestionType.FOLLOWUP_QUESTION, FollowupQuestionProvider())
        registerProvider(SuggestionType.ACTION_SUGGESTION, ActionSuggestionProvider())
        registerProvider(SuggestionType.COMMAND_COMPLETION, CommandCompletionProvider(commandRegistry))
        registerProvider(SuggestionType.TOPIC_SUGGESTION, TopicSuggestionProvider())
        registerProvider(SuggestionType.CLARIFICATION_OPTION, ClarificationOptionProvider())
    }

    private fun registerBuiltinCommands() {
        registerCommand(CommandDef("/clear", "清空对话", "🧹"))
        registerCommand(CommandDef("/summary", "生成摘要", "📝"))
        registerCommand(CommandDef("/search", "搜索历史", "🔍"))
        registerCommand(CommandDef("/scene", "切换场景", "🎭"))
        registerCommand(CommandDef("/macro", "执行宏", "⚡"))
        registerCommand(CommandDef("/export", "导出对话", "📤"))
        registerCommand(CommandDef("/health", "健康度", "❤️"))
        registerCommand(CommandDef("/branch", "对话分支", "🌿"))
        registerCommand(CommandDef("/profile", "用户画像", "👤"))
        registerCommand(CommandDef("/memory", "记忆管理", "🧠"))
        registerCommand(CommandDef("/help", "帮助", "❓"))
    }

    // ============ Input Completion ============

    class InputCompletionProvider(
        private val userHistory: ConcurrentHashMap<String, MutableList<UserInputRecord>>
    ) : SuggestionProvider {
        override fun suggest(context: SuggestionContext): List<Suggestion> {
            val input = context.currentInput.trim()
            if (input.length < 2) return emptyList()

            val suggestions = mutableListOf<Suggestion>()

            // 1. 基于用户历史
            val history = userHistory[context.userId] ?: emptyList()
            val historyMatches = history
                .filter { it.input.startsWith(input, ignoreCase = true) && it.input != input }
                .groupingBy { it.input }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { entry ->
                    Suggestion(
                        id = "completion_hist_${entry.key.hashCode()}",
                        type = SuggestionType.INPUT_COMPLETION,
                        text = entry.key,
                        displayText = entry.key,
                        description = "最近输入",
                        confidence = 0.8f,
                        action = SuggestionAction.InsertText(entry.key)
                    )
                }
            suggestions.addAll(historyMatches)

            // 2. 基于常用短语
            val commonPhrases = listOf(
                "帮我", "如何", "为什么", "什么是", "举个例子",
                "翻译", "总结", "分析", "对比", "解释一下"
            )
            val phraseMatches = commonPhrases
                .filter { it.startsWith(input, ignoreCase = true) && it != input }
                .take(3)
                .map { phrase ->
                    Suggestion(
                        id = "completion_phrase_${phrase.hashCode()}",
                        type = SuggestionType.INPUT_COMPLETION,
                        text = phrase,
                        displayText = phrase,
                        confidence = 0.5f,
                        action = SuggestionAction.InsertText(phrase)
                    )
                }
            suggestions.addAll(phraseMatches)

            return suggestions
        }
    }

    // ============ Followup Question ============

    class FollowupQuestionProvider : SuggestionProvider {
        override fun suggest(context: SuggestionContext): List<Suggestion> {
            if (context.recentMessages.isEmpty()) return emptyList()

            val lastAssistant = context.recentMessages.lastOrNull { it.role == "assistant" } ?: return emptyList()
            val lastUser = context.recentMessages.lastOrNull { it.role == "user" } ?: return emptyList()

            val suggestions = mutableListOf<Suggestion>()

            // 基于回答类型推荐后续问题
            val questionTemplates = generateFollowupQuestions(lastUser.content, lastAssistant.content)

            return questionTemplates.mapIndexed { i, q ->
                Suggestion(
                    id = "followup_$i",
                    type = SuggestionType.FOLLOWUP_QUESTION,
                    text = q,
                    displayText = q,
                    icon = "💡",
                    confidence = 0.7f - i * 0.1f,
                    action = SuggestionAction.SendMessage(q)
                )
            }
        }

        private fun generateFollowupQuestions(userQuestion: String, assistantAnswer: String): List<String> {
            val questions = mutableListOf<String>()

            // 通用后续问题
            questions.add("能详细解释一下吗？")
            questions.add("有什么具体的例子？")
            questions.add("优势和劣势分别是什么？")

            // 基于内容生成
            if (assistantAnswer.contains("步骤") || assistantAnswer.contains("如何")) {
                questions.add("第一步具体怎么做？")
            }
            if (assistantAnswer.contains(Regex("\\d+"))) {
                questions.add("这些数据来源是什么？")
            }
            if (assistantAnswer.contains("因为") || assistantAnswer.contains("原因")) {
                questions.add("还有其他原因吗？")
            }
            if (assistantAnswer.contains("可以") || assistantAnswer.contains("建议")) {
                questions.add("有没有更好的方案？")
            }

            return questions.distinct().take(5)
        }
    }

    // ============ Action Suggestion ============

    class ActionSuggestionProvider : SuggestionProvider {
        override fun suggest(context: SuggestionContext): List<Suggestion> {
            val suggestions = mutableListOf<Suggestion>()
            val lastMsg = context.recentMessages.lastOrNull()

            // 根据最近消息推荐操作
            if (lastMsg != null) {
                val content = lastMsg.content.lowercase()

                if (content.contains("代码") || content.contains("code")) {
                    suggestions.add(Suggestion(
                        id = "action_run_code",
                        type = SuggestionType.ACTION_SUGGESTION,
                        text = "运行代码",
                        displayText = "▶ 运行代码",
                        icon = "▶",
                        confidence = 0.8f,
                        action = SuggestionAction.ExecuteCommand("run_code")
                    ))
                    suggestions.add(Suggestion(
                        id = "action_save_snippet",
                        type = SuggestionType.ACTION_SUGGESTION,
                        text = "保存为代码片段",
                        displayText = "💾 保存为代码片段",
                        icon = "💾",
                        confidence = 0.6f,
                        action = SuggestionAction.ExecuteCommand("save_snippet")
                    ))
                }

                if (content.contains("文件") || content.contains("file")) {
                    suggestions.add(Suggestion(
                        id = "action_open_file",
                        type = SuggestionType.ACTION_SUGGESTION,
                        text = "打开文件",
                        displayText = "📂 打开文件",
                        icon = "📂",
                        confidence = 0.7f,
                        action = SuggestionAction.ExecuteCommand("open_file")
                    ))
                }

                if (content.contains("翻译") || content.contains("translate")) {
                    suggestions.add(Suggestion(
                        id = "action_translate",
                        type = SuggestionType.ACTION_SUGGESTION,
                        text = "翻译",
                        displayText = "🌐 翻译",
                        icon = "🌐",
                        confidence = 0.85f,
                        action = SuggestionAction.TriggerTool("translate", emptyMap())
                    ))
                }
            }

            // 通用操作
            suggestions.add(Suggestion(
                id = "action_summary",
                type = SuggestionType.ACTION_SUGGESTION,
                text = "生成摘要",
                displayText = "📝 生成摘要",
                icon = "📝",
                confidence = 0.4f,
                action = SuggestionAction.ExecuteCommand("summary")
            ))
            suggestions.add(Suggestion(
                id = "action_export",
                type = SuggestionType.ACTION_SUGGESTION,
                text = "导出对话",
                displayText = "📤 导出对话",
                icon = "📤",
                confidence = 0.3f,
                action = SuggestionAction.ExecuteCommand("export")
            ))

            return suggestions
        }
    }

    // ============ Command Completion ============

    class CommandCompletionProvider(
        private val commands: ConcurrentHashMap<String, CommandDef>
    ) : SuggestionProvider {
        override fun suggest(context: SuggestionContext): List<Suggestion> {
            val input = context.currentInput.trim()
            if (!input.startsWith("/")) return emptyList()

            val partial = input.removePrefix("/")
            return commands.values
                .filter { partial.isBlank() || it.name.startsWith(input) }
                .take(8)
                .map { cmd ->
                    Suggestion(
                        id = "cmd_${cmd.name}",
                        type = SuggestionType.COMMAND_COMPLETION,
                        text = cmd.name,
                        displayText = "${cmd.icon} ${cmd.name} - ${cmd.description}",
                        icon = cmd.icon,
                        confidence = 1f,
                        action = SuggestionAction.InsertText(cmd.name + " ")
                    )
                }
        }
    }

    // ============ Topic Suggestion ============

    class TopicSuggestionProvider : SuggestionProvider {
        override fun suggest(context: SuggestionContext): List<Suggestion> {
            if (context.currentInput.isNotBlank()) return emptyList()
            if (context.recentMessages.isNotEmpty()) return emptyList()

            // 新对话时推荐话题
            val topics = listOf(
                "今天有什么新闻？" to "新闻",
                "帮我写一段代码" to "编程",
                "推荐一本书" to "阅读",
                "解释一个概念" to "学习",
                "聊聊最近的 AI 发展" to "AI",
                "帮我规划下周" to "效率"
            )

            return topics.mapIndexed { i, (text, category) ->
                Suggestion(
                    id = "topic_$i",
                    type = SuggestionType.TOPIC_SUGGESTION,
                    text = text,
                    displayText = text,
                    icon = "💭",
                    confidence = 0.5f - i * 0.05f,
                    category = category,
                    action = SuggestionAction.SendMessage(text)
                )
            }
        }
    }

    // ============ Clarification Option ============

    class ClarificationOptionProvider : SuggestionProvider {
        override fun suggest(context: SuggestionContext): List<Suggestion> {
            // 当 AI 提出澄清问题时，提供选项
            val lastAssistant = context.recentMessages.lastOrNull { it.role == "assistant" } ?: return emptyList()
            val content = lastAssistant.content

            // 检测是否是澄清问题
            val isClarification = content.contains("?") || content.contains("？") ||
                content.containsAny("还是", "哪个", "具体", "明确")

            if (!isClarification) return emptyList()

            // 从问题中提取选项
            val options = mutableListOf<String>()

            // "A 还是 B" 模式
            val orPattern = Regex("([\\u4e00-\\u9fa5A-Za-z0-9]+)\\s*还是\\s*([\\u4e00-\\u9fa5A-Za-z0-9]+)")
            orPattern.find(content)?.let { match ->
                options.add(match.groupValues[1])
                options.add(match.groupValues[2])
            }

            // 默认选项
            if (options.isEmpty()) {
                options.addAll(listOf("是", "不是", "详细说明", "换个话题"))
            }

            return options.mapIndexed { i, opt ->
                Suggestion(
                    id = "clarify_$i",
                    type = SuggestionType.CLARIFICATION_OPTION,
                    text = opt,
                    displayText = opt,
                    icon = "👆",
                    confidence = 0.9f - i * 0.1f,
                    action = SuggestionAction.SendMessage(opt)
                )
            }
        }

        private fun String.containsAny(vararg keywords: String): Boolean =
            keywords.any { this.contains(it) }
    }

    // ============ 数据结构 ============

    data class UserInputRecord(
        val input: String,
        val accepted: Boolean,
        val suggestionText: String?,
        val timestamp: Long
    )

    data class CommandDef(
        val name: String,
        val description: String,
        val icon: String
    )

    companion object {
        val allTypes = setOf(
            SuggestionType.INPUT_COMPLETION,
            SuggestionType.FOLLOWUP_QUESTION,
            SuggestionType.ACTION_SUGGESTION,
            SuggestionType.COMMAND_COMPLETION,
            SuggestionType.TOPIC_SUGGESTION,
            SuggestionType.CLARIFICATION_OPTION
        )
    }
}

/**
 * 建议提供者接口
 */
fun interface SuggestionProvider {
    fun suggest(context: SuggestionContext): List<Suggestion>
}
