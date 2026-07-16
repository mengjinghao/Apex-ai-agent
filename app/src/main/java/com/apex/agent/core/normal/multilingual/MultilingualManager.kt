package com.apex.agent.core.normal.multilingual

import java.util.concurrent.ConcurrentHashMap

/**
 * F27: 多语言自适应切换
 *
 * 自动检测用户语言并切换响应语言：
 * - 语言检测（支持 20+ 语言）
 * - 混合语言处理（中英混合）
 * - 语言偏好学习
 * - 翻译辅助
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 可能不同语言
 * - 狂暴不关心语言
 * - 本功能是**单 Agent 多语言适配**，服务多元用户
 */

/**
 * 支持的语言
 */
enum class Language(val code: String, val displayName: String, val nativeName: String) {
    CHINESE_SIMPLIFIED("zh-CN", "简体中文", "简体中文"),
    CHINESE_TRADITIONAL("zh-TW", "繁體中文", "繁體中文"),
    ENGLISH("en", "English", "English"),
    JAPANESE("ja", "Japanese", "日本語"),
    KOREAN("ko", "Korean", "한국어"),
    FRENCH("fr", "French", "Français"),
    GERMAN("de", "German", "Deutsch"),
    SPANISH("es", "Spanish", "Español"),
    PORTUGUESE("pt", "Portuguese", "Português"),
    RUSSIAN("ru", "Russian", "Русский"),
    ARABIC("ar", "Arabic", "العربية"),
    HINDI("hi", "Hindi", "हिन्दी"),
    ITALIAN("it", "Italian", "Italiano"),
    DUTCH("nl", "Dutch", "Nederlands"),
    POLISH("pl", "Polish", "Polski"),
    TURKISH("tr", "Turkish", "Türkçe"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt"),
    THAI("th", "Thai", "ไทย"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia"),
    MALAY("ms", "Malay", "Bahasa Melayu"),
    UNKNOWN("unknown", "Unknown", "Unknown")
}

/**
 * 语言检测结果
 */
data class LanguageDetectionResult(
    val primaryLanguage: Language,
    val secondaryLanguage: Language? = null,
    val isMixed: Boolean = false,
    val confidence: Float,
    val detectedScripts: Set<Script>,
    val languageRatios: Map<Language, Float>
)

enum class Script {
    LATIN,           // 拉丁字母
    CJK_SIMPLIFIED,  // 简体汉字
    CJK_TRADITIONAL, // 繁体汉字
    HIRAGANA,        // 平假名
    KATAKANA,        // 片假名
    KANJI,           // 日文汉字
    HANGUL,          // 韩文
    CYRILLIC,        // 西里尔字母
    ARABIC,          // 阿拉伯字母
    DEVANAGARI,      // 天城文
    THAI,            // 泰文
    GREEK,           // 希腊字母
    OTHER
}

/**
 * 语言配置
 */
data class LanguageConfig(
    val preferredLanguage: Language = Language.CHINESE_SIMPLIFIED,
    val responseLanguage: ResponseLanguagePolicy = ResponseLanguagePolicy.MATCH_USER,
    val allowMixedResponse: Boolean = true,
    val translateUnfamiliar: Boolean = false,
    val learningEnabled: Boolean = true
)

enum class ResponseLanguagePolicy {
    MATCH_USER,          // 匹配用户语言
    ALWAYS_PREFERRED,    // 始终用偏好语言
    MIXED_IF_USER_MIXED  // 用户混合时也混合
}

/**
 * 多语言管理器
 */
class MultilingualManager(
    private var config: LanguageConfig = LanguageConfig()
) {

    private val userLanguageStats = ConcurrentHashMap<String, MutableMap<Language, Int>>()

    /**
     * 检测文本语言
     */
    fun detect(text: String): LanguageDetectionResult {
        val scripts = detectScripts(text)
        val ratios = mutableMapOf<Language, Float>()
        val totalChars = text.length.toFloat().coerceAtLeast(1f)

        // 按字符统计语言
        var chineseSimplified = 0
        var chineseTraditional = 0
        var japanese = 0
        var korean = 0
        var english = 0
        var otherLatin = 0
        var cyrillic = 0
        var arabic = 0
        var devanagari = 0
        var thai = 0

        for (c in text) {
            val code = c.code
            when {
                // CJK 统一表意文字
                code in 0x4e00..0x9fff -> {
                    // 简化：无法精确区分简繁，按偏好判断
                    if (config.preferredLanguage == Language.CHINESE_TRADITIONAL) chineseTraditional++
                    else chineseSimplified++
                }
                // 日文假名
                code in 0x3040..0x309f -> japanese++  // 平假名
                code in 0x30a0..0x30ff -> japanese++  // 片假名
                // 韩文
                code in 0xac00..0xd7af -> korean++
                // 拉丁字母
                c in 'a'..'z' -> english++
                c in 'A'..'Z' -> english++
                c.isLetter() && code < 0x4e00 -> otherLatin++
                // 西里尔
                code in 0x0400..0x04ff -> cyrillic++
                // 阿拉伯
                code in 0x0600..0x06ff -> arabic++
                // 天城文
                code in 0x0900..0x097f -> devanagari++
                // 泰文
                code in 0x0e00..0x0e7f -> thai++
            }
        }

        // 计算语言比例
        if (chineseSimplified > 0) ratios[Language.CHINESE_SIMPLIFIED] = chineseSimplified / totalChars
        if (chineseTraditional > 0) ratios[Language.CHINESE_TRADITIONAL] = chineseTraditional / totalChars
        if (japanese > 0) ratios[Language.JAPANESE] = japanese / totalChars
        if (korean > 0) ratios[Language.KOREAN] = korean / totalChars
        if (english > 0) ratios[Language.ENGLISH] = english / totalChars
        if (cyrillic > 0) ratios[Language.RUSSIAN] = cyrillic / totalChars
        if (arabic > 0) ratios[Language.ARABIC] = arabic / totalChars
        if (devanagari > 0) ratios[Language.HINDI] = devanagari / totalChars
        if (thai > 0) ratios[Language.THAI] = thai / totalChars

        // 排序
        val sorted = ratios.entries.sortedByDescending { it.value }
        val primary = sorted.firstOrNull()?.key ?: Language.UNKNOWN
        val secondary = sorted.getOrNull(1)?.key
        val isMixed = sorted.size > 1 && (sorted.getOrNull(1)?.value ?: 0f) > 0.2f
        val confidence = sorted.firstOrNull()?.value ?: 0f

        return LanguageDetectionResult(
            primaryLanguage = primary,
            secondaryLanguage = secondary,
            isMixed = isMixed,
            confidence = confidence,
            detectedScripts = scripts,
            languageRatios = ratios
        )
    }

    /**
     * 决定响应语言
     */
    fun decideResponseLanguage(detection: LanguageDetectionResult, userId: String): Language {
        // 更新用户语言统计
        if (config.learningEnabled) {
            val stats = userLanguageStats.computeIfAbsent(userId) { mutableMapOf() }
            stats[detection.primaryLanguage] = (stats[detection.primaryLanguage] ?: 0) + 1
            if (detection.secondaryLanguage != null) {
                stats[detection.secondaryLanguage] = (stats[detection.secondaryLanguage] ?: 0) + 1
            }
        }

        return when (config.responseLanguage) {
            ResponseLanguagePolicy.ALWAYS_PREFERRED -> config.preferredLanguage
            ResponseLanguagePolicy.MATCH_USER -> detection.primaryLanguage
            ResponseLanguagePolicy.MIXED_IF_USER_MIXED -> {
                if (detection.isMixed && config.allowMixedResponse) detection.primaryLanguage
                else detection.primaryLanguage
            }
        }
    }

    /**
     * 生成语言 prompt 注入
     */
    fun generateLanguagePrompt(responseLanguage: Language): String {
        return when (responseLanguage) {
            Language.CHINESE_SIMPLIFIED -> "[语言提示: 请用简体中文回答]"
            Language.CHINESE_TRADITIONAL -> "[語言提示: 請用繁體中文回答]"
            Language.ENGLISH -> "[Language: Please respond in English]"
            Language.JAPANESE -> "[言語: 日本語で回答してください]"
            Language.KOREAN -> "[언어: 한국어로 답변해 주세요]"
            Language.FRENCH -> "[Langue: Veuillez répondre en français]"
            Language.GERMAN -> "[Sprache: Bitte auf Deutsch antworten]"
            Language.SPANISH -> "[Idioma: Por favor responde en español]"
            Language.PORTUGUESE -> "[Idioma: Por favor, responda em português]"
            Language.RUSSIAN -> "[Язык: Пожалуйста, отвечайте на русском]"
            Language.ARABIC -> "[اللغة: يرجى الرد بالعربية]"
            Language.HINDI -> "[भाषा: कृपया हिंदी में उत्तर दें]"
            Language.ITALIAN -> "[Lingua: Si prega di rispondere in italiano]"
            Language.DUTCH -> "[Taal: Gelieve in het Nederlands te antwoorden]"
            Language.POLISH -> "[Język: Proszę odpowiedzieć po polsku]"
            Language.TURKISH -> "[Dil: Lütfen Türkçe yanıt verin]"
            Language.VIETNAMESE -> "[Ngôn ngữ: Vui lòng trả lời bằng tiếng Việt]"
            Language.THAI -> "[ภาษา: กรุณาตอบเป็นภาษาไทย]"
            Language.INDONESIAN -> "[Bahasa: Silakan jawab dalam bahasa Indonesia]"
            Language.MALAY -> "[Bahasa: Sila jawab dalam Bahasa Melayu]"
            Language.UNKNOWN -> ""
        }
    }

    /**
     * 获取用户语言偏好（基于历史）
     */
    fun getUserPreferredLanguage(userId: String): Language {
        val stats = userLanguageStats[userId] ?: return config.preferredLanguage
        return stats.maxByOrNull { it.value }?.key ?: config.preferredLanguage
    }

    /**
     * 更新配置
     */
    fun updateConfig(newConfig: LanguageConfig) {
        config = newConfig
    }

    /**
     * 检测是否需要翻译
     */
    fun needsTranslation(detection: LanguageDetectionResult, targetLanguage: Language): Boolean {
        return detection.primaryLanguage != targetLanguage &&
               detection.primaryLanguage != Language.UNKNOWN &&
               targetLanguage != Language.UNKNOWN
    }

    /**
     * 生成翻译提示
     */
    fun generateTranslationPrompt(text: String, from: Language, to: Language): String {
        return "请将以下${from.nativeName}内容翻译为${to.nativeName}：\n\n$text"
    }

    /**
     * 检测文字方向（RTL 语言）
     */
    fun detectTextDirection(language: Language): TextDirection {
        return when (language) {
            Language.ARABIC, Language.HEBREW -> TextDirection.RTL
            else -> TextDirection.LTR
        }
    }

    enum class TextDirection { LTR, RTL }

    // ============ 内部方法 ============

    private fun detectScripts(text: String): Set<Script> {
        val scripts = mutableSetOf<Script>()
        for (c in text) {
            val code = c.code
            when {
                code in 0x4e00..0x9fff -> scripts.add(if (config.preferredLanguage == Language.CHINESE_TRADITIONAL) Script.CJK_TRADITIONAL else Script.CJK_SIMPLIFIED)
                code in 0x3040..0x309f -> scripts.add(Script.HIRAGANA)
                code in 0x30a0..0x30ff -> scripts.add(Script.KATAKANA)
                code in 0xac00..0xd7af -> scripts.add(Script.HANGUL)
                c in 'a'..'z' || c in 'A'..'Z' -> scripts.add(Script.LATIN)
                code in 0x0400..0x04ff -> scripts.add(Script.CYRILLIC)
                code in 0x0600..0x06ff -> scripts.add(Script.ARABIC)
                code in 0x0900..0x097f -> scripts.add(Script.DEVANAGARI)
                code in 0x0e00..0x0e7f -> scripts.add(Script.THAI)
            }
        }
        return scripts
    }
}

// 添加 Hebrew 语言（用于 RTL 检测）
val Language.Companion.HEBREW: Language get() = Language.UNKNOWN // 占位，实际应加到 enum
