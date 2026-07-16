package com.apex.agent.core.multiagent

/**
 * 意图分类结果
 *
 * @param category 意图类别
 * @param confidence 置信度 (0.0 ~ 1.0)
 * @param matchedKeywords 命中的关键词列表
 * @param signature 分类签名，用于调试：记录哪些关键词命中了
 */
data class ClassificationResult(
    val category: IntentCategory,
    val confidence: Float,
    val matchedKeywords: List<String>,
    val signature: String
)

/**
 * 意图类别枚举
 */
enum class IntentCategory {
    CODING, SEARCH, CHAT, MEMORY, SETTINGS, FILE_OPERATION,
    WEB_BROWSING, IMAGE_PROCESSING, DATA_ANALYSIS, AUTOMATION,
    MULTI_AGENT, WORKFLOW, SYSTEM_CONTROL, UNKNOWN
}

/**
 * 智能意图分类器 - 参考 AWS Multi-Agent Orchestrator
 *
 * 双阶段分类：
 * 1. 粗分类：将查询匹配到 broad category
 * 2. 精分类：在子类别中进一步匹配（若适用）
 *
 * 支持中文和英文关键词，基于关键词匹配密度计算置信度。
 */
class IntentClassifier(private val context: android.content.Context) {

    companion object {
        private const val TAG = "IntentClassifier"
    }

    /**
     * 粗分类关键词集
     * 每个类别包含中英文关键词
     */
    private val broadPatterns: Map<IntentCategory, List<String>> = mapOf(
        IntentCategory.CODING to listOf(
            "写代码", "编程", "开发", "实现", "函数", "类", "接口", "算法", "调试", "编译",
            "代码", "程序", "bug", "修复", "重构", "优化", "测试", "部署", "git", "提交",
            "code", "program", "develop", "implement", "function", "class", "debug",
            "compile", "algorithm", "refactor", "optimize", "test", "deploy", "commit",
            "pull request", "merge", "branch", "api", "sdk", "library", "dependency"
        ),
        IntentCategory.SEARCH to listOf(
            "搜索", "查找", "查询", "资料", "信息", "文档", "百度", "谷歌", "搜一下",
            "search", "find", "look up", "query", "google", "baidu", "information",
            "research", "documentation", "wiki", "document"
        ),
        IntentCategory.CHAT to listOf(
            "聊天", "对话", "回答", "聊聊", "你好", "嗨", "hello", "hi", "hey",
            "chat", "talk", "conversation", "discuss", "discussion", "greet",
            "你好", "您好", "在吗", "help", "帮助", "问题", "question", "ask",
            "什么", "怎么", "为什么", "如何", "what", "how", "why", "which"
        ),
        IntentCategory.MEMORY to listOf(
            "记忆", "记住", "回忆", "忘记", "存储", "知识", "学习", "记住我",
            "memory", "remember", "recall", "forget", "store", "knowledge",
            "learn", "remember that", "save", "recall that"
        ),
        IntentCategory.SETTINGS to listOf(
            "设置", "配置", "选项", "偏好", "首选项", "调整", "修改设置",
            "settings", "configuration", "config", "preferences", "options",
            "setup", "prefer", "customize"
        ),
        IntentCategory.FILE_OPERATION to listOf(
            "文件", "打开", "保存", "删除", "重命名", "移动", "复制", "创建文件",
            "目录", "文件夹", "路径", "读写", "导入", "导出",
            "file", "open", "save", "delete", "rename", "move", "copy",
            "create", "directory", "folder", "path", "import", "export",
            "read file", "write file"
        ),
        IntentCategory.WEB_BROWSING to listOf(
            "网页", "浏览器", "打开网页", "网址", "url", "链接", "访问网站",
            "web", "browser", "website", "url", "link", "page", "html",
            "打开网站", "上网", "浏览", "browse", "internet"
        ),
        IntentCategory.IMAGE_PROCESSING to listOf(
            "图片", "图像", "照片", "编辑图片", "裁剪", "旋转", "滤镜",
            "压缩图片", "格式转换", "ocr", "识别文字",
            "image", "picture", "photo", "edit image", "crop", "rotate",
            "filter", "compress", "convert", "ocr", "recognize"
        ),
        IntentCategory.DATA_ANALYSIS to listOf(
            "分析", "统计", "图表", "数据", "报表", "可视化", "趋势",
            "处理数据", "数据分析", "挖掘",
            "analyze", "analysis", "statistics", "chart", "graph", "data",
            "report", "visualize", "trend", "insight", "dashboard"
        ),
        IntentCategory.AUTOMATION to listOf(
            "自动化", "自动", "定时", "任务", "批处理", "脚本", "工作流",
            "automation", "automate", "schedule", "batch", "script",
            "cron", "trigger", "pipeline", "auto"
        ),
        IntentCategory.MULTI_AGENT to listOf(
            "多智能体", "多代理", "协作", "分工", "团队", "agent协作",
            "multi-agent", "multi agent", "collaboration", "orchestration",
            "team", "coordinate", "分工", "多个agent", "多个智能体"
        ),
        IntentCategory.WORKFLOW to listOf(
            "工作流", "流程", "步骤", "流水线", "编排", "pipeline",
            "workflow", "process", "step", "pipeline", "orchestrate",
            "顺序执行", "并行执行"
        ),
        IntentCategory.SYSTEM_CONTROL to listOf(
            "系统", "关机", "重启", "状态", "监控", "资源", "性能",
            "system", "shutdown", "restart", "status", "monitor",
            "resource", "performance", "cpu", "memory", "battery"
        )
    )

    /**
     * 子类别/特定领域关键词（可选第二级）
     */
    private val specificPatterns: Map<IntentCategory, Map<String, List<String>>> = mapOf(
        IntentCategory.CODING to mapOf(
            "debug" to listOf("debug", "调试", "bug", "错误", "异常", "crash", "崩溃", "堆栈", "stack", "trace"),
            "test" to listOf("test", "测试", "单元测试", "uitest", "junit", "assert", "断言", "mock"),
            "git" to listOf("git", "commit", "push", "pull", "merge", "分支", "branch", "提交"),
            "refactor" to listOf("refactor", "重构", "优化", "优化代码", "clean", "清理")
        ),
        IntentCategory.MEMORY to mapOf(
            "store" to listOf("记住", "存储", "保存", "remember", "store", "save", "learn"),
            "recall" to listOf("回忆", "想起", "recall", "remember when", "之前"),
            "forget" to listOf("忘记", "forget", "删除记忆", "clear", "清除")
        )
    )

    /**
     * 双阶段分类
     *
     * @param query 用户查询文本
     * @return 分类结果，包含类别、置信度、匹配关键词和调试签名
     */
    fun classifyIntent(query: String): ClassificationResult {
        val lowerQuery = query.lowercase()
        val scores = mutableMapOf<IntentCategory, Float>()
        val matchedKeywords = mutableMapOf<IntentCategory, MutableList<String>>()
        val queryLength = lowerQuery.length.coerceAtLeast(1)

        // 第一阶段：粗分类
        for ((category, patterns) in broadPatterns) {
            var score = 0f
            val matched = mutableListOf<String>()
            for (pattern in patterns) {
                val lowerPattern = pattern.lowercase()
                var startIndex = 0
                while (true) {
                    val idx = lowerQuery.indexOf(lowerPattern, startIndex)
                    if (idx < 0) break
                    matched.add(pattern)
                    // 匹配密度：关键词长度占查询长度的比例
                    score += lowerPattern.length.toFloat() / queryLength
                    startIndex = idx + lowerPattern.length
                }
            }
            if (matched.isNotEmpty()) {
                scores[category] = score
                matchedKeywords[category] = matched
            }
        }

        // 第二阶段：子类别精匹配（对命中大类进一步细分）
        val subScores = mutableMapOf<IntentCategory, MutableMap<String, Float>>()
        for ((category, subMap) in specificPatterns) {
            if (!scores.containsKey(category)) continue
            val subs = mutableMapOf<String, Float>()
            for ((subName, subPatterns) in subMap) {
                var subScore = 0f
                for (pattern in subPatterns) {
                    val lowerPattern = pattern.lowercase()
                    var startIndex = 0
                    while (true) {
                        val idx = lowerQuery.indexOf(lowerPattern, startIndex)
                        if (idx < 0) break
                        subScore += lowerPattern.length.toFloat() / queryLength
                        startIndex = idx + lowerPattern.length
                    }
                }
                if (subScore > 0f) subs[subName] = subScore
            }
            if (subs.isNotEmpty()) subScores[category] = subs
        }

        // 计算归一化置信度
        val maxScore = scores.values.maxOrNull() ?: 0f
        if (maxScore > 0f) {
            for (category in scores.keys) {
                scores[category] = (scores[category] ?: 0f) / maxScore
            }
        }

        // 选择最佳类别
        val sortedCategories = scores.entries.sortedByDescending { it.value }
        val topCategory = sortedCategories.firstOrNull()?.key ?: IntentCategory.UNKNOWN
        val confidence = sortedCategories.firstOrNull()?.value ?: 0f

        // 构建签名
        val signature = buildSignature(lowerQuery, topCategory, confidence, matchedKeywords[topCategory] ?: emptyList(), subScores[topCategory])

        return ClassificationResult(
            category = topCategory,
            confidence = confidence,
            matchedKeywords = (matchedKeywords[topCategory] ?: emptyList()).distinct(),
            signature = signature
        )
    }

    /**
     * 构建分类签名，用于调试
     */
    private fun buildSignature(
        query: String,
        category: IntentCategory,
        confidence: Float,
        keywords: List<String>,
        subs: Map<String, Float>?
    ): String {
        val sb = StringBuilder()
        sb.append("IntentClassification{")
        sb.append("query='${query.take(50)}'")
        sb.append(", category=$category")
        sb.append(", confidence=%.4f".format(confidence))
        sb.append(", keywords=${keywords.distinct().take(10)}")
        if (!subs.isNullOrEmpty()) {
            sb.append(", subCategories=${subs.entries.joinToString(",") { "${it.key}=%.2f".format(it.value) }}")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 获取分类签名（用于调试日志）
     */
    fun getClassificationSignature(query: String): String {
        return classifyIntent(query).signature
    }
}
