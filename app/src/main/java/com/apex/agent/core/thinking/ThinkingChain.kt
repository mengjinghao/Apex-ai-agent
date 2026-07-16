package com.apex.agent.core.thinking

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
sealed class ThoughtNode {
    abstract val id: String
    abstract val type: ThoughtType
    abstract val content: String
    abstract val timestamp: Long
}

enum class ThoughtType {
    OBSERVATION,
    QUESTION,
    INFERENCE,
    DECISION,
    ACTION,
    RESULT,
    SUMMARY,
    UNKNOWN
}

@Serializable
data class ObservationNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val source: String = ""
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.OBSERVATION
}

@Serializable
data class QuestionNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val context: String = ""
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.QUESTION
}

@Serializable
data class InferenceNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val evidence: List<String> = emptyList(),
    val confidence: Float = 0.8f
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.INFERENCE
}

@Serializable
data class DecisionNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val options: List<String> = emptyList(),
    val chosenOption: Int = 0
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.DECISION
}

@Serializable
data class ActionNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val toolName: String = "",
    val parameters: Map<String, String> = emptyMap()
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.ACTION
}

@Serializable
data class ResultNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true,
    val actionId: String = ""
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.RESULT
}

@Serializable
data class SummaryNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val keyPoints: List<String> = emptyList()
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.SUMMARY
}

@Serializable
data class UnknownNode(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val timestamp: Long = System.currentTimeMillis()
) : ThoughtNode() {
    override val type: ThoughtType = ThoughtType.UNKNOWN
}

@Serializable
    data class Edge(
        val from: String,
        val to: String,
        val label: String = ""
    )

    fun addNode(node: ThoughtNode) {
        nodes.add(node)
    }

    fun addEdge(fromId: String, toId: String, label: String = "") {
        edges.add(Edge(fromId, toId, label))
    }

    fun connectNodes(from: ThoughtNode, to: ThoughtNode, label: String = "") {
        addEdge(from.id, to.id, label)
    }

    fun markComplete() {
        endTime = System.currentTimeMillis()
    }

    fun getDuration(): Long {
        return (endTime ?: System.currentTimeMillis()) - startTime
    }

    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromJson(json: String): ThinkingChain {
            return Json.decodeFromString(json)
        }

        fun parseFromXml(thinkXml: String): ThinkingChain {
            val chain = ThinkingChain()
            val cleanContent = thinkXml.replace("<think>", "").replace("</think>", "")
                .replace("<thinking>", "").replace("</thinking>", "").trim()

            val lines = cleanContent.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var lastNode: ThoughtNode? = null

            for (line in lines) {
                val node = parseLineToNode(line)
                if (node != null) {
                    chain.addNode(node)
                    if (lastNode != null) {
                        chain.connectNodes(lastNode, node)
                    }
                    lastNode = node
                }
            }

            return chain
        }

        private fun parseLineToNode(line: String): ThoughtNode? {
            return when {
                line.startsWith("- 观察:") || line.startsWith("观察:") -> {
                    ObservationNode(content = line.replace("- 观察:", "").replace("观察:", "").trim())
                }
                line.startsWith("- 问题:") || line.startsWith("问题:") -> {
                    QuestionNode(content = line.replace("- 问题:", "").replace("问题:", "").trim())
                }
                line.startsWith("- 推断:") || line.startsWith("推断:") || line.startsWith("推理:") -> {
                    InferenceNode(content = line.replace("- 推断:", "").replace("推断:", "")
                        .replace("- 推理:", "").replace("推理:", "").trim())
                }
                line.startsWith("- 决定:") || line.startsWith("决定:") || line.startsWith("选择:") -> {
                    DecisionNode(content = line.replace("- 决定:", "").replace("决定:", "")
                        .replace("- 选择:", "").replace("选择:", "").trim())
                }
                line.startsWith("- 行动:") || line.startsWith("行动:") || line.startsWith("执行:") -> {
                    ActionNode(content = line.replace("- 行动:", "").replace("行动:", "")
                        .replace("- 执行:", "").replace("执行:", "").trim())
                }
                line.startsWith("- 结果:") || line.startsWith("结果:") -> {
                    val content = line.replace("- 结果:", "").replace("结果:", "").trim()
                    ResultNode(content = content, success = !content.contains("失败", ignoreCase = true))
                }
                line.startsWith("- 总结:") || line.startsWith("总结:") -> {
                    SummaryNode(content = line.replace("- 总结:", "").replace("总结:", "").trim())
                }
                else -> {
                    InferenceNode(content = line)
                }
            }
        }
    }
}

enum class VisualizationMode {
    TEXT_ONLY,
    FLOW_CHART,
    HYBRID
}