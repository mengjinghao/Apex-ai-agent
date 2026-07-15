package com.apex.agent.core.tools.system.shell

import android.content.Context
import com.apex.util.AppLogger
import com.apex.agent.core.tools.system.PermissionMode
import com.apex.agent.core.tools.system.PermissionModeManager
import com.apex.agent.data.preferences.androidPermissionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.apex.agent.core.tools.defaultTool.standard.name
import com.apex.agent.core.tools.system.shell.AdminShellExecutor
import com.apex.agent.core.tools.system.shell.DebuggerShellExecutor
import com.apex.agent.core.tools.system.shell.PermissionStatus
import com.apex.agent.core.tools.system.shell.StandardShellExecutor

/**
 * 权限模式执行器工�?- 根据 PermissionMode 创建对应�?Shell 执行�?
 */
class PermissionModeExecutorFactory private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PermissionModeExecutorFactory"
        private const val CACHE_TTL = 300000L // 5分钟

        @Volatile
        private var instance: PermissionModeExecutorFactory? = null

        fun getInstance(context: Context): PermissionModeExecutorFactory =
            instance ?: synchronized(this) {
                instance ?: PermissionModeExecutorFactory(context.applicationContext).also {
                    instance = it
                }
            }
    }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mutex = Mutex()
        private val _executorCache = MutableStateFlow<Map<PermissionMode, ShellExecutor>>(emptyMap())
        val executorCache: StateFlow<Map<PermissionMode, ShellExecutor>> = _executorCache.asStateFlow()
        private val _currentExecutor = MutableStateFlow<ShellExecutor?>(null)
        val currentExecutor: StateFlow<ShellExecutor?> = _currentExecutor.asStateFlow()
        private val _isInitializing = MutableStateFlow(false)
        val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()
        private val executorCreationTime = ConcurrentHashMap<PermissionMode, Long>()
        private val modeManager by lazy { PermissionModeManager.getInstance(context) }

    /**
     * 获取指定权限模式的执行器
     */
    suspend fun getExecutor(mode: PermissionMode): ShellExecutor = mutex.withLock {
        val cached = _executorCache.value[mode]
        val now = System.currentTimeMillis()

        // 检查缓存是否有�?
    if (cached != null) {
            val creationTime = executorCreationTime[mode] ?: 0L
            if (now - creationTime < CACHE_TTL && cached.isAvailable()) {
                AppLogger.v(TAG, "使用缓存�?${mode.displayName} 执行�?)
        return cached
            }
        }

        AppLogger.d(TAG, "创建 ${mode.displayName} 执行�?..")
        val executor = createExecutor(mode)
        executor.initialize()

        _executorCache.update { current ->
            current.toMutableMap().apply { put(mode, executor) }
        }
        executorCreationTime[mode] = now

        return executor
    }
        private fun createExecutor(mode: PermissionMode): ShellExecutor =
        when (mode) {
            PermissionMode.STANDARD -> StandardShellExecutor(context)
            PermissionMode.ACCESSIBILITY -> AccessibilityShellExecutor(context)
            PermissionMode.DEBUGGER -> DebuggerShellExecutor(context)
            PermissionMode.ADMIN -> AdminShellExecutor(context)
            PermissionMode.SHIZUKU -> ShizukuShellExecutor(context)
            PermissionMode.ROOT -> RootShellExecutor(context)
        }

    /**
     * 获取当前模式的执行器
     */
    suspend fun getCurrentModeExecutor(): ShellExecutor {
        val currentMode = modeManager.currentMode.value
            ?: modeManager.getBestAvailableMode()
            ?: PermissionMode.STANDARD

        return getExecutor(currentMode)
    }

    /**
     * 获取最佳可用执行器
     */
    suspend fun getBestAvailableExecutor(): Pair<ShellExecutor, PermissionMode> {
        val bestMode = modeManager.getBestAvailableMode()
        val executor = getExecutor(bestMode)
        return executor to bestMode
    }

    /**
     * 获取用户首选执行器
     */
    suspend fun getUserPreferredExecutor(): ShellExecutor {
        val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
        val preferredMode = preferredLevel?.let {
            PermissionMode.values().find { mode ->
                mode.displayName.contains(preferredLevel.name, ignoreCase = true)
            }
        } ?: modeManager.getBestAvailableMode() ?: PermissionMode.STANDARD

        return getExecutor(preferredMode)
    }

    /**
     * 尝试按优先级顺序获取执行�?
     */
    suspend fun tryGetExecutor(
        preferredModes: List<PermissionMode> = PermissionMode.sortedByLevelDesc()
    ): ShellExecutor {
        for (mode in preferredModes) {
            try {
                val executor = getExecutor(mode)
        if (executor.isAvailable() && executor.hasPermission().granted) {
                    AppLogger.d(TAG, "使用 ${mode.displayName} 执行�?)
        return executor
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "获取 ${mode.displayName} 执行器失�?, e)
            }
        }

        AppLogger.w(TAG, "所有高级执行器不可用，回退到标准模�?)
        return getExecutor(PermissionMode.STANDARD)
    }

    /**
     * 预加载常用执行器
     */
    suspend fun preloadExecutors(modes: List<PermissionMode> = listOf(
        PermissionMode.STANDARD,
        PermissionMode.ROOT,
        PermissionMode.SHIZUKU
    )) {
        _isInitializing.value = true
        try {
            AppLogger.d(TAG, "预加载执行器...")
            modes.forEach { mode ->
                try {
                    getExecutor(mode)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "预加�?${mode.displayName} 执行器失�?, e)
                }
            }
            AppLogger.d(TAG, "执行器预加载完成")
        } finally {
            _isInitializing.value = false
        }
    }

    /**
     * 清除指定模式的缓�?
     */
    fun clearCache(mode: PermissionMode? = null) {
        if (mode != null) {
            _executorCache.update { current ->
                current.toMutableMap().apply { remove(mode) }
            }
            executorCreationTime.remove(mode)
            AppLogger.d(TAG, "已清�?${mode.displayName} 执行器缓�?)
        } else {
            _executorCache.update { emptyMap() }
            executorCreationTime.clear()
            AppLogger.d(TAG, "已清除所有执行器缓存")
        }
    }

    /**
     * 刷新所有执行器
     */
    suspend fun refreshAll() {
        clearCache()
        preloadExecutors()
    }

    /**
     * 获取所有可用执行器及其状�?
     */
    suspend fun getAvailableExecutors(): Map<PermissionMode, Pair<ShellExecutor, ShellExecutor.PermissionStatus>> {
        val result = mutableMapOf<PermissionMode, Pair<ShellExecutor, ShellExecutor.PermissionStatus>>()
        for (mode in PermissionMode.values()) {
            try {
                val executor = getExecutor(mode)
        val status = executor.hasPermission()
                result[mode] = executor to status
            } catch (e: Exception) {
                AppLogger.w(TAG, "获取 ${mode.displayName} 执行器状态失�?, e)
            }
        }
        return result
    }
}
