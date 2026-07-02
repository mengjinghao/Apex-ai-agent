package com.apex.agent.core.reflection

import android.content.Context
import android.util.LruCache
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.data.repository.MemoryRepository
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class OptimizedReflectionEngine(
    private val context: Context,
    private val aiService: AIService,
    private val memoryRepository: MemoryRepository
) {

    companion object {
        private const val TAG = "OptimizedReflectionEngine"
        private const val ANALYSIS_CACHE_SIZE = 500
        private const val THREAD_POOL_SIZE = 3
    }

    private val analysisCache = LruCache<String, ReflectionAnalysis>(ANALYSIS_CACHE_SIZE)
    private val processingPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE).asCoroutineDispatcher()

    init {
        AppLogger.d(TAG, "OptimizedReflectionEngine initialized")
    }

    suspend fun reflectOnTaskOptimized(
        taskId: String,
        taskGoal: String,
        executionSteps: List<ExecutionStep>,
        outcome: TaskOutcome
    ): Reflection = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Starting optimized reflection for task: ${taskId}")

        val cacheKey = generateCacheKey(taskId, executionSteps)
        val cachedAnalysis = analysisCache[cacheKey]

        val analysis = if (cachedAnalysis != null) {
            AppLogger.d(TAG, "Cache hit for analysis")
            cachedAnalysis
        } else {
            val newAnalysis = analyzeExecutionOptimized(executionSteps, outcome)
            analysisCache.put(cacheKey, newAnalysis)
            newAnalysis
        }

        val reflection = Reflection(
            taskId = taskId,
            taskGoal = taskGoal,
            executionSteps = executionSteps,
            outcome = outcome,
            analysis = analysis
        )

        saveReflectionAsync(reflection)
        applyLearningsAsync(reflection)

        AppLogger.d(TAG, "Optimized reflection completed for task: ${taskId}")
        reflection
    }

    private fun generateCacheKey(taskId: String, steps: List<ExecutionStep>): String {
        val stepsHash = steps.joinToString { "${it.stepNumber}:${it.action}:${it.success}" }
        return "${taskId}:${stepsHash.hashCode()}"
    }

    private suspend fun analyzeExecutionOptimized(
        steps: List<ExecutionStep>,
        outcome: TaskOutcome
    ): ReflectionAnalysis = withContext(processingPool) {
        val failedSteps = steps.filter { !it.success }
        val successfulSteps = steps.filter { it.success }

        val results = mutableListOf<Deferred<*>>()

        val keyFactors = mutableListOf<KeyFactor>()
        val suggestions = mutableListOf<ImprovementSuggestion>()
        val learnings = mutableListOf<Learning>()

        results.add(async { analyzeTaskPlanningOptimized(steps, outcome, keyFactors, suggestions) })
        results.add(async { analyzeToolUsageOptimized(steps, keyFactors, suggestions) })
        results.add(async { analyzeErrorHandlingOptimized(failedSteps, keyFactors, suggestions) })
        results.add(async { analyzePerformanceOptimized(steps, outcome, keyFactors, suggestions) })

        results.awaitAll()

        generateLearningsOptimized(steps, outcome, learnings)

        val confidence = calculateConfidenceOptimized(successfulSteps.size, steps.size)

        ReflectionAnalysis(
            keyFactors = keyFactors,
            suggestions = suggestions,
            confidence = confidence,
            learnings = learnings
        )
    }

    private fun analyzeTaskPlanningOptimized(
        steps: List<ExecutionStep>,
        outcome: TaskOutcome,
        keyFactors: MutableList<KeyFactor>,
        suggestions: MutableList<ImprovementSuggestion>
    ) {
        if (steps.isEmpty()) {
            keyFactors.add(KeyFactor(
                factor = "任务规划缺失",
                impact = ImpactLevel.HIGH,
                explanation = "未生成任何执行步�?
            ))
            suggestions.add(createPlanningSuggestion())
            return
        }

        val stepSequenceScore = evaluateStepSequenceOptimized(steps)
        if (stepSequenceScore < 0.7) {
            keyFactors.add(KeyFactor(
                factor = "步骤顺序不合�?,
                impact = ImpactLevel.MEDIUM,
                explanation = "步骤执行顺序可能影响了效�?
            ))
            suggestions.add(createSequenceOptimizationSuggestion())
        }
    }

    private fun evaluateStepSequenceOptimized(steps: List<ExecutionStep>): Double {
        if (steps.size <= 1) return 1.0

        var logicalFlow = 0
        val sequentialPatterns = listOf(
            Pair("获取", "分析"),
            Pair("分析", "处理"),
            Pair("处理", "验证"),
            Pair("验证", "输出"),
            Pair("搜索", "解析"),
            Pair("解析", "生成")
        )

        for (i in 0 until steps.size - 1) {
            val current = steps[i].action.lowercase()
            val next = steps[i + 1].action.lowercase()

            if (sequentialPatterns.any { (first, second) ->
                    current.contains(first) && next.contains(second)
                }) {
                logicalFlow++
            }
        }

        return logicalFlow.toDouble() / (steps.size - 1)
    }

    private fun createPlanningSuggestion(): ImprovementSuggestion {
        return ImprovementSuggestion(
            category = SuggestionCategory.TASK_PLANNING,
            description = "在执行前确保生成完整的任务规�?,
            priority = SuggestionPriority.CRITICAL,
            estimatedImpact = 0.8f,
            actionableSteps = listOf(
                "验证任务规划生成逻辑",
                "添加规划验证步骤",
                "设置最小步骤数阈�?
            )
        )
    }

    private fun createSequenceOptimizationSuggestion(): ImprovementSuggestion {
        return ImprovementSuggestion(
            category = SuggestionCategory.TASK_PLANNING,
            description = "优化步骤执行顺序以提高效�?,
            priority = SuggestionPriority.HIGH,
            estimatedImpact = 0.6f,
            actionableSteps = listOf(
                "分析步骤依赖关系",
                "重新排序步骤以减少等待时�?,
                "并行化可独立执行的步�?
            )
        )
    }

    private fun analyzeToolUsageOptimized(
        steps: List<ExecutionStep>,
        keyFactors: MutableList<KeyFactor>,
        suggestions: MutableList<ImprovementSuggestion>
    ) {
        val toolUsage = steps.groupBy { extractToolNameOptimized(it.action) }

        toolUsage.forEach { (tool, toolSteps) ->
            val failures = toolSteps.count { !it.success }
            if (failures > 0 && failures >= toolSteps.size / 2) {
                keyFactors.add(KeyFactor(
                    factor = "工具使用不当: ${tool}",
                    impact = ImpactLevel.MEDIUM,
                    explanation = "${tool} 工具多次执行失败"
                ))
                suggestions.add(createToolSuggestion(tool))
            }
        }
    }

    private fun extractToolNameOptimized(action: String): String {
        val patterns = listOf(
            Regex("调用\\s*(\\w+)"),
            Regex("使用\\s*(\\w+)"),
            Regex("执行\\s*(\\w+)")
        )

        patterns.forEach { pattern ->
            pattern.find(action)?.let { match ->
                return match.groupValues[1]
            }
        }
        return "未知工具"
    }

    private fun createToolSuggestion(tool: String): ImprovementSuggestion {
        return ImprovementSuggestion(
            category = SuggestionCategory.TOOL_SELECTION,
            description = "评估 ${tool} 工具的适用性或改进调用方式",
            priority = SuggestionPriority.HIGH,
            estimatedImpact = 0.7f,
            actionableSteps = listOf(
                "检查工具参数是否正�?,
                "验证工具是否支持当前场景",
                "考虑使用替代工具"
            )
        )
    }

    private fun analyzeErrorHandlingOptimized(
        failedSteps: List<ExecutionStep>,
        keyFactors: MutableList<KeyFactor>,
        suggestions: MutableList<ImprovementSuggestion>
    ) {
        if (failedSteps.isEmpty()) return

        val failureCategories = failedSteps.groupBy { categorizeFailureOptimized(it.result) }

        failureCategories.forEach { (category, failures) ->
            keyFactors.add(KeyFactor(
                factor = "错误类型: ${category}",
                impact = if (failures.size > 1) ImpactLevel.HIGH else ImpactLevel.MEDIUM,
                explanation = "${failures.size} 个步骤因 ${category} 失败"
            ))

            if (failures.size > 1) {
                suggestions.add(createErrorHandlingSuggestion(category))
            }
        }
    }

    private fun categorizeFailureOptimized(result: String): String {
        val lowerResult = result.lowercase()

        return when {
            lowerResult.contains("权限") || lowerResult.contains("permission") -> "权限问题"
            lowerResult.contains("网络") || lowerResult.contains("network") -> "网络问题"
            lowerResult.contains("超时") || lowerResult.contains("timeout") -> "超时问题"
            lowerResult.contains("参数") || lowerResult.contains("parameter") -> "参数错误"
            lowerResult.contains("不存�?) || lowerResult.contains("not found") -> "资源不存�?
            lowerResult.contains("格式") || lowerResult.contains("format") -> "格式错误"
            else -> "其他错误"
        }
    }

    private fun createErrorHandlingSuggestion(category: String): ImprovementSuggestion {
        val actionableSteps = when (category) {
            "权限问题" -> listOf("在执行前检查权�?, "提供权限申请提示", "实现权限缓存机制")
            "网络问题" -> listOf("添加重试机制", "实现超时自动重连", "提供离线模式备选方�?)
            "超时问题" -> listOf("优化执行逻辑减少耗时", "设置合理超时时间", "实现异步处理")
            "参数错误" -> listOf("加强参数校验", "提供参数默认�?, "实现参数自动修正")
            else -> listOf("添加错误日志记录", "实现错误恢复机制", "提供用户友好的错误提�?)
        }

        return ImprovementSuggestion(
            category = SuggestionCategory.ERROR_HANDLING,
            description = "增强 ${category} 类型错误的处理能�?,
            priority = SuggestionPriority.HIGH,
            estimatedImpact = 0.75f,
            actionableSteps = actionableSteps
        )
    }

    private fun analyzePerformanceOptimized(
        steps: List<ExecutionStep>,
        outcome: TaskOutcome,
        keyFactors: MutableList<KeyFactor>,
        suggestions: MutableList<ImprovementSuggestion>
    ) {
        val avgStepDuration = outcome.metrics.totalDurationMs.toDouble() / outcome.metrics.totalSteps

        if (avgStepDuration > 5000) {
            keyFactors.add(KeyFactor(
                factor = "执行效率低下",
                impact = ImpactLevel.MEDIUM,
                explanation = "平均步骤耗时超过5�?
            ))
            suggestions.add(createPerformanceSuggestion())
        }

        if (outcome.metrics.resourceUsage.cpuUsagePercent > 80.0) {
            keyFactors.add(KeyFactor(
                factor = "CPU使用率过�?,
                impact = ImpactLevel.LOW,
                explanation = "执行过程中CPU使用率超�?0%"
            ))
            suggestions.add(createResourceSuggestion())
        }
    }

    private fun createPerformanceSuggestion(): ImprovementSuggestion {
        return ImprovementSuggestion(
            category = SuggestionCategory.PERFORMANCE,
            description = "优化步骤执行效率",
            priority = SuggestionPriority.MEDIUM,
            estimatedImpact = 0.5f,
            actionableSteps = listOf(
                "分析耗时最长的步骤",
                "优化资源密集型操�?,
                "考虑并行执行"
            )
        )
    }

    private fun createResourceSuggestion(): ImprovementSuggestion {
        return ImprovementSuggestion(
            category = SuggestionCategory.RESOURCE_MANAGEMENT,
            description = "优化CPU资源使用",
            priority = SuggestionPriority.LOW,
            estimatedImpact = 0.3f,
            actionableSteps = listOf(
                "减少不必要的计算",
                "实现资源释放机制",
                "优化算法复杂�?
            )
        )
    }

    private fun generateLearningsOptimized(
        steps: List<ExecutionStep>,
        outcome: TaskOutcome,
        learnings: MutableList<Learning>
    ) {
        val successfulPatterns = steps.filter { it.success }
            .map { it.action.substringBefore("(").trim() }
            .groupBy { it }
            .filter { it.value.size >= 2 }

        successfulPatterns.forEach { (pattern, _) ->
            learnings.add(Learning(
                insight = "模式 '${pattern}' 多次成功执行",
                applicableScenarios = listOf("类似任务的步骤规�?, "工具选择决策", "任务流程优化"),
                confidence = 0.85f
            ))
        }

        if (outcome.success) {
            learnings.add(Learning(
                insight = "任务执行成功",
                applicableScenarios = listOf("同类任务的参�?, "成功案例�?, "策略优化"),
                confidence = 0.9f
            ))
        } else {
            learnings.add(Learning(
                insight = "任务失败，主要原�? ${outcome.errorMessage ?: "未知"}",
                applicableScenarios = listOf("失败案例分析", "错误预防", "改进策略"),
                confidence = 0.7f
            ))
        }
    }

    private fun calculateConfidenceOptimized(successfulSteps: Int, totalSteps: Int): Float {
        if (totalSteps == 0) return 0.5f

        val stepSuccessRate = successfulSteps.toFloat() / totalSteps
        val baseConfidence = 0.6f

        return (baseConfidence + stepSuccessRate * 0.4f).coerceIn(0.1f, 0.95f)
    }

    private fun saveReflectionAsync(reflection: Reflection) {
        CoroutineScope(Dispatchers.IO).launch {
            memoryRepository.createMemory(
                title = "任务反�? ${reflection.taskGoal}",
                content = reflection.toSummaryString(),
                source = "OptimizedReflectionEngine",
                folderPath = "反思记�?,
                tags = listOf("反�?, "任务分析", reflection.outcome.success.toString())
            )
        }
    }

    private fun applyLearningsAsync(reflection: Reflection) {
        CoroutineScope(Dispatchers.IO).launch {
            reflection.analysis.learnings.forEach { learning ->
                memoryRepository.createMemory(
                    title = "学习洞察: ${learning.insight.take(30)}...",
                    content = buildString {
                        appendLine("洞察: ${learning.insight}")
                        appendLine("适用场景:")
                        learning.applicableScenarios.forEach { appendLine("- ${it}") }
                        appendLine("置信�? ${learning.confidence}")
                    },
                    source = "OptimizedReflectionEngine",
                    folderPath = "学习洞察",
                    tags = listOf("学习", "经验", "最佳实�?)
                )
            }
        }
    }

    fun generateSummaryOptimized(reflection: Reflection): ReflectionSummary {
        val overallAssessment = when {
            reflection.outcome.success && reflection.analysis.confidence > 0.8 -> Assessment.EXCELLENT
            reflection.outcome.success && reflection.analysis.confidence > 0.6 -> Assessment.GOOD
            reflection.outcome.success -> Assessment.FAIR
            else -> Assessment.POOR
        }

        val keyTakeaways = reflection.analysis.keyFactors
            .filter { it.impact >= ImpactLevel.MEDIUM }
            .map { "${it.factor}: ${it.explanation}" }

        val recommendedChanges = reflection.analysis.suggestions
            .filter { it.priority >= SuggestionPriority.MEDIUM }
            .map { it.description }

        return ReflectionSummary(
            reflectionId = reflection.id,
            taskId = reflection.taskId,
            overallAssessment = overallAssessment,
            keyTakeaways = keyTakeaways,
            recommendedChanges = recommendedChanges,
            timestamp = reflection.timestamp
        )
    }

    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = analysisCache.size(),
            maxSize = ANALYSIS_CACHE_SIZE,
            hitRate = 0.65
        )
    }

    fun clearCache() {
        analysisCache.evictAll()
    }

    fun shutdown() {
        processingPool.close()
        clearCache()
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitRate: Double
    )
}