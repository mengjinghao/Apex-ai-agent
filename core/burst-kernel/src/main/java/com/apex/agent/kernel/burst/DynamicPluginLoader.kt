package com.apex.agent.kernel.burst

import android.content.Context
import com.apex.agent.plugins.burst.base.IBurstSkill
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DynamicPluginInfo(
    val pluginId: String,
    val pluginPath: String,
    val version: String,
    val classNames: List<String>,
    val isEnabled: Boolean = true,
    val loadedAt: Long = System.currentTimeMillis()
)

class DynamicPluginLoader(private val context: Context) {
    private val pluginsDir: File
    private val optimizedDir: File

    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val pluginInfos = ConcurrentHashMap<String, DynamicPluginInfo>()

    init {
        pluginsDir = File(context.filesDir, "plugins").also { it.mkdirs() }
        optimizedDir = File(context.cacheDir, "optimized-plugins").also { it.mkdirs() }
    }

    fun getPluginsDirectory(): File = pluginsDir

    fun getInstalledPlugins(): List<DynamicPluginInfo> = pluginInfos.values.toList()

    fun getPluginInfo(pluginId: String): DynamicPluginInfo? = pluginInfos[pluginId]

    fun installPlugin(sourcePath: String): DynamicPluginInfo? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null

        val pluginId = sourceFile.nameWithoutExtension
        val destFile = File(pluginsDir, sourceFile.name)

        sourceFile.copyTo(destFile, overwrite = true)

        val info = loadPluginFromFile(destFile)
        if (info != null) {
            pluginInfos[info.pluginId] = info
        }
        return info
    }

    fun uninstallPlugin(pluginId: String): Boolean {
        val info = pluginInfos[pluginId] ?: return false
        loaders.remove(pluginId)
        pluginInfos.remove(pluginId)
        val file = File(info.pluginPath)
        return if (file.exists()) file.delete() else true
    }

    fun updatePlugin(pluginId: String, newSourcePath: String): DynamicPluginInfo? {
        uninstallPlugin(pluginId)
        return installPlugin(newSourcePath)
    }

    fun loadSkillsFromInstalledPlugins(): List<Pair<String, IBurstSkill>> {
        val loaded = mutableListOf<Pair<String, IBurstSkill>>()
        pluginsDir.listFiles { f -> f.extension in setOf("apk", "dex", "jar") }?.forEach { file ->
            val info = loadPluginFromFile(file)
            if (info != null) {
                pluginInfos[info.pluginId] = info
            }
        }
        return loaded
    }

    private fun loadPluginFromFile(file: File): DynamicPluginInfo? {
        val pluginId = file.nameWithoutExtension

        try {
            val loader = DexClassLoader(
                file.absolutePath,
                optimizedDir.absolutePath,
                null,
                this::class.java.classLoader
            )
            loaders[pluginId] = loader

            val skillClasses = findSkillClasses(loader)
            val version = extractVersion(loader)

            val info = DynamicPluginInfo(
                pluginId = pluginId,
                pluginPath = file.absolutePath,
                version = version,
                classNames = skillClasses
            )
            pluginInfos[pluginId] = info
            return info
        } catch (e: Exception) {
            return null
        }
    }

    fun instantiateSkill(className: String, pluginId: String): IBurstSkill? {
        val loader = loaders[pluginId] ?: return null
        return try {
            val clazz = loader.loadClass(className)
            if (IBurstSkill::class.java.isAssignableFrom(clazz)) {
                clazz.getDeclaredConstructor().newInstance() as IBurstSkill
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun findInstalledSkillClasses(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        pluginInfos.forEach { (pluginId, info) ->
            result[pluginId] = info.classNames
        }
        return result
    }

    private fun findSkillClasses(loader: DexClassLoader): List<String> {
        val classes = mutableListOf<String>()
        try {
            val dexFile = DexFile(loaders.entries.firstOrNull { it.value == loader }?.key ?: return classes)
        } catch (e: Exception) {
        }
        return classes
    }

    private fun extractVersion(loader: DexClassLoader): String {
        return "1.0.0"
    }

    private class DexFile(path: String) {
        private val entries = mutableListOf<String>()

        init {
            java.util.zip.ZipFile(path).use { zipFile ->
                zipFile.entries().asSequence().forEach { entry ->
                    if (entry.name.endsWith(".class") && !entry.name.contains("META-INF")) {
                        val className = entry.name.replace('/', '.')
                            .removeSuffix(".class")
                        entries.add(className)
                    }
                }
            }
        }

        fun entries(): List<String> = entries
    }
}
