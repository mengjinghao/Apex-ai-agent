package com.apex.agent.mts.router

import com.apex.agent.mts.registry.ToolRegistry
import com.apex.agent.mts.schema.*

data class ExecutionContext(
    val deviceInfo: Map<String, Any> = emptyMap(),
    val hasNetwork: Boolean = true,
    val hasRoot: Boolean = false,
    val permissionLevel: PermissionLevel = PermissionLevel.STANDARD,
    val recentToolCalls: List<String> = emptyList(),
    val availableMemory: Long = Long.MAX_VALUE,
    val batteryLevel: Int = 100,
    val isInteractive: Boolean = true,
    /** 当前运行模式 */
    val agentMode: AgentMode = AgentMode.NORMAL
)

data class RoutingPlan(
    val primaryTool: ToolSpec?,
    val alternatives: List<ScoredTool>,
    val suggestedChain: List<ToolSpec> = emptyList(),
    val parallelHints: Set<String> = emptySet(),
    val reasoning: String = "",
    val confidence: Double = 0.0
)

data class IntentAnalysis(
    val intent: String,
    val entities: Map<String, String> = emptyMap(),
    val action: String = "",
    val target: String = "",
    val operationType: OperationType = OperationType.READ
)

enum class OperationType { READ, WRITE, DELETE, EXECUTE, NAVIGATE, SEARCH, ANALYZE, MANAGE, CREATE }

class IntelligentRouter(
    private val registry: ToolRegistry
) {
    companion object {
        private val intentPatterns = mapOf(
            // File operations
            listOf("read", "open", "view", "show", "see", "content", "文本", "读取", "查看", "打开") to "read_file",
            listOf("write", "save", "create", "edit", "update", "修改", "写入", "保存", "编辑") to "write_file",
            listOf("list", "ls", "dir", "browse", "explore", "导航", "目录", "文件夹", "浏览") to "list_files",
            listOf("find", "search", "grep", "lookup", "locate", "搜索", "查找", "找") to "find_files",
            listOf("delete", "remove", "rm", "del", "删除") to "delete_file",
            listOf("copy", "cp", "duplicate", "复制") to "copy_file",
            listOf("move", "mv", "rename", "移动", "重命名") to "move_file",
            listOf("download", "下载") to "download_file",
            listOf("zip", "compress", "压缩") to "zip_files",
            listOf("unzip", "extract", "解压") to "unzip_files",
            // UI operations
            listOf("click", "tap", "press", "点", "点击", "按") to "click_element",
            listOf("swipe", "scroll", "slide", "滑", "滑动", "滚动") to "swipe",
            listOf("screenshot", "capture", "screen", "截图", "截屏", "屏幕") to "capture_screenshot",
            listOf("type", "input", "enter", "输入", "打字") to "set_input_text",
            // Web operations
            listOf("web", "visit", "url", "http", "website", "网页", "网站", "访问", "http请求") to "visit_web",
            listOf("browser", "浏览器") to "browser_snapshot",
            // Device operations
            listOf("device", "info", "phone", "设备", "信息", "手机") to "device_info",
            listOf("install", "安装", "app") to "install_app",
            listOf("uninstall", "卸载") to "uninstall_app",
            listOf("start", "launch", "open app", "启动", "打开应用") to "start_app",
            listOf("stop", "kill", "force stop", "停止", "关闭") to "stop_app",
            // Memory operations
            listOf("remember", "memory", "memorize", "记忆", "记住", "回忆") to "query_memory",
            listOf("calculate", "calc", "math", "计算", "数学") to "calculate",
            listOf("notify", "notification", "通知", "推送") to "send_notification",
            listOf("intent", "broadcast", "意图", "广播") to "execute_intent",
            listOf("shell", "terminal", "command", "adb", "命令行", "终端", "命令") to "execute_shell",
            listOf("workflow", "自动化", "工作流") to "get_all_workflows",
            listOf("chat", "talk", "message", "对话", "聊天", "消息") to "send_message_to_ai",
            listOf("ffmpeg", "media", "video", "audio", "convert", "视频", "音频", "转换") to "ffmpeg_execute"
        )

        private val entityPatterns = mapOf(
            "path" to Regex("(?:path|file|folder|directory|dir|文件|路径|目录)\\s*[:：]?\\s*([\"\u2018]?[^\"]+[\"\u2019]?)"),
            "url" to Regex("(?:url|link|website|site|网址|链接|网站)\\s*[:：]?\\s*([\"\u2018]?https?://[^\"\\s]+[\"\u2019]?)"),
            "query" to Regex("(?:query|search|find|查找|搜索)\\s*[:：]?\\s*([\"\u2018]?[^\"]+[\"\u2019]?)"),
            "package" to Regex("(?:package|app|pkg|包|应用)\\s*[:：]?\\s*([\"\u2018]?[a-zA-Z0-9.]+[\"\u2019]?)")
        )
    }

    fun analyzeIntent(userInput: String): IntentAnalysis {
        val lower = userInput.lowercase()
        val intentWords = lower.split(" ", "，", "。", "？", "！", "\n")

        var bestMatch = ""
        var maxScore = 0
        for ((keywords, toolName) in intentPatterns) {
            val score = keywords.sumOf { kw ->
                if (lower.contains(kw)) kw.length else 0
            }
            if (score > maxScore) {
                maxScore = score
                bestMatch = toolName
            }
        }

        val entities = mutableMapOf<String, String>()
        for ((key, pattern) in entityPatterns) {
            val match = pattern.find(userInput)
            if (match != null) {
                val value = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("\u2018").removeSurrounding("\u2019")
                entities[key] = value
            }
        }

        val action = bestMatch
        val opType = when {
            action.contains("write") || action.contains("create") || action.contains("edit") ||
                action.contains("save") || action.contains("set") -> OperationType.WRITE
            action.contains("delete") || action.contains("remove") || action.contains("uninstall") -> OperationType.DELETE
            action.contains("find") || action.contains("search") || action.contains("query") ||
                action.contains("list") -> OperationType.SEARCH
            action.contains("read") || action.contains("open") || action.contains("view") ||
                action.contains("get") -> OperationType.READ
            action.contains("execute") || action.contains("run") || action.contains("shell") ||
                action.contains("start") || action.contains("install") -> OperationType.EXECUTE
            action.contains("click") || action.contains("tap") || action.contains("navigate") ||
                action.contains("swipe") -> OperationType.NAVIGATE
            action.contains("analyze") || action.contains("calculate") ||
                action.contains("convert") -> OperationType.ANALYZE
            action.contains("manage") || action.contains("config") || action.contains("modify") -> OperationType.MANAGE
            else -> OperationType.READ
        }

        return IntentAnalysis(
            intent = bestMatch,
            entities = entities,
            action = action,
            target = entities["path"] ?: entities["url"] ?: entities["package"] ?: "",
            operationType = opType
        )
    }

    suspend fun route(
        userInput: String,
        context: ExecutionContext = ExecutionContext()
    ): RoutingPlan {
        val analysis = analyzeIntent(userInput)
        val candidates = mutableListOf<ScoredTool>()

        if (analysis.intent.isNotBlank()) {
            val primary = registry.getByName(analysis.intent)
            if (primary != null) {
                candidates.add(ScoredTool(primary, 1.0, "Intent match: ${analysis.intent}"))
            }
        }

        val searchLimit = if (context.agentMode == AgentMode.BERSERK) 24 else 8
        val semanticResults = registry.search(userInput, searchLimit)
        for (result in semanticResults) {
            if (candidates.none { it.tool.name == result.tool.name }) {
                candidates.add(result)
            }
        }

        val filtered = if (context.agentMode == AgentMode.BERSERK) {
            candidates
        } else {
            candidates.filter { isToolAvailable(it.tool, context) }
        }
        val primary = filtered.firstOrNull()?.tool ?: candidates.firstOrNull()?.tool

        val suggestedChain = if (primary != null) {
            buildToolChain(primary, analysis, context)
        } else emptyList()

        val parallelHints = if (context.agentMode == AgentMode.BERSERK) {
            filtered.map { it.tool.name }.toSet()
        } else {
            identifyParallelTools(filtered.map { it.tool })
        }

        val alternatives = if (context.agentMode == AgentMode.BERSERK && filtered.size > 1) {
            val seen = mutableSetOf<String>()
            filtered.filter { seen.add(it.tool.category.id) }
        } else filtered

        val reasoning = buildReasoning(analysis, primary, filtered)
        val confidence = if (primary != null) {
            if (context.agentMode == AgentMode.BERSERK) 1.0
            else filtered.firstOrNull()?.score ?: 0.5
        } else 0.0

        return RoutingPlan(
            primaryTool = primary,
            alternatives = alternatives,
            suggestedChain = suggestedChain,
            parallelHints = parallelHints,
            reasoning = reasoning,
            confidence = confidence
        )
    }

    private fun isToolAvailable(tool: ToolSpec, context: ExecutionContext): Boolean {
        if (context.agentMode == AgentMode.BERSERK) return true

        if (tool.metadata.deprecated) return false
        if (tool.constraints.requiresNetwork && !context.hasNetwork) return false
        if (tool.constraints.requiresRoot && !context.hasRoot) return false
        if (tool.constraints.permissionLevel.ordinal > context.permissionLevel.ordinal) return false
        if (context.agentMode !in tool.modeConfig.allowedModes) return false
        return true
    }

    private fun buildToolChain(
        primary: ToolSpec,
        analysis: IntentAnalysis,
        context: ExecutionContext
    ): List<ToolSpec> {
        val chain = mutableListOf(primary)

        when (primary.name) {
            "apply_file" -> {
                listOf("read_file", "list_files").forEach { name ->
                    registry.getByName(name)?.let { chain.add(it) }
                }
            }
            "write_file" -> {
                if (analysis.entities.containsKey("path").not()) {
                    registry.getByName("list_files")?.let { chain.add(0, it) }
                }
            }
            "visit_web" -> {
                registry.getByName("download_file")?.let { chain.add(it) }
            }
            "query_memory" -> {
                registry.getByName("get_memory_by_title")?.let { chain.add(it) }
            }
            "device_info" -> {
                registry.getByName("battery_info")?.let { chain.add(it) }
                registry.getByName("network_info")?.let { chain.add(it) }
            }
            "ffmpeg_execute" -> {
                registry.getByName("ffmpeg_info")?.let { chain.add(0, it) }
            }
            "start_app" -> {
                if (analysis.entities.containsKey("package").not()) {
                    registry.getByName("list_installed_apps")?.let { chain.add(0, it) }
                }
            }
        }

        return chain.distinct()
    }

    private fun identifyParallelTools(tools: List<ToolSpec>): Set<String> {
        val parallelizable = mutableSetOf<String>()
        for (tool in tools) {
            if (tool.parallelSafe && tool.constraints.maxConcurrency > 1) {
                parallelizable.add(tool.name)
            }
        }
        return parallelizable
    }

    private fun buildReasoning(
        analysis: IntentAnalysis,
        primary: ToolSpec?,
        candidates: List<ScoredTool>
    ): String {
        val sb = StringBuilder()
        sb.append("Intent analysis: action='${analysis.action}', operation=${analysis.operationType}")
        if (analysis.entities.isNotEmpty()) {
            sb.append(", entities=${analysis.entities}")
        }
        if (primary != null) {
            sb.append(". Selected: ${primary.name} (confidence=%.2f)".format(candidates.firstOrNull()?.score ?: 0.0))
        } else {
            sb.append(". No direct match found")
        }
        return sb.toString()
    }
}
