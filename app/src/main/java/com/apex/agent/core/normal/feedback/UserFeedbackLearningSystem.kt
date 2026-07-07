package com.apex.agent.core.normal.feedback

import java.util.concurrent.ConcurrentHashMap

/**
 * F30: 用户反馈学习系统
 *
 * 从用户反馈中学习并优化：
 * - 显式反馈（点赞/点踩/编辑/评分）
 * - 隐式反馈（采纳/编辑/重试/停留时间）
 * - 反馈模式分析
 * - 自动优化建议
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的反馈是 Agent 间
 * - 狂暴不学习用户反馈
 * - 本功能是**单 Agent 持续优化**的核心，让 AI 越用越懂你
 */

/**
 * 反馈类型
 */
enum class FeedbackType {
    // 显式反馈
    THUMBS_UP,       // 点赞
    THUMBS_DOWN,     // 点踩
    STAR_RATING,     // 星级评分
    TEXT_FEEDBACK,   // 文字反馈
    REPORT_ISSUE,    // 问题报告

    // 隐式反馈
    ACCEPTED,        // 采纳（未编辑）
    EDITED,          // 编辑后采纳
    REGENERATED,     // 要求重新生成
    COPIED,          // 复制了回答
    IGNORED,         // 忽略（未互动）
    LONG_DWELL,      // 长时间停留
    QUICK_DISMISS,   // 快速关闭
    FOLLOWED_UP,     // 追问
    SWITCHED_TOPIC   // 切换话题
}

/**
 * 反馈记录
 */
data class FeedbackRecord(
    val id: String,
    val userId: String,
    val chatId: String,
    val messageId: String,
    val type: FeedbackType,
    val value: Any? = null,           // 评分值/编辑内容/反馈文本
    val originalContent: String? = null,
    val editedContent: String? = null,
    val context: FeedbackContext,
    val timestamp: Long = System.currentTimeMillis()
)

data class FeedbackContext(
    val userMessage: String,
    val assistantResponse: String,
    val responseTimeMs: Long,
    val toolCallsUsed: List<String> = emptyList(),
    val sceneTemplate: String? = null,
    val responseDepth: String? = null,
    val detectedEmotion: String? = null
)

/**
 * 反馈统计
 */
data class FeedbackStats(
    val totalFeedback: Int,
    val positiveCount: Int,
    val negativeCount: Int,
    val neutralCount: Int,
    val positiveRate: Float,
    val avgRating: Float,
    val editRate: Float,
    val regenerateRate: Float,
    val acceptanceRate: Float,
    val feedbackByType: Map<FeedbackType, Int>
)

/**
 * 学习洞察
 */
data class FeedbackInsight(
    val type: InsightType,
    val description: String,
    val confidence: Float,
    val evidence: List<String>,
    val suggestedAction: String
)

enum class InsightType {
    PREFERRED_LENGTH,       // 偏好长度
    PREFERRED_STYLE,        // 偏好风格
    PREFERRED_DEPTH,        // 偏好深度
    PREFERRED_FORMAT,       // 偏好格式
    DISLIKED_PATTERN,       // 不喜欢的模式
    HIGHLY_RATED_PATTERN,   // 高分模式
    IMPROVEMENT_AREA,       // 改进方向
    TOOL_PREFERENCE,        // 工具偏好
    SCENE_PREFERENCE,       // 场景偏好
    RESPONSE_TIME_SENSITIVITY  // 响应时间敏感度
}

/**
 * 用户反馈学习系统
 */
class UserFeedbackLearningSystem {

    private val feedbacks = ConcurrentHashMap<String, MutableList<FeedbackRecord>>()
    private val insights = ConcurrentHashMap<String, MutableList<FeedbackInsight>>()

    /**
     * 记录反馈
     */
    fun record(feedback: FeedbackRecord) {
        feedbacks.computeIfAbsent(feedback.userId) { mutableListOf() }.add(feedback)
        // 触发学习
        analyzeAndLearn(feedback.userId)
    }

    /**
     * 便捷方法：记录点赞
     */
    fun recordThumbsUp(userId: String, chatId: String, messageId: String, context: FeedbackContext) {
        record(FeedbackRecord(
            id = "fb_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            userId = userId, chatId = chatId, messageId = messageId,
            type = FeedbackType.THUMBS_UP, context = context
        ))
    }

    /**
     * 便捷方法：记录点踩
     */
    fun recordThumbsDown(userId: String, chatId: String, messageId: String, context: FeedbackContext, reason: String? = null) {
        record(FeedbackRecord(
            id = "fb_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            userId = userId, chatId = chatId, messageId = messageId,
            type = FeedbackType.THUMBS_DOWN, value = reason, context = context
        ))
    }

    /**
     * 便捷方法：记录编辑
     */
    fun recordEdit(userId: String, chatId: String, messageId: String, original: String, edited: String, context: FeedbackContext) {
        record(FeedbackRecord(
            id = "fb_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            userId = userId, chatId = chatId, messageId = messageId,
            type = FeedbackType.EDITED,
            originalContent = original, editedContent = edited,
            context = context
        ))
    }

    /**
     * 便捷方法：记录隐式采纳
     */
    fun recordAccepted(userId: String, chatId: String, messageId: String, context: FeedbackContext) {
        record(FeedbackRecord(
            id = "fb_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            userId = userId, chatId = chatId, messageId = messageId,
            type = FeedbackType.ACCEPTED, context = context
        ))
    }

    /**
     * 获取统计
     */
    fun getStats(userId: String): FeedbackStats {
        val userFeedbacks = feedbacks[userId] ?: return FeedbackStats(0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, emptyMap())
        val total = userFeedbacks.size
        val positive = userFeedbacks.count { isPositive(it.type) }
        val negative = userFeedbacks.count { isNegative(it.type) }
        val neutral = total - positive - negative

        val ratings = userFeedbacks.filter { it.type == FeedbackType.STAR_RATING }
            .mapNotNull { (it.value as? Number)?.toFloat() }
        val avgRating = if (ratings.isNotEmpty()) ratings.average().toFloat() else 0f

        val editCount = userFeedbacks.count { it.type == FeedbackType.EDITED }
        val regenerateCount = userFeedbacks.count { it.type == FeedbackType.REGENERATED }
        val acceptedCount = userFeedbacks.count { it.type == FeedbackType.ACCEPTED }
        val responses = userFeedbacks.mapNotNull { it.context.assistantResponse }.distinct().size.coerceAtLeast(1)

        val byType = userFeedbacks.groupingBy { it.type }.eachCount()

        return FeedbackStats(
            totalFeedback = total,
            positiveCount = positive,
            negativeCount = negative,
            neutralCount = neutral,
            positiveRate = if (total > 0) positive.toFloat() / total else 0f,
            avgRating = avgRating,
            editRate = if (responses > 0) editCount.toFloat() / responses else 0f,
            regenerateRate = if (responses > 0) regenerateCount.toFloat() / responses else 0f,
            acceptanceRate = if (responses > 0) acceptedCount.toFloat() / responses else 0f,
            feedbackByType = byType
        )
    }

    /**
     * 获取学习洞察
     */
    fun getInsights(userId: String): List<FeedbackInsight> {
        return insights[userId]?.toList() ?: emptyList()
    }

    /**
     * 生成优化建议 prompt
     */
    fun generateOptimizationPrompt(userId: String): String {
        val userInsights = insights[userId] ?: return ""
        if (userInsights.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[基于用户反馈的优化建议]")
        userInsights.take(5).forEach { insight ->
            sb.appendLine("- ${insight.description}")
        }
        return sb.toString()
    }

    /**
     * 分析并学习
     */
    private fun analyzeAndLearn(userId: String) {
        val userFeedbacks = feedbacks[userId] ?: return
        if (userFeedbacks.size < 5) return  // 至少 5 条反馈才开始学习

        val newInsights = mutableListOf<FeedbackInsight>()

        // 分析 1: 偏好长度
        newInsights.add(analyzePreferredLength(userFeedbacks))

        // 分析 2: 偏好风格
        newInsights.add(analyzePreferredStyle(userFeedbacks))

        // 分析 3: 偏好深度
        newInsights.add(analyzePreferredDepth(userFeedbacks))

        // 分析 4: 不喜欢的模式
        analyzeDislikedPatterns(userFeedbacks)?.let { newInsights.add(it) }

        // 分析 5: 高分模式
        analyzeHighlyRatedPatterns(userFeedbacks)?.let { newInsights.add(it) }

        // 分析 6: 改进方向
        analyzeImprovementAreas(userFeedbacks)?.let { newInsights.add(it) }

        // 分析 7: 响应时间敏感度
        newInsights.add(analyzeResponseTimeSensitivity(userFeedbacks))

        insights[userId] = newInsights.filter { it.confidence > 0.5f }.toMutableList()
    }

    // ============ 分析方法 ============

    private fun analyzePreferredLength(feedbacks: List<FeedbackRecord>): FeedbackInsight {
        val positive = feedbacks.filter { isPositive(it.type) }
        val negative = feedbacks.filter { isNegative(it.type) }

        val posLengths = positive.map { it.context.assistantResponse.length }
        val negLengths = negative.map { it.context.assistantResponse.length }

        val avgPos = if (posLengths.isNotEmpty()) posLengths.average() else 0.0
        val avgNeg = if (negLengths.isNotEmpty()) negLengths.average() else 0.0

        val preference = when {
            avgPos > avgNeg * 1.5 -> "较长"
            avgPos < avgNeg * 0.7 -> "较短"
            else -> "适中"
        }

        return FeedbackInsight(
            type = InsightType.PREFERRED_LENGTH,
            description = "用户偏好$preference的回答（正面平均 ${avgPos.toInt()} 字符，负面 ${avgNeg.toInt()} 字符）",
            confidence = if (positive.size + negative.size >= 5) 0.8f else 0.5f,
            evidence = listOf("正面反馈 ${positive.size} 条", "负面反馈 ${negative.size} 条"),
            suggestedAction = if (preference == "较长") "增加回答深度" else if (preference == "较短") "精简回答" else "保持当前长度"
        )
    }

    private fun analyzePreferredStyle(feedbacks: List<FeedbackRecord>): FeedbackInsight {
        val edited = feedbacks.filter { it.type == FeedbackType.EDITED }

        // 分析编辑模式：用户把什么改成了什么
        var formalToCasual = 0
        var casualToFormal = 0
        var addedEmoji = 0
        var removedEmoji = 0

        for (fb in edited) {
            val original = fb.originalContent ?: ""
            val edited = fb.editedContent ?: ""

            if (original.contains(Regex("尊敬的|烦请|敬请")) && edited.contains(Regex("嘿|哈喽|hi"))) formalToCasual++
            if (edited.contains(Regex("尊敬的|烦请|敬请")) && original.contains(Regex("嘿|哈喽|hi"))) casualToFormal++
            if (edited.count { it in "😀😄😊🙂" } > original.count { it in "😀😄😊🙂" }) addedEmoji++
            if (original.count { it in "😀😄😊🙂" } > edited.count { it in "😀😄😊🙂" }) removedEmoji++
        }

        val preference = when {
            formalToCasual > casualToFormal -> "随意口语"
            casualToFormal > formalToCasual -> "正式书面"
            addedEmoji > removedEmoji -> "含 emoji"
            removedEmoji > addedEmoji -> "无 emoji"
            else -> "无明显偏好"
        }

        return FeedbackInsight(
            type = InsightType.PREFERRED_STYLE,
            description = "用户偏好$preference 风格（基于 ${edited.size} 次编辑）",
            confidence = if (edited.size >= 3) 0.75f else 0.4f,
            evidence = listOf("正式→随意: $formalToCasual", "随意→正式: $casualToFormal", "加 emoji: $addedEmoji", "去 emoji: $removedEmoji"),
            suggestedAction = if (preference != "无明显偏好") "调整回答风格为$preference" else "保持当前风格"
        )
    }

    private fun analyzePreferredDepth(feedbacks: List<FeedbackRecord>): FeedbackInsight {
        val byDepth = feedbacks.groupBy { it.context.responseDepth ?: "unknown" }
        val positiveByDepth = byDepth.mapValues { (_, fbs) ->
            fbs.count { isPositive(it.type) }.toFloat() / fbs.size.coerceAtLeast(1)
        }

        val bestDepth = positiveByDepth.maxByOrNull { it.value }
        val preference = bestDepth?.key ?: "standard"

        return FeedbackInsight(
            type = InsightType.PREFERRED_DEPTH,
            description = "用户偏好 $preference 深度的回答（正面率 ${(bestDepth?.value ?: 0f) * 100}%）",
            confidence = if ((bestDepth?.value ?: 0f) > 0.6f) 0.8f else 0.4f,
            evidence = positiveByDepth.map { "${it.key}: ${(it.value * 100).toInt()}%" },
            suggestedAction = "默认使用 $preference 深度"
        )
    }

    private fun analyzeDislikedPatterns(feedbacks: List<FeedbackRecord>): FeedbackInsight? {
        val negative = feedbacks.filter { isNegative(it.type) }
        if (negative.size < 3) return null

        // 分析负面反馈的共同特征
        val patterns = mutableMapOf<String, Int>()

        negative.forEach { fb ->
            val response = fb.context.assistantResponse
            if (response.length > 1000) patterns["过长"] = (patterns["过长"] ?: 0) + 1
            if (response.length < 50) patterns["过短"] = (patterns["过短"] ?: 0) + 1
            if (!response.contains(Regex("\\d+[.、)]"))) patterns["缺少分点"] = (patterns["缺少分点"] ?: 0) + 1
            if (!response.contains("```")) patterns["缺少代码示例"] = (patterns["缺少代码示例"] ?: 0) + 1
            if (response.contains(Regex("总之|综上所述|总的来说"))) patterns["套话结尾"] = (patterns["套话结尾"] ?: 0) + 1
            if (fb.context.responseTimeMs > 5000) patterns["响应慢"] = (patterns["响应慢"] ?: 0) + 1
        }

        val topPattern = patterns.maxByOrNull { it.value } ?: return null

        return FeedbackInsight(
            type = InsightType.DISLIKED_PATTERN,
            description = "用户不喜欢「${topPattern.key}」的回答（在 ${topPattern.value}/${negative.size} 负面反馈中出现）",
            confidence = if (topPattern.value >= 3) 0.85f else 0.6f,
            evidence = patterns.map { "${it.key}: ${it.value}次" },
            suggestedAction = "避免${topPattern.key}"
        )
    }

    private fun analyzeHighlyRatedPatterns(feedbacks: List<FeedbackRecord>): FeedbackInsight? {
        val positive = feedbacks.filter { isPositive(it.type) }
        if (positive.size < 3) return null

        val patterns = mutableMapOf<String, Int>()
        positive.forEach { fb ->
            val response = fb.context.assistantResponse
            if (response.contains("```")) patterns["含代码"] = (patterns["含代码"] ?: 0) + 1
            if (response.contains(Regex("\\d+[.、)]"))) patterns["分点列表"] = (patterns["分点列表"] ?: 0) + 1
            if (response.contains("**") || response.contains("__")) patterns["加粗强调"] = (patterns["加粗强调"] ?: 0) + 1
            if (response.contains(Regex("^#+\\s", RegexOption.MULTILINE))) patterns["有标题"] = (patterns["有标题"] ?: 0) + 1
            if (response.length in 200..800) patterns["适中长度"] = (patterns["适中长度"] ?: 0) + 1
            if (fb.context.responseTimeMs < 3000) patterns["快速响应"] = (patterns["快速响应"] ?: 0) + 1
        }

        val topPattern = patterns.maxByOrNull { it.value } ?: return null

        return FeedbackInsight(
            type = InsightType.HIGHLY_RATED_PATTERN,
            description = "高分回答常含「${topPattern.key}」（${topPattern.value}/${positive.size} 正面反馈）",
            confidence = if (topPattern.value >= 3) 0.8f else 0.6f,
            evidence = patterns.map { "${it.key}: ${it.value}次" },
            suggestedAction = "保持${topPattern.key}"
        )
    }

    private fun analyzeImprovementAreas(feedbacks: List<FeedbackRecord>): FeedbackInsight? {
        val edited = feedbacks.filter { it.type == FeedbackType.EDITED }
        if (edited.size < 3) return null

        // 分析用户常修改什么
        val editTypes = mutableMapOf<String, Int>()
        edited.forEach { fb ->
            val orig = fb.originalContent ?: ""
            val edited = fb.editedContent ?: ""
            when {
                edited.length < orig.length * 0.8 -> editTypes["缩短"] = (editTypes["缩短"] ?: 0) + 1
                edited.length > orig.length * 1.2 -> editTypes["扩充"] = (editTypes["扩充"] ?: 0) + 1
                edited.contains(Regex("请|麻烦|能否")) && !orig.contains(Regex("请|麻烦|能否")) -> editTypes["增加礼貌"] = (editTypes["增加礼貌"] ?: 0) + 1
                edited.contains("```") && !orig.contains("```") -> editTypes["补代码"] = (editTypes["补代码"] ?: 0) + 1
                else -> editTypes["其他"] = (editTypes["其他"] ?: 0) + 1
            }
        }

        val topEdit = editTypes.maxByOrNull { it.value } ?: return null

        return FeedbackInsight(
            type = InsightType.IMPROVEMENT_AREA,
            description = "用户常${topEdit.key}回答（${topEdit.value}/${edited.size} 次编辑）",
            confidence = if (topEdit.value >= 3) 0.8f else 0.5f,
            evidence = editTypes.map { "${it.key}: ${it.value}次" },
            suggestedAction = when (topEdit.key) {
                "缩短" -> "更精简"
                "扩充" -> "更详细"
                "增加礼貌" -> "更礼貌"
                "补代码" -> "主动加代码"
                else -> ""
            }
        )
    }

    private fun analyzeResponseTimeSensitivity(feedbacks: List<FeedbackRecord>): FeedbackInsight {
        val fastPositive = feedbacks.count { it.context.responseTimeMs < 3000 && isPositive(it.type) }
        val fastNegative = feedbacks.count { it.context.responseTimeMs < 3000 && isNegative(it.type) }
        val slowPositive = feedbacks.count { it.context.responseTimeMs > 5000 && isPositive(it.type) }
        val slowNegative = feedbacks.count { it.context.responseTimeMs > 5000 && isNegative(it.type) }

        val fastRate = if (fastPositive + fastNegative > 0) fastPositive.toFloat() / (fastPositive + fastNegative) else 0.5f
        val slowRate = if (slowPositive + slowNegative > 0) slowPositive.toFloat() / (slowPositive + slowNegative) else 0.5f

        val sensitive = slowRate < fastRate * 0.7f
        return FeedbackInsight(
            type = InsightType.RESPONSE_TIME_SENSITIVITY,
            description = if (sensitive) "用户对响应时间敏感（快:${(fastRate * 100).toInt()}%好评 vs 慢:${(slowRate * 100).toInt()}%）"
                          else "用户对响应时间不敏感",
            confidence = if (fastPositive + fastNegative + slowPositive + slowNegative >= 5) 0.75f else 0.4f,
            evidence = listOf("快速响应好评率: ${(fastRate * 100).toInt()}%", "慢速响应好评率: ${(slowRate * 100).toInt()}%"),
            suggestedAction = if (sensitive) "优先保证响应速度" else "可优先质量"
        )
    }

    private fun isPositive(type: FeedbackType): Boolean = type in setOf(
        FeedbackType.THUMBS_UP, FeedbackType.STAR_RATING,  // 评分视为正面（实际应判断值）
        FeedbackType.ACCEPTED, FeedbackType.COPIED, FeedbackType.FOLLOWED_UP, FeedbackType.LONG_DWELL
    )

    private fun isNegative(type: FeedbackType): Boolean = type in setOf(
        FeedbackType.THUMBS_DOWN, FeedbackType.REPORT_ISSUE,
        FeedbackType.REGENERATED, FeedbackType.QUICK_DISMISS, FeedbackType.IGNORED
    )

    /**
     * 清除用户数据
     */
    fun clearUserData(userId: String) {
        feedbacks.remove(userId)
        insights.remove(userId)
    }
}
