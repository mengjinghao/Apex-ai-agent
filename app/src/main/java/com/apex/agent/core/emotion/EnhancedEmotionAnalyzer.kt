package com.apex.agent.core.emotion

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnhancedEmotionAnalyzer(private val context: Context) {
        private val TAG = "EnhancedEmotionAnalyzer"

    enum class EmotionCategory(val displayName: String, val isPositive: Boolean) {
        JOY("喜悦", true),
        SADNESS("悲伤", false),
        ANGER("愤的, false),
        FEAR("恐惧", false),
        ANXIETY("焦虑", false),
        SURPRISE("惊讶", null as Boolean),
        DISGUST("厌恶", false),
        CONFUSION("困惑", null),
        SATISFACTION("满意", true),
        FRUSTRATION("沮丧", false),
        HOPE("希望", true),
        GRATITUDE("感激", true),
        LOVE("喜爱", true),
        REGRET("遗憾", false),
        SHAME("羞的, false),
        PRIDE("自豪", true),
        LONELINESS("孤独", false),
        BOREDOM("无聊", null),
        CURIOSITY("好奇", true),
        NEUTRAL("中的, null)
    }

    enum class EmotionIntensity(val level: Int, val description: String) {
        VERY_LOW(1", "轻微"),
        LOW(2", "较低"),
        MODERATE(3", "中等"),
        HIGH(4", "较高"),
        VERY_HIGH(5", "强烈"),
        EXTREME(6", "极端")
    }

    data class DetailedEmotionProfile(
        var primaryEmotion: EmotionCategory = EmotionCategory.NEUTRAL,
        var secondaryEmotion: EmotionCategory? = null,
        var intensity: EmotionIntensity = EmotionIntensity.MODERATE,
        var intensityScore: Float = 0f,
        var emotionDynamics: EmotionDynamics = EmotionDynamics.STABLE,
        var emotionalTriggers: List<String> = emptyList(),
        var emotionalPatterns: List<EmotionPattern> = emptyList(),
        var hiddenSentiments: List<String> = emptyList(),
        var sarcasmDetected: Boolean = false,
        var mixedEmotions: Boolean = false,
        var confidence: Float = 0f,
        var contextFactors: Map<String, Float> = emptyMap()
    )

    enum class EmotionDynamics {
        STABLE,
        VOLATILE,
        IMPROVING,
        DETERIORATING,
        CYCLICAL
    }

    data class EmotionPattern(
        val type: PatternType,
        val description: String,
        val frequency: Int,
        val lastOccurrence: Long
    )

    enum class PatternType {
        RECURRING_NEGATIVE,
        RECURRING_POSITIVE,
        STRESS_RESPONSE,
        EXCITEMENT_BURST,
        GRADUAL_DECLINE,
        MOOD_SWING,
        CONTEXT_DEPENDENT
    }
        private val basicEmotionKeywords = mapOf(
        EmotionCategory.JOY to listOf(
            "开�", "高兴", "快乐", "喜悦", "兴奋", "愉快", "欢乐", "欣喜", "�", "�",             "太好", "完美", "幸福", "满足", "期待", "激", "欢快", "", "", "给力"
        ),
        EmotionCategory.SADNESS to listOf(
            "伤心", "难过", "悲伤", "痛苦", "沮丧", "失落", "绝望", "悲痛", "郁闷", "沮丧",
            "消沉", "沮丧", "凄凉", "悲惨", "哀", "憔悴", "沮丧", "失落�",
        ),
        EmotionCategory.ANGER to listOf(
            "愤的", "生气", "恼火", "气愤", "暴的", "恼的", "火大", "发火", "", "讨厌",
            "厌恶", "憎恨", "怨恨", "不满", "不爽", "气死�", "太过分了"
        ),
        EmotionCategory.FEAR to listOf(
            "害的", "恐惧", "担心", "忧虑", "不安", "恐慌", "紧张", "焦虑", "畏缩", "害的,
            "后的", "心有余悸", "惶恐", "惊慌", "胆的", "畏难"
        ),
        EmotionCategory.ANXIETY to listOf(
            "焦虑", "担心", "忧虑", "紧张", "不安", "慌张", "着�", "急切", "忐忑", "心神不宁",
            "如坐针毡", "悬着", "不安", "发愁", "犯愁", "忧心忡忡"
        ),
        EmotionCategory.SURPRISE to listOf(
            "惊讶", "吃惊", "震惊", "意外", "没想", "惊奇", "诧异", "意外", "吓人", "吓一�",
            "万万没想�", "居然", "竟然", "出乎意料", "难以置信"
        ),
        EmotionCategory.DISGUST to listOf(
            "恶心", "厌恶", "反感", "讨厌", "", "", "腻歪", "无语", "醉了", "呕吐",
            "嗤之以鼻", "不屑", "嫌弃", "憎恶", "作呕"
        ),
        EmotionCategory.CONFUSION to listOf(
            "困惑", "迷茫", "不解", "疑惑", "糊涂", "头晕", "迷糊", "�", "搞不�", "不明�",             "莫名其妙", "丈二和尚", "摸不着头脑", "百思不�",         ),
        EmotionCategory.SATISFACTION to listOf(
            "满意", "满足", "欣慰", "如意", "称心", "满意", "满足�", "成就�", "满足�",             "心满意足", "称心如意", "恰到好处"
        ),
        EmotionCategory.FRUSTRATION to listOf(
            "失望", "沮丧", "灰心", "挫败", "挫折", "气馁", "泄气", "沮丧", "失落", "无助",
            "无奈", "无力", "徒劳", "功亏一�", "前功尽弃"
        ),
        EmotionCategory.HOPE to listOf(
            "希望", "期待", "盼望", "渴望", "憧憬", "向往", "期待", "指望", "曙光", "有望",
            "前程似锦", "充满希望", "信心满满", "乐观"
        ),
        EmotionCategory.GRATITUDE to listOf(
            "感谢", "感激", "谢谢", "感恩", "致谢", "谢意", "感谢", "多谢", "衷心感谢",
            "铭记在心", "没齿难忘", "感恩戴德"
        ),
        EmotionCategory.LOVE to listOf(
            "�", "喜欢", "喜爱", "热爱", "心爱", "喜欢", "爱慕", "喜欢", "钟爱", "挚爱",
            "情有独钟", "爱不释手", "一见钟�", "心心相印"
        ),
        EmotionCategory.REGRET to listOf(
            "后悔", "遗憾", "懊悔", "惋惜", "遗憾", "后悔", "悔恨", "自责", "过意不去",
            "愧疚", "对不�", "抱歉", "追悔莫及"
        ),
        EmotionCategory.SHAME to listOf(
            "羞的", "丢脸", "尴尬", "不好意的", "脸红", "惭愧", "不自动", "难为�",
            "无地自容", "颜面尽失", "丢人现眼"
        ),
        EmotionCategory.PRIDE to listOf(
            "自豪", "骄傲", "得意", "自信", "骄傲", "成就", "光荣", "荣誉�",
            "扬眉吐气", "独占鳌头", "实至名归"
        ),
        EmotionCategory.LONELINESS to listOf(
            "孤独", "寂寞", "孤单", "无聊", "空虚", "落寞", "冷清", "凄凉", "孤零�",             "无人问津", "形单影只", "门可罗雀"
        ),
        EmotionCategory.BOREDOM to listOf(
            "无聊", "乏味", "厌的", "腻烦", "没劲", "枯燥", "单调", "无聊", "没意�",
            "百无聊赖", "兴味索然", "味同嚼蜡"
        ),
        EmotionCategory.CURIOSITY to listOf(
            "好奇", "想知", "想知", "为什", "怎么回事", "探索", "研究", "想知�",
            "刨根问底", "打破砂锅", "追根究底"
        )
    )
        private val intensifiers = listOf(
        "非常", "特别", "极其", "十分", "相当", "超级", "�", "�", "�", "�", "�",         "简�", "完全", "彻底", "绝对", "相当", "尤为", "格外", "尤其", "甚至"
    )
        private val diminishers = listOf(
        "有点", "稍微", "略微", "有点", "一", "一", "不太", "不怎么", "略微",
        "稍有", "轻微", "略微", "不太", "有点"
    )
        private val negators = listOf(
        "�", "�", "�", "�", "�", "�", "�", "�", "�", "未曾", "从未",
        "不再", "难以", "无法", "不肯"
    )

    suspend fun analyzeEmotionDetailed(messages: List<ChatMessage>): DetailedEmotionProfile =
        withContext(Dispatchers.IO) {
            AppLogger.d(TAG", "开始深度情感分析，消息数量: ${messages.size}")
        val profile = DetailedEmotionProfile()
        val userMessages = messages.filter { it.sender == "user" }
        if (userMessages.isEmpty()) return@withContext profile
        val emotionScores = calculateEmotionScores(userMessages)
            profile.primaryEmotion = emotionScores.maxByOrNull { it.value }?.key ?: EmotionCategory.NEUTRAL
        val sortedEmotions = emotionScores.entries.sortedByDescending { it.value }
        if (sortedEmotions.size > 1 && sortedEmotions[0].value > 0) {
                profile.secondaryEmotion = sortedEmotions[1].key
            }

            profile.intensityScore = calculateOverallIntensity(userMessages)
            profile.intensity = intensityFromScore(profile.intensityScore)

            profile.emotionDynamics = analyzeDynamics(userMessages)

            profile.emotionalTriggers = identifyTriggers(userMessages)

            profile.emotionalPatterns = detectPatterns(userMessages)

            profile.hiddenSentiments = detectHiddenSentiments(userMessages)

            profile.sarcasmDetected = detectSarcasm(userMessages)

            profile.mixedEmotions = detectMixedEmotions(userMessages)

            profile.confidence = calculateConfidence(emotionScores)

            profile.contextFactors = extractContextFactors(userMessages)

            AppLogger.d(TAG", "深度情感分析完成: ${profile}")
            profile
        }
        private fun calculateEmotionScores(messages: List<ChatMessage>): MutableMap<EmotionCategory, Float> {
        val scores = EmotionCategory.entries.associateWith { 0f }.toMutableMap()
        for (message in messages) {
        val content = message.content
        val hasNegation = negators.any { content.contains(it) }
        val hasIntensifier = intensifiers.any { content.contains(it) }
        val hasDiminisher = diminishers.any { content.contains(it) }
        for ((emotion, keywords) in basicEmotionKeywords) {
        var matchCount = 0
        for (keyword in keywords) {
        if (content.contains(keyword)) {
                        matchCount++
                    }
                }
        if (matchCount > 0) {
        var weight = matchCount.toFloat()
        if (hasNegatorBeforeKeyword(content, keywords)) {
                        weight *= -0.5f
                    }
        if (hasIntensifier) {
                        weight *= 1.5f
                    } else if (hasDiminisher) {
                        weight *= 0.5f
                    }

                    scores[emotion] = scores.getOrDefault(emotion, 0f) + weight
                }
            }
        }

        scores.entries.removeAll { it.value <= 0 }
        return scores
    }
        private fun hasNegatorBeforeKeyword(content: String, keywords: List<String>): Boolean {
        for (keyword in keywords) {
        val index = content.indexOf(keyword)
        if (index > 0) {
        val beforeKeyword = content.substring(0, index)
        if (negators.any { beforeKeyword.endsWith(it) }) {
        return true
                }
            }
        }
        return false
    }
        private fun calculateOverallIntensity(messages: List<ChatMessage>): Float {
        var totalIntensity = 0f
        var count = 0
        for (message in messages) {
        val content = message.content
        var intensity = 0f

            intensity += intensifiers.count { content.contains(it) } * 0.3f

            intensity += content.count { it == '!' } * 0.2f

            intensity += content.count { it == '?' } * 0.1f
        if (content.contains("... ") || content.contains("。。的)) {
                intensity += 0.3f
            }
        if (content.contains("!!") || content.contains("！？")) {
                intensity += 0.4f
            }

            totalIntensity += intensity
            count++
        }
        return if (count > 0) (totalIntensity / count).coerceIn(0f, 1f) else 0f
    }
        private fun intensityFromScore(score: Float): EmotionIntensity {
        return when {
            score < 0.15f -> EmotionIntensity.VERY_LOW
            score < 0.30f -> EmotionIntensity.LOW
            score < 0.45f -> EmotionIntensity.MODERATE
            score < 0.60f -> EmotionIntensity.HIGH
            score < 0.80f -> EmotionIntensity.VERY_HIGH
        else -> EmotionIntensity.EXTREME
        }
    }
        private fun analyzeDynamics(messages: List<ChatMessage>): EmotionDynamics {
        if (messages.size < 5) return EmotionDynamics.STABLE
        val recentMessages = messages.takeLast(5)
        val earlierMessages = messages.take(messages.size / 2)
        val recentScore = calculateOverallIntensity(recentMessages)
        val earlierScore = calculateOverallIntensity(earlierMessages)
        val variance = calculateVariance(messages.map { calculateOverallIntensity(listOf(it)) })
        return when {
            variance > 0.3f -> EmotionDynamics.VOLATILE
            recentScore > earlierScore * 1.3f -> EmotionDynamics.IMPROVING
            recentScore < earlierScore * 0.7f -> EmotionDynamics.DETERIORATING
            detectCyclicalPattern(messages) -> EmotionDynamics.CYCLICAL
        else -> EmotionDynamics.STABLE
        }
    }
        private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }
        private fun detectCyclicalPattern(messages: List<ChatMessage>): Boolean {
        if (messages.size < 10) return false
        val patternLength = messages.size / 3
        if (patternLength < 3) return false
        val first = messages.take(patternLength).map { calculateOverallIntensity(listOf(it)) }
        val second = messages.drop(patternLength).take(patternLength).map { calculateOverallIntensity(listOf(it)) }
        val third = messages.drop(patternLength * 2).map { calculateOverallIntensity(listOf(it)) }
        val correlation = calculateCorrelation(first, second) + calculateCorrelation(second, third)
        return correlation > 0.5f
    }
        private fun calculateCorrelation(list1: List<Float>, list2: List<Float>): Float {
        if (list1.size != list2.size || list1.isEmpty()) return 0f
        val n = list1.size
        val mean1 = list1.average()
        val mean2 = list2.average()
        var numerator = 0.0
        var denom1 = 0.0
        var denom2 = 0.0
        for (i in 0 until n) {
        val diff1 = list1[i] - mean1
        val diff2 = list2[i] - mean2
            numerator += diff1 * diff2
            denom1 += diff1 * diff1
            denom2 += diff2 * diff2
        }
        val denominator = kotlin.math.sqrt(denom1 * denom2)
        return if (denominator > 0) (numerator / denominator).toFloat() else 0f
    }
        private fun identifyTriggers(messages: List<ChatMessage>): List<String> {
        val triggerKeywords = mapOf(
            "工作相关" to listOf("工作", "职场", "业务", "项目", "任务", "老板", "同事", "加班"),
            "学习相关" to listOf("学习", "教育", "知识", "课程", "考试", "作业", "成绩", "学校"),
            "生活相关" to listOf("生活", "日常", "家庭", "朋友", "娱乐", "休息", "消费", "购物"),
            "技术相�"to listOf("技�", "编程", "软件", "硬件", "开�", "代码", "bug", "系统"),
            "感情相关" to listOf("朋友", "家人", "爱人", "关系", "感情", "恋爱", "约会", "分手"),
            "健康相关" to listOf("健康", "身体", "疾病", "医生", "医院", "治疗", "康复", "体检"),
            "财务相关" to listOf("�", "工资", "投资", "理财", "债务", "省钱", "花费", "收入")
        )
        val triggerCounts = mutableMapOf<String, Int>()
        for (message in messages) {
        val content = message.content
        for ((trigger, keywords) in triggerKeywords) {
        for (keyword in keywords) {
        if (content.contains(keyword)) {
                        triggerCounts[trigger] = triggerCounts.getOrDefault(trigger, 0) + 1
                    }
                }
            }
        }
        return triggerCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }
        private fun detectPatterns(messages: List<ChatMessage>): List<EmotionPattern> {
        val patterns = mutableListOf<EmotionPattern>()
        if (messages.size >= 5) {
        val recentEmotions = messages.takeLast(5).map { detectPrimaryEmotion(it.content) }
        val negativeCount = recentEmotions.count {
                it in listOf(EmotionCategory.SADNESS, EmotionCategory.ANGER, EmotionCategory.ANXIETY, EmotionCategory.FEAR)
            }
        if (negativeCount >= 3) {
                patterns.add(
                    EmotionPattern(
                        PatternType.RECURRING_NEGATIVE,
                        "近期频繁出现负面情绪",
                        negativeCount,
                        System.currentTimeMillis()
                    )
                )
            }
        }
        if (messages.size >= 3) {
        val intensities = messages.takeLast(3).map { calculateOverallIntensity(listOf(it)) }
        if (intensities.last() > intensities.first() * 1.5f) {
                patterns.add(
                    EmotionPattern(
                        PatternType.EXCITEMENT_BURST,
                        "情绪强度显著上升",
                        intensities.count { it > intensities.first() },
                        System.currentTimeMillis()
                    )
                )
            }
        }
        return patterns
    }
        private fun detectPrimaryEmotion(content: String): EmotionCategory {
        for ((emotion, keywords) in basicEmotionKeywords) {
        for (keyword in keywords) {
        if (content.contains(keyword)) {
        return emotion
                }
            }
        }
        return EmotionCategory.NEUTRAL
    }
        private fun detectHiddenSentiments(messages: List<ChatMessage>): List<String> {
        val hidden = mutableListOf<String>()
        for (message in messages) {
        val content = message.content
        if (content.contains("随便") || content.contains("都行")) {
                hidden.add("表面无所谓，实际可能有不的）
            }
        if ((content.contains("好吧") || content.contains("行吧")) && !content.contains("�") {
                hidden.add("表面同意，实际不情愿")
            }
        if (content.contains("没事") && !content.contains("有事")) {
                hidden.add("说没事，可能实际有事")
            }
        }
        return hidden.distinct()
    }
        private fun detectSarcasm(messages: List<ChatMessage>): Boolean {
        for (message in messages) {
        val content = message.content
        if ((content.contains("真是") || content.contains("简�") &&
                (content.contains("的） || content.contains("�?)) {
        return true
            }
        if (content.contains("呵呵") && content.length < 20) {
        return true
            }
        }
        return false
    }
        private fun detectMixedEmotions(messages: List<ChatMessage>): Boolean {
        for (message in messages) {
        val content = message.content
        val emotionCount = basicEmotionKeywords.count { (_, keywords) ->
                keywords.any { content.contains(it) }
            }
        if (emotionCount >= 2) {
        return true
            }
        }
        return false
    }
        private fun calculateConfidence(scores: Map<EmotionCategory, Float>): Float {
        if (scores.isEmpty()) return 0f
        val totalScore = scores.values.sum()
        val dominantScore = scores.values.maxOrNull() ?: 0f
        val agreement = if (totalScore > 0) dominantScore / totalScore else 0f
        val messageCoverage = scores.size.toFloat() / EmotionCategory.entries.size
        return (agreement * 0.7f + (1 - messageCoverage) * 0.3f).coerceIn(0f, 1f)
    }
        private fun extractContextFactors(messages: List<ChatMessage>): Map<String, Float> {
        val factors = mutableMapOf<String, Float>()
        val timePatterns = listOf("早上", "上午", "中午", "下午", "晚上", "深夜", "凌晨")
        for (pattern in timePatterns) {
        val count = messages.count { it.content.contains(pattern) }
        if (count > 0) {
                factors["时间-${pattern}"] = count.toFloat() / messages.size
            }
        }
        return factors
    }

    suspend fun generateDetailedReport(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeEmotionDetailed(messages)

        buildString {
            appendLine("=== 深度情感分析报告 ===")
            appendLine()
            appendLine("【主导情绪的${profile.primaryEmotion.displayName}")
            profile.secondaryEmotion?.let { appendLine("【次要情绪的${it.displayName}}") }
            appendLine("【情绪强度。${profile.intensity.description} (${String.format("%.1f", profile.intensityScore * 100)}%)")
            appendLine("【情绪动态的${when (profile.emotionDynamics) {
        EmotionDynamics.STABLE -> "稳定"
        EmotionDynamics.VOLATILE -> "波动"
        EmotionDynamics.IMPROVING -> "改善�"
        EmotionDynamics.DETERIORATING -> "恶化�"
        EmotionDynamics.CYCLICAL -> "周期�"
            }}")
            appendLine()
        if (profile.emotionalTriggers.isNotEmpty()) {
                appendLine("【情绪触发因素的�",
                profile.emotionalTriggers.forEach { appendLine("  - ${it}") }
                appendLine()
            }
        if (profile.emotionalPatterns.isNotEmpty()) {
                appendLine("【情绪模式的�",
                profile.emotionalPatterns.forEach { appendLine("  - ${it.description}") }
                appendLine()
            }
        if (profile.hiddenSentiments.isNotEmpty()) {
                appendLine("【潜在情感的�",                 profile.hiddenSentiments.forEach { appendLine("  - ${it}") }
                appendLine()
            }
            appendLine("【置信度。${.format("%.1f", profile.confidence * 100)}%")
        if (profile.sarcasmDetected) appendLine("【讽刺检测】可能存的）
        if (profile.mixedEmotions) appendLine("【混合情绪】检测到")
        }
    }
}