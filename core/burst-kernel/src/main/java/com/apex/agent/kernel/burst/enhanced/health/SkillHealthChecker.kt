package com.apex.agent.kernel.burst.enhanced.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

/**
 * B46: 技能健康检查器
 *
 * 定期检查技能健康状态：
 * - 心跳检测
 * - 响应时间监控
 * - 错误率监控
 * - 自动标记不健康技能
 */
class SkillHealthChecker(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val checkIntervalMs: Long = 60_000L,
    private val unhealthyErrorThreshold: Float = 0.5f,
    private val unhealthyLatencyThreshold: Long = 30_000L
) {

    enum class HealthState { HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN }

    data class SkillHealth(
        val skillId: String,
        val state: HealthState,
        val lastCheckAt: Long,
        val successRate: Float,
        val avgLatencyMs: Long,
        val consecutiveFailures: Int,
        val issues: List<String>
    )

    data class HealthCheckResult(
        val skillId: String,
        val healthy: Boolean,
        val latencyMs: Long,
        val issues: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun interface HealthCheckFunction {
        suspend fun check(skillId: String): HealthCheckResult
    }

    private val healthStates = ConcurrentHashMap<String, SkillHealth>()
    private val checkFunctions = ConcurrentHashMap<String, HealthCheckFunction>()
    private val _unhealthySkills = MutableStateFlow<Set<String>>(emptySet())
    val unhealthySkills: StateFlow<Set<String>> = _unhealthySkills.asStateFlow()
    private var checkJob: kotlinx.coroutines.Job? = null

    /**
     * 注册技能健康检查
     */
    fun registerSkill(skillId: String, checkFunction: HealthCheckFunction) {
        checkFunctions[skillId] = checkFunction
        healthStates[skillId] = SkillHealth(skillId, HealthState.UNKNOWN, 0, 0f, 0, 0, emptyList())
    }

    /**
     * 注销技能
     */
    fun unregisterSkill(skillId: String) {
        checkFunctions.remove(skillId)
        healthStates.remove(skillId)
    }

    /**
     * 执行单次健康检查
     */
    suspend fun checkSkill(skillId: String): SkillHealth? {
        val checkFn = checkFunctions[skillId] ?: return null
        val start = System.currentTimeMillis()
        val result = try { checkFn.check(skillId) } catch (e: Exception) {
            HealthCheckResult(skillId, false, System.currentTimeMillis() - start, listOf("检查异常: ${e.message}"))
        }

        val current = healthStates[skillId]
        val consecutiveFailures = if (result.healthy) 0 else (current?.consecutiveFailures ?: 0) + 1

        val newState = when {
            !result.healthy && consecutiveFailures >= 3 -> HealthState.UNHEALTHY
            !result.healthy -> HealthState.DEGRADED
            result.latencyMs > unhealthyLatencyThreshold -> HealthState.DEGRADED
            else -> HealthState.HEALTHY
        }

        val issues = mutableListOf<String>()
        if (!result.healthy) issues.add("健康检查失败")
        if (result.latencyMs > unhealthyLatencyThreshold) issues.add("延迟过高: ${result.latencyMs}ms")
        if (consecutiveFailures > 0) issues.add("连续失败: $consecutiveFailures 次")

        val health = SkillHealth(
            skillId = skillId, state = newState,
            lastCheckAt = System.currentTimeMillis(),
            successRate = if (result.healthy) 1f else 0f,
            avgLatencyMs = result.latencyMs,
            consecutiveFailures = consecutiveFailures,
            issues = issues
        )
        healthStates[skillId] = health

        updateUnhealthySet()
        return health
    }

    /**
     * 检查所有技能
     */
    suspend fun checkAll(): Map<String, SkillHealth> {
        for (skillId in checkFunctions.keys) {
            checkSkill(skillId)
        }
        return healthStates.toMap()
    }

    /**
     * 启动定期检查
     */
    fun startPeriodicCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (true) {
                checkAll()
                delay(checkIntervalMs)
            }
        }
    }

    fun stopPeriodicCheck() {
        checkJob?.cancel()
    }

    fun getHealth(skillId: String): SkillHealth? = healthStates[skillId]
    fun getAllHealth(): List<SkillHealth> = healthStates.values.toList()
    fun getHealthySkills(): List<String> = healthStates.values.filter { it.state == HealthState.HEALTHY }.map { it.skillId }
    fun getUnhealthySkillsList(): List<String> = healthStates.values.filter { it.state == HealthState.UNHEALTHY }.map { it.skillId }

    fun shutdown() {
        checkJob?.cancel()
        scope.cancel()
    }

    private fun updateUnhealthySet() {
        _unhealthySkills.value = healthStates.values
            .filter { it.state == HealthState.UNHEALTHY }
            .map { it.skillId }
            .toSet()
    }
}
