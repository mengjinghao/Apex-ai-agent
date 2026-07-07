package com.ai.assistance.aiterminal.terminal.agent.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

// ==================== 任务数据模型 ====================

/**
 * 任务整体状态
 */
enum class TaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ROLLED_BACK
}

/**
 * 单个步骤状态
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    ROLLED_BACK
}

/**
 * 失败处理策略
 */
enum class FailureStrategy {
    RETRY,
    ROLLBACK,
    SKIP,
    ABORT,
    ASK_USER
}

/**
 * 成功校验规则
 */
@Parcelize
data class ValidationRule(
    val type: ValidationType,
    val value: String,
    val description: String? = null
) : Parcelable

enum class ValidationType {
    EXIT_CODE_ZERO,
    OUTPUT_CONTAINS,
    OUTPUT_NOT_CONTAINS,
    FILE_EXISTS,
    CUSTOM
}

/**
 * 失败处理方案
 */
@Parcelize
data class FailureHandler(
    val strategy: FailureStrategy,
    val maxRetries: Int = 3,
    val rollbackCommand: String? = null,
    val alternativeCommands: List<String> = emptyList(),
    val description: String? = null
) : Parcelable

/**
 * 任务步骤
 */
@Parcelize
data class TaskStep(
    val id: String = UUID.randomUUID().toString(),
    val order: Int,
    val name: String,
    val description: String,
    val command: String,
    val requiresRoot: Boolean = false,
    val validationRules: List<ValidationRule> = emptyList(),
    val failureHandler: FailureHandler? = null,
    val requiresConfirmation: Boolean = false,
    val estimatedDuration: Long? = null,
    val tags: List<String> = emptyList()
) : Parcelable

/**
 * 任务执行计划
 */
@Parcelize
data class TaskPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val steps: List<TaskStep>,
    val createdAt: Long = System.currentTimeMillis(),
    val estimatedTotalDuration: Long? = null,
    val safetyWarning: String? = null,
    val reasoning: String? = null,
    val tags: List<String> = emptyList()
) : Parcelable

/**
 * 步骤执行结果
 */
@Parcelize
data class StepExecutionResult(
    val stepId: String,
    val status: StepStatus,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val startTime: Long,
    val endTime: Long,
    val retryCount: Int = 0,
    val validationPassed: Boolean? = null,
    val errorAnalysis: String? = null,
    val fixedCommand: String? = null
) : Parcelable {
    val duration: Long
        get() = endTime - startTime
    val isSuccess: Boolean
        get() = status == StepStatus.COMPLETED
    val errorMessage: String?
        get() = if (status == StepStatus.FAILED) stderr.takeIf { it.isNotBlank() } ?: "Exit code: $exitCode" else null
}

/**
 * 任务执行结果
 */
@Parcelize
data class TaskExecutionResult(
    val taskId: String,
    val status: TaskStatus,
    val stepResults: Map<String, StepExecutionResult>,
    val startTime: Long,
    val endTime: Long? = null,
    val lastCompletedStep: Int? = null,
    val errorMessage: String? = null,
    val rollbackPerformed: Boolean = false
) : Parcelable {
    val duration: Long?
        get() = endTime?.minus(startTime)
    
    val isSuccess: Boolean
        get() = status == TaskStatus.COMPLETED
}

/**
 * 任务断点快照（用于持久化）
 */
@Parcelize
data class TaskSnapshot(
    val taskId: String,
    val taskPlan: TaskPlan,
    val currentStep: Int,
    val stepResults: Map<String, StepExecutionResult>,
    val status: TaskStatus,
    val savedAt: Long = System.currentTimeMillis()
) : Parcelable

/**
 * 错误分析结果
 */
data class ErrorAnalysis(
    val originalCommand: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val errorType: ErrorType,
    val rootCause: String,
    val suggestedFix: String,
    val fixedCommand: String? = null,
    val confidence: Float
)

enum class ErrorType {
    PERMISSION_DENIED,
    COMMAND_NOT_FOUND,
    INVALID_ARGUMENT,
    FILE_NOT_FOUND,
    FILE_EXISTS,
    SELINUX_BLOCKED,
    PARTITION_READ_ONLY,
    VERSION_INCOMPATIBLE,
    NETWORK_ERROR,
    UNKNOWN
}

/**
 * 定时任务配置
 */
@Parcelize
data class ScheduledTaskConfig(
    val taskId: String = UUID.randomUUID().toString(),
    val taskPlan: TaskPlan,
    val triggerType: TriggerType,
    val triggerTime: Long,
    val repeatInterval: Long? = null,
    val requiresWakeLock: Boolean = true,
    val requiresCharging: Boolean = false,
    val requiresIdle: Boolean = false,
    val runAsForeground: Boolean = false,
    var notificationEnabled: Boolean = true,
    val taskName: String,
    val taskDescription: String,
    val groupId: String? = null,
    val stopOnFirstError: Boolean = true,
    val networkType: String? = null,
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false
) : Parcelable

enum class TriggerType {
    ONE_TIME,
    REPEATING,
    DAILY,
    WEEKLY,
    CUSTOM_CRON
}

/**
 * 任务通知配置
 */
@Parcelize
data class TaskNotificationConfig(
    val onStart: Boolean = true,
    val onSuccess: Boolean = true,
    val onFailure: Boolean = true,
    val onRollback: Boolean = true,
    val onStepComplete: Boolean = false,
    val channelId: String = "task_executor_channel",
    val importanceLevel: Int = 3,
    val vibrationEnabled: Boolean = true
) : Parcelable
