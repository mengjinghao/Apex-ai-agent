package com.apex.agent.orchestration.memory

import com.apex.agent.core.multiagent.KnowledgeGraphManager
import com.apex.agent.core.multiagent.KnowledgeGraphManager.KnowledgeNode
import com.apex.agent.core.multiagent.KnowledgeGraphManager.KnowledgeEdge
import com.apex.agent.core.multiagent.KnowledgeGraphManager.NodeType
import com.apex.agent.core.multiagent.KnowledgeGraphManager.RelationType
import com.apex.agent.orchestration.memory.DecisionStatus
import com.apex.agent.orchestration.memory.ContextCategory

class SessionMemoryBridge(
    private val workingMemory: WorkingMemoryManager,
    private val knowledgeGraph: KnowledgeGraphManager? = null
) {

    fun consolidateSessionToKG(sessionId: String) {
        val session = workingMemory.getSession(sessionId) ?: return
        val kg = knowledgeGraph ?: return

        val sessionNodeId = "session_$sessionId"
        kg.addNode(
            KnowledgeNode(
                id = sessionNodeId,
                type = NodeType.SESSION,
                content = session.taskDescription,
                properties = mutableMapOf(
                    "taskId" to session.taskId,
                    "startTime" to session.startTime.toString(),
                    "agentCount" to session.agents.size.toString(),
                    "artifactCount" to session.artifacts.size.toString()
                ),
                confidence = 1.0f
            )
        )
        for (agent in session.agents) {
            val agentNodeId = "agent_${agent.agentId}"
            kg.addNode(
                KnowledgeNode(
                    id = agentNodeId,
                    type = NodeType.AGENT,
                    content = "${agent.agentName} (${agent.role})",
                    properties = mutableMapOf(
                        "name" to agent.agentName,
                        "role" to agent.role,
                        "messagesSent" to agent.messagesSent.toString(),
                        "messagesReceived" to agent.messagesReceived.toString()
                    ),
                    confidence = 1.0f
                )
            )
            kg.addEdge(
                KnowledgeEdge(
                    id = "edge_${sessionId}_${agent.agentId}_participated",
                    sourceId = agentNodeId,
                    targetId = sessionNodeId,
                    relationType = RelationType.PARTICIPATED_IN
                )
            )
        }
        for (decision in session.decisions.filter { it.status == DecisionStatus.AGREED }) {
            val decisionNodeId = "decision_${decision.id}"
            kg.addNode(
                KnowledgeNode(
                    id = decisionNodeId,
                    type = NodeType.FACT,
                    content = decision.description,
                    properties = mutableMapOf(
                        "proposedBy" to decision.proposedBy,
                        "agreedBy" to decision.agreedBy.joinToString(","),
                        "reasoning" to decision.reasoning.take(200),
                        "category" to "decision"
                    ),
                    confidence = 0.9f
                )
            )
            kg.addEdge(
                KnowledgeEdge(
                    id = "edge_dec_${decision.id}_session",
                    sourceId = decisionNodeId,
                    targetId = sessionNodeId,
                    relationType = RelationType.RESULTED_IN
                )
            )
        }
        for (context in session.sharedContext.filter { it.isActive && it.category == ContextCategory.INSIGHT }) {
            val insightNodeId = "insight_${sessionId}_${context.key}"
            kg.addNode(
                KnowledgeNode(
                    id = insightNodeId,
                    type = NodeType.CONCEPT,
                    content = context.value,
                    properties = mutableMapOf(
                        "source" to context.source,
                        "key" to context.key,
                        "confidence" to context.confidence.toString(),
                        "category" to "insight"
                    ),
                    confidence = context.confidence
                )
            )
            kg.addEdge(
                KnowledgeEdge(
                    id = "edge_insight_${context.key}_session",
                    sourceId = insightNodeId,
                    targetId = sessionNodeId,
                    relationType = RelationType.DERIVED_FROM
                )
            )
        }
        for (artifact in session.artifacts) {
            val artifactNodeId = "artifact_${artifact.id}"
            kg.addNode(
                KnowledgeNode(
                    id = artifactNodeId,
                    type = NodeType.TASK,
                    content = "${artifact.name}: ${artifact.content.take(200)}",
                    properties = mutableMapOf(
                        "name" to artifact.name,
                        "type" to artifact.type.name,
                        "createdBy" to artifact.createdBy,
                        "version" to artifact.version.toString(),
                        "category" to "artifact"
                    ),
                    confidence = 1.0f
                )
            )
            kg.addEdge(
                KnowledgeEdge(
                    id = "edge_artifact_${artifact.id}_session",
                    sourceId = artifactNodeId,
                    targetId = sessionNodeId,
                    relationType = RelationType.RESULTED_IN
                )
            )
        }
    }
        fun loadContextForNewSession(sessionId: String, agentId: String, agentRole: String): String {
        val kg = knowledgeGraph ?: return ""
        val agentNodeId = "agent_$agentId"
        val agentNode = kg.getNode(agentNodeId) ?: return ""
        val neighbors = kg.getNeighbors(agentNodeId, maxDepth = 2)
        val relevantSessions = neighbors.map { it.first }.filter { it.type == NodeType.SESSION }
        if (relevantSessions.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("Previous session context for $agentRole:")
        for (session in relevantSessions.take(3)) {
            sb.appendLine("- Previous task: ${session.content.take(100)}")
        val sessionEdges = kg.getNeighbors(session.id, maxDepth = 1)
        val decisions = sessionEdges.map { it.first }.filter { it.type == NodeType.FACT }
        if (decisions.isNotEmpty()) {
                sb.appendLine("  Relevant decisions:")
                decisions.take(3).forEach { sb.appendLine("  * ${it.content.take(80)}") }
            }
        }
        return sb.toString()
    }
        fun findRelevantKnowledge(query: String, limit: Int = 5): String {
        val kg = knowledgeGraph ?: return ""
        val results = kg.semanticSearch(query, limit)
        if (results.isEmpty()) return ""
        return results.joinToString("\n") { "  - ${it.node.content} (${"%.2f".format(it.similarity)})" }
    }
}
