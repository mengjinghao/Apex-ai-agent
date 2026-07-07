package com.apex.agent.kernel.burst.engine.capability

import java.util.concurrent.ConcurrentHashMap

/**
 * E10: 内核能力注册表
 *
 * 动态声明和查询内核能力：
 * - 能力注册/注销
 * - 能力查询
 * - 能力依赖
 * - 能力版本
 */
class KernelCapabilityRegistry {

    data class Capability(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val category: CapabilityCategory,
        val provider: String,
        val dependencies: Set<String> = emptySet(),
        val metadata: Map<String, Any> = emptyMap(),
        val registeredAt: Long = System.currentTimeMillis()
    )

    enum class CapabilityCategory {
        EXECUTION, REASONING, PLANNING, SEARCH, STORAGE,
        MONITORING, SECURITY, NETWORK, LLM, PLUGIN, UI, SYSTEM
    }

    data class CapabilityQuery(
        val category: CapabilityCategory? = null,
        val nameContains: String? = null,
        val provider: String? = null,
        val minVersion: String? = null
    )

    private val capabilities = ConcurrentHashMap<String, Capability>()

    fun register(capability: Capability): Boolean {
        // 检查依赖
        for (dep in capability.dependencies) {
            if (dep !in capabilities) return false
        }
        capabilities[capability.id] = capability
        return true
    }

    fun unregister(capabilityId: String): Boolean {
        // 检查是否有其他能力依赖它
        val dependents = capabilities.values.filter { capabilityId in it.dependencies }
        if (dependents.isNotEmpty()) return false
        return capabilities.remove(capabilityId) != null
    }

    fun get(capabilityId: String): Capability? = capabilities[capabilityId]

    fun query(query: CapabilityQuery = CapabilityQuery()): List<Capability> {
        return capabilities.values.filter { cap ->
            (query.category == null || cap.category == query.category) &&
            (query.nameContains == null || cap.name.contains(query.nameContains, ignoreCase = true)) &&
            (query.provider == null || cap.provider == query.provider) &&
            (query.minVersion == null || compareVersions(cap.version, query.minVersion) >= 0)
        }.sortedBy { it.name }
    }

    fun getAll(): List<Capability> = capabilities.values.toList()

    fun getByCategory(category: CapabilityCategory): List<Capability> =
        capabilities.values.filter { it.category == category }.toList()

    fun getDependencies(capabilityId: String): Set<String> =
        capabilities[capabilityId]?.dependencies ?: emptySet()

    fun getDependents(capabilityId: String): List<String> =
        capabilities.values.filter { capabilityId in it.dependencies }.map { it.id }.toList()

    fun hasCapability(capabilityId: String): Boolean = capabilities.containsKey(capabilityId)

    fun getStats(): CapabilityStats {
        return CapabilityStats(
            totalCapabilities = capabilities.size,
            byCategory = capabilities.values.groupingBy { it.category }.eachCount(),
            byProvider = capabilities.values.groupingBy { it.provider }.eachCount()
        )
    }

    data class CapabilityStats(
        val totalCapabilities: Int,
        val byCategory: Map<CapabilityCategory, Int>,
        val byProvider: Map<String, Int>
    )

    private fun compareVersions(v1: String, v2: String): Int {
        val p1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(p1.size, p2.size)) {
            val a = p1.getOrElse(i) { 0 }
            val b = p2.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}
