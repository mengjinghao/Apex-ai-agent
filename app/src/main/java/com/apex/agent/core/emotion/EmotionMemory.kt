package com.apex.agent.core.emotion

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EmotionMemory(private val context: Context) {

    private val TAG = "EmotionMemory"

    private val memoryDir: File
        get() = File(context.filesDir, "emotion_memory").also {
            if (!it.exists()) it.mkdirs()
        }

    data class EmotionMemoryEntry(
        val id: String,
        val timestamp: Long,
        val emotionProfile: EnhancedEmotionAnalyzer.DetailedEmotionProfile,
        val context: String,
        val userMessage: String,
        val aiResponse: String?,
        val conversationId: String
    )

    data class EmotionPatternSummary(
        val timeRange: String,
        val dominantEmotion: EnhancedEmotionAnalyzer.EmotionCategory,
        val emotionDistribution: Map<EnhancedEmotionAnalyzer.EmotionCategory, Float>,
        val averageIntensity: Float,
        val keyTriggers: List<String>,
        val emotionalTrend: EnhancedEmotionAnalyzer.EmotionDynamics,
        val notableEvents: List<String>
    )

    data class LongTermEmotionProfile(
        val userId: String,
        val baselineEmotion: EnhancedEmotionAnalyzer.EmotionCategory,
        val typicalIntensities: Map<EnhancedEmotionAnalyzer.EmotionCategory, Float>,
        val commonTriggers: List<String>,
        val emotionalRhythms: Map<String, Float>,
        val responsePatterns: Map<String, Any>,
        val growthIndicators: Map<String, Float>,
        val lastUpdated: Long
    )

    suspend fun saveEmotionEntry(entry: EmotionMemoryEntry): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(memoryDir, "${entry.conversationId}_${entry.id}.json")
            val json = JSONObject().apply {
                put("id", entry.id)
                put("timestamp", entry.timestamp)
                put("context", entry.context)
                put("userMessage", entry.userMessage)
                put("aiResponse", entry.aiResponse)
                put("conversationId", entry.conversationId)

                put("emotionProfile", JSONObject().apply {
                    put("primaryEmotion", entry.emotionProfile.primaryEmotion.name)
                    put("secondaryEmotion", entry.emotionProfile.secondaryEmotion?.name)
                    put("intensityScore", entry.emotionProfile.intensityScore.toDouble())
                    put("emotionDynamics", entry.emotionProfile.emotionDynamics.name)
                    put("sarcasmDetected", entry.emotionProfile.sarcasmDetected)
                    put("mixedEmotions", entry.emotionProfile.mixedEmotions)
                    put("confidence", entry.emotionProfile.confidence.toDouble())

                    put("emotionalTriggers", JSONArray(entry.emotionProfile.emotionalTriggers))
                    put("hiddenSentiments", JSONArray(entry.emotionProfile.hiddenSentiments))

                    put("contextFactors", JSONObject().apply {
                        entry.emotionProfile.contextFactors.forEach { (key, value) ->
                            put(key, value.toDouble())
                        }
                    })
                })
            }

            file.writeText(json.toString(2))
            AppLogger.d(TAG, "情感记忆已保�? ${file.name}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存情感记忆失败", e)
            false
        }
    }

    suspend fun loadRecentEntries(conversationId: String, limit: Int = 20): List<EmotionMemoryEntry> =
        withContext(Dispatchers.IO) {
            try {
                val files = memoryDir.listFiles { file ->
                    file.name.startsWith("${conversationId}_") && file.name.endsWith(".json")
                }?.sortedByDescending { it.lastModified() }?.take(limit) ?: emptyList()

                files.mapNotNull { file ->
                    parseEntry(file)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载情感记忆失败", e)
                emptyList()
            }
        }

    private fun parseEntry(file: File): EmotionMemoryEntry? {
        return try {
            val json = JSONObject(file.readText())

            val emotionJson = json.getJSONObject("emotionProfile")

            val emotionProfile = EnhancedEmotionAnalyzer.DetailedEmotionProfile().apply {
                primaryEmotion = EnhancedEmotionAnalyzer.EmotionCategory.valueOf(
                    emotionJson.getString("primaryEmotion")
                )
                secondaryEmotion = emotionJson.optString("secondaryEmotion")?.takeIf { it.isNotEmpty() }
                    ?.let { EnhancedEmotionAnalyzer.EmotionCategory.valueOf(it) }
                intensityScore = emotionJson.getDouble("intensityScore").toFloat()
                emotionDynamics = EnhancedEmotionAnalyzer.EmotionDynamics.valueOf(
                    emotionJson.getString("emotionDynamics")
                )
                sarcasmDetected = emotionJson.getBoolean("sarcasmDetected")
                mixedEmotions = emotionJson.getBoolean("mixedEmotions")
                confidence = emotionJson.getDouble("confidence").toFloat()

                emotionalTriggers = (0 until emotionJson.getJSONArray("emotionalTriggers").length())
                    .map { emotionJson.getJSONArray("emotionalTriggers").getString(it) }

                hiddenSentiments = (0 until emotionJson.getJSONArray("hiddenSentiments").length())
                    .map { emotionJson.getJSONArray("hiddenSentiments").getString(it) }

                contextFactors = mutableMapOf<String, Float>().apply {
                    val factorsJson = emotionJson.optJSONObject("contextFactors")
                    factorsJson?.let {
                        it.keys().forEach { key ->
                            put(key, it.getDouble(key).toFloat())
                        }
                    }
                }
            }

            EmotionMemoryEntry(
                id = json.getString("id"),
                timestamp = json.getLong("timestamp"),
                emotionProfile = emotionProfile,
                context = json.getString("context"),
                userMessage = json.getString("userMessage"),
                aiResponse = json.optString("aiResponse")?.takeIf { it.isNotEmpty() },
                conversationId = json.getString("conversationId")
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析情感记忆条目失败: ${file.name}", e)
            null
        }
    }

    suspend fun generatePatternSummary(
        conversationId: String,
        daysBack: Int = 7
    ): EmotionPatternSummary = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysBack * 24 * 60 * 60 * 1000L)

        val entries = memoryDir.listFiles { file ->
            file.name.startsWith("${conversationId}_") && file.name.endsWith(".json")
        }?.mapNotNull { parseEntry(it) }
            ?.filter { it.timestamp >= cutoffTime } ?: emptyList()

        if (entries.isEmpty()) {
            return@withContext EmotionPatternSummary(
                timeRange = "${daysBack}�?,
                dominantEmotion = EnhancedEmotionAnalyzer.EmotionCategory.NEUTRAL,
                emotionDistribution = emptyMap(),
                averageIntensity = 0f,
                keyTriggers = emptyList(),
                emotionalTrend = EnhancedEmotionAnalyzer.EmotionDynamics.STABLE,
                notableEvents = emptyList()
            )
        }

        val emotionCounts = entries.groupingBy { it.emotionProfile.primaryEmotion }
            .eachCount()

        val total = emotionCounts.values.sum().toFloat()
        val emotionDistribution = emotionCounts.mapValues { (_, count) -> count / total }

        val dominantEmotion = emotionDistribution.maxByOrNull { it.value }?.key
            ?: EnhancedEmotionAnalyzer.EmotionCategory.NEUTRAL

        val averageIntensity = entries.map { it.emotionProfile.intensityScore }.average().toFloat()

        val allTriggers = entries.flatMap { it.emotionProfile.emotionalTriggers }
        val triggerCounts = allTriggers.groupingBy { it }.eachCount()
        val keyTriggers = triggerCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        val emotionalTrend = if (entries.size >= 3) {
            val recent = entries.takeLast(entries.size / 2)
            val earlier = entries.take(entries.size / 2)
            val recentAvg = recent.map { it.emotionProfile.intensityScore }.average()
            val earlierAvg = earlier.map { it.emotionProfile.intensityScore }.average()

            when {
                recentAvg > earlierAvg * 1.2 -> EnhancedEmotionAnalyzer.EmotionDynamics.IMPROVING
                recentAvg < earlierAvg * 0.8 -> EnhancedEmotionAnalyzer.EmotionDynamics.DETERIORATING
                else -> EnhancedEmotionAnalyzer.EmotionDynamics.STABLE
            }
        } else {
            EnhancedEmotionAnalyzer.EmotionDynamics.STABLE
        }

        val notableEvents = entries.filter {
            it.emotionProfile.intensityScore > 0.7f ||
            it.emotionProfile.sarcasmDetected ||
            it.emotionProfile.mixedEmotions
        }.map { entry ->
            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val time = dateFormat.format(Date(entry.timestamp))
            val emotion = entry.emotionProfile.primaryEmotion.displayName
            "${time}: �?{emotion情绪波动}"
        }

        EmotionPatternSummary(
            timeRange = "${daysBack}�?,
            dominantEmotion = dominantEmotion,
            emotionDistribution = emotionDistribution,
            averageIntensity = averageIntensity,
            keyTriggers = keyTriggers,
            emotionalTrend = emotionalTrend,
            notableEvents = notableEvents
        )
    }

    suspend fun buildLongTermProfile(userId: String, lookbackDays: Int = 30): LongTermEmotionProfile =
        withContext(Dispatchers.IO) {
            val cutoffTime = System.currentTimeMillis() - (lookbackDays * 24 * 60 * 60 * 1000L)

            val allFiles = memoryDir.listFiles { file ->
                file.name.endsWith(".json")
            }?.filter { it.lastModified() >= cutoffTime } ?: emptyList()

            val entries = allFiles.mapNotNull { parseEntry(it) }

            if (entries.isEmpty()) {
                return@withContext LongTermEmotionProfile(
                    userId = userId,
                    baselineEmotion = EnhancedEmotionAnalyzer.EmotionCategory.NEUTRAL,
                    typicalIntensities = emptyMap(),
                    commonTriggers = emptyList(),
                    emotionalRhythms = emptyMap(),
                    responsePatterns = emptyMap(),
                    growthIndicators = emptyMap(),
                    lastUpdated = System.currentTimeMillis()
                )
            }

            val emotionCounts = entries.groupingBy { it.emotionProfile.primaryEmotion }
                .eachCount()
            val total = emotionCounts.values.sum().toFloat()
            val baselineEmotion = emotionCounts.maxByOrNull { it.value }?.key
                ?: EnhancedEmotionAnalyzer.EmotionCategory.NEUTRAL

            val typicalIntensities = entries.groupBy { it.emotionProfile.primaryEmotion }
                .mapValues { (_, entries) ->
                    entries.map { it.emotionProfile.intensityScore }.average().toFloat()
                }

            val allTriggers = entries.flatMap { it.emotionProfile.emotionalTriggers }
            val commonTriggers = allTriggers.groupingBy { it }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .take(5)
                .map { it.key }

            val emotionalRhythms = analyzeEmotionalRhythms(entries)

            val responsePatterns = analyzeResponsePatterns(entries)

            val growthIndicators = analyzeGrowthIndicators(entries)

            LongTermEmotionProfile(
                userId = userId,
                baselineEmotion = baselineEmotion,
                typicalIntensities = typicalIntensities,
                commonTriggers = commonTriggers,
                emotionalRhythms = emotionalRhythms,
                responsePatterns = responsePatterns,
                growthIndicators = growthIndicators,
                lastUpdated = System.currentTimeMillis()
            )
        }

    private fun analyzeEmotionalRhythms(entries: List<EmotionMemoryEntry>): Map<String, Float> {
        val hourEmotions = mutableMapOf<Int, MutableList<Float>>()

        entries.forEach { entry ->
            val calendar = Calendar.getInstance().apply {
                timeInMillis = entry.timestamp
            }
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hourEmotions.getOrPut(hour) { mutableListOf() }
                .add(entry.emotionProfile.intensityScore)
        }

        return hourEmotions.mapValues { (_, scores) ->
            scores.average().toFloat()
        }.mapKeys { (hour, _) ->
            when (hour) {
                in 6..11 -> "上午"
                in 12..13 -> "中午"
                in 14..17 -> "下午"
                in 18..21 -> "晚上"
                else -> "深夜"
            }
        }.mapValues { (_, scores) ->
            scores.values.average().toFloat()
        }
    }

    private fun analyzeResponsePatterns(entries: List<EmotionMemoryEntry>): Map<String, Any> {
        val sarcasmCount = entries.count { it.emotionProfile.sarcasmDetected }
        val mixedEmotionCount = entries.count { it.emotionProfile.mixedEmotions }

        val hiddenSentiments = entries.flatMap { it.emotionProfile.hiddenSentiments }
            .groupingBy { it }.eachCount()

        return mapOf(
            "sarcasmFrequency" to (sarcasmCount.toFloat() / entries.size.coerceAtLeast(1)),
            "mixedEmotionFrequency" to (mixedEmotionCount.toFloat() / entries.size.coerceAtLeast(1)),
            "commonHiddenSentiments" to hiddenSentiments.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
        )
    }

    private fun analyzeGrowthIndicators(entries: List<EmotionMemoryEntry>): Map<String, Float> {
        if (entries.size < 10) return emptyMap()

        val sortedEntries = entries.sortedBy { it.timestamp }
        val halfPoint = sortedEntries.size / 2

        val earlyEntries = sortedEntries.take(halfPoint)
        val lateEntries = sortedEntries.takeLast(halfPoint)

        val earlyPositive = earlyEntries.count {
            it.emotionProfile.primaryEmotion.isPositive == true
        }.toFloat() / earlyEntries.size.coerceAtLeast(1)

        val latePositive = lateEntries.count {
            it.emotionProfile.primaryEmotion.isPositive == true
        }.toFloat() / lateEntries.size.coerceAtLeast(1)

        val earlyIntensity = earlyEntries.map { it.emotionProfile.intensityScore }.average().toFloat()
        val lateIntensity = lateEntries.map { it.emotionProfile.intensityScore }.average().toFloat()

        return mapOf(
            "positiveEmotionGrowth" to (latePositive - earlyPositive),
            "intensityChange" to (lateIntensity - earlyIntensity)
        )
    }

    suspend fun clearOldEntries(daysToKeep: Int = 60): Int = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        val filesToDelete = memoryDir.listFiles { file ->
            file.lastModified() < cutoffTime
        } ?: emptyArray()

        filesToDelete.forEach { it.delete() }

        AppLogger.d(TAG, "清理�?${filesToDelete.size} 个过期情感记忆条�?)
        filesToDelete.size
    }

    suspend fun exportMemory(conversationId: String): String = withContext(Dispatchers.IO) {
        val entries = loadRecentEntries(conversationId, 100)
        val summary = generatePatternSummary(conversationId, 30)

        buildString {
            appendLine("=== 情感记忆导出 ===")
            appendLine()
            appendLine("【总体趋势�?)
            appendLine("时间范围: ${summary.timeRange}")
            appendLine("主导情绪: ${summary.dominantEmotion.displayName}")
            appendLine("平均强度: ${String.format("%.1f", summary.averageIntensity * 100)}%")
            appendLine("情绪动�? ${summary.emotionalTrend.name}")
            appendLine()

            if (summary.keyTriggers.isNotEmpty()) {
                appendLine("【主要触发因素�?)
                summary.keyTriggers.forEach { appendLine("  - ${it}") }
                appendLine()
            }

            if (summary.notableEvents.isNotEmpty()) {
                appendLine("【重要事件�?)
                summary.notableEvents.forEach { appendLine("  - ${it}") }
                appendLine()
            }

            appendLine("【详细记录�?)
            entries.takeLast(10).forEach { entry ->
                val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                val time = dateFormat.format(Date(entry.timestamp))
                appendLine("[${time}] ${entry.emotionProfile.primaryEmotion.displayName} (${String.format("%.0f", entry.emotionProfile.intensityScore * 100)}%)")
                appendLine("  用户: ${entry.userMessage.take(50)}...")
            }
        }
    }
}