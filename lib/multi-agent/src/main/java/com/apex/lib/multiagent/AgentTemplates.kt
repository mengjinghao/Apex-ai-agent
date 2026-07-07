package com.apex.lib.multiagent

/**
 * Agent 模板 — 预设角色配置，方便快速创建 Agent。
 *
 * **内置 10 种预设角色**：
 *   - 代码审查员 / 测试生成器 / 文档撰写者 / 架构师 / 调试专家
 *   - 安全审计员 / 性能优化师 / 翻译官 / 总结者 / 创意顾问
 *
 * 使用方式：
 *   ```kotlin
 *   val reviewer = AgentTemplates.CODE_REVIEWER.create { input, blackboard ->
 *       // 实际执行逻辑
 *       AgentOutput(result = "审查完成")
 *   }
 *   engine.registerAgent(reviewer)
 *   ```
 */
object AgentTemplates {

    /** 代码审查员 — 审查代码质量、风格、安全。 */
    val CODE_REVIEWER = AgentTemplate(
        id = "template.code_reviewer",
        displayName = "代码审查员",
        role = AgentRole.REVIEWER,
        capabilities = listOf("code_review", "quality_check", "security_check"),
        description = "审查代码质量、编码规范、潜在 bug 和安全漏洞",
        priority = 50
    )

    /** 测试生成器 — 为代码生成单元测试。 */
    val TEST_GENERATOR = AgentTemplate(
        id = "template.test_generator",
        displayName = "测试生成器",
        role = AgentRole.WORKER,
        capabilities = listOf("test_generation", "unit_test", "integration_test"),
        description = "为指定代码生成全面的单元测试和集成测试",
        priority = 60
    )

    /** 文档撰写者 — 生成文档和注释。 */
    val DOC_WRITER = AgentTemplate(
        id = "template.doc_writer",
        displayName = "文档撰写者",
        role = AgentRole.WORKER,
        capabilities = listOf("documentation", "api_doc", "readme"),
        description = "生成 API 文档、README、代码注释",
        priority = 70
    )

    /** 架构师 — 设计系统架构。 */
    val ARCHITECT = AgentTemplate(
        id = "template.architect",
        displayName = "架构师",
        role = AgentRole.SUPERVISOR,
        capabilities = listOf("architecture", "design", "planning", "decomposition"),
        description = "设计系统架构、拆分任务、分配给 Worker",
        priority = 10
    )

    /** 调试专家 — 定位和修复 bug。 */
    val DEBUGGER = AgentTemplate(
        id = "template.debugger",
        displayName = "调试专家",
        role = AgentRole.WORKER,
        capabilities = listOf("debugging", "bug_fix", "root_cause_analysis"),
        description = "定位 bug 根因、提供修复方案",
        priority = 40
    )

    /** 安全审计员 — 检查安全漏洞。 */
    val SECURITY_AUDITOR = AgentTemplate(
        id = "template.security_auditor",
        displayName = "安全审计员",
        role = AgentRole.REVIEWER,
        capabilities = listOf("security_audit", "vulnerability_scan", "compliance_check"),
        description = "检查安全漏洞、注入风险、合规问题",
        priority = 30
    )

    /** 性能优化师 — 分析和优化性能。 */
    val PERFORMANCE_OPTIMIZER = AgentTemplate(
        id = "template.perf_optimizer",
        displayName = "性能优化师",
        role = AgentRole.WORKER,
        capabilities = listOf("performance", "optimization", "profiling", "bottleneck"),
        description = "分析性能瓶颈、提供优化方案",
        priority = 50
    )

    /** 翻译官 — 多语言翻译。 */
    val TRANSLATOR = AgentTemplate(
        id = "template.translator",
        displayName = "翻译官",
        role = AgentRole.WORKER,
        capabilities = listOf("translation", "i18n", "localization"),
        description = "多语言翻译和本地化",
        priority = 80
    )

    /** 总结者 — 汇总和提炼信息。 */
    val SUMMARIZER = AgentTemplate(
        id = "template.summarizer",
        displayName = "总结者",
        role = AgentRole.WORKER,
        capabilities = listOf("summarization", "extraction", "synthesis"),
        description = "汇总多方信息、提炼要点",
        priority = 90
    )

    /** 创意顾问 — 提供创意方案。 */
    val CREATIVE_ADVISOR = AgentTemplate(
        id = "template.creative_advisor",
        displayName = "创意顾问",
        role = AgentRole.CRITIC,
        capabilities = listOf("creativity", "brainstorming", "innovation"),
        description = "从创意角度提出方案和质疑",
        priority = 60
    )

    // ============================================================
    // 三省六部制（映射自主应用的 SanxingRole）
    // ============================================================

    /** 中书省 — 决策制定（草拟方案）。 */
    val ZHONGSHU_SHENG = AgentTemplate(
        id = "template.sanxing.zhongshu",
        displayName = "中书省",
        role = AgentRole.SUPERVISOR,
        capabilities = listOf("decision", "policy_drafting", "strategy", "sanxing"),
        description = "三省之首，负责草拟方案和制定决策，将任务拆分并分派给六部",
        priority = 5
    )

    /** 门下省 — 审核驳回（审查方案）。 */
    val MENXIA_SHENG = AgentTemplate(
        id = "template.sanxing.menxia",
        displayName = "门下省",
        role = AgentRole.REVIEWER,
        capabilities = listOf("review", "audit", "veto", "sanxing"),
        description = "审查中书省的方案，可驳回不合理的部分，通过后交尚书省执行",
        priority = 15
    )

    /** 尚书省 — 执行总调度（统领六部）。 */
    val SHANGSHU_SHENG = AgentTemplate(
        id = "template.sanxing.shangshu",
        displayName = "尚书省",
        role = AgentRole.SUPERVISOR,
        capabilities = listOf("execution", "coordination", "dispatch", "sanxing"),
        description = "统领六部，将审核通过的方案分配给具体部门执行",
        priority = 20
    )

    /** 吏部（人事）— 人员/资源分配。 */
    val LIBU_PERSONNEL = AgentTemplate(
        id = "template.sanxing.libu_personnel",
        displayName = "吏部",
        role = AgentRole.WORKER,
        capabilities = listOf("personnel", "resource_allocation", "team_management", "sanxing"),
        description = "负责人事安排和资源分配，评估团队能力并合理调配",
        priority = 40
    )

    /** 礼部（礼仪）— 文案/规范/文档。 */
    val LIBU_RITUAL = AgentTemplate(
        id = "template.sanxing.libu_ritual",
        displayName = "礼部",
        role = AgentRole.WORKER,
        capabilities = listOf("documentation", "style_guide", "convention", "sanxing"),
        description = "负责文案规范、编码风格指南、API 文档标准",
        priority = 70
    )

    /** 户部（财政）— 预算/资源/成本。 */
    val HUBU = AgentTemplate(
        id = "template.sanxing.hubu",
        displayName = "户部",
        role = AgentRole.WORKER,
        capabilities = listOf("budget", "cost_analysis", "resource_planning", "sanxing"),
        description = "负责预算评估、资源成本分析、性能开销控制",
        priority = 50
    )

    /** 兵部（军事）— 安全/攻防/部署。 */
    val BINGBU = AgentTemplate(
        id = "template.sanxing.bingbu",
        displayName = "兵部",
        role = AgentRole.WORKER,
        capabilities = listOf("security", "deployment", "attack_defense", "sanxing"),
        description = "负责安全审计、部署策略、攻防对抗测试",
        priority = 30
    )

    /** 刑部（司法）— 测试/质检/bug 修复。 */
    val XINGBU = AgentTemplate(
        id = "template.sanxing.xingbu",
        displayName = "刑部",
        role = AgentRole.REVIEWER,
        capabilities = listOf("testing", "quality_assurance", "bug_investigation", "sanxing"),
        description = "负责测试用例、质量保证、bug 定位与修复",
        priority = 35
    )

    /** 工部（工程）— 开发/构建/工程实现。 */
    val GONGBU = AgentTemplate(
        id = "template.sanxing.gongbu",
        displayName = "工部",
        role = AgentRole.WORKER,
        capabilities = listOf("engineering", "implementation", "build", "sanxing"),
        description = "负责核心工程开发、代码实现、构建打包",
        priority = 45
    )

    /** 御史台 — 独立监督/审计/纠错。 */
    val YUSHITAI = AgentTemplate(
        id = "template.sanxing.yushitai",
        displayName = "御史台",
        role = AgentRole.CRITIC,
        capabilities = listOf("supervision", "audit", "correction", "sanxing"),
        description = "独立监督机构，纠察违规、审查合规、弹劾不称职的 Agent",
        priority = 25
    )

    // ============================================================
    // 扩展模板（10 种专业角色）
    // ============================================================

    /** 数据分析师 — 数据分析 + 可视化。 */
    val DATA_ANALYST = AgentTemplate(
        id = "template.data_analyst",
        displayName = "数据分析师",
        role = AgentRole.WORKER,
        capabilities = listOf("data_analysis", "visualization", "statistics", "report"),
        description = "分析数据、生成图表、统计建模、撰写数据报告",
        priority = 55
    )

    /** 项目经理 — 任务拆分 + 进度跟踪。 */
    val PROJECT_MANAGER = AgentTemplate(
        id = "template.project_manager",
        displayName = "项目经理",
        role = AgentRole.SUPERVISOR,
        capabilities = listOf("planning", "task_decomposition", "progress_tracking", "risk_management"),
        description = "拆分任务、分配资源、跟踪进度、识别风险",
        priority = 15
    )

    /** UI 设计师 — 界面设计 + 用户体验。 */
    val UI_DESIGNER = AgentTemplate(
        id = "template.ui_designer",
        displayName = "UI 设计师",
        role = AgentRole.WORKER,
        capabilities = listOf("ui_design", "ux", "prototype", "design_system"),
        description = "设计界面布局、交互流程、视觉风格、设计系统",
        priority = 60
    )

    /** DevOps 工程师 — CI/CD + 部署 + 运维。 */
    val DEVOPS_ENGINEER = AgentTemplate(
        id = "template.devops_engineer",
        displayName = "DevOps 工程师",
        role = AgentRole.WORKER,
        capabilities = listOf("devops", "ci_cd", "deployment", "monitoring", "infrastructure"),
        description = "CI/CD 流水线、容器化部署、监控告警、基础设施管理",
        priority = 50
    )

    /** API 设计师 — API 架构 + 规范。 */
    val API_DESIGNER = AgentTemplate(
        id = "template.api_designer",
        displayName = "API 设计师",
        role = AgentRole.WORKER,
        capabilities = listOf("api_design", "openapi", "rest", "graphql", "sdk"),
        description = "设计 API 架构、编写 OpenAPI 规范、生成 SDK",
        priority = 45
    )

    /** 数据库专家 — 数据库设计 + 优化。 */
    val DATABASE_EXPERT = AgentTemplate(
        id = "template.database_expert",
        displayName = "数据库专家",
        role = AgentRole.WORKER,
        capabilities = listOf("database", "sql", "schema_design", "query_optimization", "migration"),
        description = "数据库设计、SQL 优化、Schema 迁移、索引调优",
        priority = 50
    )

    /** 移动开发工程师 — Android/iOS 开发。 */
    val MOBILE_DEVELOPER = AgentTemplate(
        id = "template.mobile_developer",
        displayName = "移动开发工程师",
        role = AgentRole.WORKER,
        capabilities = listOf("mobile", "android", "ios", "kotlin", "swift", "compose"),
        description = "Android/iOS 原生开发、Jetpack Compose、SwiftUI",
        priority = 45
    )

    /** 后端开发工程师 — 服务端开发。 */
    val BACKEND_DEVELOPER = AgentTemplate(
        id = "template.backend_developer",
        displayName = "后端开发工程师",
        role = AgentRole.WORKER,
        capabilities = listOf("backend", "server", "microservice", "kotlin", "java", "spring"),
        description = "服务端开发、微服务架构、API 实现、中间件",
        priority = 45
    )

    /** 机器学习工程师 — ML 模型训练 + 部署。 */
    val ML_ENGINEER = AgentTemplate(
        id = "template.ml_engineer",
        displayName = "ML 工程师",
        role = AgentRole.WORKER,
        capabilities = listOf("machine_learning", "model_training", "pytorch", "tensorflow", "mlops"),
        description = "ML 模型训练、评估、部署、MLOps 流水线",
        priority = 55
    )

    /** 产品负责人 — 需求分析 + 产品规划。 */
    val PRODUCT_OWNER = AgentTemplate(
        id = "template.product_owner",
        displayName = "产品负责人",
        role = AgentRole.SUPERVISOR,
        capabilities = listOf("product", "requirements", "roadmap", "user_story", "prioritization"),
        description = "需求分析、产品路线图、用户故事、优先级排序",
        priority = 10
    )

    /** 所有模板。 */
    val ALL: List<AgentTemplate> = listOf(
        // 基础 10 种
        CODE_REVIEWER, TEST_GENERATOR, DOC_WRITER, ARCHITECT, DEBUGGER,
        SECURITY_AUDITOR, PERFORMANCE_OPTIMIZER, TRANSLATOR, SUMMARIZER, CREATIVE_ADVISOR,
        // 三省六部制 9 种
        ZHONGSHU_SHENG, MENXIA_SHENG, SHANGSHU_SHENG,
        LIBU_PERSONNEL, LIBU_RITUAL, HUBU, BINGBU, XINGBU, GONGBU,
        YUSHITAI,
        // 扩展 10 种
        DATA_ANALYST, PROJECT_MANAGER, UI_DESIGNER, DEVOPS_ENGINEER,
        API_DESIGNER, DATABASE_EXPERT, MOBILE_DEVELOPER, BACKEND_DEVELOPER,
        ML_ENGINEER, PRODUCT_OWNER
    )

    /** 按能力查找模板。 */
    fun findByCapability(capability: String): List<AgentTemplate> =
        ALL.filter { capability in it.capabilities }

    /** 按角色查找模板。 */
    fun findByRole(role: AgentRole): List<AgentTemplate> =
        ALL.filter { it.role == role }
}

/**
 * Agent 模板定义。
 */
data class AgentTemplate(
    val id: String,
    val displayName: String,
    val role: AgentRole,
    val capabilities: List<String>,
    val description: String,
    val priority: Int = 100,
    val maxRetries: Int = 1,
    val timeoutMs: Long = 30_000L
) {
    /**
     * 从模板创建 Agent 实例。
     *
     * @param customId 自定义 Agent ID（默认用模板 ID）
     * @param execute 执行体
     */
    fun create(
        customId: String? = null,
        execute: suspend (AgentInput, Blackboard) -> AgentOutput
    ): Agent = Agent(
        id = customId ?: id,
        displayName = displayName,
        role = role,
        capabilities = capabilities,
        priority = priority,
        maxRetries = maxRetries,
        timeoutMs = timeoutMs,
        metadata = mapOf("template" to id, "description" to description),
        execute = execute
    )
}

/**
 * 协作模式推荐器 — 根据任务类型推荐合适的协作模式。
 */
object CollaborationRecommender {

    /**
     * 根据任务描述推荐协作模式 + Agent 组合。
     */
    fun recommend(taskDescription: String): CollaborationRecommendation {
        val desc = taskDescription.lowercase()
        return when {
            // 代码审查 → HIERARCHICAL（架构师 + 审查员）
            desc.contains("审查") || desc.contains("review") || desc.contains("检查代码") -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.HIERARCHICAL,
                    templateIds = listOf("template.architect", "template.code_reviewer", "template.security_auditor"),
                    reason = "代码审查适合层级模式：架构师分派 + 审查员检查"
                )
            }
            // 辩论/讨论 → DEBATE
            desc.contains("讨论") || desc.contains("辩论") || desc.contains("debate") -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.DEBATE,
                    templateIds = listOf("template.architect", "template.creative_advisor", "template.summarizer"),
                    reason = "需要多角度讨论，适合辩论模式"
                )
            }
            // 投票/决策 → VOTING
            desc.contains("投票") || desc.contains("决策") || desc.contains("选择") -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.VOTING,
                    templateIds = listOf("template.architect", "template.code_reviewer", "template.security_auditor", "template.performance_optimizer"),
                    reason = "需要集体决策，适合投票模式"
                )
            }
            // 并行竞速 → PARALLEL_RACING
            desc.contains("最快") || desc.contains("竞速") || desc.contains("并行") -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.PARALLEL_RACING,
                    templateIds = listOf("template.debugger", "template.performance_optimizer"),
                    reason = "需要快速出结果，适合并行竞速"
                )
            }
            // 对抗/优化 → ADVERSARIAL
            desc.contains("优化") || desc.contains("对抗") || desc.contains("adversarial") -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.ADVERSARIAL,
                    templateIds = listOf("template.performance_optimizer", "template.code_reviewer"),
                    reason = "需要生成-评判迭代优化，适合对抗模式"
                )
            }
            // 共识 → CONSENSUS
            desc.contains("共识") || desc.contains("一致") || desc.contains("consensus") -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.CONSENSUS,
                    templateIds = listOf("template.architect", "template.code_reviewer", "template.security_auditor"),
                    reason = "需要所有方同意，适合共识模式"
                )
            }
            // 默认 → PIPELINE
            else -> {
                CollaborationRecommendation(
                    mode = CollaborationMode.PIPELINE,
                    templateIds = listOf("template.architect", "template.debugger", "template.test_generator", "template.doc_writer"),
                    reason = "通用任务适合流水线模式：设计→实现→测试→文档"
                )
            }
        }
    }
}

/** 协作推荐结果。 */
data class CollaborationRecommendation(
    val mode: CollaborationMode,
    val templateIds: List<String>,
    val reason: String
)
