package com.apex.plugins.skill

import android.content.Context
import android.os.Environment
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import com.apex.agent.core.tools.defaultTool.standard.name

class SkillPluginLoader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillPluginLoader"

        @Volatile private var INSTANCE: SkillPluginLoader? = null

        fun getInstance(context: Context): SkillPluginLoader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillPluginLoader(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
        private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
        private val classLoaders = ConcurrentHashMap<String, ClassLoader>()
        private val pluginInstances = ConcurrentHashMap<String, SkillPlugin>()
        private val pluginsRootDir: File
        get() {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val ApexDir = File(downloadsDir, "Apex")
        val pluginsDir = File(ApexDir, SkillPluginConstants.PLUGIN_DIR)
        if (!pluginsDir.exists()) {
                pluginsDir.mkdirs()
            }
        return pluginsDir
        }
        fun getPluginsDirectory(): File = pluginsRootDir

    suspend fun loadPlugin(pluginFile: File): SkillPlugin = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Loading plugin from: ${pluginFile.absolutePath}")
        val validationResult = validatePlugin(pluginFile)
        if (!validationResult.isValid) {
            val errors = validationResult.errors.joinToString("; ")
        throw PluginLoadException("Plugin validation failed: ${errors}")
        }
        val descriptor = validationResult.descriptor
            ?: throw PluginLoadException("Plugin descriptor is null")
        val pluginDir = extractPlugin(pluginFile, descriptor.pluginId)
        val pluginClass = loadPluginClass(pluginDir, descriptor)
        val constructor = pluginClass.getDeclaredConstructor()
        val plugin = constructor.newInstance() as SkillPlugin
        pluginInstances[descriptor.pluginId] = plugin

        AppLogger.i(TAG, "Successfully loaded plugin: ${descriptor.pluginId} v${descriptor.version}")
        plugin
    }
        fun loadPluginDescriptor(pluginFile: File): SkillPluginDescriptor {
        if (!pluginFile.exists()) {
            throw PluginLoadException("Plugin file does not exist: ${pluginFile.absolutePath}")
        }
        return when {
            pluginFile.name.endsWith(".zip", ignoreCase = true) -> loadDescriptorFromZip(pluginFile)
            pluginFile.isDirectory -> loadDescriptorFromDirectory(pluginFile)
            else -> throw PluginLoadException("Unsupported plugin file format: ${pluginFile.name}")
        }
    }
        private fun loadDescriptorFromZip(zipFile: File): SkillPluginDescriptor {
        val tempDir = createTempDirectory("plugin_desc_")

        try {
            unzipFile(zipFile, tempDir)
        return loadDescriptorFromDirectory(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
        private fun loadDescriptorFromDirectory(pluginDir: File): SkillPluginDescriptor {
        val configFile = File(pluginDir, SkillPluginConstants.PLUGIN_CONFIG_FILE)
        if (!configFile.exists()) {
            throw PluginLoadException("Missing plugin.json in plugin directory")
        }
        val configContent = configFile.readText()
        return parsePluginDescriptor(configContent)
    }
        private fun parsePluginDescriptor(jsonContent: String): SkillPluginDescriptor {
        return try {
            val data = json.decodeFromString<PluginConfigData>(jsonContent)
            PluginDescriptorImpl(
                pluginId = data.id,
                name = data.name,
                version = data.version,
                author = data.author,
                description = data.description,
                category = SkillPluginCategory.valueOf(data.category.uppercase()),
                supportedSkills = data.supportedSkills,
                dependencies = data.dependencies,
                minApiVersion = data.minApiVersion,
                iconUrl = data.iconUrl,
                permissions = data.permissions
            )
        } catch (e: Exception) {
            throw PluginLoadException("Failed to parse plugin descriptor: ${e.message}", e)
        }
    }
        fun validatePlugin(pluginFile: File): PluginValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (!pluginFile.exists()) {
            return PluginValidationResult(
                isValid = false,
                errors = listOf("Plugin file does not exist")
            )
        }
        val descriptor = try {
            loadPluginDescriptor(pluginFile)
        } catch (e: Exception) {
            return PluginValidationResult(
                isValid = false,
                errors = listOf("Failed to load plugin descriptor: ${e.message}")
            )
        }
        if (descriptor.pluginId.isBlank()) {
            errors.add("Plugin ID cannot be empty")
        }
        if (descriptor.name.isBlank()) {
            errors.add("Plugin name cannot be empty")
        }
        if (descriptor.version.isBlank()) {
            errors.add("Plugin version cannot be empty")
        }
        if (!isVersionCompatible(descriptor.minApiVersion)) {
            errors.add("Plugin requires API version ${descriptor.minApiVersion}, but current version is ${SkillPluginConstants.PLUGIN_API_VERSION}")
        }
        for (dep in descriptor.dependencies) {
            if (!isPluginInstalled(dep)) {
                warnings.add("Required dependency '${dep}' is not installed")
            }
        }
        return PluginValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            descriptor = descriptor
        )
    }
        private fun loadPluginClass(pluginDir: File, descriptor: SkillPluginDescriptor): Class<out SkillPlugin> {
        val pluginClassName = getPluginMainClassName(pluginDir)
            ?: throw PluginLoadException("在 ${descriptor.pluginId} 中未找到主插件类")
        val urls = mutableListOf<java.net.URL>()

        pluginDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.parentFile.absolutePath }
            .distinct()
            .map { File(it).toURI().toURL() }
            .forEach { urls.add(it) }

        pluginDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .map { it.toURI().toURL() }
            .forEach { urls.add(it) }
        val classLoader = URLClassLoader(
            urls.toTypedArray(),
            context.classLoader
        )

        classLoaders[descriptor.pluginId] = classLoader

        try {
            val pluginClass = classLoader.loadClass(pluginClassName)
        if (!SkillPlugin::class.java.isAssignableFrom(pluginClass)) {
                throw PluginLoadException(
                    "插件类 ${pluginClassName} 必须实现 SkillPlugin 接口"
                )
            }
            @Suppress("UNCHECKED_CAST")
        return pluginClass as Class<out SkillPlugin>
        } catch (e: Exception) {
            throw PluginLoadException(
                "加载插件类 ${pluginClassName} 失败: ${e.message}", e
            )
        }
    }
        private fun getPluginMainClassName(pluginDir: File): String? {
        val configFile = File(pluginDir, SkillPluginConstants.PLUGIN_CONFIG_FILE)
        if (!configFile.exists()) return null

        return try {
            val content = configFile.readText()
        val data = json.decodeFromString<PluginConfigData>(content)
            data.mainClass
        } catch (e: Exception) {
            null
        }
    }
        private fun extractPlugin(pluginFile: File, pluginId: String): File {
        val extractDir = File(pluginsRootDir, ".extracted_${pluginId}")
        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
        extractDir.mkdirs()
        if (pluginFile.isDirectory) {
            pluginFile.copyRecursively(extractDir, overwrite = true)
        } else {
            unzipFile(pluginFile, extractDir)
        }
        return extractDir
    }

    @Throws(SecurityException::class)
        private fun unzipFile(zipFile: File, destDir: File) {
        val buffer = ByteArray(64 * 1024)
        val destCanonical = destDir.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // 防 Zip Slip 攻击: 确保解压目标路径在当前目录内
    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                    throw SecurityException("Zip Slip 攻击已拦截: ${entry.name}")
                }
        if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
        private fun createTempDirectory(prefix: String): File {
        val tempDir = File(context.cacheDir, "${prefix}${System.currentTimeMillis()}")
        tempDir.mkdirs()
        return tempDir
    }
        fun getPluginClassLoader(pluginId: String): ClassLoader? = classLoaders[pluginId]

    fun getLoadedPlugin(pluginId: String): SkillPlugin? = pluginInstances[pluginId]

    fun unloadPlugin(pluginId: String) {
        pluginInstances.remove(pluginId)
        classLoaders.remove(pluginId)
        AppLogger.d(TAG, "Unloaded plugin: ${pluginId}")
    }
        private fun isVersionCompatible(minVersion: String): Boolean {
        val current = parseVersion(SkillPluginConstants.PLUGIN_API_VERSION)
        val required = parseVersion(minVersion)
        return current >= required
    }
        private fun parseVersion(version: String): Int {
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }
        private fun isPluginInstalled(pluginId: String): Boolean {
        return pluginInstances.containsKey(pluginId)
    }

    @Serializable
    private data class PluginConfigData(
        val id: String,
        val name: String,
        val version: String,
        val author: String = "",
        val description: String = "",
        val category: String = "CUSTOM",
        val mainClass: String = "",
        val supportedSkills: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(),
        val minApiVersion: String = "1.0",
        val iconUrl: String? = null,
        val permissions: List<String> = emptyList()
    )
        private class PluginDescriptorImpl(
        override val pluginId: String,
        override val name: String,
        override val version: String,
        override val author: String,
        override val description: String,
        override val category: SkillPluginCategory,
        override val supportedSkills: List<String>,
        override val dependencies: List<String>,
        override val minApiVersion: String,
        override val iconUrl: String?,
        override val permissions: List<String>
    ) : SkillPluginDescriptor

    class PluginLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

suspend fun SkillPluginLoader.discoverPlugins(): List<File> = withContext(Dispatchers.IO) {
    val pluginsDir = getPluginsDirectory()
        val discovered = mutableListOf<File>()

    pluginsDir.listFiles()?.forEach { file ->
        when {
            file.isDirectory && File(file, SkillPluginConstants.PLUGIN_CONFIG_FILE).exists() -> {
                discovered.add(file)
            }
            file.name.endsWith(".zip", ignoreCase = true) -> {
                discovered.add(file)
            }
        }
    }

    discovered
}
