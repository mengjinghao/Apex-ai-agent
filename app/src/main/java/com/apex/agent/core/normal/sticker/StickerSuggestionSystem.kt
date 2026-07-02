package com.apex.agent.core.normal.sticker

import java.util.concurrent.ConcurrentHashMap

/**
 * F38: 表情包/贴纸建议系统（Sticker Suggestion）
 *
 * 根据对话内容推荐合适的表情/贴纸/GIF：
 * - 情感匹配（开心→😄，难过→😢）
 * - 语境匹配（感谢→🙏，赞同→👍）
 * - 流行梗表情
 * - 自定义表情包库
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不使用表情
 * - 狂暴不关心表达
 * - 本功能让单 Agent **表达更生动、更人性化**
 */

/**
 * 表情类型
 */
enum class StickerType {
    EMOJI,           // 基础 emoji
    EMOTICON,        // 颜文字 (´｡• ω •｡`)
    ASCII_ART,       // ASCII 艺术
    MEME_TEXT,       // 文字梗表情
    KAOMOJI          // 日本颜文字
}

/**
 * 表情定义
 */
data class Sticker(
    val id: String,
    val type: StickerType,
    val content: String,
    val name: String,
    val description: String,
    val emotion: String,           // 关联情感
    val contexts: List<String>,    // 适用场景
    val keywords: List<String>,    // 触发关键词
    val popularity: Float = 0.5f,
    val category: StickerCategory
)

enum class StickerCategory {
    HAPPY, SAD, ANGRY, SURPRISED, LOVE, THINKING,
    GREETING, FAREWELL, THANKS, APOLOGY, AGREE, DISAGREE,
    CELEBRATION, ENCOURAGEMENT, SYMPATHY, SARCASTIC,
    ANIMAL, FOOD, NATURE, OBJECT, ACTIVITY, MEME
}

/**
 * 表情建议
 */
data class StickerSuggestion(
    val sticker: Sticker,
    val reason: String,
    val confidence: Float,
    val position: StickerPosition = StickerPosition.END
)

enum class StickerPosition { START, END, REPLACE, INLINE }

/**
 * 表情包系统
 */
class StickerSuggestionSystem {

    private val stickers = ConcurrentHashMap<String, Sticker>()

    init {
        registerBuiltinStickers()
    }

    /**
     * 添加表情
     */
    fun addSticker(sticker: Sticker) {
        stickers[sticker.id] = sticker
    }

    /**
     * 基于文本推荐表情
     */
    fun suggest(text: String, emotion: String? = null): List<StickerSuggestion> {
        val suggestions = mutableListOf<StickerSuggestion>()
        val textLower = text.lowercase()

        for (sticker in stickers.values) {
            var score = 0f
            var reason = ""

            // 情感匹配
            if (emotion != null && sticker.emotion.equals(emotion, ignoreCase = true)) {
                score += 0.4f
                reason = "情感匹配: $emotion"
            }

            // 关键词匹配
            val matchedKeywords = sticker.keywords.count { textLower.contains(it, ignoreCase = true) }
            if (matchedKeywords > 0) {
                score += matchedKeywords * 0.2f
                reason = if (reason.isEmpty()) "关键词匹配" else "$reason + 关键词"
            }

            // 上下文匹配
            val matchedContext = sticker.contexts.any { ctx ->
                textLower.contains(ctx, ignoreCase = true)
            }
            if (matchedContext) {
                score += 0.2f
                reason = if (reason.isEmpty()) "语境匹配" else "$reason + 语境"
            }

            if (score > 0.3f) {
                suggestions.add(StickerSuggestion(
                    sticker = sticker,
                    reason = reason,
                    confidence = score.coerceAtMost(1f)
                ))
            }
        }

        return suggestions.sortedByDescending { it.confidence }.take(5)
    }

    /**
     * 推荐单一最佳表情
     */
    fun suggestBest(text: String, emotion: String? = null): Sticker? {
        return suggest(text, emotion).firstOrNull()?.sticker
    }

    /**
     * 按类别获取表情
     */
    fun getByCategory(category: StickerCategory): List<Sticker> {
        return stickers.values.filter { it.category == category }.toList()
    }

    /**
     * 搜索表情
     */
    fun search(query: String): List<Sticker> {
        val q = query.lowercase()
        return stickers.values.filter { s ->
            s.name.contains(q, true) ||
            s.description.contains(q, true) ||
            s.keywords.any { it.contains(q, true) }
        }.toList()
    }

    /**
     * 在文本中插入表情
     */
    fun insertSticker(text: String, sticker: Sticker, position: StickerPosition = StickerPosition.END): String {
        return when (position) {
            StickerPosition.START -> "${sticker.content} $text"
            StickerPosition.END -> "$text ${sticker.content}"
            StickerPosition.REPLACE -> sticker.content
            StickerPosition.INLINE -> "$text ${sticker.content}"
        }
    }

    /**
     * 生成表情 prompt 建议
     */
    fun generateStickerPrompt(text: String, emotion: String? = null): String {
        val suggestions = suggest(text, emotion)
        if (suggestions.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[建议表情]")
        suggestions.take(3).forEach { s ->
            sb.append(" ${s.sticker.content}(${s.sticker.name})")
        }
        return sb.toString()
    }

    // ============ 预置表情 ============

    private fun registerBuiltinStickers() {
        // 开心
        stickers["s_happy_1"] = Sticker("s_happy_1", StickerType.EMOJI, "😄", "开心", "笑脸", "happy", listOf("开心", "高兴"), listOf("哈哈", "开心", "好棒"), 0.9f, StickerCategory.HAPPY)
        stickers["s_happy_2"] = Sticker("s_happy_2", StickerType.EMOJI, "🤣", "笑哭", "笑到流泪", "happy", listOf("好笑"), listOf("笑死", "哈哈哈哈", "lmao"), 0.95f, StickerCategory.HAPPY)
        stickers["s_happy_3"] = Sticker("s_happy_3", StickerType.KAOMOJI, "(≧▽≦)", "开心颜文字", "超级开心", "happy", listOf("开心"), listOf("开心", "好棒"), 0.7f, StickerCategory.HAPPY)

        // 难过
        stickers["s_sad_1"] = Sticker("s_sad_1", StickerType.EMOJI, "😢", "难过", "流泪", "sad", listOf("难过", "伤心"), listOf("难过", "伤心", "哭"), 0.8f, StickerCategory.SAD)
        stickers["s_sad_2"] = Sticker("s_sad_2", StickerType.EMOJI, "😭", "大哭", "嚎啕大哭", "sad", listOf("悲伤"), listOf("哭", "太难了"), 0.85f, StickerCategory.SAD)

        // 惊讶
        stickers["s_surprise_1"] = Sticker("s_surprise_1", StickerType.EMOJI, "😱", "惊吓", "震惊", "surprised", listOf("震惊"), listOf("天哪", "不会吧", "卧槽"), 0.9f, StickerCategory.SURPRISED)
        stickers["s_surprise_2"] = Sticker("s_surprise_2", StickerType.EMOJI, "🤯", "脑洞爆炸", "震惊", "surprised", listOf("震惊"), listOf("爆炸", "不可思议"), 0.85f, StickerCategory.SURPRISED)

        // 爱
        stickers["s_love_1"] = Sticker("s_love_1", StickerType.EMOJI, "❤️", "爱心", "表达爱意", "love", listOf("爱"), listOf("爱", "喜欢", "心"), 0.9f, StickerCategory.LOVE)
        stickers["s_love_2"] = Sticker("s_love_2", StickerType.EMOJI, "🥰", "被爱包围", "温暖的爱", "love", listOf("爱"), listOf("可爱", "温暖"), 0.85f, StickerCategory.LOVE)

        // 思考
        stickers["s_think_1"] = Sticker("s_think_1", StickerType.EMOJI, "🤔", "思考", "若有所思", "thinking", listOf("思考"), listOf("想想", "思考", "为什么"), 0.9f, StickerCategory.THINKING)
        stickers["s_think_2"] = Sticker("s_think_2", StickerType.KAOMOJI, "(´・_・`)", "思考颜文字", "疑惑思考", "thinking", listOf("思考"), listOf("嗯", "让我想想"), 0.6f, StickerCategory.THINKING)

        // 问候
        stickers["s_greet_1"] = Sticker("s_greet_1", StickerType.EMOJI, "👋", "挥手", "打招呼", "neutral", listOf("问候"), listOf("你好", "hi", "hello", "嗨"), 0.9f, StickerCategory.GREETING)
        stickers["s_greet_2"] = Sticker("s_greet_2", StickerType.KAOMOJI, "(◍•ᴗ•◍)❤", "可爱问候", "卖萌问候", "happy", listOf("问候"), listOf("你好呀", "嗨"), 0.7f, StickerCategory.GREETING)

        // 感谢
        stickers["s_thanks_1"] = Sticker("s_thanks_1", StickerType.EMOJI, "🙏", "合十", "感谢/祈祷", "grateful", listOf("感谢"), listOf("谢谢", "感谢", "thanks", "多谢"), 0.95f, StickerCategory.THANKS)

        // 赞同
        stickers["s_agree_1"] = Sticker("s_agree_1", StickerType.EMOJI, "👍", "点赞", "赞同", "happy", listOf("赞同"), listOf("对", "好", "没错", "同意"), 0.9f, StickerCategory.AGREE)
        stickers["s_agree_2"] = Sticker("s_agree_2", StickerType.EMOJI, "💯", "百分百", "完全赞同", "happy", listOf("赞同"), listOf("完全", "绝对", "百分百"), 0.85f, StickerCategory.AGREE)

        // 鼓励
        stickers["s_encourage_1"] = Sticker("s_encourage_1", StickerType.EMOJI, "💪", "加油", "力量", "encouraged", listOf("鼓励"), listOf("加油", "你可以", "努力"), 0.9f, StickerCategory.ENCOURAGEMENT)
        stickers["s_encourage_2"] = Sticker("s_encourage_2", StickerType.EMOJI, "🌟", "闪亮", "你很棒", "happy", listOf("鼓励"), listOf("棒", "优秀", "厉害"), 0.8f, StickerCategory.ENCOURAGEMENT)

        // 庆祝
        stickers["s_celebrate_1"] = Sticker("s_celebrate_1", StickerType.EMOJI, "🎉", "撒花", "庆祝", "excited", listOf("庆祝"), listOf("恭喜", "成功", "完成", "耶"), 0.95f, StickerCategory.CELEBRATION)
        stickers["s_celebrate_2"] = Sticker("s_celebrate_2", StickerType.EMOJI, "🎊", "彩花", "庆祝", "excited", listOf("庆祝"), listOf("庆祝", "开心"), 0.85f, StickerCategory.CELEBRATION)

        // 歉意
        stickers["s_sorry_1"] = Sticker("s_sorry_1", StickerType.EMOJI, "😔", "抱歉", "对不起", "sad", listOf("歉意"), listOf("对不起", "抱歉", "sorry"), 0.8f, StickerCategory.APOLOGY)

        // 讽刺
        stickers["s_sarcastic_1"] = Sticker("s_sarcastic_1", StickerType.KAOMOJI, "(¬_¬)", "无语颜文字", "无语/讽刺", "neutral", listOf("讽刺"), listOf("呵呵", "是吗", "真的吗"), 0.6f, StickerCategory.SARCASTIC)

        // 动物
        stickers["s_animal_1"] = Sticker("s_animal_1", StickerType.EMOJI, "🐱", "猫咪", "可爱猫咪", "happy", listOf("可爱"), listOf("猫", "喵", "可爱"), 0.8f, StickerCategory.ANIMAL)
        stickers["s_animal_2"] = Sticker("s_animal_2", StickerType.EMOJI, "🐶", "狗狗", "忠诚狗狗", "happy", listOf("可爱"), listOf("狗", "汪", "可爱"), 0.8f, StickerCategory.ANIMAL)
        stickers["s_animal_3"] = Sticker("s_animal_3", StickerType.EMOJI, "🦊", "狐狸", "聪明狐狸", "happy", listOf("可爱"), listOf("狐狸", "可爱"), 0.7f, StickerCategory.ANIMAL)

        // 食物
        stickers["s_food_1"] = Sticker("s_food_1", StickerType.EMOJI, "☕", "咖啡", "来杯咖啡", "neutral", listOf("休息"), listOf("咖啡", "休息", "下午茶"), 0.7f, StickerCategory.FOOD)
        stickers["s_food_2"] = Sticker("s_food_2", StickerType.EMOJI, "🍜", "面条", "吃面", "neutral", listOf("吃饭"), listOf("面", "吃饭", "饿"), 0.7f, StickerCategory.FOOD)

        // 梗表情
        stickers["s_meme_1"] = Sticker("s_meme_1", StickerType.MEME_TEXT, "[doge]", "doge", "狗头保命", "sarcastic", listOf("玩梗"), listOf("doge", "狗头", "保命"), 0.85f, StickerCategory.MEME)
        stickers["s_meme_2"] = Sticker("s_meme_2", StickerType.MEME_TEXT, "[滑稽]", "滑稽", "滑稽表情", "sarcastic", listOf("玩梗"), listOf("滑稽", "搞笑"), 0.8f, StickerCategory.MEME)

        // ASCII 艺术
        stickers["s_ascii_1"] = Sticker("s_ascii_1", StickerType.ASCII_ART, "¯\\_(ツ)_/¯", "摊手", "无奈摊手", "neutral", listOf("无奈"), listOf("不知道", "随便", "无奈"), 0.7f, StickerCategory.SARCASTIC)
        stickers["s_ascii_2"] = Sticker("s_ascii_2", StickerType.ASCII_ART, "(╯°□°）╯︵ ┻━┻", "掀桌", "掀桌愤怒", "angry", listOf("愤怒"), listOf("气死", "掀桌", "愤怒"), 0.75f, StickerCategory.ANGRY)
        stickers["s_ascii_3"] = Sticker("s_ascii_3", StickerType.ASCII_ART, "┬─┬ ノ( ゜-゜ノ)", "扶桌", "冷静扶桌", "calm", listOf("冷静"), listOf("冷静", "扶桌"), 0.65f, StickerCategory.SARCASTIC)
    }
}
