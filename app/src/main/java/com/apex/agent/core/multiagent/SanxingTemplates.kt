package com.apex.agent.core.multiagent

/**
 * 三省六部制任务模板数据类
 */
data class SanxingTaskTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val category: TemplateCategory,
    val config: TemplateConfig,
    val usageTips: String? = null
)

/**
 * 模板分类
 */
enum class TemplateCategory(val displayName: String) {
    SEARCH("深度搜索"),
    CONTENT("内容创作"),
    CODE("代码开�?),
    ANALYSIS("分析研究"),
    CUSTOM("自定�?)
}

/**
 * 模板配置
 */
data class TemplateConfig(
    // 激活的Agent列表（角色枚举名�?
    val activeAgentRoles: List<String>,
    // 任务初始提示词（用户发送消息前的预设）
    val systemPrompt: String? = null,
    // 各Agent的特定配置覆�?
    val agentOverrides: Map<String, AgentOverride>? = null,
    // 是否启用智能任务分配
    val enableIntelligentAllocation: Boolean = true,
    // 搜索相关配置（深度搜索模板用�?
    val searchConfig: SearchConfig? = null
)

/**
 * Agent配置覆盖
 */
data class AgentOverride(
    val temperature: Double? = null,
    val systemPrompt: String? = null,
    val maxTokens: Int? = null
)

/**
 * 深度搜索配置
 */
data class SearchConfig(
    val maxLoop: Int = 3,
    val searchResultLimit: Int = 8,
    val maxFetchPageCount: Int = 6,
    val authorityDomains: List<String> = listOf(
        ".gov.cn", ".org.cn", ".edu.cn",
        "people.com.cn", "xinhuanet.com",
        "thepaper.cn"
    ),
    val timeout: Int = 5000
)

/**
 * 三省六部制模板库
 */
object SanxingTemplateLibrary {

    /**
     * 获取所有模�?
     */
    fun getAllTemplates(): List<SanxingTaskTemplate> {
        return listOf(
            deepSearchTemplate(),
            contentCreationTemplate(),
            codeDevelopmentTemplate(),
            marketAnalysisTemplate(),
            researchReportTemplate()
        )
    }

    /**
     * 根据ID获取模板
     */
    fun getTemplateById(id: String): SanxingTaskTemplate? {
        return getAllTemplates().find { it.id == id }
    }

    // ===================== 模板定义 =====================

    /**
     * ?? 深度搜索模板（对标豆包深度搜索）
     */
    private fun deepSearchTemplate(): SanxingTaskTemplate {
        return SanxingTaskTemplate(
            id = "deep_search",
            name = "深度搜索研究",
            description = "多轮深度搜索+网页爬取+交叉验证，生成高质量研究报告",
            icon = "??",
            category = TemplateCategory.SEARCH,
            config = TemplateConfig(
                activeAgentRoles = listOf(
                    "ZHONGSHU_DECISION",
                    "MENXIA_AUDIT",
                    "SHANGSHU_EXECUTION",
                    "HUBU_DATA",
                    "LIBU_CONTENT",
                    "BINGBU_STRATEGY",
                    "XINGBU_COMPLIANCE"
                ),
                systemPrompt = """
                    你现在是Apex深度搜索研究助手�?
                    你将启动三省六部制系统：
                    - 中书省：拆解需求，生成搜索计划
                    - 兵部：多关键词深度搜�?
                    - 户部：高价值网页爬�?
                    - 刑部+门下省：多源交叉验证
                    - 礼部：内容结构化整合
                    
                    最终将生成一份完整、准确、有深度的研究报告�?
                """.trimIndent(),
                enableIntelligentAllocation = true,
                searchConfig = SearchConfig(
                    maxLoop = 3,
                    searchResultLimit = 8,
                    maxFetchPageCount = 6
                )
            ),
            usageTips = "适合：最新资讯、行业研究、数据调查、事实核�?
        )
    }

    /**
     * ?? 文案创作模板
     */
    private fun contentCreationTemplate(): SanxingTaskTemplate {
        return SanxingTaskTemplate(
            id = "content_creation",
            name = "专业文案创作",
            description = "多轮优化+合规审查，产出高质量文案作品",
            icon = "??",
            category = TemplateCategory.CONTENT,
            config = TemplateConfig(
                activeAgentRoles = listOf(
                    "ZHONGSHU_DECISION",
                    "MENXIA_AUDIT",
                    "SHANGSHU_EXECUTION",
                    "LIBU_CONTENT",
                    "XINGBU_COMPLIANCE"
                ),
                systemPrompt = """
                    你现在是Apex专业文案创作助手�?
                    将通过三省六部制协作完成文案创作：
                    - 中书省：拆解创作需求，制定方案
                    - 礼部：多版本文案创作
                    - 门下省：质量审核和优化建�?
                    - 刑部：合规性审�?
                    - 最终交付完美文案！
                """.trimIndent(),
                enableIntelligentAllocation = true
            ),
            usageTips = "适合：广告文案、公众号文章，品牌文案、演讲稿"
        )
    }

    /**
     * ?? 代码开发模�?
     */
    private fun codeDevelopmentTemplate(): SanxingTaskTemplate {
        return SanxingTaskTemplate(
            id = "code_development",
            name = "工程项目开�?,
            description = "架构设计+代码开�质量检�完整项目交付",
            icon = "??",
            category = TemplateCategory.CODE,
            config = TemplateConfig(
                activeAgentRoles = listOf(
                    "ZHONGSHU_DECISION",
                    "MENXIA_AUDIT",
                    "SHANGSHU_EXECUTION",
                    "GONGBU_TECH",
                    "XINGBU_COMPLIANCE"
                ),
                systemPrompt = """
                    你现在是Apex工程项目开发助手�?
                    将通过三省六部制协作完成完整项目：
                    - 中书省：需求拆解，技术方案设�?
                    - 工部：架构设计、代码开�?
                    - 刑部：代码质量审查、安全检�?
                    - 门下省：最终验�?
                """.trimIndent(),
                enableIntelligentAllocation = true
            ),
            usageTips = "适合：功能开发、系统重构、技术选型、原型制�?
        )
    }

    /**
     * ?? 市场分析模板
     */
    private fun marketAnalysisTemplate(): SanxingTaskTemplate {
        return SanxingTaskTemplate(
            id = "market_analysis",
            name = "市场战略分析",
            description = "数据分析+竞品研究+策略制定=战略决策支持",
            icon = "??",
            category = TemplateCategory.ANALYSIS,
            config = TemplateConfig(
                activeAgentRoles = listOf(
                    "ZHONGSHU_DECISION",
                    "MENXIA_AUDIT",
                    "SHANGSHU_EXECUTION",
                    "HUBU_DATA",
                    "BINGBU_STRATEGY",
                    "LIBU_CONTENT"
                ),
                systemPrompt = """
                    你现在是Apex市场战略分析助手�?
                    将通过三省六部制协作：
                    - 中书省：分析需求拆�?
                    - 兵部：竞品分析、策略制�?
                    - 户部：数据收集、统计分�?
                    - 礼部：最终报告撰�?
                    - 门下省：审核+质量把控
                """.trimIndent(),
                enableIntelligentAllocation = true
            ),
            usageTips = "适合：竞品分析、市场调查、战略规划、投资决�?
        )
    }

    /**
     * ?? 研究报告模板
     */
    private fun researchReportTemplate(): SanxingTaskTemplate {
        return SanxingTaskTemplate(
            id = "research_report",
            name = "学术研究报告",
            description = "深度研究+多方验证+结构化学术输�?,
            icon = "??",
            category = TemplateCategory.ANALYSIS,
            config = TemplateConfig(
                activeAgentRoles = listOf(
                    "ZHONGSHU_DECISION",
                    "MENXIA_AUDIT",
                    "SHANGSHU_EXECUTION",
                    "HUBU_DATA",
                    "LIBU_CONTENT",
                    "XINGBU_COMPLIANCE"
                ),
                systemPrompt = """
                    你现在是Apex学术研究助手�?
                    将通过严谨的三省六部制流程�?
                    - 中书省：研究方案设计
                    - 兵部：多源资料搜�?
                    - 户部：数据处理和分析
                    - 刑部：事实核查和引用验证
                    - 礼部：结构化报告撰写
                """.trimIndent(),
                enableIntelligentAllocation = true
            ),
            usageTips = "适合：学术研究、行业报告、专题调研、资料汇�?
        )
    }
}
