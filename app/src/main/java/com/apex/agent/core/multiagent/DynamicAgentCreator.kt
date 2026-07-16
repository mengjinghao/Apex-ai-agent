package com.apex.agent.core.multiagent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DynamicAgentCreator {

    data class AgentCreationRequest(
        val description: String,
        val context: String = "",
        val preferredRole: String? = null,
        val suggestedTools: List<String> = emptyList()
    )

    data class CreationResult(
        val agent: Agent,
        val inferredRole: String,
        val confidence: Double,
        val warnings: List<String> = emptyList()
    )

    private val rolePatterns = mapOf(
        "code" to listOf("程序?, "开发?, "工程?, "coding", "code", "写代?, "开?, "程序"),
        "design" to listOf("设计?, "设计", "design", "UI", "UX", "界面", "美术"),
        "writer" to listOf("作家", "写作", "写手", "writer", "content", "内容", "文案", "编辑"),
        "research" to listOf("研究?, "研究", "research", "分析", "分析?, "调研"),
        "test" to listOf("测试", "测试?, "tester", "QA", "质量", "验证"),
        "data" to listOf("数据", "分析?, "data", "统计", "大数?, "数据科学"),
        "pm" to listOf("产品", "经理", "PM", "product", "项目经理", "产品经理"),
        "devops" to listOf("运维", "DevOps", "部署", "devops", "SRE", "运营"),
        "security" to listOf("安全", "Security", "审计", "风控", "security", "渗?),
        "pm" to listOf("项目经理", "PM", "协调", "管理", "manager"),
        "coordinator" to listOf("协调", "协调?, "coordinator", "对接", "联络"),
        "reviewer" to listOf("审核", "审查", "reviewer", "审批", "监察"),
        "supervisor" to listOf("主管", "监督", "supervisor", "负责?, "leader")
    )

    private val capabilityKeywords = mapOf(
        "coding" to listOf("代码", "程序", "function", "class", "implement", "API", "debug", "bug"),
        "design" to listOf("设计", "UI", "原型", "layout", "wireframe", "mockup", "视觉"),
        "writing" to listOf("写作", "文章", "文档", "report", "总结", "撰写", "起草"),
        "research" to listOf("研究", "调研", "分析", "investigate", "survey", "评估"),
        "testing" to listOf("测试", "验证", "test", "check", "验证", "检?),
        "data" to listOf("数据", "分析", "统计", "data", "chart", "报表", "指标"),
        "planning" to listOf("规划", "计划", "plan", "方案", "策划", "策略"),
        "communication" to listOf("沟?, "协调", "会议", "meeting", "汇报", "演示"),
        "documentation" to listOf("文档", "说明", "doc", "manual", "规范", "标准"),
        "security" to listOf("安全", "漏洞", "风险", "security", "threat", "加密")
    )

    fun createAgentFromRequest(request: AgentCreationRequest): CreationResult {
        val description = request.description.lowercase()

        val inferredRole = inferAgentRole(description)
        val capabilities = inferCapabilities(description)
        val tools = inferTools(description, request.suggestedTools)
        val systemPrompt = generateSystemPrompt(inferredRole, description, request.context)
        val warnings = generateWarnings(inferredRole, capabilities)

        val agent = Agent(
            id = generateAgentId(),
            name = generateAgentName(inferredRole),
            role = inferredRole,
            systemPrompt = systemPrompt,
            specialties = capabilities,
            permissions = AgentPermissions(
                canUseTools = tools.contains("tools"),
                canAccessInternet = tools.contains("internet"),
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = inferredRole.contains("协调") || inferredRole.contains("主管")
            )
        )

        val confidence = calculateConfidence(inferredRole, capabilities)

        return CreationResult(
            agent = agent,
            inferredRole = inferredRole,
            confidence = confidence,
            warnings = warnings
        )
    }

    fun inferAgentRole(description: String): String {
        val roleScores = mutableMapOf<String, Double>()

        rolePatterns.forEach { (role, keywords) ->
            var score = 0.0
            keywords.forEach { keyword ->
                if (description.contains(keyword.lowercase())) {
                    score += 1.0
                }
            }
            if (score > 0) {
                roleScores[role] = score
            }
        }

        return if (roleScores.isNotEmpty()) {
            val bestRole = roleScores.maxByOrNull { it.value }?.key ?: "general"
            getRoleDisplayName(bestRole)
        } else {
            "通用助手"
        }
    }

    private fun inferCapabilities(description: String): List<String> {
        val capabilities = mutableListOf<String>()

        capabilityKeywords.forEach { (capability, keywords) ->
            keywords.forEach { keyword ->
                if (description.contains(keyword.lowercase())) {
                    if (capability !in capabilities) {
                        capabilities.add(capability)
                    }
                }
            }
        }

        if (capabilities.isEmpty()) {
            capabilities.add("general")
        }

        return capabilities
    }

    private fun inferTools(description: String, suggestedTools: List<String>): List<String> {
        val tools = mutableListOf<String>()

        val toolIndicators = mapOf(
            "tools" to listOf("工具", "调用", "tool", "execute", "执行"),
            "internet" to listOf("搜索", "查询", "互联?, "browse", "search", "web", "爬虫"),
            "file" to listOf("文件", "读取", "写入", "file", "read", "write", "文档")
        )

        toolIndicators.forEach { (tool, keywords) ->
            keywords.forEach { keyword ->
                if (description.contains(keyword.lowercase())) {
                    if (tool !in tools) {
                        tools.add(tool)
                    }
                }
            }
        }

        tools.addAll(suggestedTools)

        if (tools.isEmpty()) {
            tools.add("tools")
        }

        return tools
    }

    private fun generateSystemPrompt(role: String, description: String, context: String): String {
        val basePrompt = when {
            role.contains("代码") || role.contains("开?) ->
                "你是一位专业的软件开发工程师，擅长编写高质量的代码。你需要遵循最佳实践，注重代码的可读性、可维护性和性能?

            role.contains("设计") ->
                "你是一位专业的UI/UX设计师，擅长创建美观且易用的界面设计。你需要注重用户体验，考虑交互逻辑和视觉层次?

            role.contains("写作") || role.contains("内容") ->
                "你是一位专业的内容创作者，擅长撰写各类文章和文案。你需要注重表达的清晰度和吸引力?

            role.contains("研究") || role.contains("分析") ->
                "你是一位专业的研究分析师，擅长深入调研和分析问题。你需要注重数据的准确性和结论的可靠性?

            role.contains("测试") ->
                "你是一位专业的测试工程师，擅长发现和验证问题。你需要注重细节，追求高质量的交付?

            role.contains("数据") ->
                "你是一位专业的数据分析师，擅长处理和分析数据。你需要注重数据的准确性和可视化的清晰度?

            role.contains("协调") || role.contains("主管") ->
                "你是一位经验丰富的项目协调主管，擅长组织和协调多方面的工作。你需要注重整体进度和各方协作?

            role.contains("审核") || role.contains("审查") ->
                "你是一位专业的审核人员，擅长审查和评估工作成果。你需要注重细节和合规性?

            else ->
                "你是一位专业的AI助手，擅长协助完成各类任务?
        }

        val customContext = if (context.isNotEmpty()) {
            "\n\n当前任务背景：的${context}"
        } else {
            ""
        }

        val taskContext = if (description.isNotEmpty()) {
            "\n\n任务描述：的${description}"
        } else {
            ""
        }

        return basePrompt + customContext + taskContext
    }

    private fun generateWarnings(role: String, capabilities: List<String>): List<String> {
        val warnings = mutableListOf<String>()

        if (capabilities.size > 5) {
            warnings.add("检测到多个能力领域，建议明确主要职责以提高协作效率")
        }

        if (role == "通用助手") {
            warnings.add("未能明确识别具体角色，可能影响任务分配的准确?)
        }

        if (capabilities.contains("security") && !capabilities.contains("coding")) {
            warnings.add("安全相关任务建议同时具备编码能力")
        }

        return warnings
    }

    private fun calculateConfidence(role: String, capabilities: List<String>): Double {
        var confidence = 0.5

        if (role != "通用助手") {
            confidence += 0.2
        }

        if (capabilities.size in 1..3) {
            confidence += 0.2
        } else if (capabilities.size > 5) {
            confidence -= 0.1
        }

        if (capabilities.contains("coding") || capabilities.contains("design") || capabilities.contains("writing")) {
            confidence += 0.1
        }

        return confidence.coerceIn(0.0, 1.0)
    }

    private fun generateAgentId(): String {
        return "dynamic_agent_${UUID.randomUUID().toString().take(8)}"
    }

    private fun generateAgentName(role: String): String {
        val prefixes = mapOf(
            "代码" to listOf("CodeMaster", "DevHelper", "CodeWizard"),
            "设计" to listOf("DesignPro", "ArtWizard", "UIMaster"),
            "写作" to listOf("ContentPro", "WriteHelper", "TextWizard"),
            "研究" to listOf("ResearchPro", "DataAnalyzer", "InsightMaker"),
            "测试" to listOf("TestMaster", "QAHero", "BugFinder"),
            "数据" to listOf("DataPro", "StatMaster", "NumberWizard"),
            "协调" to listOf("Coordinator", "SyncMaster", "TaskPilot"),
            "审核" to listOf("ReviewPro", "AuditMaster", "CheckHero"),
            "主管" to listOf("SuperVisor", "TeamLead", "ChiefHelper"),
            "通用" to listOf("Assistant", "Helper", "Companion")
        )

        val key = rolePatterns.entries.find { (_, keywords) ->
            keywords.any { role.contains(it) }
        }?.key ?: "通用"

        val names = prefixes[key] ?: prefixes["通用"]!!
        return names.random()
    }

    private fun getRoleDisplayName(role: String): String {
        return when (role) {
            "code" -> "代码开?
            "design" -> "界面设计"
            "writer" -> "内容创作"
            "research" -> "研究分析"
            "test" -> "测试验证"
            "data" -> "数据分析"
            "pm" -> "项目管理"
            "devops" -> "运维部署"
            "security" -> "安全审计"
            "coordinator" -> "任务协调"
            "reviewer" -> "审核审查"
            "supervisor" -> "监督主管"
            else -> "通用助手"
        }
    }

    companion object {
        private var instance: DynamicAgentCreator? = null

        fun getInstance(): DynamicAgentCreator {
            return instance ?: synchronized(this) {
                instance ?: DynamicAgentCreator().also { instance = it }
            }
        }
    }
}
