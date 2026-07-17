package com.apex.core.model

/**
 * 记忆条目 — AI 记忆系统的基础单元。
 */
data class Memory(
    val id: String,
    val content: String,
    val category: String = "general",
    val timestamp: Long = System.currentTimeMillis(),
    val importance: Float = 0.5f,  // 0.0 ~ 1.0
    val tags: List<String> = emptyList()
)
