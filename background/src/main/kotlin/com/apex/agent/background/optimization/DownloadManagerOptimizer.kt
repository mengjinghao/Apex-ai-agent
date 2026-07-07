package com.apex.agent.background.optimization

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class DownloadTask(
    val id: String,
    val url: String,
    val destinationPath: String,
    val fileName: String,
    val fileSizeBytes: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val headers: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val chunkedDownload: Boolean = true,
    val chunkSizeBytes: Long = 1024 * 1024,
    val verifyChecksum: Boolean = false,
    val expectedChecksum: String? = null,
    val checksumAlgorithm: String = "MD5"
)

data class DownloadProgress(
    val taskId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBps: Double,
    val progressPercent: Float,
    val estimatedTimeRemainingMs: Long,
    val activeChunks: Int,
    val currentChunk: Int,
    val totalChunks: Int
)

data class DownloadResult(
    val taskId: String,
    val filePath: String,
    val bytesDownloaded: Long,
    val durationMs: Long,
    val averageSpeedBps: Double,
    val success: Boolean,
    val errorMessage: String? = null,
    val checksumVerified: Boolean = false,
    val retryCount: Int = 0
)

data class DownloadMetrics(
    val totalDownloads: Long,
    val completedDownloads: Long,
    val failedDownloads: Long,
    val totalBytesDownloaded: Long,
    val averageSpeedBps: Double,
    val activeDownloads: Int,
    val queuedDownloads: Int,
    val cacheHitRate: Double,
    val averageDownloadSize: Long
)

data class DownloadRequest(
    val url: String,
    val destinationDir: String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
    val fileName: String? = null,
    val priority: Int = 0,
    val chunked: Boolean = true,
    val overwrite: Boolean = false
)

data class SpeedRecord(
    val timestampMs: Long,
    val bytesTransferred: Long,
    val instantaneousSpeedBps: Double
)

class DownloadManagerOptimizer private constructor() {

    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val downloadQueue = PriorityBlockingQueue()
    private val downloadHistory = CopyOnWriteArrayList<DownloadResult>()
    private val speedHistory = CopyOnWriteArrayList<SpeedRecord>()
    private val dnsCache = ConcurrentHashMap<String, String>()
    private var context: Context? = null
    private var scope: CoroutineScope? = null
    private var isRunning = false
    private val totalBytesDownloaded = AtomicLong(0)
    private val completedCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)
    private val semaphore = Semaphore(3)

    companion object {
        @Volatile
        private var instance: DownloadManagerOptimizer? = null

        fun getInstance(): DownloadManagerOptimizer {
            return instance ?: synchronized(this) {
                instance ?: DownloadManagerOptimizer().also { instance = it }
            }
        }

        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 30000
        private const val MAX_REDIRECTS = 5
        private const val SPEED_SAMPLE_INTERVAL_MS = 1000L
        private const val MAX_SPEED_HISTORY = 100
    }

    fun initialize(ctx: Context, coroutineScope: CoroutineScope) {
        context = ctx
        scope = coroutineScope
        isRunning = true
        coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                processQueue()
                delay(500)
            }
        }
    }

    fun shutdown() {
        isRunning = false
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        downloadQueue.clear()
    }

    fun enqueue(request: DownloadRequest): String {
        val id = "dl_${System.currentTimeMillis()}_${request.url.hashCode()}"
        val fileName = request.fileName ?: Uri.parse(request.url).lastPathSegment ?: "download_$id"
        val destPath = "${request.destinationDir}/$fileName"
        val task = DownloadTask(
            id = id,
            url = request.url,
            destinationPath = destPath,
            fileName = fileName,
            chunkedDownload = request.chunked,
            priority = request.priority
        )
        downloadQueue.add(task)
        id
    }

    suspend fun download(task: DownloadTask): DownloadResult {
        val startTime = System.currentTimeMillis()
        if (activeDownloads.containsKey(task.id)) {
            return DownloadResult(task.id, "", 0, 0, 0.0, false, "Already downloading")
        }

        val job = scope?.launch(Dispatchers.IO) {
            var lastError: String? = null
            var attempt = 0

            while (attempt <= task.maxRetries) {
                attempt++
                try {
                    val result = if (task.chunkedDownload && task.fileSizeBytes > task.chunkSizeBytes) {
                        downloadChunked(task)
                    } else {
                        downloadSingle(task)
                    }

                    var checksumOk = true
                    if (task.verifyChecksum && task.expectedChecksum != null) {
                        checksumOk = verifyChecksum(File(task.destinationPath), task.expectedChecksum, task.checksumAlgorithm)
                    }

                    val duration = System.currentTimeMillis() - startTime
                    val report = DownloadResult(
                        taskId = task.id,
                        filePath = task.destinationPath,
                        bytesDownloaded = result.bytesTransferred,
                        durationMs = duration,
                        averageSpeedBps = if (duration > 0) result.bytesTransferred.toDouble() / duration * 1000 else 0.0,
                        success = true,
                        checksumVerified = checksumOk,
                        retryCount = attempt - 1
                    )
                    totalBytesDownloaded.addAndGet(result.bytesTransferred)
                    completedCount.incrementAndGet()
                    downloadHistory.add(report)
                    return@launch report
                } catch (e: Exception) {
                    lastError = e.message
                    if (attempt <= task.maxRetries) {
                        delay((1000L * attempt).coerceAtMost(10000L))
                    }
                }
            }

            val report = DownloadResult(
                taskId = task.id,
                filePath = "",
                bytesDownloaded = 0,
                durationMs = System.currentTimeMillis() - startTime,
                averageSpeedBps = 0.0,
                success = false,
                errorMessage = lastError,
                retryCount = attempt - 1
            )
            failedCount.incrementAndGet()
            downloadHistory.add(report)
            report
        }

        job?.let { activeDownloads[task.id] = it }
        return job?.join().let { downloadHistory.lastOrNull { it.taskId == task.id } }
            ?: DownloadResult(task.id, "", 0, 0, 0.0, false, "Failed to start")
    }

    suspend fun downloadBatch(tasks: List<DownloadTask>): List<DownloadResult> {
        tasks.map { task ->
            semaphore.withPermit {
                download(task)
            }
        }
    }

    private suspend fun downloadSingle(task: DownloadTask): TransferResult {
        return withContext(Dispatchers.IO) {
            var bytesTransferred = 0L
            val file = File(task.destinationPath)
            file.parentFile?.mkdirs()

            val url = URL(task.url)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            task.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IOException("HTTP $responseCode: ${connection.responseMessage}")
                }

                val contentLength = connection.contentLengthLong
                val buffer = ByteArray(BUFFER_SIZE)
                var lastSpeedSample = System.currentTimeMillis()
                var chunkBytes = 0L

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(file).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesTransferred += read
                            chunkBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastSpeedSample >= SPEED_SAMPLE_INTERVAL_MS) {
                                val speed = chunkBytes.toDouble() / (now - lastSpeedSample) * 1000
                                speedHistory.add(SpeedRecord(now, chunkBytes, speed))
                                if (speedHistory.size > MAX_SPEED_HISTORY) speedHistory.removeAt(0)
                                chunkBytes = 0
                                lastSpeedSample = now
                            }
                        }
                    }
                }
                TransferResult(bytesTransferred)
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun downloadChunked(task: DownloadTask): TransferResult {
        val totalSize = resolveContentLength(task.url) ?: task.fileSizeBytes
        if (totalSize <= 0) return downloadSingle(task)

        val chunkCount = ceil(totalSize.toDouble() / task.chunkSizeBytes).toInt()
        val chunkResults = ConcurrentHashMap<Int, Long>()
        val errors = CopyOnWriteArrayList<Exception>()

        coroutineScope {
            val jobs = (0 until chunkCount).map { chunkIndex ->
                async(Dispatchers.IO) {
                    try {
                        val start = chunkIndex * task.chunkSizeBytes
                        val end = min(start + task.chunkSizeBytes - 1, totalSize - 1)
                        val chunkFile = File("${task.destinationPath}.part$chunkIndex")
                        val bytes = downloadChunk(task.url, start, end, chunkFile)
                        chunkResults[chunkIndex] = bytes
                    } catch (e: Exception) {
                        errors.add(e)
                    }
                }
            }
            jobs.awaitAll()
        }

        if (errors.isNotEmpty()) throw errors.first()

        val finalFile = File(task.destinationPath)
        finalFile.parentFile?.mkdirs()
        FileOutputStream(finalFile).use { output ->
            for (i in 0 until chunkCount) {
                val chunkFile = File("${task.destinationPath}.part$i")
                if (chunkFile.exists()) {
                    FileInputStream(chunkFile).use { input ->
                        input.copyTo(output)
                    }
                    chunkFile.delete()
                }
            }
        }

        val totalTransferred = chunkResults.values.sum()
        TransferResult(totalTransferred)
    }

    private fun downloadChunk(urlStr: String, start: Long, end: Long, destFile: File): Long {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("Range", "bytes=$start-$end")
        var transferred = 0L

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in listOf(200, 206)) {
                throw IOException("HTTP $responseCode for chunk $start-$end")
            }
            destFile.parentFile?.mkdirs()
            val buffer = ByteArray(BUFFER_SIZE)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        transferred += read
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        transferred
    }

    private fun resolveContentLength(urlStr: String): Long? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "HEAD"
            connection.connect()
            val length = connection.contentLengthLong
            connection.disconnect()
            if (length > 0) length else null
        } catch (e: Exception) {
            null
        }
    }

    fun resolveRedirect(urlStr: String, maxRedirects: Int = MAX_REDIRECTS): String {
        var current = urlStr
        for (i in 0 until maxRedirects) {
            val cached = dnsCache[current]
            if (cached != null) return cached
            try {
                val url = URL(current)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                connection.connect()
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        dnsCache[current] = location
                        current = location
                        connection.disconnect()
                        continue
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {}
            break
        }
        current
    }

    private fun verifyChecksum(file: File, expected: String, algorithm: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance(algorithm)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expected, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    fun getProgress(taskId: String): DownloadProgress? {
        val task = downloadQueue.all().find { it.id == taskId } ?: return null
        DownloadProgress(
            taskId = taskId,
            bytesDownloaded = 0,
            totalBytes = task.fileSizeBytes,
            speedBps = getCurrentSpeed(),
            progressPercent = 0f,
            estimatedTimeRemainingMs = 0,
            activeChunks = if (activeDownloads.containsKey(taskId)) 1 else 0,
            currentChunk = 0,
            totalChunks = 1
        )
    }

    fun getCurrentSpeed(): Double {
        val recent = speedHistory.takeLast(5)
        if (recent.size < 2) return 0.0
        val totalBytes = recent.sumOf { it.bytesTransferred }
        val timeSpan = recent.last().timestampMs - recent.first().timestampMs
        if (timeSpan <= 0) return 0.0
        totalBytes.toDouble() / timeSpan * 1000
    }

    fun getMetrics(): DownloadMetrics {
        val total = completedCount.get() + failedCount.get()
        DownloadMetrics(
            totalDownloads = total.toLong(),
            completedDownloads = completedCount.get().toLong(),
            failedDownloads = failedCount.get().toLong(),
            totalBytesDownloaded = totalBytesDownloaded.get(),
            averageSpeedBps = getCurrentSpeed(),
            activeDownloads = activeDownloads.size,
            queuedDownloads = downloadQueue.size(),
            cacheHitRate = if (dnsCache.isNotEmpty()) 0.3 else 0.0,
            averageDownloadSize = if (completedCount.get() > 0) totalBytesDownloaded.get() / completedCount.get() else 0
        )
    }

    fun cancelDownload(taskId: String): Boolean {
        val job = activeDownloads[taskId] ?: return false
        job.cancel()
        activeDownloads.remove(taskId)
        true
    }

    fun cancelAll() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        downloadQueue.clear()
    }

    fun getHistory(limit: Int = 50): List<DownloadResult> = downloadHistory.takeLast(limit)

    fun clearHistory() { downloadHistory.clear() }

    fun clearDnsCache() { dnsCache.clear() }

    fun getQueueSize(): Int = downloadQueue.size()

    private suspend fun processQueue() {
        if (!isRunning) return
        while (activeDownloads.size < 3) {
            val task = downloadQueue.poll() ?: break
            if (task != null) {
                scope?.launch { download(task) }
            }
        }
    }

    private data class TransferResult(val bytesTransferred: Long)

    class PriorityBlockingQueue {
        private val lock = Any()
        private val queue = sortedSetOf<DownloadTask>(compareByDescending<DownloadTask> { it.priority }.thenBy { it.id })

        fun add(task: DownloadTask) = synchronized(lock) { queue.add(task) }
        fun poll(): DownloadTask? = synchronized(lock) { queue.firstOrNull()?.also { queue.remove(it) } }
        fun remove(id: String): Boolean = synchronized(lock) { queue.removeAll { it.id == id } }
        fun clear() = synchronized(lock) { queue.clear() }
        fun size(): Int = synchronized(lock) { queue.size }
        fun all(): List<DownloadTask> = synchronized(lock) { queue.toList() }
    }
}

private fun <E> sortedSetOf(comparator: Comparator<E>): java.util.TreeSet<E> = java.util.TreeSet(comparator)
