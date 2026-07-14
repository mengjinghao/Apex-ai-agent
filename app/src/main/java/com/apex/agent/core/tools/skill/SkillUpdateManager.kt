package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.R
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class SkillUpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillUpdateManager"
        private const val UPDATE_CACHE_DIR = "update_cache"
        private const val BACKUP_DIR = "skill_backups"

        @Volatile private var INSTANCE: SkillUpdateManager? = null

        fun getInstance(context: Context): SkillUpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillUpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }

    private val repoClient by lazy { SkillRepoClient.getInstance() }
    private val skillManager by lazy { SkillManager.getInstance(context) }

    private val _updateState = MutableStateFlow<Map<String, UpdateState>>(emptyMap())
    val updateState: StateFlow<Map<String, UpdateState>> = _updateState.asStateFlow()

    private val _availableUpdates = MutableStateFlow<List<UpdateInfo>>(emptyList())
    val availableUpdates: StateFlow<List<UpdateInfo>> = _availableUpdates.asStateFlow()

    private val activeUpdates = ConcurrentHashMap<String, UpdateJob>()

    data class UpdateState(
        val skillId: String,
        val status: Status,
        val progress: Float = 0f,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val errorMessage: String? = null,
        val startTime: Long = 0L,
        val endTime: Long = 0L
    )

    enum class Status {
        IDLE,
        CHECKING,
        DOWNLOADING,
        APPLYING,
        VERIFYING,
        COMPLETED,
        FAILED,
        ROLLING_BACK
    }

    data class UpdateInfo(
        val skillId: String,
        val skillName: String,
        val currentVersion: String,
        val newVersion: String,
        val updateSize: Long,
        val changelog: String,
        val isIncremental: Boolean,
        val downloadUrl: String,
        val checksum: String
    )

    data class UpdateJob(
        val skillId: String,
        val version: String,
        val isIncremental: Boolean,
        val patchFromVersion: String?,
        val downloadFile: File,
        val startTime: Long = System.currentTimeMillis()
    )

    sealed class UpdateResult {
        data class Success(
            val skillId: String,
            val newVersion: String,
            val backupPath: String?
        ) : UpdateResult()

        data class Failure(
            val skillId: String,
            val error: String,
            val rolledBack: Boolean
        ) : UpdateResult()
    }

    private fun getUpdateCacheDir(): File {
        val dir = File(context.cacheDir, UPDATE_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getBackupDir(): File {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun checkForUpdate(skillId: String, currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            updateState(skillId, UpdateState(skillId, Status.CHECKING))

            val result = repoClient.checkForUpdate(skillId, currentVersion)

            result.fold(
                onSuccess = { updateCheck ->
                    if (updateCheck.hasUpdate) {
                        val updateInfo = UpdateInfo(
                            skillId = skillId,
                            skillName = getSkillDisplayName(skillId),
                            currentVersion = currentVersion,
                            newVersion = updateCheck.latestVersion,
                            updateSize = updateCheck.updateSize,
                            changelog = updateCheck.changelog,
                            isIncremental = updateCheck.isIncremental,
                            downloadUrl = updateCheck.downloadUrl,
                            checksum = updateCheck.checksum
                        )
                        updateState(skillId, UpdateState(skillId, Status.IDLE))
                        Result.success(updateInfo)
                    } else {
                        updateState(skillId, UpdateState(skillId, Status.IDLE))
                        Result.success(null)
                    }
                },
                onFailure = { error ->
                    updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = error.message))
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check for update", e)
            updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = e.message))
            Result.failure(e)
        }
    }

    suspend fun checkAllUpdates(): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<UpdateInfo>()
        val skills = skillManager.getAvailableSkills()

        for ((skillId, skillPackage) in skills) {
            val result = checkForUpdate(skillId, skillPackage.version)
            result.getOrNull()?.let { updateInfo ->
                if (updateInfo != null) {
                    updates.add(updateInfo)
                }
            }
        }

        _availableUpdates.value = updates
        updates
    }

    suspend fun downloadUpdate(
        skillId: String,
        version: String,
        isIncremental: Boolean,
        patchFromVersion: String?,
        progressCallback: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getUpdateCacheDir()
            val outputFile = File(cacheDir, "${skillId}_${version}.zip")

            if (outputFile.exists()) {
                outputFile.delete()
            }

            updateState(skillId, UpdateState(skillId, Status.DOWNLOADING, startTime = System.currentTimeMillis()))

            val job = UpdateJob(
                skillId = skillId,
                version = version,
                isIncremental = isIncremental,
                patchFromVersion = patchFromVersion,
                downloadFile = outputFile
            )
            activeUpdates[skillId] = job

            val result = if (isIncremental && patchFromVersion != null) {
                repoClient.downloadIncrementalUpdate(
                    skillId = skillId,
                    fromVersion = patchFromVersion,
                    toVersion = version,
                    outputFile = outputFile
                ) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    updateState(skillId, UpdateState(
                        skillId,
                        Status.DOWNLOADING,
                        progress = progress,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        startTime = job.startTime
                    ))
                    progressCallback?.invoke(progress)
                }
            } else {
                repoClient.downloadSkill(
                    skillId = skillId,
                    version = version,
                    outputFile = outputFile
                ) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    updateState(skillId, UpdateState(
                        skillId,
                        Status.DOWNLOADING,
                        progress = progress,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        startTime = job.startTime
                    ))
                    progressCallback?.invoke(progress)
                }
            }

            result.fold(
                onSuccess = { file ->
                    activeUpdates.remove(skillId)
                    updateState(skillId, UpdateState(skillId, Status.VERIFYING, progress = 1f, endTime = System.currentTimeMillis()))
                    Result.success(file)
                },
                onFailure = { error ->
                    activeUpdates.remove(skillId)
                    updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = error.message, endTime = System.currentTimeMillis()))
                    outputFile.delete()
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download update for ${skillId}", e)
            activeUpdates.remove(skillId)
            updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = e.message, endTime = System.currentTimeMillis()))
            Result.failure(e)
        }
    }

    suspend fun applyUpdate(skillId: String, downloadFile: File, expectedChecksum: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            updateState(skillId, UpdateState(skillId, Status.APPLYING, startTime = System.currentTimeMillis()))

            if (!downloadFile.exists() || !downloadFile.isFile) {
                val error = context.getString(R.string.skill_error_cannot_read_file, downloadFile.absolutePath)
                updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = error))
                return@withContext UpdateResult.Failure(skillId, error, false)
            }

            if (expectedChecksum != null) {
                val actualChecksum = calculateFileChecksum(downloadFile)
                if (actualChecksum != expectedChecksum) {
                    val error = "Checksum verification failed: expected ${expectedChecksum}, got ${actualChecksum}"
                    AppLogger.e(TAG, error)
                    updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = error))
                    return@withContext UpdateResult.Failure(skillId, error, false)
                }
            }

            val skillDir = skillManager.getAvailableSkills()[skillId]?.directory
            val backupPath = if (skillDir != null && skillDir.exists()) {
                createBackup(skillId, skillDir)
            } else null

            updateState(skillId, UpdateState(skillId, Status.VERIFYING, progress = 0.5f))

            val importResult = skillManager.importSkillFromZip(downloadFile)

            if (importResult.contains(context.getString(R.string.skill_imported)) ||
                importResult.contains(context.getString(R.string.skill_imported_with_desc))) {

                val newVersion = extractVersionFromImportResult(importResult)
                downloadFile.delete()
                clearUpdateCache(skillId)

                updateState(skillId, UpdateState(skillId, Status.COMPLETED, endTime = System.currentTimeMillis()))

                AppLogger.i(TAG, "Update applied successfully for ${skillId}")
                UpdateResult.Success(skillId, newVersion, backupPath)
            } else {
                if (backupPath != null) {
                    rollbackFromBackup(skillId, backupPath)
                }
                updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = importResult, endTime = System.currentTimeMillis()))
                UpdateResult.Failure(skillId, importResult, backupPath != null)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to apply update for ${skillId}", e)
            updateState(skillId, UpdateState(skillId, Status.FAILED, errorMessage = e.message, endTime = System.currentTimeMillis()))

            val job = activeUpdates[skillId]
            if (job != null) {
                updateState(skillId, UpdateState(skillId, Status.ROLLING_BACK))
                val skillDir = skillManager.getAvailableSkills()[skillId]?.directory
                if (skillDir != null && skillDir.exists()) {
                    val backupPath = createBackup(skillId, skillDir)
                    if (backupPath != null) {
                        rollbackFromBackup(skillId, backupPath)
                        return@withContext UpdateResult.Failure(skillId, e.message ?: "Unknown error", true)
                    }
                }
            }

            UpdateResult.Failure(skillId, e.message ?: "Unknown error", false)
        }
    }

    suspend fun performUpdate(
        skillId: String,
        updateInfo: UpdateInfo,
        progressCallback: ((Float, String) -> Unit)? = null
    ): UpdateResult = withContext(Dispatchers.IO) {
        try {
            progressCallback?.invoke(0f, "Downloading update...")

            val downloadResult = downloadUpdate(
                skillId = skillId,
                version = updateInfo.newVersion,
                isIncremental = updateInfo.isIncremental,
                patchFromVersion = if (updateInfo.isIncremental) updateInfo.currentVersion else null
            ) { progress ->
                progressCallback?.invoke(progress * 0.8f, "Downloading update...")
            }

            val downloadFile = downloadResult.getOrElse { return@withContext UpdateResult.Failure(skillId, it.message ?: "Download failed", false) }

            progressCallback?.invoke(0.8f, "Applying update...")

            val applyResult = applyUpdate(skillId, downloadFile, updateInfo.checksum)

            when (applyResult) {
                is UpdateResult.Success -> {
                    progressCallback?.invoke(1f, "Update completed")
                }
                is UpdateResult.Failure -> {
                    if (!applyResult.rolledBack) {
                        progressCallback?.invoke(0f, "Update failed: ${applyResult.error}")
                    }
                }
            }

            applyResult
        } catch (e: Exception) {
            AppLogger.e(TAG, "Update failed for ${skillId}", e)
            UpdateResult.Failure(skillId, e.message ?: "Unknown error", false)
        }
    }

    private fun createBackup(skillId: String, skillDir: File): String? {
        return try {
            val backupDir = getBackupDir()
            val backupPath = File(backupDir, "${skillId}_${System.currentTimeMillis()}")
            skillDir.copyRecursively(backupPath, overwrite = false)
            AppLogger.i(TAG, "Backup created at ${backupPath.absolutePath}")
            backupPath.absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create backup for ${skillId}", e)
            null
        }
    }

    private fun rollbackFromBackup(skillId: String, backupPath: String): Boolean {
        return try {
            val backupDir = File(backupPath)
            if (!backupDir.exists()) {
                AppLogger.w(TAG, "Backup directory does not exist: ${backupPath}")
                return false
            }

            val skillDir = skillManager.getAvailableSkills()[skillId]?.directory
            if (skillDir != null && skillDir.exists()) {
                skillDir.deleteRecursively()
            }

            backupDir.copyRecursively(skillDir ?: return false, overwrite = false)
            AppLogger.i(TAG, "Rollback completed for ${skillId}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Rollback failed for ${skillId}", e)
            false
        }
    }

    private fun calculateFileChecksum(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to calculate checksum", e)
            ""
        }
    }

    private fun clearUpdateCache(skillId: String) {
        try {
            val cacheDir = getUpdateCacheDir()
            cacheDir.listFiles()?.filter { it.name.startsWith("${skillId}_") }?.forEach { it.delete() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear update cache for ${skillId}", e)
        }
    }

    private fun getSkillDisplayName(skillId: String): String {
        return skillManager.getAvailableSkills()[skillId]?.name ?: skillId
    }

    private fun extractVersionFromImportResult(result: String): String {
        val regex = """(\d+\.\d+\.\d+)""".toRegex()
        return regex.find(result)?.value ?: "unknown"
    }

    private fun updateState(skillId: String, newState: UpdateState) {
        _updateState.value = _updateState.value.toMutableMap().apply {
            put(skillId, newState)
        }
    }

    fun getUpdateState(skillId: String): UpdateState? {
        return _updateState.value[skillId]
    }

    fun clearUpdateState(skillId: String) {
        _updateState.value = _updateState.value.toMutableMap().apply {
            remove(skillId)
        }
    }

    fun clearAllUpdateStates() {
        _updateState.value = emptyMap()
    }

    suspend fun clearAllCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            getUpdateCacheDir().listFiles()?.forEach { it.deleteRecursively() }
            getBackupDir().listFiles()?.forEach { it.deleteRecursively() }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear cache", e)
            false
        }
    }

    fun getCacheSize(): Long {
        return try {
            val cacheDir = getUpdateCacheDir()
            val backupDir = getBackupDir()
            (cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() +
                    backupDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum())
        } catch (e: Exception) {
            0L
        }
    }
}