package com.apex.util

enum class TaskComplexity(val level: Int, val windowMultiplier: Float, val description: String) {
    TRIVIAL(0, 0.2f, "简单闲�?),
    SIMPLE(1, 0.3f, "基础问答"),
    MODERATE(2, 0.5f, "一般任�?),
    COMPLEX(3, 0.7f, "复杂任务"),
    CRITICAL(4, 1.0f, "关键任务")
}

data class WindowConfig(
    val complexity: TaskComplexity,
    val messageCount: Int,
    val tokenLimit: Int?,
    val forceExtended: Boolean = false
)

class AdaptiveWindowManager(
    private val defaultMinMessages: Int = 3,
    private val defaultMaxMessages: Int = 50,
    private val defaultTokenLimit: Int? = null
) {

    private val recentQueries = mutableListOf<Pair<String, TaskComplexity>>()
    private val patternLearner = PatternLearner()

    companion object {
        private val TRIVIAL_PATTERNS = listOf(
            Regex("(你好|您好|hi|hello|hey|嗨|早上好|晚安�?, RegexOption.IGNORE_CASE),
            Regex("(谢谢|感谢|thx|correct)", RegexOption.IGNORE_CASE),
            Regex("^(ok|ok[�?]|好的|收到|了解�?", RegexOption.IGNORE_CASE),
            Regex("^(?:随便|无所谓|都可以|无所谓）$", RegexOption.IGNORE_CASE),
            Regex("(哈哈|笑死|哈哈哈）$")
        )

        private val SIMPLE_PATTERNS = listOf(
            Regex("(什么|怎么|如何|为什么|哪|谁|何时�?, RegexOption.IGNORE_CASE),
            Regex("^(?:定义|解释|说明|描述|列出|查询�?, RegexOption.IGNORE_CASE),
            Regex("^(?:给我|帮我|我想|我要�?, RegexOption.IGNORE_CASE)
        )

        private val COMPLEX_PATTERNS = listOf(
            Regex("(调试|debug|修复|fix|错误|exception|崩溃|crash)", RegexOption.IGNORE_CASE),
            Regex("(代码|函数|方法|类|算法|架构|设计|实现�?, RegexOption.IGNORE_CASE),
            Regex("(比较|分析|评估|优化|改进|重构�?, RegexOption.IGNORE_CASE),
            Regex("(多步|步骤|流程|阶段|先后�?, RegexOption.IGNORE_CASE),
            Regex("```[\\s\\S]*?```", RegexOption.IGNORE_CASE)
        )

        private val CRITICAL_PATTERNS = listOf(
            Regex("(必须|紧急|重要|关键|critical|must|urgent)", RegexOption.IGNORE_CASE),
            Regex("(全|所有|整个|全部�?, RegexOption.IGNORE_CASE),
            Regex("(安全|权限|认证|授权|security|permission|auth)", RegexOption.IGNORE_CASE),
            Regex("(数据库|事务|并发|锁|lock|transaction)", RegexOption.IGNORE_CASE),
            Regex("(部署|发布|上线|生产|production|deploy)", RegexOption.IGNORE_CASE)
        )

        private const val MAX_RECENT_QUERIES = 20
    }

    fun detectComplexity(userInput: String, contextMessages: List<com.apex.data.model.Message> = emptyList()): TaskComplexity {
        val input = userInput.lowercase().trim()

        if (CRITICAL_PATTERNS.any { it.containsMatchIn(input) }) {
            return TaskComplexity.CRITICAL
        }

        if (COMPLEX_PATTERNS.any { it.containsMatchIn(input) }) {
            return TaskComplexity.COMPLEX
        }

        if (contextMessages.isNotEmpty()) {
            val lastAssistant = contextMessages.lastOrNull { it.role.lowercase() == "assistant" }
            if (lastAssistant != null && ChatUtils.estimateTokenCount(lastAssistant.content) > 500) {
                return TaskComplexity.COMPLEX
            }
        }

        if (SIMPLE_PATTERNS.any { it.containsMatchIn(input) }) {
            return TaskComplexity.SIMPLE
        }

        if (TRIVIAL_PATTERNS.any { it.containsMatchIn(input) }) {
            val learnedComplexity = patternLearner.getLearnedComplexity(input)
            if (learnedComplexity != null) {
                return learnedComplexity
            }
            return TaskComplexity.TRIVIAL
        }

        if (input.length < 20) {
            return TaskComplexity.TRIVIAL
        }

        val learnedComplexity = patternLearner.getLearnedComplexity(input)
        if (learnedComplexity != null) {
            return learnedComplexity
        }

        return TaskComplexity.MODERATE
    }

    fun calculateWindowConfig(
        currentInput: String,
        contextMessages: List<com.apex.data.model.Message>,
        forceExtended: Boolean = false
    ): WindowConfig {
        val complexity = detectComplexity(currentInput, contextMessages)

        val baseMessageCount = when (complexity) {
            TaskComplexity.TRIVIAL -> 2
            TaskComplexity.SIMPLE -> 4
            TaskComplexity.MODERATE -> 8
            TaskComplexity.COMPLEX -> 15
            TaskComplexity.CRITICAL -> defaultMaxMessages
        }

        val adjustedMessageCount = if (forceExtended) {
            defaultMaxMessages
        } else {
            (baseMessageCount * complexity.windowMultiplier).toInt().coerceIn(
                defaultMinMessages,
                defaultMaxMessages
            )
        }

        recordQuery(currentInput, complexity)

        return WindowConfig(
            complexity = complexity,
            messageCount = adjustedMessageCount,
            tokenLimit = defaultTokenLimit,
            forceExtended = forceExtended
        )
    }

    fun getRecommendedMessages(
        messages: List<com.apex.data.model.Message>,
        windowConfig: WindowConfig
    ): List<com.apex.data.model.Message> {
        if (messages.isEmpty()) return emptyList()

        val systemMessages = messages.filter { it.role.lowercase() == "system" }
        val nonSystemMessages = messages.filter { it.role.lowercase() != "system" }

        if (nonSystemMessages.size <= windowConfig.messageCount) {
            return messages
        }

        val recentNonSystem = nonSystemMessages.takeLast(windowConfig.messageCount)

        patternLearner.learnFromInteraction(messages, windowConfig.complexity)

        return systemMessages + recentNonSystem
    }

    fun estimateTokenSavings(
        originalMessages: List<com.apex.data.model.Message>,
        windowConfig: WindowConfig
    ): Pair<Int, Float> {
        val originalTokens = originalMessages.sumOf {
            ChatUtils.estimateTokenCount(it.content)
        }

        val recommendedMessages = getRecommendedMessages(originalMessages, windowConfig)
        val remainingTokens = recommendedMessages.sumOf {
            ChatUtils.estimateTokenCount(it.content)
        }

        val savedTokens = originalTokens - remainingTokens
        val savingsRatio = if (originalTokens > 0) {
            savedTokens.toFloat() / originalTokens
        } else {
            0f
        }

        return savedTokens to savingsRatio
    }

    private fun recordQuery(query: String, complexity: TaskComplexity) {
        recentQueries.add(query to complexity)
        if (recentQueries.size > MAX_RECENT_QUERIES) {
            recentQueries.removeAt(0)
        }
    }

    fun getComplexityStats(): Map<TaskComplexity, Int> {
        return recentQueries.groupBy { it.second }
            .mapValues { it.value.size }
    }

    fun resetLearning() {
        patternLearner.reset()
        recentQueries.clear()
    }

    private class PatternLearner {
        private val learnedPatterns = mutableMapOf<String, TaskComplexity>()
        private val simpleIndicators = setOf(
            "�?, "�?, "�?, "�?, "呀", "�?, "�?, "�?, "�?
        )

        fun learnFromInteraction(messages: List<com.apex.data.model.Message>, complexity: TaskComplexity) {
            if (complexity == TaskComplexity.TRIVIAL || complexity == TaskComplexity.SIMPLE) {
                val lastUserMsg = messages.lastOrNull { it.role.lowercase() == "user" }?.content ?: return
                val shortPattern = lastUserMsg.take(30).lowercase().trim()
                if (shortPattern.isNotBlank() && shortPattern !in learnedPatterns) {
                    learnedPatterns[shortPattern] = complexity
                    if (learnedPatterns.size > 100) {
                        val oldest = learnedPatterns.entries.first()
                        learnedPatterns.remove(oldest.key)
                    }
                }
            }
        }

        fun getLearnedComplexity(input: String): TaskComplexity? {
            val shortPattern = input.take(30).lowercase().trim()
            return learnedPatterns[shortPattern]
        }

        fun reset() {
            learnedPatterns.clear()
        }
    }
}
