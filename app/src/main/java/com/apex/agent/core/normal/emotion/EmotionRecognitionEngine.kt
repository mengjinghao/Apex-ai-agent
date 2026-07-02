package com.apex.agent.core.normal.emotion

import java.util.concurrent.ConcurrentHashMap

/**
 * F20: 情感识别与共情响应
 *
 * 识别用户情感状态，调整 AI 响应风格：
 * - 情感识别（积极/消极/中性 + 8 种细分情感）
 * - 情感强度评估
 * - 共情响应策略
 * - 情感追踪（对话过程中的情感变化）
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不关注用户情感
 * - 狂暴不关心情感
 * - 本功能是**单 Agent 共情能力**的核心，让对话有温度
 */

/**
 * 情感类型
 */
enum class Emotion {
    // 积极
    HAPPY,        // 开心
    EXCITED,      // 兴奋
    GRATEFUL,     // 感激
    CURIOUS,      // 好奇
    HOPEFUL,      // 充满希望

    // 中性
    NEUTRAL,      // 中性
    FOCUSED,      // 专注
    CONFUSED,     // 困惑

    // 消极
    SAD,          // 悲伤
    ANGRY,        // 愤怒
    FRUSTRATED,   // 挫败
    ANXIOUS,      // 焦虑
    DISAPPOINTED, // 失望
    TIRED,        // 疲惫
    LONELY        // 孤独
}

/**
 * 情感维度（PAD 模型）
 */
data class EmotionDimension(
    val pleasure: Float,   // 愉悦度 -1..1
    val arousal: Float,    // 唤醒度 -1..1
    val dominance: Float   // 支配度 -1..1
)

/**
 * 情感分析结果
 */
data class EmotionAnalysis(
    val primaryEmotion: Emotion,
    val secondaryEmotion: Emotion? = null,
    val confidence: Float,
    val intensity: Float,         // 0..1
    val dimensions: EmotionDimension,
    val detectedCues: List<EmotionCue>,
    val suggestedResponseTone: ResponseTone
)

/**
 * 情感线索
 */
data class EmotionCue(
    val type: CueType,
    val text: String,
    val weight: Float
)

enum class CueType {
    KEYWORD,          // 关键词
    PUNCTUATION,      // 标点
    EMOJI,            // 表情符号
    CAPITALIZATION,   // 大写
    REPETITION,       // 重复
    NEGATION,         // 否定
    INTENSIFIER       // 强度词
}

/**
 * 响应语气
 */
data class ResponseTone(
    val warmth: Float,        // 温暖度 0..1
    val formality: Float,     // 正式度 0..1
    val empathy: Float,       // 共情度 0..1
    val encouragement: Float, // 鼓励度 0..1
    val humor: Float,         // 幽默度 0..1
    val directness: Float     // 直接度 0..1
) {
    fun toPromptSnippet(): String {
        val sb = StringBuilder()
        sb.append("[响应语气调整]")
        if (warmth > 0.7f) sb.append(" 温暖亲切")
        if (formality > 0.7f) sb.append(" 正式规范")
        if (empathy > 0.7f) sb.append(" 共情理解")
        if (encouragement > 0.7f) sb.append(" 鼓励支持")
        if (humor > 0.5f) sb.append(" 适度幽默")
        if (directness > 0.7f) sb.append(" 直截了当")
        return if (sb.length > 10) sb.toString() else ""
    }
}

/**
 * 情感追踪记录
 */
data class EmotionTrack(
    val chatId: String,
    val timeline: List<EmotionTrackEntry>,
    val averageEmotion: Emotion,
    val emotionTrend: EmotionTrend,
    val dominantEmotion: Emotion
)

data class EmotionTrackEntry(
    val timestamp: Long,
    val emotion: Emotion,
    val intensity: Float,
    val messageId: String?
)

enum class EmotionTrend {
    IMPROVING,      // 情绪变好
    STABLE,         // 稳定
    DECLINING,      // 情绪变差
    VOLATILE,       // 波动大
    UNKNOWN
}

/**
 * 情感识别引擎
 */
class EmotionRecognitionEngine {

    private val tracks = ConcurrentHashMap<String, MutableList<EmotionTrackEntry>>()

    // 情感关键词词典
    private val emotionKeywords = mapOf(
        Emotion.HAPPY to listOf("开心", "高兴", "快乐", "棒", "好", "喜欢", "happy", "great", "awesome", "love", "nice"),
        Emotion.EXCITED to listOf("兴奋", "激动", "期待", "wow", "excited", "amazing", "fantastic", "太棒了"),
        Emotion.GRATEFUL to listOf("谢谢", "感谢", "感激", "thanks", "thank you", "appreciate", "grateful"),
        Emotion.CURIOUS to listOf("好奇", "想知道", "为什么", "如何", "curious", "wonder", "interesting"),
        Emotion.HOPEFUL to listOf("希望", "期待", "相信", "hope", "wish", "believe", "looking forward"),
        Emotion.SAD to listOf("难过", "伤心", "悲伤", "哭", "sad", "cry", "depressed", "unhappy", "心痛"),
        Emotion.ANGRY to listOf("生气", "愤怒", "气", "怒", "angry", "mad", "furious", "pissed", "烦"),
        Emotion.FRUSTRATED to listOf("挫败", "无奈", "放弃", "frustrated", "annoyed", "stuck", "不行"),
        Emotion.ANXIOUS to listOf("焦虑", "紧张", "担心", "害怕", "anxious", "nervous", "worried", "scared", "怕"),
        Emotion.DISAPPOINTED to listOf("失望", "遗憾", "disappointed", "unfortunately", "可惜"),
        Emotion.TIRED to listOf("累", "疲惫", "困", "tired", "exhausted", "sleepy", "倦"),
        Emotion.LONELY to listOf("孤独", "寂寞", "alone", "lonely", "isolated"),
        Emotion.CONFUSED to listOf("困惑", "不明白", "不懂", "confused", "don't understand", "lost")
    )

    // 强度词
    private val intensifiers = mapOf(
        "非常" to 1.5f, "特别" to 1.5f, "超级" to 1.8f, "极其" to 2.0f,
        "很" to 1.3f, "挺" to 1.2f, "比较" to 1.1f,
        "very" to 1.5f, "extremely" to 2.0f, "really" to 1.4f, "so" to 1.3f
    )

    // 表情符号映射
    private val emojiEmotions = mapOf(
        "😀" to Emotion.HAPPY, "😄" to Emotion.HAPPY, "😊" to Emotion.HAPPY, "🙂" to Emotion.HAPPY,
        "😍" to Emotion.HAPPY, "🥰" to Emotion.HAPPY, "😎" to Emotion.HAPPY,
        "🎉" to Emotion.EXCITED, "🤩" to Emotion.EXCITED, "🎊" to Emotion.EXCITED,
        "🙏" to Emotion.GRATEFUL, "💛" to Emotion.GRATEFUL,
        "🤔" to Emotion.CURIOUS, "❓" to Emotion.CURIOUS,
        "😢" to Emotion.SAD, "😭" to Emotion.SAD, "💔" to Emotion.SAD, "😔" to Emotion.SAD,
        "😠" to Emotion.ANGRY, "😡" to Emotion.ANGRY, "🤬" to Emotion.ANGRY,
        "😰" to Emotion.ANXIOUS, "😨" to Emotion.ANXIOUS, "😱" to Emotion.ANXIOUS,
        "😩" to Emotion.FRUSTRATED, "😤" to Emotion.FRUSTRATED,
        "😞" to Emotion.DISAPPOINTED,
        "😴" to Emotion.TIRED, "🥱" to Emotion.TIRED,
        "😕" to Emotion.CONFUSED, "🤷" to Emotion.CONFUSED
    )

    /**
     * 分析文本情感
     */
    fun analyze(text: String, chatId: String? = null, messageId: String? = null): EmotionAnalysis {
        val cues = mutableListOf<EmotionCue>()

        // 1. 关键词匹配
        val emotionScores = mutableMapOf<Emotion, Float>()
        val textLower = text.lowercase()

        for ((emotion, keywords) in emotionKeywords) {
            for (kw in keywords) {
                if (textLower.contains(kw, ignoreCase = true)) {
                    val weight = 1.0f
                    emotionScores[emotion] = (emotionScores[emotion] ?: 0f) + weight
                    cues.add(EmotionCue(CueType.KEYWORD, kw, weight))
                }
            }
        }

        // 2. 表情符号
        for ((emoji, emotion) in emojiEmotions) {
            if (text.contains(emoji)) {
                emotionScores[emotion] = (emotionScores[emotion] ?: 0f) + 1.5f
                cues.add(EmotionCue(CueType.EMOJI, emoji, 1.5f))
            }
        }

        // 3. 标点符号（感叹号=兴奋/愤怒，问号=困惑）
        val exclamationCount = text.count { it == '!' || it == '！' }
        if (exclamationCount >= 2) {
            // 根据上下文判断是兴奋还是愤怒
            val targetEmotion = if (emotionScores[Emotion.ANGRY] ?: 0f > 0) Emotion.ANGRY else Emotion.EXCITED
            emotionScores[targetEmotion] = (emotionScores[targetEmotion] ?: 0f) + exclamationCount * 0.3f
            cues.add(EmotionCue(CueType.PUNCTUATION, "!".repeat(exclamationCount), exclamationCount * 0.3f))
        }

        val questionCount = text.count { it == '?' || it == '？' }
        if (questionCount >= 2) {
            emotionScores[Emotion.CONFUSED] = (emotionScores[Emotion.CONFUSED] ?: 0f) + questionCount * 0.2f
            cues.add(EmotionCue(CueType.PUNCTUATION, "?".repeat(questionCount), questionCount * 0.2f))
        }

        // 4. 大写（英文）
        val upperCaseRatio = if (text.any { it.isLetter() }) {
            text.count { it.isUpperCase() && it.isLetter() }.toFloat() / text.count { it.isLetter() }
        } else 0f
        if (upperCaseRatio > 0.5f && text.length > 5) {
            emotionScores[Emotion.ANGRY] = (emotionScores[Emotion.ANGRY] ?: 0f) + 0.5f
            cues.add(EmotionCue(CueType.CAPITALIZATION, "UPPERCASE", 0.5f))
        }

        // 5. 重复字符（soooo, ！！！）
        val repetitionPattern = Regex("(.)\\1{2,}")
        repetitionPattern.findAll(text).forEach { match ->
            val emotion = if (match.value.first() == '!' || match.value.first() == '！') Emotion.EXCITED else Emotion.HAPPY
            emotionScores[emotion] = (emotionScores[emotion] ?: 0f) + 0.3f
            cues.add(EmotionCue(CueType.REPETITION, match.value, 0.3f))
        }

        // 6. 强度词
        var intensityMultiplier = 1.0f
        for ((intensifier, multiplier) in intensifiers) {
            if (textLower.contains(intensifier)) {
                intensityMultiplier *= multiplier
                cues.add(EmotionCue(CueType.INTENSIFIER, intensifier, multiplier - 1f))
            }
        }

        // 选择主要情感
        val sorted = emotionScores.entries.sortedByDescending { it.value }
        val primaryEmotion = sorted.firstOrNull()?.key ?: Emotion.NEUTRAL
        val secondaryEmotion = sorted.getOrNull(1)?.key
        val confidence = if (sorted.isNotEmpty()) {
            sorted[0].value / (sorted.sumOf { it.value } + 0.001f)
        } else 0f
        val intensity = (sorted.firstOrNull()?.value ?: 0f) * intensityMultiplier
        val dimensions = computeDimensions(primaryEmotion, intensity)

        // 生成响应语气建议
        val responseTone = suggestResponseTone(primaryEmotion, intensity)

        // 追踪
        if (chatId != null) {
            val entry = EmotionTrackEntry(System.currentTimeMillis(), primaryEmotion, intensity.coerceIn(0f, 1f), messageId)
            tracks.computeIfAbsent(chatId) { mutableListOf() }.add(entry)
        }

        return EmotionAnalysis(
            primaryEmotion = primaryEmotion,
            secondaryEmotion = secondaryEmotion,
            confidence = confidence,
            intensity = intensity.coerceIn(0f, 1f),
            dimensions = dimensions,
            detectedCues = cues,
            suggestedResponseTone = responseTone
        )
    }

    /**
     * 获取情感追踪
     */
    fun getEmotionTrack(chatId: String): EmotionTrack {
        val timeline = tracks[chatId]?.toList() ?: emptyList()
        if (timeline.isEmpty()) {
            return EmotionTrack(chatId, emptyList(), Emotion.NEUTRAL, EmotionTrend.UNKNOWN, Emotion.NEUTRAL)
        }

        val emotionCounts = timeline.groupingBy { it.emotion }.eachCount()
        val dominantEmotion = emotionCounts.maxByOrNull { it.value }!!.key
        val averageDimensions = timeline.map { computeDimensions(it.emotion, it.intensity) }
            .let { dims ->
                EmotionDimension(
                    pleasure = dims.map { it.pleasure }.average().toFloat(),
                    arousal = dims.map { it.arousal }.average().toFloat(),
                    dominance = dims.map { it.dominance }.average().toFloat()
                )
            }
        val averageEmotion = dimensionToEmotion(averageDimensions)

        val trend = computeTrend(timeline)

        return EmotionTrack(chatId, timeline, averageEmotion, trend, dominantEmotion)
    }

    /**
     * 生成共情响应 prompt
     */
    fun generateEmpathyPrompt(analysis: EmotionAnalysis): String {
        val sb = StringBuilder()
        sb.append("[情感感知]")
        sb.append(" 用户当前情感: ${analysis.primaryEmotion}")
        if (analysis.secondaryEmotion != null) sb.append(" + ${analysis.secondaryEmotion}")
        sb.append(" (强度 ${(analysis.intensity * 100).toInt()}%, 置信度 ${(analysis.confidence * 100).toInt()}%)")

        val toneSnippet = analysis.suggestedResponseTone.toPromptSnippet()
        if (toneSnippet.isNotBlank()) sb.append("\n").append(toneSnippet)

        // 特殊共情指导
        when (analysis.primaryEmotion) {
            Emotion.SAD -> sb.append("\n[共情指导] 用户情绪低落，先表达理解与陪伴，再提供帮助")
            Emotion.ANGRY, Emotion.FRUSTRATED -> sb.append("\n[共情指导] 用户情绪激动，先认同感受，避免说教，提供解决方案")
            Emotion.ANXIOUS -> sb.append("\n[共情指导] 用户焦虑，给予安抚与确定性信息，分步骤说明")
            Emotion.CONFUSED -> sb.append("\n[共情指导] 用户困惑，耐心解释，多用类比，确认理解")
            Emotion.HAPPY, Emotion.EXCITED -> sb.append("\n[共情指导] 用户情绪积极，可以匹配热情，适度庆祝")
            Emotion.GRATEFUL -> sb.append("\n[共情指导] 用户表达感谢，谦逊回应")
            Emotion.TIRED -> sb.append("\n[共情指导] 用户疲惫，回答简洁直接，避免冗长")
            Emotion.LONELY -> sb.append("\n[共情指导] 用户孤独感，温暖陪伴，主动关心")
            else -> {}
        }

        return sb.toString()
    }

    // ============ 内部方法 ============

    private fun computeDimensions(emotion: Emotion, intensity: Float): EmotionDimension {
        val base = when (emotion) {
            Emotion.HAPPY -> Triple(0.8f, 0.3f, 0.5f)
            Emotion.EXCITED -> Triple(0.7f, 0.8f, 0.4f)
            Emotion.GRATEFUL -> Triple(0.7f, 0.2f, 0.3f)
            Emotion.CURIOUS -> Triple(0.3f, 0.4f, 0.3f)
            Emotion.HOPEFUL -> Triple(0.5f, 0.3f, 0.4f)
            Emotion.NEUTRAL -> Triple(0f, 0f, 0f)
            Emotion.FOCUSED -> Triple(0.1f, 0.2f, 0.5f)
            Emotion.CONFUSED -> Triple(-0.2f, 0.3f, -0.3f)
            Emotion.SAD -> Triple(-0.7f, -0.3f, -0.5f)
            Emotion.ANGRY -> Triple(-0.7f, 0.8f, 0.6f)
            Emotion.FRUSTRATED -> Triple(-0.5f, 0.5f, -0.3f)
            Emotion.ANXIOUS -> Triple(-0.6f, 0.6f, -0.6f)
            Emotion.DISAPPOINTED -> Triple(-0.5f, -0.2f, -0.4f)
            Emotion.TIRED -> Triple(-0.3f, -0.6f, -0.2f)
            Emotion.LONELY -> Triple(-0.5f, -0.4f, -0.5f)
        }
        return EmotionDimension(
            pleasure = base.first * intensity,
            arousal = base.second * intensity,
            dominance = base.third * intensity
        )
    }

    private fun dimensionToEmotion(dim: EmotionDimension): Emotion {
        return when {
            dim.pleasure > 0.5f && dim.arousal > 0.5f -> Emotion.EXCITED
            dim.pleasure > 0.5f -> Emotion.HAPPY
            dim.pleasure < -0.5f && dim.arousal > 0.5f -> Emotion.ANGRY
            dim.pleasure < -0.5f && dim.arousal < -0.2f -> Emotion.SAD
            dim.pleasure < -0.5f -> Emotion.ANXIOUS
            dim.arousal < -0.4f -> Emotion.TIRED
            else -> Emotion.NEUTRAL
        }
    }

    private fun suggestResponseTone(emotion: Emotion, intensity: Float): ResponseTone {
        return when (emotion) {
            Emotion.HAPPY, Emotion.EXCITED -> ResponseTone(
                warmth = 0.9f, formality = 0.3f, empathy = 0.6f,
                encouragement = 0.7f, humor = 0.6f, directness = 0.5f
            )
            Emotion.GRATEFUL -> ResponseTone(
                warmth = 0.8f, formality = 0.4f, empathy = 0.6f,
                encouragement = 0.4f, humor = 0.3f, directness = 0.6f
            )
            Emotion.CURIOUS -> ResponseTone(
                warmth = 0.6f, formality = 0.5f, empathy = 0.4f,
                encouragement = 0.7f, humor = 0.4f, directness = 0.7f
            )
            Emotion.SAD -> ResponseTone(
                warmth = 0.95f, formality = 0.3f, empathy = 0.9f,
                encouragement = 0.7f, humor = 0.1f, directness = 0.4f
            )
            Emotion.ANGRY, Emotion.FRUSTRATED -> ResponseTone(
                warmth = 0.7f, formality = 0.5f, empathy = 0.85f,
                encouragement = 0.5f, humor = 0.1f, directness = 0.8f
            )
            Emotion.ANXIOUS -> ResponseTone(
                warmth = 0.85f, formality = 0.4f, empathy = 0.9f,
                encouragement = 0.6f, humor = 0.2f, directness = 0.7f
            )
            Emotion.CONFUSED -> ResponseTone(
                warmth = 0.7f, formality = 0.4f, empathy = 0.7f,
                encouragement = 0.7f, humor = 0.3f, directness = 0.6f
            )
            Emotion.TIRED -> ResponseTone(
                warmth = 0.8f, formality = 0.3f, empathy = 0.7f,
                encouragement = 0.5f, humor = 0.3f, directness = 0.9f
            )
            Emotion.LONELY -> ResponseTone(
                warmth = 0.95f, formality = 0.3f, empathy = 0.9f,
                encouragement = 0.6f, humor = 0.2f, directness = 0.5f
            )
            else -> ResponseTone(
                warmth = 0.6f, formality = 0.5f, empathy = 0.5f,
                encouragement = 0.5f, humor = 0.4f, directness = 0.6f
            )
        }
    }

    private fun computeTrend(timeline: List<EmotionTrackEntry>): EmotionTrend {
        if (timeline.size < 2) return EmotionTrend.UNKNOWN
        val recent = timeline.takeLast(5)
        val pleasureValues = recent.map { computeDimensions(it.emotion, it.intensity).pleasure }

        // 简单线性趋势
        val firstHalf = pleasureValues.take(pleasureValues.size / 2).average()
        val secondHalf = pleasureValues.drop(pleasureValues.size / 2).average()
        val diff = secondHalf - firstHalf

        // 波动性
        val variance = pleasureValues.map { (it - pleasureValues.average()).let { d -> d * d } }.average()

        return when {
            variance > 0.3 -> EmotionTrend.VOLATILE
            diff > 0.2 -> EmotionTrend.IMPROVING
            diff < -0.2 -> EmotionTrend.DECLINING
            else -> EmotionTrend.STABLE
        }
    }

    /**
     * 重置追踪
     */
    fun resetTrack(chatId: String) {
        tracks.remove(chatId)
    }
}
