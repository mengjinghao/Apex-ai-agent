package com.apex.agent.core.scheduler

import java.util.UUID

/**
 * 定时任务模型
 * 
 * 定义定时任务的数据结�支持多种任务类型和调度配�? */
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val taskType: TaskType,
    val cronExpression: String,
    val enabled: Boolean = true,
    val platforms: List<DeliveryPlatform> = listOf(DeliveryPlatform.IN_APP),
    val config: TaskConfig = TaskConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastExecutedAt: Long? = null,
    val nextExecutionTime: Long? = null,
    val executionCount: Int = 0,
    val lastError: String? = null
) {
    /**
     * 任务类型枚举
     */
    enum class TaskType(val displayName: String, val description: String) {
        DAILY_REPORT("日报生成", "生成每日工作报告"),
        BACKUP("数据备份", "自动备份重要数据"),
        AUDIT("系统审计", "执行安全审计检�?),
        AUTO_REPORT("自动报告", "定时生成各类报告"),
        HEALTH_CHECK("健康检�?, "系统健康状态检�?),
        NOTIFICATION("通知提醒", "发送定时通知"),
        CUSTOM("自定�?, "用户自定义任�?)
    }
    
    /**
     * 投递平台枚�?     */
    enum class DeliveryPlatform(val displayName: String) {
        IN_APP("应用内通知"),
        TELEGRAM("Telegram"),
        DISCORD("Discord"),
        EMAIL("Email"),
        WECHAT("微信"),
        PUSH("系统推�?)
    }
    
    /**
     * 任务配置
     */
    data class TaskConfig(
        val retryCount: Int = 3,
        val retryDelayMinutes: Int = 5,
        val timeoutMinutes: Int = 30,
        val notificationEnabled: Boolean = true,
        val soundEnabled: Boolean = false,
        val vibrationEnabled: Boolean = true,
        val customParams: Map<String, String> = emptyMap()
    )
    
    /**
     * 任务执行结果
     */
    data class ExecutionResult(
        val taskId: String,
        val success: Boolean,
        val executedAt: Long = System.currentTimeMillis(),
        val durationMs: Long = 0,
        val output: String? = null,
        val error: String? = null,
        val platformsDelivered: List<DeliveryPlatform> = emptyList()
    )
    
    /**
     * 任务执行状�?     */
    enum class ExecutionStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        RETRYING,
        CANCELLED
    }
    
    /**
     * 创建副本,更新执行信息
     */
    fun withExecutionResult(result: ExecutionResult): ScheduledTask {
        return copy(
            lastExecutedAt = result.executedAt,
            executionCount = executionCount + 1,
            lastError = if (!result.success) result.error else null,
            nextExecutionTime = calculateNextExecution()
        )
    }
    
    /**
     * 计算下次执行时间
     */
    fun calculateNextExecution(): Long? {
        return try {
            CronExpressionParser.getInstance().calculateNextExecution(cronExpression)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取任务状态摘�?     */
    fun getStatusSummary(): String {
        val lastExec = lastExecutedAt?.let { formatTime(it) } ?: "从未执行"
        val nextExec = nextExecutionTime?.let { formatTime(it) } ?: "未知"
        val errorInfo = lastError?.let { " (错误: ${it})" } ?: ""
        return """
            任务: ${name}
            类型: ${taskType.displayName}
            状�? ${if (enabled) "已启�? else "已禁�?}${errorInfo}
            执行次数: ${executionCount}
            上次执行: ${lastExec}
            下次执行: ${nextExec}
            投递平�? ${platforms.joinToString { it.displayName }}
        """.trimIndent()
    }
    
    /**
     * 格式化时�?     */
    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    
    companion object {
        /**
         * 创建日报任务
         */
        fun createDailyReportTask(
            name: String,
            cronExpression: String = "0 9 * * *",
            platforms: List<DeliveryPlatform> = listOf(DeliveryPlatform.IN_APP)
        ): ScheduledTask {
            return ScheduledTask(
                name = name,
                taskType = TaskType.DAILY_REPORT,
                cronExpression = cronExpression,
                platforms = platforms
            )
        }
        
        /**
         * 创建备份任务
         */
        fun createBackupTask(
            name: String,
            cronExpression: String = "0 2 * * *",
            platforms: List<DeliveryPlatform> = listOf(DeliveryPlatform.IN_APP)
        ): ScheduledTask {
            return ScheduledTask(
                name = name,
                taskType = TaskType.BACKUP,
                cronExpression = cronExpression,
                platforms = platforms
            )
        }
        
        /**
         * 创建审计任务
         */
        fun createAuditTask(
            name: String,
            cronExpression: String = "0 0 * * 0",
            platforms: List<DeliveryPlatform> = listOf(DeliveryPlatform.IN_APP)
        ): ScheduledTask {
            return ScheduledTask(
                name = name,
                taskType = TaskType.AUDIT,
                cronExpression = cronExpression,
                platforms = platforms
            )
        }
    }
}