package com.apex.util

data class MessageImportanceScore(
    val score: Float,
    val reasons: List<String>,
    val hasKeyInformation: Boolean
)

class SemanticImportanceScorer {

    companion object {
        private val KEY_NUMBER_PATTERN = Regex("\\b\\d{4,}\\b")
        private val DATE_PATTERN = Regex("\\b(\\d{1,4}[-/年]\\d{1,2}[-/月]\\d{1,2}[日])|(\\d{1,2}[:：]\\d{1,2})\\b")
        private val TASK_ID_PATTERN = Regex("\\b(TASK|task|任务|ID|id)[_:-]?\\d+\\b", RegexOption.IGNORE_CASE)
        private val CODE_BLOCK_PATTERN = Regex("```[\\s\\S]*?```|`[^`]+`")
        private val URL_PATTERN = Regex("https?://\\S+")
        private val EMAIL_PATTERN = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
        private val COMMAND_PATTERN = Regex("\\b(sudo|adb|pm|am|getprop|setprop|exec|run|start|stop)\\b")
        private val ERROR_KEYWORD_PATTERN = Regex("错误|error|异常", RegexOption.IGNORE_CASE)
        private val WARNING_KEYWORD_PATTERN = Regex("警告|warning|注意", RegexOption.IGNORE_CASE)
        private val COMPLETION_KEYWORD_PATTERN = Regex("完成|done|success", RegexOption.IGNORE_CASE)
        private val HIGH_VALUE_KEYWORDS = listOf(
            "修复", "bug", "错误", "失败", "问题", "issue", "fix", "error", "crash",
            "任务", "完成", "进度", "状态", "task", "progress", "status", "done",
            "重要", "必须", "需要", "关键", "紧急", "critical", "important", "must",
            "代码", "函数", "方法", "类", "变量", "接口", "code", "function", "class",
            "测试", "验证", "通过", "结果", "test", "verify", "result",
            "配置", "设置", "环境", "config", "setting", "environment",
            "文件", "路径", "目录", "file", "path", "directory",
            "用户", "登录", "权限", "user", "login", "permission",
            "数据", "数据库", "同步", "data", "database", "sync",
            "网络", "请求", "API", "network", "request", "api",
            "更新", "升级", "版本", "update", "upgrade", "version"
        )
        private val LOW_VALUE_PATTERNS = listOf(
            "好的", "收到", "了解", "明白", "OK", "ok", "好的好的", "没问题",
            "好的呀", "了解了解", "嗯嗯", "对对", "是的是的",
            "哈哈", "哈哈哈", "笑死", "哈哈哈哈",
            "你好", "您好", "嗨", "hi", "hello", "hey",
            "请问", "打扰", "麻烦", "谢谢", "感谢", "sorry", "apologies",
            "随便", "无所谓", "都可以", "没关系", "not important", "whatever",
            "继续", "然后呢", "还有吗", "然后", "接下来", "continue", "next"
        )
        private val HIGH_VALUE_REGEX = HIGH_VALUE_KEYWORDS.joinToString("|") { Regex.escape(it) }.let { Regex(it, RegexOption.IGNORE_CASE) }
        private val LOW_VALUE_REGEX = LOW_VALUE_PATTERNS.joinToString("|") { Regex.escape(it) }.let { Regex(it, RegexOption.IGNORE_CASE) }
        private const val MAX_CACHE_SIZE = 200
        private const val MAX_EDIT_DISTANCE = 50
        private const val BASE_SCORE_CHAT = 0.3f
        private const val BASE_SCORE_USER_COMMAND = 1.0f
        private const val BASE_SCORE_AI_RESULT = 0.8f
        private const val BASE_SCORE_SYSTEM = 0.5f
        private const val MIN_KEY_INFO_SCORE = 0.6f
    }
        private val scoreCache = object : LinkedHashMap<String, MessageImportanceScore>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MessageImportanceScore>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
        fun scoreMessage(message: Message, previousMessages: List<Message> = emptyList()): MessageImportanceScore {
        val cacheKey = "${message.role}|${message.content}|${previousMessages.hashCode()}"
        scoreCache[cacheKey]?.let { return it }
        val reasons = mutableListOf<String>()
        var score = when (message.role.lowercase()) {
            "user" -> scoreUserMessage(message, reasons)
            "assistant" -> scoreAssistantMessage(message, reasons)
            "system" -> BASE_SCORE_SYSTEM
            else -> BASE_SCORE_CHAT
        }
        val hasKeyInfo = checkKeyInformation(message.content, reasons)
        if (hasKeyInfo && score < MIN_KEY_INFO_SCORE) {
            score = MIN_KEY_INFO_SCORE
        }
        val repetitionPenalty = calculateRepetitionPenalty(message, previousMessages)
        if (repetitionPenalty < 1.0f) {
            score *= repetitionPenalty
            reasons.add("重复内容评分折扣: x${String.format("%.2f", repetitionPenalty)}")
        }
        val result = MessageImportanceScore(
            score = score.coerceIn(0.0f, 1.0f),
            reasons = reasons,
            hasKeyInformation = hasKeyInfo
        )
        scoreCache[cacheKey] = result
        return result
    }
        private fun scoreUserMessage(message: Message, reasons: MutableList<String>): Float {
        var score = BASE_SCORE_USER_COMMAND
        val content = message.content

        if (HIGH_VALUE_REGEX.containsMatchIn(content)) {
            score += 0.15f
            reasons.add("包含高价值关键词")
        }
        if (CODE_BLOCK_PATTERN.containsMatchIn(content)) {
            score += 0.1f
            reasons.add("包含代码块")
        }
        if (COMMAND_PATTERN.containsMatchIn(content)) {
            score += 0.1f
            reasons.add("包含命令")
        }
        if (TASK_ID_PATTERN.containsMatchIn(content)) {
            score += 0.15f
            reasons.add("包含任务ID")
        }
        if (URL_PATTERN.containsMatchIn(content)) {
            score += 0.05f
            reasons.add("包含URL")
        }
        if (EMAIL_PATTERN.containsMatchIn(content)) {
            score += 0.05f
            reasons.add("包含邮箱")
        }
        if (content.length > 500) {
            score += 0.05f
            reasons.add("长文本内容")
        }
        if (LOW_VALUE_REGEX.containsMatchIn(content)) {
            score -= 0.2f
            reasons.add("包含低价值闲聊模式")
        }
        return score
    }
        private fun scoreAssistantMessage(message: Message, reasons: MutableList<String>): Float {
        var score = BASE_SCORE_AI_RESULT
        val content = message.content

        if (CODE_BLOCK_PATTERN.containsMatchIn(content)) {
            score += 0.15f
            reasons.add("包含代码输出")
        }
        if (ERROR_KEYWORD_PATTERN.containsMatchIn(content)) {
            score += 0.1f
            reasons.add("包含错误信息")
        }
        if (WARNING_KEYWORD_PATTERN.containsMatchIn(content)) {
            score += 0.05f
            reasons.add("包含警告信息")
        }
        if (KEY_NUMBER_PATTERN.containsMatchIn(content)) {
            score += 0.05f
            reasons.add("包含数字数据")
        }
        if (URL_PATTERN.containsMatchIn(content)) {
            score += 0.05f
            reasons.add("包含链接")
        }
        if (COMPLETION_KEYWORD_PATTERN.containsMatchIn(content)) {
            score += 0.1f
            reasons.add("包含完成状态")
        }
        if (LOW_VALUE_REGEX.containsMatchIn(content)) {
            score -= 0.15f
            reasons.add("包含低价值闲聊模式")
        }
        return score
    }
        private fun checkKeyInformation(content: String, reasons: MutableList<String>): Boolean {
        var hasKeyInfo = false

        if (KEY_NUMBER_PATTERN.containsMatchIn(content)) {
            hasKeyInfo = true
            reasons.add("包含重要数字")
        }
        if (DATE_PATTERN.containsMatchIn(content)) {
            hasKeyInfo = true
            reasons.add("包含日期时间")
        }
        if (TASK_ID_PATTERN.containsMatchIn(content)) {
            hasKeyInfo = true
            reasons.add("包含任务标识")
        }
        if (EMAIL_PATTERN.containsMatchIn(content)) {
            hasKeyInfo = true
            reasons.add("包含邮箱地址")
        }
        return hasKeyInfo
    }
        private fun calculateRepetitionPenalty(message: Message, previousMessages: List<Message>): Float {
        if (previousMessages.isEmpty()) return 1.0f

        val content = message.content.lowercase()
        var matchCount = 0
        var totalSimilarity = 0f

        for (prev in previousMessages.takeLast(5)) {
            val prevContent = prev.content.lowercase()
        if (content == prevContent && content.isNotBlank()) {
                matchCount++
                totalSimilarity = 1.0f
            } else if (content.length > 20 && prevContent.length > 20) {
                val similarity = calculateSimilarity(content, prevContent)
        if (similarity > 0.7f) {
                    matchCount++
                    totalSimilarity += similarity
                }
            }
        }
        return when {
            matchCount >= 3 -> 0.5f
            matchCount == 2 -> 0.7f
            matchCount == 1 -> 0.85f
            else -> 1.0f
        }
    }
        private fun calculateSimilarity(str1: String, str2: String): Float {
        val longer = if (str1.length > str2.length) str1 else str2
        val shorter = if (str1.length > str2.length) str2 else str1

        if (longer.isEmpty()) return 1.0f

        val longerLength = longer.length
        return (longerLength - editDistance(longer, shorter).toFloat()) / longerLength
    }
        private fun editDistance(str1: String, str2: String): Int {
        val len1 = str1.length
        val len2 = str2.length
        if (kotlin.math.abs(len1 - len2) > MAX_EDIT_DISTANCE) return MAX_EDIT_DISTANCE + 1
        if (len1 > MAX_EDIT_DISTANCE * 2 || len2 > MAX_EDIT_DISTANCE * 2) return MAX_EDIT_DISTANCE + 1

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }
        private fun containsAnyKeyword(content: String, keywords: List<String>): Boolean {
        val lowerContent = content.lowercase()
        return keywords.any { lowerContent.contains(it.lowercase()) }
    }
        private fun containsAnyPattern(content: String, patterns: List<String>): Boolean {
        val lowerContent = content.lowercase()
        return patterns.any { lowerContent.contains(it.lowercase()) }
    }
        fun scoreMessages(messages: List<Message>): List<Pair<Message, MessageImportanceScore>> {
        val cachedScores = mutableMapOf<Int, MessageImportanceScore>()
        return messages.mapIndexed { index, message ->
            val previousMessages = if (index > 0) messages.subList(0, index) else emptyList()
        val result = scoreMessage(message, previousMessages)
        cachedScores[index] = result
            message to result
        }
    }
        fun clearCache() {
        scoreCache.clear()
    }
}
