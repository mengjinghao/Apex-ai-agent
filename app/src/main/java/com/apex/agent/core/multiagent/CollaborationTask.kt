package com.apex.agent.core.multiagent

class CollaborationTask(
    val id: String,
    val name: String,
    val description: String = "",
    val agents: List<Agent> = emptyList(),
    val status: Status = Status.PENDING,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val lastUpdateTime: Long = System.currentTimeMillis(),
    val collaborationMode: CollaborationMode = CollaborationMode.SUPERVISOR_EXECUTION,
    val rules: CollaborationRules = CollaborationRules()
) {
    enum class Status {
        PENDING,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED
    }

    enum class CollaborationMode {
        SUPERVISOR_EXECUTION, // 主管-执行模式
        SERIAL_PIPELINE, // 串行流水线模�?       PARALLEL_EXECUTION, // 并行执行模式
        DEBATE_REVIEW, // 辩论评审模式
        FREE_DIALOG // 自由对话模式
    }
}

class CollaborationRules(
    val timeout: Int = 3600, // 任务超时时间（秒�?   val summaryMethod: String = "consensus", // 结果汇总方�?   val terminationRule: String = "all_completed", // 终止规则
    val retryCount: Int = 3, // 重试次数
    val maxAgents: Int = 10 // 最，Agent 数量
)
