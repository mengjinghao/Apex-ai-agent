package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SkillLoader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillLoader"
        private const val MAX_PRELOAD_QUEUE_SIZE = 10

        @Volatile private var INSTANCE: SkillLoader? = null

        fun getInstance(context: Context): SkillLoader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillLoader(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class LoadedSkill(
        val skillPackage: SkillPackage,
        val loadedAt: Long,
        val loadDurationMs: Long,
        val content: String? = null
    )

    private val loadedSkills = ConcurrentHashMap<String, LoadedSkill>()
    private val loadingSkills = ConcurrentHashMap<String, Any>()
    private val loadListeners = CopyOnWriteArrayList<SkillLoadListener>()

    private val statsTotalLoads = AtomicLong(0)
    private val statsLoadErrors = AtomicLong(0)
    private val statsCacheHits = AtomicLong(0)

    interface SkillLoadListener {
        fun onSkillLoaded(skillName: String, durationMs: Long)
        fun onSkillLoadFailed(skillName: String, error: String)
        fun onSkillPreloadRequested(skillName: String)
    }

    fun addLoadListener(listener: SkillLoadListener) {
        if (!loadListeners.contains(listener)) {
            loadListeners.add(listener)
        }
    }

    fun removeLoadListener(listener: SkillLoadListener) {
        loadListeners.remove(listener)
    }

    fun isLoaded(skillName: String): Boolean {
        return loadedSkills.containsKey(skillName)
    }

    fun getLoadedSkill(skillName: String): LoadedSkill? {
        return loadedSkills[skillName]
    }

    fun getAllLoadedSkills(): Map<String, LoadedSkill> {
        return loadedSkills.toMap()
    }

    fun getLoadedSkillCount(): Int {
        return loadedSkills.size
    }

    fun loadSkill(skillName: String, skillManager: SkillManager, forceReload: Boolean = false): LoadedSkill? {
        val startTime = System.currentTimeMillis()

        if (!forceReload) {
            loadedSkills[skillName]?.let { existing ->
                statsCacheHits.incrementAndGet()
                return existing
            }
        }

        val lock = loadingSkills.getOrPut(skillName) { Any() }
        synchronized(lock) {
            val existing = loadedSkills[skillName]
            if (existing != null && !forceReload) {
                statsCacheHits.incrementAndGet()
                return existing
            }

            val skillPackage = try {
                val availableSkills = skillManager.getAvailableSkills()
                availableSkills[skillName] ?: run {
                    notifyLoadFailed(skillName, "Skill not found: ${skillName}")
                    statsLoadErrors.incrementAndGet()
                    return null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting skill package for ${skillName}", e)
                notifyLoadFailed(skillName, e.message ?: "Unknown error")
                statsLoadErrors.incrementAndGet()
                return null
            }

            val content = try {
                skillPackage.skillFile.readText()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reading skill file for ${skillName}", e)
                notifyLoadFailed(skillName, "Failed to read skill file: ${e.message}")
                statsLoadErrors.incrementAndGet()
                return null
            }

            val loadDuration = System.currentTimeMillis() - startTime
            val loadedSkill = LoadedSkill(
                skillPackage = skillPackage,
                loadedAt = System.currentTimeMillis(),
                loadDurationMs = loadDuration,
                content = content
            )

            loadedSkills[skillName] = loadedSkill
            statsTotalLoads.incrementAndGet()

            notifySkillLoaded(skillName, loadDuration)
            AppLogger.d(TAG, "Skill loaded: ${skillName} (${loadDuration}ms)")

            return loadedSkill
        }
    }

    fun preloadSkills(skillNames: List<String>, skillManager: SkillManager) {
        if (skillNames.size > MAX_PRELOAD_QUEUE_SIZE) {
            AppLogger.w(TAG, "Preload queue exceeds max size (${MAX_PRELOAD_QUEUE_SIZE}), truncating")
        }

        skillNames.take(MAX_PRELOAD_QUEUE_SIZE).forEach { skillName ->
            if (!isLoaded(skillName)) {
                loadListeners.forEach { it.onSkillPreloadRequested(skillName) }
                loadSkill(skillName, skillManager)
            }
        }
    }

    fun unloadSkill(skillName: String): Boolean {
        val removed = loadedSkills.remove(skillName) != null
        if (removed) {
            AppLogger.d(TAG, "Skill unloaded from loader: ${skillName}")
        }
        return removed
    }

    fun unloadAllSkills() {
        val count = loadedSkills.size
        loadedSkills.clear()
        AppLogger.d(TAG, "All skills unloaded from loader: ${count} skills")
    }

    fun getSkillContent(skillName: String): String? {
        return loadedSkills[skillName]?.content
    }

    fun getStats(): LoaderStats {
        return LoaderStats(
            totalLoads = statsTotalLoads.get(),
            loadErrors = statsLoadErrors.get(),
            cacheHits = statsCacheHits.get(),
            currentlyLoaded = loadedSkills.size
        )
    }

    fun resetStats() {
        statsTotalLoads.set(0)
        statsLoadErrors.set(0)
        statsCacheHits.set(0)
    }

    private fun notifySkillLoaded(skillName: String, durationMs: Long) {
        loadListeners.forEach { listener ->
            runCatching {
                listener.onSkillLoaded(skillName, durationMs)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying listener of skill load", e)
            }
        }
    }

    private fun notifyLoadFailed(skillName: String, error: String) {
        loadListeners.forEach { listener ->
            runCatching {
                listener.onSkillLoadFailed(skillName, error)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying listener of skill load failure", e)
            }
        }
    }

    data class LoaderStats(
        val totalLoads: Long,
        val loadErrors: Long,
        val cacheHits: Long,
        val currentlyLoaded: Int
    )
}