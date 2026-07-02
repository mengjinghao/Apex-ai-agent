package com.apex.agent.kernel.burst.enhanced.pipeline.scheduler

import com.apex.agent.kernel.burst.enhanced.pipeline.orchestrator.PipelineOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B39: 流水线调度器
 *
 * 流水线定时/条件触发：
 * - 定时触发（Cron/Interval/Once）
 * - 事件触发
 * - 依赖触发
 * - 限流触发
 */
class PipelineScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    enum class TriggerType {
        INTERVAL,      // 固定间隔
        CRON,          // Cron 表达式（简化）
        ONCE,          // 一次性
        EVENT,         // 事件触发
        DEPENDENCY,    // 依赖完成触发
        MANUAL         // 手动触发
    }

    data class ScheduleRule(
        val ruleId: String,
        val pipelineDefinition: PipelineOrchestrator.PipelineDefinition,
        val triggerType: TriggerType,
        val intervalMs: Long? = null,
        val cronExpression: String? = null,
        val eventName: String? = null,
        val dependsOnPipelineId: String? = null,
        val maxInvocations: Int? = null,
        val enabled: Boolean = true,
        val initialInput: String = "",
        val inputProvider: (() -> String)? = null
    )

    data class ScheduledExecution(
        val executionId: String,
        val ruleId: String,
        val pipelineId: String,
        val triggeredAt: Long,
        val triggerType: TriggerType,
        val input: String
    )

    data class SchedulerStats(
        val totalRules: Int,
        val activeRules: Int,
        val totalExecutions: Long,
        val executionCountByRule: Map<String, Long>
    )

    private val rules = ConcurrentHashMap<String, ScheduleRule>()
    private val scheduledJobs = ConcurrentHashMap<String, Job>()
    private val executionCounter = AtomicLong(0)
    private val executionCountByRule = ConcurrentHashMap<String, AtomicLong>()
    private val _executionEvents = MutableSharedFlow<ScheduledExecution>(extraBufferCapacity = 64)
    val executionEvents: SharedFlow<ScheduledExecution> = _executionEvents.asSharedFlow()

    /**
     * 注册调度规则
     */
    fun registerRule(rule: ScheduleRule) {
        rules[rule.ruleId] = rule
        if (rule.enabled) {
            scheduleRule(rule)
        }
    }

    /**
     * 注册固定间隔触发
     */
    fun registerInterval(
        ruleId: String,
        definition: PipelineOrchestrator.PipelineDefinition,
        intervalMs: Long,
        initialInput: String = "",
        maxInvocations: Int? = null
    ) {
        registerRule(ScheduleRule(
            ruleId = ruleId,
            pipelineDefinition = definition,
            triggerType = TriggerType.INTERVAL,
            intervalMs = intervalMs,
            initialInput = initialInput,
            maxInvocations = maxInvocations
        ))
    }

    /**
     * 注册一次性触发
     */
    fun registerOnce(
        ruleId: String,
        definition: PipelineOrchestrator.PipelineDefinition,
        delayMs: Long,
        initialInput: String = ""
    ) {
        registerRule(ScheduleRule(
            ruleId = ruleId,
            pipelineDefinition = definition,
            triggerType = TriggerType.ONCE,
            intervalMs = delayMs,
            initialInput = initialInput,
            maxInvocations = 1
        ))
    }

    /**
     * 注册事件触发
     */
    fun registerEvent(
        ruleId: String,
        definition: PipelineOrchestrator.PipelineDefinition,
        eventName: String,
        inputProvider: (() -> String)? = null
    ) {
        registerRule(ScheduleRule(
            ruleId = ruleId,
            pipelineDefinition = definition,
            triggerType = TriggerType.EVENT,
            eventName = eventName,
            inputProvider = inputProvider
        ))
    }

    /**
     * 触发事件
     */
    suspend fun triggerEvent(eventName: String, input: String = "") {
        rules.values.filter { it.enabled && it.triggerType == TriggerType.EVENT && it.eventName == eventName }
            .forEach { rule ->
                val actualInput = rule.inputProvider?.invoke() ?: input
                triggerExecution(rule, actualInput)
            }
    }

    /**
     * 手动触发
     */
    suspend fun triggerManual(ruleId: String, input: String = ""): Boolean {
        val rule = rules[ruleId] ?: return false
        triggerExecution(rule, input)
        return true
    }

    /**
     * 暂停规则
     */
    fun pause(ruleId: String) {
        rules[ruleId]?.let { rule ->
            rules[ruleId] = rule.copy(enabled = false)
            scheduledJobs[ruleId]?.cancel()
            scheduledJobs.remove(ruleId)
        }
    }

    /**
     * 恢复规则
     */
    fun resume(ruleId: String) {
        rules[ruleId]?.let { rule ->
            rules[ruleId] = rule.copy(enabled = true)
            scheduleRule(rules[ruleId]!!)
        }
    }

    /**
     * 移除规则
     */
    fun remove(ruleId: String) {
        rules.remove(ruleId)
        scheduledJobs[ruleId]?.cancel()
        scheduledJobs.remove(ruleId)
    }

    /**
     * 获取所有规则
     */
    fun listRules(): List<ScheduleRule> = rules.values.toList()

    /**
     * 获取统计
     */
    fun getStats(): SchedulerStats {
        return SchedulerStats(
            totalRules = rules.size,
            activeRules = rules.values.count { it.enabled },
            totalExecutions = executionCounter.get(),
            executionCountByRule = executionCountByRule.mapValues { it.value.get() }
        )
    }

    /**
     * 关闭
     */
    fun shutdown() {
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
        scope.cancel()
    }

    // ============ 内部方法 ============

    private fun scheduleRule(rule: ScheduleRule) {
        when (rule.triggerType) {
            TriggerType.INTERVAL -> scheduleInterval(rule)
            TriggerType.ONCE -> scheduleOnce(rule)
            TriggerType.CRON -> scheduleInterval(rule)  // 简化：用 interval 代替
            TriggerType.EVENT -> {}  // 事件触发不需要主动调度
            TriggerType.DEPENDENCY -> {}  // 依赖触发由 onPipelineCompleted 调用
            TriggerType.MANUAL -> {}  // 手动触发
        }
    }

    private fun scheduleInterval(rule: ScheduleRule) {
        val interval = rule.intervalMs ?: return
        var invocations = 0
        val job = scope.launch {
            delay(interval)  // 首次延迟
            while (isActive && (rule.maxInvocations == null || invocations < rule.maxInvocations)) {
                val input = rule.inputProvider?.invoke() ?: rule.initialInput
                triggerExecution(rule, input)
                invocations++
                if (rule.maxInvocations != null && invocations >= rule.maxInvocations) break
                delay(interval)
            }
        }
        scheduledJobs[rule.ruleId] = job
    }

    private fun scheduleOnce(rule: ScheduleRule) {
        val delay = rule.intervalMs ?: 0
        val job = scope.launch {
            delay(delay)
            val input = rule.inputProvider?.invoke() ?: rule.initialInput
            triggerExecution(rule, input)
        }
        scheduledJobs[rule.ruleId] = job
    }

    private suspend fun triggerExecution(rule: ScheduleRule, input: String) {
        val execution = ScheduledExecution(
            executionId = "exec_${executionCounter.incrementAndGet()}",
            ruleId = rule.ruleId,
            pipelineId = rule.pipelineDefinition.id,
            triggeredAt = System.currentTimeMillis(),
            triggerType = rule.triggerType,
            input = input
        )
        executionCountByRule.computeIfAbsent(rule.ruleId) { AtomicLong(0) }.incrementAndGet()
        _executionEvents.emit(execution)
        // 实际执行由外部 Orchestrator 订阅 executionEvents 后执行
    }
}
