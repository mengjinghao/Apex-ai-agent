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

    /** 所有模板。 */
    val ALL: List<AgentTemplate> = listOf(
        CODE_REVIEWER, TEST_GENERATOR, DOC_WRITER, ARCHITECT, DEBUGGER,
        SECURITY_AUDITOR, PERFORMANCE_OPTIMIZER, TRANSLATOR, SUMMARIZER, CREATIVE_ADVISOR
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
