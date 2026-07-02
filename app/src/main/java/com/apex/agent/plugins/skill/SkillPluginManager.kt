package com.apex.plugins.skill

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apex.core.tools.skill.SkillManager
import com.apex.core.tools.skill.SkillUsageTracker
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val Context.pluginManagerDataStore: DataStore<Preferences> by preferencesDataStore(name = "skill_plugin_manager")

class SkillPluginManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillPluginManager"

        @Volatile private var INSTANCE: SkillPluginManager? = null

        private val KEY_ENABLED_PLUGINS = stringPreferencesKey("enabled_plugins")
        private val KEY_PLUGIN_SETTINGS = stringPreferencesKey("plugin_settings")
        private val KEY_PLUGIN_ORDER = stringPreferencesKey("plugin_order")

        fun getInstance(context: Context): SkillPluginManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillPluginManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val plugins = ConcurrentHashMap<String, SkillPlugin>()
    private val pluginScopes = ConcurrentHashMap<String, CoroutineScope>()
    private val eventListeners = CopyOnWriteArrayList<PluginEventListener>()

    private val enabledPluginIds = ConcurrentHashMap.newKeySet<String>()
    private val pluginSettings = ConcurrentHashMap<String, Map<String, Any>>()
    private var pluginOrder = listOf<String>()

    private val loader by lazy { SkillPluginLoader.getInstance(context) }
    private val skillManager by lazy { SkillManager.getInstance(context) }
    private val usageTracker by lazy { SkillUsageTracker.getInstance(context) }

    private val scope = CoroutineScope(Dispatchers.Main)

    val pluginCount: Int get() = plugins.size
    val enabledCount: Int get() = enabledPluginIds.size

    fun registerPlugin(plugin: SkillPlugin) {
        val pluginId = plugin.id
        AppLogger.d(TAG, "Registering plugin: ${pluginId}")

        plugins[pluginId]?.let { existing ->
            AppLogger.w(TAG, "Plugin ${pluginId} is already registered, replacing")
            unregisterPlugin(pluginId)
        }

        plugins[pluginId] = plugin
        savePluginOrder()
        notifyPluginLoaded(plugin)

        AppLogger.i(TAG, "Plugin registered: ${pluginId}")
    }

    fun unregisterPlugin(pluginId: String) {
        val plugin = plugins.remove(pluginId)
        if (plugin != null) {
            if (plugin.isEnabled) {
                disablePlugin(pluginId)
            }
            loader.unloadPlugin(pluginId)
            pluginScopes.remove(pluginId)
            enabledPluginIds.remove(pluginId)
            savePluginOrder()
            notifyPluginUnloaded(pluginId)
            AppLogger.i(TAG, "Plugin unregistered: ${pluginId}")
        }
    }

    fun enablePlugin(pluginId: String) {
        val plugin = plugins[pluginId] ?: run {
            AppLogger.w(TAG, "Cannot enable plugin ${pluginId}: not found")
            return
        }

        if (plugin.isEnabled) {
            AppLogger.d(TAG, "Plugin ${pluginId} is already enabled")
            return
        }

        try {
            plugin.onEnable()
            enabledPluginIds.add(pluginId)
            persistEnabledState()
            notifyPluginEnabled(pluginId)
            AppLogger.i(TAG, "Plugin enabled: ${pluginId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to enable plugin ${pluginId}", e)
            notifyPluginError(pluginId, e)
        }
    }

    fun disablePlugin(pluginId: String) {
        val plugin = plugins[pluginId] ?: run {
            AppLogger.w(TAG, "Cannot disable plugin ${pluginId}: not found")
            return
        }

        if (!plugin.isEnabled) {
            AppLogger.d(TAG, "Plugin ${pluginId} is already disabled")
            return
        }

        try {
            plugin.onDisable()
            enabledPluginIds.remove(pluginId)
            persistEnabledState()
            notifyPluginDisabled(pluginId)
            AppLogger.i(TAG, "Plugin disabled: ${pluginId}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to disable plugin ${pluginId}", e)
            notifyPluginError(pluginId, e)
        }
    }

    fun getPlugin(pluginId: String): SkillPlugin? = plugins[pluginId]

    fun getAllPlugins(): List<SkillPlugin> = plugins.values.toList()

    fun getEnabledPlugins(): List<SkillPlugin> {
        return enabledPluginIds.mapNotNull { plugins[it] }
    }

    fun getPluginsByCategory(category: SkillPluginCategory): List<SkillPlugin> {
        return plugins.values.filter { it.category == category }
    }

    fun isPluginEnabled(pluginId: String): Boolean = enabledPluginIds.contains(pluginId)

    fun getPluginSettings(pluginId: String): Map<String, Any> {
        return pluginSettings[pluginId] ?: emptyMap()
    }

    fun setPluginSettings(pluginId: String, settings: Map<String, Any>) {
        pluginSettings[pluginId] = settings
        persistSettings()
    }

    fun updatePluginSetting(pluginId: String, key: String, value: Any) {
        val current = pluginSettings[pluginId]?.toMutableMap() ?: mutableMapOf()
        current[key] = value
        pluginSettings[pluginId] = current
        persistSettings()
    }

    fun addEventListener(listener: PluginEventListener) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: PluginEventListener) {
        eventListeners.remove(listener)
    }

    fun onSkillInvoked(skillName: String, params: Map<String, Any>) {
        enabledPlugins.forEach { plugin ->
            try {
                plugin.onSkillInvoked(skillName, params)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in plugin ${plugin.id} for skill ${skillName}", e)
                notifyPluginError(plugin.id, e)
            }
        }
    }

    private val enabledPlugins: List<SkillPlugin>
        get() = enabledPluginIds.mapNotNull { plugins[it] }

    suspend fun initialize() {
        AppLogger.d(TAG, "Initializing plugin manager")
        loadPersistedState()
        AppLogger.i(TAG, "Plugin manager initialized with ${plugins.size} plugins, ${enabledPluginIds.size} enabled")
    }

    private suspend fun loadPersistedState() {
        context.pluginManagerDataStore.data.first().let { preferences ->
            val enabledJson = preferences[KEY_ENABLED_PLUGINS] ?: "[]"
            val settingsJson = preferences[KEY_PLUGIN_SETTINGS] ?: "{}"
            val orderJson = preferences[KEY_PLUGIN_ORDER] ?: "[]"

            try {
                enabledPluginIds.clear()
                enabledPluginIds.addAll(json.decodeFromString<List<String>>(enabledJson))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load enabled plugins", e)
            }

            try {
                pluginSettings.clear()
                val settingsMap = json.decodeFromString<Map<String, Map<String, Any>>>(settingsJson)
                pluginSettings.putAll(settingsMap)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load plugin settings", e)
            }

            try {
                pluginOrder = json.decodeFromString<List<String>>(orderJson)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load plugin order", e)
            }
        }
    }

    private suspend fun persistEnabledState() {
        context.pluginManagerDataStore.edit { preferences ->
            preferences[KEY_ENABLED_PLUGINS] = json.encodeToString(enabledPluginIds.toList())
        }
    }

    private suspend fun persistSettings() {
        context.pluginManagerDataStore.edit { preferences ->
            preferences[KEY_PLUGIN_SETTINGS] = json.encodeToString(pluginSettings.toMap())
        }
    }

    private suspend fun savePluginOrder() {
        pluginOrder = plugins.keys.toList()
        context.pluginManagerDataStore.edit { preferences ->
            preferences[KEY_PLUGIN_ORDER] = json.encodeToString(pluginOrder)
        }
    }

    private fun notifyPluginLoaded(plugin: SkillPlugin) {
        eventListeners.forEach { listener ->
            try {
                listener.onPluginLoaded(plugin)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying plugin loaded", e)
            }
        }
    }

    private fun notifyPluginUnloaded(pluginId: String) {
        eventListeners.forEach { listener ->
            try {
                listener.onPluginUnloaded(pluginId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying plugin unloaded", e)
            }
        }
    }

    private fun notifyPluginEnabled(pluginId: String) {
        eventListeners.forEach { listener ->
            try {
                listener.onPluginEnabled(pluginId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying plugin enabled", e)
            }
        }
    }

    private fun notifyPluginDisabled(pluginId: String) {
        eventListeners.forEach { listener ->
            try {
                listener.onPluginDisabled(pluginId)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying plugin disabled", e)
            }
        }
    }

    private fun notifyPluginError(pluginId: String, error: Throwable) {
        eventListeners.forEach { listener ->
            try {
                listener.onPluginError(pluginId, error)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error notifying plugin error", e)
            }
        }
    }

    fun getPluginDirectory(): File = loader.getPluginsDirectory()

    fun getPluginDataDirectory(pluginId: String): File {
        val dataDir = File(context.filesDir, "${SkillPluginConstants.PLUGIN_DATA_DIR}/${pluginId}")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    fun createPluginContext(pluginId: String): SkillPluginContext {
        return object : SkillPluginContext {
            override fun getPluginDirectory(): File {
                return File(loader.getPluginsDirectory(), pluginId)
            }

            override fun getPluginDataDirectory(pluginId: String): File {
                return this@SkillPluginManager.getPluginDataDirectory(pluginId)
            }

            override fun getAppContext(): Context = context

            override fun registerService(service: Any) {
                AppLogger.d(TAG, "Service registered for plugin ${pluginId}: ${service.javaClass.simpleName}")
            }

            override fun <T> getService(serviceClass: Class<T>): T? {
                return context.getSystemService(serviceClass) as? T
            }

            override fun getSkillManager(): SkillManager = this@SkillPluginManager.skillManager

            override fun getUsageTracker(): SkillUsageTracker = this@SkillPluginManager.usageTracker
        }
    }

    class PluginState(
        val id: String,
        val name: String,
        val version: String,
        val category: SkillPluginCategory,
        val isEnabled: Boolean,
        val isLoaded: Boolean
    )

    fun getPluginStates(): List<PluginState> {
        return plugins.values.map { plugin ->
            PluginState(
                id = plugin.id,
                name = plugin.name,
                version = plugin.version,
                category = plugin.category,
                isEnabled = plugin.isEnabled(),
                isLoaded = true
            )
        }
    }
}

class DefaultPluginEventListener : PluginEventListener {
    override fun onPluginLoaded(plugin: SkillPlugin) {
        AppLogger.d("PluginListener", "Plugin loaded: ${plugin.id}")
    }

    override fun onPluginUnloaded(pluginId: String) {
        AppLogger.d("PluginListener", "Plugin unloaded: ${pluginId}")
    }

    override fun onPluginEnabled(pluginId: String) {
        AppLogger.d("PluginListener", "Plugin enabled: ${pluginId}")
    }

    override fun onPluginDisabled(pluginId: String) {
        AppLogger.d("PluginListener", "Plugin disabled: ${pluginId}")
    }

    override fun onPluginError(pluginId: String, error: Throwable) {
        AppLogger.e("PluginListener", "Plugin error in ${pluginId}", error)
    }

    override fun onSkillInvoked(skillName: String, pluginId: String, params: Map<String, Any>) {
        AppLogger.v("PluginListener", "Skill ${skillName} invoked by plugin ${pluginId}")
    }
}
