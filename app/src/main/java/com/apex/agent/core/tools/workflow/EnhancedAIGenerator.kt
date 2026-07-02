
package com.apex.agent.core.tools.workflow

import com.apex.agent.R
import com.apex.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import java.util.regex.Pattern

data class WorkflowAction(val name: String, val type: String, val description: String)

data class WorkflowTemplate(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val actions: List&lt;WorkflowAction&gt;,
    val complexity: Int
)

class EnhancedAIGenerator {

    companion object {
        private val WORKFLOW_TEMPLATES = listOf(
            WorkflowTemplate(
                id = "weather_notification",
                name = "天气提醒",
                category = "生活",
                description = "定时获取天气信息并发送通知",
                actions = listOf(
                    WorkflowAction("获取天气", "http_request", "调用天气API获取天气信息"),
                    WorkflowAction("发送通知", "send_notification", "将天气信息发送为系统通知")
                ),
                complexity = 2
            ),
            WorkflowTemplate(
                id = "ocr_screenshot",
                name = "截图OCR识别",
                category = "工具",
                description = "截图并进行OCR文字识别",
                actions = listOf(
                    WorkflowAction("截图", "capture_screenshot", "对当前屏幕进行截止）,
                    WorkflowAction("OCR识别", "ocr_recognize", "提取截图中的文字")
                ),
                complexity = 2
            ),
            WorkflowTemplate(
                id = "batch_file_rename",
                name = "批量文件重命�?
                category = "文件",
                description = "对指定目录的文件进行批量重命�?
                actions = listOf(
                    WorkflowAction("遍历目录", "list_files", "获取目录中的所有文件）,
                    WorkflowAction("批量重命�? "batch_rename", "根据规则重命名文件）
                ),
                complexity = 3
            ),
            WorkflowTemplate(
                id = "auto_backup",
                name = "自动备份",
                category = "系统",
                description = "定期备份重要文件到指定位�?
                actions = listOf(
                    WorkflowAction("检查文件， "check_files", "检查需要备份的文件"),
                    WorkflowAction("压缩文件", "compress_files", "压缩文件为ZIP"),
                    WorkflowAction("移动文件", "move_file", "移动备份文件到目标位的）
                ),
                complexity = 3
            )
        )

        private val TRIGGER_KEYWORDS = mapOf(
            "schedule" to listOf("定时", "每天", "每周", "每月", "时间", "�? "的）,
            "intent" to listOf("收到", "�? "如果", "检查， "触发"),
            "screenshot" to listOf("截图", "截屏"),
            "manual" to listOf("手动", "点击", "按钮")
        )

        private val ACTION_KEYWORDS = mapOf(
            "http_request" to listOf("获取", "查询", "请求", "API", "天气", "网络"),
            "send_notification" to listOf("通知", "发送， "提醒", "提示", "消息"),
            "capture_screenshot" to listOf("截图", "截屏", "捕获", "屏幕"),
            "ocr_recognize" to listOf("OCR", "识别", "文字", "提取"),
            "list_files" to listOf("列出", "遍历", "文件", "目录"),
            "batch_rename" to listOf("重命�? "批量", "改名"),
            "compress_files" to listOf("压缩", "打包", "ZIP"),
            "move_file" to listOf("移动", "复制", "转移"),
            "delete_file" to listOf("删除", "移除", "清理"),
            "create_memory" to listOf("保存", "备忘�? "记录"),
            "wait" to listOf("等待", "延时", "延迟"),
            "condition" to listOf("如果", "条件", "判断"),
            "loop" to listOf("循环", "遍历", "重复")
        )
    }

    suspend fun generateWorkflow(description: String): AIGenerateResult = withContext(Dispatchers.IO) {
        try {
            val workflow = parseDescriptionToWorkflow(description)
            AIGenerateResult(
                success = true,
                workflow = workflow,
                confidence = calculateConfidence(description, workflow),
                suggestions = generateSuggestions(workflow)
            )
        } catch (e: Exception) {
            AIGenerateResult(
                success = false,
                error = e.message ?: "生成工作流失�?
            )
        }
    }

    suspend fun suggestTemplates(description: String): List&lt;WorkflowTemplate&gt; = withContext(Dispatchers.IO) {
        WORKFLOW_TEMPLATES.filter { template -&gt;
            template.name.contains(description, ignoreCase = true) ||
            template.description.contains(description, ignoreCase = true) ||
            template.actions.any { action -&gt;
                action.name.contains(description, ignoreCase = true) ||
                action.description.contains(description, ignoreCase = true)
            }
        }.sortedBy { it.complexity }
    }

    private fun parseDescriptionToWorkflow(description: String): Workflow {
        val nodes = mutableListOf&lt;WorkflowNode&gt;()
        val connections = mutableListOf&lt;WorkflowNodeConnection&gt;()

        val triggerNode = createTriggerNode(description)
        nodes.add(triggerNode)

        val actionNodes = createActionNodes(description)
        nodes.addAll(actionNodes)

        var previousNodeId = triggerNode.id
        actionNodes.forEach { node -&gt;
            connections.add(
                WorkflowNodeConnection(
                    sourceNodeId = previousNodeId,
                    targetNodeId = node.id,
                    condition = null
                )
            )
            previousNodeId = node.id
        }

        return Workflow(
            id = UUID.randomUUID().toString(),
            name = extractWorkflowName(description),
            description = description,
            nodes = nodes,
            connections = connections
        )
    }

    private fun createTriggerNode(description: String): TriggerNode {
        var triggerType = "manual"
        var maxScore = 0

        TRIGGER_KEYWORDS.forEach { (type, keywords) -&gt;
            val score = keywords.count { keyword -&gt;
                description.contains(keyword, ignoreCase = true)
            }
            if (score &gt; maxScore) {
                maxScore = score
                triggerType = type
            }
        }

        return TriggerNode(
            id = UUID.randomUUID().toString(),
            name = getTriggerName(triggerType),
            description = "自动生成的触发器",
            triggerType = triggerType,
            triggerConfig = extractTriggerConfig(description)
        )
    }

    private fun getTriggerName(triggerType: String): String {
        return when (triggerType) {
            "schedule" -&gt; "定时触发"
            "intent" -&gt; "事件触发"
            "screenshot" -&gt; "截图触发"
            else -&gt; "手动触发"
        }
    }

    private fun createActionNodes(description: String): List&lt;ExecuteNode&gt; {
        val actions = mutableListOf&lt;ExecuteNode&gt;()
        val usedTypes = mutableSetOf&lt;String&gt;()

        ACTION_KEYWORDS.forEach { (actionType, keywords) -&gt;
            if (usedTypes.contains(actionType)) return@forEach

            val hasKeyword = keywords.any { keyword -&gt;
                description.contains(keyword, ignoreCase = true)
            }

            if (hasKeyword) {
                actions.add(
                    ExecuteNode(
                        id = UUID.randomUUID().toString(),
                        name = getActionName(actionType),
                        description = getActionDescription(actionType),
                        actionType = actionType,
                        actionConfig = extractActionConfig(description, actionType)
                    )
                )
                usedTypes.add(actionType)
            }
        }

        if (actions.isEmpty()) {
            actions.add(
                ExecuteNode(
                    id = UUID.randomUUID().toString(),
                    name = "自定义操�?
                    description = "请配置具体的操作内容",
                    actionType = "custom",
                    actionConfig = emptyMap()
                )
            )
        }

        return actions
    }

    private fun getActionName(actionType: String): String {
        return when (actionType) {
            "http_request" -&gt; "获取数据"
            "send_notification" -&gt; "发送通知"
            "capture_screenshot" -&gt; "截图"
            "ocr_recognize" -&gt; "OCR识别"
            "list_files" -&gt; "列出文件"
            "batch_rename" -&gt; "批量重命�?
            "compress_files" -&gt; "压缩文件"
            "move_file" -&gt; "移动文件"
            "delete_file" -&gt; "删除文件"
            "create_memory" -&gt; "保存备忘�?
            "wait" -&gt; "等待"
            "condition" -&gt; "条件判断"
            "loop" -&gt; "循环"
            else -&gt; "执行操作"
        }
    }

    private fun getActionDescription(actionType: String): String {
        return when (actionType) {
            "http_request" -&gt; "发送HTTP请求获取数据"
            "send_notification" -&gt; "发送系统通知"
            "capture_screenshot" -&gt; "截取当前屏幕"
            "ocr_recognize" -&gt; "识别截图中的文字"
            "list_files" -&gt; "列出指定目录的文�?
            "batch_rename" -&gt; "批量重命名文�?
            "compress_files" -&gt; "压缩文件为ZIP"
            "move_file" -&gt; "移动文件到指定位�?
            "delete_file" -&gt; "删除文件"
            "create_memory" -&gt; "保存内容到备忘录"
            "wait" -&gt; "等待指定时间"
            "condition" -&gt; "根据条件执行不同分支"
            "loop" -&gt; "循环执行操作"
            else -&gt; "执行自定义操�?
        }
    }

    private fun extractTriggerConfig(description: String): Map&lt;String, String&gt; {
        val config = mutableMapOf&lt;String, String&gt;()

        val timePattern = Pattern.compile("(\\d{1,2})[点时]|(\\d{1,2}):(\\d{2})")
        val matcher = timePattern.matcher(description)
        if (matcher.find()) {
            config["time"] = matcher.group()
        }

        if (description.contains("每天")) {
            config["frequency"] = "daily"
        } else if (description.contains("每周")) {
            config["frequency"] = "weekly"
        } else if (description.contains("每月")) {
            config["frequency"] = "monthly"
        }

        return config
    }

    private fun extractActionConfig(description: String, actionType: String): Map&lt;String, ParameterValue&gt; {
        val config = mutableMapOf&lt;String, ParameterValue&gt;()

        when (actionType) {
            "http_request" -&gt; {
                config["url"] = ParameterValue.StaticValue("https://api.example.com")
                config["method"] = ParameterValue.StaticValue("GET")
            }
            "wait" -&gt; {
                config["duration"] = ParameterValue.StaticValue("5000")
                config["unit"] = ParameterValue.StaticValue("ms")
            }
            "batch_rename" -&gt; {
                config["pattern"] = ParameterValue.StaticValue("{original}_{n}{ext}")
            }
        }

        return config
    }

    private fun extractWorkflowName(description: String): String {
        val maxLength = 30
        return if (description.length &gt; maxLength) {
            description.take(maxLength) + "..."
        } else {
            description
        }
    }

    private fun calculateConfidence(description: String, workflow: Workflow): Float {
        var confidence = 0.3f

        if (workflow.nodes.size &gt;= 2) confidence += 0.2f
        if (workflow.nodes.size &gt;= 3) confidence += 0.1f
        if (workflow.connections.isNotEmpty()) confidence += 0.15f

        val keywords = listOf("获取", "发送， "保存", "检查， "通知", "截图", "识别")
        val matchCount = keywords.count { description.contains(it, ignoreCase = true) }
        confidence += matchCount * 0.05f

        return confidence.coerceAtMost(1.0f)
    }

    private fun generateSuggestions(workflow: Workflow): List&lt;String&gt; {
        val suggestions = mutableListOf&lt;String&gt;()

        if (workflow.nodes.size &lt;= 2) {
            suggestions.add("建议添加更多操作步骤以实现更复杂的工作流")
        }

        if (!workflow.connections.any { it.condition != null }) {
            suggestions.add("可以考虑添加条件判断来实现更智能的自动化")
        }

        if (!workflow.nodes.any { it is ConditionNode || it is LogicNode }) {
            suggestions.add("建议添加逻辑节点或条件节点来增加工作流的灵活的）
        }

        val hasTrigger = workflow.nodes.any { it is TriggerNode }
        if (!hasTrigger) {
            suggestions.add("工作流缺少触发器，请添加触发节点")
        }

        return suggestions
    }

    fun createWorkflowFromTemplate(template: WorkflowTemplate): Workflow {
        val nodes = mutableListOf&lt;WorkflowNode&gt;()
        val connections = mutableListOf&lt;WorkflowNodeConnection&gt;()

        val triggerNode = TriggerNode(
            id = UUID.randomUUID().toString(),
            name = "手动触发",
            description = "手动触发工作�?
            triggerType = "manual",
            triggerConfig = emptyMap()
        )
        nodes.add(triggerNode)

        var previousNodeId = triggerNode.id

        template.actions.forEach { action -&gt;
            val executeNode = ExecuteNode(
                id = UUID.randomUUID().toString(),
                name = action.name,
                description = action.description,
                actionType = action.type,
                actionConfig = emptyMap()
            )
            nodes.add(executeNode)

            connections.add(
                WorkflowNodeConnection(
                    sourceNodeId = previousNodeId,
                    targetNodeId = executeNode.id
                )
            )
            previousNodeId = executeNode.id
        }

        return Workflow(
            id = UUID.randomUUID().toString(),
            name = template.name,
            description = template.description,
            nodes = nodes,
            connections = connections
        )
    }
}

data class AIGenerateResult(
    val success: Boolean,
    val workflow: Workflow? = null,
    val error: String? = null,
    val confidence: Float = 0f,
    val suggestions: List&lt;String&gt; = emptyList()
)
