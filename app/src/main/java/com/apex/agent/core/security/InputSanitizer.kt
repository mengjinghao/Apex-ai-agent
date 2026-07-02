package com.apex.agent.core.security

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class InputSanitizer(private val name: String = "input-sanitizer") {
    private val logger = LoggerFactory.getLogger("InputSanitizer-$name")

    companion object {
        private val SQL_INJECTION_PATTERN = Pattern.compile(
            "(?i)(\\b(ALTER|CREATE|DELETE|DROP|EXEC(UTE)?|INSERT\\s+INTO|MERGE|SELECT\\s+.*\\bFROM|TRUNCATE|UPDATE|UNION(\\s+ALL)?)\\b)",
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        )

        private val XSS_PATTERN = Pattern.compile(
            "(?i)(<script[^>]*>|<\\/script>|javascript:\\s*|on\\w+\\s*=|vbscript:|expression\\(|eval\\(|alert\\()",
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        )

        private val PATH_TRAVERSAL_PATTERN = Pattern.compile(
            "(\\.\\.\\/|\\.\\.\\\\|\\.\\.%2f|\\.\\.%5c|%2e%2e%2f|%2e%2e\\\\)",
            Pattern.CASE_INSENSITIVE
        )

        private val COMMAND_INJECTION_PATTERN = Pattern.compile(
            "(?i)([;&|`]|\\b(rm|del|format|shutdown|reboot|sudo|chmod|chown|wget|curl)\\b)",
            Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
        )

        private val HTML_TAG_PATTERN = Pattern.compile("<[^>]*>")
        private val CONTROL_CHAR_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
        private val UNICODE_SPECIAL_PATTERN = Pattern.compile(
            "[\\uFFFD\\u200B\\u200C\\u200D\\uFEFF\\u00A0\\u2028\\u2029]"
        )

        private val MAX_STRING_LENGTH = 100000
        private val MAX_LIST_SIZE = 10000
        private val MAX_NESTING_DEPTH = 50
    }

    data class SanitizationStats(
        val totalSanitized: Long,
        val totalRejected: Long,
        val sqlInjectionAttempts: Long,
        val xssAttempts: Long,
        val pathTraversalAttempts: Long,
        val commandInjectionAttempts: Long,
        val oversizedInputs: Long
    )

    private val totalSanitized = AtomicLong(0)
    private val totalRejected = AtomicLong(0)
    private val sqlInjectionAttempts = AtomicLong(0)
    private val xssAttempts = AtomicLong(0)
    private val pathTraversalAttempts = AtomicLong(0)
    private val commandInjectionAttempts = AtomicLong(0)
    private val oversizedInputs = AtomicLong(0)

    data class SanitizationResult(
        val sanitized: String,
        val wasModified: Boolean,
        val threats: List<ThreatType>
    )

    enum class ThreatType {
        SQL_INJECTION,
        XSS,
        PATH_TRAVERSAL,
        COMMAND_INJECTION,
        OVERSIZED_INPUT,
        CONTROL_CHARACTER,
        UNICODE_SPECIAL,
        HTML_TAG
    }

    fun sanitize(input: String, strict: Boolean = false): SanitizationResult {
        val threats = mutableListOf<ThreatType>()
        var modified = false
        var result = input

        if (result.length > MAX_STRING_LENGTH) {
            threats.add(ThreatType.OVERSIZED_INPUT)
            oversizedInputs.incrementAndGet()
            if (strict) {
                totalRejected.incrementAndGet()
                return SanitizationResult("", true, threats)
            }
            result = result.take(MAX_STRING_LENGTH)
            modified = true
        }

        if (SQL_INJECTION_PATTERN.matcher(result).find()) {
            threats.add(ThreatType.SQL_INJECTION)
            sqlInjectionAttempts.incrementAndGet()
            if (strict) {
                totalRejected.incrementAndGet()
                return SanitizationResult("", true, threats)
            }
            result = SQL_INJECTION_PATTERN.matcher(result).replaceAll("")
            modified = true
        }

        if (XSS_PATTERN.matcher(result).find()) {
            threats.add(ThreatType.XSS)
            xssAttempts.incrementAndGet()
            if (strict) {
                totalRejected.incrementAndGet()
                return SanitizationResult("", true, threats)
            }
            result = XSS_PATTERN.matcher(result).replaceAll("")
            modified = true
        }

        if (PATH_TRAVERSAL_PATTERN.matcher(result).find()) {
            threats.add(ThreatType.PATH_TRAVERSAL)
            pathTraversalAttempts.incrementAndGet()
            if (strict) {
                totalRejected.incrementAndGet()
                return SanitizationResult("", true, threats)
            }
            result = PATH_TRAVERSAL_PATTERN.matcher(result).replaceAll("")
            modified = true
        }

        if (COMMAND_INJECTION_PATTERN.matcher(result).find()) {
            threats.add(ThreatType.COMMAND_INJECTION)
            commandInjectionAttempts.incrementAndGet()
            if (strict) {
                totalRejected.incrementAndGet()
                return SanitizationResult("", true, threats)
            }
            result = COMMAND_INJECTION_PATTERN.matcher(result).replaceAll("")
            modified = true
        }

        val controlMatcher = CONTROL_CHAR_PATTERN.matcher(result)
        if (controlMatcher.find()) {
            threats.add(ThreatType.CONTROL_CHARACTER)
            result = controlMatcher.replaceAll("")
            modified = true
        }

        val unicodeMatcher = UNICODE_SPECIAL_PATTERN.matcher(result)
        if (unicodeMatcher.find()) {
            threats.add(ThreatType.UNICODE_SPECIAL)
            result = unicodeMatcher.replaceAll("")
            modified = true
        }

        if (modified) totalSanitized.incrementAndGet()
        return SanitizationResult(result.trim(), modified, threats)
    }

    fun sanitizeStrict(input: String): SanitizationResult = sanitize(input, strict = true)

    fun isValid(input: String): Boolean {
        if (input.length > MAX_STRING_LENGTH) return false
        if (SQL_INJECTION_PATTERN.matcher(input).find()) return false
        if (XSS_PATTERN.matcher(input).find()) return false
        if (PATH_TRAVERSAL_PATTERN.matcher(input).find()) return false
        if (COMMAND_INJECTION_PATTERN.matcher(input).find()) return false
        return true
    }

    fun containsThreat(input: String): ThreatType? {
        if (SQL_INJECTION_PATTERN.matcher(input).find()) return ThreatType.SQL_INJECTION
        if (XSS_PATTERN.matcher(input).find()) return ThreatType.XSS
        if (PATH_TRAVERSAL_PATTERN.matcher(input).find()) return ThreatType.PATH_TRAVERSAL
        if (COMMAND_INJECTION_PATTERN.matcher(input).find()) return ThreatType.COMMAND_INJECTION
        return null
    }

    fun getStats(): SanitizationStats = SanitizationStats(
        totalSanitized = totalSanitized.get(),
        totalRejected = totalRejected.get(),
        sqlInjectionAttempts = sqlInjectionAttempts.get(),
        xssAttempts = xssAttempts.get(),
        pathTraversalAttempts = pathTraversalAttempts.get(),
        commandInjectionAttempts = commandInjectionAttempts.get(),
        oversizedInputs = oversizedInputs.get()
    )
}

class RateLimiterV2(
    private val name: String = "rate-limiter-v2",
    private val defaultLimit: Int = 100,
    private val defaultWindowMs: Long = 60000L,
    private val burstMultiplier: Double = 2.0
) {
    data class RateLimitRule(
        val key: String,
        val limit: Int,
        val windowMs: Long,
        val burstLimit: Int
    )

    data class RateLimitResult(
        val allowed: Boolean,
        val remaining: Int,
        val resetTimeMs: Long,
        val retryAfterMs: Long
    )

    data class RateLimiterStats(
        val totalRequests: Long,
        val totalAllowed: Long,
        val totalBlocked: Long,
        val activeRules: Int,
        val blockedByRule: Map<String, Long>
    )

    private class SlidingWindow(
        val limit: Int,
        val windowMs: Long,
        val burstLimit: Int
    ) {
        private val timestamps = ConcurrentLinkedQueue<Long>()
        private val blockedCount = AtomicLong(0)

        fun tryAcquire(now: Long): RateLimitResult {
            val windowStart = now - windowMs
            while (timestamps.peek() != null && timestamps.peek() < windowStart) {
                timestamps.poll()
            }
            val allowed = timestamps.size < limit
            if (allowed) {
                timestamps.add(now)
            } else {
                blockedCount.incrementAndGet()
            }
            val oldest = timestamps.peek() ?: now
            return RateLimitResult(
                allowed = allowed || timestamps.size < burstLimit,
                remaining = (limit - timestamps.size).coerceAtLeast(0),
                resetTimeMs = oldest + windowMs,
                retryAfterMs = (oldest + windowMs - now).coerceAtLeast(0)
            )
        }

        fun getBlockedCount(): Long = blockedCount.get()
    }

    private val rules = ConcurrentHashMap<String, RateLimitRule>()
    private val windows = ConcurrentHashMap<String, SlidingWindow>()
    private val totalRequests = AtomicLong(0)
    private val totalAllowed = AtomicLong(0)
    private val totalBlocked = AtomicLong(0)

    fun addRule(key: String, limit: Int, windowMs: Long = defaultWindowMs) {
        val rule = RateLimitRule(key, limit, windowMs, (limit * burstMultiplier).toInt())
        rules[key] = rule
        windows[key] = SlidingWindow(limit, windowMs, rule.burstLimit)
    }

    fun addCustomRule(rule: RateLimitRule) {
        rules[rule.key] = rule
        windows[rule.key] = SlidingWindow(rule.limit, rule.windowMs, rule.burstLimit)
    }

    fun tryAcquire(key: String): RateLimitResult {
        totalRequests.incrementAndGet()
        val window = windows[key] ?: return RateLimitResult(true, defaultLimit, 0, 0)
        val result = window.tryAcquire(System.currentTimeMillis())
        if (result.allowed) totalAllowed.incrementAndGet()
        else totalBlocked.incrementAndGet()
        return result
    }

    fun <T> callWithLimit(key: String, block: () -> T): T {
        val result = tryAcquire(key)
        if (!result.allowed) {
            throw RateLimitExceededException("Rate limit exceeded for $key: retry after ${result.retryAfterMs}ms")
        }
        return block()
    }

    suspend fun <T> callWithLimitSuspend(key: String, block: suspend () -> T): T {
        val result = tryAcquire(key)
        if (!result.allowed) {
            throw RateLimitExceededException("Rate limit exceeded for $key")
        }
        return block()
    }

    fun getStats(): RateLimiterStats = RateLimiterStats(
        totalRequests = totalRequests.get(),
        totalAllowed = totalAllowed.get(),
        totalBlocked = totalBlocked.get(),
        activeRules = rules.size,
        blockedByRule = rules.keys.associateWith { windows[it]?.getBlockedCount() ?: 0 }
    )

    class RateLimitExceededException(message: String) : RuntimeException(message)
}

class InputValidator(
    private val name: String = "input-validator"
) {
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val errors: List<String>) : ValidationResult()
    }

    data class ValidationRule(
        val name: String,
        val validate: (String) -> Boolean,
        val errorMessage: String
    )

    private val rules = ConcurrentHashMap<String, List<ValidationRule>>()
    private val validationCount = AtomicLong(0)
    private val failureCount = AtomicLong(0)

    fun addRule(field: String, rule: ValidationRule) {
        rules.compute(field) { _, existing ->
            (existing ?: emptyList()) + rule
        }
    }

    fun addRules(field: String, newRules: List<ValidationRule>) {
        rules.compute(field) { _, existing ->
            (existing ?: emptyList()) + newRules
        }
    }

    fun validate(field: String, value: String): ValidationResult {
        validationCount.incrementAndGet()
        val fieldRules = rules[field] ?: return ValidationResult.Valid
        val errors = fieldRules.filterNot { it.validate(value) }.map { it.errorMessage }
        return if (errors.isEmpty()) ValidationResult.Valid
        else {
            failureCount.incrementAndGet()
            ValidationResult.Invalid(errors)
        }
    }

    fun validateAll(data: Map<String, String>): ValidationResult {
        val allErrors = mutableListOf<String>()
        for ((field, value) in data) {
            val result = validate(field, value)
            if (result is ValidationResult.Invalid) {
                allErrors.addAll(result.errors)
            }
        }
        return if (allErrors.isEmpty()) ValidationResult.Valid
        else ValidationResult.Invalid(allErrors)
    }

    fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "totalValidations" to validationCount.get(),
        "totalFailures" to failureCount.get(),
        "activeFields" to rules.size
    )

    companion object {
        val NOT_EMPTY = ValidationRule("not_empty", { it.isNotBlank() }, "Value must not be empty")
        val EMAIL = ValidationRule("email", { it.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) }, "Invalid email format")
        val URL = ValidationRule("url", { it.matches(Regex("^https?://[\\w.-]+(:\\d+)?(/.*)?$")) }, "Invalid URL format")
        val ALPHANUMERIC = ValidationRule("alphanumeric", { it.matches(Regex("^[a-zA-Z0-9_]+$")) }, "Only alphanumeric characters and underscores allowed")
        val NUMERIC = ValidationRule("numeric", { it.matches(Regex("^\\d+$")) }, "Must be numeric")
        val NO_HTML = ValidationRule("no_html", { !it.contains(Regex("<[^>]*>")) }, "HTML tags not allowed")
        val NO_WHITESPACE = ValidationRule("no_whitespace", { !it.contains(Regex("\\s")) }, "Whitespace not allowed")

        fun minLength(min: Int): ValidationRule = ValidationRule("min_length", { it.length >= min }, "Minimum length is $min")
        fun maxLength(max: Int): ValidationRule = ValidationRule("max_length", { it.length <= max }, "Maximum length is $max")
        fun regex(pattern: String, message: String): ValidationRule = ValidationRule("regex", { it.matches(Regex(pattern)) }, message)
    }
}

class SecureConfig(
    private val name: String = "secure-config"
) {
    private val sensitiveKeys = setOf(
        "api_key", "api.secret", "password", "token", "auth_token",
        "access_token", "refresh_token", "secret_key", "private_key",
        "db_password", "jwt_secret", "session_secret", "encryption_key"
    )
    private val configValues = ConcurrentHashMap<String, String>()
    private val accessLog = mutableListOf<AccessEntry>()
    private val maxAccessLogSize = 1000

    data class AccessEntry(
        val key: String,
        val timestamp: Long,
        val caller: String,
        val wasSensitive: Boolean
    )

    fun set(key: String, value: String): SecureConfig {
        configValues[key] = value
        return this
    }

    fun get(key: String): String? {
        val value = configValues[key]
        val caller = Thread.currentThread().stackTrace.getOrNull(2)?.methodName ?: "unknown"
        val entry = AccessEntry(key, System.currentTimeMillis(), caller, key.lowercase() in sensitiveKeys)
        synchronized(accessLog) {
            accessLog.add(entry)
            if (accessLog.size > maxAccessLogSize) accessLog.removeAt(0)
        }
        return if (key.lowercase() in sensitiveKeys) null else value
    }

    fun getSensitive(key: String): String? = configValues[key]

    fun getOrDefault(key: String, default: String): String = get(key) ?: default

    fun getAll(): Map<String, String> = configValues.toMap()

    fun getNonSensitive(): Map<String, String> {
        return configValues.filterKeys { it.lowercase() !in sensitiveKeys }
    }

    fun remove(key: String) { configValues.remove(key) }

    fun clear() { configValues.clear() }

    fun contains(key: String): Boolean = configValues.containsKey(key)

    fun isSensitive(key: String): Boolean = key.lowercase() in sensitiveKeys

    fun getRecentAccess(count: Int = 10): List<AccessEntry> {
        synchronized(accessLog) {
            return accessLog.takeLast(count.coerceAtMost(maxAccessLogSize))
        }
    }
}
