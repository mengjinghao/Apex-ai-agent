package com.apex.plugins.skill

import android.content.Context
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class SkillUpdateChecker private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillUpdateChecker"

        @Volatile
        private var INSTANCE: SkillUpdateChecker? = null

        fun getInstance(context: Context): SkillUpdateChecker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillUpdateChecker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val marketplace = SkillPluginMarketplace.getInstance(context)
    private val pluginManager = SkillPluginManager.getInstance(context)

    private val _availableUpdates = MutableStateFlow<List<SkillPluginUpdate>>(emptyList())
    val availableUpdates: StateFlow<List<SkillPluginUpdate>> = _availableUpdates.asStateFlow()

    private val _lastCheckTime = MutableStateFlow(0L)
    val lastCheckTime: StateFlow<Long> = _lastCheckTime.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val notifiedUpdates = ConcurrentHashMap.newKeySet<String>()

    fun checkForUpdates() {
        scope.launch {
            _isChecking.value = true
            try {
                val updates = marketplace.checkUpdates()
                _availableUpdates.value = updates
                _lastCheckTime.value = System.currentTimeMillis()

                if (updates.isNotEmpty()) {
                    val newUpdates = updates.filter { !notifiedUpdates.contains(it.pluginId) }
                    if (newUpdates.isNotEmpty()) {
                        newUpdates.forEach { notifiedUpdates.add(it.pluginId) }
                        AppLogger.i(
                            TAG,
                            "发现 ${newUpdates.size} 个新更新"
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "检查更新失�?, e)
            } finally {
                _isChecking.value = false
            }
        }
    }

    suspend fun checkForUpdatesSync(): List<SkillPluginUpdate> = withContext(Dispatchers.IO) {
        _isChecking.value = true
        try {
            val updates = marketplace.checkUpdates()
            _availableUpdates.value = updates
            _lastCheckTime.value = System.currentTimeMillis()
            updates
        } catch (e: Exception) {
            AppLogger.e(TAG, "同步检查更新失�?, e)
            emptyList()
        } finally {
            _isChecking.value = false
        }
    }

    suspend fun applyUpdate(update: SkillPluginUpdate): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "开始更新插�? ${update.pluginId} (${update.currentVersion} -> ${update.latestVersion})")

            val pluginManager = pluginManager
            val existingPlugin = pluginManager.getPlugin(update.pluginId)
            if (existingPlugin == null) {
                return@withContext Result.failure(Exception("插件 ${update.pluginId} 未安�?))
            }

            pluginManager.disablePlugin(update.pluginId)
            pluginManager.unregisterPlugin(update.pluginId)

            val downloadedFile = marketplace.downloadPlugin(update.pluginId)

            val loader = SkillPluginLoader.getInstance(context)
            val newPlugin = loader.loadPlugin(downloadedFile)
            newPlugin.onLoad(context)

            pluginManager.registerPlugin(newPlugin)
            pluginManager.enablePlugin(update.pluginId)

            notifiedUpdates.remove(update.pluginId)
            _availableUpdates.value = _availableUpdates.value.filter { it.pluginId != update.pluginId }

            AppLogger.i(TAG, "插件更新完成: ${update.pluginId} v${update.latestVersion}")
            Result.success(update.latestVersion)
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新插件失败: ${update.pluginId}", e)
            Result.failure(e)
        }
    }

    fun getUpdateCount(): Int = _availableUpdates.value.size

    suspend fun hasUpdates(): Boolean = withContext(Dispatchers.IO) {
        _availableUpdates.value.isNotEmpty()
    }

    fun dismissNotification(pluginId: String) {
        notifiedUpdates.remove(pluginId)
    }

    fun dismissAll() {
        notifiedUpdates.clear()
    }
}
