package com.apex.agent.kernel.burst.enhanced.quota

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B9: 资源配额管理（Resource Quota Management）
 *
 * 多维度资源配额：CPU/网络/磁盘/LLM token/调用次数
 */
class QuotaManager {

    data class ResourceQuota(
        val cpuTimeMsPerMin: Long = 10_000L,
        val networkKbPerMin: Long = 100_000L,
        val diskIopsPerSec: Int = 1000,
        val llmTokensPerHour: Long = 1_000_000L,
        val skillInvocationsPerDay: Int = 1000,
        val maxConcurrentTasks: Int = 10
    )

    data class QuotaUsage(
        val cpuTimeMsUsed: Long,
        val networkKbUsed: Long,
        val diskIopsUsed: Int,
        val llmTokensUsed: Long,
        val skillInvocationsUsed: Int,
        val currentConcurrentTasks: Int
    ) {
        fun percentage(quota: ResourceQuota): Map<String, Float> = mapOf(
            "cpu" to cpuTimeMsUsed.toFloat() / quota.cpuTimeMsPerMin,
            "network" to networkKbUsed.toFloat() / quota.networkKbPerMin,
            "disk" to diskIopsUsed.toFloat() / quota.diskIopsPerSec,
            "llm" to llmTokensUsed.toFloat() / quota.llmTokensPerHour,
            "invocations" to skillInvocationsUsed.toFloat() / quota.skillInvocationsPerDay,
            "concurrent" to currentConcurrentTasks.toFloat() / quota.maxConcurrentTasks
        )
    }

    data class QuotaLease(
        val leaseId: String,
        val taskId: String,
        val cpuTimeMs: Long,
        val networkKb: Long,
        val llmTokens: Long,
        val acquiredAt: Long
    )

    enum class QuotaExceedAction { ALLOW, THROTTLE, REJECT, QUEUE }

    private var quota = ResourceQuota()
    private val cpuUsed = AtomicLong(0)
    private val networkUsed = AtomicLong(0)
    private val llmTokensUsed = AtomicLong(0)
    private val invocationsUsed = AtomicLong(0)
    private val activeLeases = ConcurrentHashMap<String, QuotaLease>()
    private val _usage = MutableStateFlow(QuotaUsage(0, 0, 0, 0, 0, 0))
    val usage: StateFlow<QuotaUsage> = _usage.asStateFlow()
    private val exceedAction = QuotaExceedAction.THROTTLE

    fun setQuota(newQuota: ResourceQuota) { quota = newQuota }
    fun setExceedAction(action: QuotaExceedAction) { exceedAction = action }

    fun acquire(taskId: String, cpu: Long = 0, network: Long = 0, tokens: Long = 0): QuotaLease? {
        if (activeLeases.size >= quota.maxConcurrentTasks && exceedAction == QuotaExceedAction.REJECT) {
            return null
        }
        val lease = QuotaLease(
            leaseId = "lease_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            taskId = taskId, cpuTimeMs = cpu, networkKb = network, llmTokens = tokens,
            acquiredAt = System.currentTimeMillis()
        )
        activeLeases[lease.leaseId] = lease
        cpuUsed.addAndGet(cpu)
        networkUsed.addAndGet(network)
        llmTokensUsed.addAndGet(tokens)
        invocationsUsed.incrementAndGet()
        updateUsage()
        return lease
    }

    fun release(lease: QuotaLease) {
        activeLeases.remove(lease.leaseId)
        updateUsage()
    }

    fun recordTokenUsage(tokens: Long) {
        llmTokensUsed.addAndGet(tokens)
        updateUsage()
    }

    fun checkQuota(): QuotaStatus {
        val usage = _usage.value
        val percentages = usage.percentage(quota)
        val exceeded = percentages.filter { it.value > 1.0f }.keys
        return QuotaStatus(exceeded.isNotEmpty(), exceeded, percentages)
    }

    data class QuotaStatus(val exceeded: Boolean, val exceededResources: Set<String>, val percentages: Map<String, Float>)

    fun reset() {
        cpuUsed.set(0); networkUsed.set(0); llmTokensUsed.set(0); invocationsUsed.set(0)
        activeLeases.clear()
        updateUsage()
    }

    private fun updateUsage() {
        _usage.value = QuotaUsage(
            cpuTimeMsUsed = cpuUsed.get(), networkKbUsed = networkUsed.get(),
            diskIopsUsed = 0, llmTokensUsed = llmTokensUsed.get(),
            skillInvocationsUsed = invocationsUsed.get().toInt(),
            currentConcurrentTasks = activeLeases.size
        )
    }
}
