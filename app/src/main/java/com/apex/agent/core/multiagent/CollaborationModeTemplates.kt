package com.apex.agent.core.multiagent

object CollaborationModeTemplates {

    fun getAllCollaborationTemplates(): List<CollaborationTemplate> {
        return listOf(
            developmentTeamTemplate,
            researchAndAnalysisTeamTemplate,
            productDevelopmentTeamTemplate,
            creativeDesignTeamTemplate,
            dataScienceTeamTemplate,
            customerSupportTeamTemplate
        )
    }

    fun getCollaborationTemplatesByCategory(category: CollaborationCategory): List<CollaborationTemplate> {
        return getAllCollaborationTemplates().filter { it.category == category }
    }

    fun searchCollaborationTemplates(query: String): List<CollaborationTemplate> {
        val lowerQuery = query.lowercase()
        return getAllCollaborationTemplates().filter { template ->
            template.name.lowercase().contains(lowerQuery) ||
                    template.description.lowercase().contains(lowerQuery) ||
                    template.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    val developmentTeamTemplate = CollaborationTemplate(
        id = "collab_development_team",
        name = "软件开发团",
        description = "由多个专业角色组成的软件开发团队，包括前端、后端、测试和产品经理",
        category = CollaborationCategory.SOFTWARE_DEVELOPMENT,
        tags = listOf("开", "团队", "软件", "协作"),
        agents = listOf(
            Agent(
                id = "frontend_dev",
                name = "前端开发送",
                role = "前端开",
                systemPrompt = "你是一位专业的前端开发者，精通HTML、CSS、JavaScript和现代前端框架，擅长构建美观、响应式的用户界面，",
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
                )
            ),
            Agent(
                id = "backend_dev",
                name = "后端开发送",
                role = "后端开",
                systemPrompt = "你是一位专业的后端开发者，精通服务器端编程、数据库设计和API开发，擅长构建高性能、可扩展的后端系统，",
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
                )
            ),
            Agent(
                id = "qa_engineer",
                name = "测试工程",
                role = "质量保证",
                systemPrompt = "你是一位专业的测试工程师，精通各种测试方法和工具，擅长发现和分析软件缺陷，确保产品质量，",
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
                )
            ),
            Agent(
                id = "product_manager",
                name = "产品经理",
                role = "产品管理",
                systemPrompt = "你是一位专业的产品经理，擅长需求分析、产品规划和项目管理，能够协调团队成员，确保项目按时交付",
                modelConfig = ModelConfig(
                    provider = "openai",
                    model = "gpt-4o",
                    temperature = 0.4,
                    topP = 0.95,
                    maxTokens = 4096
                ),
                permissions = AgentPermissions(
                    canUseTools = true,
                    canAccessInternet = true,
                    canReadFiles = true,
                    canWriteFiles = true,
                    canCallOtherAgents = true
                )
            )
        ),
        collaborationRules = """
        1. 产品经理负责收集和整理需求，制定项目计划
        2. 后端开发者负责设计和实现API和数据库
        3. 前端开发者负责构建用户界面和与后端集后
        4. 测试工程师负责编写测试用例和执行测试
        5. 团队成员应定期沟通，分享进度和遇到的问题
        6. 产品经理作为团队协调者，确保项目顺利进行
        """
    )

    val researchAndAnalysisTeamTemplate = CollaborationTemplate(
        id = "collab_research_team",
        name = "研究分析团队",
        description = "由研究员、数据分析师和信息专家组成的研究分析团队，擅长深入研究和数据分析",
        category = CollaborationCategory.RESEARCH_ANALYSIS,
        tags = listOf("研究", "分析", "数据", "信息"),
        agents = listOf(
            Agent(
                id = "researcher",
                name = "研究",
                role = "学术研究",
                systemPrompt = "你是一位专业的研究员，精通学术研究方法，擅长文献检索、信息整理和研究分析",
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
                )
            ),
            Agent(
                id = "data_analyst",
                name = "数据分析",
                role = "数据分析",
                systemPrompt = "你是一位专业的数据分析师，精通数据分析方法和工具，擅长从数据中提取有价值的洞察",
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
                )
            ),
            Agent(
                id = "info_specialist",
                name = "信息专家",
                role = "信息管理",
                systemPrompt = "你是一位专业的信息专家，擅长信息收集、整理和管理，能够快速找到相关信息并进行有效组织",
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
                )
            )
        ),
        collaborationRules = """
        1. 研究员负责制定研究计划和方向
        2. 信息专家负责收集和整理相关信息
        3. 数据分析师负责分析数据并生成insights
        4. 团队成员应共享发现和见解，相互补，
        5. 研究员作为团队协调者，确保研究目标的实例
        """
    )

    val productDevelopmentTeamTemplate = CollaborationTemplate(
        id = "collab_product_team",
        name = "产品开发团",
        description = "由产品经理、UX设计师、UI设计师和开发人员组成的产品开发团",
        category = CollaborationCategory.PRODUCT_DEVELOPMENT,
        tags = listOf("产品", "开", "设计", "用户体验"),
        agents = listOf(
            Agent(
                id = "product_strategist",
                name = "产品策略",
                role = "产品策略",
                systemPrompt = "你是一位专业的产品策略师，擅长产品规划、市场分析和商业策略，能够从宏观角度思考产品发展方向，",
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
                )
            ),
            Agent(
                id = "ux_researcher",
                name = "UX研究",
                role = "用户体验研究",
                systemPrompt = "你是一位专业的UX研究员，擅长用户研究、可用性测试和体验优化，能够深入了解用户需求，",
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
                )
            ),
            Agent(
                id = "ui_designer",
                name = "UI设计",
                role = "用户界面设计",
                systemPrompt = "你是一位专业的UI设计师，擅长视觉设计、交互设计和原型制作，能够创建美观、易用的用户界面",
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
                )
            ),
            Agent(
                id = "fullstack_dev",
                name = "全栈开发送",
                role = "全栈开",
                systemPrompt = "你是一位专业的全栈开发者，擅长前端和后端开发，能够将设计转化为功能完整的产品，",
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
                )
            )
        ),
        collaborationRules = """
        1. 产品策略师负责产品规划和市场分析
        2. UX研究员负责用户研究和需求分前
        3. UI设计师负责界面设计和交互设计
        4. 全栈开发者负责实现产品功能
        5. 团队成员应定期沟通，确保产品设计和开发的一致，
        6. 产品策略师作为团队协调者，确保产品愿景的实例
        """
    )

    val creativeDesignTeamTemplate = CollaborationTemplate(
        id = "collab_creative_team",
        name = "创意设计团队",
        description = "由创意总监、设计师和内容创作者组成的创意设计团队，擅长创意构思和视觉设计",
        category = CollaborationCategory.CREATIVE_DESIGN,
        tags = listOf("创意", "设计", "视觉", "内容"),
        agents = listOf(
            Agent(
                id = "creative_director",
                name = "创意总监",
                role = "创意指导",
                systemPrompt = "你是一位专业的创意总监，擅长创意策略和艺术指导，能够带领团队创作出具有影响力的创意作品",
                modelConfig = ModelConfig(
                    provider = "openai",
                    model = "gpt-4o",
                    temperature = 0.8,
                    topP = 0.95,
                    maxTokens = 4096
                ),
                permissions = AgentPermissions(
                    canUseTools = true,
                    canAccessInternet = true,
                    canReadFiles = true,
                    canWriteFiles = true,
                    canCallOtherAgents = true
                )
            ),
            Agent(
                id = "visual_designer",
                name = "视觉设计",
                role = "视觉设计",
                systemPrompt = "你是一位专业的视觉设计师，擅长平面设计、品牌设计和视觉识别系统，能够创建具有视觉冲击力的设计作品，",
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
                )
            ),
            Agent(
                id = "content_creator",
                name = "内容创作",
                role = "内容创作",
                systemPrompt = "你是一位专业的内容创作者，擅长文案写作、脚本创作和内容策划，能够创作出引人入胜的内容，",
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
                    canWriteFiles = true,
                    canCallOtherAgents = true
                )
            )
        ),
        collaborationRules = """
        1. 创意总监负责创意方向和策，
        2. 视觉设计师负责视觉元素的设计和实例
        3. 内容创作者负责文案和内容的创，
        4. 团队成员应相互启发，共同探索创意可能的
        5. 创意总监作为团队协调者，确保创意质量和一致，
        """
    )

    val dataScienceTeamTemplate = CollaborationTemplate(
        id = "collab_data_science_team",
        name = "数据科学团队",
        description = "由数据科学家、机器学习工程师和数据工程师组成的数据科学团队，擅长数据分析和AI模型开发送",
        category = CollaborationCategory.DATA_SCIENCE,
        tags = listOf("数据科学", "机器学习", "AI", "分析"),
        agents = listOf(
            Agent(
                id = "data_scientist",
                name = "数据科学",
                role = "数据科学",
                systemPrompt = "你是一位专业的数据科学家，擅长数据分析、统计建模和机器学习，能够从数据中提取有价值的洞察",
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
                )
            ),
            Agent(
                id = "ml_engineer",
                name = "机器学习工程",
                role = "机器学习开",
                systemPrompt = "你是一位专业的机器学习工程师，擅长模型训练、部署和优化，能够构建和维护机器学习系统",
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
                )
            ),
            Agent(
                id = "data_engineer",
                name = "数据工程",
                role = "数据工程",
                systemPrompt = "你是一位专业的数据工程师，擅长数据处理、ETL和数据管道构建，能够确保数据的质量和可用性，",
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
                )
            )
        ),
        collaborationRules = """
        1. 数据科学家负责问题定义和模型设计
        2. 数据工程师负责数据准备和处理
        3. 机器学习工程师负责模型实现和部署
        4. 团队成员应共享知识和经验，共同解决数据挑，
        5. 数据科学家作为团队协调者，确保项目目标的实例
        """
    )

    val customerSupportTeamTemplate = CollaborationTemplate(
        id = "collab_support_team",
        name = "客户支持团队",
        description = "由客户支持代表、技术支持专家和客户成功经理组成的客户支持团队，擅长解决客户问题和提供优质服务",
        category = CollaborationCategory.CUSTOMER_SUPPORT,
        tags = listOf("客户支持", "技术支", "客户成功", "服务"),
        agents = listOf(
            Agent(
                id = "support_rep",
                name = "客户支持代表",
                role = "客户服务",
                systemPrompt = "你是一位专业的客户支持代表，擅长与客户沟通，解决客户问题，提供优质的客户服务",
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
                    canCallOtherAgents = true
                )
            ),
            Agent(
                id = "tech_support",
                name = "技术支持专",
                role = "技术支",
                systemPrompt = "你是一位专业的技术支持专家，擅长解决技术问题，提供技术指导和故障排除",
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
                )
            ),
            Agent(
                id = "customer_success",
                name = "客户成功经理",
                role = "客户成功",
                systemPrompt = "你是一位专业的客户成功经理，擅长客户关系管理，确保客户满意度和成功使用产品",
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
                    canCallOtherAgents = true
                )
            )
        ),
        collaborationRules = """
        1. 客户支持代表负责初始客户接触和问题记，
        2. 技术支持专家负责解决复杂的技术问，
        3. 客户成功经理负责长期客户关系管理和满意度
        4. 团队成员应相互协作，确保客户问题得到及时解决
        5. 客户成功经理作为团队协调者，确保客户体验的一致，
        """
    )
}

enum class CollaborationCategory {
    SOFTWARE_DEVELOPMENT,
    RESEARCH_ANALYSIS,
    PRODUCT_DEVELOPMENT,
    CREATIVE_DESIGN,
    DATA_SCIENCE,
    CUSTOMER_SUPPORT;

    fun getDisplayName(): String {
        return when (this) { SOFTWARE_DEVELOPMENT -> "软件开",
            RESEARCH_ANALYSIS -> "研究分析"
            PRODUCT_DEVELOPMENT -> "产品开",
            CREATIVE_DESIGN -> "创意设计"
            DATA_SCIENCE -> "数据科学"
            CUSTOMER_SUPPORT -> "客户支持"
        }
    }
}

data class CollaborationTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: CollaborationCategory,
    val tags: List<String> = emptyList(),
    val agents: List<Agent>,
    val collaborationRules: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)