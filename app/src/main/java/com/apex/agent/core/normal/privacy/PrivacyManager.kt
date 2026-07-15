package com.apex.agent.core.normal.privacy

import java.util.concurrent.ConcurrentHashMap

/**
 * F23: 隐私模式与数据控制
 *
 * 用户可控的隐私模式：
 * - 隐私模式：临时关闭记忆/总结/画像学习
 * - 数据分级：哪些数据可存储/可分析/可上传
 * - 数据生命周期：自动过期/手动清除
 * - 数据导出/删除：GDPR 合规
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的隐私是 Agent 级
 * - 狂暴不关心隐私
 * - 本功能是**用户数据主权**的核心，单 Agent 必须尊重用户隐私
 */

/**
 * 隐私级别
 */
enum class PrivacyLevel {
    /** 完全开放：所有数据可存储/分析/上传 */
    OPEN,
    /** 标准：本地存储+分析，不上传 */
    STANDARD,
    /** 严格：仅会话内存储，会话结束清除 */
    STRICT,
    /** 隐身：完全不存储 */
    INCOGNITO
}

/**
 * 数据类型
 */
enum class DataType {
    CONVERSATION,        // 对话内容
        USER_PROFILE,        // 用户画像
        MEMORY,              // 长期记忆
        SEARCH_INDEX,        // 搜索索引
        KNOWLEDGE_GRAPH,     // 知识图谱
        EMOTION_TRACK,       // 情感追踪
        HEALTH_METRICS,      // 健康指标
        TOOL_HISTORY,        // 工具调用历史
        BRANCH_HISTORY,      // 分支历史
        SUMMARY,             // 摘要
        SENSITIVE_DATA,      // 敏感数据
        USAGE_STATISTICS     // 使用统计
}

/**
 * 数据处理策略
 */
data class DataPolicy(
    val dataType: DataType,
    val canStore: Boolean,
    val canAnalyze: Boolean,
    val canUpload: Boolean,
    val retentionDays: Int? = null,  // null=永久
    val encryptAtRest: Boolean = true,
    val anonymizeForAnalytics: Boolean = true
)

/**
 * 隐私配置
 */
data class PrivacyConfig(
    val level: PrivacyLevel = PrivacyLevel.STANDARD,
    val policies: Map<DataType, DataPolicy> = defaultPolicies(),
    val autoDeleteAfterDays: Int? = null,
    val excludePatterns: List<String> = emptyList(),  // 不存储包含这些模式的内容
    val requireExplicitConsent: Set<DataType> = setOf(DataType.SENSITIVE_DATA)
) {
    companion object {
        fun defaultPolicies(): Map<DataType, DataPolicy> {
            return DataType.values().associateWith { type ->
                when (type) {
                    DataType.CONVERSATION -> DataPolicy(type, true, true, false, retentionDays = 90)
        DataType.USER_PROFILE -> DataPolicy(type, true, true, false, retentionDays = null)
        DataType.MEMORY -> DataPolicy(type, true, true, false, retentionDays = 365)
        DataType.SEARCH_INDEX -> DataPolicy(type, true, true, false, retentionDays = 90)
        DataType.KNOWLEDGE_GRAPH -> DataPolicy(type, true, true, false, retentionDays = null)
        DataType.EMOTION_TRACK -> DataPolicy(type, true, true, false, retentionDays = 30)
        DataType.HEALTH_METRICS -> DataPolicy(type, true, true, false, retentionDays = 30)
        DataType.TOOL_HISTORY -> DataPolicy(type, true, true, false, retentionDays = 30)
        DataType.BRANCH_HISTORY -> DataPolicy(type, true, true, false, retentionDays = 30)
        DataType.SUMMARY -> DataPolicy(type, true, true, false, retentionDays = 90)
        DataType.SENSITIVE_DATA -> DataPolicy(type, false, false, false, retentionDays = 0)
        DataType.USAGE_STATISTICS -> DataPolicy(type, true, true, true, retentionDays = 365, anonymizeForAnalytics = true)
                }
            }
        }
        fun strictPolicies(): Map<DataType, DataPolicy> {
            return DataType.values().associateWith { type ->
                when (type) {
                    DataType.CONVERSATION -> DataPolicy(type, true, false, false, retentionDays = 7)
        DataType.MEMORY -> DataPolicy(type, true, false, false, retentionDays = 7)
        DataType.SEARCH_INDEX -> DataPolicy(type, true, false, false, retentionDays = 7)
        else -> DataPolicy(type, false, false, false, retentionDays = 0)
                }
            }
        }
        fun incognitoPolicies(): Map<DataType, DataPolicy> {
            return DataType.values().associateWith { DataPolicy(it, false, false, false, 0) }
        }
    }
}

/**
 * 数据访问决策
 */
sealed class DataAccessDecision {
    data class Allowed(val reason: String = "策略允许") : DataAccessDecision()
        data class Denied(val reason: String) : DataAccessDecision()
        data class RequiresConsent(val dataType: DataType, val reason: String) : DataAccessDecision()
}

/**
 * 隐私管理器
 */
class PrivacyManager(
    private var config: PrivacyConfig = PrivacyConfig()
) {

    private val consentRecords = ConcurrentHashMap<String, ConsentRecord>()
        private val dataLifecycle = ConcurrentHashMap<String, DataRecord>()
        private val listeners = mutableListOf<PrivacyEventListener>()

    /**
     * 更新隐私配置
     */
    fun updateConfig(newConfig: PrivacyConfig) {
        val oldLevel = config.level
        config = newConfig
        if (oldLevel != newConfig.level) {
            notifyLevelChanged(oldLevel, newConfig.level)
        }
    }

    /**
     * 切换隐私级别
     */
    fun setLevel(level: PrivacyLevel) {
        val newPolicies = when (level) {
            PrivacyLevel.OPEN -> PrivacyConfig.defaultPolicies().mapValues { (_, p) ->
                p.copy(canUpload = true)
            }
        PrivacyLevel.STANDARD -> PrivacyConfig.defaultPolicies()
        PrivacyLevel.STRICT -> PrivacyConfig.strictPolicies()
        PrivacyLevel.INCOGNITO -> PrivacyConfig.incognitoPolicies()
        }
        updateConfig(config.copy(level = level, policies = newPolicies))
    }

    /**
     * 检查是否允许存储某类数据
     */
    fun canStore(dataType: DataType): DataAccessDecision {
        val policy = config.policies[dataType] ?: return DataAccessDecision.Denied("无策略")
        if (!policy.canStore) return DataAccessDecision.Denied("策略禁止存储 ${dataType}")
        if (dataType in config.requireExplicitConsent) {
            val consent = consentRecords[dataType.name]
            if (consent == null || !consent.granted) {
                return DataAccessDecision.RequiresConsent(dataType, "需要用户明确同意")
            }
        }
        return DataAccessDecision.Allowed()
    }

    /**
     * 检查是否允许分析
     */
    fun canAnalyze(dataType: DataType): Boolean {
        val policy = config.policies[dataType] ?: return false
        return policy.canAnalyze
    }

    /**
     * 检查是否允许上传
     */
    fun canUpload(dataType: DataType): Boolean {
        val policy = config.policies[dataType] ?: return false
        return policy.canUpload
    }

    /**
     * 授予同意
     */
    fun grantConsent(dataType: DataType, scope: ConsentScope = ConsentScope.SESSION) {
        consentRecords[dataType.name] = ConsentRecord(
            dataType = dataType,
            granted = true,
            scope = scope,
            grantedAt = System.currentTimeMillis(),
            expiresAt = when (scope) {
                ConsentScope.ONCE -> System.currentTimeMillis() + 60_000  // 1 分钟
        ConsentScope.SESSION -> null  // 会话结束失效
        ConsentScope.PERMANENT -> null
            }
        )
    }

    /**
     * 撤销同意
     */
    fun revokeConsent(dataType: DataType) {
        consentRecords.remove(dataType.name)
    }

    /**
     * 记录数据存储
     */
    fun recordData(dataType: DataType, dataId: String, metadata: Map<String, Any> = emptyMap()) {
        val policy = config.policies[dataType] ?: return
        val expiresAt = policy.retentionDays?.let { days ->
            System.currentTimeMillis() + days * 24 * 60 * 60_000L
        }
        dataLifecycle[dataId] = DataRecord(
            dataId = dataId,
            dataType = dataType,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
            metadata = metadata
        )
    }

    /**
     * 删除特定数据
     */
    fun deleteData(dataId: String): Boolean {
        return dataLifecycle.remove(dataId) != null
    }

    /**
     * 删除某类型的所有数据
     */
    fun deleteAllData(dataType: DataType): Int {
        val toRemove = dataLifecycle.entries.filter { it.value.dataType == dataType }.map { it.key }
        toRemove.forEach { dataLifecycle.remove(it) }
        return toRemove.size
    }

    /**
     * 清除所有用户数据（GDPR Right to Erasure）
     */
    fun deleteAllUserData(): DeletionReport {
        val report = DeletionReport()
        val byType = dataLifecycle.values.groupingBy { it.dataType }.eachCount()
        for ((type, count) in byType) {
            report.addDeleted(type, count)
        deleteAllData(type)
        }
        consentRecords.clear()
        report.completedAt = System.currentTimeMillis()
        return report
    }

    /**
     * 检查过期数据
     */
    fun checkExpiredData(): List<String> {
        val now = System.currentTimeMillis()
        return dataLifecycle.entries
            .filter { (_, record) -> record.expiresAt != null && record.expiresAt!! < now }
            .map { it.key }
    }

    /**
     * 检查内容是否匹配排除模式
     */
    fun shouldExclude(content: String): Boolean {
        return config.excludePatterns.any { pattern ->
            try {
                content.contains(Regex(pattern))
            } catch (e: Exception) {
                content.contains(pattern, ignoreCase = true)
            }
        }
    }

    /**
     * 导出所有用户数据（GDPR Data Portability）
     */
    fun exportAllData(): UserDataExport {
        return UserDataExport(
            exportedAt = System.currentTimeMillis(),
            config = config,
            consents = consentRecords.toMap(),
            dataRecords = dataLifecycle.toMap()
        )
    }

    /**
     * 生成隐私状态报告
     */
    fun generateStatusReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 隐私状态 ═══")
        sb.appendLine("当前级别: ${config.level}")
        sb.appendLine()
        sb.appendLine("数据策略:")
        config.policies.forEach { (type, policy) ->
            val store = if (policy.canStore) "✓" else "✗"
        val analyze = if (policy.canAnalyze) "✓" else "✗"
        val upload = if (policy.canUpload) "✓" else "✗"
        val retention = policy.retentionDays?.let { "${it}天" } ?: "永久"
        sb.appendLine("  ${type.name.padEnd(20)} 存储:$store 分析:$analyze 上传:$upload 保留:$retention")
        }
        sb.appendLine()
        sb.appendLine("已记录数据: ${dataLifecycle.size} 条")
        val byType = dataLifecycle.values.groupingBy { it.dataType }.eachCount()
        if (byType.isNotEmpty()) {
            sb.appendLine("按类型:")
        byType.forEach { (type, count) -> sb.appendLine("  ${type.name}: $count") }
        }
        sb.appendLine()
        sb.appendLine("同意记录: ${consentRecords.size} 条")
        sb.appendLine("排除模式: ${config.excludePatterns.size} 个")
        sb.appendLine("═══════════════")
        return sb.toString()
    }

    /**
     * 添加事件监听器
     */
    fun addListener(listener: PrivacyEventListener) {
        listeners.add(listener)
    }
        private fun notifyLevelChanged(old: PrivacyLevel, new: PrivacyLevel) {
        listeners.forEach { it.onPrivacyLevelChanged(old, new) }
    }

    // ============ 数据结构 ============
    data class ConsentRecord(
        val dataType: DataType,
        val granted: Boolean,
        val scope: ConsentScope,
        val grantedAt: Long,
        val expiresAt: Long?
    )
        enum class ConsentScope { ONCE, SESSION, PERMANENT }
        data class DataRecord(
        val dataId: String,
        val dataType: DataType,
        val createdAt: Long,
        val expiresAt: Long?,
        val metadata: Map<String, Any>
    )
        data class DeletionReport(
        val deletedByType: MutableMap<DataType, Int> = mutableMapOf(),
        var completedAt: Long = 0
    ) {
        fun addDeleted(type: DataType, count: Int) {
            deletedByType[type] = (deletedByType[type] ?: 0) + count
        }
        val totalDeleted: Int get() = deletedByType.values.sum()
    }
        data class UserDataExport(
        val exportedAt: Long,
        val config: PrivacyConfig,
        val consents: Map<String, ConsentRecord>,
        val dataRecords: Map<String, DataRecord>
    )
}

/**
 * 隐私事件监听器
 */
interface PrivacyEventListener {
    fun onPrivacyLevelChanged(old: PrivacyLevel, new: PrivacyLevel)
}
