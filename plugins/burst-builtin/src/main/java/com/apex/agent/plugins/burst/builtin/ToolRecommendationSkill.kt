package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

/**
 * 工具推荐技能
 * 实现任务意图检测、工具推荐算法、工作流构建
 */
class ToolRecommendationSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 意图关键词映射
    private val intentKeywords = mapOf(
        TaskIntent.CODE_DEVELOPMENT to listOf("写代码", "编写", "开发", "实现", "创建", "编程", "write code", "develop", "implement"),
        TaskIntent.CODE_ANALYSIS to listOf("分析", "检查", "评估", "审查", "analyze", "check", "evaluate"),
        TaskIntent.CODE_REFACTORING to listOf("重构", "优化", "重写", "refactor", "optimize"),
        TaskIntent.TESTING to listOf("测试", "单元测试", "test", "unit test"),
        TaskIntent.DEBUGGING to listOf("调试", "修复bug", "debug", "fix bug"),
        TaskIntent.DEPLOYMENT to listOf("部署", "发布", "构建", "deploy", "release", "build"),
        TaskIntent.WRITING_CREATION to listOf("写作", "创作", "文章", "writing", "create", "article"),
        TaskIntent.DATA_ANALYSIS to listOf("分析", "数据分析", "统计", "analyze", "data analysis"),
        TaskIntent.INFORMATION_RETRIEVAL to listOf("搜索", "查询", "信息", "search", "query"),
        TaskIntent.UNKNOWN to emptyList()
    )
    
    // 工具能力映射
    private val toolCapabilities = mapOf(
        "code_generate" to listOf("写代码", "开发", "实现", "创建"),
        "code_quality_analyze" to listOf("质量", "分析", "检测", "安全"),
        "unit_test_generate" to listOf("测试", "单元测试", "测试用例"),
        "api_doc_generate" to listOf("API文档", "接口文档", "生成文档"),
        "debugger_attach" to listOf("调试", "断点", "调试器"),
        "context_summarize" to listOf("摘要", "压缩", "总结"),
        "task_decompose" to listOf("分解", "子任务", "任务拆分"),
        "web_search" to listOf("网页", "搜索", "信息")
    )
    
    init {
        manifest = BurstSkillManifest(
            skillId = "tool_recommendation",
            skillName = "工具推荐",
            version = "1.0.0",
            description = "智能分析任务意图，推荐最合适的工具组合和工作流",
            author = "Apex Agent",
            tags = listOf("tool", "recommendation", "intent-detection"),
            priority = 88,
            capabilities = listOf(
                "intent_detection",
                "tool_recommendation",
                "workflow_building"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val taskDescription = task.input.text ?: task.description
            
            val intent = detectIntent(taskDescription)
            val capabilities = extractRequiredCapabilities(taskDescription)
            val complexity = estimateComplexity(taskDescription, intent)
            val recommendations = recommendTools(taskDescription, intent, capabilities)
            val workflow = buildWorkflow(intent, recommendations)
            val warnings = generateWarnings(taskDescription, intent, recommendations)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = """
                    |Tool recommendation completed:
                    |- Detected intent: $intent
                    |- Estimated complexity: $complexity
                    |- Recommended tools: ${recommendations.size}
                    ${recommendations.take(3).joinToString("\n") { "- ${it.toolName}: ${it.reason}" }}
                    ${if (warnings.isNotEmpty()) "- Warnings: ${warnings.joinToString()}" else ""}
                """.trimMargin(),
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = recommendations.size
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    private fun detectIntent(taskDescription: String): TaskIntent {
        val lowerDesc = taskDescription.lowercase()
        var maxScore = 0f
        var detectedIntent = TaskIntent.UNKNOWN
        
        intentKeywords.forEach { (intent, keywords) ->
            var score = 0f
            keywords.forEach { keyword ->
                if (lowerDesc.contains(keyword.lowercase())) {
                    score += 1f
                }
            }
            if (keywords.isNotEmpty()) {
                val normalizedScore = score / keywords.size
                if (normalizedScore > maxScore) {
                    maxScore = normalizedScore
                    detectedIntent = intent
                }
            }
        }
        
        return detectedIntent
    }
    
    private fun extractRequiredCapabilities(taskDescription: String): List<String> {
        val capabilities = mutableSetOf<String>()
        val lowerDesc = taskDescription.lowercase()
        
        toolCapabilities.forEach { (tool, keywords) ->
            keywords.forEach { keyword ->
                if (lowerDesc.contains(keyword.lowercase())) {
                    capabilities.add(keyword)
                }
            }
        }
        
        return capabilities.toList()
    }
    
    private fun estimateComplexity(taskDescription: String, intent: TaskIntent): TaskComplexity {
        val length = taskDescription.length
        val wordCount = taskDescription.split("\\s+").size
        
        val hasMultiStep = taskDescription.contains("然后") || taskDescription.contains("接下来") || taskDescription.contains("finally")
        val hasComplex = taskDescription.contains("复杂") || taskDescription.contains("困难") || taskDescription.contains("complex")
        
        var complexityScore = 0
        
        when {
            length > 500 || wordCount > 100 -> complexityScore += 2
            length > 200 || wordCount > 40 -> complexityScore += 1
        }
        
        if (hasMultiStep) complexityScore += 1
        if (hasComplex) complexityScore += 2
        
        return when {
            complexityScore >= 5 -> TaskComplexity.EXTREME
            complexityScore >= 3 -> TaskComplexity.COMPLEX
            complexityScore >= 2 -> TaskComplexity.MODERATE
            complexityScore >= 1 -> TaskComplexity.SIMPLE
            else -> TaskComplexity.TRIVIAL
        }
    }
    
    private fun recommendTools(
        taskDescription: String,
        intent: TaskIntent,
        capabilities: List<String>
    ): List<ToolRecommendation> {
        val recommendations = mutableListOf<ToolRecommendation>()
        val lowerDesc = taskDescription.lowercase()
        
        val primaryTools = getPrimaryToolsForIntent(intent)
        
        primaryTools.forEachIndexed { index, toolName ->
            val confidence = calculateConfidence(toolName, taskDescription, intent)
            val reason = generateReason(toolName, taskDescription, intent)
            
            recommendations.add(ToolRecommendation(
                toolName = toolName,
                confidence = confidence,
                reason = reason,
                priority = index + 1,
                required = index < 2,
                alternativeTools = getAlternativeTools(toolName)
            ))
        }
        
        return recommendations.sortedByDescending { it.confidence }
    }
    
    private fun calculateConfidence(toolName: String, taskDescription: String, intent: TaskIntent): Float {
        val keywords = toolCapabilities[toolName] ?: emptyList()
        var matchCount = 0
        keywords.forEach { keyword ->
            if (taskDescription.lowercase().contains(keyword.lowercase())) {
                matchCount += 2
            }
        }
        
        val primaryTools = getPrimaryToolsForIntent(intent)
        if (toolName in primaryTools.take(2)) {
            matchCount += 3
        }
        
        return (matchCount / 10f).coerceIn(0.1f, 1.0f)
    }
    
    private fun generateReason(toolName: String, taskDescription: String, intent: TaskIntent): String {
        val keywords = toolCapabilities[toolName] ?: emptyList()
        val matchedKeywords = keywords.filter { taskDescription.lowercase().contains(it.lowercase()) }
        
        return when {
            matchedKeywords.isNotEmpty() -> "匹配关键词: ${matchedKeywords.take(2).joinToString(", ")}"
            else -> "适合${intent.name}任务"
        }
    }
    
    private fun getPrimaryToolsForIntent(intent: TaskIntent): List<String> {
        return when (intent) {
            TaskIntent.CODE_DEVELOPMENT -> listOf("code_generate", "code_quality_analyze", "api_doc_generate")
            TaskIntent.CODE_ANALYSIS -> listOf("code_quality_analyze", "context_summarize", "task_decompose")
            TaskIntent.TESTING -> listOf("unit_test_generate", "code_quality_analyze", "debugger_attach")
            TaskIntent.DEBUGGING -> listOf("debugger_attach", "context_summarize", "code_quality_analyze")
            TaskIntent.DEPLOYMENT -> listOf("web_search", "context_summarize", "task_decompose")
            TaskIntent.WRITING_CREATION -> listOf("context_summarize", "task_decompose", "web_search")
            TaskIntent.DATA_ANALYSIS -> listOf("context_summarize", "task_decompose", "web_search")
            else -> listOf("task_decompose", "context_summarize", "web_search")
        }
    }
    
    private fun getAlternativeTools(toolName: String): List<String> {
        return when (toolName) {
            "code_quality_analyze" -> listOf("code_generate", "api_doc_generate")
            "unit_test_generate" -> listOf("code_quality_analyze")
            else -> emptyList()
        }
    }
    
    private fun buildWorkflow(
        intent: TaskIntent,
        recommendations: List<ToolRecommendation>
    ): List<WorkflowStep> {
        val workflow = mutableListOf<WorkflowStep>()
        val requiredTools = recommendations.filter { it.required }.take(4)
        
        requiredTools.forEachIndexed { index, tool ->
            workflow.add(WorkflowStep(
                stepNumber = index + 1,
                toolName = tool.toolName,
                description = getToolDescription(tool.toolName),
                dependsOn = if (index > 0) listOf(index) else emptyList(),
                parallelizable = canParallelize(tool.toolName, intent)
            ))
        }
        
        return workflow
    }
    
    private fun getToolDescription(toolName: String): String {
        return when (toolName) {
            "code_quality_analyze" -> "分析代码质量和潜在问题"
            "code_generate" -> "生成代码骨架"
            "unit_test_generate" -> "生成单元测试"
            "api_doc_generate" -> "生成API文档"
            "debugger_attach" -> "附加调试器进行调试"
            "context_summarize" -> "生成内容摘要"
            "task_decompose" -> "分解复杂任务"
            else -> "执行工具: $toolName"
        }
    }
    
    private fun canParallelize(toolName: String, intent: TaskIntent): Boolean {
        val parallelizableTools = setOf("code_quality_analyze", "context_summarize", "web_search")
        return toolName in parallelizableTools
    }
    
    private fun generateWarnings(
        taskDescription: String,
        intent: TaskIntent,
        recommendations: List<ToolRecommendation>
    ): List<String> {
        val warnings = mutableListOf<String>()
        
        if (recommendations.any { it.toolName in listOf("debugger_attach") }) {
            warnings.add("当前任务包含可能的风险操作，请谨慎执行")
        }
        
        if (intent == TaskIntent.DEPLOYMENT) {
            warnings.add("此任务类型风险较高，建议先在测试环境验证")
        }
        
        if (recommendations.size > 4) {
            warnings.add("检测到多工具组合，可能需要较长执行时间")
        }
        
        return warnings
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.87f
    
    // 任务意图
    enum class TaskIntent {
        CODE_DEVELOPMENT, CODE_ANALYSIS, CODE_REFACTORING, TESTING, DOCUMENTATION,
        DEBUGGING, DEPLOYMENT, DATA_PROCESSING, VERSION_CONTROL,
        WRITING_CREATION, CONTENT_WRITING, COPYWRITING,
        DATA_ANALYSIS, DATA_VISUALIZATION, STATISTICS,
        MULTIMEDIA_PROCESSING, IMAGE_PROCESSING, AUDIO_VIDEO_PROCESSING,
        INFORMATION_RETRIEVAL, WEB_SEARCH, KNOWLEDGE_QUERY,
        DAILY_LIFE, SCHEDULING, OFFICE_PRODUCTIVITY, DOCUMENT_PROCESSING, PRESENTATION,
        CREATIVE_TOOLS, IDEA_GENERATION, GENERAL_INQUIRY, UNKNOWN
    }
    
    // 任务复杂度
    enum class TaskComplexity {
        TRIVIAL, SIMPLE, MODERATE, COMPLEX, EXTREME
    }
    
    // 工具推荐
    data class ToolRecommendation(
        val toolName: String,
        val confidence: Float,
        val reason: String,
        val priority: Int,
        val required: Boolean,
        val alternativeTools: List<String> = emptyList()
    )
    
    // 工作流步骤
    data class WorkflowStep(
        val stepNumber: Int,
        val toolName: String,
        val description: String,
        val dependsOn: List<Int> = emptyList(),
        val parallelizable: Boolean = false
    )
}
