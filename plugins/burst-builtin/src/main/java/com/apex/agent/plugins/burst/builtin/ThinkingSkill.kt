package com.apex.agent.plugins.burst.builtin

import android.util.Log
import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class ThinkingSkill : IBurstSkill {
    companion object {
        private const val TAG = "ThinkingSkill"
    }
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val complexityAnalyzer = ComplexityAnalyzer()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "thinking_agent",
            skillName = "思考Agent",
            version = "1.0.0",
            description = "智能任务分析和分解，支持动态深度规划和多层级执行计划生成",
            author = "Apex Agent",
            tags = listOf("planning", "decomposition", "complexity-analysis"),
            priority = 100,
            capabilities = listOf(
                "task_analysis",
                "dynamic_decomposition",
                "complexity_assessment",
                "plan_validation"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val llm = context.llmService
            val taskType = analyzeTaskType(task, llm)
            val assessment = complexityAnalyzer.assess(task, llm)
            val depth = assessment.recommendedDepth
            
            val plan = generateExecutionPlan(task, taskType, assessment, depth)

            val validation = validatePlan(plan)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = validation.isValid,
                output = buildString {
                    appendLine("Thinking completed:")
                    appendLine("- Task type: $taskType")
                    appendLine("- Complexity score: ${assessment.complexityScore}")
                    appendLine("- Decomposition depth: $depth")
                    appendLine("- Plan nodes: ${plan.nodes.size}")
                    appendLine("- Estimated time: ${assessment.estimatedDurationMs}ms")
                    if (!validation.isValid) {
                        appendLine("- Validation issues: ${validation.issues.joinToString()}")
                    }
                },
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = plan.nodes.size
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(success = false, errorMessage = e.message)
        }
    }
    
    private suspend fun analyzeTaskType(task: BurstTask, llm: ILLMService?): TaskType {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("分析以下任务描述，判断其类型。只返回类型名称。")
                appendLine("可选类型：")
                appendLine("SOFTWARE_DEVELOPMENT, SYSTEM_ARCHITECTURE, DATABASE_DESIGN")
                appendLine("FRONTEND_DEVELOPMENT, BACKEND_DEVELOPMENT, DEVOPS")
                appendLine("CODE_GENERATION, TEXT_ANALYSIS, DATA_ANALYSIS")
                appendLine("RESEARCH, WRITING, PROBLEM_SOLVING, MULTIMODAL_REASONING")
                appendLine("REPOSITORY_PROCESSING, UNKNOWN")
                appendLine()
                appendLine("任务描述：${task.description}")
            }
            val response = llm.generate(prompt, maxTokens = 64)
            return try {
                TaskType.valueOf(response.trim().uppercase())
            } catch (_: Exception) {
                classifyByKeywords(task.description)
            }
        }
        return classifyByKeywords(task.description)
    }

    private fun classifyByKeywords(goal: String): TaskType {
        val g = goal.lowercase()
        return when {
            g.contains("software") || g.contains("project") -> TaskType.SOFTWARE_DEVELOPMENT
            g.contains("architecture") || g.contains("system design") -> TaskType.SYSTEM_ARCHITECTURE
            g.contains("database") || g.contains("data model") -> TaskType.DATABASE_DESIGN
            g.contains("frontend") || g.contains("ui") || g.contains("client") -> TaskType.FRONTEND_DEVELOPMENT
            g.contains("backend") || g.contains("server") || g.contains("api") -> TaskType.BACKEND_DEVELOPMENT
            g.contains("devops") || g.contains("ci/cd") || g.contains("deployment") -> TaskType.DEVOPS
            g.contains("code") || g.contains("program") -> TaskType.CODE_GENERATION
            g.contains("analyze") || g.contains("summarize") || g.contains("read") -> TaskType.TEXT_ANALYSIS
            g.contains("repository") || g.contains("git") -> TaskType.REPOSITORY_PROCESSING
            g.contains("image") || g.contains("video") || g.contains("audio") -> TaskType.MULTIMODAL_REASONING
            g.contains("data") || g.contains("statistics") || g.contains("analytics") -> TaskType.DATA_ANALYSIS
            g.contains("research") || g.contains("study") -> TaskType.RESEARCH
            g.contains("write") || g.contains("create") || g.contains("compose") -> TaskType.WRITING
            g.contains("solve") || g.contains("fix") || g.contains("problem") -> TaskType.PROBLEM_SOLVING
            else -> TaskType.UNKNOWN
        }
    }
    
    private fun generateExecutionPlan(
        task: BurstTask,
        taskType: TaskType,
        assessment: ComplexityAssessment,
        maxDepth: Int
    ): ExecutionPlan {
        val nodes = generateNodesDynamic(task, taskType, maxDepth, assessment)
        val edges = generateEdges(nodes)
        val modelRoutes = generateModelRoutes(nodes, taskType)
        val complexityScore = calculateComplexityScore(nodes)
        
        return ExecutionPlan(
            planId = "plan_${System.currentTimeMillis()}",
            taskId = task.id,
            taskType = taskType.name,
            nodes = nodes,
            edges = edges,
            modelRoutes = modelRoutes,
            complexityScore = complexityScore,
            estimatedTotalTimeMs = assessment.estimatedDurationMs
        )
    }
    
    private fun generateNodesDynamic(
        task: BurstTask,
        taskType: TaskType,
        maxDepth: Int,
        assessment: ComplexityAssessment
    ): List<PlanNode> {
        val nodes = mutableListOf<PlanNode>()
        
        val goalNode = PlanNode(
            nodeId = "goal",
            type = NodeType.GOAL.name,
            description = task.description,
            estimatedTimeMs = 0,
            priority = 0,
            requiredTools = emptyList(),
            complexity = 0,
            level = 0
        )
        nodes.add(goalNode)
        
        var currentLevel = 1
        
        if (currentLevel <= maxDepth) {
            nodes.add(createNode("system_architecture", "系统架构设计", 90, 1, assessment.estimatedLines))
            nodes.add(createNode("technology_stack", "技术栈选型", 70, 1, assessment.estimatedLines))
            currentLevel++
        }
        
        if (currentLevel <= maxDepth) {
            val moduleCount = assessment.moduleCount.coerceAtMost(10)
            for (i in 1..moduleCount) {
                nodes.add(createNode("module_$i", "功能模块$i", 60 + i % 20, 2))
            }
            currentLevel++
        }
        
        if (currentLevel <= maxDepth) {
            nodes.add(createNode("file_controllers", "控制器层", 50, 3))
            nodes.add(createNode("file_services", "服务层", 60, 3))
            nodes.add(createNode("file_models", "数据模型层", 45, 3))
            currentLevel++
        }
        
        if (currentLevel <= maxDepth) {
            val fileCount = minOf(assessment.moduleCount * 2, 50)
            for (i in 1..fileCount) {
                nodes.add(createNode("file_$i", "实现文件$i", 30 + i % 15, 4))
            }
            currentLevel++
        }
        
        if (currentLevel <= maxDepth) {
            for (i in 1..minOf(assessment.moduleCount * 3, 100)) {
                nodes.add(createNode("impl_$i", "具体实现$i", 20 + i % 10, 5))
            }
        }
        
        return nodes
    }
    
    private fun createNode(
        nodeId: String, description: String, complexity: Int, level: Int, lines: Long? = null
    ): PlanNode {
        val type = NodeType.entries.first { it.name.contains(levelName(level)) }
        val (estimatedTimeMs, priority, requiredTools) = configForLevel(level, lines)
        return PlanNode(
            nodeId = nodeId, type = type.name, description = description,
            estimatedTimeMs = estimatedTimeMs, priority = priority,
            requiredTools = requiredTools, complexity = complexity, level = level
        )
    }

    private fun levelName(level: Int) = when (level) {
        1 -> "SYSTEM_LEVEL"; 2 -> "MODULE_LEVEL"; 3 -> "FILE_TYPE_LEVEL"
        4 -> "FILE_LEVEL"; 5 -> "IMPLEMENTATION_LEVEL"; else -> error("Invalid level")
    }

    private fun configForLevel(level: Int, lines: Long?): Triple<Long, Int, List<String>> = when (level) {
        1 -> Triple(1800000L * ((lines ?: 1000) / 1000).coerceAtLeast(1), 1, listOf("architecture", "design"))
        2 -> Triple(3600000L, 2, listOf("code", "design"))
        3 -> Triple(2700000L, 3, listOf("code"))
        4 -> Triple(900000L, 4, listOf("code"))
        5 -> Triple(600000L, 5, listOf("code"))
        else -> error("Invalid level")
    }
    
    private fun generateEdges(nodes: List<PlanNode>): List<PlanEdge> {
        val edges = mutableListOf<PlanEdge>()
        val level0Nodes = nodes.filter { it.level == 0 }
        val level1Nodes = nodes.filter { it.level == 1 }
        val level2Nodes = nodes.filter { it.level == 2 }
        val level3Nodes = nodes.filter { it.level == 3 }
        val level4Nodes = nodes.filter { it.level == 4 }
        val level5Nodes = nodes.filter { it.level == 5 }
        
        level0Nodes.forEach { level0 ->
            level1Nodes.forEach { level1 -> edges.add(PlanEdge(fromNodeId = level0.nodeId, toNodeId = level1.nodeId)) }
        }
        level1Nodes.forEach { level1 ->
            level2Nodes.forEach { level2 -> edges.add(PlanEdge(fromNodeId = level1.nodeId, toNodeId = level2.nodeId)) }
        }
        level2Nodes.forEach { level2 ->
            level3Nodes.forEach { level3 -> edges.add(PlanEdge(fromNodeId = level2.nodeId, toNodeId = level3.nodeId)) }
        }
        level3Nodes.forEach { level3 ->
            level4Nodes.forEach { level4 -> edges.add(PlanEdge(fromNodeId = level3.nodeId, toNodeId = level4.nodeId)) }
        }
        level4Nodes.forEach { level4 ->
            level5Nodes.forEach { level5 -> edges.add(PlanEdge(fromNodeId = level4.nodeId, toNodeId = level5.nodeId)) }
        }
        return edges
    }
    
    private fun generateModelRoutes(nodes: List<PlanNode>, taskType: TaskType): Map<String, String> {
        val routes = mutableMapOf<String, String>()
        nodes.forEach { node ->
            val model = when (node.level) {
                0, 1 -> "claude-3.5"
                2 -> when (taskType) {
                    TaskType.CODE_GENERATION, TaskType.SOFTWARE_DEVELOPMENT -> "deepseek-coder"
                    else -> "claude-3.5"
                }
                else -> when (taskType) {
                    TaskType.CODE_GENERATION, TaskType.SOFTWARE_DEVELOPMENT -> "deepseek-coder"
                    TaskType.FRONTEND_DEVELOPMENT -> "qwen-max"
                    else -> "claude-3.5"
                }
            }
            routes[node.nodeId] = model
        }
        return routes
    }
    
    private fun calculateComplexityScore(nodes: List<PlanNode>): Int {
        return nodes.sumOf { it.complexity } / maxOf(1, nodes.size)
    }
    
    private fun validatePlan(plan: ExecutionPlan): ValidationResult {
        val issues = mutableListOf<String>()
        if (plan.nodes.isEmpty()) issues.add("No nodes in plan")
        
        plan.edges.forEach { edge ->
            val fromExists = plan.nodes.any { it.nodeId == edge.fromNodeId }
            val toExists = plan.nodes.any { it.nodeId == edge.toNodeId }
            if (!fromExists || !toExists) issues.add("Invalid edge: ${edge.fromNodeId} -> ${edge.toNodeId}")
        }
        
        val cycles = detectCycles(plan)
        if (cycles.isNotEmpty()) {
            cycles.forEach { cycle -> issues.add("Circular dependency: ${cycle.joinToString(" -> ")}") }
        }
        
        val levelIssues = validateLevelStructure(plan.nodes)
        issues.addAll(levelIssues)
        
        return ValidationResult(isValid = issues.isEmpty(), issues = issues)
    }
    
    private fun detectCycles(plan: ExecutionPlan): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val adjacencyList = mutableMapOf<String, MutableList<String>>()
        plan.edges.forEach { edge ->
            adjacencyList.computeIfAbsent(edge.fromNodeId) { mutableListOf() }.add(edge.toNodeId)
        }
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()
        
        fun dfs(node: String) {
            if (node in recursionStack) {
                val cycleStartIndex = path.indexOf(node)
                if (cycleStartIndex != -1) cycles.add(path.subList(cycleStartIndex, path.size) + node)
                return
            }
            if (node in visited) return
            visited.add(node)
            recursionStack.add(node)
            path.add(node)
            adjacencyList[node]?.forEach { neighbor -> dfs(neighbor) }
            recursionStack.remove(node)
            path.removeLast()
        }
        
        plan.nodes.forEach { node ->
            if (node.nodeId !in visited) dfs(node.nodeId)
        }
        return cycles
    }
    
    private fun validateLevelStructure(nodes: List<PlanNode>): List<String> {
        val issues = mutableListOf<String>()
        val levelCounts = nodes.groupBy { it.level }.mapValues { it.value.size }
        if (!levelCounts.containsKey(1) || levelCounts[1] == 0) issues.add("No system-level nodes found")
        val maxLevel = levelCounts.keys.maxOrNull() ?: 0
        if (maxLevel < 2) issues.add("Insufficient decomposition depth")
        return issues
    }
    
    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.90f
    
    data class ValidationResult(val isValid: Boolean, val issues: List<String>)
}

class ComplexityAnalyzer {
    companion object {
        private const val TAG = "ComplexityAnalyzer"
    }

    suspend fun assess(task: BurstTask, llm: ILLMService? = null): ComplexityAssessment {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("评估以下任务的复杂度，以JSON格式返回评估结果。")
                appendLine("任务：${task.description}")
                appendLine()
                appendLine("JSON格式：")
                appendLine("""{"estimatedLines": 数字, "moduleCount": 数字, "recommendedDepth": 数字(1-6), "complexityScore": 数字(1-10)}""")
            }
            val response = llm.generate(prompt, maxTokens = 128)
            try {
                val json = response.substringAfter("{").substringBeforeLast("}")
                val lines = Regex(""""estimatedLines"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 1000
                val modules = Regex(""""moduleCount"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 3
                val depth = Regex(""""recommendedDepth"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 3
                val score = Regex(""""complexityScore"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 3
                return ComplexityAssessment(
                    estimatedLines = lines,
                    moduleCount = modules,
                    recommendedDepth = depth.coerceIn(1, 6),
                    estimatedDurationMs = lines / 10,
                    complexityScore = score.coerceIn(1, 10)
                )
            } catch (e: Exception) { Log.e(TAG, "assessComplexity fallback to heuristic", e) }
        }
        return assessHeuristic(task)
    }

    private fun assessHeuristic(task: BurstTask): ComplexityAssessment {
        val description = task.description
        val length = description.length
        
        return when {
            length > 500 -> ComplexityAssessment(5000, 5, 4, 500000, 4)
            length > 200 -> ComplexityAssessment(2000, 3, 3, 200000, 3)
            else -> ComplexityAssessment(500, 1, 2, 50000, 2)
        }
    }
}
