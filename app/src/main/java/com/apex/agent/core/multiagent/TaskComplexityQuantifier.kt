package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TaskComplexityQuantifier {

    data class TaskFeature(
        val category: String,
        val difficulty: Int, // 1-10
        val resourceRequirement: ResourceRequirement,
        val riskLevel: Int, // 1-5
        val estimatedTime: Int, // minutes
        val requiredSkills: List<String>
    )

    data class ResourceRequirement(
        val memory: Int, // MB
        val cpu: Int, // percentage
        val network: Int, // Mbps
        val storage: Int // MB
    )

    data class SubTaskTicket(
        val id: String,
        val description: String,
        val features: TaskFeature,
        val dependencies: List<String>
    )

    private val categoryKeywords = mapOf(
        "coding" to listOf("code", "编程", "开�", "软件", "程序", "algorithm", "算法"),
        "writing" to listOf("write", "写作", "文案", "文章", "内容", "创作"),
        "research" to listOf("research", "研究", "调查", "分析", "探索"),
        "design" to listOf("design", "设计", "规划", "布局", "界面"),
        "data" to listOf("data", "数据", "统计", "分析", "表格"),
        "communication" to listOf("communicate", "沟", "协调", "联系", "交流"),
        "planning" to listOf("plan", "计划", "规划", "安排", "调度"),
        "testing" to listOf("test", "测试", "验证", "检�", "调试"),
        "documentation" to listOf("document", "文档", "记录", "说明", "手册"),
        "other" to listOf("其他", "misc", "general")
    )

    private val difficultyPatterns = mapOf(
        1 to listOf("简�", "easy", "基础", "基本"),
        2 to listOf("较简�", "relatively easy"),
        3 to listOf("一", "normal", "普",),
        4 to listOf("较复�", "relatively complex"),
        5 to listOf("复杂", "complex", "中等"),
        6 to listOf("较困�", "relatively difficult"),
        7 to listOf("困难", "difficult"),
        8 to listOf("很困�", "very difficult"),
        9 to listOf("极困�", "extremely difficult"),
        10 to listOf("超级困难", "super difficult", "expert")
    )

    private val riskPatterns = mapOf(
        1 to listOf("低风�", "low risk", "安全"),
        2 to listOf("较低风险", "relatively low risk"),
        3 to listOf("中等风险", "medium risk"),
        4 to listOf("较高风险", "relatively high risk"),
        5 to listOf("高风�", "high risk", "危险")
    )

    private val skillMapping = mapOf(
        "coding" to listOf("编程", "算法", "数据结构", "调试"),
        "writing" to listOf("写作", "文案", "编辑", "内容创作"),
        "research" to listOf("研究", "分析", "调查", "信息收集"),
        "design" to listOf("设计", "创意", "美学", "用户体验"),
        "data" to listOf("数据分析", "统计", "数据可视图", "数据�",
        "communication" to listOf("沟", "协调", "表达", "谈判"),
        "planning" to listOf("计划", "组织", "调度", "项目管理"),
        "testing" to listOf("测试", "质量保证", "调试", "问题定位"),
        "documentation" to listOf("文档编写", "技术写�", "知识管理")
    )

    fun quantifyTask(taskDescription: String): TaskFeature {
        val startTime = System.currentTimeMillis()

        try {
            val category = identifyCategory(taskDescription)
            val difficulty = calculateDifficulty(taskDescription)
            val resourceRequirement = estimateResourceRequirement(category, difficulty)
            val riskLevel = assessRiskLevel(taskDescription, category)
            val estimatedTime = estimateTime(difficulty, category)
            val requiredSkills = identifyRequiredSkills(category, taskDescription)

            val elapsedTime = System.currentTimeMillis() - startTime
            if (elapsedTime > 1000) {
                // 超时降级处理
                return TaskFeature(
                    category = category,
                    difficulty = min(5, difficulty),
                    resourceRequirement = ResourceRequirement(128, 20, 5, 10),
                    riskLevel = min(3, riskLevel),
                    estimatedTime = min(30, estimatedTime),
                    requiredSkills = requiredSkills.take(3)
                )
            }

            return TaskFeature(
                category = category,
                difficulty = difficulty,
                resourceRequirement = resourceRequirement,
                riskLevel = riskLevel,
                estimatedTime = estimatedTime,
                requiredSkills = requiredSkills
            )
        } catch (e: Exception) {
            // 异常降级处理
            return TaskFeature(
                category = "other",
                difficulty = 3,
                resourceRequirement = ResourceRequirement(64, 10, 2, 5),
                riskLevel = 2,
                estimatedTime = 15,
                requiredSkills = listOf("通用能力")
            )
        }
    }

    fun createSubTaskTickets(originalTask: String, subTasks: List<String>): List<SubTaskTicket> {
        return subTasks.mapIndexed { index, subTask ->
            val features = quantifyTask(subTask)
            SubTaskTicket(
                id = "subtask_${System.currentTimeMillis()}_${index}",
                description = subTask,
                features = features,
                dependencies = emptyList()
            )
        }
    }

    private fun identifyCategory(taskDescription: String): String {
        val lowerDescription = taskDescription.lowercase()
        
        for ((category, keywords) in categoryKeywords) {
            if (keywords.any { lowerDescription.contains(it.lowercase()) }) {
                return category
            }
        }
        
        return "other"
    }

    private fun calculateDifficulty(taskDescription: String): Int {
        val lowerDescription = taskDescription.lowercase()
        var maxDifficulty = 1
        
        for ((difficulty, patterns) in difficultyPatterns) {
            if (patterns.any { lowerDescription.contains(it.lowercase()) }) {
                maxDifficulty = maxOf(maxDifficulty, difficulty)
            }
        }
        
        // 基于任务长度和复杂度进行调整
        val lengthScore = min((taskDescription.length / 100) + 1, 5)
        val complexityScore = if (taskDescription.contains(":") || taskDescription.contains("步骤") || taskDescription.contains("流程")) {
            2
        } else {
            0
        }
        
        return min(maxDifficulty + lengthScore + complexityScore, 10)
    }

    private fun estimateResourceRequirement(category: String, difficulty: Int): ResourceRequirement {
        val baseMemory = when (category) {
            "coding" -> 256
            "data" -> 512
            "design" -> 128
            else -> 64
        }
        
        val baseCpu = when (category) {
            "coding" -> 30
            "data" -> 40
            "design" -> 20
            else -> 15
        }
        
        val baseNetwork = when (category) {
            "research" -> 10
            "communication" -> 8
            else -> 5
        }
        
        val baseStorage = when (category) {
            "data" -> 100
            "design" -> 50
            else -> 20
        }
        
        val difficultyMultiplier = 1.0 + (difficulty - 1) * 0.1
        
        return ResourceRequirement(
            memory = (baseMemory * difficultyMultiplier).toInt(),
            cpu = min((baseCpu * difficultyMultiplier).toInt(), 100),
            network = (baseNetwork * difficultyMultiplier).toInt(),
            storage = (baseStorage * difficultyMultiplier).toInt()
        )
    }

    private fun assessRiskLevel(taskDescription: String, category: String): Int {
        val lowerDescription = taskDescription.lowercase()
        var maxRisk = 1
        
        for ((risk, patterns) in riskPatterns) {
            if (patterns.any { lowerDescription.contains(it.lowercase()) }) {
                maxRisk = maxOf(maxRisk, risk)
            }
        }
        
        // 基于类别和关键词调整风险等级
        if (category in listOf("coding", "data")) {
            maxRisk = maxOf(maxRisk, 2)
        }
        
        if (lowerDescription.contains("安全") || lowerDescription.contains("隐私") || lowerDescription.contains("机密")) {
            maxRisk = maxOf(maxRisk, 4)
        }
        
        if (lowerDescription.contains("紧",) || lowerDescription.contains("重要")) {
            maxRisk = maxOf(maxRisk, 3)
        }
        
        return min(maxRisk, 5)
    }

    private fun estimateTime(difficulty: Int, category: String): Int {
        val baseTime = when (category) {
            "coding" -> 60
            "data" -> 90
            "design" -> 45
            "research" -> 120
            else -> 30
        }
        
        val difficultyMultiplier = 1.0 + (difficulty - 1) * 0.2
        
        return (baseTime * difficultyMultiplier).toInt()
    }

    private fun identifyRequiredSkills(category: String, taskDescription: String): List<String> {
        val skills = mutableListOf<String>()
        
        // 基于类别添加基础技�",
        if (skillMapping.containsKey(category)) {
            skills.addAll(skillMapping[category] ?: emptyList())
        }
        
        // 基于任务描述添加特定技�",
        val lowerDescription = taskDescription.lowercase()
        
        if (lowerDescription.contains("python")) skills.add("Python")
        if (lowerDescription.contains("java")) skills.add("Java")
        if (lowerDescription.contains("javascript") || lowerDescription.contains("js")) skills.add("JavaScript")
        if (lowerDescription.contains("html") || lowerDescription.contains("css")) skills.add("Web前端")
        if (lowerDescription.contains("database") || lowerDescription.contains("数据�") skills.add("数据�",
        if (lowerDescription.contains("machine learning") || lowerDescription.contains("机器学习")) skills.add("机器学习")
        if (lowerDescription.contains("ai") || lowerDescription.contains("人工智能")) skills.add("人工智能")
        if (lowerDescription.contains("ui") || lowerDescription.contains("ux")) skills.add("UI/UX设计")
        if (lowerDescription.contains("project") || lowerDescription.contains("项目")) skills.add("项目管理")
        
        return skills.distinct().take(5)
    }
}
