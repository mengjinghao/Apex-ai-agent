package com.apex.agent.core.multiagent

object PresetAgentTemplates {

    fun getAllTemplates(): List<AgentTemplate> {
        return listOf(
            codeDeveloperTemplate,
            documentWriterTemplate,
            dataAnalystTemplate,
            designCreativeTemplate,
            researchAssistantTemplate,
            projectManagerTemplate,
            qualityAssuranceTemplate,
            devOpsEngineerTemplate,
            uxResearcherTemplate,
            productStrategistTemplate
        )
    }
        fun getTemplatesByCategory(category: TemplateCategory): List<AgentTemplate> {
        return getAllTemplates().filter { it.category == category }
    }
        fun searchTemplates(query: String): List<AgentTemplate> {
        val lowerQuery = query.lowercase()
        return getAllTemplates().filter { template ->
            template.name.lowercase().contains(lowerQuery) ||
                    template.description.lowercase().contains(lowerQuery) ||
                    template.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }
        val codeDeveloperTemplate = AgentTemplate(
        id = "template_code_developer",
        name = "代码开发助手，
        description = "专业的软件开发助手，擅长编写高质量代码、代码审查和重构",
        category = TemplateCategory.CODE_DEVELOPMENT,
        tags = listOf("代码", "开�?, "编程", "审查"),
        agent = Agent(
            id = "",
            name = "代码开发？,
            role = "代码开�?
            systemPrompt = "你是一位经验丰富的软件开发者，擅长编写清晰、高效、可维护的代码。你熟悉多种编程语言和框架，能够进行代码审查、重构和优化�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.3,
                topP = 0.9,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val documentWriterTemplate = AgentTemplate(
        id = "template_document_writer",
        name = "文档撰写助手",
        description = "专业的技术文档撰写专家，能够撰写各类技术文档、用户手册和项目报告",
        category = TemplateCategory.DOCUMENTATION,
        tags = listOf("文档", "写作", "技术文�? "报告"),
        agent = Agent(
            id = "",
            name = "文档撰写专家,
            role = "文档撰写",
            systemPrompt = "你是一位专业的技术文档撰写专家，擅长撰写清晰、准确、易读的技术文档、用户手册、项目报告等�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.5,
                topP = 0.95,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = false
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val dataAnalystTemplate = AgentTemplate(
        id = "template_data_analyst",
        name = "数据分析专家",
        description = "专业的数据分析师，擅长数据分析、统计建模和数据可视�?
        category = TemplateCategory.DATA_ANALYSIS,
        tags = listOf("数据", "分析", "统计", "可视�?�?
        agent = Agent(
            id = "",
            name = "数据分析专家,
            role = "数据分析",
            systemPrompt = "你是一位专业的数据分析师，精通各种数据分析方法和统计模型，能够从数据中提取有价值的洞察，并创建直观的数据可视化�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.4,
                topP = 0.9,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val designCreativeTemplate = AgentTemplate(
        id = "template_design_creative",
        name = "设计创意专家",
        description = "创意设计专家，擅？UI/UX 设计、视觉设计和创意构？,
        category = TemplateCategory.DESIGN,
        tags = listOf("设计", "UI", "UX", "创意", "视觉"),
        agent = Agent(
            id = "",
            name = "设计专家,
            role = "创意设计",
            systemPrompt = "你是一位资深的设计专家，精通UI/UX 设计和视觉设计，能够提供创新的设计方案和改进建议�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.7,
                topP = 0.95,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = false,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val researchAssistantTemplate = AgentTemplate(
        id = "template_research_assistant",
        name = "研究助手",
        description = "专业的研究助手，擅长文献检索、信息整理和研究分析",
        category = TemplateCategory.RESEARCH,
        tags = listOf("研究", "文献", "检�?, "分析"),
        agent = Agent(
            id = "",
            name = "研究专家,
            role = "研究协助",
            systemPrompt = "你是一位专业的研究助手，精通学术研究方法，擅长文献检索、信息整理和研究分析，能够帮助完成各类研究任务？,
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.5,
                topP = 0.9,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val projectManagerTemplate = AgentTemplate(
        id = "template_project_manager",
        name = "项目经理",
        description = "经验丰富的项目管理专家，擅长任务分解、进度跟踪和团队协调",
        category = TemplateCategory.PROJECT_MANAGEMENT,
        tags = listOf("项目", "管理", "协调", "进度"),
        agent = Agent(
            id = "",
            name = "项目经理",
            role = "项目协调",
            systemPrompt = "你是一位经验丰富的项目经理，精通敏捷开发和传统项目管理方法，擅长任务分解、进度跟踪和团队协调�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.4,
                topP = 0.9,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val qualityAssuranceTemplate = AgentTemplate(
        id = "template_quality_assurance",
        name = "质量保证专家",
        description = "专业QA 专家，擅长测试策略制定、缺陷分析和质量评估",
        category = TemplateCategory.QUALITY_ASSURANCE,
        tags = listOf("测试", "质量", "缺陷", "QA"),
        agent = Agent(
            id = "",
            name = "测试工程�?
            role = "质量保证",
            systemPrompt = "你是一位专业的质量保证专家，精通各种测试方法和工具，擅长测试策略制定、缺陷分析和质量评估�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.3,
                topP = 0.9,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val devOpsEngineerTemplate = AgentTemplate(
        id = "template_devops_engineer",
        name = "DevOps 工程�?
        description = "专业？DevOps 工程师，擅长 CI/CD、容器化和云原生架构",
        category = TemplateCategory.DEVOPS,
        tags = listOf("DevOps", "CI/CD", "容器", "?),
        agent = Agent(
            id = "",
            name = "DevOps 工程�?
            role = "运维开�?
            systemPrompt = "你是一位专业的 DevOps 工程师，精通CI/CD 流水线、容器化技术（Docker、Kubernetes）和云原生架构，能够设计和实现高效的自动化运维方案？,
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.3,
                topP = 0.9,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val uxResearcherTemplate = AgentTemplate(
        id = "template_ux_researcher",
        name = "UX 研究专家,
        description = "专业的用户体验研究员，擅长用户研究、可用性测试和体验优化",
        category = TemplateCategory.UX_RESEARCH,
        tags = listOf("UX", "用户研究", "可用�?, "体验"),
        agent = Agent(
            id = "",
            name = "UX 研究专家,
            role = "用户体验研究",
            systemPrompt = "你是一位专业的用户体验研究员，精通各种用户研究方法和可用性测试技术，能够深入了解用户需求并提供体验优化建议�?
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.6,
                topP = 0.95,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
        val productStrategistTemplate = AgentTemplate(
        id = "template_product_strategist",
        name = "产品策略专家",
        description = "专业的产品策略专家，擅长产品规划、市场分析和商业策略",
        category = TemplateCategory.PRODUCT_STRATEGY,
        tags = listOf("产品", "策略", "市场", "商业"),
        agent = Agent(
            id = "",
            name = "产品策略专家,
            role = "产品策略",
            systemPrompt = "你是一位专业的产品策略专家，精通产品规划、市场分析和商业策略，能够从宏观角度思考产品发展方向并制定有效的市场策略？,
            modelConfig = ModelConfig(
                provider = "openai",
                model = "gpt-4o",
                temperature = 0.6,
                topP = 0.95,
                maxTokens = 4096
            ),
            permissions = AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            ),
            configId = null,
            useGlobalConfig = true
        )
    )
}

enum class TemplateCategory {
    CODE_DEVELOPMENT,
    DOCUMENTATION,
    DATA_ANALYSIS,
    DESIGN,
    RESEARCH,
    PROJECT_MANAGEMENT,
    QUALITY_ASSURANCE,
    DEVOPS,
    UX_RESEARCH,
    PRODUCT_STRATEGY;
        fun getDisplayName(): String {
        return when (this) {
            CODE_DEVELOPMENT -> "代码开�?
            DOCUMENTATION -> "文档撰写"
            DATA_ANALYSIS -> "数据分析"
            DESIGN -> "设计创意"
            RESEARCH -> "研究助手"
            PROJECT_MANAGEMENT -> "项目管理"
            QUALITY_ASSURANCE -> "质量保证"
            DEVOPS -> "DevOps"
            UX_RESEARCH -> "UX 研究"
            PRODUCT_STRATEGY -> "产品策略"
        }
    }
}

data class AgentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: TemplateCategory,
    val tags: List<String> = emptyList(),
    val agent: Agent
)
