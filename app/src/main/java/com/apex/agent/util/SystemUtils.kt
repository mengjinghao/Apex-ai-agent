package com.apex.util

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.management.ManagementFactory

/**
 * 系统工具类，提供操作系统信息、运行环境、内存管理、进程控制等常用功能
 */
object SystemUtils {

    /**
     * 获取操作系统版本字符串
     *
     * @return 操作系统版本，如 "13", "14"
     */
    fun getOsVersion(): String {
        return Build.VERSION.RELEASE
    }

    /**
     * 获取操作系统的 API 级别
     *
     * @return SDK API 级别，如 33, 34
     */
    fun getOsApiLevel(): Int {
        return Build.VERSION.SDK_INT
    }

    /**
     * 获取 Java 运行环境版本
     *
     * @return Java 版本字符串
     */
    fun getJavaVersion(): String {
        return System.getProperty("java.version") ?: "unknown"
    }

    /**
     * 获取 JVM 最大可用堆内存（字节）
     *
     * @return 最大堆内存（字节）
     */
    fun getRuntimeMaxMemory(): Long {
        return Runtime.getRuntime().maxMemory()
    }

    /**
     * 获取 JVM 当前总堆内存（字节）
     *
     * @return 总堆内存（字节）
     */
    fun getRuntimeTotalMemory(): Long {
        return Runtime.getRuntime().totalMemory()
    }

    /**
     * 获取 JVM 当前空闲堆内存（字节）
     *
     * @return 空闲堆内存（字节）
     */
    fun getRuntimeFreeMemory(): Long {
        return Runtime.getRuntime().freeMemory()
    }

    /**
     * 获取堆内存使用百分比
     *
     * @return 内存使用率（0.0 ~ 1.0）
     */
    fun getMemoryUsagePercent(): Float {
        val total = getRuntimeTotalMemory()
        if (total == 0L) return 0f
        val free = getRuntimeFreeMemory()
        return (total - free).toFloat() / total.toFloat()
    }

    /**
     * 建议系统运行垃圾回收
     */
    fun runGarbageCollection() {
        System.gc()
        System.runFinalization()
    }

    /**
     * 获取环境变量值
     *
     * @param name 环境变量名
     * @return 环境变量值，如果不存在返回 null
     */
    fun getEnvironmentVariable(name: String): String? {
        return System.getenv(name)
    }

    /**
     * 获取系统属性值
     *
     * @param name 属性名
     * @return 属性值，如果不存在返回 null
     */
    fun getSystemProperty(name: String): String? {
        return try {
            System.getProperty(name)
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * 获取所有系统属性
     *
     * @return 所有系统属性的 Map
     */
    fun getAllSystemProperties(): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        try {
            val props = System.getProperties()
            for (key in props.stringPropertyNames()) {
                properties[key] = props.getProperty(key)
            }
        } catch (e: SecurityException) {
            // 无权访问时返回空 Map
        }
        return properties
    }

    /**
     * 检查当前是否为调试模式
     *
     * @return 如果是调试模式返回 true
     */
    fun isDebugMode(): Boolean {
        return try {
            val debugField = Build::class.java.getDeclaredField("DEBUG")
            debugField.isAccessible = true
            debugField.getBoolean(null)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取当前进程名称
     *
     * @return 进程名，获取失败返回 null
     */
    fun getProcessName(): String? {
        return try {
            val reader = BufferedReader(InputStreamReader(FileInputStream(File("/proc/self/cmdline"))))
            try {
                reader.readLine()?.trim { it <= ' ' }
            } finally {
                IOUtils.closeQuietly(reader)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取当前进程 ID
     *
     * @return 进程 PID
     */
    fun getProcessId(): Int {
        return Process.myPid()
    }

    /**
     * 判断当前是否为主进程
     *
     * @param context 上下文
     * @return 如果是主进程返回 true
     */
    fun isMainProcess(context: Context): Boolean {
        val processName = getProcessName()
        return processName == context.packageName || processName == null
    }

    /**
     * 重启当前应用
     *
     * @param context 上下文
     * @param delayMs 重启延迟时间（毫秒），默认为 100ms
     */
    fun restartApp(context: Context, delayMs: Long = 100) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pendingIntent)
                killProcess()
            }
        } catch (e: Exception) {
            // 重启失败时直接退出
            killProcess()
        }
    }

    /**
     * 杀死当前进程
     */
    fun killProcess() {
        Process.killProcess(Process.myPid())
    }

    /**
     * 获取默认的 User-Agent 字符串
     *
     * @return User-Agent 字符串
     */
    fun getUserAgent(): String {
        return try {
            val userAgent = System.getProperty("http.agent")
            if (userAgent.isNullOrEmpty()) {
                "Mozilla/5.0 (Linux; Android ${getOsVersion()}; ${Build.MODEL} Build/${Build.ID}) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Mobile Safari/537.36"
            } else {
                userAgent
            }
        } catch (e: SecurityException) {
            "Mozilla/5.0 (Linux; Android ${getOsVersion()}; ${Build.MODEL}) AppleWebKit/537.36"
        }
    }
}
