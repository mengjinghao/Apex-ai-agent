package com.apex.agent.kernel.burst.enhanced.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * B25: 配置热更新
 *
 * 增强现有 BurstModeConfig：
 * - 运行时热更新（不重启）
 * - 配置版本管理
 * - 回滚支持
 * - 配置校验
 * - A/B 配置测试
 */
class HotConfigManager(
    private val maxVersions: Int = 10
) {

    data class ConfigVersion(
        val version: Int,
        val config: Map<String, Any>,
        val createdAt: Long,
        val createdBy: String,
        val reason: String,
        val isActive: Boolean
    )

    data class ConfigChange(
        val key: String,
        val oldValue: Any?,
        val newValue: Any?,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ConfigValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    private val versions = mutableListOf<ConfigVersion>()
    private val _currentConfig = MutableStateFlow<Map<String, Any>>(emptyMap())
    val currentConfig: StateFlow<Map<String, Any>> = _currentConfig.asStateFlow()

    private val _changeHistory = MutableStateFlow<List<ConfigChange>>(emptyList())
    val changeHistory: StateFlow<List<ConfigChange>> = _changeHistory.asStateFlow()

    private val recentChanges = mutableListOf<ConfigChange>()
    private val validators = ConcurrentHashMap<String, (Any?) -> Boolean>()
    private val configWatchers = mutableListOf<(Map<String, Any>, List<ConfigChange>) -> Unit>()

    /**
     * 初始化配置
     */
    fun initialize(config: Map<String, Any>, createdBy: String = "system", reason: String = "初始化") {
        val version = ConfigVersion(
            version = 1, config = config,
            createdAt = System.currentTimeMillis(),
            createdBy = createdBy, reason = reason, isActive = true
        )
        versions.add(version)
        _currentConfig.value = config
    }

    /**
     * 热更新配置
     */
    fun update(changes: Map<String, Any>, createdBy: String = "system", reason: String = ""): ConfigValidationResult {
        // 校验
        val validation = validate(changes)
        if (!validation.isValid) return validation

        val oldConfig = _currentConfig.value
        val newConfig = oldConfig.toMutableMap().apply { putAll(changes) }

        // 记录变更
        val configChanges = changes.map { (key, newValue) ->
            ConfigChange(key, oldConfig[key], newValue)
        }
        recentChanges.addAll(configChanges)
        while (recentChanges.size > 500) recentChanges.removeAt(0)
        _changeHistory.value = recentChanges.toList()

        // 旧版本标记为非活跃
        versions.lastOrNull { it.isActive }?.let { v ->
            val idx = versions.indexOf(v)
            versions[idx] = v.copy(isActive = false)
        }

        // 新版本
        val newVersion = ConfigVersion(
            version = (versions.maxOfOrNull { it.version } ?: 0) + 1,
            config = newConfig, createdAt = System.currentTimeMillis(),
            createdBy = createdBy, reason = reason, isActive = true
        )
        versions.add(newVersion)
        while (versions.size > maxVersions) versions.removeAt(0)

        // 应用
        _currentConfig.value = newConfig

        // 通知观察者
        configWatchers.forEach { it(newConfig, configChanges) }

        return validation
    }

    /**
     * 回滚到指定版本
     */
    fun rollback(targetVersion: Int, reason: String = "回滚"): Boolean {
        val target = versions.find { it.version == targetVersion } ?: return false
        // 创建新版本（内容是旧版本的配置）
        update(target.config, "system", "回滚到 v$targetVersion: $reason")
        return true
    }

    /**
     * 回滚到上一版本
     */
    fun rollbackLast(): Boolean {
        val inactive = versions.filter { !it.isActive }.sortedByDescending { it.version }
        val target = inactive.firstOrNull() ?: return false
        return rollback(target.version)
    }

    /**
     * 注册校验器
     */
    fun registerValidator(key: String, validator: (Any?) -> Boolean) {
        validators[key] = validator
    }

    /**
     * 注册观察者
     */
    fun watch(callback: (Map<String, Any>, List<ConfigChange>) -> Unit) {
        configWatchers.add(callback)
    }

    /**
     * 获取配置值
     */
    fun <T> get(key: String, default: T): T {
        @Suppress("UNCHECKED_CAST")
        return _currentConfig.value[key] as? T ?: default
    }

    /**
     * 获取版本历史
     */
    fun getVersionHistory(): List<ConfigVersion> = versions.toList()

    /**
     * 获取变更历史
     */
    fun getChangeHistory(limit: Int = 50): List<ConfigChange> =
        recentChanges.takeLast(limit).toList()

    // ============ 内部方法 ============

    private fun validate(changes: Map<String, Any>): ConfigValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for ((key, value) in changes) {
            val validator = validators[key]
            if (validator != null && !validator(value)) {
                errors.add("校验失败: $key = $value")
            }

            // 通用校验
            when (key) {
                "maxConcurrency" -> {
                    val v = value as? Int ?: 0
                    if (v < 1) errors.add("maxConcurrency 不能小于 1")
                    if (v > 100) warnings.add("maxConcurrency = $v 可能过高")
                }
                "timeoutMs" -> {
                    val v = (value as? Number)?.toLong() ?: 0L
                    if (v < 1000) warnings.add("timeoutMs = $v 可能过短")
                    if (v > 600_000) warnings.add("timeoutMs = $v 可能过长")
                }
            }
        }

        return ConfigValidationResult(errors.isEmpty(), errors, warnings)
    }
}
