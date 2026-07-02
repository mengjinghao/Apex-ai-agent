package com.apex.agent.kernel.burst.enhanced.compatibility

import java.util.concurrent.ConcurrentHashMap

/**
 * B49: 技能兼容性检查器
 *
 * 执行前检查技能与当前环境/配置的兼容性：
 * - API 版本兼容
 * - 权限兼容
 * - 资源兼容
 * - 配置依赖兼容
 */
class SkillCompatibilityChecker {

    data class CompatibilityResult(
        val skillId: String,
        val compatible: Boolean,
        val issues: List<CompatibilityIssue>,
        val warnings: List<String>
    )

    data class CompatibilityIssue(
        val type: IssueType,
        val severity: IssueSeverity,
        val message: String,
        val requiredValue: String?,
        val actualValue: String?
    )

    enum class IssueType {
        API_VERSION, PERMISSION, RESOURCE, CONFIG_DEPENDENCY,
        ANDROID_VERSION, NETWORK_REQUIREMENT, MEMORY_REQUIREMENT,
        SKILL_VERSION, CONFLICT
    }

    enum class IssueSeverity { ERROR, WARNING }

    data class SkillRequirements(
        val skillId: String,
        val minApiVersion: String? = null,
        val requiredPermissions: Set<String> = emptySet(),
        val minAndroidVersion: Int? = null,
        val requiresNetwork: Boolean = false,
        val minMemoryMb: Int? = null,
        val minCpuCores: Int? = null,
        val requiredConfigs: Map<String, Any> = emptyMap(),
        val conflictsWith: Set<String> = emptySet(),
        val requiredSkills: Set<String> = emptySet()
    )

    data class EnvironmentInfo(
        val apiVersion: String,
        val androidVersion: Int,
        val availableMemoryMb: Int,
        val cpuCores: Int,
        val hasNetwork: Boolean,
        val currentPermissions: Set<String>,
        val currentConfigs: Map<String, Any>,
        val activeSkills: Set<String>
    )

    private val requirements = ConcurrentHashMap<String, SkillRequirements>()
    private val checkHistory = mutableListOf<CompatibilityResult>()

    /**
     * 注册技能需求
     */
    fun registerRequirements(req: SkillRequirements) {
        requirements[req.skillId] = req
    }

    /**
     * 检查兼容性
     */
    fun check(skillId: String, env: EnvironmentInfo): CompatibilityResult {
        val req = requirements[skillId]
        val issues = mutableListOf<CompatibilityIssue>()
        val warnings = mutableListOf<String>()

        if (req == null) {
            return CompatibilityResult(skillId, true, emptyList(), listOf("未注册需求，默认兼容"))
        }

        // API 版本
        if (req.minApiVersion != null) {
            if (compareVersions(env.apiVersion, req.minApiVersion) < 0) {
                issues.add(CompatibilityIssue(IssueType.API_VERSION, IssueSeverity.ERROR,
                    "API 版本过低", req.minApiVersion, env.apiVersion))
            }
        }

        // Android 版本
        if (req.minAndroidVersion != null && env.androidVersion < req.minAndroidVersion) {
            issues.add(CompatibilityIssue(IssueType.ANDROID_VERSION, IssueSeverity.ERROR,
                "Android 版本过低", req.minAndroidVersion.toString(), env.androidVersion.toString()))
        }

        // 权限
        val missingPerms = req.requiredPermissions - env.currentPermissions
        if (missingPerms.isNotEmpty()) {
            issues.add(CompatibilityIssue(IssueType.PERMISSION, IssueSeverity.ERROR,
                "缺少权限: $missingPerms", req.requiredPermissions.toString(), env.currentPermissions.toString()))
        }

        // 网络
        if (req.requiresNetwork && !env.hasNetwork) {
            issues.add(CompatibilityIssue(IssueType.NETWORK_REQUIREMENT, IssueSeverity.ERROR,
                "需要网络但当前无网络", "网络可用", "无网络"))
        }

        // 内存
        if (req.minMemoryMb != null && env.availableMemoryMb < req.minMemoryMb) {
            issues.add(CompatibilityIssue(IssueType.MEMORY_REQUIREMENT, IssueSeverity.WARNING,
                "内存不足", "${req.minMemoryMb}MB", "${env.availableMemoryMb}MB"))
            warnings.add("可用内存较低，可能影响性能")
        }

        // CPU
        if (req.minCpuCores != null && env.cpuCores < req.minCpuCores) {
            issues.add(CompatibilityIssue(IssueType.RESOURCE, IssueSeverity.WARNING,
                "CPU 核心数不足", req.minCpuCores.toString(), env.cpuCores.toString()))
        }

        // 配置依赖
        for ((key, requiredValue) in req.requiredConfigs) {
            val actualValue = env.currentConfigs[key]
            if (actualValue != requiredValue) {
                issues.add(CompatibilityIssue(IssueType.CONFIG_DEPENDENCY, IssueSeverity.ERROR,
                    "配置不匹配: $key", requiredValue.toString(), actualValue?.toString() ?: "未设置"))
            }
        }

        // 冲突技能
        val activeConflicts = req.conflictsWith.intersect(env.activeSkills)
        if (activeConflicts.isNotEmpty()) {
            issues.add(CompatibilityIssue(IssueType.CONFLICT, IssueSeverity.ERROR,
                "与活跃技能冲突: $activeConflicts", "无冲突", activeConflicts.toString()))
        }

        // 依赖技能
        val missingSkills = req.requiredSkills - env.activeSkills
        if (missingSkills.isNotEmpty()) {
            issues.add(CompatibilityIssue(IssueType.SKILL_VERSION, IssueSeverity.ERROR,
                "缺少依赖技能: $missingSkills", req.requiredSkills.toString(), env.activeSkills.toString()))
        }

        val hasErrors = issues.any { it.severity == IssueSeverity.ERROR }
        val result = CompatibilityResult(skillId, !hasErrors, issues, warnings)
        checkHistory.add(result)
        while (checkHistory.size > 500) checkHistory.removeAt(0)
        return result
    }

    /**
     * 批量检查
     */
    fun checkAll(skillIds: List<String>, env: EnvironmentInfo): Map<String, CompatibilityResult> {
        return skillIds.associateWith { check(it, env) }
    }

    /**
     * 获取兼容的技能
     */
    fun getCompatibleSkills(env: EnvironmentInfo): List<String> {
        return requirements.keys.filter { check(it, env).compatible }.toList()
    }

    fun getRequirements(skillId: String): SkillRequirements? = requirements[skillId]
    fun getHistory(): List<CompatibilityResult> = checkHistory.toList()

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
}
