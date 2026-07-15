package com.apex.agent.core.ai.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.text.Regex

data class TokenizedResult(
    val tokens: List<String>,
    val tokenCount: Int,
    val estimatedTokens: Int,
    val language: String,
    val averageTokenLength: Double,
    val uniqueTokens: Int,
    val compressionRatio: Double
)

data class ParsedIntent(
    val action: String,
    val target: String?,
    val parameters: Map<String, String>,
    val confidence: Double,
    val rawText: String,
    val tokens: List<String>,
    val sentiment: SentimentScore? = null,
    val entities: List<Entity> = emptyList(),
    val alternativeActions: List<String> = emptyList()
)

data class SentimentScore(
    val score: Double,
    val magnitude: Double,
    val label: String,
    val confidence: Double
)

data class Entity(
    val name: String,
    val value: String,
    val type: EntityType,
    val startPosition: Int,
    val endPosition: Int,
    val confidence: Double
)

enum class EntityType {
    PERSON, LOCATION, ORGANIZATION, DATE, TIME, NUMBER,
    COMMAND, PARAMETER, FILE_PATH, URL, EMAIL, PHONE,
    LANGUAGE, TOOL_NAME, SKILL_NAME, UNKNOWN
}

data class IntentCacheEntry(
    val text: String,
    val intent: ParsedIntent,
    val timestampMs: Long,
    val hitCount: Int = 0
)

data class LanguageProfile(
    val language: String,
    val confidence: Double,
    val commonWords: Set<String>,
    val stopWords: Set<String>,
    val wordPattern: Regex
)

data class NLPMetrics(
    val totalParsed: Long,
    val cacheHitRate: Double,
    val averageParseTimeMs: Double,
    val p95ParseTimeMs: Double,
    val intentDistribution: Map<String, Int>,
    val cacheSize: Int,
    val totalTokensProcessed: Long,
    val averageConfidence: Double
)

data class NLPTuningConfig(
    val enableCache: Boolean = true,
    val cacheMaxSize: Int = 5000,
    val cacheTtlMs: Long = 3600000L,
    val enableSentiment: Boolean = true,
    val enableEntityExtraction: Boolean = true,
    val enableLanguageDetection: Boolean = true,
    val maxAlternativeActions: Int = 3,
    val fuzzyMatchThreshold: Double = 0.85,
    val tokenizerType: TokenizerType = TokenizerType.WHITESPACE,
    val maxTokenLength: Int = 100,
    val threadPoolSize: Int = 2
)

enum class TokenizerType { WHITESPACE, CHARACTER, REGEX, SMART }

class NLPOptimizer private constructor() {

    private val intentCache = ConcurrentHashMap<String, IntentCacheEntry>()
        private val languageProfiles = loadLanguageProfiles()
        private val parseTimeHistory = CopyOnWriteArrayList<Long>()
        private val intentCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val totalParsed = AtomicLong(0)
        private val totalTokens = AtomicLong(0)
        private val cacheHits = AtomicLong(0)
        private val cacheMisses = AtomicLong(0)
        private val config = NLPTuningConfig()
        private val mutex = Mutex()
        private var scope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: NLPOptimizer? = null

        fun getInstance(): NLPOptimizer {
            return instance ?: synchronized(this) {
                instance ?: NLPOptimizer().also { instance = it }
            }
        }
        private val COMMON_ENGLISH_WORDS = setOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me"
        )
        private val COMMAND_PATTERNS = listOf(
            Regex("^(?:open|launch|start|run)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:close|stop|exit|quit)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:find|search|lookup)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:create|make|new|add)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:delete|remove|erase|clear)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:update|change|modify|edit|set)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:show|display|list|get)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:send|share|export|upload)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:download|save|import)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:copy|move|rename|duplicate)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:analyze|parse|process|compile)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:install|uninstall|setup|configure)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:enable|disable|activate|deactivate)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:lock|unlock|secure|protect)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:connect|disconnect|link|pair)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:schedule|remind|notify|alert)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        private val PARAMETER_PATTERN = Regex("--?(\\w+)[=:]?[\"']?([^\"'\\s]+)?[\"']?", RegexOption.IGNORE_CASE)
        private val FILE_PATH_PATTERN = Regex("(?:/[\\w.\\-]+)+|(?:[A-Za-z]:\\[\\w.\\-]+(?:\\[\\w.\\-]+)*)", RegexOption.IGNORE_CASE)
        private val URL_PATTERN = Regex("https?://[\\w.-]+(:\\d+)?(/[\\w./%-]*)?", RegexOption.IGNORE_CASE)
        private val EMAIL_PATTERN = Regex("[\\w.-]+@[\\w.-]+\\.\\w{2,}", RegexOption.IGNORE_CASE)
    }
        private fun loadLanguageProfiles(): List<LanguageProfile> = listOf(
        LanguageProfile("en", 0.95, COMMON_ENGLISH_WORDS,
            setOf("a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "by", "with", "from", "is", "are", "was", "were", "be", "been", "have", "has", "had", "do", "does", "did", "will", "would", "can", "could", "shall", "should", "may", "might", "i", "you", "he", "she", "it", "we", "they", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our", "their"),
            Regex("[a-zA-Z]+")),
        LanguageProfile("zh", 0.6, emptySet(), emptySet(), Regex("[\\u4e00-\\u9fff]+")),
        LanguageProfile("ja", 0.5, emptySet(), emptySet(), Regex("[\\u3040-\\u309f\\u30a0-\\u30ff\\u4e00-\\u9fff]+")),
        LanguageProfile("ko", 0.4, emptySet(), emptySet(), Regex("[\\uac00-\\ud7af]+")),
        LanguageProfile("de", 0.3, setOf("der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einer", "eines", "und", "oder", "aber", "mit", "von", "zu", "auf", "an", "für", "nicht", "sich", "auch", "werden", "ist", "sind", "war"),
            setOf("der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einer", "eines", "und", "oder", "aber", "mit", "von", "zu", "auf", "an"),
            Regex("[a-zA-Zäöüß]+"))
    )
        fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        if (config.enableCache) {
            coroutineScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(60000L)
                    evictStaleCacheEntries()
                }
            }
        }
    }
        fun parseIntent(text: String): ParsedIntent {
        val startTime = System.nanoTime()
        totalParsed.incrementAndGet()
        if (config.enableCache) {
            val normalized = normalizeText(text)
        val cached = intentCache[normalized]
            if (cached != null && (System.currentTimeMillis() - cached.timestampMs) < config.cacheTtlMs) {
                cacheHits.incrementAndGet()
                intentCounts.computeIfAbsent(cached.intent.action) { AtomicInteger(0) }.incrementAndGet()
        return cached.intent
            }
            cacheMisses.incrementAndGet()
        }
        val tokens = tokenize(text)
        totalTokens.addAndGet(tokens.size.toLong())
        val language = detectLanguage(text, tokens)
        val entities = extractEntities(text)
        val sentiment = if (config.enableSentiment) analyzeSentiment(text) else null

        var bestAction = "unknown"
        var bestTarget: String? = null
        var bestConfidence = 0.0
        val alternatives = mutableListOf<String>()
        for (pattern in COMMAND_PATTERNS) {
            val match = pattern.find(text.trim())
        if (match != null) {
                val action = match.groupValues[0].lowercase().substringBefore(" ")
        val target = match.groupValues[1]
                val confidence = calculateConfidence(action, target, tokens)
        if (confidence > bestConfidence) {
                    if (bestConfidence > 0) alternatives.add(bestAction)
                    bestAction = action
                    bestTarget = target
                    bestConfidence = confidence
                } else if (alternatives.size < config.maxAlternativeActions) {
                    alternatives.add(action)
                }
            }
        }
        val parameters = parseParameters(text)
        val intent = ParsedIntent(
            action = bestAction,
            target = bestTarget,
            parameters = parameters,
            confidence = bestConfidence.coerceIn(0.0, 1.0),
            rawText = text,
            tokens = tokens,
            sentiment = sentiment,
            entities = entities,
            alternativeActions = alternatives
        )
        if (config.enableCache) {
            val normalized = normalizeText(text)
            intentCache[normalized] = IntentCacheEntry(
                text = text,
                intent = intent,
                timestampMs = System.currentTimeMillis()
            )
        if (intentCache.size > config.cacheMaxSize) {
                evictStaleCacheEntries()
            }
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        parseTimeHistory.add(elapsed)
        if (parseTimeHistory.size > 1000) parseTimeHistory.removeAt(0)

        intentCounts.computeIfAbsent(bestAction) { AtomicInteger(0) }.incrementAndGet()
        intent
    }
        fun parseIntentBatch(texts: List<String>): List<ParsedIntent> {
        texts.map { parseIntent(it) }
    }
        fun tokenize(text: String): List<String> {
        return when (config.tokenizerType) {
            TokenizerType.WHITESPACE -> text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            TokenizerType.CHARACTER -> text.toList().map { it.toString() }.filter { it.isNotBlank() }
            TokenizerType.REGEX -> Regex("\\w+|\\p{Punct}+").findAll(text).map { it.value }.toList()
            TokenizerType.SMART -> smartTokenize(text)
        }
    }
        private fun smartTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val regex = Regex("""([a-zA-Z]+(?:'[a-zA-Z]+)?)|(\d+(?:\.\d+)?)|(\p{Punct})|([\p{Lo}]+)|(\s+)""")
        for (match in regex.findAll(text)) {
            val value = match.value
            if (value.isBlank()) continue
            if (value.length <= config.maxTokenLength) {
                tokens.add(value)
            } else {
                tokens.addAll(value.chunked(config.maxTokenLength))
            }
        }
        tokens
    }
        fun detectLanguage(text: String, tokens: List<String> = tokenize(text)): String {
        if (!config.enableLanguageDetection) return "en"
        var bestLang = "en"
        var bestScore = 0.0
        for (profile in languageProfiles) {
            val matches = tokens.count { profile.wordPattern.matches(it) }
        val stopWords = tokens.count { profile.stopWords.contains(it.lowercase()) }
        val commonWords = tokens.count { profile.commonWords.contains(it.lowercase()) }
        val score = (matches.toDouble() / max(tokens.size, 1)) * 0.5 +
                    (stopWords.toDouble() / max(tokens.size, 1)) * 0.3 +
                    (commonWords.toDouble() / max(tokens.size, 1)) * 0.2
            if (score > bestScore) {
                bestScore = score
                bestLang = profile.language
            }
        }
        bestLang
    }
        fun extractEntities(text: String): List<Entity> {
        if (!config.enableEntityExtraction) return emptyList()
        val entities = mutableListOf<Entity>()
        for (match in URL_PATTERN.findAll(text)) {
            entities.add(Entity("url", match.value, EntityType.URL, match.range.first, match.range.last + 1, 0.95))
        }
        for (match in EMAIL_PATTERN.findAll(text)) {
            entities.add(Entity("email", match.value, EntityType.EMAIL, match.range.first, match.range.last + 1, 0.95))
        }
        for (match in FILE_PATH_PATTERN.findAll(text)) {
            entities.add(Entity("path", match.value, EntityType.FILE_PATH, match.range.first, match.range.last + 1, 0.9))
        }
        val numberPattern = Regex("\\b(\\d+(?:\\.\\d+)?)\\b")
        for (match in numberPattern.findAll(text)) {
            entities.add(Entity("number", match.value, EntityType.NUMBER, match.range.first, match.range.last + 1, 0.85))
        }
        entities
    }
        private fun parseParameters(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (match in PARAMETER_PATTERN.findAll(text)) {
            val key = match.groupValues[1].lowercase()
        val value = match.groupValues[2].ifEmpty { "true" }
            params[key] = value
        }
        params
    }
        fun analyzeSentiment(text: String): SentimentScore {
        val positiveWords = setOf(
            "good", "great", "excellent", "amazing", "wonderful", "fantastic", "awesome",
            "happy", "love", "like", "best", "perfect", "beautiful", "nice", "cool",
            "yes", "please", "thanks", "thank", "helpful", "brilliant", "outstanding",
            "superior", "impressive", "delightful", "pleased", "satisfied", "welcome"
        )
        val negativeWords = setOf(
            "bad", "terrible", "awful", "horrible", "worst", "hate", "dislike",
            "ugly", "poor", "wrong", "broken", "error", "fail", "failed", "failure",
            "no", "not", "never", "stop", "quit", "annoying", "useless", "stupid",
            "terrible", "dreadful", "horrific", "dissatisfied", "angry", "frustrated"
        )
        val tokens = tokenize(text.lowercase())
        var positiveCount = 0
        var negativeCount = 0
        for (token in tokens) {
            if (token in positiveWords) positiveCount++
            if (token in negativeWords) negativeCount++
        }
        val total = tokens.size.toDouble()
        val score = if (total > 0) (positiveCount - negativeCount) / total else 0.0
        val magnitude = (positiveCount + negativeCount).toDouble() / max(total, 1.0)
        val label = when {
            score > 0.2 -> "positive"
            score < -0.2 -> "negative"
            else -> "neutral"
        }
        SentimentScore(score.coerceIn(-1.0, 1.0), magnitude.coerceIn(0.0, 1.0), label, magnitude)
    }
        fun calculateConfidence(action: String, target: String?, tokens: List<String>): Double {
        var confidence = 0.5
        if (target != null && target.isNotBlank()) confidence += 0.1
        if (action.length >= 3) confidence += 0.1
        val ratio = action.length.toDouble() / max(tokens.sumOf { it.length }, 1)
        if (ratio > 0.1) confidence += 0.1
        if (tokens.size >= 2) confidence += 0.1
        if (tokens.size <= 10) confidence += 0.1
        confidence.coerceIn(0.0, 1.0)
    }
        fun normalizeText(text: String): String {
        text.trim().lowercase().replace(Regex("\\s+"), " ")
    }
        fun getTokenizedResult(text: String): TokenizedResult {
        val tokens = tokenize(text)
        val unique = tokens.distinct()
        val avgLen = if (tokens.isNotEmpty()) tokens.sumOf { it.length }.toDouble() / tokens.size else 0.0
        val estimatedTokens = estimateTokens(text)
        val lang = detectLanguage(text, tokens)
        TokenizedResult(
            tokens = tokens,
            tokenCount = tokens.size,
            estimatedTokens = estimatedTokens,
            language = lang,
            averageTokenLength = avgLen,
            uniqueTokens = unique.size,
            compressionRatio = if (text.isNotEmpty()) text.length.toDouble() / max(tokens.size, 1) else 1.0
        )
    }
        fun estimateTokens(text: String): Int {
        val words = text.trim().split(Regex("\\s+")).size
        val avgTokenPerWord = 1.3
        (words * avgTokenPerWord).toInt().coerceAtLeast(1)
    }
        fun getCachedIntents(): List<ParsedIntent> = intentCache.values.map { it.intent }
        fun getIntentCacheSize(): Int = intentCache.size

    fun clearCache() {
        intentCache.clear()
    }
        fun getMetrics(): NLPMetrics {
        val totalCalls = cacheHits.get() + cacheMisses.get()
        val hitRate = if (totalCalls > 0) cacheHits.get().toDouble() / totalCalls else 0.0
        val sortedTimes = parseTimeHistory.sorted()
        val avgTime = if (sortedTimes.isNotEmpty()) sortedTimes.average() else 0.0
        val p95Idx = (sortedTimes.size * 0.95).toInt().coerceAtMost(sortedTimes.size - 1)
        val p95 = if (sortedTimes.isNotEmpty()) sortedTimes[p95Idx].toDouble() else 0.0
        val avgConf = getCachedIntents().let { intents ->
            if (intents.isNotEmpty()) intents.map { it.confidence }.average() else 0.0
        }
        NLPMetrics(
            totalParsed = totalParsed.get(),
            cacheHitRate = hitRate,
            averageParseTimeMs = avgTime,
            p95ParseTimeMs = p95,
            intentDistribution = intentCounts.entries.associate { it.key to it.value.get() },
            cacheSize = intentCache.size,
            totalTokensProcessed = totalTokens.get(),
            averageConfidence = avgConf
        )
    }
        private fun evictStaleCacheEntries() {
        val now = System.currentTimeMillis()
        val toRemove = intentCache.filter { (now - it.value.timestampMs) > config.cacheTtlMs }
        toRemove.keys.forEach { intentCache.remove(it) }
    }
        fun reset() {
        intentCache.clear()
        parseTimeHistory.clear()
        intentCounts.clear()
        totalParsed.set(0)
        totalTokens.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
    }
}
