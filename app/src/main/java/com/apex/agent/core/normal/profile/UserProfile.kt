package com.apex.agent.core.normal.profile

import java.util.concurrent.ConcurrentHashMap

/**
 * F4: 用户偏好画像
 *
 * 长期记录用户偏好（语言风格、技术栈、回答偏好、禁忌话题），
 * 每次对话注入 system prompt，支持用户查看/编辑自己的画像。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 关注 Agent 画像
 * - 狂暴不关注用户
 * - 本功能专注**用户画像**，是单 Agent 个性化的核心
 */

/**
 * 用户偏好画像
 */
data class UserProfile(
    val userId: String,
    val displayName: String = "",

    /** 语言风格偏好 */
    val languageStyle: LanguageStyle = LanguageStyle(),

    /** 技术栈 */
    val techStack: TechStack = TechStack(),

    /** 回答偏好 */
    val responsePreference: ResponsePreference = ResponsePreference(),

    /** 禁忌话题 */
    val tabooTopics: List<String> = emptyList(),

    /** 兴趣领域 */
    val interests: List<String> = emptyList(),

    /** 时区 */
    val timezone: String = "Asia/Shanghai",

    /** 学习到的特征（AI 自动推断） */
    val learnedTraits: Map<String, String> = emptyMap(),

    /** 更新时间 */
    val updatedAt: Long = System.currentTimeMillis(),

    /** 交互次数 */
    val interactionCount: Int = 0
) {
    fun toPromptSnippet(): String {
        val sb = StringBuilder()
        sb.appendLine("[用户画像]")
        if (displayName.isNotBlank()) sb.appendLine("- 称呼: $displayName")
        if (languageStyle.formality != Formality.NEUTRAL) {
            sb.appendLine("- 语言风格: ${languageStyle.formality.desc}")
        }
        if (languageStyle.preferredLanguage.isNotBlank()) {
            sb.appendLine("- 偏好语言: ${languageStyle.preferredLanguage}")
        }
        if (techStack.languages.isNotEmpty()) {
            sb.appendLine("- 技术栈: ${techStack.languages.joinToString()}")
        }
        if (techStack.frameworks.isNotEmpty()) {
            sb.appendLine("- 框架: ${techStack.frameworks.joinToString()}")
        }
        sb.appendLine("- 回答偏好: ${responsePreference.depth} / ${responsePreference.style}")
        if (responsePreference.useEmoji) sb.appendLine("- 允许使用 emoji")
        if (responsePreference.includeExamples) sb.appendLine("- 喜欢包含示例")
        if (tabooTopics.isNotEmpty()) {
            sb.appendLine("- 避免话题: ${tabooTopics.joinToString()}")
        }
        if (interests.isNotEmpty()) {
            sb.appendLine("- 兴趣: ${interests.joinToString()}")
        }
        learnedTraits.forEach { (k, v) ->
            sb.appendLine("- $k: $v")
        }
        return sb.toString()
    }
}

data class LanguageStyle(
    val formality: Formality = Formality.NEUTRAL,
    val preferredLanguage: String = "",  // "中文" / "English" / "中英混合"
    val tone: Tone = Tone.FRIENDLY,
    val verbosity: Verbosity = Verbosity.MODERATE
)

enum class Formality(val desc: String) {
    CASUAL("随意口语"),
    NEUTRAL("中性"),
    FORMAL("正式书面")
}

enum class Tone { FRIENDLY, PROFESSIONAL, HUMOROUS, SERIOUS, ENCOURAGING }
enum class Verbosity { CONCISE, MODERATE, VERBOSE }

data class TechStack(
    val languages: List<String> = emptyList(),
    val frameworks: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val domains: List<String> = emptyList()  // web/mobile/backend/ml/devops
)

data class ResponsePreference(
    val depth: String = "standard",  // brief/standard/detailed/comprehensive
    val style: String = "balanced",  // balanced/technical/conversational/academic
    val useEmoji: Boolean = false,
    val includeExamples: Boolean = true,
    val preferBulletPoints: Boolean = false,
    val codeStyle: CodeStyle = CodeStyle.WITH_COMMENTS
)

enum class CodeStyle {
    MINIMAL,           // 仅代码
    WITH_COMMENTS,     // 带注释
    FULLY_DOCUMENTED   // 详细文档
}

/**
 * 用户画像管理器
 */
class UserProfileManager {

    private val profiles = ConcurrentHashMap<String, UserProfile>()

    /**
     * 获取用户画像
     */
    fun get(userId: String): UserProfile {
        return profiles[userId] ?: UserProfile(userId = userId)
    }

    /**
     * 更新用户画像
     */
    fun update(userId: String, block: (UserProfile) -> UserProfile): UserProfile {
        val current = get(userId)
        val updated = block(current).copy(
            updatedAt = System.currentTimeMillis(),
            interactionCount = current.interactionCount + 1
        )
        profiles[userId] = updated
        return updated
    }

    /**
     * 从用户消息中学习偏好
     */
    fun learnFromMessage(userId: String, userMessage: String, assistantResponse: String) {
        update(userId) { profile ->
            val newLanguages = detectLanguages(userMessage, profile.techStack.languages)
            val newFrameworks = detectFrameworks(userMessage, profile.techStack.frameworks)
            val newTraits = learnTraits(userMessage, profile.learnedTraits)

            profile.copy(
                techStack = profile.techStack.copy(
                    languages = (profile.techStack.languages + newLanguages).distinct().take(10),
                    frameworks = (profile.techStack.frameworks + newFrameworks).distinct().take(10)
                ),
                learnedTraits = newTraits,
                languageStyle = inferLanguageStyle(userMessage, profile.languageStyle)
            )
        }
    }

    /**
     * 显式设置偏好（用户手动配置）
     */
    fun setPreference(userId: String, key: String, value: String) {
        update(userId) { profile ->
            when (key) {
                "display_name" -> profile.copy(displayName = value)
                "language" -> profile.copy(languageStyle = profile.languageStyle.copy(preferredLanguage = value))
                "formality" -> profile.copy(languageStyle = profile.languageStyle.copy(
                    formality = runCatching { Formality.valueOf(value.uppercase()) }.getOrDefault(Formality.NEUTRAL)
                ))
                "depth" -> profile.copy(responsePreference = profile.responsePreference.copy(depth = value))
                "style" -> profile.copy(responsePreference = profile.responsePreference.copy(style = value))
                "emoji" -> profile.copy(responsePreference = profile.responsePreference.copy(useEmoji = value == "true"))
                "examples" -> profile.copy(responsePreference = profile.responsePreference.copy(includeExamples = value == "true"))
                else -> profile.copy(learnedTraits = profile.learnedTraits + (key to value))
            }
        }
    }

    /**
     * 添加禁忌话题
     */
    fun addTabooTopic(userId: String, topic: String) {
        update(userId) { profile ->
            profile.copy(tabooTopics = (profile.tabooTopics + topic).distinct())
        }
    }

    /**
     * 添加兴趣
     */
    fun addInterest(userId: String, interest: String) {
        update(userId) { profile ->
            profile.copy(interests = (profile.interests + interest).distinct())
        }
    }

    /**
     * 生成用户画像 prompt 注入
     */
    fun generatePromptSnippet(userId: String): String {
        val profile = get(userId)
        return if (profile.interactionCount == 0 && profile.displayName.isBlank()) ""
               else profile.toPromptSnippet()
    }

    // ============ 学习方法 ============

    private fun detectLanguages(message: String, known: List<String>): List<String> {
        val patterns = mapOf(
            "Python" to Regex("\\b(python|django|flask|pandas|numpy)\\b", RegexOption.IGNORE_CASE),
            "Kotlin" to Regex("\\b(kotlin|jetpack|compose)\\b", RegexOption.IGNORE_CASE),
            "Java" to Regex("\\b(java|spring|maven|gradle)\\b", RegexOption.IGNORE_CASE),
            "JavaScript" to Regex("\\b(javascript|node|npm|webpack)\\b", RegexOption.IGNORE_CASE),
            "TypeScript" to Regex("\\b(typescript|tsx|deno)\\b", RegexOption.IGNORE_CASE),
            "Rust" to Regex("\\b(rust|cargo)\\b", RegexOption.IGNORE_CASE),
            "Go" to Regex("\\b(golang|go )\\b", RegexOption.IGNORE_CASE),
            "C++" to Regex("\\b(c\\+\\|cpp|qt)\\b", RegexOption.IGNORE_CASE)
        )
        return patterns.filter { (_, regex) -> regex.containsMatchIn(message) }
            .map { it.key }
            .filter { it !in known }
    }

    private fun detectFrameworks(message: String, known: List<String>): List<String> {
        val patterns = mapOf(
            "Android" to Regex("\\b(android|jetpack|compose|room|hilt)\\b", RegexOption.IGNORE_CASE),
            "React" to Regex("\\b(react|jsx|hooks|redux)\\b", RegexOption.IGNORE_CASE),
            "Vue" to Regex("\\b(vue|vuex|nuxt)\\b", RegexOption.IGNORE_CASE),
            "Spring" to Regex("\\b(spring|springboot|spring-boot)\\b", RegexOption.IGNORE_CASE),
            "Docker" to Regex("\\b(docker|dockerfile|container)\\b", RegexOption.IGNORE_CASE),
            "Kubernetes" to Regex("\\b(kubernetes|k8s|kubectl)\\b", RegexOption.IGNORE_CASE)
        )
        return patterns.filter { (_, regex) -> regex.containsMatchIn(message) }
            .map { it.key }
            .filter { it !in known }
    }

    private fun learnTraits(message: String, known: Map<String, String>): Map<String, String> {
        val traits = known.toMutableMap()
        // 检测时间偏好
        if (message.contains("早上|上午|morning", true) && !"morning_person" in traits) {
            // 不自动设置，仅标记
        }
        // 检测代码偏好
        if (message.contains("```") && "prefers_code" !in traits) {
            traits["prefers_code"] = "true"
        }
        return traits
    }

    private fun inferLanguageStyle(message: String, current: LanguageStyle): LanguageStyle {
        var style = current
        // 推断正式度
        if (current.formality == Formality.NEUTRAL) {
            val casualMarkers = listOf("哈", "呀", "呢", "嘛", "lol", "btw", "haha")
            val formalMarkers = listOf("请问", "烦请", "敬请", "恳请", "respectfully")
            style = when {
                casualMarkers.any { message.contains(it, ignoreCase = true) } ->
                    style.copy(formality = Formality.CASUAL)
                formalMarkers.any { message.contains(it, ignoreCase = true) } ->
                    style.copy(formality = Formality.FORMAL)
                else -> style
            }
        }
        // 推断偏好语言
        if (current.preferredLanguage.isBlank()) {
            val hasChinese = message.any { it.code in 0x4e00..0x9fff }
            val hasEnglish = message.any { it in 'a'..'z' || it in 'A'..'Z' }
            style = when {
                hasChinese && hasEnglish -> style.copy(preferredLanguage = "中英混合")
                hasChinese -> style.copy(preferredLanguage = "中文")
                hasEnglish -> style.copy(preferredLanguage = "English")
                else -> style
            }
        }
        return style
    }
}
