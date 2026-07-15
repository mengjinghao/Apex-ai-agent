package com.apex.agent.core.emotion

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.apex.agent.core.emotion.ConversationStyle

class StyleAdapter(private val context: Context) {

    private val TAG = "StyleAdapter"

    enum class ConversationStyle(
        val displayName: String,
        val formalLevel: Int,
        val verbosity: Verbosity,
        val emotionalLevel: Int
    ) {
        FORMAL(
            "正式",
            5,
            Verbosity.CONCISE,
            1
        ),
        PROFESSIONAL(
            "专业",
            4,
            Verbosity.DETAILED,
            2
        ),
        CASUAL(
            "日常",
            2,
            Verbosity.MODERATE,
            3
        ),
        FRIENDLY(
            "友好",
            1,
            Verbosity.MODERATE,
            4
        ),
        EMPATHETIC(
            "共情",
            1,
            Verbosity.DETAILED,
            5
        ),
        HUMOROUS(
            "幽默",
            1,
            Verbosity.MODERATE,
            4
        ),
        TECHNICAL(
            "技�?,
            5,
            Verbosity.CONCISE,
            1
        ),
        ACADEMIC(
            "学术",
            5,
            Verbosity.DETAILED,
            1
        ),
        CREATIVE(
            "创意",
            2,
            Verbosity.ELABORATE,
            3
        ),
        SUPPORTIVE(
            "支持�?,
            1,
            Verbosity.DETAILED,
            5
        )
    }

    enum class Verbosity(val wordsPerResponse: IntRange) {
        CONCISE(20..50),
        MODERATE(50..150),
        DETAILED(150..300),
        ELABORATE(300..500)
    }

    data class StyleConfiguration(
        val primaryStyle: ConversationStyle,
        val secondaryStyle: ConversationStyle? = null,
        val customModifiers: List<String> = emptyList(),
        val toneAdjustments: Map<String, Float> = emptyMap(),
        val responseLength: Verbosity = Verbosity.MODERATE,
        val includeEncouragement: Boolean = false,
        val includeQuestions: Boolean = true,
        val showEmpathy: Boolean = false
    )

    data class AdaptationResult(
        val suggestedStyle: ConversationStyle,
        val confidence: Float,
        val reasoning: String,
        val samplePhrases: List<String>
    )
        private val styleTransitionPhrases = mapOf(
        ConversationStyle.FORMAL to listOf(
            "根据我们的讨�?, "基于上述分析", "就此问题而言", "综上所�?,
            "从专业角度来�?, "根据您提供的信息"
        ),
        ConversationStyle.PROFESSIONAL to listOf(
            "从技术角度分�?, "根据我的理解", "这个问题涉及", "建议您考虑",
            "基于当前情况", "值得注意的是"
        ),
        ConversationStyle.CASUAL to listOf(
            "话说", "其实", "说起�?, "大概", "可能", "也许",
            "我觉得吧", "你知道吗"
        ),
        ConversationStyle.FRIENDLY to listOf(
            "太好了！", "太棒了！", "加油�?, "别担心！", "你一定能行！",
            "我理解你的感�?, "这很正常"
        ),
        ConversationStyle.EMPATHETIC to listOf(
            "我能理解你的感受", "这确实让人很困扰", "我理解你的难�?,
            "听起来你最近压力很�?, "我完全能感受到你的心�?,
            "这一定很不容�?
        ),
        ConversationStyle.HUMOROUS to listOf(
            "哈哈，这题有意�?, "让我想想怎么解释", "这么说吧",
            "想象一�?, "说起来好笑的�?, "你有没有想过"
        ),
        ConversationStyle.TECHNICAL to listOf(
            "从技术实现角�?, "具体来说", "技术细节如�?, "实现原理�?,
            "核心算法�?, "关键在于", "具体参数�?
        ),
        ConversationStyle.ACADEMIC to listOf(
            "根据相关研究", "学术文献表明", "从理论角度分�?, "研究表明",
            "学术界普遍认�?, "这一观点的理论基础�?
        ),
        ConversationStyle.CREATIVE to listOf(
            "让我换个角度想想", "或许可以这样理解", "打个比方",
            "想象一下如�?, "从另一个视角来�?, "这就�?
        ),
        ConversationStyle.SUPPORTIVE to listOf(
            "我相信你能做�?, "你已经做得很好了", "继续加油",
            "不要放弃", "这是一个好的开�?, "相信你自�?
        )
    )
        private val styleTemplates = mapOf(
        ConversationStyle.FORMAL to listOf(
            "尊敬的先�女士�?,
            "敬启者，",
            "特此通知您，",
            "请您知悉�?
        ),
        ConversationStyle.PROFESSIONAL to listOf(
            "您好�?,
            "感谢您的咨询�?,
            "关于您的问题�?,
            "根据您的情况�?
        ),
        ConversationStyle.CASUAL to listOf(
            "嘿，",
            "嗨，",
            "你好呀�?,
            "Hi�?
        ),
        ConversationStyle.FRIENDLY to listOf(
            "很高兴见到你�?,
            "太开心了�?,
            "欢迎欢迎�?,
            "好久不见�?
        )
    )
        private val emotionToStyleMapping = mapOf(
        EnhancedEmotionAnalyzer.EmotionCategory.SADNESS to ConversationStyle.EMPATHETIC,
        EnhancedEmotionAnalyzer.EmotionCategory.ANXIETY to ConversationStyle.SUPPORTIVE,
        EnhancedEmotionAnalyzer.EmotionCategory.FEAR to ConversationStyle.SUPPORTIVE,
        EnhancedEmotionAnalyzer.EmotionCategory.JOY to ConversationStyle.FRIENDLY,
        EnhancedEmotionAnalyzer.EmotionCategory.HOPE to ConversationStyle.ENCURAGING,
        EnhancedEmotionAnalyzer.EmotionCategory.FRUSTRATION to ConversationStyle.EMPATHETIC,
        EnhancedEmotionAnalyzer.EmotionCategory.CURIOSITY to ConversationStyle.CREATIVE,
        EnhancedEmotionAnalyzer.EmotionCategory.CONFUSION to ConversationStyle.TECHNICAL
    )
        private val contextToStyleMapping = mapOf(
        "编程" to Pair(ConversationStyle.TECHNICAL, 0.9f),
        "代码" to Pair(ConversationStyle.TECHNICAL, 0.9f),
        "开�? to Pair(ConversationStyle.TECHNICAL, 0.8f),
        "学术" to Pair(ConversationStyle.ACADEMIC, 0.9f),
        "研究" to Pair(ConversationStyle.ACADEMIC, 0.8f),
        "论文" to Pair(ConversationStyle.ACADEMIC, 0.9f),
        "工作" to Pair(ConversationStyle.PROFESSIONAL, 0.8f),
        "职场" to Pair(ConversationStyle.PROFESSIONAL, 0.8f),
        "学习" to Pair(ConversationStyle.ACADEMIC, 0.7f),
        "考试" to Pair(ConversationStyle.ACADEMIC, 0.8f),
        "创意" to Pair(ConversationStyle.CREATIVE, 0.9f),
        "写作" to Pair(ConversationStyle.CREATIVE, 0.8f),
        "故事" to Pair(ConversationStyle.CREATIVE, 0.9f),
        "笑话" to Pair(ConversationStyle.HUMOROUS, 0.9f),
        "有趣" to Pair(ConversationStyle.HUMOROUS, 0.8f),
        "安慰" to Pair(ConversationStyle.EMPATHETIC, 0.9f),
        "心情" to Pair(ConversationStyle.EMPATHETIC, 0.9f),
        "感受" to Pair(ConversationStyle.EMPATHETIC, 0.8f)
    )

    suspend fun adaptStyle(
        emotionProfile: EnhancedEmotionAnalyzer.DetailedEmotionProfile,
        userPreferences: UserStylePreferences?,
        context: String
    ): AdaptationResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始风格适配")
        var bestStyle = ConversationStyle.CASUAL
        var confidence = 0.5f
        val reasons = mutableListOf<String>()
        val emotionStyle = emotionToStyleMapping[emotionProfile.primaryEmotion]
        if (emotionStyle != null) {
            val emotionWeight = emotionProfile.confidence
            confidence = emotionWeight
            bestStyle = emotionStyle
            reasons.add("基于当前情绪�?{emotionProfile.primaryEmotion.displayName}�?)
        }
        if (userPreferences != null) {
            val preferenceWeight = userPreferences.preferredStyle
            if (preferenceWeight > confidence) {
                bestStyle = userPreferences.favoriteStyle
                confidence = preferenceWeight
                reasons.add("基于用户偏好")
            }
        }
        val contextStyle = findContextStyle(context)
        if (contextStyle != null) {
            val (style, weight) = contextStyle
            if (weight > confidence * 0.8f) {
                bestStyle = style
                confidence = weight
                reasons.add("基于使用场景")
            }
        }
        if (emotionProfile.intensityScore > 0.7f &&
            bestStyle !in listOf(ConversationStyle.EMPATHETIC, ConversationStyle.SUPPORTIVE)) {
            bestStyle = ConversationStyle.EMPATHETIC
            reasons.add("情绪强度较高，调整为共情模式")
        }
        if (emotionProfile.emotionDynamics == EnhancedEmotionAnalyzer.EmotionDynamics.IMPROVING) {
            reasons.add("情绪趋势向好")
        }
        val samplePhrases = styleTransitionPhrases[bestStyle] ?: emptyList()
        val reasoning = reasons.joinToString("�?)

        AppLogger.d(TAG, "风格适配完成: ${bestStyle} (置信�? ${confidence})")

        AdaptationResult(
            suggestedStyle = bestStyle,
            confidence = confidence,
            reasoning = reasoning,
            samplePhrases = samplePhrases
        )
    }
        private fun findContextStyle(context: String): Pair<ConversationStyle, Float>? {
        for ((keyword, stylePair) in contextToStyleMapping) {
            if (context.contains(keyword)) {
                return stylePair
            }
        }
        return null
    }

    suspend fun generateStyledResponse(
        content: String,
        style: ConversationStyle,
        emotionProfile: EnhancedEmotionAnalyzer.DetailedEmotionProfile?
    ): String = withContext(Dispatchers.IO) {
        val prefix = styleTemplates[style]?.randomOrNull() ?: ""
        val transition = styleTransitionPhrases[style]?.randomOrNull() ?: ""
        var styledContent = content

        if (emotionProfile != null && emotionProfile.showEmpathy) {
            val empathyPhrase = styleTransitionPhrases[ConversationStyle.EMPATHETIC]?.randomOrNull() ?: ""
            styledContent = "${empathyPhrase}${styledContent}"
        }
        when (style) {
            ConversationStyle.FORMAL -> {
                styledContent = styledContent
                    .replace("�?, "�?)
                    .replace("我觉�?, "我认�?)
            }
            ConversationStyle.CASUAL -> {
                styledContent = styledContent
                    .replace("我认�?, "我觉�?)
                    .replace("因此", "所�?)
            }
            else -> {}
        }

        buildString {
            if (prefix.isNotEmpty()) append(prefix)
            append(styledContent)
        }
    }
        fun buildStylePrompt(
        style: ConversationStyle,
        includeSystemPrompt: Boolean = true
    ): String {
        val styleDescriptions = mapOf(
            ConversationStyle.FORMAL to "请使用正式、礼貌的语言风格，避免口语化表达�?,
            ConversationStyle.PROFESSIONAL to "请使用专业但易懂的表达方式，保持专业态度�?,
            ConversationStyle.CASUAL to "请使用轻松、自然的口吻，像和朋友聊天一样�?,
            ConversationStyle.FRIENDLY to "请使用热情、友好的语言，多使用鼓励性表达�?,
            ConversationStyle.EMPATHETIC to "请充分理解用户情感，表达共情和理解，提供情感支持�?,
            ConversationStyle.HUMOROUS to "请在保持有用性的同时，加入适当的幽默元素�?,
            ConversationStyle.TECHNICAL to "请使用精确的技术语言，提供详细的技术解释�?,
            ConversationStyle.ACADEMIC to "请使用学术化的语言，引用相关理论和研究�?,
            ConversationStyle.CREATIVE to "请使用富有想象力的表达，提供创意性的建议�?,
            ConversationStyle.SUPPORTIVE to "请多给予鼓励和支持，帮助用户建立信心�?
        )
        return if (includeSystemPrompt) {
            styleDescriptions[style] ?: ""
        } else {
            "�?{style.displayName}的方式回�?
        }
    }
        fun detectStyleFromContent(content: String): ConversationStyle {
        val lowerContent = content.lowercase()
        val formalIndicators = listOf("�?, "�?, "感谢", "尊敬", "特此", "�?)
        val casualIndicators = listOf("�?, "�?, "呀", "�?, "�?, "�?)
        val techIndicators = listOf("代码", "函数", "算法", "api", "接口", "实现")
        val formalCount = formalIndicators.count { lowerContent.contains(it) }
        val casualCount = casualIndicators.count { lowerContent.contains(it) }
        val techCount = techIndicators.count { lowerContent.contains(it) }
        return when {
            techCount > formalCount && techCount > casualCount -> ConversationStyle.TECHNICAL
            formalCount > casualCount -> ConversationStyle.FORMAL
            casualCount > formalCount -> ConversationStyle.CASUAL
            else -> ConversationStyle.CASUAL
        }
    }

    data class UserStylePreferences(
        val favoriteStyle: ConversationStyle = ConversationStyle.CASUAL,
        val preferredStyle: Float = 0.5f,
        val styleHistory: List<ConversationStyle> = emptyList(),
        val styleChangeCount: Int = 0,
        val lastUpdated: Long = System.currentTimeMillis()
    ) {
        fun recordStyleUsage(style: ConversationStyle): UserStylePreferences {
            val newHistory = (styleHistory + style).takeLast(10)
        val newChangeCount = if (newHistory.size >= 2 && newHistory.last() != newHistory[newHistory.size - 2]) {
                styleChangeCount + 1
            } else {
                styleChangeCount
            }
        val newFavoriteStyle = newHistory.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: favoriteStyle

            return UserStylePreferences(
                favoriteStyle = newFavoriteStyle,
                preferredStyle = (preferredStyle + 0.1f).coerceAtMost(1f),
                styleHistory = newHistory,
                styleChangeCount = newChangeCount,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }
}

private val ConversationStyle.ENCURAGING: ConversationStyle
    get() = ConversationStyle.SUPPORTIVE