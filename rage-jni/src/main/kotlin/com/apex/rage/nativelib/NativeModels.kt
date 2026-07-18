package com.apex.rage.nativelib

import kotlinx.serialization.Serializable

/**
 * Kotlin-side mirror of the C++ core types in `core/RageTypes.h`.
 *
 * These data classes are JSON-serialized via kotlinx.serialization when
 * crossing the JNI boundary — the C++ side has a hand-rolled JSON
 * serializer/parser (`util/JsonSerializer.cpp`) that produces/consumes the
 * same wire format.
 *
 * Schema MUST stay in sync with the C++ structs. Any field added on one side
 * must be added on the other.
 */

enum class NativeTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED;

    companion object {
        fun fromString(s: String): NativeTaskStatus =
            runCatching { valueOf(s.uppercase()) }.getOrDefault(PENDING)
    }
}

@Serializable
data class NativeSubtask(
    val id: String = "",
    val description: String = "",
    val status: String = "pending",
    val output: String = ""
)

@Serializable
data class NativeTask(
    val id: String,
    val description: String,
    val preset: String = "BALANCED",
    val status: String = "PENDING",
    val createdAt: Long = 0L,
    val startedAt: Long = 0L,
    val completedAt: Long = 0L,
    val progress: Float = 0.0f,
    val result: String = "",
    val errorMessage: String = "",
    val agentInvocations: Int = 0,
    val retryCount: Int = 0,
    val durationMs: Long = 0L
)

@Serializable
data class NativeRageConfig(
    val maxConcurrency: Int = 4,
    val defaultTimeoutMs: Long = 60_000L,
    val maxRetries: Int = 3,
    val enableAutoExpand: Boolean = true,
    val enableGitBranching: Boolean = true,
    val enableSandboxExec: Boolean = true,
    val enableGithubSearch: Boolean = false,
    val enableCodeRag: Boolean = true
)

@Serializable
data class NativeExecutionResult(
    val success: Boolean = false,
    val errorMessage: String = "",
    val subtasks: List<NativeSubtask> = emptyList(),
    val agentInvocations: Int = 0,
    val retryCount: Int = 0,
    val durationMs: Long = 0L,
    val finalOutput: String = "",
    val taskId: String = ""
)

@Serializable
data class NativeMetrics(
    val totalTasks: Long = 0L,
    val successfulTasks: Long = 0L,
    val failedTasks: Long = 0L,
    val cancelledTasks: Long = 0L,
    val averageExecutionTimeMs: Double = 0.0,
    val successRate: Double = 0.0,
    val currentConcurrency: Int = 0,
    val peakConcurrency: Int = 0
)

enum class NativeEventType {
    TASK_STARTED,
    TASK_PROGRESS,
    TASK_COMPLETED,
    TASK_FAILED,
    SKILL_INVOKED,
    AGENT_STEP,
    BLACKBOARD_UPDATED,
    LLM_REQUEST,
    SEARCH_REQUEST;

    companion object {
        fun fromString(s: String): NativeEventType =
            runCatching { valueOf(s.uppercase()) }.getOrDefault(TASK_PROGRESS)
    }
}

@Serializable
data class NativeEvent(
    val type: String = "TASK_PROGRESS",
    val taskId: String = "",
    val progress: Float = 0.0f,
    val message: String = "",
    val agentId: String = "",
    val agentName: String = "",
    val action: String = "",
    val success: Boolean = false,
    val skillId: String = "",
    val skillName: String = "",
    val llmPrompt: String = "",
    val llmSystemPrompt: String = "",
    val searchQuery: String = ""
) {
    val eventType: NativeEventType get() = NativeEventType.fromString(type)
}
