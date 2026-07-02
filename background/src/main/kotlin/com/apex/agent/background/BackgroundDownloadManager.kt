package com.apex.agent.background

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class BackgroundDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: Flow<DownloadProgress?> = _downloadProgress.asStateFlow()

    suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read

                        if (contentLength > 0) {
                            _downloadProgress.value = DownloadProgress(
                                url,
                                bytesRead,
                                contentLength,
                                false
                            )
                            onProgress?.invoke(bytesRead, contentLength)
                        }
                    }
                }
            }

            _downloadProgress.value = DownloadProgress(url, contentLength, contentLength, true)
            Result.success(destFile)
        } catch (e: Exception) {
            _downloadProgress.value = DownloadProgress(url, 0, 0, false)
            Result.failure(e)
        }
    }

    fun cancelDownload() {
        _downloadProgress.value = null
    }

    data class DownloadProgress(
        val url: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean
    )
}
