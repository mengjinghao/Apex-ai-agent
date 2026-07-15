package com.apex.agent.core.tools.defaultTool.websession.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "WebSessionOptStorage"

/**
 * Web AI 助手优化数据存储
 * 
 * 功能。
 * - 持久化保存使用统计数。
 * - 存储学习的选择。
 * - 记录优化提示
 * - 服务级别性能跟踪
 * 
 * 设计理念。
 * - 简单本地存储（无加密，Agent 内部管理）
 * - SharedPreferences 快速读写
 * - JSON 序列化结构化数据
 */
internal class WebSessionOptimizationStorage private constructor(
    private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
        private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val PREFS_NAME = "web_ai_optimization"
        private const val KEY_OPTIMIZATION_DATA = "optimization_data"
        
        @Volatile
        private var instance: WebSessionOptimizationStorage? = null

        fun getInstance(context: Context): WebSessionOptimizationStorage {
            return instance ?: synchronized(this) {
                instance ?: WebSessionOptimizationStorage(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * 保存优化数据
     * 
     * @param data JSON 格式的优化数据
     */
    fun saveOptimizationData(data: String) {
        try {
            prefs.edit().putString(KEY_OPTIMIZATION_DATA, data).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveOptimizationData failed", e)
        }
    }

    /**
     * 获取优化数据
     * 
     * @return JSON 格式的优化数据，如果没有则返回 null
     */
    fun getOptimizationData(): String? {
        return try {
            prefs.getString(KEY_OPTIMIZATION_DATA, null)
        } catch (e: Exception) {
            Log.e(TAG, "getOptimizationData failed", e)
            null
        }
    }

    /**
     * 保存结构化的优化数据对象
     * 
     * @param optimizationData 优化数据对象
     */
    fun saveOptimizationObject(optimizationData: OptimizationData) {
        try {
            val jsonStr = json.encodeToString(optimizationData)
            saveOptimizationData(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "saveOptimizationObject failed", e)
        }
    }

    /**
     * 获取结构化的优化数据对象
     * 
     * @return 优化数据对象，如果解析失败则返回 null
     */
    fun getOptimizationObject(): OptimizationData? {
        return try {
            val jsonStr = getOptimizationData() ?: return null
            json.decodeFromString<OptimizationData>(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "getOptimizationObject failed", e)
            null
        }
    }

    /**
     * 清除所有优化数。
     */
    fun clearOptimizationData() {
        prefs.edit().remove(KEY_OPTIMIZATION_DATA).apply()
    }

    /**
     * 检查是否有保存的优化数。
     */
    fun hasOptimizationData(): Boolean {
        return prefs.contains(KEY_OPTIMIZATION_DATA)
    }

    /**
     * 获取最后更新时间戳
     */
    fun getLastUpdateTime(): Long {
        val data = getOptimizationObject()
        return data?.timestamp ?: 0L
    }
}

/**
 * 优化数据结构
 */
@Serializable
data class OptimizationData(
    val usageStats: UsageStats = UsageStats(),
    val learnedSelectors: Map<String, ServiceSelectors> = emptyMap(),
    val optimizationHints: List<OptimizationHint> = emptyList(),
    val timestamp: Long = 0L
)

/**
 * 使用统计
 */
@Serializable
data class UsageStats(
    val totalCalls: Int = 0,
    val successfulCalls: Int = 0,
    val failedCalls: Int = 0,
    val retryCount: Int = 0,
    val lastSuccess: LastOperation? = null,
    val lastError: LastError? = null,
    val methodSuccessRates: Map<String, MethodStats> = emptyMap(),
    val serviceStats: Map<String, ServiceStats> = emptyMap()
)

@Serializable
data class LastOperation(
    val operation: String = "",
    val timestamp: Long = 0L,
    val details: String = ""
)

@Serializable
data class LastError(
    val operation: String = "",
    val error: String = "",
    val timestamp: Long = 0L
)

@Serializable
data class MethodStats(
    val success: Int = 0,
    val total: Int = 0
)

@Serializable
data class ServiceStats(
    val totalCalls: Int = 0,
    val successfulCalls: Int = 0,
    val failedCalls: Int = 0,
    val successRate: String = "0",
    val lastUsed: Long? = null,
    val commonErrors: List<String> = emptyList()
)

/**
 * 服务选择。
 */
@Serializable
data class ServiceSelectors(
    val input: List<String> = emptyList(),
    val sendButton: List<String> = emptyList(),
    val modeToggles: List<String> = emptyList()
)

/**
 * 优化提示
 */
@Serializable
data class OptimizationHint(
    val operation: String = "",
    val error: String = "",
    val timestamp: Long = 0L,
    val suggestion: String = ""
)
