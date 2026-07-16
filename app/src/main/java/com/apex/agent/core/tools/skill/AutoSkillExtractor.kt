package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.apex.agent.core.normal.export.ToolCallRecord

/**
 * 技能候选数据类
 * 表示从会话中提取的潜在可复用技能模? */
data class SkillCandidate(
    val pattern: List<String>,
    val frequency: Int,
    val confidence: Float,
    val sourceSessionId: String,
    val tools: List<String>,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pattern", JSONArray(pattern))
        put("frequency", frequency)
        put("confidence", confidence)
        put("sourceSessionId", sourceSessionId)
        put("tools", JSONArray(tools))
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SkillCandidate = SkillCandidate(
            pattern = json.getJSONArray("pattern").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            frequency = json.getInt("frequency"),
            confidence = json.getDouble("confidence").toFloat(),
            sourceSessionId = json.getString("sourceSessionId"),
            tools = json.getJSONArray("tools").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            createdAt = json.getLong("createdAt")
        )
    }
}

/**
 * 会话工具调用记录数据? * 用于记录会话期间的工具调用历史，以便模式提取
 */
data class SessionToolCallRecord(
    val toolName: String,
    val timestamp: Long,
    val parameters: Map<String, Any> = emptyMap(),
    val success: Boolean = true
)

/**
 * 自动技能提取引? * 分析工具调用序列，检测重复模式并生成技能候? */
class AutoSkillExtractor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AutoSkillExtractor"
        private const val MIN_PATTERN_LENGTH = 2
        private const val MAX_PATTERN_LENGTH = 10
        private const val MIN_FREQUENCY = 3
        private const val CONFIDENCE_THRESHOLD = 0.7f

        @Volatile
        private var INSTANCE: AutoSkillExtractor? = null

        fun getInstance(context: Context): AutoSkillExtractor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutoSkillExtractor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val candidatesFile: File by lazy {
        File(context.filesDir, "skill_candidates.json")
    }

    /**
     * 从会话中提取技能候?     * @param sessionId 会话ID
     * @param toolCallHistory 工具调用历史
     * @return 提取的技能候选列?     */
    suspend fun extractFromSession(
        sessionId: String,
        toolCallHistory: List<SessionToolCallRecord>
    ): List<SkillCandidate> = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Extracting skills from session: ${sessionId}, tool calls: ${toolCallHistory.size}")

        if (toolCallHistory.size < MIN_PATTERN_LENGTH * MIN_FREQUENCY) {
            AppLogger.d(TAG, "Not enough tool calls for pattern extraction")
            return@withContext emptyList()
        }

        val toolNames = toolCallHistory.map { it.toolName }
        val patterns = extractPatterns(toolNames)
        val candidates = patterns
            .filter { it.frequency >= MIN_FREQUENCY }
            .map { pattern ->
                val confidence = calculateConfidence(
                    pattern = pattern.sequence,
                    frequency = pattern.frequency,
                    toolCallHistory = toolCallHistory
                )
                SkillCandidate(
                    pattern = pattern.sequence,
                    frequency = pattern.frequency,
                    confidence = confidence,
                    sourceSessionId = sessionId,
                    tools = pattern.sequence.distinct(),
                    createdAt = System.currentTimeMillis()
                )
            }
            .filter { it.confidence >= CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.confidence }

        AppLogger.i(TAG, "Extracted ${candidates.size} skill candidates with confidence >= ${CONFIDENCE_THRESHOLD}")
        candidates
    }

    /**
     * 使用 n-gram 算法提取重复模式
     */
    private fun extractPatterns(toolNames: List<String>): List<PatternMatch> {
        val patternCounts = mutableMapOf<List<String>, Int>()

        // 提取不同长度?n-gram 模式
        for (n in MIN_PATTERN_LENGTH..MAX_PATTERN_LENGTH.coerceAtMost(toolNames.size)) {
            for (i in 0..toolNames.size - n) {
                val ngram = toolNames.subList(i, i + n)
                patternCounts[ngram] = (patternCounts[ngram] ?: 0) + 1
            }
        }

        // 过滤出重复出现的模式，并去除子模?        val frequentPatterns = patternCounts
            .filter { it.value >= MIN_FREQUENCY }
            .map { PatternMatch(it.key, it.value) }
            .sortedByDescending { it.sequence.size }

        // 去除被更长模式包含的子模?        return removeSubPatterns(frequentPatterns)
    }

    /**
     * 移除被更长模式包含的子模?     */
    private fun removeSubPatterns(patterns: List<PatternMatch>): List<PatternMatch> {
        val result = mutableListOf<PatternMatch>()

        for (pattern in patterns) {
            val isSubPattern = result.any { longer ->
                containsSubsequence(longer.sequence, pattern.sequence)
            }
            if (!isSubPattern) {
                result.add(pattern)
            }
        }

        return result
    }

    /**
     * 检?longer 是否包含 sub 作为子序?     */
    private fun containsSubsequence(longer: List<String>, sub: List<String>): Boolean {
        if (sub.size >= longer.size) return false

        for (i in 0..longer.size - sub.size) {
            if (longer.subList(i, i + sub.size) == sub) {
                return true
            }
        }
        return false
    }

    /**
     * 计算模式置信?     * 基于频率、一致性和通用?     */
    private fun calculateConfidence(
        pattern: List<String>,
        frequency: Int,
        toolCallHistory: List<SessionToolCallRecord>
    ): Float {
        // 频率分数：出现次数越多，分数越高（归一化到 0-1?        val frequencyScore = (frequency.toFloat() / MIN_FREQUENCY).coerceAtMost(1.0f)

        // 一致性分数：模式在历史中完整出现的比?        val consistencyScore = calculateConsistency(pattern, toolCallHistory)

        // 通用性分数：模式不依赖特定数据的程度
        val generalityScore = calculateGenerality(pattern, toolCallHistory)

        // 综合置信度：加权平均
        val confidence = frequencyScore * 0.4f + consistencyScore * 0.35f + generalityScore * 0.25f

        return confidence.coerceIn(0f, 1f)
    }

    /**
     * 计算一致性分?     * 模式在历史中完整出现的比?     */
    private fun calculateConsistency(pattern: List<String>, toolCallHistory: List<SessionToolCallRecord>): Float {
        val toolNames = toolCallHistory.map { it.toolName }
        var occurrences = 0

        for (i in 0..toolNames.size - pattern.size) {
            if (toolNames.subList(i, i + pattern.size) == pattern) {
                occurrences++
            }
        }

        // 一致?= 实际出现次数 / 理论最大出现次?        val maxPossible = (toolNames.size / pattern.size).coerceAtLeast(1)
        return (occurrences.toFloat() / maxPossible).coerceAtMost(1.0f)
    }

    /**
     * 计算通用性分?     * 基于模式中的工具多样性和参数独立?     */
    private fun calculateGenerality(pattern: List<String>, toolCallHistory: List<SessionToolCallRecord>): Float {
        // 工具多样性：模式中包含的不同工具数量
        val uniqueTools = pattern.distinct().size
        val diversityScore = (uniqueTools.toFloat() / pattern.size).coerceAtMost(1.0f)

        // 参数独立性：检查工具调用参数是否变化（简化版?        val patternOccurrences = findPatternOccurrences(pattern, toolCallHistory)
        val parameterVariation = if (patternOccurrences.size > 1) {
            // 如果同一模式出现多次，检查参数是否有变化
            val hasVariation = patternOccurrences.any { occ ->
                occ.any { it.parameters.isNotEmpty() }
            }
            if (hasVariation) 0.8f else 0.5f
        } else {
            0.5f
        }

        return (diversityScore * 0.6f + parameterVariation * 0.4f).coerceIn(0f, 1f)
    }

    /**
     * 查找模式在历史中的所有出现位?     */
    private fun findPatternOccurrences(
        pattern: List<String>,
        toolCallHistory: List<ToolCallRecord>
    ): List<List<ToolCallRecord>> {
        val occurrences = mutableListOf<List<ToolCallRecord>>()
        val toolNames = toolCallHistory.map { it.toolName }

        for (i in 0..toolNames.size - pattern.size) {
            if (toolNames.subList(i, i + pattern.size) == pattern) {
                occurrences.add(toolCallHistory.subList(i, i + pattern.size))
            }
        }

        return occurrences
    }

    /**
     * 保存技能候选到文件
     */
    suspend fun saveCandidates(candidates: List<SkillCandidate>) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            candidates.forEach { jsonArray.put(it.toJson()) }
            candidatesFile.writeText(jsonArray.toString(2))
            AppLogger.i(TAG, "Saved ${candidates.size} skill candidates to file")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save skill candidates", e)
        }
    }

    /**
     * 加载已保存的技能候?     */
    suspend fun loadCandidates(): List<SkillCandidate> = withContext(Dispatchers.IO) {
        try {
            if (!candidatesFile.exists()) {
                return@withContext emptyList()
            }

            val json = candidatesFile.readText()
            val jsonArray = JSONArray(json)
            val candidates = (0 until jsonArray.length()).map {
                SkillCandidate.fromJson(jsonArray.getJSONObject(it))
            }
            AppLogger.d(TAG, "Loaded ${candidates.size} skill candidates from file")
            candidates
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load skill candidates", e)
            emptyList()
        }
    }

    /**
     * 添加新的技能候?     */
    suspend fun addCandidate(candidate: SkillCandidate) = withContext(Dispatchers.IO) {
        val existing = loadCandidates().toMutableList()
        existing.add(candidate)
        saveCandidates(existing)
    }

    /**
     * 移除指定的技能候?     */
    suspend fun removeCandidate(index: Int) = withContext(Dispatchers.IO) {
        val existing = loadCandidates().toMutableList()
        if (index in existing.indices) {
            existing.removeAt(index)
            saveCandidates(existing)
        }
    }

    /**
     * 清空所有技能候?     */
    suspend fun clearCandidates() = withContext(Dispatchers.IO) {
        try {
            if (candidatesFile.exists()) {
                candidatesFile.delete()
            }
            AppLogger.i(TAG, "Cleared all skill candidates")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear skill candidates", e)
        }
    }

    private data class PatternMatch(
        val sequence: List<String>,
        val frequency: Int
    )
}
