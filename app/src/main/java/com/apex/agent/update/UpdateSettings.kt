package com.apex.agent.update

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.sdk.storage.ApexDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 热更新偏好设置。
 *
 * 持久化字段通过 [ApexDataStore] 保存（跨 APK 共享）：
 * - [KEY_REPO_OWNER] / [KEY_REPO_NAME] — 仓库归属（默认 mengjinghao / Apex-ai-agent）
 * - [KEY_AUTO_CHECK] — 启动时自动检查更新
 * - [KEY_INCLUDE_PRERELEASE] — 是否包含预发布版本
 * - [KEY_CHECK_INTERVAL_HOURS] — 最小检查间隔（小时），避免频繁请求 GitHub API
 * - [KEY_LAST_CHECK_TIMESTAMP] — 上次检查时间戳
 * - [KEY_LAST_IGNORED_VERSION] — 用户主动忽略的版本
 */
object UpdateSettings {

    private const val TAG = "UpdateSettings"

    const val DEFAULT_REPO_OWNER = "mengjinghao"
    const val DEFAULT_REPO_NAME = "Apex-ai-agent"

    const val KEY_REPO_OWNER = "update.repo.owner"
    const val KEY_REPO_NAME = "update.repo.name"
    const val KEY_AUTO_CHECK = "update.auto_check"
    const val KEY_INCLUDE_PRERELEASE = "update.include_prerelease"
    const val KEY_CHECK_INTERVAL_HOURS = "update.check_interval_hours"
    const val KEY_LAST_CHECK_TIMESTAMP = "update.last_check_ts"
    const val KEY_LAST_IGNORED_VERSION = "update.last_ignored_version"
    const val KEY_DOWNLOAD_OVER_WIFI_ONLY = "update.download_wifi_only"

    /** 仓库归属。 */
    fun repoOwnerFlow(context: Context): Flow<String> =
        ApexDataStore.string(context, KEY_REPO_OWNER, DEFAULT_REPO_OWNER)

    /** 仓库名称。 */
    fun repoNameFlow(context: Context): Flow<String> =
        ApexDataStore.string(context, KEY_REPO_NAME, DEFAULT_REPO_NAME)

    /** 启动时自动检查。 */
    fun autoCheckFlow(context: Context): Flow<Boolean> =
        ApexDataStore.boolean(context, KEY_AUTO_CHECK, default = true)

    /** 包含预发布版本。 */
    fun includePrereleaseFlow(context: Context): Flow<Boolean> =
        ApexDataStore.boolean(context, KEY_INCLUDE_PRERELEASE, default = false)

    /** 最小检查间隔（小时）。 */
    fun checkIntervalHoursFlow(context: Context): Flow<Int> =
        ApexDataStore.int(context, KEY_CHECK_INTERVAL_HOURS, default = 6)

    /** 仅 Wi-Fi 下载。 */
    fun downloadWifiOnlyFlow(context: Context): Flow<Boolean> =
        ApexDataStore.boolean(context, KEY_DOWNLOAD_OVER_WIFI_ONLY, default = true)

    /** 上次忽略的版本。 */
    fun lastIgnoredVersionFlow(context: Context): Flow<String> =
        ApexDataStore.string(context, KEY_LAST_IGNORED_VERSION, default = "")

    suspend fun getRepoOwner(context: Context): String =
        repoOwnerFlow(context).first()

    suspend fun getRepoName(context: Context): String =
        repoNameFlow(context).first()

    suspend fun isAutoCheckEnabled(context: Context): Boolean =
        autoCheckFlow(context).first()

    suspend fun isIncludePrerelease(context: Context): Boolean =
        includePrereleaseFlow(context).first()

    suspend fun getCheckIntervalHours(context: Context): Int =
        checkIntervalHoursFlow(context).first()

    suspend fun isDownloadWifiOnly(context: Context): Boolean =
        downloadWifiOnlyFlow(context).first()

    suspend fun getLastIgnoredVersion(context: Context): String =
        lastIgnoredVersionFlow(context).first()

    suspend fun getLastCheckTimestamp(context: Context): Long =
        ApexDataStore.getLongSync(context, KEY_LAST_CHECK_TIMESTAMP, default = 0L)

    suspend fun setAutoCheck(context: Context, enabled: Boolean) {
        ApexDataStore.putBoolean(context, KEY_AUTO_CHECK, enabled)
    }

    suspend fun setIncludePrerelease(context: Context, enabled: Boolean) {
        ApexDataStore.putBoolean(context, KEY_INCLUDE_PRERELEASE, enabled)
    }

    suspend fun setCheckIntervalHours(context: Context, hours: Int) {
        ApexDataStore.putInt(context, KEY_CHECK_INTERVAL_HOURS, hours.coerceIn(1, 168))
    }

    suspend fun setDownloadWifiOnly(context: Context, enabled: Boolean) {
        ApexDataStore.putBoolean(context, KEY_DOWNLOAD_OVER_WIFI_ONLY, enabled)
    }

    suspend fun setLastIgnoredVersion(context: Context, version: String) {
        ApexDataStore.putString(context, KEY_LAST_IGNORED_VERSION, version)
    }

    suspend fun setLastCheckTimestamp(context: Context, ts: Long) {
        ApexDataStore.putLong(context, KEY_LAST_CHECK_TIMESTAMP, ts)
    }

    suspend fun setRepo(context: Context, owner: String, name: String) {
        ApexDataStore.putString(context, KEY_REPO_OWNER, owner)
        ApexDataStore.putString(context, KEY_REPO_NAME, name)
        AppLogger.i(TAG, "仓库切换为 $owner/$name")
    }

    /** 判断是否到达下一次允许检查的时间点。 */
    suspend fun shouldCheckNow(context: Context): Boolean {
        val intervalHours = getCheckIntervalHours(context)
        val last = getLastCheckTimestamp(context)
        if (last <= 0L) return true
        val elapsedMs = System.currentTimeMillis() - last
        val intervalMs = intervalHours * 60L * 60L * 1000L
        return elapsedMs >= intervalMs
    }
}

/**
 * 语义化版本比较工具。
 *
 * 支持：
 * - 纯数字版本：`1.2.3` vs `1.2.4`
 * - 带前缀 `v`：`v1.2.3`
 * - 预发布后缀：`1.2.3-beta.1` < `1.2.3`
 * - build 元数据：`1.2.3+build456`（被忽略）
 *
 * 返回值：负数表示 [a] 旧于 [b]，0 表示相等，正数表示 [a] 新于 [b]。
 */
object VersionComparator {

    /** 解析版本字符串为可比较的结构。 */
    fun parse(version: String): ParsedVersion {
        val cleaned = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+') // 去掉 build 元数据
        val (core, pre) = if (cleaned.contains('-')) {
            val idx = cleaned.indexOf('-')
            cleaned.substring(0, idx) to cleaned.substring(idx + 1)
        } else {
            cleaned to ""
        }
        val coreParts = core.split('.').map { it.toIntOrNull() ?: 0 }
        val preParts = if (pre.isBlank()) {
            emptyList()
        } else {
            pre.split('.').map { segment ->
                segment.toIntOrNull() ?: 0
            }
        }
        return ParsedVersion(coreParts, preParts, pre.isBlank())
    }

    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        // 比较 core 部分
        val maxLen = maxOf(pa.core.size, pb.core.size)
        for (i in 0 until maxLen) {
            val av = pa.core.getOrElse(i) { 0 }
            val bv = pb.core.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        // 都没有预发布 → 相等
        // 一个有预发布、一个没有 → 没有的更大（正式版 > 预发布版）
        return when {
            pa.isRelease && pb.isRelease -> 0
            pa.isRelease && !pb.isRelease -> 1
            !pa.isRelease && pb.isRelease -> -1
            else -> {
                // 都有预发布，逐段比较
                val maxPre = maxOf(pa.pre.size, pb.pre.size)
                for (i in 0 until maxPre) {
                    val av = pa.pre.getOrElse(i) { 0 }
                    val bv = pb.pre.getOrElse(i) { 0 }
                    if (av != bv) return av - bv
                }
                0
            }
        }
    }

    /** 判断 [candidate] 是否新于 [current]。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private data class ParsedVersion(
        val core: List<Int>,
        val pre: List<Int>,
        val isRelease: Boolean
    )
}

/** 将字节数格式化为人类可读字符串。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var idx = 0
    while (v >= 1024.0 && idx < units.lastIndex) {
        v /= 1024.0
        idx++
    }
    return if (idx == 0) "${bytes} B" else String.format("%.1f %s", v, units[idx])
}
