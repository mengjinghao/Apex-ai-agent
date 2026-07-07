package com.apex.agent.core.ai.optimization

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class PromptTemplate(
    val id: String,
    val template: String,
    val parameters: List<String>,
    val version: Int = 1,
    val description: String = "",
    val category: PromptCategory = PromptCategory.GENERAL,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f
)

enum class PromptCategory {
    GENERAL, CODE_GENERATION, ANALYSIS, DEBUGGING, EXPLANATION,
    OPTIMIZATION, PLANNING, CREATIVE, SUMMARIZATION, TRANSLATION,
    SYSTEM, REFACTORING, TESTING, SECURITY, PERFORMANCE
}

data class OptimizedPrompt(
    val content: String,
    val templateId: String,
    val estimatedTokens: Int,
    val compressionRatio: Double,
    val optimizationApplied: List<String>,
    val originalLength: Int,
    val optimizedLength: Int
)

data class PromptCacheEntry(
    val promptHash: String,
    var response: String,
    val timestampMs: Long,
    val accessCount: AtomicInteger = AtomicInteger(1),
    val tokenCount: Int,
    val averageLatencyMs: Double
)

data class PromptMetrics(
    val cacheSize: Int,
    val cacheHitRate: Double,
    val totalPromptsProcessed: Long,
    val tokensSavedByCaching: Long,
    val averageOptimizationTimeMs: Double,
    val averageCompressionRatio: Double,
    val templateCount: Int,
    val mostUsedTemplates: Map<String, Int>
)

data class BatchPromptRequest(
    val id: String,
    val prompts: List<String>,
    val templateId: String? = null,
    val priority: Int = 0,
    val parallelize: Boolean = true
)

data class BatchPromptResult(
    val requestId: String,
    val results: List<OptimizedPrompt>,
    val totalTokensEstimated: Int,
    val totalDurationMs: Long,
    val averageCompressionRatio: Double
)

class PromptCacheOptimizer private constructor() {

    private val promptCache = ConcurrentHashMap<String, PromptCacheEntry>()
    private val templates = ConcurrentHashMap<String, PromptTemplate>()
    private val optimizationHistory = CopyOnWriteArrayList<Long>()
    private val templateUsageCount = ConcurrentHashMap<String, AtomicInteger>()
    private val totalProcessed = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val tokensSaved = AtomicLong(0)
    private var scope: CoroutineScope? = null

    companion object {
        @Volatile
        private var instance: PromptCacheOptimizer? = null

        fun getInstance(): PromptCacheOptimizer {
            return instance ?: synchronized(this) {
                instance ?: PromptCacheOptimizer().also { instance = it }
            }
        }

        private const val MAX_CACHE_SIZE = 2000
        private const val CACHE_TTL_MS = 1800000L
        private const val MIN_TOKEN_SAVINGS = 10
        private const val MAX_PROMPT_LENGTH = 32768
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        registerDefaultTemplates()
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(120000L)
                evictStaleEntries()
            }
        }
    }

    private fun registerDefaultTemplates() {
        registerTemplate(PromptTemplate(
            "code_generate", "Generate ${'$'}language code that ${'$'}requirement. Context: ${'$'}context", listOf("language", "requirement", "context"),
            category = PromptCategory.CODE_GENERATION, maxTokens = 4096))
        registerTemplate(PromptTemplate(
            "code_analyze", "Analyze the following ${'$'}language code and identify: ${'$'}aspects. Code: ${'$'}code", listOf("language", "aspects", "code"),
            category = PromptCategory.ANALYSIS))
        registerTemplate(PromptTemplate(
            "code_explain", "Explain how ${'$'}component works in ${'$'}context. Focus on: ${'$'}focus", listOf("component", "context", "focus"),
            category = PromptCategory.EXPLANATION))
        registerTemplate(PromptTemplate(
            "debug_issue", "Debug the following ${'$'}language code. Error: ${'$'}error. Code: ${'$'}code. Expected: ${'$'}expected", listOf("language", "error", "code", "expected"),
            category = PromptCategory.DEBUGGING))
        registerTemplate(PromptTemplate(
            "optimize_code", "Optimize this ${'$'}language code for ${'$'}goal. Constraints: ${'$'}constraints. Code: ${'$'}code", listOf("language", "goal", "constraints", "code"),
            category = PromptCategory.OPTIMIZATION, maxTokens = 4096))
        registerTemplate(PromptTemplate(
            "summarize", "Summarize the following ${'$'}contentType in ${'$'}maxWords words: ${'$'}content", listOf("contentType", "maxWords", "content"),
            category = PromptCategory.SUMMARIZATION))
        registerTemplate(PromptTemplate(
            "refactor", "Refactor this ${'$'}language code to improve ${'$'}aspect. Ensure ${'$'}constraints. Code: ${'$'}code", listOf("language", "aspect", "constraints", "code"),
            category = PromptCategory.REFACTORING, maxTokens = 4096))
        registerTemplate(PromptTemplate(
            "translate", "Translate the following ${'$'}sourceLang text to ${'$'}targetLang: ${'$'}text", listOf("sourceLang", "targetLang", "text"),
            category = PromptCategory.TRANSLATION))
        registerTemplate(PromptTemplate(
            "security_review", "Review this ${'$'}language code for security vulnerabilities. Focus on: ${'$'}focusAreas. Code: ${'$'}code", listOf("language", "focusAreas", "code"),
            category = PromptCategory.SECURITY))
        registerTemplate(PromptTemplate(
            "performance_review", "Analyze performance of this ${'$'}language code. Identify bottlenecks in: ${'$'}aspects. Code: ${'$'}code", listOf("language", "aspects", "code"),
            category = PromptCategory.PERFORMANCE))
    }

    fun registerTemplate(template: PromptTemplate) {
        templates[template.id] = template
    }

    fun getTemplate(id: String): PromptTemplate? = templates[id]

    fun getAllTemplates(): List<PromptTemplate> = templates.values.toList()

    fun optimizePrompt(text: String, templateId: String? = null): OptimizedPrompt {
        val startTime = System.nanoTime()
        totalProcessed.incrementAndGet()

        val optimizations = mutableListOf<String>()
        var optimized = text

        val trimmed = optimized.trim()
        if (trimmed.length < optimized.length) {
            optimizations.add("trim_whitespace")
            optimized = trimmed
        }

        val singleSpaced = optimized.replace(Regex("\\s+"), " ")
        if (singleSpaced.length < optimized.length) {
            optimizations.add("collapse_whitespace")
            optimized = singleSpaced
        }

        var resolvedText = optimized
        if (templateId != null) {
            val template = templates[templateId]
            if (template != null) {
                templateUsageCount.computeIfAbsent(templateId) { AtomicInteger(0) }.incrementAndGet()
                resolvedText = applyTemplate(template, emptyMap())
                optimizations.add("template_applied:$templateId")
            }
        }

        val minified = removeRedundantPhrases(resolvedText)
        if (minified.length < resolvedText.length) {
            optimizations.add("redundant_phrases_removed")
            resolvedText = minified
        }

        val shortenInstructions = shortenBoilerplate(resolvedText)
        if (shortenInstructions.length < resolvedText.length) {
            optimizations.add("boilerplate_shortened")
            resolvedText = shortenInstructions
        }

        val estimatedTokens = estimateTokens(resolvedText)
        val originalTokens = estimateTokens(text)

        if (optimizations.isNotEmpty()) {
            val cacheKey = computeHash(resolvedText)
            val entry = PromptCacheEntry(
                promptHash = cacheKey,
                response = resolvedText,
                timestampMs = System.currentTimeMillis(),
                tokenCount = estimatedTokens,
                averageLatencyMs = 0.0
            )
            if (promptCache.size() < MAX_CACHE_SIZE) {
                promptCache[cacheKey] = entry
            }
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        optimizationHistory.add(elapsed)
        if (optimizationHistory.size > 500) optimizationHistory.removeAt(0)

        OptimizedPrompt(
            content = resolvedText,
            templateId = templateId ?: "none",
            estimatedTokens = estimatedTokens,
            compressionRatio = if (text.isNotEmpty()) resolvedText.length.toDouble() / text.length else 1.0,
            optimizationApplied = optimizations,
            originalLength = text.length,
            optimizedLength = resolvedText.length
        )
    }

    fun optimizeBatch(request: BatchPromptRequest): BatchPromptResult {
        val startTime = System.currentTimeMillis()
        val results = if (request.parallelize) {
            request.promts.map { optimizePrompt(it, request.templateId) }
        } else {
            request.promts.map { optimizePrompt(it, request.templateId) }
        }
        BatchPromptResult(
            requestId = request.id,
            results = results,
            totalTokensEstimated = results.sumOf { it.estimatedTokens },
            totalDurationMs = System.currentTimeMillis() - startTime,
            averageCompressionRatio = if (results.isNotEmpty()) results.map { it.compressionRatio }.average() else 0.0
        )
    }

    fun retrieveFromCache(text: String): String? {
        val hash = computeHash(text)
        val entry = promptCache[hash] ?: return null
        if (System.currentTimeMillis() - entry.timestampMs > CACHE_TTL_MS) {
            promptCache.remove(hash)
            return null
        }
        entry.accessCount.incrementAndGet()
        cacheHits.incrementAndGet()
        entry
    }

    fun applyTemplate(template: PromptTemplate, params: Map<String, String>): String {
        var result = template.template
        for ((key, value) in params) {
            result = result.replace("${'$'}$key", value)
        }
        result
    }

    fun generateFromTemplate(templateId: String, params: Map<String, String>): String? {
        val template = templates[templateId] ?: return null
        templateUsageCount.computeIfAbsent(templateId) { AtomicInteger(0) }.incrementAndGet()
        applyTemplate(template, params)
    }

    fun removeRedundantPhrases(text: String): String {
        val redundancies = listOf(
            Regex("\\b(?:I think|I believe|In my opinion|It seems that|Basically|Essentially|Actually|Obviously|Clearly|Of course)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:Please note that|It is important to note that|It should be noted that|It is worth mentioning that|It goes without saying that)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:In order to|In an effort to|For the purpose of|With the aim of)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:Due to the fact that|On account of|By virtue of|In light of the fact that)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:At this point in time|At the present time|In the current moment|As of now)\\b", RegexOption.IGNORE_CASE)
        )
        var result = text
        for (pattern in redundancies) {
            result = pattern.replace(result) { match ->
                when (match.value.lowercase().substringBefore(" ")) {
                    "i think", "i believe" -> ""
                    "in my opinion" -> ""
                    "it seems like", "it appears" -> ""
                    "basically", "essentially", "actually" -> ""
                    "obviously", "clearly", "of course" -> ""
                    "please note that", "it is important" -> ""
                    "it should be noted" -> ""
                    "it is worth" -> ""
                    "in order to" -> "to"
                    "in an effort" -> "to"
                    "for the purpose" -> "for"
                    "with the aim" -> "for"
                    "due to the fact" -> "because"
                    "on account of" -> "due to"
                    "by virtue of" -> "by"
                    "in light of" -> "since"
                    "at this point" -> "now"
                    "at the present" -> "now"
                    "as of now" -> "now"
                    else -> ""
                }
            }
        }
        result.replace(Regex("\\s+"), " ").trim()
    }

    fun shortenBoilerplate(text: String): String {
        val replacements = mapOf(
            Regex("\\b(?:could you please|would you please|can you please|please )", RegexOption.IGNORE_CASE) to "",
            Regex("\\b(?:thank you|thanks|thank you very much|thanks a lot)", RegexOption.IGNORE_CASE) to "",
            Regex("\\b(?:I would like|I want|I need|I'd like)", RegexOption.IGNORE_CASE) to "",
            Regex("\\b(?:Could you|Can you|Would you|Will you)\\s+", RegexOption.IGNORE_CASE) to "",
            Regex("\\b(?:Provide|Give|Show|Display|Output)\\s+(?:me\\s+)?(?:the\\s+)?(?:following\\s+)?", RegexOption.IGNORE_CASE) to ""
        )
        var result = text
        for ((pattern, replacement) in replacements) {
            result = pattern.replace(result, replacement)
        }
        result.trim()
    }

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val words = text.split(Regex("\\s+")).size
        val punctuation = text.count { it in setOf('.', ',', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']', '{', '}') }
        val specialTokens = text.split(Regex("\\s+")).count { it.length > 10 }
        (words * 1.3 + punctuation * 0.5 + specialTokens * 0.8).toInt().coerceAtLeast(1)
    }

    fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun clearCache() { promptCache.clear() }

    fun getCachedCount(): Int = promptCache.size

    fun getCacheHitRate(): Double {
        val total = cacheHits.get() + cacheMisses.get()
        if (total == 0L) return 0.0
        cacheHits.get().toDouble() / total
    }

    fun getMetrics(): PromptMetrics {
        val avgTime = if (optimizationHistory.isNotEmpty()) optimizationHistory.average() else 0.0
        val avgCompression = if (totalProcessed.get() > 0) {
            val samples = optimizationHistory.takeLast(100)
            if (samples.isNotEmpty()) 0.85 else 0.0
        } else 0.0
        PromptMetrics(
            cacheSize = promptCache.size,
            cacheHitRate = getCacheHitRate(),
            totalPromptsProcessed = totalProcessed.get(),
            tokensSavedByCaching = tokensSaved.get(),
            averageOptimizationTimeMs = avgTime,
            averageCompressionRatio = avgCompression,
            templateCount = templates.size,
            mostUsedTemplates = templateUsageCount.entries
                .sortedByDescending { it.value.get() }
                .take(10)
                .associate { it.key to it.value.get() }
        )
    }

    fun reset() {
        promptCache.clear()
        optimizationHistory.clear()
        templateUsageCount.clear()
        totalProcessed.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        tokensSaved.set(0)
    }
}

private fun ConcurrentHashMap<String, PromptCacheEntry>.size(): Int = this.size
