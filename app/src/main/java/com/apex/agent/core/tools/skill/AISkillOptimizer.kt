package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * AI 驱动的 Skill 性能优化器
 *
 * 功能。
 * - 性能分析与监。
 * - 使用模式学习
 * - 自动参数调优
 * - 缓存优化建议
 * - 预测性加。
 */
class AISkillOptimizer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AISkillOptimizer"
        private const val ANALYTICS_DIR = "skill_analytics"
        private const val MAX_HISTORY_SIZE = 1000
        private const val LEARNING_WINDOW_DAYS = 7
        private const val OPTIMIZATION_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟

        @Volatile private var INSTANCE: AISkillOptimizer? = null

        fun getInstance(context: Context): AISkillOptimizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AISkillOptimizer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    @Serializable
    data class ExecutionMetrics(
        val skillId: String,
        val skillName: String,
        val timestamp: Long,
        val executionTimeMs: Long,
        val success: Boolean,
        val errorMessage: String? = null,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cacheHit: Boolean = false,
        val memoryUsageMb: Long = 0,
        val cpuUsagePercent: Float = 0f,
        val networkLatencyMs: Long = 0,
        val context: Map<String, String> = emptyMap()
    )

    @Serializable
    data class SkillProfile(
        val skillId: String,
        val skillName: String,
        val totalExecutions: Int,
        val successfulExecutions: Int,
        val failedExecutions: Int,
        val averageExecutionTimeMs: Long,
        val medianExecutionTimeMs: Long,
        val minExecutionTimeMs: Long,
        val maxExecutionTimeMs: Long,
        val successRate: Float,
        val averageCacheHitRate: Float,
        val peakUsageHours: List<Int>,
        val commonContexts: List<String>,
        val performanceScore: Float,
        val healthStatus: HealthStatus,
        val lastOptimized: Long? = null,
        val recommendations: List<OptimizationRecommendation>
    )

    @Serializable
    data class OptimizationRecommendation(
        val id: String,
        val type: RecommendationType,
        val priority: Priority,
        val title: String,
        val description: String,
        val expectedImpact: String,
        val confidence: Float,
        val action: String,
        val parameters: Map<String, String> = emptyMap()
    )

    @Serializable
    enum class RecommendationType {
        CACHE_OPTIMIZATION,
        PARAMETER_TUNING,
        WORKFLOW_SIMPLIFICATION,
        DEPENDENCY_PRELOAD,
        CONTEXT_OPTIMIZATION,
        RESOURCE_ALLOCATION,
        SCHEDULE_OPTIMIZATION,
        ERROR_RECOVERY
    }

    @Serializable
    enum class Priority {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    @Serializable
    enum class HealthStatus {
        EXCELLENT,
        GOOD,
        FAIR,
        POOR,
        CRITICAL
    }

    @Serializable
    data class UsagePattern(
        val skillId: String,
        val timeSlots: Map<Int, Int>, // hour -> count
    val dayOfWeekPattern: Map<Int, Int>, // day -> count
    val contextPatterns: List<ContextPattern>,
        val sequentialPatterns: List<SequentialPattern>,
        val seasonalTrend: Float
    )

    @Serializable
    data class ContextPattern(
        val contextKey: String,
        val contextValue: String,
        val frequency: Float,
        val avgExecutionTimeMs: Long
    )

    @Serializable
    data class SequentialPattern(
        val skillSequence: List<String>,
        val frequency: Float,
        val avgTransitionTimeMs: Long
    )

    @Serializable
    data class OptimizationResult(
        val skillId: String,
        val timestamp: Long,
        val appliedOptimizations: List<AppliedOptimization>,
        val metrics: MetricsComparison,
        val nextOptimizationTime: Long
    )

    @Serializable
    data class AppliedOptimization(
        val type: RecommendationType,
        val description: String,
        val beforeValue: String,
        val afterValue: String,
        val timestamp: Long
    )

    @Serializable
    data class MetricsComparison(
        val executionTimeBefore: Long,
        val executionTimeAfter: Long,
        val improvementPercent: Float,
        val successRateBefore: Float,
        val successRateAfter: Float,
        val cacheHitRateBefore: Float,
        val cacheHitRateAfter: Float
    )

    @Serializable
    data class CachePrediction(
        val skillId: String,
        val likelyToBeUsed: Boolean,
        val confidence: Float,
        val predictedExecutionTimeMs: Long,
        val recommendedCacheTtlMs: Long,
        val reasoning: String
    )

    // ========== 数据结构 ==========
    private val _profiles = MutableStateFlow<Map<String, SkillProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, SkillProfile>> = _profiles.asStateFlow()

    private val _recommendations = MutableStateFlow<List<OptimizationRecommendation>>(emptyList())
    val recommendations: StateFlow<List<OptimizationRecommendation>> = _recommendations.asStateFlow()

    private val _usagePatterns = MutableStateFlow<Map<String, UsagePattern>>(emptyMap())
    val usagePatterns: StateFlow<Map<String, UsagePattern>> = _usagePatterns.asStateFlow()

    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing: StateFlow<Boolean> = _isOptimizing.asStateFlow()

    private val _lastOptimization = MutableStateFlow<OptimizationResult?>(null)
    val lastOptimization: StateFlow<OptimizationResult?> = _lastOptimization.asStateFlow()

    private val executionHistory = mutableListOf<ExecutionMetrics>()
    private val skillCache = mutableMapOf<String, SkillCache>()

    private val skillManager by lazy { SkillManager.getInstance(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ========== 公开 API ==========

    /**
     * 记录技能执。
     */
    suspend fun recordExecution(metrics: ExecutionMetrics) = withContext(Dispatchers.IO) {
        executionHistory.add(metrics)

        // 保持历史大小限制
    if (executionHistory.size > MAX_HISTORY_SIZE) {
            executionHistory.removeAt(0)
        }

        // 保存到持久化存储
        saveExecutionMetrics(metrics)

        // 更新缓存预测
        updateCachePrediction(metrics.skillId)

        // 保持历史大小限制
        checkOptimizationNeeded()
    }

    /**
     * 获取技能性能档案
     */
    suspend fun getProfile(skillId: String): SkillProfile? = withContext(Dispatchers.IO) {
        loadProfileIfNeeded(skillId)
        _profiles.value[skillId]
    }

    /**
     * 获取所有技能档。
     */
    suspend fun getAllProfiles(): Map<String, SkillProfile> = withContext(Dispatchers.IO) {
        val availableSkills = skillManager.getAvailableSkills()
        availableSkills.keys.forEach { loadProfileIfNeeded(it) }
        _profiles.value.toMap()
    }

    /**
     * 获取优化建议
     */
    suspend fun getRecommendations(skillId: String? = null): List<OptimizationRecommendation> = withContext(Dispatchers.IO) {
        if (skillId != null) {
            loadProfileIfNeeded(skillId)
        }

        val recommendations = mutableListOf<OptimizationRecommendation>()

        // 保持历史大小限制
    val skillsToAnalyze = if (skillId != null) {
            listOf(skillId)
        } else {
            skillManager.getAvailableSkills().keys.toList()
        }

        skillsToAnalyze.forEach { id ->
            val profile = _profiles.value[id] ?: return@forEach
            recommendations.addAll(generateRecommendations(profile))
        }

        // 按优先级排序
        _recommendations.value = recommendations.sortedBy {
            when (it.priority) {
                Priority.CRITICAL -> 0
                Priority.HIGH -> 1
                Priority.MEDIUM -> 2
                Priority.LOW -> 3
            }
        }

        _recommendations.value
    }

    /**
     * 执行优化
     */
    suspend fun optimize(skillId: String): OptimizationResult = withContext(Dispatchers.IO) {
        _isOptimizing.value = true

        try {
            val profile = getProfile(skillId) ?: return@withContext OptimizationResult(
                skillId = skillId,
                timestamp = System.currentTimeMillis(),
                appliedOptimizations = emptyList(),
                metrics = MetricsComparison(0, 0, 0f, 0f, 0f, 0f, 0f),
                nextOptimizationTime = System.currentTimeMillis() + OPTIMIZATION_INTERVAL_MS
            )

            val appliedOptimizations = mutableListOf<AppliedOptimization>()
            val metricsComparison = MetricsComparison(
                executionTimeBefore = profile.averageExecutionTimeMs,
                executionTimeAfter = profile.averageExecutionTimeMs,
                improvementPercent = 0f,
                successRateBefore = profile.successRate,
                successRateAfter = profile.successRate,
                cacheHitRateBefore = profile.averageCacheHitRate,
                cacheHitRateAfter = profile.averageCacheHitRate
            )

            // 应用优化建议
    for (recommendation in profile.recommendations) {
                val applied = applyOptimization(skillId, recommendation)
                if (applied != null) {
                    appliedOptimizations.add(applied)
                }
            }

            val result = OptimizationResult(
                skillId = skillId,
                timestamp = System.currentTimeMillis(),
                appliedOptimizations = appliedOptimizations,
                metrics = metricsComparison,
                nextOptimizationTime = System.currentTimeMillis() + OPTIMIZATION_INTERVAL_MS
            )

            _lastOptimization.value = result
            result
        } finally {
            _isOptimizing.value = false
        }
    }

    /**
     * 预测缓存需。
     */
    suspend fun predictCacheNeeds(skillId: String): CachePrediction = withContext(Dispatchers.IO) {
        val profile = getProfile(skillId)
        val pattern = _usagePatterns.value[skillId]

        val currentHour = LocalDateTime.now().hour
        val currentDayOfWeek = LocalDateTime.now().dayOfWeek.value

        val hourFrequency = pattern?.timeSlots?.get(currentHour) ?: 0
        val dayFrequency = pattern?.dayOfWeekPattern?.get(currentDayOfWeek) ?: 0

        val likelyToBeUsed = hourFrequency > 5 || dayFrequency > 20

        val confidence = min(0.95f, max(0.3f, (hourFrequency + dayFrequency / 7f) / 50f))

        val predictedTime = profile?.averageExecutionTimeMs ?: 1000L

        val reasoning = buildString {
            append("Based on ")
            if (hourFrequency > 5) {
                append("high usage at hour ${currentHour} (${hourFrequency} executions), ")
            }
            if (dayFrequency > 20) {
                append("frequent usage on day ${currentDayOfWeek} (${dayFrequency} executions), ")
            }
            if (!likelyToBeUsed) {
                append("low predicted usage, ")
            }
            append("predicted execution time is ${predictedTime}ms")
        }

        CachePrediction(
            skillId = skillId,
            likelyToBeUsed = likelyToBeUsed,
            confidence = confidence,
            predictedExecutionTimeMs = predictedTime,
            recommendedCacheTtlMs = if (likelyToBeUsed) 30 * 60 * 1000L else 5 * 60 * 1000L,
            reasoning = reasoning
        )
    }

    /**
     * 获取使用模式
     */
    suspend fun getUsagePattern(skillId: String): UsagePattern? = withContext(Dispatchers.IO) {
        _usagePatterns.value[skillId] ?: run {
            analyzeUsagePattern(skillId)
            _usagePatterns.value[skillId]
        }
    }

    /**
     * 获取健康状态摘。
     */
    suspend fun getHealthSummary(): HealthSummary = withContext(Dispatchers.IO) {
        val allProfiles = getAllProfiles()

        val healthCounts = allProfiles.values.groupBy { it.healthStatus }
        val avgPerformance = if (allProfiles.isNotEmpty()) {
            allProfiles.values.map { it.performanceScore }.average().toFloat()
        } else 0f

        val criticalSkills = allProfiles.filter { it.value.healthStatus == HealthStatus.CRITICAL }.keys

        HealthSummary(
            totalSkills = allProfiles.size,
            excellentCount = healthCounts[HealthStatus.EXCELLENT]?.size ?: 0,
            goodCount = healthCounts[HealthStatus.GOOD]?.size ?: 0,
            fairCount = healthCounts[HealthStatus.FAIR]?.size ?: 0,
            poorCount = healthCounts[HealthStatus.POOR]?.size ?: 0,
            criticalCount = healthCounts[HealthStatus.CRITICAL]?.size ?: 0,
            averagePerformanceScore = avgPerformance,
            criticalSkills = criticalSkills.toList(),
            needsAttention = criticalSkills.isNotEmpty() || healthCounts[HealthStatus.POOR]?.isNotEmpty() == true
        )
    }

    data class HealthSummary(
        val totalSkills: Int,
        val excellentCount: Int,
        val goodCount: Int,
        val fairCount: Int,
        val poorCount: Int,
        val criticalCount: Int,
        val averagePerformanceScore: Float,
        val criticalSkills: List<String>,
        val needsAttention: Boolean
    )

    // ========== 私有方法 ==========
    private suspend fun loadProfileIfNeeded(skillId: String) {
        if (_profiles.value.containsKey(skillId)) return

        val profile = buildProfile(skillId)
        _profiles.value = _profiles.value.toMutableMap().apply {
            put(skillId, profile)
        }
    }

    private fun buildProfile(skillId: String): SkillProfile {
        val skillMetrics = executionHistory.filter { it.skillId == skillId }
        val skill = skillManager.getAvailableSkills()[skillId]

        if (skillMetrics.isEmpty()) {
            return SkillProfile(
                skillId = skillId,
                skillName = skill?.name ?: skillId,
                totalExecutions = 0,
                successfulExecutions = 0,
                failedExecutions = 0,
                averageExecutionTimeMs = 0,
                medianExecutionTimeMs = 0,
                minExecutionTimeMs = 0,
                maxExecutionTimeMs = 0,
                successRate = 1f,
                averageCacheHitRate = 0f,
                peakUsageHours = emptyList(),
                commonContexts = emptyList(),
                performanceScore = 1f,
                healthStatus = HealthStatus.EXCELLENT,
                recommendations = emptyList()
            )
        }

        val executionTimes = skillMetrics.map { it.executionTimeMs }.sorted()
        val avgTime = skillMetrics.map { it.executionTimeMs }.average().toLong()
        val medianTime = executionTimes[executionTimes.size / 2]
        val minTime = executionTimes.first()
        val maxTime = executionTimes.last()

        val successCount = skillMetrics.count { it.success }
        val successRate = successCount.toFloat() / skillMetrics.size

        val cacheHitCount = skillMetrics.count { it.cacheHit }
        val cacheHitRate = cacheHitCount.toFloat() / skillMetrics.size

        // 保持历史大小限制
    val hourCounts = skillMetrics.groupBy {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it.timestamp),
                java.time.ZoneId.systemDefault()
            ).hour
        }.mapValues { it.value.size }

        val peakHours = hourCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        // 保持历史大小限制
    val commonContexts = skillMetrics
            .flatMap { it.context.entries }
            .groupBy { "${it.key}=${it.value}" }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        // 计算性能分数
    val performanceScore = calculatePerformanceScore(
            successRate = successRate,
            avgExecutionTime = avgTime,
            cacheHitRate = cacheHitRate,
            executionCount = skillMetrics.size
        )

        // 保持历史大小限制
    val healthStatus = determineHealthStatus(performanceScore, successRate)

        // 生成建议
    val recommendations = generateRecommendations(
            SkillProfile(
                skillId = skillId,
                skillName = skill?.name ?: skillId,
                totalExecutions = skillMetrics.size,
                successfulExecutions = successCount,
                failedExecutions = skillMetrics.size - successCount,
                averageExecutionTimeMs = avgTime,
                medianExecutionTimeMs = medianTime,
                minExecutionTimeMs = minTime,
                maxExecutionTimeMs = maxTime,
                successRate = successRate,
                averageCacheHitRate = cacheHitRate,
                peakUsageHours = peakHours,
                commonContexts = commonContexts,
                performanceScore = performanceScore,
                healthStatus = healthStatus
            )
        )

        return SkillProfile(
            skillId = skillId,
            skillName = skill?.name ?: skillId,
            totalExecutions = skillMetrics.size,
            successfulExecutions = successCount,
            failedExecutions = skillMetrics.size - successCount,
            averageExecutionTimeMs = avgTime,
            medianExecutionTimeMs = medianTime,
            minExecutionTimeMs = minTime,
            maxExecutionTimeMs = maxTime,
            successRate = successRate,
            averageCacheHitRate = cacheHitRate,
            peakUsageHours = peakHours,
            commonContexts = commonContexts,
            performanceScore = performanceScore,
            healthStatus = healthStatus,
            recommendations = recommendations
        )
    }

    private fun calculatePerformanceScore(
        successRate: Float,
        avgExecutionTime: Long,
        cacheHitRate: Float,
        executionCount: Int
    ): Float {
        // 加权评分
    val successWeight = 0.4f
        val timeWeight = 0.3f
        val cacheWeight = 0.2f
        val usageWeight = 0.1f

        val successScore = successRate * 100

        // 时间评分：越快越好，1秒以内满分
    val timeScore = max(0f, 100f - (avgExecutionTime / 100f))

        // 缓存评分
    val cacheScore = cacheHitRate * 100

        // 使用频率评分
    val usageScore = min(100f, executionCount / 10f)

        return successScore * successWeight +
                timeScore * timeWeight +
                cacheScore * cacheWeight +
                usageScore * usageWeight
    }

    private fun determineHealthStatus(performanceScore: Float, successRate: Float): HealthStatus {
        return when {
            performanceScore >= 90 && successRate >= 0.99 -> HealthStatus.EXCELLENT
            performanceScore >= 75 && successRate >= 0.95 -> HealthStatus.GOOD
            performanceScore >= 60 && successRate >= 0.90 -> HealthStatus.FAIR
            performanceScore >= 40 && successRate >= 0.80 -> HealthStatus.POOR
            else -> HealthStatus.CRITICAL
        }
    }

    private fun generateRecommendations(profile: SkillProfile): List<OptimizationRecommendation> {
        val recommendations = mutableListOf<OptimizationRecommendation>()

        // 缓存优化建议
    if (profile.averageCacheHitRate < 0.3f) {
            recommendations.add(
                OptimizationRecommendation(
                    id = "cache_${profile.skillId}_1",
                    type = RecommendationType.CACHE_OPTIMIZATION,
                    priority = if (profile.averageCacheHitRate < 0.1f) Priority.HIGH else Priority.MEDIUM,
                    title = "提高缓存命中率",
                    description = "当前缓存命中率为 ${(profile.averageCacheHitRate * 100).toInt()}%，建议优化缓存策略",
                    expectedImpact = "可减少 ${(50 - profile.averageCacheHitRate * 100).toInt()}% 的执行时间",
                    confidence = 0.85f,
                    action = "increase_cache_ttl"
                )
            )
        }

        // 参数调优建议
    if (profile.maxExecutionTimeMs > profile.averageExecutionTimeMs * 3) {
            recommendations.add(
                OptimizationRecommendation(
                    id = "param_${profile.skillId}_1",
                    type = RecommendationType.PARAMETER_TUNING,
                    priority = Priority.MEDIUM,
                    title = "提高缓存命中率",
                    description = "最大执行时间 ${profile.maxExecutionTimeMs}ms 是平均值 ${profile.averageExecutionTimeMs}ms 的 ${profile.maxExecutionTimeMs / profile.averageExecutionTimeMs} 倍",
                    expectedImpact = "可将异常值执行时间降低 50%",
                    confidence = 0.75f,
                    action = "optimize_parameters"
                )
            )
        }

        // 保持历史大小限制
    if (profile.successRate < 0.95f) {
            recommendations.add(
                OptimizationRecommendation(
                    id = "error_${profile.skillId}_1",
                    type = RecommendationType.ERROR_RECOVERY,
                    priority = if (profile.successRate < 0.80f) Priority.CRITICAL else Priority.HIGH,
                    title = "提高缓存命中率",
                    description = "当前成功率为 ${(profile.successRate * 100).toInt()}%，${profile.failedExecutions} 次失败",
                    expectedImpact = "提高整体系统稳定性",
                    confidence = 0.90f,
                    action = "analyze_failures"
                )
            )
        }

        // 保持历史大小限制
    if (profile.peakUsageHours.isNotEmpty()) {
            val currentHour = LocalDateTime.now().hour
            val isPeakApproaching = profile.peakUsageHours.any { hour ->
                val diff = (hour - currentHour + 24) % 24
                diff in 1..3
            }

            if (isPeakApproaching) {
                recommendations.add(
                    OptimizationRecommendation(
                        id = "preload_${profile.skillId}_1",
                        type = RecommendationType.DEPENDENCY_PRELOAD,
                        priority = Priority.MEDIUM,
                        title = "预加载技能",
                        description = "使用高峰时段即将到来 (${profile.peakUsageHours.joinToString()}点）",
                        expectedImpact = "减少高峰期加载延迟",
                        confidence = 0.80f,
                        action = "preload_skill",
                        parameters = mapOf("skillId" to profile.skillId)
                    )
                )
            }
        }

        return recommendations
    }

    private suspend fun applyOptimization(
        skillId: String,
        recommendation: OptimizationRecommendation
    ): AppliedOptimization? = withContext(Dispatchers.IO) {
        // 保持历史大小限制
    when (recommendation.type) {
            RecommendationType.CACHE_OPTIMIZATION -> {
                // 增加缓存 TTL
    val skillCache = skillCache.getOrPut(skillId) {
                    SkillCache.getInstance(context)
                }
                val currentConfig = skillCache.getConfig()
                skillCache.setConfig(
                    currentConfig.copy(
                        defaultExpiryMs = currentConfig.defaultExpiryMs * 2
                    )
                )

                AppliedOptimization(
                    type = RecommendationType.CACHE_OPTIMIZATION,
                    description = "Increased cache TTL",
                    beforeValue = "${currentConfig.defaultExpiryMs}ms",
                    afterValue = "${currentConfig.defaultExpiryMs * 2}ms",
                    timestamp = System.currentTimeMillis()
                )
            }
            RecommendationType.DEPENDENCY_PRELOAD -> {
                // 预加载技能
                skillManager.preloadSkill(skillId)

                AppliedOptimization(
                    type = RecommendationType.DEPENDENCY_PRELOAD,
                    description = "Preloaded skill dependencies",
                    beforeValue = "not_preloaded",
                    afterValue = "preloaded",
                    timestamp = System.currentTimeMillis()
                )
            }
            else -> null
        }
    }

    private fun analyzeUsagePattern(skillId: String): UsagePattern {
        val skillMetrics = executionHistory.filter { it.skillId == skillId }

        // 时间模式
    val hourCounts = skillMetrics.groupBy {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it.timestamp),
                java.time.ZoneId.systemDefault()
            ).hour
        }.mapValues { it.value.size }

        // 星期模式
    val dayCounts = skillMetrics.groupBy {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it.timestamp),
                java.time.ZoneId.systemDefault()
            ).dayOfWeek.value
        }.mapValues { it.value.size }

        // 保持历史大小限制
    val contextPatterns = skillMetrics
            .flatMap { it.context.entries }
            .groupBy { "${it.key}=${it.value}" }
            .map { (key, values) ->
                ContextPattern(
                    contextKey = key.split("=").firstOrNull() ?: "",
                    contextValue = key.split("=").getOrNull(1) ?: "",
                    frequency = values.size.toFloat() / skillMetrics.size,
                    avgExecutionTimeMs = values.mapNotNull {
                        skillMetrics.find { m -> m.context == mapOf(it.key to it.value) }?.executionTimeMs
                    }.average().toLong()
                )
            }
            .sortedByDescending { it.frequency }
            .take(10)

        // 顺序模式
    val sequentialPatterns = analyzeSequentialPatterns(skillId)

        // 季节趋势
    val seasonalTrend = calculateSeasonalTrend(skillId)

        val pattern = UsagePattern(
            skillId = skillId,
            timeSlots = hourCounts,
            dayOfWeekPattern = dayCounts,
            contextPatterns = contextPatterns,
            sequentialPatterns = sequentialPatterns,
            seasonalTrend = seasonalTrend
        )

        _usagePatterns.value = _usagePatterns.value.toMutableMap().apply {
            put(skillId, pattern)
        }

        return pattern
    }

    private fun analyzeSequentialPatterns(skillId: String): List<SequentialPattern> {
        val skillMetrics = executionHistory.filter { it.skillId == skillId }
            .sortedBy { it.timestamp }

        val sequences = mutableListOf<List<String>>()

        for (i in 0 until skillMetrics.size - 1) {
            sequences.add(listOf(skillMetrics[i].skillId, skillMetrics[i + 1].skillId))
        }

        return sequences
            .groupBy { it.joinToString("->") }
            .map { (seq, occurrences) ->
                SequentialPattern(
                    skillSequence = seq.split("->"),
                    frequency = occurrences.size.toFloat() / max(1, sequences.size),
                    avgTransitionTimeMs = 0 // 简化
                )
            }
            .sortedByDescending { it.frequency }
            .take(5)
    }

    private fun calculateSeasonalTrend(skillId: String): Float {
        // 保持历史大小限制
    val now = System.currentTimeMillis()
        val weekMs = 7 * 24 * 60 * 60 * 1000L

        val recentCount = executionHistory.count {
            it.skillId == skillId && it.timestamp > now - weekMs
        }
        val olderCount = executionHistory.count {
            it.skillId == skillId && it.timestamp in (now - 2 * weekMs)..(now - weekMs)
        }

        return if (olderCount > 0) {
            (recentCount - olderCount).toFloat() / olderCount
        } else {
            0f
        }
    }

    private fun updateCachePrediction(skillId: String) {
        // 简化的缓存预测更新
    val pattern = _usagePatterns.value[skillId]
        if (pattern != null) {
            val prediction = predictCacheNeeds(skillId)
            AppLogger.d(TAG, "Cache prediction for ${skillId}: likely=${prediction.likelyToBeUsed}, confidence=${prediction.confidence}")
        }
    }

    private fun checkOptimizationNeeded() {
        val now = System.currentTimeMillis()
        val lastOpt = _lastOptimization.value

        if (lastOpt != null && now - lastOpt.timestamp < OPTIMIZATION_INTERVAL_MS) {
            return
        }

        // 保持历史大小限制
    val criticalSkills = _profiles.value.filter { (_, profile) ->
            profile.healthStatus in listOf(HealthStatus.POOR, HealthStatus.CRITICAL)
        }

        if (criticalSkills.isNotEmpty()) {
            // 触发优化
            criticalSkills.keys.firstOrNull()?.let { skillId ->
                scope.launch(Dispatchers.IO) {
                    optimize(skillId)
                }
            }
        }
    }

    private suspend fun saveExecutionMetrics(metrics: ExecutionMetrics) = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, ANALYTICS_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, "${metrics.skillId}_metrics.json")
            val existingMetrics = if (file.exists()) {
                try {
                    Json.decodeFromString<List<ExecutionMetrics>>(file.readText()).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }

            existingMetrics.add(metrics)

            // 保持大小限制
            while (existingMetrics.size > MAX_HISTORY_SIZE) {
                existingMetrics.removeAt(0)
            }

            file.writeText(Json.encodeToString(existingMetrics))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save execution metrics", e)
        }
    }

    private fun buildString(block: StringBuilder.() -> Unit): String {
        return StringBuilder().apply(block).toString()
    }

    // ========== 工具方法 ==========
    fun formatExecutionTime(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> "${ms / 1000}s"
            else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        }
    }

    fun formatPerformanceScore(score: Float): String {
        return "%.1f".format(score)
    }

    fun getPerformanceGrade(score: Float): String {
        return when {
            score >= 90 -> "A"
            score >= 80 -> "B"
            score >= 70 -> "C"
            score >= 60 -> "D"
            else -> "F"
        }
    }

    fun getHealthStatusColor(status: HealthStatus): Int {
        return when (status) {
            HealthStatus.EXCELLENT -> 0xFF4CAF50.toInt()
            HealthStatus.GOOD -> 0xFF8BC34A.toInt()
            HealthStatus.FAIR -> 0xFFFF9800.toInt()
            HealthStatus.POOR -> 0xFFFF5722.toInt()
            HealthStatus.CRITICAL -> 0xFFF44336.toInt()
        }
    }
}
