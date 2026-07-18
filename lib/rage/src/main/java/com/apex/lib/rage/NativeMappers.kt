@file:Suppress("unused")

package com.apex.lib.rage

import com.apex.rage.nativelib.NativeEvent
import com.apex.rage.nativelib.NativeExecutionResult
import com.apex.rage.nativelib.NativeMetrics
import com.apex.rage.nativelib.NativeRageConfig
import com.apex.rage.nativelib.NativeTask

/**
 * Kotlin ↔ Native 类型映射 —— 把 [RageModels] / [RageArchitectTypes] 中的数据类
 * 与 [:rage-jni] 的 `Native*` 数据类(package `com.apex.rage.nativelib`)相互转换。
 *
 * **行为契约**:这些 mapper 是纯函数,不接触 [RageTaskStore] / 不发事件。
 * 由 [RageEngine] 在调用 [com.apex.rage.nativelib.RageNativeBridge] 前后调用。
 *
 * ────────────────────────────────────────────────────────────────────
 * Native 数据类 schema 契约(由 ARCH-2 在 :rage-jni 中实现,字段名须对齐):
 * ────────────────────────────────────────────────────────────────────
 *
 *   package com.apex.rage.nativelib
 *
 *   @Serializable
 *   data class NativeTask(
 *       val id: String,
 *       val description: String,
 *       val preset: String
 *   )
 *
 *   @Serializable
 *   data class NativeRageConfig(
 *       val maxConcurrency: Int,
 *       val defaultTimeoutMs: Long,
 *       val maxRetries: Int,
 *       val enableAutoExpand: Boolean,
 *       val enableGitBranching: Boolean,
 *       val enableSandboxExec: Boolean,
 *       val enableGithubSearch: Boolean,
 *       val enableCodeRag: Boolean
 *   )
 *
 *   @Serializable
 *   data class NativeEvent(
 *       val type: String,        // TASK_STARTED / TASK_PROGRESS / TASK_COMPLETED /
 *                                // TASK_FAILED / TASK_CANCELLED / SKILL_INVOKED /
 *                                // AGENT_STEP / BLACKBOARD_UPDATED
 *       val taskId: String? = null,
 *       val progress: Float? = null,
 *       val success: Boolean? = null,
 *       val durationMs: Long? = null,
 *       val message: String? = null,
 *       val payload: Map<String, String> = emptyMap()
 *   )
 *
 *   @Serializable
 *   data class NativeAgentStepRecord(
 *       val agentId: String,
 *       val agentName: String,
 *       val role: String,
 *       val action: String,
 *       val thought: String,
 *       val output: String,
 *       val blackboardUpdates: Map<String, String>,
 *       val success: Boolean,
 *       val errorMessage: String? = null,
 *       val durationMs: Long,
 *       val timestamp: Long
 *   )
 *
 *   @Serializable
 *   data class NativeExecutionResult(
 *       val taskId: String,
 *       val success: Boolean,
 *       val steps: List<NativeAgentStepRecord>,
 *       val blackboardSnapshot: Map<String, String>,
 *       val durationMs: Long,
 *       val retryCount: Int,
 *       val agentInvocations: Int,
 *       val dynamicAgentCount: Int,
 *       val errorMessage: String? = null
 *   )
 *
 *   @Serializable
 *   data class NativeMetrics(
 *       val totalTasks: Long,
 *       val successfulTasks: Long,
 *       val failedTasks: Long,
 *       val cancelledTasks: Long,
 *       val averageExecutionTimeMs: Double,
 *       val successRate: Double,
 *       val currentConcurrency: Int,
 *       val peakConcurrency: Int
 *   )
 * ────────────────────────────────────────────────────────────────────
 * 若 ARCH-2 实际实现与本契约字段名不一致,需在 :rage-jni 侧调整以对齐,
 * 否则本文件的 mapper 会在编译期报错(这是契约对齐的强制点)。
 */

// ============================================================
// RageModeConfig → NativeRageConfig
// ============================================================

/** 把 Kotlin 侧 [RageModeConfig] 映射为 C++ 核心可识别的 [NativeRageConfig]。 */
fun RageModeConfig.toNative(): NativeRageConfig = NativeRageConfig(
    maxConcurrency = maxConcurrency,
    defaultTimeoutMs = defaultTimeoutMs,
    maxRetries = maxRetries,
    enableAutoExpand = enableAutoExpand,
    enableGitBranching = enableGitBranching,
    enableSandboxExec = enableSandboxExec,
    enableGithubSearch = enableGithubSearch,
    enableCodeRag = enableCodeRag
)

// ============================================================
// NativeExecutionResult → TaskExecutionResult
// ============================================================

/** 把 C++ 核心返回的 [NativeExecutionResult] 映射回 Kotlin 公开类型 [TaskExecutionResult]。 */
fun NativeExecutionResult.toKotlin(): TaskExecutionResult = TaskExecutionResult(
    taskId = taskId,
    success = success,
    steps = steps.map { it.toKotlin() },
    blackboardSnapshot = blackboardSnapshot,
    durationMs = durationMs,
    retryCount = retryCount,
    agentInvocations = agentInvocations,
    dynamicAgentCount = dynamicAgentCount,
    errorMessage = errorMessage
)

// ============================================================
// NativeAgentStepRecord → AgentStepRecord
// (NativeAgentStepRecord 在 :rage-jni 的 com.apex.rage.nativelib 包中声明)
// ============================================================

/** 映射 native 步骤记录 —— 字段与 [AgentStepRecord] 一一对齐。 */
private fun com.apex.rage.nativelib.NativeAgentStepRecord.toKotlin(): AgentStepRecord = AgentStepRecord(
    agentId = agentId,
    agentName = agentName,
    role = role,
    action = action,
    thought = thought,
    output = output,
    blackboardUpdates = blackboardUpdates,
    success = success,
    errorMessage = errorMessage,
    durationMs = durationMs,
    timestamp = timestamp
)

// ============================================================
// NativeEvent → RageEvent
// ============================================================

/**
 * 把 C++ 核心回调的 [NativeEvent] 映射为 Kotlin [RageEvent] —— 不匹配的事件返回 null。
 *
 * 事件 `type` 取值与映射:
 * - `TASK_STARTED`       → [RageEvent.TaskStarted]           (message 作为 description)
 * - `TASK_PROGRESS`      → [RageEvent.TaskProgress]          (progress / message;
 *   [RageEngine.startTask] 同时用此事件驱动 `onProgress` 回调)
 * - `TASK_COMPLETED`     → [RageEvent.TaskCompleted]         (success / durationMs)
 * - `TASK_FAILED`        → [RageEvent.TaskFailed]            (message 作为 error)
 * - `TASK_CANCELLED`     → [RageEvent.TaskCancelled]         (message 作为 reason)
 * - `SKILL_INVOKED`      → [RageEvent.SkillInvoked]          (payload 需包含 skillId / skillName)
 * - `AGENT_STEP`         → [RageEvent.AgentStepEvent]        (payload 需包含 agentId / agentName / action;
 *   success 字段驱动事件 success)
 * - `BLACKBOARD_UPDATED` → [RageEvent.BlackboardUpdated]     (payload 作为黑板快照)
 * - 其他                 → null(忽略,不转发)
 */
fun NativeEvent.toRageEvent(): RageEvent? {
    val id = taskId ?: return null
    return when (type) {
        "TASK_STARTED" -> RageEvent.TaskStarted(id, message ?: "")
        "TASK_PROGRESS" -> RageEvent.TaskProgress(id, progress ?: 0f, message)
        "TASK_COMPLETED" -> RageEvent.TaskCompleted(id, success ?: false, durationMs ?: 0L)
        "TASK_FAILED" -> RageEvent.TaskFailed(id, message ?: "unknown")
        "TASK_CANCELLED" -> RageEvent.TaskCancelled(id, message)
        "SKILL_INVOKED" -> RageEvent.SkillInvoked(
            id = id,
            skillId = payload["skillId"] ?: "",
            skillName = payload["skillName"] ?: ""
        )
        "AGENT_STEP" -> RageEvent.AgentStepEvent(
            id = id,
            agentId = payload["agentId"] ?: "",
            agentName = payload["agentName"] ?: "",
            action = payload["action"] ?: "",
            success = success ?: false
        )
        "BLACKBOARD_UPDATED" -> RageEvent.BlackboardUpdated(id, payload)
        else -> null
    }
}

// ============================================================
// NativeMetrics → RageMetrics
// ============================================================

/** 把 C++ 核心返回的 [NativeMetrics] 映射为 Kotlin 公开类型 [RageMetrics]。 */
fun NativeMetrics.toKotlin(): RageMetrics = RageMetrics(
    totalTasks = totalTasks,
    successfulTasks = successfulTasks,
    failedTasks = failedTasks,
    cancelledTasks = cancelledTasks,
    averageExecutionTimeMs = averageExecutionTimeMs,
    successRate = successRate,
    currentConcurrency = currentConcurrency,
    peakConcurrency = peakConcurrency
)
