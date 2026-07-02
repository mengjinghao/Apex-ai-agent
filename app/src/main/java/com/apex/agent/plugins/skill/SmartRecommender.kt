package com.apex.plugins.skill

import android.content.Context
import com.apex.core.tools.skill.SkillManager
import com.apex.core.tools.skill.SkillUsageTracker
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow

class SmartRecommender private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SmartRecommender"

        @Volatile private var INSTANCE: SmartRecommender? = null

        private const val MIN_CONFIDENCE = 0.1
        private const val RECENCY_DECAY = 0.95
        private const val TRENDING_THRESHOLD = 1.5

        fun getInstance(context: Context): SmartRecommender {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SmartRecommender(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val usageTracker by lazy { SkillUsageTracker.getInstance(context) }
    private val skillManager by lazy { SkillManager.getInstance(context) }
    private val usageAnalyzer by lazy { SkillUsageAnalyzer.getInstance(context) }

    data class RecommenderResult(
        val recommendations: List<RecommendedSkill>,
        val explanations: Map<String, String>,
        val metadata: RecommendationMetadata
    )

    data class RecommendedSkill(
        val skillName: String,
        val score: Double,
        val sources: Set<RecommendationSource>,
        val reason: String,
        val confidence: Double,
        val estimatedUsefulness: Double,
        val similarUsersCount: Int = 0
    )

    data class RecommendationMetadata(
        val totalCandidates: Int,
        val generationTimeMs: Long,
        val algorithm: String,
        val userId: String
    )

    enum class RecommendationSource {
        USAGE_BASED,
        HISTORY_BASED,
        COLLABORATIVE,
        TRENDING,
        SIMILAR_USER,
        PATTERN_MATCH,
        SKILL_SIMILARITY
    }

    suspend fun recommendSkills(
        userId: String,
        limit: Int = 10,
        includeSources: Set<RecommendationSource> = RecommendationSource.entries.toSet()
    ): RecommenderResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val candidates = getCandidateSkills()
        val scores = mutableMapOf<String, SkillScoreComponents>()

        candidates.forEach { skillName ->
            scores[skillName] = calculateSkillScore(skillName, includeSources)
        }

        val recommendations = scores
            .filter { it.value.totalScore >= MIN_CONFIDENCE }
            .map { (skillName, components) ->
                val sources = components.sources.filter { includeSources.contains(it) }.toSet()
                RecommendedSkill(
                    skillName = skillName,
                    score = normalizeScore(components.totalScore),
                    sources = sources,
                    reason = generateRecommendationReason(skillName, components),
                    confidence = components.confidence,
                    estimatedUsefulness = components.estimatedUsefulness,
                    similarUsersCount = components.similarUsersCount
                )
            }
            .sortedByDescending { it.score }
            .take(limit)

        val explanations = recommendations.associate { it.skillName to it.reason }

        val metadata = RecommendationMetadata(
            totalCandidates = candidates.size,
            generationTimeMs = System.currentTimeMillis() - startTime,
            algorithm = "HybridWeighted-${includeSources.size}sources",
            userId = userId
        )

        AppLogger.d(TAG, "Generated ${recommendations.size} recommendations in ${metadata.generationTimeMs}ms")

        RecommenderResult(recommendations, explanations, metadata)
    }

    private data class SkillScoreComponents(
        val totalScore: Double,
        val sources: List<RecommendationSource>,
        val confidence: Double,
        val estimatedUsefulness: Double,
        val similarUsersCount: Int
    )

    private fun getCandidateSkills(): List<String> {
        val availableSkills = skillManager.getAvailableSkills()
        val usedSkills = usageTracker.getUsageData().keys
        return availableSkills.keys.filter { it !in usedSkills }
    }

    private fun calculateSkillScore(
        skillName: String,
        includeSources: Set<RecommendationSource>
    ): SkillScoreComponents {
        var totalScore = 0.0
        val sources = mutableListOf<RecommendationSource>()
        var confidence = 0.0
        var estimatedUsefulness = 0.0
        var similarUsersCount = 0

        if (includeSources.contains(RecommendationSource.TRENDING)) {
            val trendingScore = calculateTrendingScore(skillName)
            if (trendingScore > 0) {
                totalScore += trendingScore * 0.25
                sources.add(RecommendationSource.TRENDING)
                confidence = maxOf(confidence, trendingScore)
            }
        }

        if (includeSources.contains(RecommendationSource.USAGE_BASED)) {
            val usageScore = calculateUsageBasedScore(skillName)
            if (usageScore > 0) {
                totalScore += usageScore * 0.35
                sources.add(RecommendationSource.USAGE_BASED)
                confidence = maxOf(confidence, usageScore)
            }
        }

        if (includeSources.contains(RecommendationSource.HISTORY_BASED)) {
            val historyScore = calculateHistoryBasedScore(skillName)
            if (historyScore > 0) {
                totalScore += historyScore * 0.25
                sources.add(RecommendationSource.HISTORY_BASED)
                confidence = maxOf(confidence, historyScore)
            }
        }

        if (includeSources.contains(RecommendationSource.PATTERN_MATCH)) {
            val patternScore = calculatePatternMatchScore(skillName)
            if (patternScore > 0) {
                totalScore += patternScore * 0.15
                sources.add(RecommendationSource.PATTERN_MATCH)
                confidence = maxOf(confidence, patternScore)
            }
        }

        if (includeSources.contains(RecommendationSource.SKILL_SIMILARITY)) {
            val similarityScore = calculateSkillSimilarityScore(skillName)
            if (similarityScore > 0) {
                totalScore += similarityScore * 0.20
                sources.add(RecommendationSource.SKILL_SIMILARITY)
                confidence = maxOf(confidence, similarityScore)
            }
        }

        if (includeSources.contains(RecommendationSource.COLLABORATIVE)) {
            val collabScore = calculateCollaborativeScore(skillName)
            if (collabScore > 0) {
                totalScore += collabScore * 0.30
                sources.add(RecommendationSource.COLLABORATIVE)
                confidence = maxOf(confidence, collabScore)
                similarUsersCount = (collabScore * 100).toInt()
            }
        }

        estimatedUsefulness = calculateEstimatedUsefulness(skillName)

        return SkillScoreComponents(
            totalScore = totalScore,
            sources = sources,
            confidence = confidence.coerceIn(0.0, 1.0),
            estimatedUsefulness = estimatedUsefulness,
            similarUsersCount = similarUsersCount
        )
    }

    private fun calculateTrendingScore(skillName: String): Double {
        val dailyStats = usageTracker.getDailyStats()
        if (dailyStats.size < 7) return 0.0

        val recentData = dailyStats.values.takeLast(7)
        val olderData = dailyStats.values.take(7)

        if (olderData.isEmpty()) return 0.0

        val recentAvg = recentData.map { it.totalInvocations }.average()
        val olderAvg = olderData.map { it.totalInvocations }.average()

        if (olderAvg == 0.0) return if (recentAvg > 0) 0.8 else 0.0

        val trendRatio = recentAvg / olderAvg
        return if (trendRatio >= TRENDING_THRESHOLD) {
            minOf((trendRatio - 1.0) / 2.0, 1.0)
        } else {
            0.0
        }
    }

    private fun calculateUsageBasedScore(skillName: String): Double {
        val userProfile = usageAnalyzer.buildUserBehaviorProfile("current_user")

        var maxScore = 0.0

        userProfile.skillAffinityScores.forEach { (usedSkill, affinity) ->
            val similarity = getSkillSimilarity(skillName, usedSkill)
            val score = affinity * similarity
            maxScore = maxOf(maxScore, score)
        }

        return maxScore
    }

    private fun calculateHistoryBasedScore(skillName: String): Double {
        val usageData = usageTracker.getUsageData()
        if (usageData.isEmpty()) return 0.0

        val today = LocalDate.now()
        var score = 0.0

        usageData.forEach { (usedSkill, data) ->
            if (data.lastUsed > 0) {
                val daysSinceUse = ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(data.lastUsed),
                    Instant.now()
                )
                val recencyWeight = RECENCY_DECAY.pow(daysSinceUse / 7)
                val similarity = getSkillSimilarity(skillName, usedSkill)
                score += recencyWeight * similarity * (data.totalInvocations / 100.0)
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun calculatePatternMatchScore(skillName: String): Double {
        val patterns = usageAnalyzer.analyzeUsagePatterns(skillName)
        if (patterns.isEmpty()) return 0.0

        return patterns.map { it.confidence }.average().coerceIn(0.0, 1.0)
    }

    private fun calculateSkillSimilarityScore(skillName: String): Double {
        val correlationMatrix = usageAnalyzer.getSkillCorrelationMatrix()
        val usedSkills = usageTracker.getUsageData().keys

        var maxSimilarity = 0.0
        correlationMatrix.forEach { (usedSkill, similarSkills) ->
            if (usedSkill in usedSkills) {
                similarSkills[skillName]?.let { similarity ->
                    maxSimilarity = maxOf(maxSimilarity, similarity)
                }
            }
        }

        return maxSimilarity
    }

    private fun calculateCollaborativeScore(skillName: String): Double {
        val similarUsers = usageAnalyzer.getSimilarUsers(skillName)
        if (similarUsers.isEmpty()) return 0.0

        val score = similarUsers.size / 100.0
        return score.coerceIn(0.0, 1.0)
    }

    private fun getSkillSimilarity(skill1: String, skill2: String): Double {
        val keywords1 = skill1.lowercase().split(Regex("[\\s_-]")).toSet()
        val keywords2 = skill2.lowercase().split(Regex("[\\s_-]")).toSet()

        val intersection = keywords1.intersect(keywords2).size
        val union = keywords1.union(keywords2).size

        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    private fun calculateEstimatedUsefulness(skillName: String): Double {
        val patterns = usageAnalyzer.analyzeUsagePatterns(skillName)
        if (patterns.isEmpty()) return 0.5

        val consistencyScore = patterns.count { it.patternType == SkillUsageAnalyzer.PatternType.CONSISTENT } / patterns.size.toDouble()
        val emergingScore = patterns.count { it.patternType == SkillUsageAnalyzer.PatternType.EMERGING } * 0.2
        val learningScore = patterns.count { it.patternType == SkillUsageAnalyzer.PatternType.LEARNING_CURVE } * 0.15

        return (0.3 + consistencyScore * 0.4 + emergingScore + learningScore).coerceIn(0.0, 1.0)
    }

    private fun normalizeScore(score: Double): Double {
        return (score * 100).coerceIn(0.0, 100.0)
    }

    private fun generateRecommendationReason(
        skillName: String,
        components: SkillScoreComponents
    ): String {
        val primarySource = components.sources.firstOrNull() ?: return "General recommendation"

        return when (primarySource) {
            RecommendationSource.TRENDING -> {
                "This skill is trending and gaining popularity"
            }
            RecommendationSource.USAGE_BASED -> {
                "Based on your usage patterns, this skill complements your frequently used skills"
            }
            RecommendationSource.HISTORY_BASED -> {
                "Recommended based on your skill usage history"
            }
            RecommendationSource.PATTERN_MATCH -> {
                "Matches your established usage patterns"
            }
            RecommendationSource.SKILL_SIMILARITY -> {
                "Similar to skills you use often"
            }
            RecommendationSource.COLLABORATIVE -> {
                "Popular among users with similar patterns"
            }
            RecommendationSource.SIMILAR_USER -> {
                "Recommended by ${components.similarUsersCount} similar users"
            }
        }
    }

    suspend fun recommendSkillCombinations(
        primarySkill: String,
        limit: Int = 5
    ): List<List<String>> = withContext(Dispatchers.IO) {
        val correlationMatrix = usageAnalyzer.getSkillCorrelationMatrix()
        val primaryCorrelations = correlationMatrix[primarySkill] ?: emptyMap()

        val recommendedCombinations = mutableListOf<List<String>>()

        primaryCorrelations.entries
            .sortedByDescending { it.value }
            .take(limit)
            .forEach { (secondarySkill, correlation) ->
                if (correlation > 0.6) {
                    recommendedCombinations.add(listOf(primarySkill, secondarySkill))
                }
            }

        recommendedCombinations
    }

    fun explainRecommendation(skillName: String): String {
        val usageData = usageTracker.getSkillUsageData(skillName)
        val patterns = usageAnalyzer.analyzeUsagePatterns(skillName)
        val userProfile = usageAnalyzer.buildUserBehaviorProfile("current_user")

        val explanation = StringBuilder()
        explanation.append("Recommendation for: ${skillName}\n\n")

        usageData?.let { data ->
            explanation.append("Usage Statistics:\n")
            explanation.append("- Total invocations: ${data.totalInvocations}\n")
            explanation.append("- Success rate: ${(data.successCount.toDouble() / data.totalInvocations * 100).toInt()}%\n")
            explanation.append("- Average execution time: ${usageTracker.getAverageExecutionTime(skillName)}ms\n")
            explanation.append("- Last used: ${if (data.lastUsed > 0) java.time.Instant.ofEpochMilli(data.lastUsed) else "Never"}\n\n")
        }

        if (patterns.isNotEmpty()) {
            explanation.append("Detected Patterns:\n")
            patterns.take(3).forEach { pattern ->
                explanation.append("- ${pattern.patternType}: ${pattern.description}\n")
            }
            explanation.append("\n")
        }

        val relatedSkills = userProfile.skillAffinityScores
            .filter { getSkillSimilarity(skillName, it.key) > 0.3 }
            .keys
            .take(5)

        if (relatedSkills.isNotEmpty()) {
            explanation.append("Related to skills you use: ${relatedSkills.joinToString(", ")}\n")
        }

        return explanation.toString()
    }
}
