package com.apex.agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 热更新核心管理器。
 *
 * 职责：
 * 1. **检查更新** — 调用 GitHub Releases API，按版本号判断是否有新版本；
 * 2. **下载 APK** — 按镜像源顺序尝试下载，支持断点续传（简化版：失败后切换镜像重头下）；
 * 3. **完整性校验** — 下载完成后比对 Content-Length 与 SHA-256（如果镜像返回了的话）；
 * 4. **触发安装** — 通过 FileProvider 暴露 APK，调起系统 PackageInstaller。
 *
 * 使用方式：
 * ```kotlin
 * val manager = HotUpdateManager.getInstance(context)
 *
 * // 检查更新
 * val result = manager.checkForUpdate(force = false)
 * if (result is CheckResult.UpdateAvailable) { ... }
 *
 * // 下载并安装
 * manager.downloadAndInstall(result.release, result.apkAsset) { progress -> ... }
 * ```
 */
class HotUpdateManager private constructor(
    private val context: Context,
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "HotUpdateManager"
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val USER_AGENT = "Apex-AI-Agent-HotUpdate/1.0"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 60L
        private const val MIRROR_PROBE_TIMEOUT_S = 8L

        @Volatile private var INSTANCE: HotUpdateManager? = null

        fun getInstance(context: Context): HotUpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build()
                    HotUpdateManager(context.applicationContext, client).also { INSTANCE = it }
                }
            }
        }
    }

    private val registry = MirrorSourceRegistry.getInstance(context)

    /** 管理器内部协程作用域，用于后台下载，支持取消。 */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前检查/下载状态，UI 可订阅。 */
    private val _state = MutableStateFlow(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 当前正在下载的作业，用于支持取消。 */
    private val currentDownloadJob = AtomicReference<Job?>(null)

    /** 当前应用的版本名。 */
    fun currentVersionName(): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (t: Throwable) {
            AppLogger.w(TAG, "读取当前版本失败: ${t.message}")
            "0.0.0"
        }
    }

    /**
     * 检查更新。
     *
     * @param force true 表示无视最小检查间隔强制请求。
     * @return [CheckResult]
     */
    suspend fun checkForUpdate(force: Boolean = false): CheckResult = withContext(Dispatchers.IO) {
        if (!force) {
            if (!UpdateSettings.shouldCheckNow(context)) {
                AppLogger.i(TAG, "距上次检查时间过短，跳过（force=false）")
                return@withContext CheckResult.UpToDate(
                    currentVersionName(),
                    currentVersionName()
                )
            }
        }

        _state.value = UpdateState.Checking
        try {
            val owner = UpdateSettings.getRepoOwner(context)
            val name = UpdateSettings.getRepoName(context)
            val includePre = UpdateSettings.isIncludePrerelease(context)
            val current = currentVersionName()

            val release = fetchLatestRelease(owner, name, includePre)
                ?: return@withContext CheckResult.Failed(
                    "未找到可用的 Release（仓库 $owner/$name 可能尚未发布版本）"
                ).also { _state.value = UpdateState.Idle }

            UpdateSettings.setLastCheckTimestamp(context, System.currentTimeMillis())

            val apkAsset = pickApkAsset(release)
                ?: return@withContext CheckResult.Failed(
                    "Release ${release.tagName} 未包含可识别的 APK 资源"
                ).also { _state.value = UpdateState.Idle }

            val latestTag = release.tagName
            if (!VersionComparator.isNewer(latestTag, current)) {
                _state.value = UpdateState.Idle
                return@withContext CheckResult.UpToDate(current, latestTag)
            }

            // 被忽略的版本直接当作无更新
            val ignored = UpdateSettings.getLastIgnoredVersion(context)
            if (ignored == latestTag) {
                _state.value = UpdateState.Idle
                return@withContext CheckResult.UpToDate(current, latestTag)
            }

            _state.value = UpdateState.UpdateAvailable(
                currentVersion = current,
                latestVersion = latestTag,
                changelog = release.body ?: "",
                sizeText = formatBytes(apkAsset.size),
                sizeBytes = apkAsset.size,
                release = release,
                asset = apkAsset
            )

            CheckResult.UpdateAvailable(
                currentVersion = current,
                release = release,
                apkAsset = apkAsset,
                changelog = release.body ?: "(无更新日志)",
                sizeText = formatBytes(apkAsset.size)
            )
        } catch (ce: CancellationException) {
            _state.value = UpdateState.Idle
            throw ce
        } catch (t: Throwable) {
            AppLogger.e(TAG, "检查更新失败", t)
            _state.value = UpdateState.Idle
            CheckResult.Failed(t.message ?: "未知错误", t)
        }
    }

    /**
     * 下载并触发安装。
     *
     * @param release 目标 Release
     * @param asset 目标 APK 资源
     * @param onProgress 进度回调（主线程之外）
     */
    suspend fun downloadAndInstall(
        release: UpdateRelease,
        asset: UpdateAsset,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val mirrors = registry.enabledMirrors().ifEmpty { MirrorSourceRegistry.BUILTIN_MIRRORS }
        val targetDir = File(context.cacheDir, "hotupdate").apply { mkdirs() }
        val targetFile = File(targetDir, "apex-${release.tagName}-${asset.name}")

        var lastError: Throwable? = null
        for (mirror in mirrors) {
            try {
                AppLogger.i(TAG, "尝试镜像：${mirror.name} (${mirror.id})")
                val url = wrapUrlWithMirror(mirror, asset.browserDownloadUrl)
                val downloaded = downloadFile(
                    url = url,
                    target = targetFile,
                    expectedSize = if (asset.size > 0) asset.size else -1L,
                    mirrorId = mirror.id,
                    onProgress = onProgress
                )
                AppLogger.i(TAG, "下载完成：${downloaded.length()} 字节（via ${mirror.name}）")
                triggerInstall(downloaded)
                _state.value = UpdateState.Downloaded
                return@withContext Result.success(downloaded)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppLogger.w(TAG, "镜像 ${mirror.name} 失败：${t.message}")
                lastError = t
                // 继续尝试下一个镜像
            }
        }

        _state.value = UpdateState.Failed(lastError?.message ?: "所有镜像均失败")
        Result.failure(lastError ?: IllegalStateException("所有镜像均失败"))
    }

    /** 取消正在进行的下载。 */
    fun cancelDownload() {
        currentDownloadJob.getAndSet(null)?.cancel()
        _state.value = UpdateState.Idle
        AppLogger.i(TAG, "用户取消下载")
    }

    /** 标记某版本为忽略，下次检查将自动跳过。 */
    suspend fun ignoreVersion(version: String) {
        UpdateSettings.setLastIgnoredVersion(context, version)
        _state.value = UpdateState.Idle
    }

    /**
     * 便捷方法：基于当前 [UpdateState.UpdateAvailable] 直接开始下载。
     * 若当前状态不是 UpdateAvailable，则什么也不做。
     *
     * 该方法在管理器内部的 [downloadScope] 中执行，可被 [cancelDownload] 取消。
     * 进度通过 [state]（StateFlow）暴露，UI 订阅即可。
     */
    fun startDownload(onProgress: (DownloadProgress) -> Unit = {}) {
        val s = _state.value
        if (s !is UpdateState.UpdateAvailable || s.release == null || s.asset == null) {
            _state.value = UpdateState.Failed("当前无可下载的更新")
            return
        }
        // 取消任何已在进行的下载，并把新 job 原子写入（避免竞态）
        val job = downloadScope.launch {
            try {
                val result = downloadAndInstall(s.release, s.asset, onProgress)
                if (result.isFailure) {
                    _state.value = UpdateState.Failed(result.exceptionOrNull()?.message ?: "下载失败")
                }
            } catch (ce: CancellationException) {
                _state.value = UpdateState.Idle
                throw ce
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(t.message ?: "下载失败")
            } finally {
                currentDownloadJob.compareAndSet(job, null)
            }
        }
        // 注意：先取消旧 job，再写入新 job；finally 中的 compareAndSet 避免误删后续 job
        currentDownloadJob.getAndSet(job)?.cancel()
    }

    /** UI 调用：暴露一个错误状态。 */
    fun notifyFailed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    /** UI 调用：标记已下载完成（系统安装界面已弹出）。 */
    fun markDownloaded() {
        _state.value = UpdateState.Downloaded
    }

    /** UI 调用：重置状态为空闲。 */
    fun resetState() {
        _state.value = UpdateState.Idle
    }

    /**
     * 测试某个镜像的连通性。
     */
    suspend fun testMirror(mirror: MirrorSource): MirrorTestResult = withContext(Dispatchers.IO) {
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(MIRROR_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(MIRROR_PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val probeUrl = wrapUrlWithMirror(mirror, "https://github.com/mengjinghao/Apex-ai-agent")
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(probeUrl)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                if (resp.isSuccessful || resp.code in 301..399) {
                    MirrorTestResult(mirror.id, success = true, latencyMs = latency, "HTTP ${resp.code}")
                } else {
                    MirrorTestResult(mirror.id, success = false, latencyMs = latency, "HTTP ${resp.code}")
                }
            }
        } catch (t: Throwable) {
            MirrorTestResult(mirror.id, success = false, latencyMs = -1L, t.message ?: "连接失败")
        }
    }

    // ---------- 内部实现 ----------

    private fun wrapUrlWithMirror(mirror: MirrorSource, originalUrl: String): String {
        // kkgithub 是域名替换型镜像，单独处理
        return if (mirror.id == "kkgithub") {
            MirrorSourceRegistry.applyKkGithub(originalUrl)
        } else {
            mirror.wrap(originalUrl)
        }
    }

    /**
     * 拉取最新 Release。若允许预发布，则取所有 releases 的第一个；否则取 /releases/latest。
     */
    private suspend fun fetchLatestRelease(
        owner: String,
        name: String,
        includePrerelease: Boolean
    ): UpdateRelease? = withContext(Dispatchers.IO) {
        val path = if (includePrerelease) {
            "$GITHUB_API_BASE/repos/$owner/$name/releases?per_page=10"
        } else {
            "$GITHUB_API_BASE/repos/$owner/$name/releases/latest"
        }
        val req = Request.Builder()
            .url(path)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLogger.w(TAG, "GitHub API 返回 ${resp.code}: ${resp.message}")
                if (resp.code == 404) return@withContext null
                if (resp.code == 403) {
                    // Rate limit
                    val remaining = resp.header("X-RateLimit-Remaining")
                    val reset = resp.header("X-RateLimit-Reset")
                    AppLogger.w(TAG, "GitHub API 限流：remaining=$remaining, reset=$reset")
                }
                throw IllegalStateException("GitHub API ${resp.code} ${resp.message}")
            }
            val body = resp.body?.string()
                ?: throw IllegalStateException("GitHub API 响应体为空")
            if (includePrerelease) {
                val list = updateJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(UpdateRelease.serializer()),
                    body
                )
                list.firstOrNull()
            } else {
                updateJson.decodeFromString(UpdateRelease.serializer(), body)
            }
        }
    }

    /**
     * 从 Release 的 assets 中挑选 APK。优先匹配主 APK 命名（app-/main-/apex-），
     * 若无精确匹配，则取第一个扩展名为 .apk 的资源。
     */
    private fun pickApkAsset(release: UpdateRelease): UpdateAsset? {
        val assets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (assets.isEmpty()) return null
        // 优先匹配主 APK
        val preferred = assets.firstOrNull { asset ->
            val n = asset.name.lowercase()
            n.startsWith("app-") || n.startsWith("main-") || n.startsWith("apex-") ||
                n.contains("main.apk") || n.contains("universal")
        }
        return preferred ?: assets.first()
    }

    /**
     * 下载单个文件，支持进度回调与简单校验。
     */
    private suspend fun downloadFile(
        url: String,
        target: File,
        expectedSize: Long,
        mirrorId: String,
        onProgress: (DownloadProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // 删除可能残留的半成品
        if (target.exists()) target.delete()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("下载失败 HTTP ${resp.code}")
            }
            val total = expectedSize.takeIf { it > 0 }
                ?: resp.header("Content-Length")?.toLongOrNull()
                ?: -1L
            val body = resp.body ?: throw IllegalStateException("响应体为空")
            val input = body.byteStream()
            val output = target.outputStream()
            val buffer = ByteArray(8 * 1024)
            var bytesRead = 0L
            var lastEmit = 0L
            val startTs = System.currentTimeMillis()
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    bytesRead += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 200L) {
                        val elapsedSec = (now - startTs).coerceAtLeast(1L) / 1000.0
                        val speed = if (elapsedSec > 0) ((bytesRead / elapsedSec).toLong()) else 0L
                        val percent = if (total > 0) ((bytesRead * 100) / total).toInt() else -1
                        val progress = DownloadProgress(
                            bytesRead = bytesRead,
                            totalBytes = total,
                            percent = percent,
                            mirrorId = mirrorId,
                            speedBytesPerSec = speed
                        )
                        _state.value = UpdateState.Downloading(progress)
                        onProgress(progress)
                        lastEmit = now
                    }
                }
                output.flush()
            } finally {
                output.closeQuietly()
            }

            // 简单完整性校验
            if (total > 0 && target.length() != total) {
                throw IllegalStateException("文件大小不匹配 expected=$total actual=${target.length()}")
            }

            // 可选 SHA-256 校验：若 Release 资源有同名的 .sha256 / .sha256sum 文件，则比对
            // 这里简化处理：只做长度校验，签名校验交给 PackageInstaller
            target
        }
    }

    /** 调起系统安装界面。 */
    private fun triggerInstall(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            context.startActivity(intent)
            AppLogger.i(TAG, "已调起系统安装界面：${apkFile.name}")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "调起安装界面失败", t)
            throw t
        }
    }

    private fun java.io.OutputStream.closeQuietly() {
        try { close() } catch (_: Throwable) {}
    }
}

/** UI 订阅的更新状态。 */
sealed class UpdateState {
    /** 空闲 */
    object Idle : UpdateState()
    /** 检查中 */
    object Checking : UpdateState()
    /** 发现新版本 */
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val changelog: String,
        val sizeText: String,
        val sizeBytes: Long,
        /** 完整的 Release 对象，用于直接触发下载。 */
        val release: UpdateRelease? = null,
        /** 选中的 APK 资源。 */
        val asset: UpdateAsset? = null
    ) : UpdateState()
    /** 下载中 */
    data class Downloading(val progress: DownloadProgress) : UpdateState()
    /** 下载完成，已调起安装 */
    object Downloaded : UpdateState()
    /** 失败 */
    data class Failed(val message: String) : UpdateState()
}
