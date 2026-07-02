package com.apex.agent.core.tools.system

import com.apex.agent.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 权限模式管理器性能优化扩展
 */
internal class PermissionModeStateCache {
    companion object {
        private const val TAG = "PermissionModeCache"
        private const val CACHE_TTL_MS = 30_000L // 30�?
    }

    private val stateCache = ConcurrentHashMap<PermissionMode, CachedState>()

    data class CachedState(
        val state: PermissionModeState,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 获取缓存的状�?
     */
    fun get(mode: PermissionMode): PermissionModeState? {
        val cached = stateCache[mode] ?: return null
        val now = System.currentTimeMillis()

        if (now - cached.timestamp > CACHE_TTL_MS) {
            stateCache.remove(mode)
            return null
        }

        return cached.state
    }

    /**
     * 缓存状�?
     */
    fun put(mode: PermissionMode, state: PermissionModeState) {
        stateCache[mode] = CachedState(state)
        AppLogger.v(TAG, "缓存状�? ${mode.displayName}")
    }

    /**
     * 清除缓存
     */
    fun clear(mode: PermissionMode? = null) {
        if (mode != null) {
            stateCache.remove(mode)
            AppLogger.d(TAG, "清除缓存: ${mode.displayName}")
        } else {
            stateCache.clear()
            AppLogger.d(TAG, "清除所有缓�?)
        }
    }

    /**
     * 清除过期缓存
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        val expiredKeys = stateCache.filterValues {
            now - it.timestamp > CACHE_TTL_MS
        }.keys

        expiredKeys.forEach { stateCache.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            AppLogger.d(TAG, "清除�?${expiredKeys.size} 个过期缓存项")
        }
    }
}

/**
 * 批量检测结�?
 */
data class BatchDetectionResult(
    val modeStates: Map<PermissionMode, PermissionModeState>,
    val durationMs: Long
)

/**
 * 权限模式管理器性能优化
 */
internal suspend fun PermissionModeManager.checkAllModesOptimized(
    forceRefresh: Boolean = false
): BatchDetectionResult {
    val startTime = System.currentTimeMillis()

    val states = mutableMapOf<PermissionMode, PermissionModeState>()

    // 并行检测独立的模式
    for (mode in PermissionMode.values()) {
        if (!forceRefresh) {
            // 尝试使用缓存
            val cached = stateCache.get(mode)
            if (cached != null) {
                states[mode] = cached
                continue
            }
        }

        // 检测模式状�?
        val state = checkModeInternal(mode)
        states[mode] = state
        stateCache.put(mode, state)
    }

    // 更新状态流
    _modeStates.update { states }
    notifyStateChanges(states.values)

    val duration = System.currentTimeMillis() - startTime
    AppLogger.d(TAG, "批量检测完成，耗时: ${duration}ms")

    return BatchDetectionResult(states, duration)
}

/**
 * 内部检测模式（不使用缓存）
 */
private suspend fun PermissionModeManager.checkModeInternal(
    mode: PermissionMode
): PermissionModeState {
    val timestamp = System.currentTimeMillis()

    return when (mode) {
        PermissionMode.STANDARD -> checkStandardMode(timestamp)
        PermissionMode.ACCESSIBILITY -> checkAccessibilityMode(timestamp)
        PermissionMode.DEBUGGER -> checkDebuggerMode(timestamp)
        PermissionMode.ADMIN -> checkAdminMode(timestamp)
        PermissionMode.SHIZUKU -> checkShizukuMode(timestamp)
        PermissionMode.ROOT -> checkRootMode(timestamp)
    }
}

/**
 * 缓存实例
 */
private val PermissionModeManager.stateCache: PermissionModeStateCache
    get() = PermissionModeStateCache()

/**
 * 性能监控
 */
class PermissionModePerformanceMonitor {
    companion object {
        private const val TAG = "PermissionModePerf"
    }

    private val checkTimes = mutableListOf<Long>()
    private var checkCount = 0
    private var cacheHits = 0
    private var cacheMisses = 0

    /**
     * 记录检测时�?
     */
    fun recordCheckTime(duration: Long) {
        checkTimes.add(duration)
        checkCount++

        // 保留最�?00条记�?
        if (checkTimes.size > 100) {
            checkTimes.removeAt(0)
        }
    }

    /**
     * 记录缓存命中
     */
    fun recordCacheHit() {
        cacheHits++
    }

    /**
     * 记录缓存未命�?
     */
    fun recordCacheMiss() {
        cacheMisses++
    }

    /**
     * 获取性能统计
     */
    fun getStatistics(): PerformanceStatistics {
        val avgTime = if (checkTimes.isNotEmpty()) {
            checkTimes.average()
        } else {
            0.0
        }

        val totalCacheLookups = cacheHits + cacheMisses
        val hitRate = if (totalCacheLookups > 0) {
            (cacheHits.toDouble() / totalCacheLookups) * 100
        } else {
            0.0
        }

        return PerformanceStatistics(
            checkCount = checkCount,
            avgCheckTimeMs = avgTime,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheHitRate = hitRate
        )
    }

    /**
     * 重置统计
     */
    fun reset() {
        checkTimes.clear()
        checkCount = 0
        cacheHits = 0
        cacheMisses = 0
        AppLogger.d(TAG, "性能统计已重�?)
    }
}

/**
 * 性能统计
 */
data class PerformanceStatistics(
    val checkCount: Int,
    val avgCheckTimeMs: Double,
    val cacheHits: Int,
    val cacheMisses: Int,
    val cacheHitRate: Double
)

/**
 * 监控实例
 */
private val performanceMonitor = PermissionModePerformanceMonitor()

/**
 * 获取性能监控�?
 */
val PermissionModeManager.performanceMonitor: PermissionModePerformanceMonitor
    get() = com.apex.agent.core.tools.system.performanceMonitor

/**
 * 带性能监控的检�?
 */
suspend fun PermissionModeManager.checkAllModesWithMonitor(
    forceRefresh: Boolean = false
): BatchDetectionResult {
    val startTime = System.currentTimeMillis()

    val result = checkAllModesOptimized(forceRefresh)

    performanceMonitor.recordCheckTime(result.durationMs)

    val stats = performanceMonitor.getStatistics()
    AppLogger.d(TAG, "性能统计: checks=${stats.checkCount}, " +
            "avg=${String.format("%.2f", stats.avgCheckTimeMs)}ms, " +
            "hitRate=${String.format("%.1f", stats.cacheHitRate)}%")

    return result
}
