package com.apex.agent.core.multiagent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.performanceSettingsDataStore by preferencesDataStore(name = "performance_settings")

class PerformanceOptimizationManager(private val context: Context) {

    companion object {
        private const val TAG = "PerformanceOptimizationManager"
        const val PRESET_BALANCED = "balanced"
        const val PRESET_HIGH_PERFORMANCE = "high_performance"
        const val PRESET_LOW_POWER = "low_power"
        const val PRESET_CUSTOM = "custom"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataStore = context.performanceSettingsDataStore
    private val gson = Gson()

    private object SettingsKeys {
        val PERFORMANCE_PRESET = stringPreferencesKey("performance_preset")
        val ENABLE_FEDERATED_LEARNING = booleanPreferencesKey("enable_federated_learning")
        val ENABLE_SELF_HEALING = booleanPreferencesKey("enable_self_healing")
        val ENABLE_KNOWLEDGE_GRAPH = booleanPreferencesKey("enable_knowledge_graph")
        val ENABLE_REAL_TIME_SYNC = booleanPreferencesKey("enable_real_time_sync")
        val ENABLE_AUTO_SCALING = booleanPreferencesKey("enable_auto_scaling")
        val LEARNING_RATE = floatPreferencesKey("learning_rate")
        val MAX_AGENTS = intPreferencesKey("max_agents")
        val CACHE_SIZE = intPreferencesKey("cache_size")
    }

    private val DEFAULT_SETTINGS = PerformanceSettings(preset = PRESET_BALANCED, enableFederatedLearning = true, enableSelfHealing = true, enableKnowledgeGraph = true, enableRealTimeSync = true, enableAutoScaling = true, learningRate = 0.01f, maxAgents = 100, cacheSize = 10000, taskTimeout = 300000, enableCaching = true, enableParallelExecution = true, batchSize = 32, enableOptimizationLogging = false, gpuAcceleration = false, memoryLimitMb = 1024)

    private val PRESETS = mapOf(
        PRESET_BALANCED to PerformanceSettings(preset = PRESET_BALANCED, enableFederatedLearning = true, enableSelfHealing = true, enableKnowledgeGraph = true, enableRealTimeSync = true, enableAutoScaling = true, learningRate = 0.01f, maxAgents = 100, cacheSize = 10000, taskTimeout = 300000, enableCaching = true, enableParallelExecution = true, batchSize = 32, enableOptimizationLogging = false, gpuAcceleration = false, memoryLimitMb = 1024),
        PRESET_HIGH_PERFORMANCE to PerformanceSettings(preset = PRESET_HIGH_PERFORMANCE, enableFederatedLearning = true, enableSelfHealing = true, enableKnowledgeGraph = true, enableRealTimeSync = true, enableAutoScaling = true, learningRate = 0.05f, maxAgents = 200, cacheSize = 50000, taskTimeout = 180000, enableCaching = true, enableParallelExecution = true, batchSize = 64, enableOptimizationLogging = true, gpuAcceleration = true, memoryLimitMb = 2048),
        PRESET_LOW_POWER to PerformanceSettings(preset = PRESET_LOW_POWER, enableFederatedLearning = false, enableSelfHealing = false, enableKnowledgeGraph = false, enableRealTimeSync = false, enableAutoScaling = false, learningRate = 0.001f, maxAgents = 20, cacheSize = 1000, taskTimeout = 600000, enableCaching = false, enableParallelExecution = false, batchSize = 8, enableOptimizationLogging = false, gpuAcceleration = false, memoryLimitMb = 512)
    )

    val currentSettings: Flow<PerformanceSettings> = dataStore.data.map { prefs ->
        val preset = prefs[SettingsKeys.PERFORMANCE_PRESET] ?: PRESET_BALANCED
        PerformanceSettings(preset = preset, enableFederatedLearning = prefs[SettingsKeys.ENABLE_FEDERATED_LEARNING] ?: DEFAULT_SETTINGS.enableFederatedLearning, enableSelfHealing = prefs[SettingsKeys.ENABLE_SELF_HEALING] ?: DEFAULT_SETTINGS.enableSelfHealing, enableKnowledgeGraph = prefs[SettingsKeys.ENABLE_KNOWLEDGE_GRAPH] ?: DEFAULT_SETTINGS.enableKnowledgeGraph, enableRealTimeSync = prefs[SettingsKeys.ENABLE_REAL_TIME_SYNC] ?: DEFAULT_SETTINGS.enableRealTimeSync, enableAutoScaling = prefs[SettingsKeys.ENABLE_AUTO_SCALING] ?: DEFAULT_SETTINGS.enableAutoScaling, learningRate = prefs[SettingsKeys.LEARNING_RATE] ?: DEFAULT_SETTINGS.learningRate, maxAgents = prefs[SettingsKeys.MAX_AGENTS] ?: DEFAULT_SETTINGS.maxAgents, cacheSize = prefs[SettingsKeys.CACHE_SIZE] ?: DEFAULT_SETTINGS.cacheSize, taskTimeout = DEFAULT_SETTINGS.taskTimeout, enableCaching = DEFAULT_SETTINGS.enableCaching, enableParallelExecution = DEFAULT_SETTINGS.enableParallelExecution, batchSize = DEFAULT_SETTINGS.batchSize, enableOptimizationLogging = DEFAULT_SETTINGS.enableOptimizationLogging, gpuAcceleration = DEFAULT_SETTINGS.gpuAcceleration, memoryLimitMb = DEFAULT_SETTINGS.memoryLimitMb)
    }

    suspend fun initialize() {
        val prefs = currentSettings.first()
        if (prefs.preset == PRESET_BALANCED) {
            applySettings(DEFAULT_SETTINGS)
        }
    }

    suspend fun applySettings(settings: PerformanceSettings) {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.PERFORMANCE_PRESET] = settings.preset
            prefs[SettingsKeys.ENABLE_FEDERATED_LEARNING] = settings.enableFederatedLearning
            prefs[SettingsKeys.ENABLE_SELF_HEALING] = settings.enableSelfHealing
            prefs[SettingsKeys.ENABLE_KNOWLEDGE_GRAPH] = settings.enableKnowledgeGraph
            prefs[SettingsKeys.ENABLE_REAL_TIME_SYNC] = settings.enableRealTimeSync
            prefs[SettingsKeys.ENABLE_AUTO_SCALING] = settings.enableAutoScaling
            prefs[SettingsKeys.LEARNING_RATE] = settings.learningRate
            prefs[SettingsKeys.MAX_AGENTS] = settings.maxAgents
            prefs[SettingsKeys.CACHE_SIZE] = settings.cacheSize
        }
        android.util.Log.d(TAG, "已应用性能设置: ${settings.preset}")
    }

    fun applyPreset(preset: String) {
        val presetSettings = PRESETS[preset] ?: DEFAULT_SETTINGS
        scope.launch { applySettings(presetSettings) }
    }

    suspend fun resetToDefaults() { applySettings(DEFAULT_SETTINGS) }

    fun destroy() {
        scope.cancel()
    }

    fun getAvailablePresets(): List<PresetInfo> = listOf(
        PresetInfo(PRESET_BALANCED, "平衡模式", "兼顾性能和资源消耗的最佳平),
        PresetInfo(PRESET_HIGH_PERFORMANCE, "高性能模式", "最大程度提升系统性能，资源消耗较),
        PresetInfo(PRESET_LOW_POWER, "低功耗模, "最小化资源消耗，适合移动设备"),
        PresetInfo(PRESET_CUSTOM, "自定, "根据个人需求自定义配置")
    )
}

data class PerformanceSettings(
    val preset: String,
    val enableFederatedLearning: Boolean,
    val enableSelfHealing: Boolean,
    val enableKnowledgeGraph: Boolean,
    val enableRealTimeSync: Boolean,
    val enableAutoScaling: Boolean,
    val learningRate: Float,
    val maxAgents: Int,
    val cacheSize: Int,
    val taskTimeout: Int,
    val enableCaching: Boolean,
    val enableParallelExecution: Boolean,
    val batchSize: Int,
    val enableOptimizationLogging: Boolean,
    val gpuAcceleration: Boolean,
    val memoryLimitMb: Int
)

data class PresetInfo(val id: String, val name: String, val description: String)
