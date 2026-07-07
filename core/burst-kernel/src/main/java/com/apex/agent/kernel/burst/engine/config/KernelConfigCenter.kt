package com.apex.agent.kernel.burst.engine.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * E2: 内核配置中心
 *
 * 统一管理内核所有配置：
 * - 分层配置（系统/用户/运行时）
 * - 热更新（不重启）
 * - 配置校验
 * - 配置变更通知
 */
class KernelConfigCenter {

    data class KernelConfig(
        // 执行配置
        val maxConcurrency: Int = 4,
        val defaultTimeoutMs: Long = 120_000L,
        val maxRetries: Int = 3,
        val retryDelayMs: Long = 1000L,
        // 内存配置
        val memoryBudgetMb: Int = 256,
        val contextWindowTokens: Int = 32_000,
        // LLM 配置
        val llmProvider: String = "deepseek",
        val llmModel: String = "deepseek-chat",
        val llmApiKey: String = "",
        val llmEndpoint: String = "",
        val llmTemperature: Float = 0.7f,
        val llmMaxTokens: Int = 4096,
        // 调度配置
        val taskQueueSize: Int = 100,
        val healthCheckIntervalMs: Long = 30_000L,
        val checkpointIntervalMs: Long = 10_000L,
        // 功能开关
        val enableAdaptiveOptimization: Boolean = true,
        val enableHealthCheck: Boolean = true,
        val enableCheckpointing: Boolean = true,
        val enableMetrics: Boolean = true,
        val enableStreaming: Boolean = true,
        val enableSecurityCheck: Boolean = true,
        // 高级配置
        val skillWarmupEnabled: Boolean = true,
        val predictiveLoading: Boolean = false,
        val autoRecovery: Boolean = true,
        val maxParallelSkills: Int = 5
    )

    enum class ConfigLayer { SYSTEM, USER, RUNTIME }

    data class ConfigEntry(
        val key: String,
        val value: Any,
        val layer: ConfigLayer,
        val updatedAt: Long = System.currentTimeMillis(),
        val updatedBy: String = "system"
    )

    data class ConfigChange(
        val key: String,
        val oldValue: Any?,
        val newValue: Any,
        val layer: ConfigLayer,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val systemConfig = ConcurrentHashMap<String, ConfigEntry>()
    private val userConfig = ConcurrentHashMap<String, ConfigEntry>()
    private val runtimeConfig = ConcurrentHashMap<String, ConfigEntry>()
    private val _currentConfig = MutableStateFlow(KernelConfig())
    val currentConfig: StateFlow<KernelConfig> = _currentConfig.asStateFlow()
    private val _changeHistory = MutableStateFlow<List<ConfigChange>>(emptyList())
    val changeHistory: StateFlow<List<ConfigChange>> = _changeHistory.asStateFlow()
    private val recentChanges = mutableListOf<ConfigChange>()
    private val watchers = mutableListOf<(ConfigChange) -> Unit>()

    init {
        // 初始化默认配置
        loadDefaults()
    }

    private fun loadDefaults() {
        val default = KernelConfig()
        set("maxConcurrency", default.maxConcurrency, ConfigLayer.SYSTEM)
        set("defaultTimeoutMs", default.defaultTimeoutMs, ConfigLayer.SYSTEM)
        set("maxRetries", default.maxRetries, ConfigLayer.SYSTEM)
        set("enableAdaptiveOptimization", default.enableAdaptiveOptimization, ConfigLayer.SYSTEM)
        set("enableHealthCheck", default.enableHealthCheck, ConfigLayer.SYSTEM)
        // ... 其他默认值由 get 时自动填充
    }

    fun set(key: String, value: Any, layer: ConfigLayer = ConfigLayer.RUNTIME, updatedBy: String = "system") {
        val targetMap = when (layer) {
            ConfigLayer.SYSTEM -> systemConfig
            ConfigLayer.USER -> userConfig
            ConfigLayer.RUNTIME -> runtimeConfig
        }
        val oldEntry = getEffectiveEntry(key)
        val entry = ConfigEntry(key, value, layer, updatedBy = updatedBy)
        targetMap[key] = entry

        val change = ConfigChange(key, oldEntry?.value, value, layer)
        recentChanges.add(change)
        while (recentChanges.size > 200) recentChanges.removeAt(0)
        _changeHistory.value = recentChanges.toList()
        watchers.forEach { it(change) }
        rebuildConfig()
    }

    fun <T> get(key: String, default: T): T {
        val entry = getEffectiveEntry(key) ?: return default
        @Suppress("UNCHECKED_CAST")
        return entry.value as? T ?: default
    }

    fun getEntry(key: String): ConfigEntry? = getEffectiveEntry(key)

    fun watch(key: String, callback: (ConfigChange) -> Unit) {
        watchers.add { change -> if (change.key == key) callback(change) }
    }

    fun watchAll(callback: (ConfigChange) -> Unit) {
        watchers.add(callback)
    }

    fun reset(key: String) {
        runtimeConfig.remove(key)
        userConfig.remove(key)
        rebuildConfig()
    }

    fun resetAll() {
        runtimeConfig.clear()
        userConfig.clear()
        rebuildConfig()
    }

    fun getAllEntries(): Map<String, ConfigEntry> {
        val result = mutableMapOf<String, ConfigEntry>()
        systemConfig.forEach { (k, v) -> result[k] = v }
        userConfig.forEach { (k, v) -> result[k] = v }
        runtimeConfig.forEach { (k, v) -> result[k] = v }
        return result
    }

    fun exportConfig(): Map<String, Any> = getAllEntries().mapValues { it.value.value }

    fun importConfig(config: Map<String, Any>, layer: ConfigLayer = ConfigLayer.USER) {
        config.forEach { (k, v) -> set(k, v, layer) }
    }

    private fun getEffectiveEntry(key: String): ConfigEntry? {
        // 优先级：RUNTIME > USER > SYSTEM
        return runtimeConfig[key] ?: userConfig[key] ?: systemConfig[key]
    }

    private fun rebuildConfig() {
        val current = _currentConfig.value
        _currentConfig.value = current.copy(
            maxConcurrency = get("maxConcurrency", current.maxConcurrency),
            defaultTimeoutMs = get("defaultTimeoutMs", current.defaultTimeoutMs),
            maxRetries = get("maxRetries", current.maxRetries),
            retryDelayMs = get("retryDelayMs", current.retryDelayMs),
            memoryBudgetMb = get("memoryBudgetMb", current.memoryBudgetMb),
            contextWindowTokens = get("contextWindowTokens", current.contextWindowTokens),
            llmProvider = get("llmProvider", current.llmProvider),
            llmModel = get("llmModel", current.llmModel),
            llmApiKey = get("llmApiKey", current.llmApiKey),
            llmEndpoint = get("llmEndpoint", current.llmEndpoint),
            llmTemperature = get("llmTemperature", current.llmTemperature),
            llmMaxTokens = get("llmMaxTokens", current.llmMaxTokens),
            taskQueueSize = get("taskQueueSize", current.taskQueueSize),
            healthCheckIntervalMs = get("healthCheckIntervalMs", current.healthCheckIntervalMs),
            checkpointIntervalMs = get("checkpointIntervalMs", current.checkpointIntervalMs),
            enableAdaptiveOptimization = get("enableAdaptiveOptimization", current.enableAdaptiveOptimization),
            enableHealthCheck = get("enableHealthCheck", current.enableHealthCheck),
            enableCheckpointing = get("enableCheckpointing", current.enableCheckpointing),
            enableMetrics = get("enableMetrics", current.enableMetrics),
            enableStreaming = get("enableStreaming", current.enableStreaming),
            enableSecurityCheck = get("enableSecurityCheck", current.enableSecurityCheck),
            skillWarmupEnabled = get("skillWarmupEnabled", current.skillWarmupEnabled),
            predictiveLoading = get("predictiveLoading", current.predictiveLoading),
            autoRecovery = get("autoRecovery", current.autoRecovery),
            maxParallelSkills = get("maxParallelSkills", current.maxParallelSkills)
        )
    }
}
