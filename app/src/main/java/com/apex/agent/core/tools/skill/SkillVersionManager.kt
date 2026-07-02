package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.R
import com.apex.agent.data.gepa.SkillDatabase
import com.apex.agent.data.gepa.SkillVersion
import com.apex.agent.data.gepa.SkillVersionDao
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillVersionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillVersionManager"
        private const val VERSION_HISTORY_DIR = "version_history"
        private const val MAX_VERSION_HISTORY = 10

        @Volatile private var INSTANCE: SkillVersionManager? = null

        fun getInstance(context: Context): SkillVersionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillVersionManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }

    private val skillManager by lazy { SkillManager.getInstance(context) }
    private val database by lazy { SkillDatabase.getInstance(context) }
    private val versionDao by lazy { database.skillVersionDao() }

    private val versionCache = ConcurrentHashMap<String, List<SkillVersionRecord>>()

    data class SkillVersionRecord(
        val id: Int,
        val skillId: String,
        val versionNumber: String,
        val versionCode: Int,
        val changelog: String,
        val backupPath: String,
        val createdAt: Long,
        val isActive: Boolean
    )

    data class RollbackResult(
        val success: Boolean,
        val message: String,
        val rolledBackVersion: String?,
        val newCurrentVersion: String?
    )

    private fun getVersionHistoryDir(): File {
        val dir = File(context.filesDir, VERSION_HISTORY_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun saveVersion(skillId: String, version: String, changelog: String = ""): Result<SkillVersionRecord> =
        withContext(Dispatchers.IO) {
            try {
                val skill = skillManager.getAvailableSkills()[skillId]
                    ?: return@withContext Result.failure(Exception("Skill not found: ${skillId}"))

                val backupPath = createSkillBackup(skillId, skill.directory)
                    ?: return@withContext Result.failure(Exception("Failed to create backup"))

                val versionCode = parseVersionCode(version)
                val record = SkillVersionRecord(
                    id = 0,
                    skillId = skillId,
                    versionNumber = version,
                    versionCode = versionCode,
                    changelog = changelog,
                    backupPath = backupPath,
                    createdAt = System.currentTimeMillis(),
                    isActive = true
                )

                deactivateAllVersions(skillId)

                val savedRecord = saveToHistory(record)
                versionCache.remove(skillId)

                Result.success(savedRecord)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to save version for ${skillId}", e)
                Result.failure(e)
            }
        }

    suspend fun getVersionHistory(skillId: String): List<SkillVersionRecord> = withContext(Dispatchers.IO) {
        versionCache.getOrPut(skillId) {
            loadVersionHistory(skillId)
        }
    }

    fun getVersionHistoryFlow(skillId: String): Flow<List<SkillVersionRecord>> {
        return versionDao.getVersionsForSkill(skillId.hashCode()).map { dbVersions ->
            dbVersions.map { dbVersion ->
                SkillVersionRecord(
                    id = dbVersion.id,
                    skillId = skillId,
                    versionNumber = dbVersion.versionNumber.toString(),
                    versionCode = dbVersion.versionNumber,
                    changelog = dbVersion.changeDescription,
                    backupPath = "",
                    createdAt = dbVersion.createdAt,
                    isActive = dbVersion.isActive
                )
            }
        }
    }

    suspend fun rollbackToVersion(skillId: String, versionNumber: String): RollbackResult = withContext(Dispatchers.IO) {
        try {
            val history = getVersionHistory(skillId)
            val targetVersion = history.find { it.versionNumber == versionNumber }
                ?: return@withContext RollbackResult(false, "Version not found: ${versionNumber}", null, null)

            if (!targetVersion.isActive) {
                return@withContext RollbackResult(false, "Version is not active: ${versionNumber}", null, null)
            }

            val currentSkill = skillManager.getAvailableSkills()[skillId]
                ?: return@withContext RollbackResult(false, "Skill not found: ${skillId}", null, null)

            val currentBackupPath = createSkillBackup(skillId, currentSkill.directory)

            val rollbackSuccess = performRollback(skillId, targetVersion)
            if (!rollbackSuccess) {
                if (currentBackupPath != null) {
                    val currentRecord = SkillVersionRecord(
                        id = 0,
                        skillId = skillId,
                        versionNumber = currentSkill.version,
                        versionCode = parseVersionCode(currentSkill.version),
                        changelog = "Automatic backup before failed rollback",
                        backupPath = currentBackupPath,
                        createdAt = System.currentTimeMillis(),
                        isActive = true
                    )
                    saveToHistory(currentRecord)
                }
                return@withContext RollbackResult(false, "Rollback failed", null, null)
            }

            deactivateAllVersions(skillId)
            activateVersion(skillId, targetVersion.versionNumber)

            val newVersion = skillManager.getAvailableSkills()[skillId]?.version

            versionCache.remove(skillId)

            RollbackResult(true, "Successfully rolled back to ${versionNumber}", versionNumber, newVersion)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Rollback failed for ${skillId}", e)
            RollbackResult(false, e.message ?: "Rollback failed", null, null)
        }
    }

    suspend fun rollbackToPreviousVersion(skillId: String): RollbackResult = withContext(Dispatchers.IO) {
        val history = getVersionHistory(skillId)
        val previousVersion = history.drop(1).firstOrNull()
            ?: return@withContext RollbackResult(false, "No previous version available", null, null)

        rollbackToVersion(skillId, previousVersion.versionNumber)
    }

    suspend fun deleteVersion(skillId: String, versionNumber: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val history = getVersionHistory(skillId)
            val version = history.find { it.versionNumber == versionNumber }
                ?: return@withContext false

            if (version.isActive) {
                AppLogger.w(TAG, "Cannot delete active version: ${versionNumber}")
                return@withContext false
            }

            deleteBackup(version.backupPath)
            removeFromHistory(skillId, versionNumber)
            versionCache.remove(skillId)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete version ${versionNumber} for ${skillId}", e)
            false
        }
    }

    suspend fun pruneOldVersions(skillId: String, keepCount: Int = MAX_VERSION_HISTORY): Int =
        withContext(Dispatchers.IO) {
            try {
                val history = getVersionHistory(skillId)
                if (history.size <= keepCount) return@withContext 0

                val inactiveVersions = history.filter { !it.isActive }.drop(keepCount - 1)
                var deletedCount = 0

                for (version in inactiveVersions) {
                    if (deleteVersion(skillId, version.versionNumber)) {
                        deletedCount++
                    }
                }

                deletedCount
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to prune old versions for ${skillId}", e)
                0
            }
        }

    suspend fun getRollbackStatus(skillId: String): RollbackStatus = withContext(Dispatchers.IO) {
        try {
            val history = getVersionHistory(skillId)
            val currentSkill = skillManager.getAvailableSkills()[skillId]

            RollbackStatus(
                canRollback = history.size > 1 && history.any { it.isActive && !it.versionNumber.startsWith(currentSkill?.version ?: "") },
                currentVersion = currentSkill?.version ?: "unknown",
                previousVersion = history.getOrNull(1)?.versionNumber,
                isRollingBack = false,
                lastRollbackTime = history.firstOrNull()?.createdAt
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get rollback status for ${skillId}", e)
            RollbackStatus(
                canRollback = false,
                currentVersion = "unknown",
                previousVersion = null,
                isRollingBack = false,
                lastRollbackTime = null
            )
        }
    }

    data class RollbackStatus(
        val canRollback: Boolean,
        val currentVersion: String,
        val previousVersion: String?,
        val isRollingBack: Boolean,
        val lastRollbackTime: Long?
    )

    private fun createSkillBackup(skillId: String, skillDir: File): String? {
        return try {
            val backupDir = getVersionHistoryDir()
            val timestamp = System.currentTimeMillis()
            val backupPath = File(backupDir, "${skillId}_v${timestamp}")

            skillDir.copyRecursively(backupPath, overwrite = false)

            val metadataFile = File(backupPath, ".version_metadata")
            metadataFile.writeText("""
                skillId=${skillId}
                timestamp=${timestamp}
                backedUpAt=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}
            """.trimIndent())

            AppLogger.i(TAG, "Backup created: ${backupPath.absolutePath}")
            backupPath.absolutePath
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create backup for ${skillId}", e)
            null
        }
    }

    private fun performRollback(skillId: String, targetVersion: SkillVersionRecord): Boolean {
        return try {
            val backupDir = File(targetVersion.backupPath)
            if (!backupDir.exists()) {
                AppLogger.e(TAG, "Backup directory does not exist: ${targetVersion.backupPath}")
                return false
            }

            val currentSkill = skillManager.getAvailableSkills()[skillId]
            if (currentSkill != null && currentSkill.directory.exists()) {
                currentSkill.directory.deleteRecursively()
            }

            backupDir.copyRecursively(currentSkill?.directory ?: return false, overwrite = false)

            skillManager.refreshAvailableSkills()

            AppLogger.i(TAG, "Rollback completed for ${skillId} to version ${targetVersion.versionNumber}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Rollback failed for ${skillId}", e)
            false
        }
    }

    private suspend fun saveToHistory(record: SkillVersionRecord): SkillVersionRecord = withContext(Dispatchers.IO) {
        val dbVersion = SkillVersion(
            id = 0,
            skillId = record.skillId.hashCode(),
            versionNumber = record.versionCode,
            subtaskStructure = "",
            successRate = 0f,
            totalExecutions = 0,
            successfulExecutions = 0,
            createdAt = record.createdAt,
            changeDescription = record.changelog,
            isActive = record.isActive
        )

        val id = versionDao.insertVersion(dbVersion)
        record.copy(id = id.toInt())
    }

    private suspend fun loadVersionHistory(skillId: String): List<SkillVersionRecord> = withContext(Dispatchers.IO) {
        try {
            val dbVersions = versionDao.getVersionsForSkill(skillId.hashCode())
            dbVersions.map { dbVersion ->
                SkillVersionRecord(
                    id = dbVersion.id,
                    skillId = skillId,
                    versionNumber = dbVersion.versionNumber.toString(),
                    versionCode = dbVersion.versionNumber,
                    changelog = dbVersion.changeDescription,
                    backupPath = "",
                    createdAt = dbVersion.createdAt,
                    isActive = dbVersion.isActive
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load version history for ${skillId}", e)
            emptyList()
        }
    }

    private suspend fun deactivateAllVersions(skillId: String) = withContext(Dispatchers.IO) {
        try {
            versionDao.deactivateAllVersions(skillId.hashCode())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to deactivate versions for ${skillId}", e)
        }
    }

    private suspend fun activateVersion(skillId: String, versionNumber: String) = withContext(Dispatchers.IO) {
        try {
            val versionCode = parseVersionCode(versionNumber)
            val versions = versionDao.getVersionsForSkill(skillId.hashCode())
            val targetVersion = versions.find { it.versionNumber == versionCode }
            if (targetVersion != null) {
                versionDao.activateVersion(targetVersion.id)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to activate version ${versionNumber} for ${skillId}", e)
        }
    }

    private suspend fun removeFromHistory(skillId: String, versionNumber: String) = withContext(Dispatchers.IO) {
        try {
            val versionCode = parseVersionCode(versionNumber)
            val versions = versionDao.getVersionsForSkill(skillId.hashCode())
            val targetVersion = versions.find { it.versionNumber == versionCode }
            if (targetVersion != null) {
                versionDao.deleteVersion(targetVersion)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to remove version ${versionNumber} from history for ${skillId}", e)
        }
    }

    private fun deleteBackup(backupPath: String): Boolean {
        return try {
            val backupDir = File(backupPath)
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            } else {
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete backup: ${backupPath}", e)
            false
        }
    }

    private fun parseVersionCode(version: String): Int {
        val parts = version.split(".")
        return when {
            parts.size >= 3 -> (parts[0].toIntOrNull() ?: 1) * 10000 +
                    (parts[1].toIntOrNull() ?: 0) * 100 +
                    (parts[2].replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0)
            parts.size >= 2 -> (parts[0].toIntOrNull() ?: 1) * 10000 +
                    (parts[1].toIntOrNull() ?: 0) * 100
            else -> (parts[0].toIntOrNull() ?: 1) * 10000
        }
    }

    suspend fun exportVersion(skillId: String, versionNumber: String, outputFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val history = getVersionHistory(skillId)
                val version = history.find { it.versionNumber == versionNumber }
                    ?: return@withContext false

                val backupDir = File(version.backupPath)
                if (!backupDir.exists()) {
                    return@withContext false
                }

                ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                    backupDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val entryName = backupDir.toURI().relativize(file.toURI()).path.removePrefix("/")
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }

                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to export version ${versionNumber} for ${skillId}", e)
                false
            }
        }

    suspend fun getVersionStorageSize(): Long = withContext(Dispatchers.IO) {
        try {
            val historyDir = getVersionHistoryDir()
            historyDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            0L
        }
    }
}