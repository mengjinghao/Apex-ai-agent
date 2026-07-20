package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class TerminalContext(
    val currentDirectory: String = "/",
    val environmentVariables: Map<String, String> = emptyMap(),
    val recentCommands: List<String> = emptyList(),
    val availableShells: List<String> = emptyList(),
    val androidInfo: AndroidSystemInfo = AndroidSystemInfo(),
    val runningProcesses: List<ProcessInfo> = emptyList(),
    val storageInfo: StorageInfo = StorageInfo(),
    val networkInfo: NetworkInfo = NetworkInfo(),
    val deviceModel: String = "",
    val androidVersion: String = "",
    val sdkVersion: Int = 0,
    val isRootAvailable: Boolean = false,
    val isSELinuxEnforcing: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class AndroidSystemInfo(
    val buildId: String = "",
    val buildType: String = "",
    val buildTags: String = "",
    val productName: String = "",
    val deviceName: String = "",
    val manufacturer: String = "",
    val brand: String = "",
    val cpuAbi: String = "",
    val totalMemory: Long = 0L,
    val totalStorage: Long = 0L,
    val uptime: String = ""
)

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val cpuUsage: Float = 0f,
    val memoryUsage: Long = 0L
)

data class StorageInfo(
    val internalTotal: Long = 0L,
    val internalUsed: Long = 0L,
    val externalTotal: Long = 0L,
    val externalUsed: Long = 0L
)

data class NetworkInfo(
    val wifiConnected: Boolean = false,
    val mobileConnected: Boolean = false,
    val wifiSsid: String = "",
    val ipAddress: String = ""
)

class TerminalContextCollector(private val context: Context) {

    private var cachedContext: TerminalContext? = null
    private var lastCollectTime: Long = 0
    private val cacheValidityMs: Long = 5000

    companion object {
        /**
         * PERF-25: 缓存 android.os.SystemProperties.get(String, String) 的 Method 引用。
         * 该方法每次调用读取 ro.* 属性只需一次 binder 调用（且系统内部有缓存），
         * 相比 spawn `getprop` 子进程（fork+exec）节省 ~5-15ms/次 × 10 次。
         * SystemProperties 是 hidden API，反射访问在所有 Android 版本上稳定可用。
         */
        private val systemPropertiesGet: java.lang.reflect.Method? by lazy {
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                clazz.getMethod("get", String::class.java, String::class.java)
            } catch (e: Throwable) {
                null
            }
        }
    }

    // J-14: Root 可用性在会话生命周期内不会变化,缓存首次结果不再每 5s spawn `su -c id`
    // (spawn su 成本高 + 频繁弹授权框)。null = 尚未计算。
    @Volatile
    private var rootAvailabilityCached: Boolean? = null

    /**
     * J-14: 会话级缓存的 root 可用性检查。
     * 首次调用实际执行 `su -c id`,之后直接返回缓存值。
     */
    private fun isRootAvailable(): Boolean {
        rootAvailabilityCached?.let { return it }
        val result = checkRootAccess()
        rootAvailabilityCached = result
        return result
    }

    suspend fun collectContext(
        sessionId: String? = null,
        forceRefresh: Boolean = false,
        useCache: Boolean = true
    ): TerminalContext = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        val cached = cachedContext
        if (useCache && !forceRefresh && cached != null && (now - lastCollectTime) < cacheValidityMs) {
            return@withContext cached
        }

        val terminalManager = TerminalManager.getInstance(context)

        val currentDir = sessionId?.let {
            terminalManager.getCurrentDirectory(it)
        } ?: getCurrentWorkingDirectory()

        val envVars = getEnvironmentVariables()
        val recentCmds = sessionId?.let {
            terminalManager.getRecentCommands(it)
        } ?: getRecentCommandsFromHistory()

        val availableShells = detectAvailableShells()
        val androidInfo = collectAndroidSystemInfo()
        val storageInfo = collectStorageInfo()
        val networkInfo = collectNetworkInfo()
        val isRoot = isRootAvailable()
        val selinux = checkSELinuxStatus()

        val newContext = TerminalContext(
            currentDirectory = currentDir,
            environmentVariables = envVars,
            recentCommands = recentCmds,
            availableShells = availableShells,
            androidInfo = androidInfo,
            runningProcesses = emptyList(),
            storageInfo = storageInfo,
            networkInfo = networkInfo,
            deviceModel = androidInfo.productName,
            androidVersion = androidInfo.buildType,
            sdkVersion = getAndroidSdkVersion(),
            isRootAvailable = isRoot,
            isSELinuxEnforcing = selinux,
            timestamp = now
        )

        cachedContext = newContext
        lastCollectTime = now

        return@withContext newContext
    }

    private fun getCurrentWorkingDirectory(): String {
        return try {
            val process = Runtime.getRuntime().exec("pwd")
            BufferedReader(InputStreamReader(process.inputStream)).readLine() ?: "/"
        } catch (e: Exception) {
            "/"
        }
    }

    private fun getEnvironmentVariables(): Map<String, String> {
        return try {
            System.getenv()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getRecentCommandsFromHistory(): List<String> {
        return try {
            val historyFile = File(System.getProperty("user.home"), ".bash_history")
            if (historyFile.exists()) {
                historyFile.readLines().takeLast(20).filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun detectAvailableShells(): List<String> {
        val shells = listOf("/system/bin/sh", "/system/bin/bash", "/system/bin/zsh", "/system/bin/fish")
        return shells.filter { File(it).exists() }
    }

    private fun collectAndroidSystemInfo(): AndroidSystemInfo {
        return try {
            val props = mapOf(
                "ro.build.id" to getSystemProperty("ro.build.id"),
                "ro.build.type" to getSystemProperty("ro.build.type"),
                "ro.build.tags" to getSystemProperty("ro.build.tags"),
                "ro.product.name" to getSystemProperty("ro.product.name"),
                "ro.product.device" to getSystemProperty("ro.product.device"),
                "ro.product.manufacturer" to getSystemProperty("ro.product.manufacturer"),
                "ro.product.brand" to getSystemProperty("ro.product.brand"),
                "ro.product.cpu.abi" to getSystemProperty("ro.product.cpu.abi"),
                "ro.build.version.release" to getSystemProperty("ro.build.version.release"),
                "ro.build.version.sdk" to getSystemProperty("ro.build.version.sdk")
            )

            val memInfo = File("/proc/meminfo").readLines()
            val totalMemory = memInfo.find { it.startsWith("MemTotal:") }
                ?.replace(Regex("[^0-9]"), "")
                ?.toLongOrNull()
                ?.times(1024) ?: 0L

            val uptime = try {
                val uptimeLines = File("/proc/uptime").readLines()
                if (uptimeLines.isNotEmpty()) {
                    val uptimeSeconds = uptimeLines[0].split(" ")[0].toDoubleOrNull() ?: 0.0
                    formatUptime(uptimeSeconds)
                } else ""
            } catch (e: Exception) {
                ""
            }

            AndroidSystemInfo(
                buildId = props["ro.build.id"] ?: "",
                buildType = props["ro.build.type"] ?: "",
                buildTags = props["ro.build.tags"] ?: "",
                productName = props["ro.product.name"] ?: "",
                deviceName = props["ro.product.device"] ?: "",
                manufacturer = props["ro.product.manufacturer"] ?: "",
                brand = props["ro.product.brand"] ?: "",
                cpuAbi = props["ro.product.cpu.abi"] ?: "",
                totalMemory = totalMemory,
                uptime = uptime
            )
        } catch (e: Exception) {
            AndroidSystemInfo()
        }
    }

    /**
     * PERF-25: 通过反射调用 android.os.SystemProperties.get(key, def) 读取系统属性，
     * 避免每次 spawn `getprop` 子进程（fork+exec ~5-15ms/次）。
     * Method 引用在 [companion object] 中缓存，仅首次反射一次。
     * 反射失败（极少数 ROM 限制）时返回空串，行为与原 getprop 失败一致。
     */
    private fun getSystemProperty(prop: String): String {
        return try {
            systemPropertiesGet?.invoke(null, prop, "") as? String ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    private fun getAndroidSdkVersion(): Int {
        return try {
            getSystemProperty("ro.build.version.sdk").toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun formatUptime(seconds: Double): String {
        val days = (seconds / 86400).toInt()
        val hours = ((seconds % 86400) / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
            append("${secs}s")
        }.trim()
    }

    private fun collectStorageInfo(): StorageInfo {
        return try {
            val internalPath = File("/data")
            val statFs = android.os.StatFs(internalPath.path)
            val blockSize = statFs.blockSizeLong
            val totalBlocks = statFs.blockCountLong
            val availableBlocks = statFs.availableBlocksLong

            StorageInfo(
                internalTotal = totalBlocks * blockSize,
                internalUsed = (totalBlocks - availableBlocks) * blockSize
            )
        } catch (e: Exception) {
            StorageInfo()
        }
    }

    private fun collectNetworkInfo(): NetworkInfo {
        return try {
            val wifiConnected = File("/sys/class/net/wlan0").exists()
            val mobileConnected = File("/sys/class/net/ppp0").exists()

            var ipAddress = ""
            try {
                val process = Runtime.getRuntime().exec(arrayOf("ifconfig", "wlan0"))
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val ipMatch = Regex("inet addr:(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(output)
                ipAddress = ipMatch?.groupValues?.get(1) ?: ""
            } catch (e: Exception) {
            }

            NetworkInfo(
                wifiConnected = wifiConnected,
                mobileConnected = mobileConnected,
                ipAddress = ipAddress
            )
        } catch (e: Exception) {
            NetworkInfo()
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * PERF-25: 用 SystemProperties 读取 SELinux 状态，避免 spawn `getenforce` 子进程。
     * ro.boot.selinux 反映启动时的 enforcing 策略；运行时 setenforce 切换不会被反映，
     * 但终端上下文采集只需要“设备出厂策略”这一近似值即可，准确度足够。
     */
    private fun checkSELinuxStatus(): Boolean {
        val v = getSystemProperty("ro.boot.selinux")
        return v.trim().equals("enforcing", ignoreCase = true)
    }

    fun buildContextPrompt(context: TerminalContext, includeHistory: Boolean = true): String {
        return buildString {
            appendLine("=== 终端上下文信息 ===")
            appendLine()
            appendLine("【设备信息】")
            appendLine("设备型号: ${context.deviceModel}")
            appendLine("Android版本: ${context.androidVersion}")
            appendLine("SDK版本: ${context.sdkVersion}")
            appendLine("制造商: ${context.androidInfo.manufacturer}")
            appendLine("品牌: ${context.androidInfo.brand}")
            appendLine("CPU架构: ${context.androidInfo.cpuAbi}")
            appendLine()

            appendLine("【当前状态】")
            appendLine("当前目录: ${context.currentDirectory}")
            appendLine("Root权限: ${if (context.isRootAvailable) "可用" else "不可用"}")
            appendLine("SELinux: ${if (context.isSELinuxEnforcing) "Enforcing" else "Permissive"}")
            appendLine("可用Shell: ${context.availableShells.joinToString(", ").ifEmpty { "sh" }}")
            appendLine()

            if (context.environmentVariables.isNotEmpty()) {
                appendLine("【环境变量】")
                val importantVars = listOf("PATH", "HOME", "USER", "ANDROID_DATA", "ANDROID_ROOT")
                importantVars.forEach { varName ->
                    context.environmentVariables[varName]?.let { value ->
                        appendLine("$varName=$value")
                    }
                }
                appendLine()
            }

            appendLine("【存储信息】")
            appendLine("内部存储: ${formatBytes(context.storageInfo.internalUsed)} / ${formatBytes(context.storageInfo.internalTotal)}")
            if (context.storageInfo.externalTotal > 0) {
                appendLine("外部存储: ${formatBytes(context.storageInfo.externalUsed)} / ${formatBytes(context.storageInfo.externalTotal)}")
            }
            appendLine()

            appendLine("【内存信息】")
            appendLine("总内存: ${formatBytes(context.androidInfo.totalMemory)}")
            appendLine("运行时间: ${context.androidInfo.uptime}")
            appendLine()

            appendLine("【网络状态】")
            appendLine("WiFi: ${if (context.networkInfo.wifiConnected) "已连接 (${context.networkInfo.ipAddress})" else "未连接"}")
            appendLine("移动数据: ${if (context.networkInfo.mobileConnected) "已连接" else "未连接"}")
            appendLine()

            if (includeHistory && context.recentCommands.isNotEmpty()) {
                appendLine("【最近命令】")
                context.recentCommands.takeLast(10).forEachIndexed { index, cmd ->
                    appendLine("${index + 1}. $cmd")
                }
                appendLine()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return String.format("%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }
}