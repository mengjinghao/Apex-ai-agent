package com.apex.agent.core.normal.skimming

/**
 * F42: 对话速读模式（Skim Mode）
 *
 * 精简 AI 回复，适合快速浏览：
 * - 一句话总结
 * - 要点提炼
 * - 关键词高亮
 * - 分级展开（TL;DR → 要点 → 详情）
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 不优化阅读
 * - 狂暴不关心可读性
 * - 本功能让单 Agent **高效、可速读**
 */

enum class SkimLevel {
    HEADLINE_ONLY,   // 仅标题
    TLDR,            // 一句话总结
    KEY_POINTS,      // 要点列表
    SUMMARY,         // 简短摘要
    FULL             // 完整内容
}

data class SkimResult(
    val original: String,
    val level: SkimLevel,
    val headline: String,
    val tldr: String,
    val keyPoints: List<String>,
    val summary: String,
    val keywords: List<String>,
    val readingTimeMs: Long,
    val originalWords: Int,
    val skimmedWords: Int,
    val compressionRatio: Float
)

class SkimModeProcessor {

    fun process(text: String, level: SkimLevel = SkimLevel.TLDR): SkimResult {
        val start = System.currentTimeMillis()
        val headline = extractHeadline(text)
        val tldr = generateTLDR(text)
        val keyPoints = extractKeyPoints(text)
        val summary = generateSummary(text)
        val keywords = extractKeywords(text)
        val originalWords = countWords(text)
        val skimmedContent = when (level) {
            SkimLevel.HEADLINE_ONLY -> headline
            SkimLevel.TLDR -> tldr
            SkimLevel.KEY_POINTS -> keyPoints.joinToString("\n") { "• $it" }
            SkimLevel.SUMMARY -> summary
            SkimLevel.FULL -> text
        }
        val skimmedWords = countWords(skimmedContent)

        return SkimResult(
            original = text,
            level = level,
            headline = headline,
            tldr = tldr,
            keyPoints = keyPoints,
            summary = summary,
            keywords = keywords,
            readingTimeMs = System.currentTimeMillis() - start,
            originalWords = originalWords,
            skimmedWords = skimmedWords,
            compressionRatio = if (originalWords > 0) skimmedWords.toFloat() / originalWords else 1f
        )
    }

    fun formatSkimmed(result: SkimResult): String {
        return when (result.level) {
            SkimLevel.HEADLINE_ONLY -> "📰 ${result.headline}"
            SkimLevel.TLDR -> "📝 TL;DR: ${result.tldr}"
            SkimLevel.KEY_POINTS -> buildString {
                appendLine("📌 要点:")
                result.keyPoints.forEach { appendLine("• $it") }
            }
            SkimLevel.SUMMARY -> "📄 ${result.summary}"
            SkimLevel.FULL -> result.original
        }
    }

    private fun extractHeadline(text: String): String {
        // 取第一个标题或第一句话
        val firstLine = text.lines().firstOrNull { it.isNotBlank() } ?: ""
        return when {
            firstLine.matches(Regex("^#+\\s+.+")) -> firstLine.removePrefix("#").trim()
            firstLine.length < 50 -> firstLine
            else -> firstLine.take(40) + "..."
        }
    }

    private fun generateTLDR(text: String): String {
        // 取最重要的句子（第一个完整句子）
        val sentences = text.split(Regex("[。.！!？?\\n]")).filter { it.isNotBlank() }
        val firstSentence = sentences.firstOrNull() ?: text.take(100)
        return if (firstSentence.length > 80) firstSentence.take(80) + "..." else firstSentence
    }

    private fun extractKeyPoints(text: String): List<String> {
        val points = mutableListOf<String>()
        // 已有列表项
        Regex("(?:^|\\n)[-*•]\\s+(.+)", RegexOption.MULTILINE).findAll(text).forEach {
            points.add(it.groupValues[1].trim())
        }
        // 标题
        Regex("(?:^|\\n)#+\\s+(.+)", RegexOption.MULTILINE).findAll(text).forEach {
            points.add(it.groupValues[1].trim())
        }
        // 如果没有，从句子提取
        if (points.isEmpty()) {
            val sentences = text.split(Regex("[。.！!？?\\n]")).filter { it.isNotBlank() && it.length > 10 }
            points.addAll(sentences.take(3))
        }
        return points.take(5)
    }

    private fun generateSummary(text: String): String {
        val sentences = text.split(Regex("[。.！!？?\\n]")).filter { it.isNotBlank() }
        return when {
            sentences.isEmpty() -> text.take(200)
            sentences.size <= 2 -> text.take(200)
            else -> listOf(sentences.first(), sentences[sentences.size / 2], sentences.last())
                .joinToString(" ").take(200)
        }
    }

    private fun extractKeywords(text: String): List<String> {
        return text.split(Regex("[\\s,，。.？?！!；;：:、\"'()（）\\[\\]【】\\n]+"))
            .filter { it.length >= 3 }
            .groupBy { it.lowercase() }
            .filter { it.value.size >= 2 }
            .keys
            .take(10)
            .toList()
    }

    private fun countWords(text: String): Int {
        val chinese = text.count { it.code in 0x4e00..0x9fff }
        val english = text.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotEmpty() && it.all { c -> c.code !in 0x4e00..0x9fff } }
            .size
        return chinese + english
    }
}
