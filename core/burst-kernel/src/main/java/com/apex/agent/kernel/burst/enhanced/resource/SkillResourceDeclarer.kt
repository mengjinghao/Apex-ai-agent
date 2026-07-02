package com.apex.agent.kernel.burst.enhanced.resource

import java.util.concurrent.ConcurrentHashMap

/**
 * B54: 技能资源声明器
 *
 * 技能声明自己的资源需求：
 * - CPU/内存/网络/存储
 * - API Key/权限
 * - 模型/数据
 * - 运行时依赖
 */
class SkillResourceDeclarer {

    data class ResourceDeclaration(
        val skillId: String,
        val cpu: CpuRequirement = CpuRequirement(),
        val memory: MemoryRequirement = MemoryRequirement(),
        val network: NetworkRequirement = NetworkRequirement(),
        val storage: StorageRequirement = StorageRequirement(),
        val apiKeys: Set<String> = emptySet(),
        val permissions: Set<String> = emptySet(),
        val models: Set<String> = emptySet(),
        val dependencies: Set<String> = emptySet(),
        val runtimeRequirements: Set<String> = emptySet()
    )

    data class CpuRequirement(val minCores: Int = 1, val preferredCores: Int = 2, val maxLoadPercent: Int = 80)
    data class MemoryRequirement(val minMb: Int = 50, val preferredMb: Int = 100, val maxMb: Int = 500)
    data class NetworkRequirement(val required: Boolean = false, val minBandwidthKbps: Int = 0, val maxLatencyMs: Int = 0)
    data class StorageRequirement(val minMb: Int = 0, val preferredMb: Int = 0, val cacheDir: String? = null)

    data class ResourceAvailability(
        val availableCpuCores: Int,
        val availableMemoryMb: Int,
        val hasNetwork: Boolean,
        val networkLatencyMs: Long,
        val availableStorageMb: Long,
        val availableApiKeys: Set<String>,
        val grantedPermissions: Set<String>,
        val loadedModels: Set<String>,
        val activeDependencies: Set<String>
    )

    data class ResourceCheckResult(
        val skillId: String,
        val canRun: Boolean,
        val missing: List<String>,
        val warnings: List<String>,
        val allocation: ResourceAllocation?
    )

    data class ResourceAllocation(
        val allocatedCpuCores: Int,
        val allocatedMemoryMb: Int,
        val allocatedStorageMb: Long,
        val networkPriority: Int
    )

    private val declarations = ConcurrentHashMap<String, ResourceDeclaration>()
    private val allocations = ConcurrentHashMap<String, ResourceAllocation>()

    fun declare(declaration: ResourceDeclaration) {
        declarations[declaration.skillId] = declaration
    }

    fun check(skillId: String, availability: ResourceAvailability): ResourceCheckResult {
        val decl = declarations[skillId] ?: return ResourceCheckResult(skillId, true, emptyList(), emptyList(), null)
        val missing = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // CPU
        if (availability.availableCpuCores < decl.cpu.minCores) {
            missing.add("CPU 核心不足: 需要 ${decl.cpu.minCores}，可用 ${availability.availableCpuCores}")
        }

        // 内存
        if (availability.availableMemoryMb < decl.memory.minMb) {
            missing.add("内存不足: 需要 ${decl.memory.minMb}MB，可用 ${availability.availableMemoryMb}MB")
        } else if (availability.availableMemoryMb < decl.memory.preferredMb) {
            warnings.add("内存低于推荐值: 推荐 ${decl.memory.preferredMb}MB")
        }

        // 网络
        if (decl.network.required && !availability.hasNetwork) {
            missing.add("需要网络但无网络连接")
        }
        if (decl.network.maxLatencyMs > 0 && availability.networkLatencyMs > decl.network.maxLatencyMs) {
            warnings.add("网络延迟过高: ${availability.networkLatencyMs}ms > ${decl.network.maxLatencyMs}ms")
        }

        // 存储
        if (decl.storage.minMb > 0 && availability.availableStorageMb < decl.storage.minMb) {
            missing.add("存储不足: 需要 ${decl.storage.minMb}MB")
        }

        // API Keys
        val missingKeys = decl.apiKeys - availability.availableApiKeys
        if (missingKeys.isNotEmpty()) missing.add("缺少 API Key: $missingKeys")

        // 权限
        val missingPerms = decl.permissions - availability.grantedPermissions
        if (missingPerms.isNotEmpty()) missing.add("缺少权限: $missingPerms")

        // 模型
        val missingModels = decl.models - availability.loadedModels
        if (missingModels.isNotEmpty()) missing.add("缺少模型: $missingModels")

        // 依赖
        val missingDeps = decl.dependencies - availability.activeDependencies
        if (missingDeps.isNotEmpty()) missing.add("缺少依赖: $missingDeps")

        val canRun = missing.isEmpty()
        val allocation = if (canRun) {
            ResourceAllocation(
                allocatedCpuCores = minOf(decl.cpu.preferredCores, availability.availableCpuCores),
                allocatedMemoryMb = minOf(decl.memory.preferredMb, availability.availableMemoryMb),
                allocatedStorageMb = minOf(decl.storage.preferredMb.toLong(), availability.availableStorageMb),
                networkPriority = if (decl.network.required) 1 else 0
            ).also { allocations[skillId] = it }
        } else null

        return ResourceCheckResult(skillId, canRun, missing, warnings, allocation)
    }

    fun getDeclaration(skillId: String): ResourceDeclaration? = declarations[skillId]
    fun getAllocation(skillId: String): ResourceAllocation? = allocations[skillId]
    fun releaseAllocation(skillId: String) { allocations.remove(skillId) }
    fun getAllDeclarations(): List<ResourceDeclaration> = declarations.values.toList()
}
