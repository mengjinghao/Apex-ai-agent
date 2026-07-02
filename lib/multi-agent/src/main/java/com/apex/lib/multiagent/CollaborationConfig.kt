package com.apex.lib.multiagent

import kotlinx.coroutines.flow.SharedFlow

enum class CollaborationMode {
    PIPELINE,        // 顺序流水线：A → B → C
    DEBATE,          // 辩论模式：多 Agent 多轮交锋
    ADVERSARIAL,     // 对抗模式：Generator vs Discriminator
    PARALLEL_RACING, // 并行竞速：多 Agent 同时跑，取最优
    HIERARCHICAL     // 层级模式：Supervisor 分派 + Reviewer 检查
}

data class CollaborationConfig(
    val mode: CollaborationMode,
    val agentIds: List<String>,
    val initialPrompt: String,
    val maxRounds: Int = 10,
    val timeoutMs: Long = 60_000L
)
