package com.apex.plugins.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import com.apex.agent.core.tools.defaultTool.standard.name

class SkillPluginExporter private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillPluginExporter"
        private const val EXPORT_FILE_NAME = "apex_plugins_export.json"

        @Volatile
        private var INSTANCE: SkillPluginExporter? = null

        fun getInstance(context: Context): SkillPluginExporter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillPluginExporter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val pluginManager = SkillPluginManager.getInstance(context)

    @Serializable
    data class PluginExportData(
        val exportVersion: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val plugins: List<PluginEntry>,
        val enabledPlugins: List<String>
    )

    @Serializable
    data class PluginEntry(
        val pluginId: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val category: String,
        val isEnabled: Boolean
    )

    @Serializable
    data class PluginImportResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>
    )

    suspend fun exportToFile(destinationDir: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val allPlugins = pluginManager.getAllPlugins()
            val enabledIds = pluginManager.getEnabledPlugins().map { it.id }

            val entries = allPlugins.map { plugin ->
                PluginEntry(
                    pluginId = plugin.id,
                    name = plugin.name,
                    version = plugin.version,
                    author = plugin.author,
                    description = plugin.description,
                    category = plugin.category.name,
                    isEnabled = enabledIds.contains(plugin.id)
                )
            }

            if (entries.isEmpty()) {
                return@withContext Result.failure(Exception("没有已安装的插件可导�?))
            }

            val exportData = PluginExportData(plugins = entries, enabledPlugins = enabledIds)
            val jsonContent = json.encodeToString(exportData)

            if (!destinationDir.exists()) destinationDir.mkdirs()
            val exportFile = File(destinationDir, EXPORT_FILE_NAME)
            exportFile.writeText(jsonContent)

            AppLogger.i(TAG, "已导�?${entries.size} 个插件信息到 ${exportFile.absolutePath}")
            Result.success(exportFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "导出插件信息失败", e)
            Result.failure(e)
        }
    }

    suspend fun importFromFile(importFile: File): Result<PluginImportResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!importFile.exists()) {
                    return@withContext Result.failure(Exception("导入文件不存�?))
                }

                val jsonContent = importFile.readText()
                val exportData = json.decodeFromString<PluginExportData>(jsonContent)

                val existingPluginIds = pluginManager.getAllPlugins().map { it.id }.toSet()
                var imported = 0
                var skipped = 0
                val errors = mutableListOf<String>()

                for (entry in exportData.plugins) {
                    if (entry.pluginId in existingPluginIds) {
                        skipped++
                        continue
                    }
                    AppLogger.d(TAG, "导入条目 (需手动下载): ${entry.pluginId} v${entry.version}")
                    skipped++
                }

                val result = PluginImportResult(
                    imported = imported,
                    skipped = skipped,
                    errors = errors
                )

                AppLogger.i(
                    TAG,
                    "导入完成: 导入 ${result.imported}, 跳过 ${result.skipped}, " +
                        "错误 ${result.errors.size}"
                )

                Result.success(result)
            } catch (e: Exception) {
                AppLogger.e(TAG, "导入插件信息失败", e)
                Result.failure(e)
            }
        }

    suspend fun exportPluginIds(): List<String> = withContext(Dispatchers.IO) {
        pluginManager.getAllPlugins().map { it.id }
    }

    suspend fun importPluginIds(pluginIds: List<String>): Result<PluginImportResult> =
        withContext(Dispatchers.IO) {
            try {
                val installer = SkillBatchInstaller.getInstance(context)
                val existingIds = pluginManager.getAllPlugins().map { it.id }.toSet()

                val toInstall = pluginIds.filter { it !in existingIds }

                if (toInstall.isEmpty()) {
                    return@withContext Result.success(PluginImportResult(0, pluginIds.size, emptyList()))
                }

                val result = installer.installPlugins(toInstall)

                val importResult = PluginImportResult(
                    imported = result.successCount,
                    skipped = pluginIds.size - toInstall.size,
                    errors = result.failures.map { "${it.pluginId}: ${it.reason}" }
                )

                Result.success(importResult)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
