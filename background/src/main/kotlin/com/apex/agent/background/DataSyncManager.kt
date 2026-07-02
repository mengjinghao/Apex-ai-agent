package com.apex.agent.background

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 数据同步管理器：负责本地-本地、本地-远程（占位）的数据同步。
 *
 * 支持的 source/target URI 协议：
 * - `local://path`        : 本地文件/目录
 * - `payload://name`      : 内存中的字符串数据（通过 [extraPayload] 传入）
 * - `remote://path`       : 远程资源（占位，需上层注入 [RemoteSyncAdapter]）
 *
 * 同步过程通过 [_syncStatus] 暴露实时进度，UI 可观察。
 */
class DataSyncManager(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val remoteAdapter: RemoteSyncAdapter? = null
) {

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: Flow<SyncStatus> = _syncStatus.asStateFlow()

    companion object {
        private const val TAG = "DataSyncManager"
        private const val BUFFER_SIZE = 8 * 1024
    }

    /**
     * 同步入口。
     *
     * @param source 源 URI，例如 `local:///data/data/.../foo.json`
     * @param target 目标 URI
     * @param extraPayload 当 source 为 `payload://` 时使用
     */
    suspend fun syncData(
        source: String,
        target: String,
        extraPayload: ByteArray? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _syncStatus.value = SyncStatus.Syncing(0f)
        try {
            val result = when {
                source.startsWith("local://") && target.startsWith("local://") ->
                    syncLocalToLocal(source.removePrefix("local://"), target.removePrefix("local://"))

                source.startsWith("payload://") && target.startsWith("local://") ->
                    syncPayloadToLocal(
                        payload = extraPayload ?: source.removePrefix("payload://").toByteArray(),
                        targetPath = target.removePrefix("local://")
                    )

                source.startsWith("remote://") && target.startsWith("local://") ->
                    syncRemoteToLocal(
                        remotePath = source.removePrefix("remote://"),
                        localPath = target.removePrefix("local://")
                    )

                else -> {
                    _syncStatus.value = SyncStatus.Failed(IllegalArgumentException("Unsupported scheme: $source -> $target"))
                    return@withContext Result.failure(IllegalArgumentException("Unsupported scheme"))
                }
            }
            _syncStatus.value = if (result.isSuccess) SyncStatus.Idle else SyncStatus.Failed(result.exceptionOrNull() ?: RuntimeException("sync failed"))
            result
        } catch (e: Exception) {
            Log.e(TAG, "syncData failed: ${e.message}", e)
            _syncStatus.value = SyncStatus.Failed(e)
            Result.failure(e)
        }
    }

    /** 取消当前同步：仅重置状态；正在执行的 IO 由协程取消信号传递终止。 */
    fun cancelSync() {
        _syncStatus.value = SyncStatus.Idle
    }

    // ===== 内部实现 =====

    private suspend fun syncLocalToLocal(sourcePath: String, targetPath: String): Result<Unit> {
        return try {
            val src = File(sourcePath)
            val dst = File(targetPath)
            if (!src.exists()) return Result.failure(java.io.FileNotFoundException("Source not found: $sourcePath"))

            dst.parentFile?.mkdirs()
            val totalBytes = if (src.isDirectory) src.walkTopDown().filter { it.isFile }.sumOf { it.length() } else src.length()
            if (totalBytes == 0L) {
                _syncStatus.value = SyncStatus.Syncing(1f)
                return Result.success(Unit)
            }

            if (src.isDirectory) {
                copyDirectory(src, dst, totalBytes)
            } else {
                copyFile(src, dst, totalBytes)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "syncLocalToLocal failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun copyFile(src: File, dst: File, totalBytes: Long) {
        var bytesRead = 0L
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0) {
                        _syncStatus.value = SyncStatus.Syncing(bytesRead.toFloat() / totalBytes)
                    }
                }
            }
        }
        _syncStatus.value = SyncStatus.Syncing(1f)
    }

    private suspend fun copyDirectory(src: File, dst: File, totalBytes: Long) {
        var bytesCopied = 0L
        src.walkTopDown().forEach { file ->
            val relative = file.absolutePath.removePrefix(src.absolutePath)
            val target = File(dst, relative)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            if (totalBytes > 0) {
                                _syncStatus.value = SyncStatus.Syncing(bytesCopied.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }
        }
        _syncStatus.value = SyncStatus.Syncing(1f)
    }

    private suspend fun syncPayloadToLocal(payload: ByteArray, targetPath: String): Result<Unit> {
        return try {
            val dst = File(targetPath)
            dst.parentFile?.mkdirs()
            FileOutputStream(dst).use { it.write(payload) }
            _syncStatus.value = SyncStatus.Syncing(1f)
            Log.i(TAG, "Payload synced to $targetPath (${payload.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "syncPayloadToLocal failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun syncRemoteToLocal(remotePath: String, localPath: String): Result<Unit> {
        val adapter = remoteAdapter
            ?: return Result.failure(IllegalStateException("Remote sync adapter not configured"))
        return try {
            val data = adapter.fetch(remotePath) { progress ->
                _syncStatus.value = SyncStatus.Syncing(progress)
            }
            val dst = File(localPath)
            dst.parentFile?.mkdirs()
            FileOutputStream(dst).use { it.write(data) }

            // 校验：如果 adapter 返回了 checksum，则校验
            adapter.checksumOf(remotePath)?.let { expected ->
                val actual = sha256(data)
                if (!expected.equals(actual, ignoreCase = true)) {
                    Log.w(TAG, "Checksum mismatch for $remotePath: expected=$expected, actual=$actual")
                    return Result.failure(java.io.IOException("Checksum mismatch"))
                }
            }
            _syncStatus.value = SyncStatus.Syncing(1f)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "syncRemoteToLocal failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 同步状态机：Idle / Syncing(progress 0..1) / Failed */
    sealed class SyncStatus {
        object Idle : SyncStatus()
        data class Syncing(val progress: Float) : SyncStatus()
        data class Failed(val error: Throwable) : SyncStatus()
    }
}

/**
 * 远程同步适配器：让 [DataSyncManager] 不直接依赖网络层。
 * 业务侧可以注入 OkHttp / Retrofit / MCP 客户端的实现。
 */
interface RemoteSyncAdapter {
    /**
     * 拉取远程资源。
     * @param onProgress 进度回调，参数是 0..1 的浮点
     */
    suspend fun fetch(remotePath: String, onProgress: (Float) -> Unit): ByteArray

    /** 可选：返回远程资源的校验和（null 表示不校验） */
    fun checksumOf(remotePath: String): String? = null
}
