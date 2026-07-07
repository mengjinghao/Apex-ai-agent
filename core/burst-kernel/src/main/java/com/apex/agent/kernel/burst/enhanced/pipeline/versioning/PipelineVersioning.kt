package com.apex.agent.kernel.burst.enhanced.pipeline.versioning

import com.apex.agent.kernel.burst.enhanced.pipeline.orchestrator.PipelineOrchestrator
import java.util.concurrent.ConcurrentHashMap

/**
 * B38: 流水线版本管理
 *
 * 流水线定义版本化：
 * - 版本追踪
 * - A/B 测试
 * - 回滚
 * - 变更对比
 */
class PipelineVersioning {

    data class PipelineVersion(
        val pipelineId: String,
        val version: Int,
        val definition: PipelineOrchestrator.PipelineDefinition,
        val createdAt: Long = System.currentTimeMillis(),
        val createdBy: String,
        val changeDescription: String,
        val isActive: Boolean = false,
        val abTestPercentage: Int = 0
    )

    data class VersionDiff(
        val fromVersion: Int,
        val toVersion: Int,
        val addedSteps: List<String>,
        val removedSteps: List<String>,
        val modifiedSteps: List<String>,
        val configChanges: List<String>
    )

    data class ABTestConfig(
        val pipelineId: String,
        val versionA: Int,
        val versionB: Int,
        val splitPercentage: Int  // versionB 的流量百分比
    )

    private val versions = ConcurrentHashMap<String, MutableList<PipelineVersion>>()
    private val abTests = ConcurrentHashMap<String, ABTestConfig>()

    /**
     * 发布新版本
     */
    fun publish(
        pipelineId: String,
        definition: PipelineOrchestrator.PipelineDefinition,
        createdBy: String = "system",
        changeDescription: String = ""
    ): PipelineVersion {
        val pipelineVersions = versions.computeIfAbsent(pipelineId) { mutableListOf() }

        // 旧版本标记为非活跃
        pipelineVersions.find { it.isActive }?.let { v ->
            val idx = pipelineVersions.indexOf(v)
            pipelineVersions[idx] = v.copy(isActive = false)
        }

        val version = PipelineVersion(
            pipelineId = pipelineId,
            version = (pipelineVersions.maxOfOrNull { it.version } ?: 0) + 1,
            definition = definition,
            createdBy = createdBy,
            changeDescription = changeDescription,
            isActive = true
        )
        pipelineVersions.add(version)
        return version
    }

    /**
     * 回滚到指定版本
     */
    fun rollback(pipelineId: String, targetVersion: Int, reason: String = ""): PipelineVersion? {
        val pipelineVersions = versions[pipelineId] ?: return null
        val target = pipelineVersions.find { it.version == targetVersion } ?: return null

        // 创建新版本（内容是旧版本的）
        return publish(
            pipelineId = pipelineId,
            definition = target.definition,
            createdBy = "system",
            changeDescription = "回滚到 v$targetVersion: $reason"
        )
    }

    /**
     * 获取活跃版本
     */
    fun getActive(pipelineId: String): PipelineVersion? {
        return versions[pipelineId]?.find { it.isActive }
    }

    /**
     * 获取版本历史
     */
    fun getHistory(pipelineId: String): List<PipelineVersion> {
        return versions[pipelineId]?.toList() ?: emptyList()
    }

    /**
     * 比较两个版本
     */
    fun diff(pipelineId: String, versionA: Int, versionB: Int): VersionDiff? {
        val pipelineVersions = versions[pipelineId] ?: return null
        val vA = pipelineVersions.find { it.version == versionA } ?: return null
        val vB = pipelineVersions.find { it.version == versionB } ?: return null

        val stepsA = vA.definition.steps.map { it.id to it.skillId }.toMap()
        val stepsB = vB.definition.steps.map { it.id to it.skillId }.toMap()

        val added = stepsB.keys - stepsA.keys
        val removed = stepsA.keys - stepsB.keys
        val modified = stepsA.keys.intersect(stepsB.keys).filter { stepsA[it] != stepsB[it] }

        val configChanges = mutableListOf<String>()
        if (vA.definition.config.maxRetries != vB.definition.config.maxRetries)
            configChanges.add("maxRetries: ${vA.definition.config.maxRetries} → ${vB.definition.config.maxRetries}")
        if (vA.definition.config.timeoutMs != vB.definition.config.timeoutMs)
            configChanges.add("timeoutMs: ${vA.definition.config.timeoutMs} → ${vB.definition.config.timeoutMs}")

        return VersionDiff(versionA, versionB, added.toList(), removed.toList(), modified.toList(), configChanges)
    }

    /**
     * 设置 A/B 测试
     */
    fun setABTest(config: ABTestConfig) {
        abTests[config.pipelineId] = config
    }

    /**
     * 根据 A/B 测试选择版本
     */
    fun selectVersion(pipelineId: String): PipelineVersion? {
        val abTest = abTests[pipelineId]
        if (abTest != null) {
            val useB = (Math.random() * 100) < abTest.splitPercentage
            val targetVersion = if (useB) abTest.versionB else abTest.versionA
            return versions[pipelineId]?.find { it.version == targetVersion }
        }
        return getActive(pipelineId)
    }

    /**
     * 获取统计
     */
    fun getStats(): VersioningStats {
        val allVersions = versions.values.flatMap { it }
        return VersioningStats(
            totalPipelines = versions.size,
            totalVersions = allVersions.size,
            activeABTests = abTests.size,
            avgVersionsPerPipeline = if (versions.isNotEmpty()) allVersions.size / versions.size else 0
        )
    }

    data class VersioningStats(
        val totalPipelines: Int,
        val totalVersions: Int,
        val activeABTests: Int,
        val avgVersionsPerPipeline: Int
    )
}
