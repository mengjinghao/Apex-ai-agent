package com.apex.agent.kernel.burst

import kotlinx.coroutines.runBlocking
import java.io.File

data class PluginOperationResult(
    val success: Boolean,
    val pluginId: String? = null,
    val skillIds: List<String> = emptyList(),
    val message: String = ""
)

class PluginManager(
    private val pluginLoader: BurstPluginLoader,
    private val dynamicLoader: DynamicPluginLoader,
    private val dependencyResolver: SkillDependencyResolver
) {

    fun installPlugin(apkPath: String): PluginOperationResult {
        val file = File(apkPath)
        if (!file.exists()) {
            return PluginOperationResult(false, message = "Plugin APK not found: $apkPath")
        }

        val info = dynamicLoader.installPlugin(apkPath)
        if (info == null) {
            return PluginOperationResult(false, message = "Failed to install plugin from $apkPath")
        }

        val loadedSkillIds = mutableListOf<String>()
        val classMap = dynamicLoader.findInstalledSkillClasses()

        classMap[info.pluginId]?.forEach { className ->
            val skill = dynamicLoader.instantiateSkill(className, info.pluginId)
            if (skill != null) {
                val manifest = skill.manifest

                val existingSkills = pluginLoader.getLoadedSkills().mapNotNull { id ->
                    val manifest = pluginLoader.getSkillManifest(id) ?: return@mapNotNull null
                    id to manifest
                }.toMap()

                val resolution = dependencyResolver.resolveDependencies(
                    manifest,
                    emptyMap(),
                    existingSkills
                )

                if (!resolution.resolved) {
                    return PluginOperationResult(
                        false,
                        pluginId = info.pluginId,
                        message = buildString {
                            appendLine("Dependency resolution failed for ${manifest.skillId}:")
                            if (resolution.missingDependencies.isNotEmpty()) {
                                appendLine("  Missing: ${resolution.missingDependencies}")
                            }
                            if (resolution.versionMismatches.isNotEmpty()) {
                                appendLine("  Version mismatch: ${resolution.versionMismatches}")
                            }
                            if (resolution.circularDependency.isNotEmpty()) {
                                appendLine("  Circular: ${resolution.circularDependency}")
                            }
                        }
                    )
                }

                pluginLoader.registerSkill(skill)
                dependencyResolver.registerManifest(manifest)
                loadedSkillIds.add(manifest.skillId)
            }
        }

        return PluginOperationResult(
            success = true,
            pluginId = info.pluginId,
            skillIds = loadedSkillIds,
            message = "Installed ${loadedSkillIds.size} skills from plugin ${info.pluginId}"
        )
    }

    fun uninstallPlugin(pluginId: String): PluginOperationResult {
        val info = dynamicLoader.getPluginInfo(pluginId)
        if (info == null) {
            return PluginOperationResult(false, message = "Plugin not found: $pluginId")
        }

        val unloadedIds = mutableListOf<String>()

        val allManifests = pluginLoader.getLoadedSkills().mapNotNull { id ->
            val manifest = pluginLoader.getSkillManifest(id) ?: return@mapNotNull null
            id to manifest
        }.toMap()

        dynamicLoader.findInstalledSkillClasses()[pluginId]?.forEach { className ->
            val tempSkill = dynamicLoader.instantiateSkill(className, pluginId) ?: return@forEach
            val skillId = tempSkill.manifest.skillId

            val dependents = dependencyResolver.findDependents(skillId, allManifests)
            if (dependents.isNotEmpty()) {
                return PluginOperationResult(
                    false,
                    pluginId = pluginId,
                    message = "Cannot uninstall $skillId, dependents: $dependents"
                )
            }

            dependencyResolver.unregisterManifest(skillId)
            runBlocking { pluginLoader.unloadSkill(skillId) }
            unloadedIds.add(skillId)
        }

        dynamicLoader.uninstallPlugin(pluginId)

        return PluginOperationResult(
            success = true,
            pluginId = pluginId,
            skillIds = unloadedIds,
            message = "Uninstalled plugin $pluginId with ${unloadedIds.size} skills"
        )
    }

    fun updatePlugin(pluginId: String, newApkPath: String): PluginOperationResult {
        val unloadedIds = mutableListOf<String>()

        dynamicLoader.findInstalledSkillClasses()[pluginId]?.forEach { className ->
            val tempSkill = dynamicLoader.instantiateSkill(className, pluginId) ?: return@forEach
            val skillId = tempSkill.manifest.skillId
            dependencyResolver.unregisterManifest(skillId)
            runBlocking { pluginLoader.unloadSkill(skillId) }
            unloadedIds.add(skillId)
        }

        val updatedInfo = dynamicLoader.updatePlugin(pluginId, newApkPath)
        if (updatedInfo == null) {
            return PluginOperationResult(
                false,
                pluginId = pluginId,
                message = "Failed to update plugin $pluginId"
            )
        }

        val newSkillIds = mutableListOf<String>()
        val classMap = dynamicLoader.findInstalledSkillClasses()
        classMap[pluginId]?.forEach { className ->
            val skill = dynamicLoader.instantiateSkill(className, pluginId)
            if (skill != null) {
                pluginLoader.registerSkill(skill)
                dependencyResolver.registerManifest(skill.manifest)
                newSkillIds.add(skill.manifest.skillId)
            }
        }

        return PluginOperationResult(
            success = true,
            pluginId = pluginId,
            skillIds = newSkillIds,
            message = "Updated plugin $pluginId: ${unloadedIds.size} old -> ${newSkillIds.size} new skills"
        )
    }

    fun listInstalledPlugins(): List<DynamicPluginInfo> {
        return dynamicLoader.getInstalledPlugins()
    }

    fun reloadAllPlugins(): PluginOperationResult {
        val oldPlugins = dynamicLoader.getInstalledPlugins().toList()

        pluginLoader.unloadAllSkills()

        oldPlugins.forEach { info ->
            dynamicLoader.uninstallPlugin(info.pluginId)
        }

        val loadedIds = mutableListOf<String>()
        dynamicLoader.loadSkillsFromInstalledPlugins()

        dynamicLoader.findInstalledSkillClasses().forEach { (pluginId, classNames) ->
            classNames.forEach { className ->
                val skill = dynamicLoader.instantiateSkill(className, pluginId)
                if (skill != null) {
                    pluginLoader.registerSkill(skill)
                    dependencyResolver.registerManifest(skill.manifest)
                    loadedIds.add(skill.manifest.skillId)
                }
            }
        }

        return PluginOperationResult(
            success = true,
            skillIds = loadedIds,
            message = "Reloaded ${loadedIds.size} skills from plugins"
        )
    }
}
