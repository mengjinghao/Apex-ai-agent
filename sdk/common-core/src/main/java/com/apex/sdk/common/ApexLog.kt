package com.apex.sdk.common

import android.util.Log

/**
 * 套件级日志门面 — 所有 SDK / lib / APK 模块统一使用。
 *
 * Tag 自动加上 `[Apex/<apkId>]` 前缀，方便在 logcat 中跨 APK 过滤。
 */
object ApexLog {
    private const val GLOBAL_TAG = "Apex"

    var minLevel: Level = Level.DEBUG

    enum class Level(val value: Int) {
        VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6)
    }

    fun tag(apkId: String? = null, subTag: String? = null): String {
        val parts = mutableListOf(GLOBAL_TAG)
        if (!apkId.isNullOrBlank()) parts.add(apkId)
        if (!subTag.isNullOrBlank()) parts.add(subTag)
        return parts.joinToString("/")
    }

    fun v(apkId: String?, msg: String, t: Throwable? = null) =
        log(Level.VERBOSE, apkId, msg, t)

    fun d(apkId: String?, msg: String, t: Throwable? = null) =
        log(Level.DEBUG, apkId, msg, t)

    fun i(apkId: String?, msg: String, t: Throwable? = null) =
        log(Level.INFO, apkId, msg, t)

    fun w(apkId: String?, msg: String, t: Throwable? = null) =
        log(Level.WARN, apkId, msg, t)

    fun e(apkId: String?, msg: String, t: Throwable? = null) =
        log(Level.ERROR, apkId, msg, t)

    private fun log(level: Level, apkId: String?, msg: String, t: Throwable?) {
        if (level.value < minLevel.value) return
        val tag = tag(apkId)
        when (level) {
            Level.VERBOSE -> Log.v(tag, msg, t)
            Level.DEBUG -> Log.d(tag, msg, t)
            Level.INFO -> Log.i(tag, msg, t)
            Level.WARN -> Log.w(tag, msg, t)
            Level.ERROR -> Log.e(tag, msg, t)
        }
    }
}
