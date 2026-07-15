package com.apex.agent.core.tools.skill

import android.content.Context
import android.os.Environment
import com.apex.util.AppLogger
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class DevServerConfig private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DevServerConfig"

        const val DEFAULT_PORT = 8765
        const val DEFAULT_WS_PORT = 8766
        const val DEFAULT_HOT_RELOAD_DEBOUNCE_MS = 300L
        const val DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024L

        @Volatile private var INSTANCE: DevServerConfig? = null

        fun getInstance(context: Context): DevServerConfig {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DevServerConfig(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class ServerSettings(
        val port: Int = DEFAULT_PORT,
        val wsPort: Int = DEFAULT_WS_PORT,
        val host: String = "localhost",
        val enableCors: Boolean = true,
        val corsOrigins: List<String> = listOf("*"),
        val requestTimeoutMs: Long = 30000L,
        val maxConnections: Int = 50
    )

    data class HotReloadSettings(
        val enabled: Boolean = true,
        val debounceDelayMs: Long = DEFAULT_HOT_RELOAD_DEBOUNCE_MS,
        val watchExtensions: Set<String> = setOf(".js", ".ts", ".json", ".md", ".yaml", ".yml"),
        val ignorePatterns: Set<String> = setOf("node_modules", ".git", "dist", "build", ".gradle"),
        val watchDirectory: String = ""
    )

    data class EditorSettings(
        val theme: String = "vs-dark",
        val fontSize: Int = 14,
        val tabSize: Int = 2,
        val wordWrap: Boolean = true,
        val autoSave: Boolean = true,
        val autoSaveDelayMs: Long = 1000L,
        val syntaxHighlighting: Boolean = true,
        val minimapEnabled: Boolean = true,
        val lineNumbers: Boolean = true
    )

    data class PreviewSettings(
        val enableRealTimePreview: Boolean = true,
        val refreshDelayMs: Long = 500L,
        val defaultViewport: String = "mobile",
        val userAgent: String = "SkillDevAssistant/1.0"
    )
        private var serverSettings = ServerSettings()
        private var hotReloadSettings = HotReloadSettings()
        private var editorSettings = EditorSettings()
        private var previewSettings = PreviewSettings()
        private val configListeners = CopyOnWriteArrayList<ConfigListener>()

    interface ConfigListener {
        fun onServerSettingsChanged(settings: ServerSettings)
        fun onHotReloadSettingsChanged(settings: HotReloadSettings)
        fun onEditorSettingsChanged(settings: EditorSettings)
        fun onPreviewSettingsChanged(settings: PreviewSettings)
    }
        fun addConfigListener(listener: ConfigListener) {
        if (!configListeners.contains(listener)) {
            configListeners.add(listener)
        }
    }
        fun removeConfigListener(listener: ConfigListener) {
        configListeners.remove(listener)
    }
        fun getServerSettings(): ServerSettings = serverSettings

    fun updateServerSettings(settings: ServerSettings) {
        val old = serverSettings
        serverSettings = settings
        AppLogger.d(TAG, "Server settings updated: port=${settings.port}, host=${settings.host}")
        notifyServerSettingsChanged(settings)
    }
        fun getHotReloadSettings(): HotReloadSettings = hotReloadSettings

    fun updateHotReloadSettings(settings: HotReloadSettings) {
        val old = hotReloadSettings
        hotReloadSettings = settings
        AppLogger.d(TAG, "HotReload settings updated: enabled=${settings.enabled}, debounce=${settings.debounceDelayMs}ms")
        notifyHotReloadSettingsChanged(settings)
    }
        fun getEditorSettings(): EditorSettings = editorSettings

    fun updateEditorSettings(settings: EditorSettings) {
        val old = editorSettings
        editorSettings = settings
        AppLogger.d(TAG, "Editor settings updated: theme=${settings.theme}, fontSize=${settings.fontSize}")
        notifyEditorSettingsChanged(settings)
    }
        fun getPreviewSettings(): PreviewSettings = previewSettings

    fun updatePreviewSettings(settings: PreviewSettings) {
        val old = previewSettings
        previewSettings = settings
        AppLogger.d(TAG, "Preview settings updated: realtime=${settings.enableRealTimePreview}")
        notifyPreviewSettingsChanged(settings)
    }
        fun getSkillsRootDirectory(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apexDir = File(downloadsDir, "logistra")
        val skillsDir = File(apexDir, "skills")
        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }
        return skillsDir
    }
        fun getDevWorkspaceDirectory(): File {
        val workspaceDir = File(context.filesDir, "skill_dev_workspace")
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }
        return workspaceDir
    }
        fun getTempDirectory(): File {
        val tempDir = File(context.cacheDir, "skill_dev_temp")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }
        fun resetToDefaults() {
        serverSettings = ServerSettings()
        hotReloadSettings = HotReloadSettings()
        editorSettings = EditorSettings()
        previewSettings = PreviewSettings()
        AppLogger.d(TAG, "All settings reset to defaults")
    }
        private fun notifyServerSettingsChanged(settings: ServerSettings) {
        configListeners.forEach { listener ->
            runCatching {
                listener.onServerSettingsChanged(settings)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying server settings change", e)
            }
        }
    }
        private fun notifyHotReloadSettingsChanged(settings: HotReloadSettings) {
        configListeners.forEach { listener ->
            runCatching {
                listener.onHotReloadSettingsChanged(settings)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying hot reload settings change", e)
            }
        }
    }
        private fun notifyEditorSettingsChanged(settings: EditorSettings) {
        configListeners.forEach { listener ->
            runCatching {
                listener.onEditorSettingsChanged(settings)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying editor settings change", e)
            }
        }
    }
        private fun notifyPreviewSettingsChanged(settings: PreviewSettings) {
        configListeners.forEach { listener ->
            runCatching {
                listener.onPreviewSettingsChanged(settings)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying preview settings change", e)
            }
        }
    }
}