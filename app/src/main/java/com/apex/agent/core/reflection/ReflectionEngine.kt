package com.apex.agent.core.reflection

import android.content.Context
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.data.repository.MemoryRepository
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.apex.core.tools.javascript.not

class ReflectionEngine(
    private val context: Context,
    private val aiService: AIService,
    private val memoryRepository: MemoryRepository
) {

    companion object {
        private const val TAG = "ReflectionEngine"
    }

    private val reflectionPrompts = ReflectionPrompts()

    suspend fun reflectOnTask(
        taskId: String,
        taskGoal: String,
        executionSteps: List<ExecutionStep>,
        outcome: TaskOutcome
    ): Reflection = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Starting reflection for task: ${taskId}")

        val analysis = analyzeExecution(executionSteps, outcome)
        
        val reflection = Reflection(
            taskId = taskId,
            taskGoal = taskGoal,
            executionSteps = executionSteps,
            outcome = outcome,
            analysis = analysis
        )

        saveReflection(reflection)
        applyLearnings(reflection)

        AppLogger.d(TAG, "Reflection completed for task: ${taskId}")
        reflection
    }

    private suspend fun analyzeExecution(
        steps: List<ExecutionStep>,
        outcome: TaskOutcome
    ): ReflectionAnalysis = withContext(Dispatchers.IO) {
        val failedSteps = steps.filter { !it.success }
        val successfulSteps = steps.filter { it.success }

        val keyFactors = mutableListOf<KeyFactor>()
        val suggestions = mutableListOf<ImprovementSuggestion>()
        val learnings = mutableListOf<Learning>()

        analyzeTaskPlanning(steps, outcome, keyFactors, suggestions)
        
        analyzeToolUsage(steps, keyFactors, suggestions)
        
        analyzeErrorHandling(failedSteps, keyFactors, suggestions)
        
        analyzePerformance(steps, outcome, keyFactors, suggestions)
        
        generateLearnings(steps, outcome, learnings)

        val confidence = calculateConfidence(successfulSteps.size, steps.size)

        ReflectionAnalysis(
            keyFactors = keyFactors,
            suggestions = suggestions,
            confidence = confidence,
            learnings = learnings
        )
    }

    private fun analyzeTaskPlanning(
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
            suggestions.add(ImprovementSuggestion(
                category = SuggestionCategory.TASK_PLANNING,
                description = "在执行前确保生成完整的任务规�?,
                priority = SuggestionPriority.CRITICAL,
                estimatedImpact = 0.8f,
                actionableSteps = listOf(
                    "验证任务规划生成逻辑",
                    "添加规划验证步骤",
                    "设置最小步骤数阈�?
                )
            ))
            return
        }

        val stepSequenceScore = evaluateStepSequence(steps)
        if (stepSequenceScore < 0.7) {
            keyFactors.add(KeyFactor(
                factor = "步骤顺序不合�?,
                impact = ImpactLevel.MEDIUM,
                explanation = "步骤执行顺序可能影响了效�?
            ))
            suggestions.add(ImprovementSuggestion(
                category = SuggestionCategory.TASK_PLANNING,
                description = "优化步骤执行顺序以提高效�?,
                priority = SuggestionPriority.HIGH,
                estimatedImpact = 0.6f,
                actionableSteps = listOf(
                    "分析步骤依赖关系",
                    "重新排序步骤以减少等待时�?,
                    "并行化可独立执行的步�?
                )
            ))
        }
    }

    private fun evaluateStepSequence(steps: List<ExecutionStep>): Double {
        var score = 0.0
        var logicalFlow = 0

        for (i in 0 until steps.size - 1) {
            val current = steps[i]
            val next = steps[i + 1]
            
            if (isLogicallySequential(current.action, next.action)) {
                logicalFlow++
            }
        }

        score = if (steps.size > 1) {
            logicalFlow.toDouble() / (steps.size - 1)
        } else {
            1.0
        }

        return score
    }

    private fun isLogicallySequential(action1: String, action2: String): Boolean {
        val action1Lower = action1.lowercase()
        val action2Lower = action2.lowercase()

        val sequentialPatterns = listOf(
            Pair("获取", "分析"),
            Pair("分析", "处理"),
            Pair("处理", "验证"),
            Pair("验证", "输出"),
            Pair("搜索", "解析"),
            Pair("解析", "生成")
        )

        return sequentialPatterns.any { (first, second) ->
            action1Lower.contains(first) && action2Lower.contains(second)
        }
    }

    private fun analyzeToolUsage(
        steps: List<ExecutionStep>,
        keyFactors: MutableList<KeyFactor>,
        suggestions: MutableList<ImprovementSuggestion>
    ) {
        val toolUsage = steps.groupBy { extractToolName(it.action) }
        
        toolUsage.forEach { (tool, toolSteps) ->
            val failures = toolSteps.count { !it.success }
            if (failures > 0 && failures >= toolSteps.size / 2) {
                keyFactors.add(KeyFactor(
                    factor = "工具使用不当: ${tool}",
                    impact = ImpactLevel.MEDIUM,
                    explanation = "${tool} 工具多次执行失败"
                ))
                suggestions.add(ImprovementSuggestion(
                    category = SuggestionCategory.TOOL_SELECTION,
                    description = "评估 ${tool} 工具的适用性或改进调用方式",
                    priority = SuggestionPriority.HIGH,
                    estimatedImpact = 0.7f,
                    actionableSteps = listOf(
                        "检查工具参数是否正�?,
                        "验证工具是否支持当前场景",
                        "考虑使用替代工具"
                    )
                ))
            }
        }
    }

    private fun extractToolName(action: String): String {
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

    private fun analyzeErrorHandling(
        failedSteps: List<ExecutionStep>,
        keyFactors: MutableList<KeyFactor>,
        suggestions: MutableList<ImprovementSuggestion>
    ) {
        if (failedSteps.isEmpty()) return

        val failureCategories = failedSteps.groupBy { categorizeFailure(it.result) }
        
        failureCategories.forEach { (category, failures) ->
            keyFactors.add(KeyFactor(
                factor = "错误类型: ${category}",
                impact = if (failures.size > 1) ImpactLevel.HIGH else ImpactLevel.MEDIUM,
                explanation = "${failures.size} 个步骤因 ${category} 失败"
            ))
            
            if (failures.size > 1) {
                suggestions.add(ImprovementSuggestion(
                    category = SuggestionCategory.ERROR_HANDLING,
                    description = "增强 ${category} 类型错误的处理能�?,
                    priority = SuggestionPriority.HIGH,
                    estimatedImpact = 0.75f,
                    actionableSteps = generateErrorHandlingSuggestions(category)
                ))
            }
        }
    }

    private fun categorizeFailure(result: String): String {
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

    private fun generateErrorHandlingSuggestions(category: String): List<String> {
        return when (category) {
            "权限问题" -> listOf(
                "在执行前检查权�?,
                "提供权限申请提示",
                "实现权限缓存机制"
            )
            "网络问题" -> listOf(
                "添加重试机制",
                "实现超时自动重连",
                "提供离线模式备选方�?
            )
            "超时问题" -> listOf(
                "优化执行逻辑减少耗时",
                "设置合理超时时间",
                "实现异步处理"
            )
            "参数错误" -> listOf(
                "加强参数校验",
                "提供参数默认�?,
                "实现参数自动修正"
            )
            else -> listOf(
                "添加错误日志记录",
                "实现错误恢复机制",
                "提供用户友好的错误提�?
            )
        }
    }

    private fun analyzePerformance(
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
            suggestions.add(ImprovementSuggestion(
                category = SuggestionCategory.PERFORMANCE,
                description = "优化步骤执行效率",
                priority = SuggestionPriority.MEDIUM,
                estimatedImpact = 0.5f,
                actionableSteps = listOf(
                    "分析耗时最长的步骤",
                    "优化资源密集型操�?,
                    "考虑并行执行"
                )
            ))
        }

        if (outcome.metrics.resourceUsage.cpuUsagePercent > 80.0) {
            keyFactors.add(KeyFactor(
                factor = "CPU使用率过�?,
                impact = ImpactLevel.LOW,
                explanation = "执行过程中CPU使用率超�?0%"
            ))
            suggestions.add(ImprovementSuggestion(
                category = SuggestionCategory.RESOURCE_MANAGEMENT,
                description = "优化CPU资源使用",
                priority = SuggestionPriority.LOW,
                estimatedImpact = 0.3f,
                actionableSteps = listOf(
                    "减少不必要的计算",
                    "实现资源释放机制",
                    "优化算法复杂�?
                )
            ))
        }
    }

    private fun generateLearnings(
        steps: List<ExecutionStep>,
        outcome: TaskOutcome,
        learnings: MutableList<Learning>
    ) {
        val successfulPatterns = steps.filter { it.success }
            .map { it.action }
            .groupBy { it.substringBefore("(").trim() }
            .filter { it.value.size >= 2 }

        successfulPatterns.forEach { (pattern, instances) ->
            learnings.add(Learning(
                insight = "模式 '${pattern}' 多次成功执行",
                applicableScenarios = listOf(
                    "类似任务的步骤规�?,
                    "工具选择决策",
                    "任务流程优化"
                ),
                confidence = 0.85f
            ))
        }

        if (outcome.success) {
            learnings.add(Learning(
                insight = "任务 '${outcome}' 整体执行成功",
                applicableScenarios = listOf(
                    "同类任务的参�?,
                    "成功案例�?,
                    "策略优化"
                ),
                confidence = 0.9f
            ))
        } else {
            learnings.add(Learning(
                insight = "任务失败，主要原�? ${outcome.errorMessage ?: "未知"}",
                applicableScenarios = listOf(
                    "失败案例分析",
                    "错误预防",
                    "改进策略"
                ),
                confidence = 0.7f
            ))
        }
    }

    private fun calculateConfidence(successfulSteps: Int, totalSteps: Int): Float {
        if (totalSteps == 0) return 0.5f
        
        val stepSuccessRate = successfulSteps.toFloat() / totalSteps
        val baseConfidence = 0.6f
        
        return (baseConfidence + stepSuccessRate * 0.4f).coerceIn(0.1f, 0.95f)
    }

    private suspend fun saveReflection(reflection: Reflection) {
        memoryRepository.createMemory(
            title = "任务反�? ${reflection.taskGoal}",
            content = reflection.toSummaryString(),
            source = "ReflectionEngine",
            folderPath = "反思记�?,
            tags = listOf("反�?, "任务分析", reflection.outcome.success.toString())
        )
    }

    private suspend fun applyLearnings(reflection: Reflection) {
        reflection.analysis.learnings.forEach { learning ->
            memoryRepository.createMemory(
                title = "学习洞察: ${learning.insight.take(30)}...",
                content = buildString {
                    appendLine("洞察: ${learning.insight}")
                    appendLine("适用场景:")
                    learning.applicableScenarios.forEach { appendLine("- ${it}") }
                    appendLine("置信�? ${learning.confidence}")
                },
                source = "ReflectionEngine",
                folderPath = "学习洞察",
                tags = listOf("学习", "经验", "最佳实�?)
            )
        }
    }

    fun generateSummary(reflection: Reflection): ReflectionSummary {
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

    suspend fun getReflectionsForTask(taskId: String): List<Reflection> {
        return emptyList()
    }

    suspend fun getAllReflections(): List<Reflection> {
        return emptyList()
    }
}

private fun Reflection.toSummaryString(): String {
    return buildString {
        appendLine("任务ID: ${taskId}")
        appendLine("任务目标: ${taskGoal}")
        appendLine("执行结果: ${if (outcome.success) "成功" else "失败"}")
        if (!outcome.success) {
            appendLine("错误信息: ${outcome.errorMessage}")
        }
        appendLine()
        appendLine("执行步骤:")
        executionSteps.forEach { step ->
            appendLine("  [${step.stepNumber}] ${step.action} - ${if (step.success) "成功" else "失败"} (${step.durationMs}ms)")
        }
        appendLine()
        appendLine("关键因素:")
        analysis.keyFactors.forEach { factor ->
            appendLine("  - ${factor.factor} (影响: ${factor.impact})")
        }
        appendLine()
        appendLine("改进建议:")
        analysis.suggestions.forEach { suggestion ->
            appendLine("  [${suggestion.priority}] ${suggestion.description}")
        }
    }
}

class ReflectionPrompts {
    val reflectionPrompt = """
        你是一个AI Agent反思助手。请分析以下任务执行过程并提供改进建议�?
        
        任务目标: {task_goal}
        
        执行步骤:
        {execution_steps}
        
        执行结果: {outcome}
        
        请提�?
        1. 执行质量评估
        2. 成功因素分析
        3. 失败原因分析
        4. 具体改进建议
        5. 可复用的经验教训
    """.trimIndent()
}