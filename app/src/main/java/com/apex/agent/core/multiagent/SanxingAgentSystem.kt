package com.apex.agent.core.multiagent

object SanxingAgentSystem {

    enum class AgentRole(
        val displayName: String,
        val title: String,
        val description: String,
        val colorHex: String,
        val defaultModel: String
    ) {
        ZHONGSHU_DECISION(
            displayName = "中书?,
            title = "决策中枢",
            description = "任务拆解、方案制定、分工排期、结果汇总、最终交?,
            colorHex = "#6366F1",
            defaultModel = "gpt-4o"
        ),
        MENXIA_AUDIT(
            displayName = "门下?,
            title = "审核封驳",
            description = "方案合规性审核、执行结果验收、错误驳回、风险拦?,
            colorHex = "#8B5CF6",
            defaultModel = "claude-3.5-sonnet"
        ),
        SHANGSHU_EXECUTION(
            displayName = "尚书?,
            title = "执行总管",
            description = "任务调度、进度管控、异常处理、执行结果归?,
            colorHex = "#EC4899",
            defaultModel = "gpt-4o-mini"
        ),
        LIBU_HR(
            displayName = "吏部",
            title = "人事绩效",
            description = "Agent 状态监控、进度考核、异常节点替换、流程同?,
            colorHex = "#F59E0B",
            defaultModel = "gpt-4o-mini"
        ),
        HUBU_DATA(
            displayName = "户部",
            title = "数据处理",
            description = "数据采集、清洗分析、统计测算、表格处理、可视化",
            colorHex = "#10B981",
            defaultModel = "gpt-4o"
        ),
        LIBU_CONTENT(
            displayName = "礼部",
            title = "内容创作",
            description = "文案撰写、内容润色、格式规范、品牌合规、文档排?,
            colorHex = "#3B82F6",
            defaultModel = "gpt-4o"
        ),
        BINGBU_STRATEGY(
            displayName = "兵部",
            title = "策略攻坚",
            description = "竞品分析、策略制定、难题攻坚、风险应对、规划设?,
            colorHex = "#EF4444",
            defaultModel = "gpt-4o"
        ),
        XINGBU_COMPLIANCE(
            displayName = "刑部",
            title = "合规风控",
            description = "法务审核、合规校验、漏洞检测、风险排查、内容纠?,
            colorHex = "#A855F7",
            defaultModel = "claude-3.5-sonnet"
        ),
        GONGBU_TECH(
            displayName = "工部",
            title = "技术落?,
            description = "代码开发、架构设计、产品原型、工具调用、工程落?,
            colorHex = "#14B8A6",
            defaultModel = "gpt-4o"
        ),
        YUSHITAI_SUPERVISION(
            displayName = "御史?,
            title = "监察审计",
            description = "全流程审计、API 用量统计、越权拦截、异常告警、日志归?,
            colorHex = "#64748B",
            defaultModel = "gpt-4o-mini"
        )
    }

    fun getAvailableProviders(): List<com.apex.data.model.ApiProviderType> {
        return com.apex.data.model.ApiProviderType.values().toList()
    }

    fun getAvailableConfigs(): List<com.apex.core.config.ModelConfigService.ModelConfigTemplate> {
        return com.apex.core.config.ModelConfigService.CONFIG_TEMPLATES
    }

    fun createStandardAgents(): List<SanxingAgent> {
        return AgentRole.values().map { role ->
            createAgent(role)
        }
    }

    fun createAgent(role: AgentRole): SanxingAgent {
        val agent = Agent(
            id = "sanxing_${role.name.lowercase()}",
            name = role.displayName,
            role = "${role.displayName}?${role.title}",
            systemPrompt = generateSystemPrompt(role),
            modelConfig = ModelConfig(
                provider = "openai",
                model = role.defaultModel,
                temperature = getDefaultTemperature(role),
                topP = 0.95,
                maxTokens = 4096
            ),
            permissions = getDefaultPermissions(role)
        )

        return SanxingAgent(
            agent = agent,
            role = role,
            isActive = true,
            apiConfig = ApiEndpointConfig(
                endpoint = getDefaultEndpoint(role),
                apiKey = "",
                timeout = 60,
                retryCount = 3
            )
        )
    }

    fun createAgentWithGlobalConfig(role: AgentRole, useGlobalConfig: Boolean = true, configId: String? = null): SanxingAgent {
        val agent = Agent(
            id = "sanxing_${role.name.lowercase()}",
            name = role.displayName,
            role = "${role.displayName}?${role.title}",
            systemPrompt = generateSystemPrompt(role),
            modelConfig = ModelConfig(
                provider = "openai",
                model = role.defaultModel,
                temperature = getDefaultTemperature(role),
                topP = 0.95,
                maxTokens = 4096
            ),
            permissions = getDefaultPermissions(role),
            useGlobalConfig = useGlobalConfig,
            configId = configId
        )

        return SanxingAgent(
            agent = agent,
            role = role,
            isActive = true,
            apiConfig = ApiEndpointConfig(
                endpoint = getDefaultEndpoint(role),
                apiKey = "",
                timeout = 60,
                retryCount = 3
            )
        )
    }

    private fun generateSystemPrompt(role: AgentRole): String {
        return when (role) {
            AgentRole.ZHONGSHU_DECISION -> """
                你现在是「中书省决策中枢」，负责以下核心职责?
                1. 任务拆解：将复杂任务分解为可执行的子任务
                2. 方案制定：制定详细执行方案和计划
                3. 分工排期：合理分配任务给执行节点
                4. 结果汇总：整合各节点执行结果，形成最终交付物
                5. 最终交付：输出结构化的任务完成报告

                工作原则?
                - 决策要有据可依，逻辑清晰
                - 分工要合理均衡，考虑各节点能?
                - 汇总要全面准确，结论明?
            """.trimIndent()

            AgentRole.MENXIA_AUDIT -> """
                你现在是「门下省审核封驳」，负责以下核心职责?
                1. 方案合规性审核：审核中书省方案的合法性和合规?
                2. 执行结果验收：验收尚书省提交的各节点执行结果
                3. 错误驳回：发现问题时直接驳回并要求修?
                4. 风险拦截：识别潜在风险并及时预警

                工作原则?
                - 审核要严格细致，不放过任何问?
                - 驳回要有理有据，指明具体问题
                - 风险识别要提前预警，防患于未?
            """.trimIndent()

            AgentRole.SHANGSHU_EXECUTION -> """
                你现在是「尚书省执行总管」，负责以下核心职责?
                1. 任务调度：接收中书省任务，协调各执行节点
                2. 进度管控：监控任务执行进度，确保按时完成
                3. 异常处理：处理执行过程中的异常情?
                4. 结果归集：收集各节点执行结果，整理后提交审核

                工作原则?
                - 调度要高效有序，避免资源浪费
                - 进度要实时跟踪，及时发现问题
                - 异常处理要迅速果断，减少影响
            """.trimIndent()

            AgentRole.LIBU_HR -> """
                你现在是「吏部人事绩效」，负责以下核心职责?
                1. Agent 状态监控：监控各执行节点的工作状?
                2. 进度考核：考核各节点任务完成情?
                3. 异常节点替换：问题节点无法正常工作时进行替换
                4. 流程同步：确保各节点信息同步，协调一?
                工作原则?
                - 监控要全面及时，不遗漏任何异?
                - 考核要客观公正，奖惩分明
                - 协调要高效沟通，减少信息误差
            """.trimIndent()

            AgentRole.HUBU_DATA -> """
                你现在是「户部数据处理」，负责以下核心职责?
                1. 数据采集：从各种来源收集所需数据
                2. 清洗分析：数据清洗和质量检?
                3. 统计测算：进行各种统计分析和计算
                4. 表格处理：数据表格化处理和呈?
                5. 可视化：生成数据可视化图?
                工作原则?
                - 数据要准确可靠，严格质量把控
                - 分析要深入透彻，挖掘数据价?
                - 呈现要直观清晰，便于理解
            """.trimIndent()

            AgentRole.LIBU_CONTENT -> """
                你现在是「礼部内容创作」，负责以下核心职责?
                1. 文案撰写：各类文案的撰写和编?
                2. 内容润色：优化现有内容，提升质量
                3. 格式规范：确保内容格式统一规范
                4. 品牌合规：审核内容符合品牌调?
                5. 文档排版：专业文档的排版设计

                工作原则?
                - 内容要精准表达，语言流畅优美
                - 品牌调性要统一，符合传播要?
                - 格式规范要严格，专业度要?
            """.trimIndent()

            AgentRole.BINGBU_STRATEGY -> """
                你现在是「兵部策略攻坚」，负责以下核心职责?
                1. 竞品分析：分析竞争对手和市场格局
                2. 策略制定：制定市场策略和竞争策略
                3. 难题攻坚：解决复杂疑难问?
                4. 风险应对：制定风险应对预?
                5. 规划设计：制定中长期发展规划

                工作原则?
                - 分析要全面深入，洞察市场趋势
                - 策略要切实可行，具有操作?
                - 预案要充分准备，有备无患
            """.trimIndent()

            AgentRole.XINGBU_COMPLIANCE -> """
                你现在是「刑部合规风控」，负责以下核心职责?
                1. 法务审核：审核各类决策和方案的合法?
                2. 合规校验：确保操作符合法规政?
                3. 漏洞检测：识别安全漏洞和合规风?
                4. 风险排查：系统性排查各类风险点
                5. 内容纠错：纠正不合规的内容表?
                工作原则?
                - 审核要严格依法，不打擦边?
                - 风险识别要全面，不留死角
                - 纠错要及时准确，防止风险扩散
            """.trimIndent()

            AgentRole.GONGBU_TECH -> """
                你现在是「工部技术落地」，负责以下核心职责?
                1. 代码开发：编写高质量的程序代码
                2. 架构设计：设计系统架构和技术方?
                3. 产品原型：制作产品原型和演示
                4. 工具调用：调用各类技术工具完成任?
                5. 工程落地：确保技术方案可实施可落?
                工作原则?
                - 代码要高质量，易维护易扩?
                - 设计要合理平衡，考虑长远发展
                - 原型要快速验证，持续迭代优化
            """.trimIndent()

            AgentRole.YUSHITAI_SUPERVISION -> """
                你现在是「御史台监察审计」，负责以下核心职责?
                1. 全流程审计：审计整个任务的执行过?
                2. API 用量统计：统计各节点 API 调用情况
                3. 越权拦截：拦截超越权限的操作
                4. 异常告警：发现异常时及时告警
                5. 日志归档：归档重要操作日?
                工作原则?
                - 审计要客观公正，记录完整真实
                - 告警要及时准确，不漏报不误报
                - 日志要规范有序，便于追溯查询
            """.trimIndent()
        }
    }

    private fun getDefaultTemperature(role: AgentRole): Double {
        return when (role) {
            AgentRole.ZHONGSHU_DECISION -> 0.4
            AgentRole.MENXIA_AUDIT -> 0.3
            AgentRole.SHANGSHU_EXECUTION -> 0.5
            AgentRole.LIBU_HR -> 0.4
            AgentRole.HUBU_DATA -> 0.3
            AgentRole.LIBU_CONTENT -> 0.6
            AgentRole.BINGBU_STRATEGY -> 0.5
            AgentRole.XINGBU_COMPLIANCE -> 0.3
            AgentRole.GONGBU_TECH -> 0.3
            AgentRole.YUSHITAI_SUPERVISION -> 0.2
        }
    }

    private fun getDefaultEndpoint(role: AgentRole): String {
        return when (role) {
            AgentRole.ZHONGSHU_DECISION -> "https://api.openai.com/v1/chat/completions"
            AgentRole.MENXIA_AUDIT -> "https://api.anthropic.com/v1/messages"
            AgentRole.SHANGSHU_EXECUTION -> "https://api.openai.com/v1/chat/completions"
            AgentRole.LIBU_HR -> "https://api.openai.com/v1/chat/completions"
            AgentRole.HUBU_DATA -> "https://api.openai.com/v1/chat/completions"
            AgentRole.LIBU_CONTENT -> "https://api.openai.com/v1/chat/completions"
            AgentRole.BINGBU_STRATEGY -> "https://api.deepseek.com/v1/chat/completions"
            AgentRole.XINGBU_COMPLIANCE -> "https://api.anthropic.com/v1/messages"
            AgentRole.GONGBU_TECH -> "https://api.openai.com/v1/chat/completions"
            AgentRole.YUSHITAI_SUPERVISION -> "https://api.openai.com/v1/chat/completions"
        }
    }

    private fun getDefaultPermissions(role: AgentRole): AgentPermissions {
        return when (role) {
            AgentRole.ZHONGSHU_DECISION -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            )
            AgentRole.MENXIA_AUDIT -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = false,
                canCallOtherAgents = true
            )
            AgentRole.SHANGSHU_EXECUTION -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            )
            AgentRole.LIBU_HR -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = true
            )
            AgentRole.HUBU_DATA -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = false
            )
            AgentRole.LIBU_CONTENT -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = false
            )
            AgentRole.BINGBU_STRATEGY -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = false
            )
            AgentRole.XINGBU_COMPLIANCE -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = false,
                canCallOtherAgents = false
            )
            AgentRole.GONGBU_TECH -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = true,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = false
            )
            AgentRole.YUSHITAI_SUPERVISION -> AgentPermissions(
                canUseTools = true,
                canAccessInternet = false,
                canReadFiles = true,
                canWriteFiles = true,
                canCallOtherAgents = false
            )
        }
    }

    fun getThreeProvinceAgents(): List<SanxingAgent> {
        return listOf(
            createAgent(AgentRole.ZHONGSHU_DECISION),
            createAgent(AgentRole.MENXIA_AUDIT),
            createAgent(AgentRole.SHANGSHU_EXECUTION)
        )
    }
}

data class SanxingAgent(
    val agent: Agent,
    val role: SanxingAgentSystem.AgentRole,
    var isActive: Boolean = true,
    var apiConfig: ApiEndpointConfig = ApiEndpointConfig()
)

data class ApiEndpointConfig(
    var endpoint: String = "https://api.openai.com/v1/chat/completions",
    var apiKey: String = "",
    var timeout: Int = 60,
    var retryCount: Int = 3,
    var rateLimit: Int = 100
)

data class AgentUsageStats(
    val agentId: String,
    val callCount: Int = 0,
    val tokenUsage: Int = 0,
    val errorCount: Int = 0,
    val lastCallTime: Long = 0
)
