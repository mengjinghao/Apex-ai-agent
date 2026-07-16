package com.apex.util

import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.WordDictionary
import java.io.File
import android.content.Context
import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 文本分词工具类
 *
 * 提供中文和多语言文本的分词功能，支持自定义词典加载、
 * 分词缓存、相关性评分、关键词提取等高级特性。
 */
object TextSegmenter {
    private const val TAG = "TextSegmenter"
    private const val PREWARM_TEXT = "搜索记忆 分词预热"

    private val segmenter by lazy { JiebaSegmenter() }

    private val initLock = Any()

    @Volatile
    private var baseInitialized = false

    private val loadedUserDictPaths = ConcurrentHashMap.newKeySet<String>()

    private val segmentCache = ConcurrentHashMap<String, List<String>>()

    private const val MAX_CACHE_SIZE = 1000

    private val stopWords = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "can", "could",
        "shall", "should", "may", "might", "must", "i", "you", "he", "she", "it",
        "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its",
        "our", "their", "mine", "yours", "hers", "ours", "theirs", "this", "that",
        "these", "those", "and", "but", "or", "not", "no", "nor", "so", "if", "then",
        "else", "when", "where", "why", "how", "which", "who", "whom", "what",
        "in", "on", "at", "to", "for", "with", "by", "from", "of", "as", "into",
        "through", "during", "before", "after", "about", "between", "under", "over",
        "again", "further", "once", "here", "there", "all", "each", "every", "both",
        "few", "more", "most", "other", "some", "such", "only", "own", "same",
        "too", "very", "just", "because", "than", "also", "any", "off", "up", "down",
        "out", "above", "below", "的", "了", "在", "是", "我", "有", "和", "就", "不",
        "人", "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会",
        "着", "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那",
        "什么", "怎么", "如何", "因为", "所以", "但是", "然而", "如果", "虽然",
        "而且", "或者", "还是", "只是", "以及", "不仅", "因此", "然后", "之后",
        "同时", "其中", "关于", "根据", "按照", "通过", "进行", "可以", "能够",
        "应该", "必须", "需要", "可能", "已经", "正在", "一直", "从来", "常常",
        "往往", "有时", "偶尔", "终于", "刚刚", "马上", "立刻", "曾经", "将要",
        "把", "被", "让", "给", "对", "从", "向", "在", "到", "于", "比", "跟",
        "同", "与", "为", "以"
    )

    private val sentenceDelimiter = Regex("[。！？.!?\\n]+")

    private var totalSegmentCalls: Long = 0
    private var cacheHits: Long = 0

    /**
     * 分词统计信息数据类
     *
     * @param cacheSize 当前缓存大小
     * @param totalSegments 总的分词调用次数
     * @param hitRate 缓存命中率（0.0 ~ 1.0）
     */
    data class SegmentStats(
        val cacheSize: Int,
        val totalSegments: Long,
        val hitRate: Double
    )

    /**
     * 初始化分词器，可选加载自定义词典
     *
     * @param context 应用上下文
     * @param customDictPath 自定义词典路径（可选）
     */
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context, customDictPath: String? = null) {
        if (baseInitialized && customDictPath.isNullOrBlank()) return

        val startTime = System.currentTimeMillis()
        try {
            synchronized(initLock) {
                val dictionary = WordDictionary.getInstance()

                customDictPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { loadCustomDictionaryIfNeeded(dictionary, it) }

                if (!baseInitialized) {
                    segmenter.process(PREWARM_TEXT, JiebaSegmenter.SegMode.SEARCH)
                    baseInitialized = true
                    AppLogger.d(
                        TAG,
                        "分词器预热完成，耗时 ${System.currentTimeMillis() - startTime}ms"
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "初始化分词器失败", e)
        }
    }

    /**
     * 加载用户自定义词典（若尚未加载过）
     *
     * @param dictionary WordDictionary 实例
     * @param customDictPath 自定义词典文件路径
     */
    private fun loadCustomDictionaryIfNeeded(dictionary: WordDictionary, customDictPath: String) {
        val dictFile = File(customDictPath)
        if (!dictFile.exists()) return

        val normalizedPath = dictFile.absolutePath
        if (normalizedPath in loadedUserDictPaths) return

        dictionary.loadUserDict(dictFile.toPath())
        loadedUserDictPaths.add(normalizedPath)
        AppLogger.d(TAG, "已加载自定义词典: ${normalizedPath}")
    }

    /**
     * 对文本进行分词（仅返回长度大于 1 的词）
     *
     * @param text 要分词的文本
     * @param useCached 是否使用缓存
     * @return 分词后的关键词列表
     */
    fun segment(text: String, useCached: Boolean = true): List<String> {
        totalSegmentCalls++
        if (text.isBlank()) return emptyList()

        if (useCached && segmentCache.containsKey(text)) {
            cacheHits++
            return segmentCache[text] ?: emptyList()
        }

        try {
            val result = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)
                .map { it.word }
                .filter { it.length > 1 }

            if (useCached) {
                if (segmentCache.size > MAX_CACHE_SIZE) {
                    val keysToRemove = segmentCache.keys.take(MAX_CACHE_SIZE / 2)
                    keysToRemove.forEach { segmentCache.remove(it) }
                }
                segmentCache[text] = result
            }

            return result
        } catch (e: Exception) {
            AppLogger.e(TAG, "分词失败: ${e.message}")
            return text.split(Regex("\\s+|,|，|\\.|。"))
                .filter { it.length > 1 }
        }
    }

    /**
     * 将文本分割为单个词语（包含单字词，不过滤）
     *
     * 使用结巴分词器对文本进行 INDEX 模式分词，返回所有词语，
     * 包括单个字符的词语。
     *
     * @param text 要分词的文本
     * @return 所有词语列表（包含单字词）
     */
    fun segmentToWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return try {
            segmenter.process(text, JiebaSegmenter.SegMode.INDEX)
                .map { it.word }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "词语分割失败: ${e.message}")
            text.split(Regex("\\s+|,|，|\\.|。|！|？|；"))
                .filter { it.isNotBlank() }
        }
    }

    /**
     * 将文本分割为句子列表
     *
     * 根据句号、问号、感叹号、换行符等分句符号进行分割，
     * 过滤空字符串。
     *
     * @param text 要分割的文本
     * @return 句子列表
     */
    fun segmentToSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(sentenceDelimiter)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * 基于词频（TF）的关键词提取
     *
     * 对文本进行分词后统计词频，过滤停用词和单字词，
     * 返回词频最高的 topN 个词语。
     *
     * @param text 源文本
     * @param topN 返回的关键词数量，默认为 10
     * @return 关键词列表，按词频降序排列
     */
    fun extractKeywords(text: String, topN: Int = 10): List<String> {
        if (text.isBlank()) return emptyList()
        return try {
            val words = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)
                .map { it.word }
                .filter { it.length > 1 && it !in stopWords }

            // 统计词频
            val freqMap = mutableMapOf<String, Int>()
            words.forEach { word ->
                freqMap[word] = (freqMap[word] ?: 0) + 1
            }

            freqMap.entries
                .sortedByDescending { it.value }
                .take(topN)
                .map { it.key }
        } catch (e: Exception) {
            AppLogger.e(TAG, "关键词提取失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取文本中的词语数量（基于分词结果）
     *
     * @param text 要统计的文本
     * @return 词语数量
     */
    fun getWordCount(text: String): Int {
        if (text.isBlank()) return 0
        return try {
            segmenter.process(text, JiebaSegmenter.SegMode.SEARCH).size
        } catch (e: Exception) {
            text.split(Regex("\\s+")).count { it.isNotBlank() }
        }
    }

    /**
     * 获取文本中非空白字符的数量
     *
     * @param text 要统计的文本
     * @return 非空白字符数量
     */
    fun getCharCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.count { !it.isWhitespace() }
    }

    /**
     * 判断指定词语是否为停用词
     *
     * 内置常见英文和中文停用词列表。
     *
     * @param word 要检查的词语
     * @return true 为停用词，false 不是
     */
    fun isStopWord(word: String): Boolean {
        return word.trim().lowercase() in stopWords
    }

    /**
     * 模糊判断文本是否包含指定关键词
     *
     * 对文本和关键词进行分词后检查是否包含，不区分大小写。
     *
     * @param text 目标文本
     * @param keyword 要查找的关键词
     * @return true 包含，false 不包含
     */
    fun containsKeyword(text: String, keyword: String): Boolean {
        if (text.isBlank() || keyword.isBlank()) return false
        val textLower = text.lowercase()
        val keywordLower = keyword.lowercase()

        // 先尝试直接包含检查
        if (textLower.contains(keywordLower)) return true

        // 再尝试对关键词分词后逐词检查
        return try {
            val keywordWords = segmenter.process(keyword, JiebaSegmenter.SegMode.SEARCH)
                .map { it.word.lowercase() }
                .filter { it.length > 1 }

            keywordWords.any { word -> textLower.contains(word) }
        } catch (e: Exception) {
            textLower.contains(keywordLower)
        }
    }

    /**
     * 获取分词统计信息
     *
     * @return SegmentStats 包含缓存大小、总调用次数和命中率
     */
    fun getSegmentStats(): SegmentStats {
        val total = totalSegmentCalls
        val hits = cacheHits
        val hitRate = if (total > 0) hits.toDouble() / total else 0.0
        return SegmentStats(
            cacheSize = segmentCache.size,
            totalSegments = total,
            hitRate = hitRate
        )
    }

    /**
     * 清除分词缓存
     */
    fun clearCache() {
        segmentCache.clear()
        totalSegmentCalls = 0
        cacheHits = 0
    }

    /**
     * 计算文本与关键词的相关性得分
     *
     * @param text 要检查的文本
     * @param keywords 关键词列表
     * @return 相关性得分（0.0 ~ 1.0 范围）
     */
    fun calculateRelevance(text: String, keywords: List<String>): Double {
        if (text.isBlank() || keywords.isEmpty()) return 0.0

        val maxLength = 5000
        val textToProcess = if (text.length > maxLength) text.substring(0, maxLength) else text
        val textLower = textToProcess.lowercase()

        val hasDirectMatch = keywords.any { keyword ->
            textLower.contains(keyword.lowercase())
        }

        if (!hasDirectMatch) {
            return 0.0
        }

        val exactMatches = keywords.count { keyword ->
            textLower.contains(keyword.lowercase())
        }

        if (exactMatches >= keywords.size / 2 || exactMatches >= 3) {
            val quickScore = (exactMatches * 2.0) / (keywords.size * 3.0)
            return quickScore.coerceIn(0.0, 1.0)
        }

        val textSegments = segment(textLower)

        val segmentMatches = keywords.count { keyword ->
            textSegments.any { segment -> segment.contains(keyword.lowercase()) }
        }

        val totalScore = (exactMatches * 2.0 + segmentMatches) / (keywords.size * 3.0)
        return totalScore.coerceIn(0.0, 1.0)
    }
}
