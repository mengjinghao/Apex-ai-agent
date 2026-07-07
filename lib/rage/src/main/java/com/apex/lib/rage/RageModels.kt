package com.apex.lib.rage

import kotlinx.serialization.Serializable

/**
 * 狂暴模式核心数据模型。
 *
 * 包含：
 * - [RageTask] / [RageTaskStatus] — 任务实体与状态机
 * - [RageEvent] — 引擎生命周期事件流（sealed class）
 * - [RageModeConfig] — 引擎配置
 * - [RageMetrics] — 运行指标
 */

// ============================================================
// 任务状态机
// ============================================================

/**
 * 任务状态。
 *
 * 流转：PENDING → RUNNING → COMPLETED / FAILED / CANCELLED
 */
enum class RageTaskStatus {
    PENDING,     // 待执行
    RUNNING,     // 执行中
    COMPLETED,   // 已完成
    FAILED,      // 已失败
    CANCELLED    // 已取消
}

/**
 * 狂暴任务。
 *
 * @param id           任务 ID（Trace.newId 生成）
 * @param description  任务描述
 * @param preset       策略预设名（AGGRESSIVE / BALANCED / CONSERVATIVE / DEBUG）
 * @param status       当前状态
 * @param createdAt    创建时间戳
 * @param startedAt    开始执行时间戳
 * @param completedAt  完成时间戳
 * @param progress     进度 [0.0, 1.0]
 * @param result       执行结果（成功时）
 * @param errorMessage 错误信息（失败时）
 * @param agentInvocations  Agent 调用次数
 * @param retryCount   重试次数
 * @param durationMs   总耗时（毫秒）
 */
@Serializable
data class RageTask(
    val id: String,
    val description: String,
    val preset: String = "BALANCED",
    val status: RageTaskStatus = RageTaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val progress: Float = 0.0f,
    val result: String? = null,
    val errorMessage: String? = null,
    val agentInvocations: Int = 0,
    val retryCount: Int = 0,
    val durationMs: Long = 0L
)

// ============================================================
// 配置与指标
// ============================================================

/**
 * 狂暴模式引擎配置。
 *
 * 与 [RagePresets] 配合使用：预设生成配置，引擎应用配置。
 */
@Serializable
data class RageModeConfig(
    val maxConcurrency: Int = 4,
    val defaultTimeoutMs: Long = 60_000L,
    val maxRetries: Int = 3,
    val enableAutoExpand: Boolean = true,
    val enableGitBranching: Boolean = true,
    val enableSandboxExec: Boolean = true,
    val enableGithubSearch: Boolean = false,
    val enableCodeRag: Boolean = true
)

/**
 * 狂暴模式运行指标快照。
 */
@Serializable
data class RageMetrics(
    val totalTasks: Long = 0L,
    val successfulTasks: Long = 0L,
    val failedTasks: Long = 0L,
    val cancelledTasks: Long = 0L,
    val averageExecutionTimeMs: Double = 0.0,
    val successRate: Double = 0.0,
    val currentConcurrency: Int = 0,
    val peakConcurrency: Int = 0
)

// ============================================================
// 事件流
// ============================================================

/**
 * 狂暴引擎事件流 — 任务生命周期 + 技能调用 + Agent 步骤 + 黑板更新。
 *
 * 通过 [RageEngine.events] / [RageTaskStore.taskEvents] 暴露为 SharedFlow。
 */
sealed class RageEvent {
    abstract val taskId: String?

    /** 任务已启动。 */
    data class TaskStarted(val id: String, val description: String) : RageEvent() {
        override val taskId: String? = id
    }

    /** 任务进度更新。 */
    data class TaskProgress(val id: String, val progress: Float, val message: String?) : RageEvent() {
        override val taskId: String? = id
    }

    /** 任务已完成。 */
    data class TaskCompleted(val id: String, val success: Boolean, val durationMs: Long) : RageEvent() {
        override val taskId: String? = id
    }

    /** 任务失败。 */
    data class TaskFailed(val id: String, val error: String) : RageEvent() {
        override val taskId: String? = id
    }

    /** 任务已取消。 */
    data class TaskCancelled(val id: String, val reason: String?) : RageEvent() {
        override val taskId: String? = id
    }

    /** 技能被调用。 */
    data class SkillInvoked(val id: String, val skillId: String, val skillName: String) : RageEvent() {
        override val taskId: String? = id
    }

    /** Agent 步骤完成。 */
    data class AgentStepEvent(
        val id: String,
        val agentId: String,
        val agentName: String,
        val action: String,
        val success: Boolean
    ) : RageEvent() {
        override val taskId: String? = id
    }

    /** 黑板已更新。 */
    data class BlackboardUpdated(val id: String, val entries: Map<String, String>) : RageEvent() {
        override val taskId: String? = id
    }
}
