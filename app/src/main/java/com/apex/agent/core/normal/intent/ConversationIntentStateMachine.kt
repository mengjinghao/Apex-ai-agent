package com.apex.agent.core.normal.intent

import java.util.concurrent.ConcurrentHashMap

/**
 * F1: 对话意图状态机
 *
 * 跨轮次追踪用户对话意图，识别"追问/纠正/切换话题/补充信息"等模式，
 * 让 LLM 知道用户当前是在延续上一个话题还是开启新话题。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 关注 Agent 间意图（委派/协作）
 * - 狂暴关注任务执行意图（brute force）
 * - 本功能专注**用户对话意图**，是单 Agent 体验的核心
 */

/**
 * 对话意图类型
 */
enum class ConversationIntent {
    /** 首次提问 */
    INITIAL_QUERY,
    /** 追问上一个话题 */
    FOLLOW_UP,
    /** 纠正之前的回答 */
    CORRECTION,
    /** 切换到新话题 */
    TOPIC_SWITCH,
    /** 补充信息到上一个问题 */
    SUPPLEMENT,
    /** 确认理解 */
    CONFIRMATION,
    /** 请求澄清 */
    CLARIFICATION,
    /** 表达满意/结束 */
    CLOSURE,
    /** 请求重新生成 */
    REGENERATION_REQUEST
}

/**
 * 意图状态
 */
data class IntentState(
    val currentIntent: ConversationIntent,
    val topicId: String,
    val topicSummary: String,
    val confidence: Float,
    val detectedAt: Long = System.currentTimeMillis(),
    val triggerKeywords: List<String> = emptyList()
)

/**
 * 话题追踪
 */
data class TrackedTopic(
    val id: String,
    val summary: String,
    val startedAt: Long,
    val lastActiveAt: Long,
    val messageCount: Int,
    val keywords: List<String>
)

/**
 * 对话意图状态机
 *
 * 维护当前对话的意图状态，跨轮次追踪话题演变
 */
class ConversationIntentStateMachine {

    private val states = ConcurrentHashMap<String, IntentState>()
    private val topics = ConcurrentHashMap<String, MutableList<TrackedTopic>>()
    private val currentTopicIndex = ConcurrentHashMap<String, Int>()

    /**
     * 检测用户消息的意图
     */
    fun detect(chatId: String, userMessage: String, history: List<String>): IntentState {
        val msg = userMessage.trim().lowercase()
        val currentState = states[chatId]
        val topicsForChat = topics.computeIfAbsent(chatId) { mutableListOf() }

        val (intent, confidence, keywords) = classifyIntent(msg, currentState, history)

        // 话题管理
        val topicId: String
        val topicSummary: String
        when (intent) {
            ConversationIntent.INITIAL_QUERY, ConversationIntent.TOPIC_SWITCH -> {
                val newTopic = TrackedTopic(
                    id = "topic_${System.currentTimeMillis()}",
                    summary = userMessage.take(50),
                    startedAt = System.currentTimeMillis(),
                    lastActiveAt = System.currentTimeMillis(),
                    messageCount = 1,
                    keywords = extractKeywords(userMessage)
                )
                topicsForChat.add(newTopic)
                currentTopicIndex[chatId] = topicsForChat.size - 1
                topicId = newTopic.id
                topicSummary = newTopic.summary
            }
            ConversationIntent.FOLLOW_UP, ConversationIntent.SUPPLEMENT,
            ConversationIntent.CORRECTION, ConversationIntent.CLARIFICATION,
            ConversationIntent.CONFIRMATION, ConversationIntent.REGENERATION_REQUEST -> {
                val idx = currentTopicIndex[chatId] ?: (topicsForChat.size - 1).coerceAtLeast(0)
                val topic = topicsForChat.getOrNull(idx)
                if (topic != null) {
                    val updated = topic.copy(
                        lastActiveAt = System.currentTimeMillis(),
                        messageCount = topic.messageCount + 1,
                        keywords = (topic.keywords + extractKeywords(userMessage)).distinct().take(10)
                    )
                    topicsForChat[idx] = updated
                    topicId = updated.id
                    topicSummary = updated.summary
                } else {
                    topicId = "topic_unknown"
                    topicSummary = userMessage.take(50)
                }
            }
            ConversationIntent.CLOSURE -> {
                val idx = currentTopicIndex[chatId] ?: (topicsForChat.size - 1).coerceAtLeast(0)
                topicId = topicsForChat.getOrNull(idx)?.id ?: "topic_unknown"
                topicSummary = topicsForChat.getOrNull(idx)?.summary ?: userMessage.take(50)
            }
        }

        val state = IntentState(
            currentIntent = intent,
            topicId = topicId,
            topicSummary = topicSummary,
            confidence = confidence,
            triggerKeywords = keywords
        )
        states[chatId] = state
        return state
    }

    /**
     * 获取当前意图状态
     */
    fun getCurrentState(chatId: String): IntentState? = states[chatId]

    /**
     * 获取所有话题
     */
    fun getTopics(chatId: String): List<TrackedTopic> = topics[chatId]?.toList() ?: emptyList()

    /**
     * 生成意图提示，注入到 system prompt
     */
    fun getIntentPrompt(chatId: String): String {
        val state = states[chatId] ?: return ""
        return when (state.currentIntent) {
            ConversationIntent.INITIAL_QUERY -> ""
            ConversationIntent.FOLLOW_UP -> "[意图提示：用户在追问上一个话题「${state.topicSummary}」，请保持上下文连贯]"
            ConversationIntent.CORRECTION -> "[意图提示：用户在纠正之前的回答，请重新审视并修正]"
            ConversationIntent.TOPIC_SWITCH -> "[意图提示：用户切换到新话题「${state.topicSummary}」，可以不用参考之前的上下文]"
            ConversationIntent.SUPPLEMENT -> "[意图提示：用户在补充上一个问题的信息，请结合新信息重新回答]"
            ConversationIntent.CONFIRMATION -> "[意图提示：用户在确认理解，请简洁回应]"
            ConversationIntent.CLARIFICATION -> "[意图提示：用户请求澄清，请更详细地解释]"
            ConversationIntent.CLOSURE -> "[意图提示：用户表示满意或结束，请简短收尾]"
            ConversationIntent.REGENERATION_REQUEST -> "[意图提示：用户要求重新生成，请尝试不同的回答角度]"
        }
    }

    /**
     * 重置会话意图
     */
    fun reset(chatId: String) {
        states.remove(chatId)
        topics.remove(chatId)
        currentTopicIndex.remove(chatId)
    }

    // ============ 内部方法 ============

    private fun classifyIntent(
        message: String,
        currentState: IntentState?,
        history: List<String>
    ): Triple<ConversationIntent, Float, List<String>> {
        // 关键词模式匹配
        val patterns = mapOf(
            ConversationIntent.CORRECTION to listOf("不对", "错了", "不是这个意思", "重新", "纠正", "wrong", "incorrect", "no,"),
            ConversationIntent.SUPPLEMENT to listOf("补充", "另外", "还有", "加上", "对了", "顺便", "also", "additionally", "plus"),
            ConversationIntent.CONFIRMATION to listOf("对", "是的", "没错", "确认", "ok", "yes", "correct", "right"),
            ConversationIntent.CLARIFICATION to listOf("什么意思", "不明白", "解释一下", "详细", "为什么", "what do you mean", "explain", "clarify"),
            ConversationIntent.CLOSURE to listOf("谢谢", "好的", "知道了", "明白了", "thanks", "got it", "understood"),
            ConversationIntent.REGENERATION_REQUEST to listOf("重新生成", "换一个", "再来一次", "regenerate", "try again", "another")
        )

        val matchedKeywords = mutableListOf<String>()
        for ((intent, keywords) in patterns) {
            for (kw in keywords) {
                if (message.contains(kw)) {
                    matchedKeywords.add(kw)
                    return Triple(intent, 0.85f, matchedKeywords)
                }
            }
        }

        // 检测话题切换：代词指代不清 + 主题词变化大
        if (currentState != null) {
            val currentKeywords = extractKeywords(currentState.topicSummary).toSet()
            val newKeywords = extractKeywords(message).toSet()
            val overlap = currentKeywords.intersect(newKeywords).size
            val total = currentKeywords.union(newKeywords).size
            val similarity = if (total > 0) overlap.toFloat() / total else 0f

            return when {
                similarity < 0.2f -> Triple(ConversationIntent.TOPIC_SWITCH, 0.75f, listOf("low_topic_similarity=$similarity"))
                similarity > 0.5f -> Triple(ConversationIntent.FOLLOW_UP, 0.7f, listOf("high_topic_similarity=$similarity"))
                else -> Triple(ConversationIntent.SUPPLEMENT, 0.6f, listOf("medium_topic_similarity=$similarity"))
            }
        }

        return Triple(ConversationIntent.INITIAL_QUERY, 1.0f, emptyList())
    }

    private fun extractKeywords(text: String): List<String> {
        // 简化：按空格和标点分词，过滤短词
        return text.split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】]+"))
            .filter { it.length >= 2 }
            .filter { it.lowercase() !in STOP_WORDS }
            .take(8)
    }

    private val STOP_WORDS = setOf(
        "的", "了", "是", "在", "我", "你", "他", "她", "它", "我们", "你们", "他们",
        "这", "那", "这个", "那个", "这些", "那些", "什么", "怎么", "为什么", "哪里",
        "可以", "能", "会", "要", "想", "需要", "应该", "the", "a", "an", "is", "are",
        "was", "were", "be", "been", "have", "has", "had", "do", "does", "did",
        "will", "would", "could", "should", "may", "might", "can", "what", "how", "why"
    )
}
