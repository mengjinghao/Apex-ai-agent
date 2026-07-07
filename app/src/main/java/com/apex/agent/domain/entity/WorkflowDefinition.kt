package com.apex.agent.domain.entity

data class WorkflowDefinition(
    val id: String,
    val name: String,
    val nodes: List<Any>,
    val edges: List<Any>,
    val variables: Map<String, Any>
)
