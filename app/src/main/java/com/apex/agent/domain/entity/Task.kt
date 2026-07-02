package com.apex.agent.domain.entity

data class Task(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val collaborationMode: String,
    val agentIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)
