@file:Suppress("unused")

package com.apex.lib.rage

import com.apex.rage.nativelib.NativeEvent
import com.apex.rage.nativelib.NativeExecutionResult
import com.apex.rage.nativelib.NativeMetrics
import com.apex.rage.nativelib.NativeRageConfig
import com.apex.rage.nativelib.NativeSubtask

/**
 * Kotlin ↔ Native 类型映射 —— 把 [RageModels] / [RageArchitectTypes] 中的数据类
 * 与 [:rage-jni] 的 `Native*` 数据类(package `com.apex.rage.nativelib`)相互转换。
 *
 * **行为契约**:这些 mapper 是纯函数,不接触 [RageTaskStore] / 不发事件。
 * 由 [RageEngine] 在调用 [com.apex.rage.nativelib.RageNativeBridge] 前后调用。
 *
 * **Source of truth**:Native 端 schema 以 [:rage-jni] 的
 * `com.apex.rage.nativelib.NativeModels` 为准;本文件必须与之对齐,字段名不一致
 * 会在编译期报错(契约对齐的强制点)。若 Native 端某些字段在 Kotlin 公开类型中
 * 没有对应,使用合理默认(空集合 / 0 / null)并在下方逐条注释。
 *
 * **当前已知的字段差异** (NativeExecutionResult ↔ [TaskExecutionResult]):
 * - Native 端 `subtasks: List<NativeSubtask>` → Kotlin 端 `steps: List<AgentStepRecord>`。
 *   [NativeSubtask] 仅含 id/description/status/output,映射为最小化的
 *   [AgentStepRecord](agentId=id, agentName="", role="", action=description,
 *   thought="", output=output, success=(status=="completed"),其余字段填默认值)。
 * - Native 端不提供 `blackboardSnapshot`,映射为空 Map。
 * - Native 端不提供 `dynamicAgentCount`,映射为 0。
 * - Native 端额外提供 `finalOutput`,当前 [TaskExecutionResult] 无对应字段,暂忽略
 *   (后续若新增 finalOutput 字段需同步更新本 mapper)。
 *
 * **NativeEvent 字段说明**:所有字段均为非空(默认空串 / 0 / false)。
 * - `taskId` 非空 → mapper 中以 `taskId.isEmpty()` 判定是否丢弃事件。
 * - `type` 为原始字符串,本 mapper 按 `TASK_STARTED / TASK_PROGRESS / TASK_COMPLETED /
 *   TASK_FAILED / TASK_CANCELLED / SKILL_INVOKED / AGENT_STEP / BLACKBOARD_UPDATED`
 *   分支映射;其余(LLM_REQUEST / SEARCH_REQUEST 等)返回 null。
 * - SKILL_INVOKED / AGENT_STEP 使用 NativeEvent 上的扁平字段
 *   (skillId / skillName / agentId / agentName / action)而非 Map payload。
 *   BLACKBOARD_UPDATED 因 NativeEvent 不携带黑板快照,以空 Map 兜底。
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

/**
 * 把 C++ 核心返回的 [NativeExecutionResult] 映射回 Kotlin 公开类型 [TaskExecutionResult]。
 *
 * 属于有损映射 —— NativeExecutionResult 没有原生 blackboard / dynamic agent 数据,
 * 仅有 [NativeSubtask] 列表(轻量步骤记录),按 [NativeSubtask.toAgentStepRecord]
 * 映射为 [AgentStepRecord]。
 */
fun NativeExecutionResult.toKotlin(): TaskExecutionResult = TaskExecutionResult(
    taskId = taskId,
    success = success,
    steps = subtasks.map { it.toAgentStepRecord() },
    blackboardSnapshot = emptyMap(),
    durationMs = durationMs,
    retryCount = retryCount,
    agentInvocations = agentInvocations,
    dynamicAgentCount = 0,
    errorMessage = errorMessage
)

/**
 * 把 [NativeSubtask] 映射为最小化 [AgentStepRecord]。
 *
 * [NativeSubtask] 仅含 id/description/status/output,因此 [AgentStepRecord] 的
 * agentName/role/thought/blackboardUpdates 等字段填默认空值,timestamp/durationMs 填 0。
 * success 由 status == "completed" 推断(大小写不敏感)。
 */
private fun NativeSubtask.toAgentStepRecord(): AgentStepRecord = AgentStepRecord(
    agentId = id,
    agentName = "",
    role = "",
    action = description,
    thought = "",
    output = output,
    blackboardUpdates = emptyMap(),
    success = status.equals("completed", ignoreCase = true),
    errorMessage = null,
    durationMs = 0L,
    timestamp = 0L
)

// ============================================================
// NativeEvent → RageEvent
// ============================================================

/**
 * 把 C++ 核心回调的 [NativeEvent] 映射为 Kotlin [RageEvent] —— 不匹配的事件返回 null。
 *
 * 事件 `type` 取值与映射:
 * - `TASK_STARTED`       → [RageEvent.TaskStarted]           (message 作为 description)
 * - `TASK_PROGRESS`      → [RageEvent.TaskProgress]          (progress / message)
 * - `TASK_COMPLETED`     → [RageEvent.TaskCompleted]         (success;durationMs 因 NativeEvent
 *                                                          不携带,填 0)
 * - `TASK_FAILED`        → [RageEvent.TaskFailed]            (message 作为 error)
 * - `TASK_CANCELLED`     → [RageEvent.TaskCancelled]         (message 作为 reason)
 * - `SKILL_INVOKED`      → [RageEvent.SkillInvoked]          (skillId / skillName 扁平字段)
 * - `AGENT_STEP`         → [RageEvent.AgentStepEvent]        (agentId / agentName / action / success 扁平字段)
 * - `BLACKBOARD_UPDATED` → [RageEvent.BlackboardUpdated]     (NativeEvent 不携带黑板数据,entries 填空 Map)
 * - 其他                 → null(忽略,不转发)
 */
fun NativeEvent.toRageEvent(): RageEvent? {
    // taskId 在 NativeEvent 中是非空 String(默认空串),空串视为无归属任务 → 丢弃。
    val id = taskId
    if (id.isEmpty()) return null
    return when (type) {
        "TASK_STARTED" -> RageEvent.TaskStarted(id, message)
        "TASK_PROGRESS" -> RageEvent.TaskProgress(id, progress, message)
        "TASK_COMPLETED" -> RageEvent.TaskCompleted(id, success, 0L)
        "TASK_FAILED" -> RageEvent.TaskFailed(id, message.ifEmpty { "unknown" })
        "TASK_CANCELLED" -> RageEvent.TaskCancelled(id, message)
        "SKILL_INVOKED" -> RageEvent.SkillInvoked(
            id = id,
            skillId = skillId,
            skillName = skillName
        )
        "AGENT_STEP" -> RageEvent.AgentStepEvent(
            id = id,
            agentId = agentId,
            agentName = agentName,
            action = action,
            success = success
        )
        "BLACKBOARD_UPDATED" -> RageEvent.BlackboardUpdated(id, emptyMap())
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
