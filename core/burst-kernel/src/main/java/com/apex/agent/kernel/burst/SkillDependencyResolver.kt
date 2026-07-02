package com.apex.agent.kernel.burst

import com.apex.agent.plugins.burst.base.BurstSkillManifest
import com.apex.agent.plugins.burst.base.IBurstSkill
import com.apex.agent.plugins.burst.base.SkillDependency
import java.util.concurrent.ConcurrentHashMap

data class DependencyResolution(
    val resolved: Boolean,
    val missingDependencies: List<String> = emptyList(),
    val versionMismatches: List<String> = emptyList(),
    val circularDependency: List<String> = emptyList()
)

class SkillDependencyResolver {
    private val registeredManifests = ConcurrentHashMap<String, BurstSkillManifest>()

    fun registerManifest(manifest: BurstSkillManifest) {
        registeredManifests[manifest.skillId] = manifest
    }

    fun unregisterManifest(skillId: String) {
        registeredManifests.remove(skillId)
    }

    fun resolveDependencies(
        manifest: BurstSkillManifest,
        existingSkills: Map<String, IBurstSkill>,
        existingManifests: Map<String, BurstSkillManifest>
    ): DependencyResolution {
        val missing = mutableListOf<String>()
        val versionMismatches = mutableListOf<String>()

        for (dep in manifest.dependencies) {
            val depManifest = existingManifests[dep.skillId] ?: registeredManifests[dep.skillId]
            if (depManifest == null) {
                missing.add("${dep.skillId} (required: ${dep.versionRange})")
                continue
            }
            if (!checkVersion(depManifest.version, dep.versionRange)) {
                versionMismatches.add(
                    "${dep.skillId}: installed=${depManifest.version}, required=${dep.versionRange}"
                )
            }
        }

        val circular = detectCircularDependency(manifest.skillId, manifest, existingManifests)

        return DependencyResolution(
            resolved = missing.isEmpty() && versionMismatches.isEmpty() && circular.isEmpty(),
            missingDependencies = missing,
            versionMismatches = versionMismatches,
            circularDependency = circular
        )
    }

    fun findDependents(skillId: String, existingManifests: Map<String, BurstSkillManifest>): List<String> {
        return existingManifests.filter { (_, manifest) ->
            manifest.dependencies.any { it.skillId == skillId }
        }.keys.toList()
    }

    private fun checkVersion(installed: String, versionRange: String): Boolean {
        if (versionRange.isEmpty() || versionRange == "*") return true

        val parts = versionRange.split(",").map { it.trim() }
        return parts.all { range ->
            when {
                range.startsWith(">=") -> compareVersions(installed, range.removePrefix(">=")) >= 0
                range.startsWith("<=") -> compareVersions(installed, range.removePrefix("<=")) <= 0
                range.startsWith(">") -> compareVersions(installed, range.removePrefix(">")) > 0
                range.startsWith("<") -> compareVersions(installed, range.removePrefix("<")) < 0
                range.startsWith("=") || !range.contains(Regex("[<>=]")) -> {
                    compareVersions(installed, range.removePrefix("=")) == 0
                }
                range.contains(" - ") -> {
                    val (low, high) = range.split(" - ").map { it.trim() }
                    compareVersions(installed, low) >= 0 && compareVersions(installed, high) <= 0
                }
                else -> true
            }
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun detectCircularDependency(
        skillId: String,
        manifest: BurstSkillManifest,
        existingManifests: Map<String, BurstSkillManifest>,
        visited: Set<String> = setOf()
    ): List<String> {
        if (skillId in visited) return visited.toList() + skillId

        val deps = manifest.dependencies.map { it.skillId }
        val path = visited + skillId

        for (depId in deps) {
            val depManifest = existingManifests[depId] ?: continue
            val result = detectCircularDependency(depId, depManifest, existingManifests, path)
            if (result.isNotEmpty()) return result
        }

        return emptyList()
    }
}
