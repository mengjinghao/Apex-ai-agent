package com.apex.plugins.skill

import android.content.Context
import com.apex.core.tools.skill.SkillUsageTracker
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

class SkillUsageAnalyzer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillUsageAnalyzer"

        @Volatile private var INSTANCE: SkillUsageAnalyzer? = null

        fun getInstance(context: Context): SkillUsageAnalyzer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillUsageAnalyzer(context.applicationContext).also { INSTANCE = it }
            }
        }
        private const val PATTERN_SEQUENCE_LENGTH = 5
        private const val RECENCY_WEIGHT = 0.3
        private const val FREQUENCY_WEIGHT = 0.4
        private const val CONSISTENCY_WEIGHT = 0.3
    }
        private val usageTracker by lazy { SkillUsageTracker.getInstance(context) }
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    data class UsagePattern(
        val skillName: String,
        val patternType: PatternType,
        val confidence: Double,
        val description: String,
        val recommendations: List<String>
    )

    enum class PatternType {
        DAILY,
        WEEKLY,
        WEEKEND,
        WORKING_HOURS,
        LEARNING_CURVE,
        DECLINING_USAGE,
        EMERGING,
        CONSISTENT,
        SEASONAL
    }

    data class UserBehaviorProfile(
        val userId: String,
        val preferredSkills: List<String>,
        val skillAffinityScores: Map<String, Double>,
        val frequentTimeSlots: List<TimeSlot>,
        val averageSessionDuration: Long,
        val skillSuccessRates: Map<String, Double>,
        val learningProgression: Map<String, Int>,
        val interests: List<String>
    )

    data class TimeSlot(
        val hour: Int,
        val dayOfWeek: DayOfWeek,
        val frequency: Double
    )

    data class SkillRecommendation(
        val skillName: String,
        val score: Double,
        val reason: String,
        val source: RecommendationSource
    )

    enum class RecommendationSource {
        USAGE_BASED,
        HISTORY_BASED,
        COLLABORATIVE,
        TRENDING,
        SIMILAR_USER
    }

    data class PersonalizedSettings(
        val skillAutoLoad: Boolean,
        val showUsageStats: Boolean,
        val enableRecommendations: Boolean,
        val recommendationTypes: List<RecommendationSource>,
        val notificationFrequency: NotificationFrequency
    )

    enum class NotificationFrequency {
        NEVER,
        DAILY,
        WEEKLY
    }
        fun analyzeUsagePatterns(skillName: String): List<UsagePattern> {
        val patterns = mutableListOf<UsagePattern>()
        val usageData = usageTracker.getSkillUsageData(skillName) ?: return patterns

        val dailyStats = usageTracker.getDailyStats()
        val last7Days = getLast7DaysData(dailyStats)
        val last30Days = getLast30DaysData(dailyStats)
        if (last7Days.isNotEmpty()) {
            val avgDaily = last7Days.map { it.totalInvocations }.average()
        val variance = calculateVariance(last7Days.map { it.totalInvocations.toDouble() })
        if (variance < 0.5 && avgDaily > 2) {
                patterns.add(
                    UsagePattern(
                        skillName = skillName,
                        patternType = PatternType.CONSISTENT,
                        confidence = 0.9,
                        description = "Usage is consistent with ~${avgDaily.toInt()} invocations per day",
                        recommendations = listOf("Maintain current usage pattern", "Consider exploring related skills")
                    )
                )
            }
        if (isTrendingUp(last7Days.map { it.totalInvocations })) {
                patterns.add(
                    UsagePattern(
                        skillName = skillName,
                        patternType = PatternType.EMERGING,
                        confidence = 0.85,
                        description = "Usage is increasing over the past week",
                        recommendations = listOf("This skill is becoming more useful to you", "Consider enabling auto-load")
                    )
                )
            }
        if (isTrendingDown(last7Days.map { it.totalInvocations })) {
                patterns.add(
                    UsagePattern(
                        skillName = skillName,
                        patternType = PatternType.DECLINING_USAGE,
                        confidence = 0.8,
                        description = "Usage has been declining recently",
                        recommendations = listOf("Skill may need updating", "Consider alternatives")
                    )
                )
            }
        }
        val weekendUsage = calculateWeekendVsWeekdayRatio(dailyStats)
        if (weekendUsage > 1.5) {
            patterns.add(
                UsagePattern(
                    skillName = skillName,
                    patternType = PatternType.WEEKEND,
                    confidence = 0.75,
                    description = "Used more frequently on weekends",
                    recommendations = listOf("Personal usage pattern detected")
                )
            )
        } else if (weekendUsage < 0.5) {
            patterns.add(
                UsagePattern(
                    skillName = skillName,
                    patternType = PatternType.WORKING_HOURS,
                    confidence = 0.75,
                    description = "Primarily used during work hours/weekdays",
                    recommendations = listOf("Work-related usage pattern detected")
                )
            )
        }
        if (last30Days.isNotEmpty() && last7Days.isNotEmpty()) {
            val recentAvg = last7Days.map { it.totalInvocations }.average()
        val olderAvg = last30Days.take(23).map { it.totalInvocations }.average()
        if (recentAvg > olderAvg * 1.5) {
                patterns.add(
                    UsagePattern(
                        skillName = skillName,
                        patternType = PatternType.LEARNING_CURVE,
                        confidence = 0.8,
                        description = "You're getting more value from this skill over time",
                        recommendations = listOf("Great progress in using this skill effectively")
                    )
                )
            }
        }
        return patterns
    }

    suspend fun buildUserBehaviorProfile(userId: String): UserBehaviorProfile = withContext(Dispatchers.IO) {
        val usageData = usageTracker.getUsageData()
        val dailyStats = usageTracker.getDailyStats()
        val topSkills = usageTracker.getTopUsedSkills(20)
            .filter { it.second > 0 }
            .map { it.first }
        val skillAffinityScores = calculateSkillAffinityScores(usageData)
        val timeSlots = analyzeTimeSlots(dailyStats)
        val successRates = calculateSuccessRates()
        val learningProgression = calculateLearningProgression(usageData)
        val interests = inferInterests(usageData)

        UserBehaviorProfile(
            userId = userId,
            preferredSkills = topSkills,
            skillAffinityScores = skillAffinityScores,
            frequentTimeSlots = timeSlots,
            averageSessionDuration = calculateAverageSessionDuration(),
            skillSuccessRates = successRates,
            learningProgression = learningProgression,
            interests = interests
        )
    }
        private fun calculateSkillAffinityScores(usageData: Map<String, SkillUsageTracker.SkillUsageData>): Map<String, Double> {
        if (usageData.isEmpty()) return emptyMap()
        val maxInvocations = usageData.values.maxOfOrNull { it.totalInvocations } ?: 1
        val maxToolCalls = usageData.values.maxOfOrNull { it.totalToolCalls } ?: 1

        return usageData.mapValues { (_, data) ->
            val recencyScore = calculateRecencyScore(data.lastUsed)
        val frequencyScore = data.totalInvocations.toDouble() / maxInvocations
            val engagementScore = data.totalToolCalls.toDouble() / maxToolCalls

            (recencyScore * RECENCY_WEIGHT +
                    frequencyScore * FREQUENCY_WEIGHT +
                    engagementScore * CONSISTENCY_WEIGHT)
        }
    }
        private fun calculateRecencyScore(lastUsedTimestamp: Long): Double {
        if (lastUsedTimestamp == 0L) return 0.0
        val daysSinceUse = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(lastUsedTimestamp),
            Instant.now()
        )
        return maxOf(0.0, 1.0 - (daysSinceUse / 30.0))
    }
        private fun analyzeTimeSlots(dailyStats: Map<String, SkillUsageTracker.DailyStats>): List<TimeSlot> {
        val hourSlotCounts = mutableMapOf<Int, MutableMap<DayOfWeek, Int>>()

        dailyStats.forEach { (_, stats) ->
            val date = try {
                LocalDate.parse(stats.date, dateFormatter)
            } catch (e: Exception) {
                return@forEach
            }
        val dayOfWeek = date.dayOfWeek
            stats.skillCounts.forEach { (skillName, count) ->
                val hourlyEstimate = (count / 8).coerceAtLeast(1)
                hourSlotCounts.getOrPut(9) { mutableMapOf() }
                    .getOrPut(dayOfWeek) { 0 }
                    .plusAssign(hourlyEstimate)
            }
        }
        return hourSlotCounts.flatMap { (hour, dayCounts) ->
            dayCounts.map { (dayOfWeek, count) ->
                TimeSlot(
                    hour = hour,
                    dayOfWeek = dayOfWeek,
                    frequency = count.toDouble()
                )
            }
        }.sortedByDescending { it.frequency }.take(10)
    }
        private fun calculateSuccessRates(): Map<String, Double> {
        val usageData = usageTracker.getUsageData()
        return usageData.mapValues { (_, data) ->
            if (data.totalInvocations > 0) {
                data.successCount.toDouble() / data.totalInvocations
            } else {
                0.0
            }
        }
    }
        private fun calculateLearningProgression(
        usageData: Map<String, SkillUsageTracker.SkillUsageData>
    ): Map<String, Int> {
        return usageData.mapValues { (skillName, data) ->
            when {
                data.totalInvocations > 100 -> 5
                data.totalInvocations > 50 -> 4
                data.totalInvocations > 20 -> 3
                data.totalInvocations > 5 -> 2
                data.totalInvocations > 0 -> 1
                else -> 0
            }
        }
    }
        private fun inferInterests(usageData: Map<String, SkillUsageTracker.SkillUsageData>): List<String> {
        val skillCategories = mapOf(
            "android" to "Mobile Development",
            "github" to "Development",
            "file" to "File Management",
            "web" to "Web",
            "api" to "API",
            "code" to "Programming",
            "search" to "Search",
            "image" to "Image Processing",
            "video" to "Video Processing",
            "audio" to "Audio Processing"
        )
        return usageData.keys
            .flatMap { skillName ->
                skillCategories.entries
                    .filter { (keywords, _) ->
                        keywords.any { skillName.lowercase().contains(it) }
                    }
                    .map { it.value }
            }
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .keys
            .toList()
    }
        private fun calculateAverageSessionDuration(): Long {
        val executionTimes = usageTracker.getExecutionTimes()
        if (executionTimes.isEmpty()) return 0
        return executionTimes.map { it.executionTimeMs }.average().toLong()
    }
        private fun getLast7DaysData(dailyStats: Map<String, SkillUsageTracker.DailyStats>): List<SkillUsageTracker.DailyStats> {
        val today = LocalDate.now()
        return (0..6).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo.toLong()).format(dateFormatter)
            dailyStats[date]
        }
    }
        private fun getLast30DaysData(dailyStats: Map<String, SkillUsageTracker.DailyStats>): List<SkillUsageTracker.DailyStats> {
        val today = LocalDate.now()
        return (0..29).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo.toLong()).format(dateFormatter)
            dailyStats[date]
        }
    }
        private fun calculateVariance(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }
        private fun isTrendingUp(values: List<Long>): Boolean {
        if (values.size < 3) return false
        val recentTrend = values.takeLast(3).average()
        val olderTrend = values.take(3).average()
        return recentTrend > olderTrend * 1.3
    }
        private fun isTrendingDown(values: List<Long>): Boolean {
        if (values.size < 3) return false
        val recentTrend = values.takeLast(3).average()
        val olderTrend = values.take(3).average()
        return recentTrend < olderTrend * 0.7
    }
        private fun calculateWeekendVsWeekdayRatio(dailyStats: Map<String, SkillUsageTracker.DailyStats>): Double {
        var weekendUsage = 0L
        var weekdayUsage = 0L

        dailyStats.forEach { (_, stats) ->
            val date = try {
                LocalDate.parse(stats.date, dateFormatter)
            } catch (e: Exception) {
                return@forEach
            }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
                weekendUsage += stats.totalInvocations
            } else {
                weekdayUsage += stats.totalInvocations
            }
        }
        return if (weekdayUsage > 0) weekendUsage.toDouble() / (weekdayUsage / 5) else 0.0
    }
        fun getSimilarUsers(skillName: String, limit: Int = 10): List<String> {
        return emptyList()
    }
        fun getSkillCorrelationMatrix(): Map<String, Map<String, Double>> {
        val usageData = usageTracker.getUsageData()
        val dailyStats = usageTracker.getDailyStats()
        val skillDailyUsage = mutableMapOf<String, MutableList<Double>>()

        dailyStats.values.forEach { stats ->
            stats.skillCounts.forEach { (skillName, count) ->
                skillDailyUsage.getOrPut(skillName) { mutableListOf() }.add(count.toDouble())
            }
        }
        val skills = skillDailyUsage.keys.toList()
        val correlationMatrix = mutableMapOf<String, Map<String, Double>>()
        for (i in skills.indices) {
            for (j in skills.indices) {
                if (i == j) continue

                val skill1 = skills[i]
                val skill2 = skills[j]

                val values1 = skillDailyUsage[skill1] ?: continue
                val values2 = skillDailyUsage[skill2] ?: continue

                val correlation = calculateCorrelation(values1, values2)
        if (correlation > 0.5) {
                    correlationMatrix.getOrPut(skill1) { mutableMapOf() }[skill2] = correlation
                }
            }
        }
        return correlationMatrix
    }
        private fun calculateCorrelation(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0

        val n = x.size
        val meanX = x.average()
        val meanY = y.average()
        var numerator = 0.0
        var denomX = 0.0
        var denomY = 0.0

        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            numerator += dx * dy
            denomX += dx * dx
            denomY += dy * dy
        }
        val denominator = sqrt(denomX * denomY)
        return if (denominator > 0) numerator / denominator else 0.0
    }
        fun getPersonalizedSettings(): PersonalizedSettings {
        return PersonalizedSettings(
            skillAutoLoad = true,
            showUsageStats = true,
            enableRecommendations = true,
            recommendationTypes = listOf(
                RecommendationSource.USAGE_BASED,
                RecommendationSource.HISTORY_BASED
            ),
            notificationFrequency = NotificationFrequency.WEEKLY
        )
    }
}
