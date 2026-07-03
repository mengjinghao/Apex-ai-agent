package com.apex.lib.workingfiles.agent

import kotlinx.serialization.Serializable

/**
 * Agent 执行步骤 — Agent 在一次会话中执行的一个原子操作。
 *
 * **设计参考**：
 *   - Cline (VSCode AI 插件)：每个工具调用记录为一步，含 say/text/tool/ask 等类型
 *   - Aider：每个 commit 对应一个步骤，含 message + files changed
 *   - OpenAI Operator：执行步骤含 thought + action + observation
 *
 * **每个步骤关联**：
 *   - 触发的文件快照 ID 列表（可能影响多个文件）
 *   - 工具调用参数 + 返回值
 *   - 思考过程（thought，可选）
 *   - 持续时间
 *
 * @property id 步骤 ID
 * @property sessionId 会话 ID
 * @property agentId 执行此步骤的 Agent ID
 * @property agentName Agent 显示名
 * @property type 步骤类型
 * @property order 在会话中的顺序（从 0 开始）
 * @property timestamp 开始时间
 * @property durationMs 持续时间（毫秒，0 表示未完成）
 * @property title 标题（一句话）
 * @property description 详细描述
 * @property thought Agent 思考内容（可选）
 * @property action 动作内容（如命令、API 调用）
 * @property result 执行结果
 * @property isSuccess 是否成功
 * @property errorMessage 错误信息（失败时）
 * @property affectedFiles 受影响的文件路径列表
 * @property snapshotIds 关联的快照 ID 列表（每个文件一个）
 * @property metadata 附加元数据
 */
@Serializable
data class AgentStep(
    val id: String,
    val sessionId: String,
    val agentId: String,
    val agentName: String,
    val type: AgentStepType,
    val order: Int,
    val timestamp: Long,
    val durationMs: Long = 0,
    val title: String,
    val description: String = "",
    val thought: String? = null,
    val action: String? = null,
    val result: String? = null,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
    val affectedFiles: List<String> = emptyList(),
    val snapshotIds: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Agent 步骤类型。
 *
 * 参考 Cline 的 step 类型 + ReAct 模式。
 */
@Serializable
enum class AgentStepType(val displayName: String, val iconHint: String) {
    THOUGHT("思考", "thought"),
    ACTION("执行", "action"),
    TOOL_CALL("工具调用", "tool"),
    FILE_READ("读取文件", "file_read"),
    FILE_WRITE("写入文件", "file_write"),
    FILE_EDIT("编辑文件", "file_edit"),
    FILE_DELETE("删除文件", "file_delete"),
    COMMAND("执行命令", "command"),
    LLM_CALL("LLM 调用", "llm"),
    SEARCH("搜索", "search"),
    WEB("网络请求", "web"),
    OBSERVATION("观察", "observation"),
    REFLECTION("反思", "reflection"),
    DECISION("决策", "decision"),
    ERROR("错误", "error"),
    CHECKPOINT("检查点", "checkpoint"),
    ROLLBACK("回退", "rollback"),
    USER_INPUT("用户输入", "user"),
    CUSTOM("自定义", "custom")
}

/**
 * Agent 会话 — 一次完整的 Agent 执行流程。
 *
 * @property id 会话 ID
 * @property agentId Agent ID（可能是 multi-agent 的 supervisor）
 * @property agentName Agent 显示名
 * @property startTime 开始时间
 * @property endTime 结束时间（0 表示未结束）
 * @property taskDescription 任务描述
 * @property mode Agent 模式（NORMAL / MULTI_AGENT / BURST）
 * @property stepCount 步骤数
 * @property fileCount 受影响文件数
 * @property status 会话状态
 * @property finalResult 最终结果（成功时）
 */
@Serializable
data class AgentSession(
    val id: String,
    val agentId: String,
    val agentName: String,
    val startTime: Long,
    val endTime: Long = 0,
    val taskDescription: String,
    val mode: AgentMode,
    val stepCount: Int = 0,
    val fileCount: Int = 0,
    val status: AgentSessionStatus = AgentSessionStatus.RUNNING,
    val finalResult: String? = null
) {
    val isFinished: Boolean get() = status == AgentSessionStatus.COMPLETED || status == AgentSessionStatus.FAILED
    val durationMs: Long get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime
}

/** Agent 模式。 */
@Serializable
enum class AgentMode(val displayName: String) {
    NORMAL("普通 Agent"),
    MULTI_AGENT("多 Agent 协作"),
    BURST("狂暴模式")
}

/** 会话状态。 */
@Serializable
enum class AgentSessionStatus(val displayName: String) {
    RUNNING("进行中"),
    COMPLETED("已完成"),
    FAILED("已失败"),
    CANCELLED("已取消"),
    PAUSED("已暂停")
}

/**
 * Agent 执行流程 — 一个会话的所有步骤。
 */
@Serializable
data class AgentFlow(
    val session: AgentSession,
    val steps: List<AgentStep>
) {
    /** 总文件变更次数。 */
    val totalFileChanges: Int get() = steps.sumOf { it.affectedFiles.size }

    /** 总快照数。 */
    val totalSnapshots: Int get() = steps.sumOf { it.snapshotIds.size }

    /** 是否包含错误步骤。 */
    val hasErrors: Boolean get() = steps.any { !it.isSuccess }

    /** 错误步骤数。 */
    val errorCount: Int get() = steps.count { !it.isSuccess }

    /** 总执行时长（毫秒）。 */
    val totalDurationMs: Long get() = steps.sumOf { it.durationMs }
}
