package com.apex.agent.kernel.burst.enhanced.pipeline.error

import java.util.concurrent.ConcurrentHashMap

/**
 * B33: 错误处理链
 *
 * 流水线步骤失败时的分级错误处理：
 * - 6 种错误处理策略
 * - 错误分类匹配
 * - Fallback 技能链
 * - 错误升级机制
 */
class ErrorHandlingChain {

    /**
     * 错误处理策略
     */
    enum class ErrorStrategy {
        ABORT,              // 中止整个流水线
        SKIP_STEP,          // 跳过此步骤，继续下一步
        RETRY_STEP,         // 重试此步骤
        RETRY_WITH_FALLBACK,// 重试 + Fallback
        USE_DEFAULT,        // 使用默认值继续
        ESCALATE            // 升级到更复杂的处理
    }

    /**
     * 错误处理规则
     */
    data class ErrorRule(
        val ruleId: String,
        val name: String,
        val errorPattern: String,           // 错误消息匹配（正则）
        val strategy: ErrorStrategy,
        val maxRetries: Int = 3,
        val retryDelayMs: Long = 1000L,
        val fallbackSkillId: String? = null,
        val defaultValue: String? = null,
        val escalateTo: String? = null      // 升级到的 Skill ID
    )

    /**
     * 错误处理结果
     */
    data class ErrorHandlingResult(
        val ruleId: String?,
        val strategy: ErrorStrategy,
        val action: String,
        val shouldContinue: Boolean,
        val retryCount: Int,
        val fallbackOutput: String?,
        val escalated: Boolean
    )

    /**
     * 错误记录
     */
    data class ErrorRecord(
        val stepId: String,
        val errorType: String,
        val errorMessage: String,
        val ruleApplied: String?,
        val strategy: ErrorStrategy?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val rules = mutableListOf<ErrorRule>()
    private val defaultRule = ErrorRule(
        ruleId = "default",
        name = "默认处理",
        errorPattern = ".*",
        strategy = ErrorStrategy.RETRY_STEP,
        maxRetries = 2
    )
    private val errorHistory = mutableListOf<ErrorRecord>()
    private val stepRetryCounts = ConcurrentHashMap<String, Int>()
    private val maxTotalRetries = 20
    private var totalRetries = 0

    /**
     * 注册错误处理规则
     */
    fun registerRule(rule: ErrorRule) {
        rules.add(rule)
        rules.sortByDescending { it.errorPattern.length }  // 更具体的模式优先
    }

    /**
     * 处理错误
     */
    suspend fun handleError(
        stepId: String,
        error: Throwable,
        currentRetryCount: Int
    ): ErrorHandlingResult {
        val errorType = error::class.simpleName ?: "Unknown"
        val errorMessage = error.message ?: ""
        val matchedRule = findMatchingRule(errorType, errorMessage) ?: defaultRule

        // 检查重试上限
        val stepRetries = stepRetryCounts[stepId] ?: 0
        if (stepRetries >= matchedRule.maxRetries) {
            // 重试耗尽，升级策略
            return handleExhaustedRetries(stepId, error, matchedRule)
        }

        // 检查全局重试上限
        if (totalRetries >= maxTotalRetries) {
            return ErrorHandlingResult(
                ruleId = matchedRule.ruleId,
                strategy = ErrorStrategy.ABORT,
                action = "全局重试上限已达，中止流水线",
                shouldContinue = false,
                retryCount = stepRetries,
                fallbackOutput = null,
                escalated = false
            )
        }

        // 记录错误
        errorHistory.add(ErrorRecord(stepId, errorType, errorMessage, matchedRule.ruleId, matchedRule.strategy))

        // 根据策略处理
        val result = when (matchedRule.strategy) {
            ErrorStrategy.ABORT -> ErrorHandlingResult(
                ruleId = matchedRule.ruleId,
                strategy = ErrorStrategy.ABORT,
                action = "中止流水线: ${matchedRule.name}",
                shouldContinue = false,
                retryCount = stepRetries,
                fallbackOutput = null,
                escalated = false
            )

            ErrorStrategy.SKIP_STEP -> ErrorHandlingResult(
                ruleId = matchedRule.ruleId,
                strategy = ErrorStrategy.SKIP_STEP,
                action = "跳过步骤 $stepId",
                shouldContinue = true,
                retryCount = stepRetries,
                fallbackOutput = matchedRule.defaultValue,
                escalated = false
            )

            ErrorStrategy.RETRY_STEP -> {
                totalRetries++
                stepRetryCounts[stepId] = stepRetries + 1
                kotlinx.coroutines.delay(matchedRule.retryDelayMs)
                ErrorHandlingResult(
                    ruleId = matchedRule.ruleId,
                    strategy = ErrorStrategy.RETRY_STEP,
                    action = "重试步骤 $stepId (第 ${stepRetries + 1} 次)",
                    shouldContinue = true,
                    retryCount = stepRetries + 1,
                    fallbackOutput = null,
                    escalated = false
                )
            }

            ErrorStrategy.RETRY_WITH_FALLBACK -> {
                totalRetries++
                stepRetryCounts[stepId] = stepRetries + 1
                kotlinx.coroutines.delay(matchedRule.retryDelayMs)
                ErrorHandlingResult(
                    ruleId = matchedRule.ruleId,
                    strategy = ErrorStrategy.RETRY_WITH_FALLBACK,
                    action = "重试步骤 $stepId + 使用 Fallback: ${matchedRule.fallbackSkillId}",
                    shouldContinue = true,
                    retryCount = stepRetries + 1,
                    fallbackOutput = matchedRule.fallbackSkillId,
                    escalated = false
                )
            }

            ErrorStrategy.USE_DEFAULT -> ErrorHandlingResult(
                ruleId = matchedRule.ruleId,
                strategy = ErrorStrategy.USE_DEFAULT,
                action = "使用默认值: ${matchedRule.defaultValue}",
                shouldContinue = true,
                retryCount = stepRetries,
                fallbackOutput = matchedRule.defaultValue,
                escalated = false
            )

            ErrorStrategy.ESCALATE -> ErrorHandlingResult(
                ruleId = matchedRule.ruleId,
                strategy = ErrorStrategy.ESCALATE,
                action = "升级到: ${matchedRule.escalateTo}",
                shouldContinue = true,
                retryCount = stepRetries,
                fallbackOutput = matchedRule.escalateTo,
                escalated = true
            )
        }

        return result
    }

    /**
     * 重试耗尽处理
     */
    private fun handleExhaustedRetries(stepId: String, error: Throwable, rule: ErrorRule): ErrorHandlingResult {
        return when {
            rule.fallbackSkillId != null -> ErrorHandlingResult(
                ruleId = rule.ruleId,
                strategy = ErrorStrategy.USE_DEFAULT,
                action = "重试耗尽，使用 Fallback: ${rule.fallbackSkillId}",
                shouldContinue = true,
                retryCount = stepRetryCounts[stepId] ?: 0,
                fallbackOutput = rule.fallbackSkillId,
                escalated = false
            )
            rule.defaultValue != null -> ErrorHandlingResult(
                ruleId = rule.ruleId,
                strategy = ErrorStrategy.USE_DEFAULT,
                action = "重试耗尽，使用默认值",
                shouldContinue = true,
                retryCount = stepRetryCounts[stepId] ?: 0,
                fallbackOutput = rule.defaultValue,
                escalated = false
            )
            else -> ErrorHandlingResult(
                ruleId = rule.ruleId,
                strategy = ErrorStrategy.ABORT,
                action = "重试耗尽，中止流水线: ${error.message}",
                shouldContinue = false,
                retryCount = stepRetryCounts[stepId] ?: 0,
                fallbackOutput = null,
                escalated = false
            )
        }
    }

    /**
     * 重置步骤重试计数
     */
    fun resetStepRetries(stepId: String) {
        stepRetryCounts.remove(stepId)
    }

    /**
     * 查找匹配规则
     */
    private fun findMatchingRule(errorType: String, errorMessage: String): ErrorRule? {
        for (rule in rules) {
            try {
                val pattern = Regex(rule.errorPattern, RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(errorMessage) || pattern.containsMatchIn(errorType)) {
                    return rule
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * 获取错误历史
     */
    fun getErrorHistory(): List<ErrorRecord> = errorHistory.toList()

    /**
     * 获取统计
     */
    fun getStats(): ErrorStats {
        return ErrorStats(
            totalErrors = errorHistory.size,
            totalRetries = totalRetries,
            byStrategy = errorHistory.groupingBy { it.strategy ?: ErrorStrategy.ABORT }.eachCount(),
            byStep = errorHistory.groupingBy { it.stepId }.eachCount()
        )
    }

    data class ErrorStats(
        val totalErrors: Int,
        val totalRetries: Int,
        val byStrategy: Map<ErrorStrategy, Int>,
        val byStep: Map<String, Int>
    )

    /**
     * 清空
     */
    fun clear() {
        errorHistory.clear()
        stepRetryCounts.clear()
        totalRetries = 0
    }

    init {
        // 注册内置规则
        registerRule(ErrorRule(
            ruleId = "timeout_rule",
            name = "超时处理",
            errorPattern = "timeout|timed out|超时",
            strategy = ErrorStrategy.RETRY_STEP,
            maxRetries = 3,
            retryDelayMs = 2000L
        ))
        registerRule(ErrorRule(
            ruleId = "network_rule",
            name = "网络错误处理",
            errorPattern = "network|connection|ioexception|网络",
            strategy = ErrorStrategy.RETRY_STEP,
            maxRetries = 3,
            retryDelayMs = 3000L
        ))
        registerRule(ErrorRule(
            ruleId = "oom_rule",
            name = "内存不足处理",
            errorPattern = "out of memory|oom|OutOfMemory",
            strategy = ErrorStrategy.SKIP_STEP,
            maxRetries = 0,
            defaultValue = "[内存不足，跳过此步骤]"
        ))
        registerRule(ErrorRule(
            ruleId = "permission_rule",
            name = "权限错误处理",
            errorPattern = "permission|denied|security|权限",
            strategy = ErrorStrategy.ABORT,
            maxRetries = 0
        ))
        registerRule(ErrorRule(
            ruleId = "validation_rule",
            name = "校验错误处理",
            errorPattern = "validation|invalid|illegal|校验",
            strategy = ErrorStrategy.USE_DEFAULT,
            maxRetries = 1,
            defaultValue = "[校验失败，使用默认值]"
        ))
    }
}
