package com.apex.agent.kernel.burst.engine.security

import java.util.concurrent.ConcurrentHashMap

/**
 * E11: 内核安全网关
 *
 * 请求安全检查：
 * - 权限验证
 * - 速率限制
 * - 输入过滤
 * - 审计日志
 */
class KernelSecurityGateway {

    data class SecurityContext(
        val userId: String,
        val sessionId: String,
        val permissions: Set<String>,
        val roles: Set<String>,
        val ipAddress: String? = null,
        val deviceId: String? = null
    )

    data class SecurityCheckResult(
        val allowed: Boolean,
        val reason: String,
        val violations: List<SecurityViolation>,
        val auditEntry: AuditEntry
    )

    data class SecurityViolation(
        val type: ViolationType,
        val message: String,
        val severity: ViolationSeverity
    )

    enum class ViolationType {
        MISSING_PERMISSION, RATE_LIMITED, INPUT_BLOCKED,
        BLACKLISTED, QUOTA_EXCEEDED, SUSPICIOUS_ACTIVITY
    }

    enum class ViolationSeverity { LOW, MEDIUM, HIGH, CRITICAL }

    data class AuditEntry(
        val userId: String,
        val action: String,
        val resource: String,
        val allowed: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val details: Map<String, Any> = emptyMap()
    )

    data class SecurityRule(
        val name: String,
        val description: String,
        val check: (SecurityContext, String, Map<String, Any>) -> SecurityViolation?
    )

    private val rules = mutableListOf<SecurityRule>()
    private val blacklist = ConcurrentHashMap<String, Long>()  // userId -> until timestamp
    private val rateLimits = ConcurrentHashMap<String, RateBucket>()
    private val auditLog = mutableListOf<AuditEntry>()
    private val inputFilters = mutableListOf<Regex>()

    init {
        registerBuiltinRules()
        registerBuiltinFilters()
    }

    /**
     * 安全检查
     */
    fun check(
        context: SecurityContext,
        action: String,
        resource: String = "",
        input: String = "",
        metadata: Map<String, Any> = emptyMap()
    ): SecurityCheckResult {
        val violations = mutableListOf<SecurityViolation>()

        // 黑名单检查
        val blacklistUntil = blacklist[context.userId]
        if (blacklistUntil != null && System.currentTimeMillis() < blacklistUntil) {
            violations.add(SecurityViolation(ViolationType.BLACKLISTED, "用户在黑名单中", ViolationSeverity.CRITICAL))
        }

        // 速率限制
        val rateKey = "${context.userId}:$action"
        val bucket = rateLimits.computeIfAbsent(rateKey) { RateBucket(60, 60_000L) }
        if (!bucket.tryConsume()) {
            violations.add(SecurityViolation(ViolationType.RATE_LIMITED, "速率超限: $action", ViolationSeverity.MEDIUM))
        }

        // 输入过滤
        if (input.isNotBlank()) {
            for (filter in inputFilters) {
                if (filter.containsMatchIn(input)) {
                    violations.add(SecurityViolation(ViolationType.INPUT_BLOCKED, "输入包含禁止内容", ViolationSeverity.HIGH))
                    break
                }
            }
        }

        // 自定义规则
        for (rule in rules) {
            val violation = rule.check(context, action, metadata + mapOf("resource" to resource, "input" to input))
            if (violation != null) violations.add(violation)
        }

        val hasCritical = violations.any { it.severity == ViolationSeverity.CRITICAL }
        val hasHigh = violations.any { it.severity == ViolationSeverity.HIGH }
        val allowed = violations.isEmpty() || (!hasCritical && !hasHigh)

        val audit = AuditEntry(context.userId, action, resource, allowed,
            details = mapOf("violations" to violations.size, "sessionId" to context.sessionId))
        auditLog.add(audit)
        while (auditLog.size > 1000) auditLog.removeAt(0)

        return SecurityCheckResult(allowed, if (allowed) "允许" else "拒绝", violations, audit)
    }

    fun blacklist(userId: String, durationMs: Long) {
        blacklist[userId] = System.currentTimeMillis() + durationMs
    }

    fun unblacklist(userId: String) {
        blacklist.remove(userId)
    }

    fun registerRule(rule: SecurityRule) { rules.add(rule) }
    fun addInputFilter(pattern: String) { inputFilters.add(Regex(pattern)) }

    fun getAuditLog(limit: Int = 100): List<AuditEntry> = auditLog.takeLast(limit)

    private fun registerBuiltinRules() {
        rules.add(SecurityRule("permission_check", "权限检查") { ctx, action, _ ->
            val requiredPerm = when (action) {
                "execute_skill", "run_pipeline" -> "execute"
                "update_config", "reload_plugin" -> "admin"
                "shutdown" -> "superadmin"
                else -> "read"
            }
            if (requiredPerm !in ctx.permissions && "superadmin" !in ctx.roles) {
                SecurityViolation(ViolationType.MISSING_PERMISSION, "缺少权限: $requiredPerm", ViolationSeverity.HIGH)
            } else null
        })
    }

    private fun registerBuiltinFilters() {
        inputFilters.add(Regex("(?i)<script|javascript:|onerror=|onload="))  // XSS
        inputFilters.add(Regex("(?i)(drop|delete|truncate)\\s+table"))  // SQL injection
        inputFilters.add(Regex("(?i)\\$\\{|\\${env\\."))  // Template injection
    }

    private class RateBucket(private val capacity: Int, private val windowMs: Long) {
        private var tokens = capacity
        private var lastRefill = System.currentTimeMillis()

        @Synchronized
        fun tryConsume(): Boolean {
            refill()
            return if (tokens > 0) { tokens--; true } else false
        }

        private fun refill() {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefill
            val newTokens = (elapsed * capacity / windowMs).toInt()
            if (newTokens > 0) {
                tokens = (tokens + newTokens).coerceAtMost(capacity)
                lastRefill = now
            }
        }
    }
}
