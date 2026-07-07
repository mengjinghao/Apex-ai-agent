package com.apex.agent.kernel.burst

import android.content.Context
import android.content.SharedPreferences
import com.apex.agent.plugins.burst.base.PluginUpdateInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UpdateCheckState(
    val isChecking: Boolean = false,
    val availableUpdates: List<PluginUpdateInfo> = emptyList(),
    val lastCheckTime: Long = 0,
    val errorMessage: String? = null
)

class PluginUpdateChecker(
    private val context: Context,
    private val repositoryClient: PluginRepositoryClient,
    private val pluginLoader: BurstPluginLoader,
    private val pluginManager: PluginManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = context.getSharedPreferences("plugin_updates", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UpdateCheckState())
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    private var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean("auto_update", false)
        set(value) { prefs.edit().putBoolean("auto_update", value).apply() }

    private var checkIntervalMs: Long
        get() = prefs.getLong("check_interval", 86400000L)
        set(value) { prefs.edit().putLong("check_interval", value).apply() }

    fun setAutoUpdate(enabled: Boolean) { autoUpdateEnabled = enabled }
    fun isAutoUpdateEnabled(): Boolean = autoUpdateEnabled
    fun setCheckInterval(ms: Long) { checkIntervalMs = ms }
    fun getCheckInterval(): Long = checkIntervalMs

    fun startPeriodicCheck() {
        scope.launch {
            while (isActive) {
                val lastCheck = _state.value.lastCheckTime
                if (System.currentTimeMillis() - lastCheck >= checkIntervalMs) {
                    checkForUpdates()
                }
                delay(60000)
            }
        }
    }

    fun stopPeriodicCheck() {
        scope.cancel()
    }

    suspend fun checkForUpdates(): List<PluginUpdateInfo> {
        if (_state.value.isChecking) return _state.value.availableUpdates

        _state.value = _state.value.copy(isChecking = true, errorMessage = null)

        try {
            val installedPlugins = mutableMapOf<String, String>()
            pluginLoader.getLoadedSkills().forEach { skillId ->
                pluginLoader.getSkillManifest(skillId)?.let { manifest ->
                    installedPlugins[skillId] = manifest.version
                }
            }

            val updates = repositoryClient.checkForUpdates(installedPlugins)

            _state.value = _state.value.copy(
                isChecking = false,
                availableUpdates = updates,
                lastCheckTime = System.currentTimeMillis()
            )

            if (autoUpdateEnabled) {
                updates.forEach { update ->
                    if (shouldAutoUpdate(update)) {
                        applyUpdate(update)
                    }
                }
            }

            return updates
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isChecking = false,
                errorMessage = e.message
            )
            return emptyList()
        }
    }

    suspend fun applyUpdate(update: PluginUpdateInfo): Boolean {
        return try {
            val result = repositoryClient.downloadPlugin(update.pluginId, update.latestVersion)
            if (!result.success) return false

            val updateResult = pluginManager.updatePlugin(update.pluginId, result.localPath)
            updateResult.success
        } catch (e: Exception) { false }
    }

    fun applyUpdateAsync(update: PluginUpdateInfo, onComplete: (Boolean) -> Unit) {
        scope.launch {
            val success = applyUpdate(update)
            onComplete(success)
        }
    }

    fun applyAllUpdates(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        scope.launch {
            val updates = _state.value.availableUpdates
            updates.forEachIndexed { index, update ->
                applyUpdate(update)
                onProgress(index + 1, updates.size)
            }
        }
    }

    fun dismissUpdate(pluginId: String) {
        _state.value = _state.value.copy(
            availableUpdates = _state.value.availableUpdates.filter { it.pluginId != pluginId }
        )
    }

    fun clearAllUpdates() {
        _state.value = _state.value.copy(availableUpdates = emptyList())
    }

    private fun shouldAutoUpdate(update: PluginUpdateInfo): Boolean {
        val currentParts = update.currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = update.latestVersion.split(".").map { it.toIntOrNull() ?: 0 }

        val currentMajor = currentParts.getOrElse(0) { 0 }
        val latestMajor = latestParts.getOrElse(0) { 0 }

        return latestMajor == currentMajor
    }
}
