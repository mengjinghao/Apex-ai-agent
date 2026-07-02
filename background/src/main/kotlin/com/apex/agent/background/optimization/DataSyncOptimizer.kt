package com.apex.agent.background.optimization

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.min
import kotlin.math.max

data class SyncTask(
    val id: String,
    val sourcePath: String,
    val destinationPath: String,
    val fileSizeBytes: Long = 0L,
    val checksum: String? = null,
    val priority: Int = 0,
    val isDirectory: Boolean = false,
    val compressionEnabled: Boolean = true,
    val recursive: Boolean = false,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
)

data class SyncReport(
    val taskId: String,
    val bytesTransferred: Long,
    val durationMs: Long,
    val success: Boolean,
    val compressionRatio: Double = 1.0,
    val errorMessage: String? = null,
    val checksumVerified: Boolean = false
)

data class SyncBatchResult(
    val batchId: String,
    val reports: List<SyncReport>,
    val totalBytes: Long,
    val totalDurationMs: Long,
    val successCount: Int,
    val failureCount: Int
)

data class SyncMetrics(
    val totalSyncsCompleted: Long,
    val totalBytesTransferred: Long,
    val averageSpeedBps: Double,
    val compressionSavingsBytes: Long,
    val successRate: Double,
    val activeSyncs: Int
)

data class NetworkCondition(
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isMetered: Boolean,
    val bandwidthBps: Long = 0L,
    val signalStrength: Int = 0
)

class DataSyncOptimizer private constructor() {

    private val activeSyncs = ConcurrentHashMap<String, Job>()
    private val syncHistory = CopyOnWriteArrayList<SyncReport>()
    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var isRunning = false
    private val bytesTransferred = AtomicLong(0)
    private val compressionSavings = AtomicLong(0)
    private val syncCount = AtomicInteger(0)
    private val failCount = AtomicInteger(0)

    companion object {
        @Volatile
        private var instance: DataSyncOptimizer? = null

        fun getInstance(): DataSyncOptimizer {
            return instance ?: synchronized(this) {
                instance ?: DataSyncOptimizer().also { instance = it }
            }
        }

        private const val CHUNK_SIZE = 8192
        private const val COMPRESSION_THRESHOLD = 4096L
        private const val MAX_CONCURRENT_SYNCS = 3
    }

    fun initialize(ctx: Context, coroutineScope: CoroutineScope) {
        context = ctx
        scope = coroutineScope
        isRunning = true
    }

    fun shutdown() {
        isRunning = false
        activeSyncs.values.forEach { it.cancel() }
        activeSyncs.clear()
    }

    suspend fun syncFile(task: SyncTask): SyncReport {
        val startTime = System.currentTimeMillis()
        return try {
            val source = File(task.sourcePath)
            if (!source.exists()) {
                SyncReport(task.id, 0L, 0L, false, 0.0, "Source not found: ${task.sourcePath}")
            }
            val dest = File(task.destinationPath)
            dest.parentFile?.mkdirs()
            val result = if (task.compressionEnabled && task.fileSizeBytes > COMPRESSION_THRESHOLD) {
                syncWithCompression(source, dest)
            } else {
                syncDirect(source, dest)
            }
            var checksumOk = false
            if (task.checksum != null) {
                val actualChecksum = computeChecksum(dest)
                checksumOk = actualChecksum == task.checksum
            }
            bytesTransferred.addAndGet(result.bytesTransferred)
            compressionSavings.addAndGet(result.compressionSavings)
            val report = SyncReport(
                taskId = task.id,
                bytesTransferred = result.bytesTransferred,
                durationMs = System.currentTimeMillis() - startTime,
                success = true,
                compressionRatio = if (result.bytesTransferred > 0) result.bytesTransferred.toDouble() / task.fileSizeBytes else 1.0,
                checksumVerified = checksumOk
            )
            syncHistory.add(report)
            syncCount.incrementAndGet()
            report
        } catch (e: Exception) {
            val report = SyncReport(
                taskId = task.id,
                bytesTransferred = 0L,
                durationMs = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = e.message
            )
            syncHistory.add(report)
            failCount.incrementAndGet()
            report
        }
    }

    suspend fun syncBatch(tasks: List<SyncTask>): SyncBatchResult {
        val batchId = "sync_batch_${System.currentTimeMillis()}"
        val semaphore = kotlinx.coroutines.semaphore.Semaphore(MAX_CONCURRENT_SYNCS)
        val deferred = tasks.map { task ->
            scope?.async {
                semaphore.withPermit {
                    syncFile(task)
                }
            }
        }
        val reports = deferred.mapNotNull { it?.await() }
        SyncBatchResult(
            batchId = batchId,
            reports = reports,
            totalBytes = reports.sumOf { it.bytesTransferred },
            totalDurationMs = reports.maxOfOrNull { it.durationMs } ?: 0L,
            successCount = reports.count { it.success },
            failureCount = reports.count { !it.success }
        )
    }

    suspend fun syncDirectory(sourceDir: String, destDir: String, recursive: Boolean = true, compress: Boolean = true): SyncBatchResult {
        val source = File(sourceDir)
        if (!source.isDirectory) {
            return SyncBatchResult("dir_sync", emptyList(), 0L, 0L, 0, 1)
        }
        val files = if (recursive) {
            source.walkTopDown().filter { it.isFile }.toList()
        } else {
            source.listFiles()?.filter { it.isFile } ?: emptyList()
        }
        val tasks = files.map { file ->
            val relative = file.relativeTo(source).path
            SyncTask(
                id = "sync_${relative.hashCode()}",
                sourcePath = file.absolutePath,
                destinationPath = "$destDir/$relative",
                fileSizeBytes = file.length(),
                compressionEnabled = compress
            )
        }
        syncBatch(tasks)
    }

    suspend fun incrementalSync(sourceDir: String, destDir: String): SyncBatchResult {
        val source = File(sourceDir)
        if (!source.isDirectory) return SyncBatchResult("incr_sync", emptyList(), 0L, 0L, 0, 1)
        val needSync = source.walkTopDown().filter { it.isFile }.filter { file ->
            val relative = file.relativeTo(source).path
            val dest = File(destDir, relative)
            !dest.exists() || dest.lastModified() < file.lastModified() || dest.length() != file.length()
        }.toList()
        val tasks = needSync.map { file ->
            val relative = file.relativeTo(source).path
            SyncTask(
                id = "incr_${relative.hashCode()}",
                sourcePath = file.absolutePath,
                destinationPath = "$destDir/$relative",
                fileSizeBytes = file.length()
            )
        }
        syncBatch(tasks)
    }

    suspend fun syncWithRetry(task: SyncTask, maxRetries: Int = 3): SyncReport {
        var lastError: String? = null
        for (attempt in 0..maxRetries) {
            val report = syncFile(task.copy(retryCount = attempt))
            if (report.success) return report
            lastError = report.errorMessage
            if (attempt < maxRetries) {
                delay((1000L * (attempt + 1)).coerceAtMost(15000L))
            }
        }
        SyncReport(task.id, 0L, 0L, false, 0.0, "All retries exhausted: $lastError")
    }

    fun getMetrics(): SyncMetrics {
        val totalDuration = syncHistory.sumOf { it.durationMs }
        val totalBytes = syncHistory.sumOf { it.bytesTransferred }
        SyncMetrics(
            totalSyncsCompleted = syncCount.get().toLong(),
            totalBytesTransferred = bytesTransferred.get(),
            averageSpeedBps = if (totalDuration > 0) totalBytes.toDouble() / totalDuration * 1000.0 else 0.0,
            compressionSavingsBytes = compressionSavings.get(),
            successRate = if (syncHistory.size > 0) syncHistory.count { it.success }.toDouble() / syncHistory.size else 1.0,
            activeSyncs = activeSyncs.size
        )
    }

    fun getNetworkCondition(): NetworkCondition {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkCondition(false, false, true)
        val network = cm.activeNetwork ?: return NetworkCondition(false, false, true)
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkCondition(false, false, true)
        NetworkCondition(
            isConnected = true,
            isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            bandwidthBps = caps.linkDownstreamBandwidthKbps * 1000L,
            signalStrength = caps.linkDownstreamBandwidthKbps / 1000
        )
    }

    fun getEnabledOptimizations(): List<String> = listOf(
        "compression", "incremental_sync", "batch_sync",
        "checksum_verification", "bandwidth_aware", "retry_with_backoff"
    )

    private data class SyncResult(
        val bytesTransferred: Long,
        val compressionSavings: Long
    )

    private fun syncDirect(source: File, dest: File): SyncResult {
        val buffer = ByteArray(CHUNK_SIZE)
        var totalBytes = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalBytes += read
                }
            }
        }
        SyncResult(totalBytes, 0L)
    }

    private fun syncWithCompression(source: File, dest: File): SyncResult {
        val originalSize = source.length()
        val compressedFile = File.createTempFile("sync_compress", ".tmp")
        try {
            DeflaterOutputStream(FileOutputStream(compressedFile), Deflater(Deflater.BEST_SPEED)).use { deflater ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        deflater.write(buffer, 0, read)
                    }
                }
            }
            val compressedSize = compressedFile.length()
            val buffer = ByteArray(CHUNK_SIZE)
            var totalBytes = 0L
            FileInputStream(compressedFile).use { input ->
                FileOutputStream(dest).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
            }
            val savings = originalSize - compressedSize
            SyncResult(totalBytes, max(0L, savings))
        } finally {
            compressedFile.delete()
        }
    }

    private fun computeChecksum(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getHistory(limit: Int = 50): List<SyncReport> = syncHistory.takeLast(limit)

    fun clearHistory() { syncHistory.clear() }
}
