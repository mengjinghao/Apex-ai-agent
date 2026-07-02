package com.apex.agent.kernel.burst.enhanced.deprecation

import java.util.concurrent.ConcurrentHashMap

/**
 * B52: 技能弃用处理器
 *
 * 优雅处理弃用的技能：
 * - 弃用标记与时间线
 * - 替代技能推荐
 * - 过渡期管理
 * - 弃用警告
 */
class SkillDeprecationHandler {

    data class DeprecationInfo(
        val skillId: String,
        val deprecatedAt: Long,
        val sunsetAt: Long?,            // 完全移除时间
        val replacementSkillId: String?,
        val reason: String,
        val migrationGuide: String?,
        val severity: DeprecationSeverity
    )

    enum class DeprecationSeverity {
        INFO,         // 仅通知
        WARNING,      // 警告但仍可用
        MIGRATE,      // 建议迁移
        CRITICAL      // 即将移除
    }

    data class DeprecationCheckResult(
        val skillId: String,
        val isDeprecated: Boolean,
        val severity: DeprecationSeverity?,
        val replacement: String?,
        val warning: String?,
        val shouldBlock: Boolean,
        val migrationGuide: String?
    )

    private val deprecations = ConcurrentHashMap<String, DeprecationInfo>()
    private val migrationHistory = mutableListOf<Pair<String, String>>()  // (oldSkillId, newSkillId)

    /**
     * 标记技能弃用
     */
    fun deprecate(
        skillId: String,
        replacementSkillId: String? = null,
        reason: String = "技能已弃用",
        migrationGuide: String? = null,
        sunsetAfterDays: Int? = 90,
        severity: DeprecationSeverity = DeprecationSeverity.WARNING
    ) {
        val sunsetAt = sunsetAfterDays?.let { System.currentTimeMillis() + it * 24 * 60 * 60_000L }
        deprecations[skillId] = DeprecationInfo(
            skillId = skillId,
            deprecatedAt = System.currentTimeMillis(),
            sunsetAt = sunsetAt,
            replacementSkillId = replacementSkillId,
            reason = reason,
            migrationGuide = migrationGuide,
            severity = severity
        )
    }

    /**
     * 检查弃用状态
     */
    fun check(skillId: String): DeprecationCheckResult {
        val info = deprecations[skillId]
            ?: return DeprecationCheckResult(skillId, false, null, null, null, false, null)

        val now = System.currentTimeMillis()
        val daysUntilSunset = info.sunsetAt?.let { (it - now) / (24 * 60 * 60_000L) }

        val currentSeverity = when {
            info.sunsetAt != null && now > info.sunsetAt -> DeprecationSeverity.CRITICAL
            daysUntilSunset != null && daysUntilSunset < 30 -> DeprecationSeverity.CRITICAL
            daysUntilSunset != null && daysUntilSunset < 60 -> DeprecationSeverity.MIGRATE
            else -> info.severity
        }

        val warning = buildString {
            append("⚠️ 技能 $skillId 已弃用: ${info.reason}")
            if (info.replacementSkillId != null) append("，替代: ${info.replacementSkillId}")
            if (daysUntilSunset != null) append("，${daysUntilSunset}天后移除")
        }

        val shouldBlock = currentSeverity == DeprecationSeverity.CRITICAL &&
                         info.sunsetAt != null && now > info.sunsetAt

        return DeprecationCheckResult(
            skillId = skillId,
            isDeprecated = true,
            severity = currentSeverity,
            replacement = info.replacementSkillId,
            warning = warning,
            shouldBlock = shouldBlock,
            migrationGuide = info.migrationGuide
        )
    }

    /**
     * 自动迁移到替代技能
     */
    fun migrate(skillId: String): String? {
        val info = deprecations[skillId] ?: return null
        val replacement = info.replacementSkillId ?: return null
        migrationHistory.add(skillId to replacement)
        return replacement
    }

    /**
     * 获取所有弃用技能
     */
    fun getAllDeprecated(): List<DeprecationInfo> = deprecations.values.toList()

    /**
     * 获取即将移除的技能
     */
    fun getCriticalDeprecations(): List<DeprecationInfo> {
        val now = System.currentTimeMillis()
        return deprecations.values.filter {
            it.sunsetAt != null && (it.sunsetAt - now) < 30 * 24 * 60 * 60_000L
        }.sortedBy { it.sunsetAt }
    }

    /**
     * 取消弃用
     */
    fun undeprecate(skillId: String): Boolean {
        return deprecations.remove(skillId) != null
    }

    /**
     * 获取迁移历史
     */
    fun getMigrationHistory(): List<Pair<String, String>> = migrationHistory.toList()

    /**
     * 生成弃用报告
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 技能弃用报告 ═══")
        sb.appendLine("已弃用: ${deprecations.size}")
        sb.appendLine("迁移次数: ${migrationHistory.size}")
        sb.appendLine()
        val critical = getCriticalDeprecations()
        if (critical.isNotEmpty()) {
            sb.appendLine("⚠️ 即将移除:")
            critical.forEach { info ->
                val days = info.sunsetAt?.let { (it - System.currentTimeMillis()) / (24 * 60 * 60_000L) }
                sb.appendLine("  ${info.skillId} → ${info.replacementSkillId ?: "无替代"} (${days}天后)")
            }
        } else {
            sb.appendLine("无即将移除的技能")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
