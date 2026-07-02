package com.ai.assistance.aiterminal.terminal.agent

import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private const val TAG = "SystemProbe"

/**
 * 系统探测模块 - 静默执行预设命令，获取手机核心信息
 */
class SystemProbe(
    private val rootTerminalManager: RootTerminalManager
) {
    private var cachedData: SystemProbeData? = null
    private var lastProbeTime: Long = 0
    private val cacheValidityMs = 60000L // 缓存 60 秒

    /**
     * 获取系统探测数据（支持缓存）
     */
    suspend fun probeSystem(forceRefresh: Boolean = false): ProbeResult<SystemProbeData> {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            
            if (!forceRefresh && cachedData != null && now - lastProbeTime < cacheValidityMs) {
                Log.d(TAG, "Returning cached probe data")
                return@withContext ProbeResult.Success(cachedData!!)
            }

            try {
                Log.i(TAG, "Starting system probe...")
                
                val hasRoot = rootTerminalManager.checkRootAccess()
                
                // 并行执行探测任务
                val systemInfoDeferred = async { probeSystemInfo(hasRoot) }
                val hardwareStateDeferred = async { probeHardwareState(hasRoot) }
                val softwareStateDeferred = async { probeSoftwareState(hasRoot) }
                
                val results = awaitAll(
                    systemInfoDeferred,
                    hardwareStateDeferred,
                    softwareStateDeferred
                )
                val systemInfo = results[0] as SystemInfo
                val hardwareState = results[1] as HardwareState
                val softwareState = results[2] as SoftwareState
                
                val probeData = SystemProbeData(
                    systemInfo = systemInfo,
                    hardwareState = hardwareState,
                    softwareState = softwareState
                )
                
                // 缓存结果
                cachedData = probeData
                lastProbeTime = now
                
                Log.i(TAG, "System probe completed successfully")
                ProbeResult.Success(probeData)
                
            } catch (e: Exception) {
                Log.e(TAG, "System probe failed", e)
                ProbeResult.Error("System probe failed: ${e.message}", e)
            }
        }
    }

    /**
     * 探测系统信息
     */
    private suspend fun probeSystemInfo(hasRoot: Boolean): SystemInfo {
        return SystemInfo(
            androidVersion = Build.VERSION.RELEASE,
            kernelVersion = getKernelVersion(),
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            rootScheme = detectRootScheme(hasRoot),
            selinuxStatus = detectSELinuxStatus(),
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.SECURITY_PATCH
            } else null,
            buildId = Build.DISPLAY
        )
    }

    /**
     * 获取内核版本
     */
    private fun getKernelVersion(): String? {
        return try {
            val process = Runtime.getRuntime().exec("uname -r")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine()?.trim().also {
                process.waitFor()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get kernel version", e)
            null
        }
    }

    /**
     * 检测 Root 方案
     */
    private suspend fun detectRootScheme(hasRoot: Boolean): RootScheme {
        if (!hasRoot) return RootScheme.NONE
        
        return try {
            // 检查 Magisk
            val magiskResult = executeCommand("magisk -v")
            if (magiskResult.first == 0 && !magiskResult.second.isNullOrEmpty()) {
                return RootScheme.MAGISK
            }
            
            // 检查 KernelSU
            val ksuResult = executeCommand("ksud --version")
            if (ksuResult.first == 0 && !ksuResult.second.isNullOrEmpty()) {
                return RootScheme.KERNELSU
            }
            
            // 检查 Magisk 路径
            val magiskPath = File("/data/adb/magisk")
            if (magiskPath.exists()) {
                return RootScheme.MAGISK
            }
            
            // 检查 KernelSU 路径
            val ksuPath = File("/data/adb/ksu")
            if (ksuPath.exists()) {
                return RootScheme.KERNELSU
            }
            
            // 检查 SuperSU
            val supersuResult = executeCommand("su --version")
            if (supersuResult.first == 0 && supersuResult.second?.contains("SUPERSU") == true) {
                return RootScheme.SUPERSU
            }
            
            RootScheme.UNKNOWN
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect root scheme", e)
            RootScheme.UNKNOWN
        }
    }

    /**
     * 检测 SELinux 状态
     */
    private fun detectSELinuxStatus(): SELinuxStatus {
        return try {
            val process = Runtime.getRuntime().exec("getenforce")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val status = reader.readLine()?.trim()?.uppercase()
            process.waitFor()
            
            when (status) {
                "ENFORCING" -> SELinuxStatus.ENFORCING
                "PERMISSIVE" -> SELinuxStatus.PERMISSIVE
                "DISABLED" -> SELinuxStatus.DISABLED
                else -> SELinuxStatus.UNKNOWN
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect SELinux status", e)
            SELinuxStatus.UNKNOWN
        }
    }

    /**
     * 探测硬件状态
     */
    private suspend fun probeHardwareState(hasRoot: Boolean): HardwareState {
        return HardwareState(
            cpuArchitecture = getCpuArchitecture(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuFrequency = getCpuFrequency(),
            cpuGovernor = getCpuGovernor(),
            gpuInfo = getGpuInfo(),
            totalMemory = getTotalMemory(),
            availableMemory = getAvailableMemory(),
            usedMemory = getTotalMemory()?.minus(getAvailableMemory() ?: 0L),
            totalStorage = getTotalStorage(),
            availableStorage = getAvailableStorage(),
            batteryLevel = getBatteryLevel(),
            batteryHealth = getBatteryHealth(),
            wakeLocks = if (hasRoot) getWakeLocks() else emptyList()
        )
    }

    /**
     * 获取 CPU 架构
     */
    private fun getCpuArchitecture(): String? {
        return try {
            val process = Runtime.getRuntime().exec("uname -m")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine()?.trim().also {
                process.waitFor()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get CPU architecture", e)
            null
        }
    }

    /**
     * 获取 CPU 频率
     */
    private fun getCpuFrequency(): String? {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val frequencyPattern = Regex("""cpu MHz\s+:\s+([\d.]+)""")
            val matches = frequencyPattern.findAll(cpuInfo).map { it.groupValues[1] }.toList()
            
            if (matches.isNotEmpty()) {
                val avg = matches.map { it.toDouble() }.average()
                "%.2f MHz".format(avg)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get CPU frequency", e)
            null
        }
    }

    /**
     * 获取 CPU 调度器
     */
    private fun getCpuGovernor(): String? {
        return try {
            val governorFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            if (governorFile.exists()) {
                governorFile.readText().trim()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get CPU governor", e)
            null
        }
    }

    /**
     * 获取 GPU 信息
     */
    private fun getGpuInfo(): String? {
        // 简单实现，实际可以通过 OpenGL 获取
        return try {
            val process = Runtime.getRuntime().exec("dumpsys SurfaceFlinger --display-id 0")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("GL_VERSION") == true) {
                    return line
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get GPU info", e)
            null
        }
    }

    /**
     * 获取总内存
     */
    private fun getTotalMemory(): Long? {
        return try {
            val memInfo = File("/proc/meminfo").readText()
            val pattern = Regex("""MemTotal:\s+(\d+)\s+kB""")
            pattern.find(memInfo)?.groupValues?.get(1)?.toLongOrNull()?.times(1024)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get total memory", e)
            null
        }
    }

    /**
     * 获取可用内存
     */
    private fun getAvailableMemory(): Long? {
        return try {
            val memInfo = File("/proc/meminfo").readText()
            val pattern = Regex("""MemAvailable:\s+(\d+)\s+kB""")
            pattern.find(memInfo)?.groupValues?.get(1)?.toLongOrNull()?.times(1024)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get available memory", e)
            null
        }
    }

    /**
     * 获取总存储
     */
    private fun getTotalStorage(): Long? {
        return try {
            val statFs = StatFs(Environment.getDataDirectory().path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                statFs.blockSizeLong * statFs.blockCountLong
            } else {
                @Suppress("DEPRECATION")
                statFs.blockSize.toLong() * statFs.blockCount.toLong()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get total storage", e)
            null
        }
    }

    /**
     * 获取可用存储
     */
    private fun getAvailableStorage(): Long? {
        return try {
            val statFs = StatFs(Environment.getDataDirectory().path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                statFs.blockSizeLong * statFs.availableBlocksLong
            } else {
                @Suppress("DEPRECATION")
                statFs.blockSize.toLong() * statFs.availableBlocks.toLong()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get available storage", e)
            null
        }
    }

    /**
     * 获取电池电量
     */
    private fun getBatteryLevel(): Int? {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys battery")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("level:") == true) {
                    return line?.substringAfter("level:")?.trim()?.toIntOrNull()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery level", e)
            null
        }
    }

    /**
     * 获取电池健康状态
     */
    private fun getBatteryHealth(): String? {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys battery")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("health:") == true) {
                    return line?.substringAfter("health:")?.trim()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery health", e)
            null
        }
    }

    /**
     * 获取唤醒锁（需要 Root）
     */
    private suspend fun getWakeLocks(): List<WakeLockInfo> {
        return try {
            val (exitCode, output) = executeCommand("dumpsys power | grep -i wakelock")
            if (exitCode != 0 || output.isNullOrEmpty()) {
                return emptyList()
            }
            
            // 简单解析，实际需要更复杂的逻辑
            val locks = mutableListOf<WakeLockInfo>()
            val lines = output.lines()
            
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && trimmed.contains("WakeLock")) {
                    locks.add(WakeLockInfo(
                        name = trimmed,
                        type = "unknown"
                    ))
                }
            }
            
            locks.take(50)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get wake locks", e)
            emptyList()
        }
    }

    /**
     * 探测软件状态
     */
    private suspend fun probeSoftwareState(hasRoot: Boolean): SoftwareState {
        return SoftwareState(
            installedApps = emptyList(), // 可通过 PackageManager 获取
            runningProcesses = getRunningProcesses(),
            mountedPartitions = getMountedPartitions(),
            systemApps = emptyList()
        )
    }

    /**
     * 获取运行中进程
     */
    private fun getRunningProcesses(): List<ProcessInfo> {
        return try {
            val processes = mutableListOf<ProcessInfo>()
            val procDir = File("/proc")
            
            procDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.toIntOrNull() != null) {
                    val pid = dir.name.toInt()
                    val cmdline = File(dir, "cmdline").takeIf { it.exists() }?.readText()?.trim()
                    if (!cmdline.isNullOrEmpty()) {
                        processes.add(ProcessInfo(
                            pid = pid,
                            name = cmdline
                        ))
                    }
                }
            }
            
            processes.sortedByDescending { it.pid }.take(100)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get running processes", e)
            emptyList()
        }
    }

    /**
     * 获取挂载分区
     */
    private fun getMountedPartitions(): List<PartitionInfo> {
        return try {
            val mounts = File("/proc/mounts").readText()
            val partitions = mutableListOf<PartitionInfo>()
            
            mounts.lines().forEach { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 3) {
                    partitions.add(PartitionInfo(
                        device = parts.getOrNull(0),
                        mountPoint = parts.getOrNull(1) ?: "",
                        fsType = parts.getOrNull(2) ?: ""
                    ))
                }
            }
            
            partitions
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get mounted partitions", e)
            emptyList()
        }
    }

    /**
     * 执行命令并返回结果
     */
    private suspend fun executeCommand(command: String): Pair<Int, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(command)
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val exitCode = process.waitFor()
                exitCode to output
            } catch (e: Exception) {
                Log.w(TAG, "Command failed: $command", e)
                -1 to null
            }
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cachedData = null
        lastProbeTime = 0
    }
}
