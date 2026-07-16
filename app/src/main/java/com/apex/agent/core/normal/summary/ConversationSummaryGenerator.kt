package com.apex.agent.core.normal.summary

import java.util.concurrent.ConcurrentHashMap

/**
 * F16: 对话摘要智能生成器
 *
 * 多策略摘要生成，保留关键信息：
 * - 抽取式摘要：提取关键句
 * - 生成式摘要：LLM 重新组织
 * - 结构化摘要：按主题/决策/待办分类
 * - 增量摘要：只处理新增内容
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 独立摘要
 * - 狂暴用任务级 summary
 * - 本功能专注**单 Agent 长对话压缩**，保留用户关心的细节
 */

/**
 * 摘要策略
 */
enum class SummaryStrategy {
    /** 抽取式：从原文提取关键句 */
    EXTRACTIVE,
    /** 生成式：LLM 重新组织（占位，需注入 LLM） */
    ABSTRACTIVE,
    /** 结构化：按主题/决策/待办/事实分类 */
    STRUCTURED,
    /** 增量：只处理新增内容，合并到已有摘要 */
    INCREMENTAL,
    /** 混合：抽取 + 结构化 */
    HYBRID
}

/**
 * 摘要内容
 */
data class ConversationSummary(
    val id: String,
    val chatId: String,
    val strategy: SummaryStrategy,
    val originalMessageCount: Int,
    val originalTokenCount: Int,
    val summary: String,
    val structuredContent: StructuredSummary? = null,
    val keyPoints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val summaryTokenCount: Int,
    val compressionRatio: Float,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 结构化摘要
 */
data class StructuredSummary(
    val overview: String,           // 总览
    val topics: List<TopicSummary>, // 按主题
    val timeline: List<TimelineEvent>, // 时间线
    val decisions: List<DecisionRecord>, // 决策
    val actionItems: List<ActionItem>,   // 待办
    val keyEntities: List<EntityRecord>, // 关键实体
    val openQuestions: List<String>      // 未解决问题
)

data class TopicSummary(val topic: String, val summary: String, val messageRange: IntRange)
data class TimelineEvent(val timestamp: Long, val description: String, val type: String)
data class DecisionRecord(val decision: String, val rationale: String, val timestamp: Long, val messageId: String?)
data class ActionItem(val description: String, val assignee: String?, val dueDate: String?, val priority: Int)
data class EntityRecord(val name: String, val type: String, val mentions: Int, val firstMentioned: Long)

/**
 * 摘要生成器
 */
class ConversationSummaryGenerator {

    private val summaries = ConcurrentHashMap<String, MutableList<ConversationSummary>>()

    /**
     * 生成摘要
     */
    fun generate(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        strategy: SummaryStrategy = SummaryStrategy.HYBRID,
        maxTokens: Int = 1000
    ): ConversationSummary {
        val originalTokens = messages.sumOf { it.tokenCount }

        return when (strategy) {
            SummaryStrategy.EXTRACTIVE -> generateExtractive(chatId, messages, maxTokens, originalTokens)
            SummaryStrategy.ABSTRACTIVE -> generateAbstractive(chatId, messages, maxTokens, originalTokens)
            SummaryStrategy.STRUCTURED -> generateStructured(chatId, messages, maxTokens, originalTokens)
            SummaryStrategy.INCREMENTAL -> generateIncremental(chatId, messages, maxTokens, originalTokens)
            SummaryStrategy.HYBRID -> generateHybrid(chatId, messages, maxTokens, originalTokens)
        }.also { summary ->
            summaries.computeIfAbsent(chatId) { mutableListOf() }.add(summary)
        }
    }

    /**
     * 抽取式摘要：基于 TextRank 算法提取关键句
     */
    private fun generateExtractive(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        maxTokens: Int,
        originalTokens: Int
    ): ConversationSummary {
        val sentences = messages.flatMap { msg ->
            msg.content.split(Regex("[。.！!？?\\n]+"))
                .filter { it.isNotBlank() }
                .map { Sentence(it.trim(), msg.id, msg.role, msg.timestamp) }
        }

        if (sentences.isEmpty()) {
            return emptySummary(chatId, messages, originalTokens)
        }

        // TextRank: 计算句子相似度并排序
        val ranked = textrank(sentences)

        // 选取 top-N 句子（按 token 预算）
        val selected = mutableListOf<Sentence>()
        var tokenCount = 0
        for (sentence in ranked) {
            val tokens = estimateTokens(sentence.text)
            if (tokenCount + tokens > maxTokens) break
            selected.add(sentence)
            tokenCount += tokens
        }

        // 按原始顺序排列
        val ordered = selected.sortedBy { it.timestamp }
        val summaryText = ordered.joinToString(" ") { it.text }

        return ConversationSummary(
            id = "summary_${System.currentTimeMillis()}",
            chatId = chatId,
            strategy = SummaryStrategy.EXTRACTIVE,
            originalMessageCount = messages.size,
            originalTokenCount = originalTokens,
            summary = summaryText,
            keyPoints = ordered.map { it.text },
            summaryTokenCount = tokenCount,
            compressionRatio = if (originalTokens > 0) tokenCount.toFloat() / originalTokens else 1f
        )
    }

    /**
     * 生成式摘要（占位：实际需调用 LLM）
     */
    private fun generateAbstractive(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        maxTokens: Int,
        originalTokens: Int
    ): ConversationSummary {
        // 降级为抽取式
        val extractive = generateExtractive(chatId, messages, maxTokens, originalTokens)
        return extractive.copy(strategy = SummaryStrategy.ABSTRACTIVE)
    }

    /**
     * 结构化摘要
     */
    private fun generateStructured(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        maxTokens: Int,
        originalTokens: Int
    ): ConversationSummary {
        val text = messages.joinToString("\n") { "${it.role}: ${it.content}" }

        val topics = extractTopics(messages)
        val decisions = extractDecisions(messages)
        val actionItems = extractActionItems(messages)
        val entities = extractEntities(messages)
        val timeline = extractTimeline(messages)
        val openQuestions = extractOpenQuestions(messages)

        val structured = StructuredSummary(
            overview = generateOverview(messages),
            topics = topics,
            timeline = timeline,
            decisions = decisions,
            actionItems = actionItems,
            keyEntities = entities,
            openQuestions = openQuestions
        )

        val summaryText = formatStructured(structured)
        val summaryTokens = estimateTokens(summaryText)

        return ConversationSummary(
            id = "summary_${System.currentTimeMillis()}",
            chatId = chatId,
            strategy = SummaryStrategy.STRUCTURED,
            originalMessageCount = messages.size,
            originalTokenCount = originalTokens,
            summary = summaryText,
            structuredContent = structured,
            keyPoints = topics.map { it.topic },
            decisions = decisions.map { it.decision },
            actionItems = actionItems.map { it.description },
            entities = entities.map { it.name },
            topics = topics.map { it.topic },
            summaryTokenCount = summaryTokens,
            compressionRatio = if (originalTokens > 0) summaryTokens.toFloat() / originalTokens else 1f
        )
    }

    /**
     * 增量摘要：只处理新增内容
     */
    private fun generateIncremental(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        maxTokens: Int,
        originalTokens: Int
    ): ConversationSummary {
        val lastSummary = summaries[chatId]?.lastOrNull()
        val newMessages = if (lastSummary != null) {
            messages.filter { it.timestamp > lastSummary.createdAt }
        } else messages

        if (newMessages.isEmpty()) {
            return lastSummary ?: emptySummary(chatId, messages, originalTokens)
        }

        val newSummary = generateStructured(chatId, newMessages, maxTokens, originalTokens)

        // 合并到旧摘要
        val merged = if (lastSummary != null) {
            mergeSummaries(lastSummary, newSummary)
        } else newSummary

        return merged.copy(strategy = SummaryStrategy.INCREMENTAL)
    }

    /**
     * 混合摘要：抽取 + 结构化
     */
    private fun generateHybrid(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        maxTokens: Int,
        originalTokens: Int
    ): ConversationSummary {
        val structured = generateStructured(chatId, messages, maxTokens / 2, originalTokens)
        val extractive = generateExtractive(chatId, messages, maxTokens / 2, originalTokens)

        val combinedText = buildString {
            appendLine(structured.summary)
            appendLine()
            appendLine("--- 关键要点 ---")
            extractive.keyPoints.take(5).forEach { appendLine("- $it") }
        }

        return structured.copy(
            strategy = SummaryStrategy.HYBRID,
            summary = combinedText.toString(),
            keyPoints = (structured.keyPoints + extractive.keyPoints).distinct(),
            summaryTokenCount = structured.summaryTokenCount + extractive.summaryTokenCount
        )
    }

    // ============ 提取方法 ============

    private fun extractTopics(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): List<TopicSummary> {
        // 按关键词聚类
        val topicGroups = mutableMapOf<String, MutableList<com.apex.agent.core.normal.context.ConversationMessage>>()
        messages.forEach { msg ->
            val keywords = extractKeywords(msg.content)
            keywords.forEach { kw ->
                topicGroups.getOrPut(kw) { mutableListOf() }.add(msg)
            }
        }
        return topicGroups
            .filter { it.value.size >= 2 }
            .map { (topic, msgs) ->
                TopicSummary(
                    topic = topic,
                    summary = msgs.first().content.take(100),
                    messageRange = msgs.first().id.hashCode()..msgs.last().id.hashCode()
                )
            }
            .sortedByDescending { it.messageRange.last - it.messageRange.first }
            .take(5)
    }

    private fun extractDecisions(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): List<DecisionRecord> {
        val decisionPatterns = listOf("决定", "同意", "选择", "确认", "decided", "agreed", "chose", "will", "let's go with")
        return messages.mapNotNull { msg ->
            val decision = decisionPatterns.firstOrNull { msg.content.contains(it, ignoreCase = true) }
            if (decision != null) {
                DecisionRecord(
                    decision = msg.content.take(200),
                    rationale = "基于对话上下文",
                    timestamp = msg.timestamp,
                    messageId = msg.id
                )
            } else null
        }
    }

    private fun extractActionItems(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): List<ActionItem> {
        val actionPatterns = listOf("需要", "待办", "todo", "应该", "plan to", "need to", "should", "must")
        return messages.flatMap { msg ->
            actionPatterns.mapNotNull { pattern ->
                if (msg.content.contains(pattern, ignoreCase = true)) {
                    val sentence = msg.content.split(Regex("[。.！!？?\\n]"))
                        .firstOrNull { it.contains(pattern, ignoreCase = true) }
                        ?: msg.content.take(100)
                    ActionItem(
                        description = sentence.trim(),
                        assignee = null,
                        dueDate = null,
                        priority = 3
                    )
                } else null
            }
        }.distinctBy { it.description }.take(10)
    }

    private fun extractEntities(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): List<EntityRecord> {
        val entityCounts = mutableMapOf<String, MutableList<Pair<Long, String>>>()  // name -> [(timestamp, type)]
        val entityTypes = mapOf(
            "人名" to Regex("[A-Z][a-z]+ [A-Z][a-z]+|[\\u4e00-\\u9fa5]{2,3}(说|表示|认为|提出)"),
            "日期" to Regex("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日"),
            "数字" to Regex("\\b\\d+(?:\\.\\d+)?%?\\b"),
            "URL" to Regex("https?://[^\\s]+"),
            "邮箱" to Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        )
        messages.forEach { msg ->
            entityTypes.forEach { (type, regex) ->
                regex.findAll(msg.content).forEach { match ->
                    val name = match.value
                    entityCounts.getOrPut(name) { mutableListOf() }.add(msg.timestamp to type)
                }
            }
        }
        return entityCounts.map { (name, occurrences) ->
            EntityRecord(
                name = name,
                type = occurrences.first().second,
                mentions = occurrences.size,
                firstMentioned = occurrences.minOf { it.first }
            )
        }.sortedByDescending { it.mentions }.take(10)
    }

    private fun extractTimeline(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): List<TimelineEvent> {
        return messages.map { msg ->
            TimelineEvent(
                timestamp = msg.timestamp,
                description = "${msg.role}: ${msg.content.take(80)}",
                type = msg.role.name
            )
        }
    }

    private fun extractOpenQuestions(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): List<String> {
        val questions = messages.flatMap { msg ->
            msg.content.split(Regex("[？?！!]"))
                .map { it.trim() }
                .filter { it.endsWith("?") || it.endsWith("？") || it.length > 10 }
        }
        return questions.distinct().take(5)
    }

    private fun generateOverview(messages: List<com.apex.agent.core.normal.context.ConversationMessage>): String {
        val userMessages = messages.filter { it.role == com.apex.agent.core.normal.context.ConversationMessage.Role.USER }
        val firstUserMsg = userMessages.firstOrNull()?.content?.take(100) ?: ""
        val msgCount = messages.size
        return "本次对话共 $msgCount 条消息，主要讨论：$firstUserMsg"
    }

    private fun formatStructured(s: StructuredSummary): String {
        return buildString {
            appendLine("## 总览")
            appendLine(s.overview)
            appendLine()
            if (s.topics.isNotEmpty()) {
                appendLine("## 主题")
                s.topics.forEach { appendLine("- ${it.topic}: ${it.summary}") }
                appendLine()
            }
            if (s.decisions.isNotEmpty()) {
                appendLine("## 决策")
                s.decisions.forEach { appendLine("- ${it.decision}") }
                appendLine()
            }
            if (s.actionItems.isNotEmpty()) {
                appendLine("## 待办")
                s.actionItems.forEach { appendLine("- [ ] ${it.description}") }
                appendLine()
            }
            if (s.keyEntities.isNotEmpty()) {
                appendLine("## 关键实体")
                s.keyEntities.forEach { appendLine("- [${it.type}] ${it.name} (×${it.mentions})") }
                appendLine()
            }
            if (s.openQuestions.isNotEmpty()) {
                appendLine("## 未解决问题")
                s.openQuestions.forEach { appendLine("- $it") }
            }
        }
    }

    private fun mergeSummaries(old: ConversationSummary, new: ConversationSummary): ConversationSummary {
        val merged = (old.keyPoints + new.keyPoints).distinct()
        val mergedDecisions = (old.decisions + new.decisions).distinct()
        val mergedActions = (old.actionItems + new.actionItems).distinct()
        val mergedEntities = (old.entities + new.entities).distinct()
        val mergedTopics = (old.topics + new.topics).distinct()

        return new.copy(
            keyPoints = merged,
            decisions = mergedDecisions,
            actionItems = mergedActions,
            entities = mergedEntities,
            topics = mergedTopics,
            originalMessageCount = old.originalMessageCount + new.originalMessageCount,
            originalTokenCount = old.originalTokenCount + new.originalTokenCount,
            summary = old.summary + "\n\n--- 新增 ---\n" + new.summary,
            summaryTokenCount = old.summaryTokenCount + new.summaryTokenCount
        )
    }

    // ============ TextRank ============

    private data class Sentence(val text: String, val messageId: String, val role: com.apex.agent.core.normal.context.ConversationMessage.Role, val timestamp: Long)

    private fun textrank(sentences: List<Sentence>): List<Sentence> {
        if (sentences.size <= 3) return sentences

        // 计算句子间相似度（基于词重叠）
        val n = sentences.size
        val similarity = Array(n) { FloatArray(n) }
        val words = sentences.map { tokenize(it.text) }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sim = jaccardSimilarity(words[i], words[j])
                similarity[i][j] = sim
                similarity[j][i] = sim
            }
        }

        // PageRank 迭代
        val scores = FloatArray(n) { 1f }
        val d = 0.85f
        repeat(20) {
            val newScores = FloatArray(n)
            for (i in 0 until n) {
                var sum = 0f
                for (j in 0 until n) {
                    if (i == j || similarity[j][i] == 0f) continue
                    val total = (0 until n).filter { it != j }.sumOf { similarity[j][it].toDouble() }.toFloat()
                    if (total > 0) sum += similarity[j][i] / total * scores[j]
                }
                newScores[i] = (1 - d) + d * sum
            }
            for (i in 0 until n) scores[i] = newScores[i]
        }

        return sentences.indices.sortedByDescending { scores[it] }.map { sentences[it] }
    }

    private fun jaccardSimilarity(a: List<String>, b: List<String>): Float {
        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union > 0) intersection.toFloat() / union else 0f
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】\\n]+"))
            .filter { it.length >= 2 }
    }

    private fun extractKeywords(text: String): List<String> {
        return tokenize(text)
            .filter { it.lowercase() !in STOP_WORDS }
            .groupBy { it }
            .filter { it.value.size >= 2 }
            .keys
            .toList()
    }

    private fun estimateTokens(text: String): Int =
        com.apex.agent.core.normal.context.SmartContextCompressor.estimateTokens(text)

    private fun emptySummary(
        chatId: String,
        messages: List<com.apex.agent.core.normal.context.ConversationMessage>,
        originalTokens: Int
    ) = ConversationSummary(
        id = "summary_${System.currentTimeMillis()}",
        chatId = chatId,
        strategy = SummaryStrategy.EXTRACTIVE,
        originalMessageCount = messages.size,
        originalTokenCount = originalTokens,
        summary = "",
        summaryTokenCount = 0,
        compressionRatio = 0f
    )

    private val STOP_WORDS = setOf(
        "的", "了", "是", "在", "我", "你", "他", "这", "那", "什么", "怎么",
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "have", "has", "had",
        "do", "does", "did", "will", "would", "could", "should", "may", "might", "can",
        "what", "how", "why", "when", "where", "who", "which"
    )

    /**
     * 获取历史摘要
     */
    fun getHistory(chatId: String): List<ConversationSummary> = summaries[chatId]?.toList() ?: emptyList()

    /**
     * 获取最新摘要
     */
    fun getLatest(chatId: String): ConversationSummary? = summaries[chatId]?.lastOrNull()
}
