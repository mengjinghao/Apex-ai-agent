package com.apex.core.tools.workflow

import com.apex.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AIGenerator {

    suspend fun generateWorkflow(description: String): AIGenerateResult = withContext(Dispatchers.IO) {
        val workflow = parseDescriptionToWorkflow(description)
        AIGenerateResult(
            success = true,
            workflow = workflow,
            confidence = calculateConfidence(description, workflow),
            suggestions = generateSuggestions(workflow)
        )
    }

    private fun parseDescriptionToWorkflow(description: String): Workflow {
        val nodes = mutableListOf<WorkflowNode>()
        val connections = mutableListOf<WorkflowNodeConnection>()

        val triggerNode = createTriggerNode(description)
        nodes.add(triggerNode)

        val actionNodes = createActionNodes(description)
        nodes.addAll(actionNodes)

        var previousNodeId = triggerNode.id
        actionNodes.forEach { node ->
            connections.add(
                WorkflowNodeConnection(
                    sourceNodeId = previousNodeId,
                    targetNodeId = node.id
                )
            )
            previousNodeId = node.id
        }

        return Workflow(
            id = java.util.UUID.randomUUID().toString(),
            name = extractWorkflowName(description),
            description = description,
            nodes = nodes,
            connections = connections
        )
    }

    private fun createTriggerNode(description: String): TriggerNode {
        val triggerType = when {
            description.contains("每天") || description.contains("定时") -> "schedule"
            description.contains("收到") || description.contains("�?) -> "intent"
            description.contains("截图") -> "screenshot"
            else -> "manual"
        }

        return TriggerNode(
            id = java.util.UUID.randomUUID().toString(),
            name = "触发�?,
            description = "自动生成的触发器",
            triggerType = triggerType,
            triggerConfig = extractTriggerConfig(description)
        )
    }

    private fun createActionNodes(description: String): List<ExecuteNode> {
        val actions = mutableListOf<ExecuteNode>()

        when {
            description.contains("天气") -> {
                actions.add(
                    ExecuteNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "获取天气",
                        description = "获取当前位置天气",
                        actionType = "http_request",
                        actionConfig = mapOf(
                            "url" to ParameterValue.StaticValue("https://api.weather.example.com")
                        )
                    )
                )
            }
            description.contains("通知") || description.contains("发�?) -> {
                actions.add(
                    ExecuteNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "发送通知",
                        description = "发送系统通知",
                        actionType = "send_notification"
                    )
                )
            }
            description.contains("截图") || description.contains("OCR") -> {
                actions.add(
                    ExecuteNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "截图",
                        description = "屏幕截图",
                        actionType = "capture_screenshot"
                    )
                )
                actions.add(
                    ExecuteNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "OCR识别",
                        description = "提取文字",
                        actionType = "ocr_recognize"
                    )
                )
            }
            description.contains("保存") || description.contains("备忘�?) -> {
                actions.add(
                    ExecuteNode(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "保存备忘�?,
                        description = "保存到备忘录",
                        actionType = "create_memory"
                    )
                )
            }
        }

        return actions
    }

    private fun extractTriggerConfig(description: String): Map<String, String> {
        val config = mutableMapOf<String, String>()

        val timeRegex = Regex("(\\d{1,2})点|(\\d{1,2}):(\\d{2})")
        timeRegex.find(description)?.let { match ->
            config["time"] = match.value
        }

        if (description.contains("每天")) {
            config["frequency"] = "daily"
        }

        return config
    }

    private fun extractWorkflowName(description: String): String {
        return description.take(20).let {
            if (it.length < description.length) "${it}..." else it
        }
    }

    private fun calculateConfidence(description: String, workflow: Workflow): Float {
        var confidence = 0.5f

        if (workflow.nodes.size >= 2) confidence += 0.2f
        if (workflow.connections.isNotEmpty()) confidence += 0.15f

        val keywords = listOf("获取", "发�?, "保存", "检�?, "通知")
        keywords.forEach { keyword ->
            if (description.contains(keyword)) confidence += 0.03f
        }

        return confidence.coerceAtMost(1.0f)
    }

    private fun generateSuggestions(workflow: Workflow): List<String> {
        val suggestions = mutableListOf<String>()

        if (workflow.nodes.size == 1) {
            suggestions.add("建议添加更多操作步骤以实现更复杂的工作流")
        }

        if (!workflow.connections.any { it.condition != null }) {
            suggestions.add("可以考虑添加条件判断来实现更智能的自动化")
        }

        return suggestions
    }

    suspend fun suggestImprovements(workflow: Workflow): List<String> = withContext(Dispatchers.IO) {
        val suggestions = mutableListOf<String>()

        val nodeTypes = workflow.nodes.map { it.type }.distinct()
        if (nodeTypes.size < 2) {
            suggestions.add("工作流较为简单，可以考虑添加更多节点")
        }

        val hasErrorHandling = workflow.nodes.any {
            it is ExecuteNode && it.actionConfig.containsKey("on_error")
        }
        if (!hasErrorHandling) {
            suggestions.add("建议添加错误处理机制以提高稳定�?)
        }

        suggestions
    }
}

data class AIGenerateResult(
    val success: Boolean,
    val workflow: Workflow? = null,
    val error: String? = null,
    val confidence: Float = 0f,
    val suggestions: List<String> = emptyList()
)