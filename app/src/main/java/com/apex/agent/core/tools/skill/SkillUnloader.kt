package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SkillUnloader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillUnloader"

        @Volatile private var INSTANCE: SkillUnloader? = null

        fun getInstance(context: Context): SkillUnloader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillUnloader(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class UnloadResult(
        val skillName: String,
        val success: Boolean,
        val removedFromLoader: Boolean,
        val removedFromCache: Boolean,
        val error: String? = null
    )

    data class UnloadAllResult(
        val totalSkills: Int,
        val successfulUnloads: Int,
        val failedUnloads: Int,
        val unloadResults: List<UnloadResult>
    )

    private val skillLoader: SkillLoader by lazy { SkillLoader.getInstance(context) }
    private val skillCache: SkillCache by lazy { SkillCache.getInstance(context) }

    private val unloadListeners = ConcurrentHashMap<String, MutableList<SkillUnloadListener>>()

    private val statsTotalUnloads = AtomicLong(0)
    private val statsFailedUnloads = AtomicLong(0)

    interface SkillUnloadListener {
        fun onSkillUnloading(skillName: String)
        fun onSkillUnloaded(skillName: String, result: UnloadResult)
        fun onAllSkillsUnloaded(result: UnloadAllResult)
    }

    fun addUnloadListener(skillName: String, listener: SkillUnloadListener) {
        unloadListeners.getOrPut(skillName) { mutableListOf() }.add(listener)
    }

    fun removeUnloadListener(skillName: String, listener: SkillUnloadListener) {
        unloadListeners[skillName]?.remove(listener)
    }

    fun removeAllUnloadListeners() {
        unloadListeners.clear()
    }

    fun unload(skillName: String): UnloadResult {
        AppLogger.d(TAG, "Unloading skill: ${skillName}")
        notifyUnloading(skillName)
        statsTotalUnloads.incrementAndGet()

        val removedFromLoader = skillLoader.unloadSkill(skillName)
        skillCache.invalidateSkill(skillName)

        val result = UnloadResult(
            skillName = skillName,
            success = true,
            removedFromLoader = removedFromLoader,
            removedFromCache = true
        )

        notifyUnloaded(result)
        AppLogger.d(TAG, "Skill unloaded: ${skillName}, removedFromLoader=${removedFromLoader}")

        return result
    }

    fun unloadAll(): UnloadAllResult {
        val loadedSkills = skillLoader.getAllLoadedSkills()
        val totalCount = loadedSkills.size
        AppLogger.d(TAG, "Unloading all skills: ${totalCount} to unload")

        val results = mutableListOf<UnloadResult>()
        var successCount = 0
        var failCount = 0

        loadedSkills.keys.forEach { skillName ->
            val result = unload(skillName)
            results.add(result)
            if (result.success) {
                successCount++
            } else {
                failCount++
            }
        }

        val unloadAllResult = UnloadAllResult(
            totalSkills = totalCount,
            successfulUnloads = successCount,
            failedUnloads = failCount,
            unloadResults = results
        )

        notifyAllUnloaded(unloadAllResult)

        statsFailedUnloads.addAndGet(failCount.toLong())

        AppLogger.d(TAG, "All skills unloaded: total=${totalCount}, success=${successCount}, failed=${failCount}")

        return unloadAllResult
    }

    fun unloadByPrefix(prefix: String): UnloadAllResult {
        val loadedSkills = skillLoader.getAllLoadedSkills()
        val toUnload = loadedSkills.keys.filter { it.startsWith(prefix) }
        val totalCount = toUnload.size
        AppLogger.d(TAG, "Unloading skills with prefix '${prefix}': ${totalCount} to unload")

        val results = mutableListOf<UnloadResult>()
        var successCount = 0
        var failCount = 0

        toUnload.forEach { skillName ->
            val result = unload(skillName)
            results.add(result)
            if (result.success) {
                successCount++
            } else {
                failCount++
            }
        }

        return UnloadAllResult(
            totalSkills = totalCount,
            successfulUnloads = successCount,
            failedUnloads = failCount,
            unloadResults = results
        )
    }

    fun isLoaded(skillName: String): Boolean {
        return skillLoader.isLoaded(skillName)
    }

    fun getStats(): UnloadStats {
        return UnloadStats(
            totalUnloads = statsTotalUnloads.get(),
            failedUnloads = statsFailedUnloads.get(),
            currentlyLoaded = skillLoader.getLoadedSkillCount()
        )
    }

    fun resetStats() {
        statsTotalUnloads.set(0)
        statsFailedUnloads.set(0)
    }

    private fun notifyUnloading(skillName: String) {
        unloadListeners[skillName]?.forEach { listener ->
            runCatching {
                listener.onSkillUnloading(skillName)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying unload listener for ${skillName}", e)
            }
        }
    }

    private fun notifyUnloaded(result: UnloadResult) {
        unloadListeners[result.skillName]?.forEach { listener ->
            runCatching {
                listener.onSkillUnloaded(result.skillName, result)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying unload listener for ${result.skillName}", e)
            }
        }
    }

    private fun notifyAllUnloaded(result: UnloadAllResult) {
        unloadListeners.forEach { (_, listeners) ->
            listeners.forEach { listener ->
                runCatching {
                    listener.onAllSkillsUnloaded(result)
                }.onFailure { e ->
                    AppLogger.e(TAG, "Error notifying all-unloaded listener", e)
                }
            }
        }
    }

    data class UnloadStats(
        val totalUnloads: Long,
        val failedUnloads: Long,
        val currentlyLoaded: Int
    )
}