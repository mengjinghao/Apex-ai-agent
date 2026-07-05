package com.apex.lib.rage

/**
 * 狂暴模式技能分类。
 */
enum class RageSkillCategory {
    REASONING,        // 推理类（ReAct / ToT / CoT 等）
    CODING,           // 编码类
    SEARCH,           // 搜索类
    ANALYSIS,         // 分析类
    GENERATION,       // 生成类
    TRANSFORMATION,   // 转换类
    UTILITY           // 工具类
}

/**
 * 技能描述符 — 内置 31 技能的元数据。
 *
 * @param id          技能唯一 ID（如 `reasoning.react`）
 * @param name        显示名
 * @param description 描述
 * @param category    分类（见 [RageSkillCategory]）
 * @param parameters  参数说明（参数名 → 类型描述）
 * @param tags        标签列表
 * @param priority    优先级（数值越大越优先，0-100）
 */
data class RageSkillDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val category: RageSkillCategory,
    val parameters: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val priority: Int = 50
)

/**
 * 技能目录 — 内置 31 技能注册表，支持按分类 / 名称 / ID 查询。
 *
 * 用法：
 * ```
 * val catalog = RageSkillCatalog.default()
 * catalog.list()                        // 全部 31 技能
 * catalog.findByCategory(RageSkillCategory.REASONING)  // 推理类
 * catalog.find("reasoning.react")       // 按 ID
 * catalog.findByName("ReAct 推理")      // 按名
 * ```
 */
class RageSkillCatalog {

    private val skills = linkedMapOf<String, RageSkillDescriptor>()

    /** 注册技能。 */
    fun register(descriptor: RageSkillDescriptor) {
        skills[descriptor.id] = descriptor
    }

    /** 按 ID 查找。 */
    fun find(id: String): RageSkillDescriptor? = skills[id]

    /** 按显示名查找。 */
    fun findByName(name: String): RageSkillDescriptor? =
        skills.values.firstOrNull { it.name == name }

    /** 列出全部技能。 */
    fun list(): List<RageSkillDescriptor> = skills.values.toList()

    /** 按分类查询。 */
    fun findByCategory(category: RageSkillCategory): List<RageSkillDescriptor> =
        skills.values.filter { it.category == category }

    /** 技能数量。 */
    fun count(): Int = skills.size

    companion object {

        /**
         * 31 个内置技能。
         *
         * 分类：
         * - 推理类 8 个（ReAct / ToT / CoT / Self-Refine / Reflexion / Racing / Adversarial / Debate）
         * - 编码类 8 个（生成 / 审查 / 调试 / 重构 / 测试 / 解释 / 优化 / 迁移）
         * - 搜索类 5 个（代码库 / GitHub / Web / 文档 / AST）
         * - 分析类 4 个（影响 / 依赖 / 安全 / 复杂度）
         * - 生成类 3 个（文档 / Commit / PR）
         * - 转换类 2 个（格式化 / 翻译）
         * - 工具类 1 个（Shell）
         */
        val BUILTIN: List<RageSkillDescriptor> = listOf(
            // ===== 推理类 (8) =====
            RageSkillDescriptor(
                id = "reasoning.react",
                name = "ReAct 推理",
                description = "交替推理与行动的循环式问题求解",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("maxSteps" to "Int", "tools" to "List<String>"),
                tags = listOf("reasoning", "react", "tool-use"),
                priority = 90
            ),
            RageSkillDescriptor(
                id = "reasoning.tot",
                name = "Tree of Thought",
                description = "树状思维搜索，多分支探索取最优解",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("branchingFactor" to "Int", "maxDepth" to "Int"),
                tags = listOf("reasoning", "tot", "search"),
                priority = 85
            ),
            RageSkillDescriptor(
                id = "reasoning.cot",
                name = "Chain of Thought",
                description = "链式思维推理，逐步推导结论",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("steps" to "Int"),
                tags = listOf("reasoning", "cot"),
                priority = 80
            ),
            RageSkillDescriptor(
                id = "reasoning.self_refine",
                name = "自我精炼",
                description = "生成-评估-改进循环，迭代优化输出",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("maxIterations" to "Int"),
                tags = listOf("reasoning", "refine"),
                priority = 75
            ),
            RageSkillDescriptor(
                id = "reasoning.reflexion",
                name = "反思推理",
                description = "失败后反思原因并重试，带记忆",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("maxRetries" to "Int", "memorySize" to "Int"),
                tags = listOf("reasoning", "reflexion", "retry"),
                priority = 78
            ),
            RageSkillDescriptor(
                id = "reasoning.racing",
                name = "并行竞速",
                description = "多路并行推理，取置信度最高的结果",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("parallelism" to "Int", "selection" to "String"),
                tags = listOf("reasoning", "racing", "parallel"),
                priority = 72
            ),
            RageSkillDescriptor(
                id = "reasoning.adversarial",
                name = "对抗推理",
                description = "Generator vs Discriminator 对抗迭代",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("rounds" to "Int"),
                tags = listOf("reasoning", "adversarial"),
                priority = 70
            ),
            RageSkillDescriptor(
                id = "reasoning.debate",
                name = "辩论推理",
                description = "多角色辩论后由主持人裁决",
                category = RageSkillCategory.REASONING,
                parameters = mapOf("debaters" to "Int", "rounds" to "Int"),
                tags = listOf("reasoning", "debate"),
                priority = 68
            ),

            // ===== 编码类 (8) =====
            RageSkillDescriptor(
                id = "code.generate",
                name = "代码生成",
                description = "根据需求描述生成代码",
                category = RageSkillCategory.CODING,
                parameters = mapOf("language" to "String", "framework" to "String?"),
                tags = listOf("code", "generate"),
                priority = 88
            ),
            RageSkillDescriptor(
                id = "code.review",
                name = "代码审查",
                description = "自动审查代码质量，给出改进建议",
                category = RageSkillCategory.CODING,
                parameters = mapOf("strictness" to "String"),
                tags = listOf("code", "review"),
                priority = 82
            ),
            RageSkillDescriptor(
                id = "code.debug",
                name = "调试",
                description = "定位并修复 Bug",
                category = RageSkillCategory.CODING,
                parameters = mapOf("errorLog" to "String?", "reproduce" to "String?"),
                tags = listOf("code", "debug"),
                priority = 86
            ),
            RageSkillDescriptor(
                id = "code.refactor",
                name = "重构",
                description = "改善代码结构，保持行为不变",
                category = RageSkillCategory.CODING,
                parameters = mapOf("pattern" to "String?"),
                tags = listOf("code", "refactor"),
                priority = 65
            ),
            RageSkillDescriptor(
                id = "code.test_gen",
                name = "测试生成",
                description = "自动生成单元测试",
                category = RageSkillCategory.CODING,
                parameters = mapOf("framework" to "String", "coverage" to "Float"),
                tags = listOf("code", "test"),
                priority = 76
            ),
            RageSkillDescriptor(
                id = "code.explain",
                name = "代码解释",
                description = "解释代码逻辑与设计意图",
                category = RageSkillCategory.CODING,
                parameters = mapOf("detailLevel" to "String"),
                tags = listOf("code", "explain"),
                priority = 60
            ),
            RageSkillDescriptor(
                id = "code.optimize",
                name = "性能优化",
                description = "分析性能瓶颈并给出优化方案",
                category = RageSkillCategory.CODING,
                parameters = mapOf("profile" to "String?"),
                tags = listOf("code", "performance"),
                priority = 70
            ),
            RageSkillDescriptor(
                id = "code.migrate",
                name = "代码迁移",
                description = "跨语言/框架迁移代码",
                category = RageSkillCategory.CODING,
                parameters = mapOf("from" to "String", "to" to "String"),
                tags = listOf("code", "migrate"),
                priority = 62
            ),

            // ===== 搜索类 (5) =====
            RageSkillDescriptor(
                id = "search.codebase",
                name = "代码库搜索",
                description = "全库语义检索 + 向量搜索",
                category = RageSkillCategory.SEARCH,
                parameters = mapOf("query" to "String", "topK" to "Int"),
                tags = listOf("search", "rag", "codebase"),
                priority = 84
            ),
            RageSkillDescriptor(
                id = "search.github",
                name = "GitHub 搜索",
                description = "搜索 GitHub 代码与 Issue",
                category = RageSkillCategory.SEARCH,
                parameters = mapOf("query" to "String", "repo" to "String?"),
                tags = listOf("search", "github"),
                priority = 66
            ),
            RageSkillDescriptor(
                id = "search.web",
                name = "网页搜索",
                description = "实时网络搜索",
                category = RageSkillCategory.SEARCH,
                parameters = mapOf("query" to "String", "maxResults" to "Int"),
                tags = listOf("search", "web"),
                priority = 64
            ),
            RageSkillDescriptor(
                id = "search.docs",
                name = "文档搜索",
                description = "官方文档检索",
                category = RageSkillCategory.SEARCH,
                parameters = mapOf("query" to "String", "source" to "String?"),
                tags = listOf("search", "docs"),
                priority = 58
            ),
            RageSkillDescriptor(
                id = "search.ast",
                name = "AST 解析",
                description = "语法树分析，提取调用关系图",
                category = RageSkillCategory.SEARCH,
                parameters = mapOf("file" to "String", "depth" to "Int"),
                tags = listOf("search", "ast", "parse"),
                priority = 60
            ),

            // ===== 分析类 (4) =====
            RageSkillDescriptor(
                id = "analyze.impact",
                name = "影响分析",
                description = "变更影响范围分析",
                category = RageSkillCategory.ANALYSIS,
                parameters = mapOf("change" to "String"),
                tags = listOf("analyze", "impact"),
                priority = 72
            ),
            RageSkillDescriptor(
                id = "analyze.dependency",
                name = "依赖分析",
                description = "模块依赖关系分析",
                category = RageSkillCategory.ANALYSIS,
                parameters = mapOf("module" to "String?"),
                tags = listOf("analyze", "dependency"),
                priority = 68
            ),
            RageSkillDescriptor(
                id = "analyze.security",
                name = "安全分析",
                description = "安全漏洞扫描",
                category = RageSkillCategory.ANALYSIS,
                parameters = mapOf("rules" to "List<String>?"),
                tags = listOf("analyze", "security", "scan"),
                priority = 80
            ),
            RageSkillDescriptor(
                id = "analyze.complexity",
                name = "复杂度分析",
                description = "圈复杂度计算与报告",
                category = RageSkillCategory.ANALYSIS,
                parameters = mapOf("threshold" to "Int?"),
                tags = listOf("analyze", "complexity"),
                priority = 55
            ),

            // ===== 生成类 (3) =====
            RageSkillDescriptor(
                id = "gen.docs",
                name = "文档生成",
                description = "自动生成 API 文档",
                category = RageSkillCategory.GENERATION,
                parameters = mapOf("format" to "String"),
                tags = listOf("generate", "docs"),
                priority = 60
            ),
            RageSkillDescriptor(
                id = "gen.commit",
                name = "Commit 生成",
                description = "生成 Git Commit 消息",
                category = RageSkillCategory.GENERATION,
                parameters = mapOf("style" to "String"),
                tags = listOf("generate", "commit", "git"),
                priority = 56
            ),
            RageSkillDescriptor(
                id = "gen.pr",
                name = "PR 生成",
                description = "生成 Pull Request 描述",
                category = RageSkillCategory.GENERATION,
                parameters = mapOf("template" to "String?"),
                tags = listOf("generate", "pr"),
                priority = 54
            ),

            // ===== 转换类 (2) =====
            RageSkillDescriptor(
                id = "transform.format",
                name = "格式化",
                description = "代码格式化",
                category = RageSkillCategory.TRANSFORMATION,
                parameters = mapOf("rules" to "String?"),
                tags = listOf("transform", "format"),
                priority = 50
            ),
            RageSkillDescriptor(
                id = "transform.translate",
                name = "翻译",
                description = "多语言翻译",
                category = RageSkillCategory.TRANSFORMATION,
                parameters = mapOf("from" to "String", "to" to "String"),
                tags = listOf("transform", "translate", "i18n"),
                priority = 52
            ),

            // ===== 工具类 (1) =====
            RageSkillDescriptor(
                id = "utility.shell",
                name = "Shell 执行",
                description = "命令行执行",
                category = RageSkillCategory.UTILITY,
                parameters = mapOf("command" to "String", "timeout" to "Long"),
                tags = listOf("utility", "shell", "exec"),
                priority = 40
            )
        )

        /** 创建包含全部 31 内置技能的目录实例。 */
        fun default(): RageSkillCatalog = RageSkillCatalog().apply {
            BUILTIN.forEach { register(it) }
        }
    }
}
