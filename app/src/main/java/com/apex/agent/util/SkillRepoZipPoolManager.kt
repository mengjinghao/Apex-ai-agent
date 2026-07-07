package com.apex.util

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SkillRepoZipPoolManager {
    private const val TAG = "SkillRepoZipPoolManager"

    var maxPoolSize = 6
        set(value) {
            if (value > 0) {
                field = value
                AppLogger.d(TAG, "池子大小限制已更新为: ${value}")
            }
        }

    private var cacheDir: File? = null

    private val keyMutexes = ConcurrentHashMap<String, Mutex>()
    private val evictionMutex = Mutex()

    @Synchronized
    fun initialize(baseDir: File) {
        cacheDir = ApexPaths.skillRepoZipPoolDir(baseDir)
        if (cacheDir?.exists() != true) {
            runCatching { cacheDir?.mkdirs() }
        }
    }

    private fun sha256Hex16(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val hexChars = "0123456789abcdef"
        val hex = buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(hexChars[v ushr 4])
                append(hexChars[v and 0x0F])
            }
        }
        return hex.take(16)
    }

    private fun zipFileFor(dir: File, key: String): File = File(dir, "repo_${sha256Hex16(key)}.zip")

    private fun partFileFor(dir: File, key: String): File = File(dir, "repo_${sha256Hex16(key)}.download")

    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    private fun evictIfNeededLocked(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) } ?: return
        if (files.size <= maxPoolSize) return

        val removeCount = files.size - maxPoolSize
        val sorted = files.sortedBy { it.lastModified() }
        for (i in 0 until removeCount) {
            val f = sorted.getOrNull(i) ?: continue
            runCatching { f.delete() }
        }
    }

    suspend fun getOrDownloadZip(
        key: String,
        downloadTo: suspend (outFile: File) -> Boolean
    ): File? {
        val dir = cacheDir
        if (dir == null) {
            AppLogger.w(TAG, "缓存目录未初始化，无法复，ZIP")
            return null
        }

        val mutex = keyMutexes.getOrPut(key) { Mutex() }
        return mutex.withLock {
            val zipFile = zipFileFor(dir, key)
            if (zipFile.exists() && zipFile.isFile && zipFile.length() > 0L) {
                AppLogger.d(TAG, "ZIP 命中缓存: key=${key}, file=${zipFile.name}, bytes=${zipFile.length()}")
                touch(zipFile)
                return@withLock zipFile
            }

            AppLogger.d(TAG, "ZIP 缓存未命中，开始下�?key=${key}")
            val partFile = partFileFor(dir, key)
            runCatching { partFile.delete() }

            val ok = try {
                downloadTo(partFile)
            } catch (e: Exception) {
                AppLogger.e(TAG, "下载失败: key=${key}", e)
                false
            }

            if (!ok || !partFile.exists() || partFile.length() <= 0L) {
                AppLogger.w(TAG, "ZIP 下载失败或文件为�?key=${key}")
                runCatching { partFile.delete() }
                return@withLock null
            }

            runCatching { if (zipFile.exists()) zipFile.delete() }

            val renamed = runCatching { partFile.renameTo(zipFile) }.getOrNull() == true
            if (!renamed) {
                try {
                    partFile.copyTo(zipFile, overwrite = true)
                    partFile.delete()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "写入 ZIP 失败: key=${key}", e)
                    runCatching { partFile.delete() }
                    runCatching { zipFile.delete() }
                    return@withLock null
                }
            }

            touch(zipFile)

            AppLogger.d(TAG, "ZIP 已缓�?key=${key}, file=${zipFile.name}, bytes=${zipFile.length()}")

            evictionMutex.withLock {
                evictIfNeededLocked(dir)
            }

            zipFile
        }
    }
}
