package com.apex.agent.orchestration.core

import com.apex.agent.orchestration.core.AllocationModels.ComplexityReport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskComplexityQuantifier constructor() {

    fun quantifyTask(taskDescription: String): ComplexityReport {
        val category = identifyCategory(taskDescription)
        val difficulty = estimateDifficulty(taskDescription)
        val complexityScore = computeComplexityScore(taskDescription, difficulty)
        return ComplexityReport(
            category = category,
            difficulty = difficulty,
            complexityScore = complexityScore,
            resourceRequirement = estimateResourceRequirement(category, difficulty),
            riskLevel = estimateRiskLevel(taskDescription),
            estimatedTimeMinutes = estimateTime(difficulty),
            requiredSkills = identifyRequiredSkills(category, taskDescription),
            reasoning = buildReasoning(taskDescription, category, difficulty, complexityScore)
        )
    }

    fun createSubTaskTickets(originalTask: String, subTasks: List<String>): List<SubTaskTicket> {
        return subTasks.mapIndexed { index, description ->
            SubTaskTicket(
                id = "subtask_${System.currentTimeMillis()}_$index",
                description = description,
                features = quantifyTask(description)
            )
        }
    }

    data class SubTaskTicket(
        val id: String,
        val description: String,
        val features: ComplexityReport
    )

    private val categoryPatterns = listOf(
        "coding" to listOf(
            "code", "program", "开�?, "编程", "implement", "function", "class",
            "algorithm", "api", "endpoint", "database", "sql", "bug", "fix", "refactor"
        ),
        "debugging" to listOf(
            "debug", "bug", "error", "crash", "异常", "排查", "fix", "issue", "trace",
            "stack trace", "log", "diagnos"
        ),
        "testing" to listOf(
            "test", "unit test", "integration test", "e2e", "mock", "assert",
            "coverage", "测试", "验证"
        ),
        "writing" to listOf(
            "write", "document", "doc", "readme", "article", "blog", "文案", "写作",
            "content", "copy", "tutorial", "guide"
        ),
        "research" to listOf(
            "research", "investigate", "study", "分析", "研究", "survey", "explore",
            "literature", "paper", "comparison"
        ),
        "analysis" to listOf(
            "analy", "review", "audit", "评估", "评估", "metric", "dashboard",
            "report", "statistics"
        ),
        "data" to listOf(
            "data", "etl", "pipeline", "dataset", "csv", "json", "xml", "parse",
            "transform", "数据", "migration", "import", "export"
        ),
        "design" to listOf(
            "design", "ui", "ux", "layout", "mockup", "prototype", "wireframe",
            "figma", "sketch", "设计"
        ),
        "planning" to listOf(
            "plan", "roadmap", "sprint", "milestone", "schedule", "timeline",
            "规划", "strategy", "architecture"
        ),
        "creative" to listOf(
            "creative", "idea", "brainstorm", "innovate", "campaign", "marketing",
            "创意", "创作", "story", "narrative"
        ),
        "devops" to listOf(
            "deploy", "ci/cd", "pipeline", "docker", "kubernetes", "k8s",
            "infrastructure", "terraform", "ansible", "monitoring"
        ),
        "security" to listOf(
            "security", "auth", "permission", "encrypt", "vulnerability", "owasp",
            "安全", "ssl", "oauth", "jwt", "xss", "sql injection"
        )
    )

    fun identifyCategory(description: String): String {
        val lower = description.lowercase()
        val scores = categoryPatterns.map { (category, patterns) ->
            val matchCount = patterns.count { pattern -> lower.contains(pattern) }
            category to matchCount
        }
        val best = scores.maxByOrNull { it.second }
        return if (best != null && best.second > 0) best.first else "other"
    }

    fun estimateDifficulty(description: String): Int {
        val lower = description.lowercase()
        var score = 3

        val complexityIndicators = listOf(
            3 to listOf("complex", "复杂", "advanced", "difficult", "挑战", "challenging"),
            2 to listOf("large", "大规�?, "extensive", "multi-step", "multi module"),
            2 to listOf("distributed", "distributed system", "microservice", "高并�?),
            2 to listOf("optimize", "optimization", "performance", "高可�?),
            1 to listOf("integrate", "integration", "multiple", "多个"),
            1 to listOf("concurrent", "parallel", "async", "异步"),
            1 to listOf("security", "encrypt", "authentication"),
            -1 to listOf("simple", "简�?, "basic", "trivial", "minor", "小幅"),
            -1 to listOf("typo", "rename", "cosmetic", "format"),
            -2 to listOf("quick", "快�?, "easy", "straightforward")
        )

        for ((delta, patterns) in complexityIndicators) {
            if (patterns.any { lower.contains(it) }) {
                score += delta
            }
        }

        if (lower.length > 200) score += 1
        if (lower.length > 500) score += 1
        if (lower.contains("\n") && lower.lines().size > 20) score += 1

        return score.coerceIn(1, 10)
    }

    fun computeComplexityScore(description: String, difficulty: Int): Float {
        val difficultyScore = difficulty.toFloat() / 10f
        val lengthScore = (description.length.toFloat() / 1000f).coerceAtMost(0.3f)
        val indicatorScore = countComplexityIndicators(description).toFloat() / 10f
        return (difficultyScore * 0.5f + lengthScore * 0.2f + indicatorScore * 0.3f).coerceIn(0f, 1f)
    }

    private fun estimateResourceRequirement(category: String, difficulty: Int): ComplexityReport.ResourceRequirement {
        val multiplier = 1.0 + (difficulty - 1) * 0.15
        val baseMemory = when (category) {
            "coding" -> 256; "data" -> 512; "debugging" -> 384
            "analysis" -> 256; "research" -> 192; "devops" -> 320
            "security" -> 320; "testing" -> 256; "design" -> 128
            else -> 128
        }
        return ComplexityReport.ResourceRequirement(
            memory = (baseMemory * multiplier).toInt(),
            cpu = (20 * multiplier).toInt().coerceAtMost(100),
            network = (5 * multiplier).toInt(),
            storage = (20 * multiplier).toInt()
        )
    }

    private fun estimateRiskLevel(description: String): Int {
        val lower = description.lowercase()
        var risk = 2
        val riskIndicators = listOf(
            3 to listOf("high risk", "高风�?, "critical", "production"),
            2 to listOf("security", "secure", "auth", "payment", "finance"),
            2 to listOf("migration", "migrate", "数据迁移", "升级"),
            1 to listOf("dependency", "第三�?, "external api"),
            1 to listOf("deadline", "urgent", "紧�?)
        )
        for ((delta, patterns) in riskIndicators) {
            if (patterns.any { lower.contains(it) }) {
                risk += delta
            }
        }
        return risk.coerceIn(1, 5)
    }

    private fun estimateTime(difficulty: Int): Int {
        return when {
            difficulty <= 2 -> difficulty * 5
            difficulty <= 5 -> difficulty * 10
            difficulty <= 8 -> difficulty * 20
            else -> difficulty * 30
        }
    }

    private fun identifyRequiredSkills(category: String, description: String): List<String> {
        val skills = mutableListOf<String>()
        val lower = description.lowercase()
        val categorySkills = mapOf(
            "coding" to listOf("编程", "调试", "代码审查"),
            "debugging" to listOf("调试", "问题排查", "日志分析"),
            "testing" to listOf("测试", "自动化测�?, "质量保证"),
            "writing" to listOf("写作", "编辑", "内容规划"),
            "research" to listOf("研究", "分析", "信息检�?),
            "analysis" to listOf("数据分析", "逻辑分析", "报告撰写"),
            "data" to listOf("数据处理", "ETL", "数据�?),
            "design" to listOf("UI设计", "用户体验", "原型设计"),
            "planning" to listOf("项目管理", "规划", "风险评估"),
            "creative" to listOf("创意", "内容创作", "头脑风暴"),
            "devops" to listOf("DevOps", "CI/CD", "容器�?),
            "security" to listOf("安全", "渗透测�?, "安全审计")
        )
        skills.addAll(categorySkills[category] ?: emptyList())
        val techPatterns = mapOf(
            "Python" to listOf("python"), "Java" to listOf("java"), "Kotlin" to listOf("kotlin"),
            "JavaScript" to listOf("javascript", "js", "node"), "TypeScript" to listOf("typescript", "ts"),
            "Go" to listOf("golang", "go "), "Rust" to listOf("rust"),
            "React" to listOf("react"), "Vue" to listOf("vue"), "Angular" to listOf("angular"),
            "Docker" to listOf("docker"), "Kubernetes" to listOf("kubernetes", "k8s"),
            "SQL" to listOf("sql", "mysql", "postgresql"), "NoSQL" to listOf("mongodb", "redis"),
            "AWS" to listOf("aws", "cloud"), "Android" to listOf("android"),
            "iOS" to listOf("ios", "swift")
        )
        for ((tech, patterns) in techPatterns) {
            if (patterns.any { lower.contains(it) }) skills.add(tech)
        }
        return skills.distinct().take(8)
    }

    private fun buildReasoning(description: String, category: String, difficulty: Int, score: Float): String {
        val diffLabel = when {
            difficulty <= 3 -> "简�?
            difficulty <= 6 -> "中等"
            difficulty <= 8 -> "复杂"
            else -> "极复�?
        }
        return "类别: $category, 难度: $diffLabel($difficulty/10), 复杂度分�? ${"%.2f".format(score)}"
    }

    private fun countComplexityIndicators(description: String): Int {
        val indicators = listOf(
            "integrat", "distribut", "concurr", "optimiz", "scalable",
            "resilien", "failover", "redundan", "consisten", "transaction",
            "orchestrat", "pipeline", "workflow", "asynchro"
        )
        val lower = description.lowercase()
        return indicators.count { lower.contains(it) }
    }
}
