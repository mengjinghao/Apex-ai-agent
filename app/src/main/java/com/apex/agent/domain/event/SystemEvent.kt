package com.apex.agent.domain.event

/**
 * 系统启动事件
 */
data class SystemStartedEvent(
    val timestamp: Long,
    val version: String,
    val buildNumber: String
)

/**
 * 系统关闭事件
 */
data class SystemShutdownEvent(
    val timestamp: Long,
    val graceful: Boolean,
    val reason: String
)

/**
 * 系统错误事件
 */
data class SystemErrorEvent(
    val errorCode: String,
    val message: String,
    val severity: ErrorSeverity,
    val stackTrace: String? = null
)

enum class ErrorSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * 系统配置变更事件
 */
data class SystemConfigChangedEvent(
    val key: String,
    val oldValue: String?,
    val newValue: String?,
    val changedBy: String
)

/**
 * 系统健康检查事件
 */
data class SystemHealthCheckEvent(
    val status: HealthStatus,
    val component: String,
    val responseTime: Long
)


/**
 * 系统资源警告事件
 */
data class SystemResourceWarningEvent(
    val resourceType: String,
    val usagePercent: Double,
    val threshold: Double
)
