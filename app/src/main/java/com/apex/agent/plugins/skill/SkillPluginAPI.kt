package com.apex.plugins.skill

import android.content.Context
import java.io.File

interface SkillPlugin {
    val id: String
    val name: String
    val version: String
    val author: String
    val description: String
    val category: SkillPluginCategory
    val dependencies: List<String>
    val apiVersion: String

    fun onLoad(context: Context)
        fun onUnload()
        fun onEnable()
        fun onDisable()
        fun onSkillInvoked(skillName: String, params: Map<String, Any>)
        fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

enum class SkillPluginCategory {
    ANALYSIS,
    RECOMMENDATION,
    AUTOMATION,
    INTEGRATION,
    VISUALIZATION,
    SECURITY,
    CUSTOM
}

interface SkillPluginContext {
    fun getPluginDirectory(): File
    fun getPluginDataDirectory(pluginId: String): File
    fun getAppContext(): Context
    fun registerService(service: Any)
        fun <T> getService(serviceClass: Class<T>): T?
    fun getSkillManager(): com.apex.core.tools.skill.SkillManager
    fun getUsageTracker(): com.apex.core.tools.skill.SkillUsageTracker
}

interface SkillPluginDescriptor {
    val pluginId: String
    val name: String
    val version: String
    val author: String
    val description: String
    val category: SkillPluginCategory
    val supportedSkills: List<String>
    val dependencies: List<String>
    val minApiVersion: String
    val iconUrl: String?
    val permissions: List<String>
}

interface SkillPluginLoader {
    fun loadPlugin(pluginFile: File): SkillPlugin
    fun loadPluginDescriptor(pluginFile: File): SkillPluginDescriptor
    fun validatePlugin(pluginFile: File): PluginValidationResult
    fun getPluginClassLoader(pluginId: String): ClassLoader?
}

interface SkillPluginManager {
    fun registerPlugin(plugin: SkillPlugin)
        fun unregisterPlugin(pluginId: String)
        fun enablePlugin(pluginId: String)
        fun disablePlugin(pluginId: String)
        fun getPlugin(pluginId: String): SkillPlugin?
    fun getAllPlugins(): List<SkillPlugin>
    fun getEnabledPlugins(): List<SkillPlugin>
    fun getPluginsByCategory(category: SkillPluginCategory): List<SkillPlugin>
    fun isPluginEnabled(pluginId: String): Boolean
}

interface SkillPluginMarketplace {
    suspend fun searchPlugins(query: String, category: SkillPluginCategory? = null): List<SkillPluginListing>
    suspend fun getPluginDetails(pluginId: String): SkillPluginListing?
    suspend fun downloadPlugin(pluginId: String): File
    suspend fun checkUpdates(): List<SkillPluginUpdate>
    suspend fun getFeaturedPlugins(): List<SkillPluginListing>
    suspend fun getPopularPlugins(limit: Int = 10): List<SkillPluginListing>
    suspend fun getRecommendedPlugins(userId: String): List<SkillPluginListing>
}

interface SkillPluginListing {
    val id: String
    val name: String
    val version: String
    val author: String
    val description: String
    val category: SkillPluginCategory
    val downloadUrl: String
    val iconUrl: String?
    val rating: Double
    val downloadCount: Int
    val tags: List<String>
    val createdAt: Long
    val updatedAt: Long
}

interface SkillPluginUpdate {
    val pluginId: String
    val currentVersion: String
    val latestVersion: String
    val updateUrl: String
    val changelog: String
    val isSecurityUpdate: Boolean
}

data class PluginValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val descriptor: SkillPluginDescriptor? = null
)

interface PluginEventListener {
    fun onPluginLoaded(plugin: SkillPlugin)
        fun onPluginUnloaded(pluginId: String)
        fun onPluginEnabled(pluginId: String)
        fun onPluginDisabled(pluginId: String)
        fun onPluginError(pluginId: String, error: Throwable)
        fun onSkillInvoked(skillName: String, pluginId: String, params: Map<String, Any>)
}

abstract class SkillPluginAdapter(
    override val id: String,
    override val name: String,
    override val version: String,
    override val author: String,
    override val description: String,
    override val category: SkillPluginCategory,
    override val dependencies: List<String> = emptyList(),
    override val apiVersion: String = "1.0"
) : SkillPlugin {

    private var _enabled = false
    protected val isInitialized: Boolean
        get() = _enabled

    override fun onLoad(context: Context) {}
    override fun onUnload() {}
    override fun onEnable() { _enabled = true }
    override fun onDisable() { _enabled = false }
    override fun onSkillInvoked(skillName: String, params: Map<String, Any>) {}

    override fun isEnabled(): Boolean = _enabled
    override fun setEnabled(enabled: Boolean) {
        _enabled = enabled
    }
}

object SkillPluginConstants {
    const val PLUGIN_API_VERSION = "1.0"
    const val PLUGIN_DIR = "skill_plugins"
    const val PLUGIN_DATA_DIR = "plugin_data"
    const val PLUGIN_CACHE_DIR = "plugin_cache"
  const val PLUGIN_CONFIG_FILE = "plugin.json"
    const val PLUGIN_ICON_FILE = "icon.png"
    const val MIN_COMPATIBLE_VERSION = "1.0"
}
