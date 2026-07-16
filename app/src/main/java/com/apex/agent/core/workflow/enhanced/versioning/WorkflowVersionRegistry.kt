package com.apex.agent.core.workflow.enhanced.versioning

import com.apex.agent.core.workflow.enhanced.model.EnhancedWorkflow
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 工作流版本管理 - 支持灰度发布与回滚
 *
 * 参照 Temporal Worker Versioning（Build ID + Ramp %）
 * 与 Airflow DAG 版本管理
 */

/**
 * 版本状态
 */
enum class VersionStatus {
    DRAFT,        // 草稿
    ACTIVE,       // 100% 流量
    RAMPING,      // 灰度中（0-99%）
    DEPRECATED,   // 已弃用（仍可执行存量，不接受新触发）
    ARCHIVED      // 已归档（不可执行）
}

/**
 * 工作流版本
 */
data class WorkflowVersion(
    val workflowId: String,
    val version: Int,                              // 单调递增
    val definition: EnhancedWorkflow,
    val createdAt: Long = System.currentTimeMillis(),
    val status: VersionStatus = VersionStatus.DRAFT,
    val rampPercentage: Int = 0,                   // 0-100
    val changelog: String = "",
    val createdBy: String = "system"
)

/**
 * 版本注册表接口
 */
interface WorkflowVersionRegistry {
    /** 发布新版本 */
    suspend fun publish(workflowId: String, definition: EnhancedWorkflow, changelog: String = ""): WorkflowVersion

    /** 设置灰度比例 */
    suspend fun ramp(workflowId: String, version: Int, percentage: Int): WorkflowVersion

    /** 完全切换到某版本（100%） */
    suspend fun activate(workflowId: String, version: Int): WorkflowVersion

    /** 弃用版本 */
    suspend fun deprecate(workflowId: String, version: Int): WorkflowVersion

    /** 归档版本 */
    suspend fun archive(workflowId: String, version: Int): WorkflowVersion

    /** 按灰度比例解析当前应该使用的版本 */
    suspend fun resolve(workflowId: String): WorkflowVersion?

    /** 获取特定版本 */
    suspend fun get(workflowId: String, version: Int): WorkflowVersion?

    /** 列出某工作流的所有版本 */
    suspend fun list(workflowId: String): List<WorkflowVersion>

    /** 回滚到上一活跃版本 */
    suspend fun rollback(workflowId: String): WorkflowVersion?
}

/**
 * 内存版本注册表实现
 */
class InMemoryVersionRegistry : WorkflowVersionRegistry {

    private val versions = ConcurrentHashMap<String, MutableList<WorkflowVersion>>()

    override suspend fun publish(
        workflowId: String,
        definition: EnhancedWorkflow,
        changelog: String
    ): WorkflowVersion {
        val list = versions.computeIfAbsent(workflowId) { mutableListOf() }
        val nextVersion = (list.maxOfOrNull { it.version } ?: 0) + 1
        val updated = definition.copy(version = nextVersion)
        val v = WorkflowVersion(
            workflowId = workflowId,
            version = nextVersion,
            definition = updated,
            changelog = changelog
        )
        synchronized(list) { list.add(v) }
        return v
    }

    override suspend fun ramp(workflowId: String, version: Int, percentage: Int): WorkflowVersion {
        val list = versions[workflowId] ?: error("工作流 $workflowId 不存在")
        val pct = percentage.coerceIn(0, 100)
        return synchronized(list) {
            val v = list.find { it.version == version } ?: error("版本 $version 不存在")
            val updated = v.copy(status = VersionStatus.RAMPING, rampPercentage = pct)
            val idx = list.indexOf(v)
            list[idx] = updated
            updated
        }
    }

    override suspend fun activate(workflowId: String, version: Int): WorkflowVersion {
        val list = versions[workflowId] ?: error("工作流 $workflowId 不存在")
        return synchronized(list) {
            // 将其他 ACTIVE/RAMPING 降级为 DEPRECATED
            list.mapIndexed { i, v ->
                if (v.status == VersionStatus.ACTIVE || v.status == VersionStatus.RAMPING) {
                    list[i] = v.copy(status = VersionStatus.DEPRECATED, rampPercentage = 0)
                }
            }
            val target = list.find { it.version == version } ?: error("版本 $version 不存在")
            val idx = list.indexOf(target)
            val updated = target.copy(status = VersionStatus.ACTIVE, rampPercentage = 100)
            list[idx] = updated
            updated
        }
    }

    override suspend fun deprecate(workflowId: String, version: Int): WorkflowVersion {
        return updateStatus(workflowId, version, VersionStatus.DEPRECATED) { it.copy(rampPercentage = 0) }
    }

    override suspend fun archive(workflowId: String, version: Int): WorkflowVersion {
        return updateStatus(workflowId, version, VersionStatus.ARCHIVED) { it.copy(rampPercentage = 0) }
    }

    override suspend fun resolve(workflowId: String): WorkflowVersion? {
        val list = versions[workflowId]?.toList() ?: return null
        // 找所有可执行版本（ACTIVE 或 RAMPING）
        val candidates = list.filter {
            it.status == VersionStatus.ACTIVE || it.status == VersionStatus.RAMPING
        }.sortedByDescending { it.version }

        if (candidates.isEmpty()) return null
        // 如果只有一个，直接返回
        if (candidates.size == 1) return candidates.first()

        // 按灰度比例随机选择
        val roll = Random.nextInt(100)
        var cumulative = 0
        for (v in candidates) {
            cumulative += if (v.status == VersionStatus.ACTIVE) {
                100 - candidates.filter { it.status == VersionStatus.RAMPING }.sumOf { it.rampPercentage }
            } else {
                v.rampPercentage
            }
            if (roll < cumulative) return v
        }
        return candidates.first()
    }

    override suspend fun get(workflowId: String, version: Int): WorkflowVersion? {
        return versions[workflowId]?.find { it.version == version }
    }

    override suspend fun list(workflowId: String): List<WorkflowVersion> {
        return versions[workflowId]?.toList()?.sortedByDescending { it.version } ?: emptyList()
    }

    override suspend fun rollback(workflowId: String): WorkflowVersion? {
        val list = versions[workflowId]?.toList() ?: return null
        // 找到当前 ACTIVE 版本的前一个非 ARCHIVED 版本
        val active = list.find { it.status == VersionStatus.ACTIVE } ?: return null
        val prev = list.filter {
            it.version < active.version && it.status != VersionStatus.ARCHIVED
        }.maxByOrNull { it.version } ?: return null
        return activate(workflowId, prev.version)
    }

    private fun updateStatus(
        workflowId: String,
        version: Int,
        newStatus: VersionStatus,
        transform: (WorkflowVersion) -> WorkflowVersion
    ): WorkflowVersion {
        val list = versions[workflowId] ?: error("工作流 $workflowId 不存在")
        return synchronized(list) {
            val idx = list.indexOfFirst { it.version == version }
            if (idx < 0) error("版本 $version 不存在")
            val updated = transform(list[idx]).copy(status = newStatus)
            list[idx] = updated
            updated
        }
    }
}

/**
 * 版本注册表持有者
 */
object VersionRegistryHolder {
    @Volatile
    private var instance: WorkflowVersionRegistry = InMemoryVersionRegistry()

    fun get(): WorkflowVersionRegistry = instance

    fun set(registry: WorkflowVersionRegistry) {
        instance = registry
    }
}
