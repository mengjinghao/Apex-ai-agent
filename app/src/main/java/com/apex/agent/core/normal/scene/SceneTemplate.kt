package com.apex.agent.core.normal.scene

import java.util.concurrent.ConcurrentHashMap

/**
 * F13: 场景化对话模板（Scene Template）
 *
 * 预置"编程/写作/翻译/学习/问答/头脑风暴"等场景模板，
 * 每个模板包含 system prompt + 工具集 + 推荐参数。用户可一键切换。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的"模板"是 Agent 角色
 * - 狂暴是策略
 * - 本功能是**对话场景模板**，体现单 Agent 的场景适配
 */

/**
 * 场景模板
 */
data class SceneTemplate(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val icon: String,
    val systemPrompt: String,
    val recommendedTools: List<String> = emptyList(),
    val recommendedDepth: String = "standard",  // brief/standard/detailed/comprehensive
    val recommendedStyle: String = "balanced",   // balanced/technical/conversational/academic
    val suggestedParams: Map<String, Any> = emptyMap(),
    val exampleQuestions: List<String> = emptyList(),
    val category: SceneCategory,
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true
)

enum class SceneCategory {
    PROGRAMMING, WRITING, TRANSLATION, LEARNING, QNA,
    BRAINSTORMING, ANALYSIS, CREATIVE, PRODUCTIVITY, PERSONAL
}

/**
 * 场景模板注册表
 */
class SceneTemplateRegistry {

    private val templates = ConcurrentHashMap<String, SceneTemplate>()
        private val activeTemplates = ConcurrentHashMap<String, String>()  // chatId -> templateId
    init {
        registerBuiltinTemplates()
    }

    /**
     * 注册模板
     */
    fun register(template: SceneTemplate) {
        templates[template.id] = template
    }

    /**
     * 获取模板
     */
    fun get(id: String): SceneTemplate? = templates[id]

    /**
     * 列出所有模板
     */
    fun list(category: SceneCategory? = null): List<SceneTemplate> {
        return templates.values
            .filter { it.enabled }
            .filter { category == null || it.category == category }
            .sortedBy { it.displayName }
            .toList()
    }

    /**
     * 为对话应用模板
     */
    fun apply(chatId: String, templateId: String): SceneTemplate? {
        val template = templates[templateId] ?: return null
        activeTemplates[chatId] = templateId
        return template
    }

    /**
     * 获取当前活跃模板
     */
    fun getActive(chatId: String): SceneTemplate? {
        val id = activeTemplates[chatId] ?: return null
        return templates[id]
    }

    /**
     * 清除当前场景
     */
    fun clear(chatId: String) {
        activeTemplates.remove(chatId)
    }

    /**
     * 生成场景 prompt 注入
     */
    fun generateScenePrompt(chatId: String): String {
        val template = getActive(chatId) ?: return ""
        return buildString {
            appendLine("[当前场景: ${template.displayName}]")
        appendLine(template.systemPrompt)
        if (template.recommendedDepth.isNotBlank()) {
                appendLine("[推荐深度: ${template.recommendedDepth}]")
            }
        if (template.recommendedStyle.isNotBlank()) {
                appendLine("[推荐风格: ${template.recommendedStyle}]")
            }
        }
    }

    /**
     * 搜索模板
     */
    fun search(query: String): List<SceneTemplate> {
        val q = query.lowercase()
        return templates.values.filter { template ->
            template.name.contains(q, true) ||
            template.displayName.contains(q, true) ||
            template.description.contains(q, true) ||
            template.tags.any { it.contains(q, true) }
        }.toList()
    }

    // ============ 预置模板 ============
    private fun registerBuiltinTemplates() {
        // 编程助手
        register(SceneTemplate(
            id = "scene_programming",
            name = "programming",
            displayName = "编程助手",
            description = "代码编写、调试、重构、代码审查",
            icon = "💻",
            systemPrompt = """你是一位资深全栈工程师助手。
- 回答时优先给出可运行的代码示例
- 代码需包含必要注释
- 涉及最佳实践时主动说明
- 发现潜在 bug 或性能问题时主动提醒
- 支持多种编程语言：Python/Kotlin/Java/JavaScript/TypeScript/Rust/Go/C++""",
            recommendedTools = listOf("code_execution", "file_read", "file_write", "search"),
            recommendedDepth = "detailed",
            recommendedStyle = "technical",
            suggestedParams = mapOf("temperature" to 0.2, "max_tokens" to 4096),
            exampleQuestions = listOf(
                "帮我写一个 Python 单例模式",
                "这段 Kotlin 代码有什么问题：...",
                "如何用 React 实现一个拖拽列表？"
            ),
            category = SceneCategory.PROGRAMMING,
            tags = listOf("代码", "开发", "调试")
        ))

        // 写作助手
        register(SceneTemplate(
            id = "scene_writing",
            name = "writing",
            displayName = "写作助手",
            description = "文章、报告、邮件、文案撰写",
            icon = "✍️",
            systemPrompt = """你是一位专业的写作助手。
- 根据场景调整文风（正式/休闲/学术/营销）
- 注重逻辑结构和段落衔接
- 主动提供标题建议
- 支持润色、改写、扩写、缩写
- 避免空话套话，注重内容实质""",
            recommendedDepth = "comprehensive",
            recommendedStyle = "balanced",
            suggestedParams = mapOf("temperature" to 0.7, "max_tokens" to 8192),
            exampleQuestions = listOf(
                "帮我写一封求职信",
                "把这段话改得更正式：...",
                "写一篇关于人工智能的科普文章"
            ),
            category = SceneCategory.WRITING,
            tags = listOf("写作", "文案", "润色")
        ))

        // 翻译
        register(SceneTemplate(
            id = "scene_translation",
            name = "translation",
            displayName = "翻译专家",
            description = "多语言互译，保留语境与语气",
            icon = "🌐",
            systemPrompt = """你是一位专业翻译，精通中英日韩等多语言。
- 翻译时保留原文语气和风格
- 遇到专业术语给出原文注释
- 提供多种译法供选择
- 支持本地化适配（如美式英语 vs 英式英语）
- 代码/技术文档保持术语一致""",
            recommendedDepth = "standard",
            recommendedStyle = "balanced",
            exampleQuestions = listOf(
                "把这段英文翻译成中文：...",
                "Translate to English: ...",
                "这句话用日语怎么说？"
            ),
            category = SceneCategory.TRANSLATION,
            tags = listOf("翻译", "多语言")
        ))

        // 学习导师
        register(SceneTemplate(
            id = "scene_learning",
            name = "learning",
            displayName = "学习导师",
            description = "概念讲解、知识梳理、答疑解惑",
            icon = "📚",
            systemPrompt = """你是一位耐心的学习导师。
- 由浅入深，循序渐进
- 用类比帮助理解抽象概念
- 主动举例和画图说明
- 鼓励提问，不厌其烦
- 检测理解程度，适时复习""",
            recommendedDepth = "detailed",
            recommendedStyle = "conversational",
            exampleQuestions = listOf(
                "解释一下什么是闭包",
                "帮我理解 React Hooks 的工作原理",
                "什么是 CAP 定理？"
            ),
            category = SceneCategory.LEARNING,
            tags = listOf("学习", "教学", "科普")
        ))

        // 头脑风暴
        register(SceneTemplate(
            id = "scene_brainstorming",
            name = "brainstorming",
            displayName = "头脑风暴",
            description = "创意发想、方案探索、可能性拓展",
            icon = "💡",
            systemPrompt = """你是一位创意十足的头脑风暴伙伴。
- 不急于否定任何想法
- 提供多样化的视角和方向
- 鼓励"疯狂"想法
- 帮助组合和演进想法
- 适时归纳和收敛""",
            recommendedDepth = "detailed",
            recommendedStyle = "conversational",
            suggestedParams = mapOf("temperature" to 0.9),
            exampleQuestions = listOf(
                "为新产品起名，给我 20 个想法",
                "如何提高用户留存？头脑风暴一下",
                "周末活动创意，越独特越好"
            ),
            category = SceneCategory.BRAINSTORMING,
            tags = listOf("创意", "想法", "发散")
        ))

        // 数据分析
        register(SceneTemplate(
            id = "scene_analysis",
            name = "analysis",
            displayName = "数据分析",
            description = "数据解读、趋势分析、洞察提取",
            icon = "📊",
            systemPrompt = """你是一位数据分析师。
- 注重数据背后的洞察
- 提供多个维度的分析
- 区分事实与推测
- 给出可执行的建议
- 必要时提醒数据局限性""",
            recommendedDepth = "comprehensive",
            recommendedStyle = "technical",
            exampleQuestions = listOf(
                "分析这组销售数据：...",
                "这个趋势说明了什么？",
                "帮我做一份用户画像分析"
            ),
            category = SceneCategory.ANALYSIS,
            tags = listOf("数据", "分析", "洞察")
        ))

        // 生产力
        register(SceneTemplate(
            id = "scene_productivity",
            name = "productivity",
            displayName = "效率助手",
            description = "待办管理、时间规划、流程优化",
            icon = "⚡",
            systemPrompt = """你是一位效率顾问。
- 帮助拆解复杂任务
- 推荐时间管理方法（番茄钟/GTD/ Eisenhower）
- 优化工作流程
- 提醒优先级和截止日期
- 避免过度建议，聚焦可执行方案""",
            recommendedDepth = "standard",
            recommendedStyle = "balanced",
            recommendedTools = listOf("todo", "calendar", "reminder"),
            exampleQuestions = listOf(
                "帮我规划下周的工作",
                "如何高效学习一门新技能？",
                "这个流程怎么优化？"
            ),
            category = SceneCategory.PRODUCTIVITY,
            tags = listOf("效率", "时间管理", "规划")
        ))

        // 个人助理
        register(SceneTemplate(
            id = "scene_personal",
            name = "personal",
            displayName = "个人助理",
            description = "日常问答、生活建议、闲聊",
            icon = "🤝",
            systemPrompt = """你是用户的个人 AI 助理。
- 友好、体贴、有温度
- 记住用户的偏好和习惯
- 主动关心但不啰嗦
- 诚实，不假装全知
- 遇到敏感话题谨慎处理""",
            recommendedDepth = "standard",
            recommendedStyle = "conversational",
            exampleQuestions = listOf(
                "今天天气怎么样？",
                "晚饭吃什么好？",
                "推荐一部电影"
            ),
            category = SceneCategory.PERSONAL,
            tags = listOf("日常", "生活", "闲聊")
        ))
    }
}
