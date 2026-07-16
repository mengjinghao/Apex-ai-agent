package com.apex.api.chat.llmprovider

import com.apex.util.AppLogger

/**
 * 任务复杂度分析报? */
data class ComplexityReport(
    val complexity: TaskComplexity,
    val estimatedTokens: Int,
    val suggestedTier: String,
    val confidence: Float
)

/**
 * 任务复杂度分析器
 * 通过分析输入文本的长度、结构和关键词，评估任务复杂? */
object TaskComplexityAnalyzer {

    private const val TAG = "TaskComplexityAnalyzer"

    // 任务类型关键词映?    private val taskTypeKeywords = mapOf(
        TaskComplexity.SIMPLE to listOf(
            "你好", "hello", "hi", "谢谢", "thanks",
            "是什?, "what is", "简?, "simple", "基本", "basic"
        ),
        TaskComplexity.SINGLE_FILE to listOf(
            "编辑", "edit", "修改", "modify", "更新", "update",
            "单文?, "single file", "这个文件", "this file",
            "写个", "write a", "创建一?, "create a"
        ),
        TaskComplexity.MULTI_FILE to listOf(
            "搜索", "search", "查找", "find", "全局", "global",
            "重构", "refactor", "重命?, "rename",
            "多个文件", "multiple files", "所有文?, "all files"
        ),
        TaskComplexity.COMPLEX to listOf(
            "架构", "architecture", "设计", "design", "系统", "system",
            "实现", "implement", "开?, "develop", "构建", "build",
            "规划", "plan", "方案", "solution", "整体", "overall"
        ),
        TaskComplexity.SECURITY to listOf(
            "安全", "security", "漏洞", "vulnerability", "审计", "audit",
            "加密", "encrypt", "权限", "permission", "认证", "auth",
            "隐私", "privacy", "敏感", "sensitive"
        )
    )

    // 复杂度到推荐层级的映?    private val complexityToTier = mapOf(
        TaskComplexity.SIMPLE to "lightweight",
        TaskComplexity.SINGLE_FILE to "standard",
        TaskComplexity.MULTI_FILE to "capable",
        TaskComplexity.COMPLEX to "powerful",
        TaskComplexity.SECURITY to "powerful"
    )

    /**
     * 分析输入文本的复杂度
     *
     * @param input 用户输入的文?     * @return 复杂度分析报?     */
    fun analyzeComplexity(input: String): ComplexityReport {
        if (input.isBlank()) {
            return ComplexityReport(
                complexity = TaskComplexity.SIMPLE,
                estimatedTokens = 0,
                suggestedTier = "lightweight",
                confidence = 1.0f
            )
        }

        // 1. 分析文本结构
        val wordCount = countWords(input)
        val paragraphCount = countParagraphs(input)
        val charCount = input.length

        AppLogger.d(TAG, "文本分析: words=${wordCount}, paragraphs=${paragraphCount}, chars=${charCount}")

        // 2. 检测任务类型关键词
        val detectedTypes = detectTaskTypes(input)
        AppLogger.d(TAG, "检测到的任务类? ${detectedTypes.keys}")

        // 3. 评估预估 Token 消?        val estimatedTokens = estimateTokens(input, wordCount, paragraphCount)
        AppLogger.d(TAG, "预估 Token 消? ${estimatedTokens}")

        // 4. 综合评估复杂?        val complexity = evaluateComplexity(wordCount, paragraphCount, detectedTypes, estimatedTokens)
        val suggestedTier = complexityToTier[complexity] ?: "standard"
        val confidence = calculateConfidence(detectedTypes, wordCount)

        AppLogger.i(TAG, "复杂度分析结? complexity=${complexity}, tier=${suggestedTier}, confidence=${confidence}")

        return ComplexityReport(
            complexity = complexity,
            estimatedTokens = estimatedTokens,
            suggestedTier = suggestedTier,
            confidence = confidence
        )
    }

    /**
     * 统计词数（支持中英文混合?     * 英文按空格分词，中文按字符计
     */
    private fun countWords(text: String): Int {
        var count = 0
        // 英文单词?        val englishWords = text.split(Regex("\\s+")).filter { it.isNotBlank() && it.any { c -> c.isLetter() && c.code < 128 } }
        count += englishWords.size
        // 中文字符数（每个中文字符算一个词?        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        count += chineseChars
        return count
    }

    /**
     * 统计段落?     */
    private fun countParagraphs(text: String): Int {
        return text.split(Regex("\\n\\s*\\n|\\r\\n\\s*\\r\\n"))
            .filter { it.isNotBlank() }
            .size
            .coerceAtLeast(1)
    }

    /**
     * 检测任务类型关键词
     * 返回 Map: TaskComplexity -> 匹配到的关键词列?     */
    private fun detectTaskTypes(input: String): Map<TaskComplexity, List<String>> {
        val lowerInput = input.lowercase()
        val result = mutableMapOf<TaskComplexity, List<String>>()

        for ((complexity, keywords) in taskTypeKeywords) {
            val matched = keywords.filter { keyword ->
                lowerInput.contains(keyword.lowercase())
            }
            if (matched.isNotEmpty()) {
                result[complexity] = matched
            }
        }

        return result
    }

    /**
     * 预估 Token 消?     * 基于字数和结构进行估?     * - 英文? ??1.3 tokens
     * - 中文? ??1.5 tokens
     * - 代码块：额外增加 20%
     * - 多段落：每段增加 5 tokens（分隔符?     */
    private fun estimateTokens(text: String, wordCount: Int, paragraphCount: Int): Int {
        // 基础 token 估算
        val englishWords = text.split(Regex("\\s+")).filter { it.isNotBlank() && it.any { c -> c.isLetter() && c.code < 128 } }.size
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }

        var tokens = (englishWords * 1.3 + chineseChars * 1.5).toInt()

        // 代码块检测（简单的 { } 或关键词?        val hasCode = text.contains(Regex("(function|class|def |const |let |var |public |private )")) ||
                text.contains("{") && text.contains("}")
        if (hasCode) {
            tokens = (tokens * 1.2).toInt()
        }

        // 段落分隔?        tokens += (paragraphCount - 1) * 5

        // 最小值保?        return tokens.coerceAtLeast(10)
    }

    /**
     * 综合评估复杂?     */
    private fun evaluateComplexity(
        wordCount: Int,
        paragraphCount: Int,
        detectedTypes: Map<TaskComplexity, List<String>>,
        estimatedTokens: Int
    ): TaskComplexity {
        // 如果没有检测到任何关键词，基于文本长度判断
        if (detectedTypes.isEmpty()) {
            return when {
                wordCount < 20 -> TaskComplexity.SIMPLE
                wordCount < 100 -> TaskComplexity.SINGLE_FILE
                wordCount < 500 -> TaskComplexity.MULTI_FILE
                else -> TaskComplexity.COMPLEX
            }
        }

        // 基于检测到的任务类型，选择最高复杂度
        // 优先级：SECURITY > COMPLEX > MULTI_FILE > SINGLE_FILE > SIMPLE
        val priorityOrder = listOf(
            TaskComplexity.SECURITY,
            TaskComplexity.COMPLEX,
            TaskComplexity.MULTI_FILE,
            TaskComplexity.SINGLE_FILE,
            TaskComplexity.SIMPLE
        )

        for (complexity in priorityOrder) {
            if (detectedTypes.containsKey(complexity)) {
                // 如果文本很短但检测到高复杂度关键词，降级处理
                if (wordCount < 10 && complexity.ordinal > TaskComplexity.SIMPLE.ordinal) {
                    continue
                }
                return complexity
            }
        }

        return TaskComplexity.SIMPLE
    }

    /**
     * 计算置信?     * 基于检测到的关键词数量和文本长?     */
    private fun calculateConfidence(detectedTypes: Map<TaskComplexity, List<String>>, wordCount: Int): Float {
        if (detectedTypes.isEmpty()) {
            // 没有关键词匹配，置信度较?            return if (wordCount > 50) 0.6f else 0.4f
        }

        // 关键词越多，置信度越?        val totalKeywords = detectedTypes.values.sumOf { it.size }
        val keywordScore = (totalKeywords * 0.15f).coerceAtMost(0.5f)

        // 文本长度适中时置信度更高
        val lengthScore = when {
            wordCount in 20..200 -> 0.3f
            wordCount in 200..500 -> 0.2f
            else -> 0.1f
        }

        // 如果只检测到一种类型，置信度更?        val typeClarity = if (detectedTypes.size == 1) 0.2f else 0f

        return (0.4f + keywordScore + lengthScore + typeClarity).coerceAtMost(1.0f)
    }
}
