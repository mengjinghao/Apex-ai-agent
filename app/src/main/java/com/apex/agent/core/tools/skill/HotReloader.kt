package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HotReloader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "HotReloader"

        @Volatile private var INSTANCE: HotReloader? = null

        fun getInstance(context: Context): HotReloader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HotReloader(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class FileChange(
        val file: File,
        val changeType: ChangeType,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class ChangeType {
        CREATED,
        MODIFIED,
        DELETED
    }

    data class ReloadEvent(
        val skillName: String,
        val changedFiles: List<FileChange>,
        val timestamp: Long = System.currentTimeMillis()
    )

    interface ReloadListener {
        fun onFilesChanged(event: ReloadEvent)
        fun onSkillReloaded(skillName: String, success: Boolean, error: String)
        fun onError(error: String)
    }
        private val config = DevServerConfig.getInstance(context)
        private val skillManager = SkillManager.getInstance(context)
        private val skillLoader = SkillLoader.getInstance(context)
        private val watchedDirectories = ConcurrentHashMap<String, Boolean>()
        private val fileTimestamps = ConcurrentHashMap<String, Long>()
        private val pendingChanges = ConcurrentHashMap<String, FileChange>()
        private val listeners = CopyOnWriteArrayList<ReloadListener>()
        private val isWatching = AtomicBoolean(false)
        private val isPaused = AtomicBoolean(false)
        private var watchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val statsReloadCount = AtomicLong(0)
        private val statsErrorCount = AtomicLong(0)
        private val statsLastReloadTime = AtomicLong(0)
        private val debounceJob = AtomicLong(0)
        fun addReloadListener(listener: ReloadListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
        fun removeReloadListener(listener: ReloadListener) {
        listeners.remove(listener)
    }
        fun startWatching(skillName: String? = null) {
        if (isWatching.getAndSet(true)) {
            AppLogger.d(TAG, "Already watching for file changes")
        return
        }
        val directories = if (skillName != null) {
            listOf(getSkillDirectory(skillName)).filterNotNull()
        } else {
            getAllSkillDirectories()
        }

        directories.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                watchedDirectories[dir.absolutePath] = true
                scanExistingFiles(dir)
            }
        }

        startWatchLoop()
        AppLogger.d(TAG, "Started watching ${directories.size} directories")
    }
        fun stopWatching() {
        if (!isWatching.getAndSet(false)) {
            return
        }

        watchJob?.cancel()
        watchJob = null
        pendingChanges.clear()
        watchedDirectories.clear()

        AppLogger.d(TAG, "Stopped watching for file changes")
    }
        fun pauseWatching() {
        isPaused.set(true)
        AppLogger.d(TAG, "File watching paused")
    }
        fun resumeWatching() {
        isPaused.set(false)
        AppLogger.d(TAG, "File watching resumed")
    }
        fun reloadSkill(skillName: String): Boolean {
        val startTime = System.currentTimeMillis()

        try {
            val skillDir = getSkillDirectory(skillName)
        if (skillDir == null) {
                notifyError("Skill directory not found for: ${skillName}")
                statsErrorCount.incrementAndGet()
        return false
            }

            skillLoader.unloadSkill(skillName)
        val reloaded = skillManager.preloadSkill(skillName, forceReload = true)
        if (reloaded) {
                statsReloadCount.incrementAndGet()
                statsLastReloadTime.set(System.currentTimeMillis())
                AppLogger.d(TAG, "Skill reloaded successfully: ${skillName} (${System.currentTimeMillis() - startTime}ms)")
            } else {
                statsErrorCount.incrementAndGet()
                AppLogger.w(TAG, "Failed to reload skill: ${skillName}")
            }

            notifySkillReloaded(skillName, reloaded, if (!reloaded) "Failed to reload skill" else null)
        return reloaded

        } catch (e: Exception) {
            statsErrorCount.incrementAndGet()
            AppLogger.e(TAG, "Error reloading skill: ${skillName}", e)
            notifyError("Error reloading skill: ${e.message}")
            notifySkillReloaded(skillName, false, e.message)
        return false
        }
    }
        fun triggerReloadForFile(file: File) {
        if (!config.getHotReloadSettings().enabled) {
            return
        }
        val skillName = extractSkillName(file)
        if (skillName != null) {
            val settings = config.getHotReloadSettings()
        if (settings.debounceDelayMs > 0) {
                scheduleDebouncedReload(skillName, settings.debounceDelayMs)
            } else {
                reloadSkill(skillName)
            }
        }
    }
        private fun scheduleDebouncedReload(skillName: String, delayMs: Long) {
        debounceJob.set(System.currentTimeMillis() + delayMs)

        scope.launch {
            delay(delayMs)
        if (System.currentTimeMillis() >= debounceJob.get()) {
                reloadSkill(skillName)
            }
        }
    }
        private fun startWatchLoop() {
        watchJob = scope.launch {
            while (isActive && isWatching.get()) {
                if (!isPaused.get()) {
                    checkForChanges()
                }
                delay(1000)
            }
        }
    }
        private fun checkForChanges() {
        watchedDirectories.keys().forEach { dirPath ->
            val dir = File(dirPath)
        if (!dir.exists()) return@forEach

            checkDirectoryRecursive(dir)
        }
    }
        private fun checkDirectoryRecursive(directory: File) {
        val settings = config.getHotReloadSettings()

        directory.listFiles()?.forEach { file ->
            val relativePath = file.absolutePath

            if (shouldIgnore(relativePath, settings.ignorePatterns)) {
                return@forEach
            }
        if (file.isDirectory) {
                checkDirectoryRecursive(file)
            } else if (file.isFile) {
                if (shouldWatch(file.name, settings.watchExtensions)) {
                    checkFileChange(file)
                }
            }
        }
    }
        private fun checkFileChange(file: File) {
        val path = file.absolutePath
        val lastModified = file.lastModified()
        val previousTimestamp = fileTimestamps[path]

        if (previousTimestamp == null) {
            fileTimestamps[path] = lastModified
            return
        }
        if (lastModified > previousTimestamp) {
            fileTimestamps[path] = lastModified

            val change = FileChange(
                file = file,
                changeType = ChangeType.MODIFIED,
                timestamp = System.currentTimeMillis()
            )

            pendingChanges[path] = change
            notifyFilesChanged(change)

            triggerReloadForFile(file)
        }
    }
        private fun scanExistingFiles(directory: File) {
        val settings = config.getHotReloadSettings()

        directory.walkTopDown().forEach { file ->
            if (file.isFile && shouldWatch(file.name, settings.watchExtensions)) {
                if (!shouldIgnore(file.absolutePath, settings.ignorePatterns)) {
                    fileTimestamps[file.absolutePath] = file.lastModified()
                }
            }
        }
    }
        private fun shouldWatch(fileName: String, extensions: Set<String>): Boolean {
        return extensions.any { fileName.endsWith(it, ignoreCase = true) }
    }
        private fun shouldIgnore(path: String, ignorePatterns: Set<String>): Boolean {
        return ignorePatterns.any { pattern ->
            path.contains(File.separator + pattern + File.separator) ||
                    path.endsWith(pattern) ||
                    path.contains(".${pattern}")
        }
    }
        private fun getSkillDirectory(skillName: String): File? {
        val skillsDir = config.getSkillsRootDirectory()
        val skillDir = File(skillsDir, skillName)
        return if (skillDir.exists() && skillDir.isDirectory) skillDir else null
    }
        private fun getAllSkillDirectories(): List<File> {
        val skillsDir = config.getSkillsRootDirectory()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.toList()
            ?: emptyList()
    }
        private fun extractSkillName(file: File): String? {
        val skillsDir = config.getSkillsRootDirectory()
        val filePath = file.absolutePath

        if (filePath.startsWith(skillsDir.absolutePath)) {
            val relativePath = filePath.substring(skillsDir.absolutePath.length)
        val parts = relativePath.split(File.separator)
        return if (parts.size > 1) parts[1] else null
        }
        return null
    }
        fun getStats(): ReloadStats {
        return ReloadStats(
            totalReloads = statsReloadCount.get(),
            totalErrors = statsErrorCount.get(),
            lastReloadTime = statsLastReloadTime.get(),
            watchedFileCount = fileTimestamps.size,
            watchedDirectoryCount = watchedDirectories.size
        )
    }
        fun isCurrentlyWatching(): Boolean = isWatching.get()
        fun isPaused(): Boolean = isPaused.get()
        private fun notifyFilesChanged(change: FileChange) {
        val skillName = extractSkillName(change.file) ?: "unknown"
        val event = ReloadEvent(
            skillName = skillName,
            changedFiles = listOf(change)
        )

        listeners.forEach { listener ->
            runCatching {
                listener.onFilesChanged(event)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying files changed", e)
            }
        }
    }
        private fun notifySkillReloaded(skillName: String, success: Boolean, error: String) {
        listeners.forEach { listener ->
            runCatching {
                listener.onSkillReloaded(skillName, success, error)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying skill reloaded", e)
            }
        }
    }
        private fun notifyError(error: String) {
        listeners.forEach { listener ->
            runCatching {
                listener.onError(error)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying error", e)
            }
        }
    }

    data class ReloadStats(
        val totalReloads: Long,
        val totalErrors: Long,
        val lastReloadTime: Long,
        val watchedFileCount: Int,
        val watchedDirectoryCount: Int
    )
}