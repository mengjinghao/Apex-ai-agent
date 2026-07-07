package com.apex.agent.orchestration.agent

/** Agent 任务记录 */
data class AgentTaskRecord(
    val task: String,
    var status: String,
    val timestamp: Long,
    var result: String = ""
)
