package com.apex.agent.infrastructure.knowledgegraph

data class KnowledgeNode(
    val id: String,
    val label: String,
    val properties: Map<String, String> = emptyMap()
)

data class KnowledgeEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val relation: String,
    val properties: Map<String, String> = emptyMap()
)

data class KnowledgeGraph(
    val nodes: List<KnowledgeNode> = emptyList(),
    val edges: List<KnowledgeEdge> = emptyList()
)
