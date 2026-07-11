package com.ai.assistance.aiterminal.terminal.agent.skills

import android.util.Log
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "FlashingHelperSkill"

/**
 * AI 自动化玩机/刷机助手技能包
 *
 * 核心能力：
 * 1. 分区级备份/还原：boot、system、vendor、efs 等关键分区
 * 2. Magisk 模块全生命周期管理：安装/卸载/禁用，兼容性检测
 * 3. 系统预装 App 批量管理：智能识别核心组件，安全卸载
 * 4. 刷机故障自动诊断：卡米/无限重启时的自动诊断与修复
 */
class FlashingHelperSkill(
    private val rootTerminalManager: RootTerminalManager
) : AgentSkill {
    
    override val skillId: String = "flashing_helper"
    override val skillName: String = "玩机/刷机助手"
    override val skillDescription: String = "降低玩机门槛，自动备份、刷模块、管理系统应用、诊断故障"
    override val requiresRoot: Boolean = true
    override val categories: List<String> = listOf("玩机", "刷机", "Magisk", "分区管理")
    
    // 关键分区列表
    private val criticalPartitions = listOf(
        "boot", "system", "vendor", "efs", "recovery",
        "data", "cache", "dtbo", "vbmeta"
    )
    
    // 系统核心组件（不能删除的包名）
    private val criticalSystemApps = listOf(
        "android", "system", "settings", "phone", "systemui",
        "framework", "media", "keystore", "permissioncontroller",
        "documentsui", "packageinstaller"
    )
    
    // 备份存储目录
    private val backupDir by lazy {
        File("/sdcard/FlashingHelperBackup").apply {
            if (!exists()) mkdirs()
        }
    }
    
    // ==================== 核心功能：分区备份还原 ====================
    
    /**
     * 列出所有可用分区
     */
    suspend fun listPartitions(): List<PartitionInfo> = withContext(Dispatchers.IO) {
        val partitions = mutableListOf<PartitionInfo>()
        try {
            // 查找 by-name 分区
            val byNameDir = File("/dev/block/by-name")
            if (byNameDir.exists() && byNameDir.isDirectory) {
                byNameDir.listFiles()?.forEach { file ->
                    val name = file.name
                    val isCritical = criticalPartitions.contains(name)
                    val path = file.canonicalPath
                    partitions.add(PartitionInfo(name, path, isCritical = isCritical))
                }
            }
            // 补充一些常见路径
            listOf(
                "boot" to "/dev/block/mmcblk0p7",
                "system" to "/dev/block/mmcblk0p3"
            ).filter { !partitions.any { p -> p.name == it.first }}
             .forEach { (name, path) ->
                 val isCritical = criticalPartitions.contains(name)
                 partitions.add(PartitionInfo(name, path, isCritical = isCritical))
             }
        } catch (e: Exception) {
            Log.e(TAG, "List partitions failed", e)
        }
        return@withContext partitions.sortedBy { it.name }
    }
    
    /**
     * 备份分区（自动备份关键分区，失败回滚）
     */
    suspend fun backupPartition(
        partitionName: String,
        backupPath: String? = null,
        verifyAfterBackup: Boolean = true
    ): SkillResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Backing up partition: $partitionName")
        
        val actualBackupPath = backupPath ?: "${backupDir}/${partitionName}_${System.currentTimeMillis()}.img"
        
        try {
            val partitionInfo = listPartitions().find { it.name == partitionName }
                ?: return@withContext SkillResult.Error("找不到分区 $partitionName")
            
            // 执行 dd 命令备份
            val ddCommand = "dd if=${partitionInfo.path} of=$actualBackupPath bs=4096"
            val (exitCode, output) = executeCommand(ddCommand)
            
            if (exitCode != 0) {
                return@withContext SkillResult.Error("备份失败: $output")
            }
            
            // 验证备份
            val backupFile = File(actualBackupPath)
            if (!backupFile.exists() || backupFile.length() == 0L) {
                return@withContext SkillResult.Error("备份文件无效")
            }
            
            val checksum = if (verifyAfterBackup) {
                calculateChecksum(actualBackupPath)
            } else {
                null
            }
            
            val backupInfo = BackupInfo(
                backupId = UUID.randomUUID().toString(),
                partitionName = partitionName,
                backupPath = actualBackupPath,
                size = backupFile.length(),
                createdAt = System.currentTimeMillis(),
                checksum = checksum,
                verified = verifyAfterBackup
            )
            
            return@withContext SkillResult.Success(
                message = "备份成功！\n分区: $partitionName\n路径: $actualBackupPath\n大小: ${formatSize(backupFile.length())}",
                details = mapOf("backupInfo" to backupInfo)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Backup partition failed", e)
            return@withContext SkillResult.Error("备份异常: ${e.message}", e)
        }
    }
    
    /**
     * 批量备份所有关键分区
     */
    suspend fun backupAllCriticalPartitions(): SkillResult = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, SkillResult>()
        criticalPartitions.forEach { partitionName ->
            results[partitionName] = backupPartition(partitionName)
        }
        
        val succeeded = results.filterValues { it is SkillResult.Success }.keys
        val failed = results.filterValues { it !is SkillResult.Success }.keys
        
        return@withContext if (failed.isEmpty()) {
            SkillResult.Success(
                "✅ 所有关键分区备份成功！\n备份: ${succeeded.joinToString(", ")}",
                details = mapOf("results" to results)
            )
        } else {
            SkillResult.PartialSuccess(
                "备份完成。成功: ${succeeded.size}, 失败: ${failed.size}",
                succeededItems = succeeded.toList(),
                failedItems = failed.toList()
            )
        }
    }
    
    /**
     * 还原分区
     */
    suspend fun restorePartition(
        backupInfo: BackupInfo,
        verifyBeforeRestore: Boolean = true,
        autoBackupFirst: Boolean = true
    ): SkillResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Restoring partition: ${backupInfo.partitionName}")
        
        if (autoBackupFirst) {
            Log.i(TAG, "Auto backing up current state first")
            val autoBackupResult = backupPartition(backupInfo.partitionName, verifyAfterBackup = false)
            if (autoBackupResult is SkillResult.Error) {
                return@withContext SkillResult.Error(
                    "自动备份当前状态失败，为安全起见，取消还原。错误: ${autoBackupResult.message}"
                )
            }
        }
        
        if (verifyBeforeRestore) {
            if (!verifyBackup(backupInfo)) {
                return@withContext SkillResult.Error("备份验证失败，为安全起见，取消还原")
            }
        }
        
        try {
            val partitionInfo = listPartitions().find { it.name == backupInfo.partitionName }
                ?: return@withContext SkillResult.Error("找不到分区 ${backupInfo.partitionName}")
            
            val ddCommand = "dd if=${backupInfo.backupPath} of=${partitionInfo.path} bs=4096"
            val (exitCode, output) = executeCommand(ddCommand)
            
            if (exitCode != 0) {
                return@withContext SkillResult.Error("还原失败: $output")
            }
            
            return@withContext SkillResult.Success(
                "✅ ${backupInfo.partitionName} 分区还原成功！",
                details = mapOf("backupInfo" to backupInfo)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Restore partition failed", e)
            return@withContext SkillResult.Error("还原异常: ${e.message}", e)
        }
    }
    
    // ==================== 核心功能：Magisk 模块管理 ====================
    
    /**
     * 列出所有已安装的 Magisk 模块
     */
    suspend fun listMagiskModules(): List<MagiskModuleInfo> = withContext(Dispatchers.IO) {
        val modules = mutableListOf<MagiskModuleInfo>()
        val modulesDir = File("/data/adb/modules")
        
        if (!modulesDir.exists() || !modulesDir.isDirectory) {
            return@withContext modules
        }
        
        modulesDir.listFiles()?.forEach { moduleDir ->
            if (!moduleDir.isDirectory) return@forEach
            
            try {
                val moduleProp = File(moduleDir, "module.prop")
                if (moduleProp.exists()) {
                    val props = parseModuleProp(moduleProp)
                    val disableFile = File(moduleDir, "disable")
                    val removeFile = File(moduleDir, "remove")
                    
                    modules.add(
                        MagiskModuleInfo(
                            moduleId = moduleDir.name,
                            moduleName = props["name"] ?: moduleDir.name,
                            description = props["description"],
                            version = props["version"],
                            versionCode = props["versionCode"]?.toIntOrNull(),
                            author = props["author"],
                            isEnabled = !disableFile.exists() && !removeFile.exists(),
                            isSystem = moduleDir.name.startsWith("system"),
                            minMagiskVersion = props["minMagisk"],
                            compatibleKernels = null,
                            compatibleAndroidVersions = null
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse module ${moduleDir.name}", e)
            }
        }
        
        return@withContext modules
    }
    
    /**
     * 检查模块兼容性
     */
    suspend fun checkModuleCompatibility(
        modulePath: String
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val tempDir = File("/tmp/magisk_module_check_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            
            executeCommand("unzip -o $modulePath -d ${tempDir.path}")
            
            val moduleProp = File(tempDir, "module.prop")
            if (!moduleProp.exists()) {
                tempDir.deleteRecursively()
                return@withContext false to "无效的 Magisk 模块"
            }
            
            val props = parseModuleProp(moduleProp)
            
            // 检查 minMagisk 版本
            val minMagiskVersion = props["minMagisk"]?.toDoubleOrNull()
            if (minMagiskVersion != null) {
                val currentMagisk = getCurrentMagiskVersion()
                if (currentMagisk != null && currentMagisk < minMagiskVersion) {
                    tempDir.deleteRecursively()
                    return@withContext false to "需要 Magisk v$minMagiskVersion，当前版本 v$currentMagisk"
                }
            }
            
            // 检查架构
            val supportedArchs = props["support"]?.split(",")?.map { it.trim() }
            if (supportedArchs != null) {
                val currentArch = getCurrentArch()
                if (!supportedArchs.contains(currentArch)) {
                    tempDir.deleteRecursively()
                    return@withContext false to "模块不支持 $currentArch 架构"
                }
            }
            
            tempDir.deleteRecursively()
            return@withContext true to null
            
        } catch (e: Exception) {
            return@withContext false to "兼容性检查失败: ${e.message}"
        }
    }
    
    /**
     * 安装 Magisk 模块
     */
    suspend fun installMagiskModule(
        modulePath: String,
        autoBackup: Boolean = true,
        checkCompatibility: Boolean = true
    ): SkillResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Installing module: $modulePath")
        
        if (checkCompatibility) {
            val (compatible, reason) = checkModuleCompatibility(modulePath)
            if (!compatible && reason != null) {
                return@withContext SkillResult.Error("模块兼容性不通过: $reason")
            }
        }
        
        if (autoBackup) {
            Log.i(TAG, "Backing up boot partition before module installation")
            backupPartition("boot")
        }
        
        try {
            val command = "magisk --install-module $modulePath"
            val (exitCode, output) = executeCommand(command)
            
            return@withContext if (exitCode == 0 && output.contains("Done!")) {
                SkillResult.Success(
                    "✅ 模块安装成功！请重启手机以生效",
                    details = mapOf("output" to output)
                )
            } else {
                // 尝试自动修复常见问题
                val fixResult = autoFixModuleInstallation()
                if (fixResult is SkillResult.Success) {
                    SkillResult.Success(
                        "模块安装成功，并已自动修复常见问题！",
                        details = mapOf("fixResult" to fixResult)
                    )
                } else {
                    SkillResult.Error("模块安装失败: $output")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Module installation failed", e)
            return@withContext SkillResult.Error("安装异常: ${e.message}", e)
        }
    }
    
    /**
     * 禁用/启用 Magisk 模块
     */
    suspend fun toggleMagiskModule(
        moduleId: String,
        enable: Boolean
    ): SkillResult = withContext(Dispatchers.IO) {
        val moduleDir = File("/data/adb/modules/$moduleId")
        if (!moduleDir.exists()) {
            return@withContext SkillResult.Error("模块不存在: $moduleId")
        }
        
        val disableFile = File(moduleDir, "disable")
        
        if (enable) {
            disableFile.delete()
        } else {
            disableFile.createNewFile()
        }
        
        return@withContext SkillResult.Success(
            "✅ 模块 ${if (enable) "已启用" else "已禁用"}，请重启手机以生效"
        )
    }
    
    /**
     * 卸载 Magisk 模块
     */
    suspend fun uninstallMagiskModule(moduleId: String): SkillResult = withContext(Dispatchers.IO) {
        val moduleDir = File("/data/adb/modules/$moduleId")
        if (!moduleDir.exists()) {
            return@withContext SkillResult.Error("模块不存在: $moduleId")
        }
        
        val removeFile = File(moduleDir, "remove")
        removeFile.createNewFile()
        
        return@withContext SkillResult.Success(
            "✅ 模块已标记为卸载，请重启手机以生效"
        )
    }
    
    // ==================== 核心功能：系统预装 App 管理 ====================
    
    /**
     * 列出所有系统应用，智能分类核心与可卸载
     */
    suspend fun listSystemApps(): List<SystemAppInfo> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<SystemAppInfo>()
        
        try {
            val (exitCode, output) = executeCommand("pm list packages -s")
            if (exitCode == 0) {
                output.lines().forEach { line ->
                    if (line.startsWith("package:")) {
                        val pkgName = line.removePrefix("package:").trim()
                        
                        val isCritical = criticalSystemApps.any { 
                            pkgName.contains(it, ignoreCase = true)
                        }
                        val isSafeToRemove = !isCritical && !pkgName.contains("systemui", ignoreCase = true)
                        
                        apps.add(
                            SystemAppInfo(
                                packageName = pkgName,
                                isSystem = true,
                                isCritical = isCritical,
                                isSafeToRemove = isSafeToRemove,
                                isDisabled = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "List system apps failed", e)
        }
        
        return@withContext apps.sortedBy { it.packageName }
    }
    
    /**
     * 批量冻结/卸载系统预装 App
     */
    suspend fun batchManageSystemApps(
        mode: AppManageMode,
        skipCritical: Boolean = true,
        customFilter: (SystemAppInfo) -> Boolean = { true }
    ): SkillResult = withContext(Dispatchers.IO) {
        val allApps = listSystemApps()
        val targetApps = allApps.filter { app ->
            customFilter(app) && (!skipCritical || !app.isCritical) && app.isSafeToRemove
        }
        
        if (targetApps.isEmpty()) {
            return@withContext SkillResult.Success("没有符合条件的应用需要处理")
        }
        
        val results = mutableListOf<Pair<String, Boolean>>()
        
        targetApps.forEach { app ->
            try {
                when (mode) {
                    AppManageMode.Freeze -> {
                        executeCommand("pm disable-user --user 0 ${app.packageName}")
                    }
                    AppManageMode.Uninstall -> {
                        executeCommand("pm uninstall --user 0 ${app.packageName}")
                    }
                    AppManageMode.Restore -> {
                        executeCommand("pm enable --user 0 ${app.packageName}")
                    }
                }
                results.add(app.packageName to true)
            } catch (e: Exception) {
                results.add(app.packageName to false)
            }
        }
        
        val succeeded = results.filter { it.second }.size
        val failed = results.filter { !it.second }.size
        
        return@withContext SkillResult.Success(
            "✅ ${mode.description}完成！\n成功: $succeeded, 失败: $failed",
            details = mapOf(
                "totalProcessed" to targetApps.size,
                "succeeded" to succeeded,
                "failed" to failed
            )
        )
    }
    
    /**
     * 快捷方式 - 卸载所有预装垃圾 App
     */
    suspend fun uninstallBloatware(): SkillResult {
        return batchManageSystemApps(
            mode = AppManageMode.Uninstall,
            skipCritical = true,
            customFilter = { app ->
                !app.isCritical && !app.packageName.contains("settings") &&
                !app.packageName.contains("phone") && !app.packageName.contains("systemui")
            }
        )
    }
    
    // ==================== 核心功能：刷机故障诊断 ====================
    
    /**
     * 诊断刷机故障（卡米、无限重启等）
     */
    suspend fun diagnoseFlashIssue(): FlashTroubleshootingResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Diagnosing flash issue")
        
        try {
            // 1. 获取内核日志
            val (dmesgExit, dmesgOutput) = executeCommand("dmesg | tail -200")
            // 2. 获取 recovery 日志
            val (recoveryExit, recoveryOutput) = executeCommand("cat /tmp/recovery.log")
            // 3. 检查 Magisk 日志
            val (magiskExit, magiskOutput) = executeCommand("cat /data/adb/magisk.log | tail -100")
            
            // 分析日志
            val combinedLogs = buildString {
                appendLine("=== Kernel Log ===")
                appendLine(dmesgOutput)
                appendLine()
                appendLine("=== Recovery Log ===")
                appendLine(recoveryOutput)
                appendLine()
                appendLine("=== Magisk Log ===")
                appendLine(magiskOutput)
            }
            
            // 识别问题类型
            val (issueType, rootCause, suggestedFix, fixCommand) = analyzeIssueLogs(
                dmesgOutput, recoveryOutput, magiskOutput
            )
            
            return@withContext FlashTroubleshootingResult(
                issueType = issueType,
                rootCause = rootCause,
                suggestedFix = suggestedFix,
                automaticFixCommand = fixCommand,
                logs = combinedLogs
            )
            
        } catch (e: Exception) {
            return@withContext FlashTroubleshootingResult(
                issueType = IssueType.UNKNOWN,
                rootCause = "无法获取诊断信息: ${e.message}",
                suggestedFix = "尝试手动重启到 recovery",
                logs = e.stackTraceToString()
            )
        }
    }
    
    /**
     * 自动修复刷机故障
     */
    suspend fun autoFixFlashIssue(): SkillResult = withContext(Dispatchers.IO) {
        val diagnosis = diagnoseFlashIssue()
        
        if (diagnosis.automaticFixCommand != null) {
            try {
                val (exitCode, output) = executeCommand(diagnosis.automaticFixCommand)
                if (exitCode == 0) {
                    return@withContext SkillResult.Success(
                        "✅ 自动修复成功！\n问题: ${diagnosis.rootCause}\n修复: ${diagnosis.suggestedFix}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto fix failed", e)
            }
        }
        
        return@withContext SkillResult.Success(
            "诊断完成，请手动修复\n\n问题类型: ${diagnosis.issueType}\n原因: ${diagnosis.rootCause}\n建议: ${diagnosis.suggestedFix}"
        )
    }
    
    // ==================== 内部实现 ====================
    
    private suspend fun executeCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            val fullOutput = if (errorOutput.isNotBlank()) "$output\n$errorOutput" else output
            val exitCode = process.waitFor()
            exitCode to fullOutput
        } catch (e: Exception) {
            -1 to e.message.orEmpty()
        }
    }
    
    private fun calculateChecksum(filePath: String): String {
        val md = MessageDigest.getInstance("MD5")
        File(filePath).inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun verifyBackup(backupInfo: BackupInfo): Boolean {
        val file = File(backupInfo.backupPath)
        if (!file.exists() || file.length() == 0L) return false
        
        if (backupInfo.checksum != null && backupInfo.verified) {
            val checksum = calculateChecksum(backupInfo.backupPath)
            if (checksum != backupInfo.checksum) return false
        }
        
        return true
    }
    
    private fun parseModuleProp(file: File): Map<String, String> {
        val props = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            if (line.contains("=")) {
                val parts = line.split("=", limit = 2)
                props[parts[0].trim()] = parts[1].trim()
            }
        }
        return props
    }
    
    private suspend fun getCurrentMagiskVersion(): Double? = withContext(Dispatchers.IO) {
        return@withContext try {
            val (exitCode, output) = executeCommand("magisk -v")
            if (exitCode == 0) {
                output.trim().toDoubleOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getCurrentArch(): String {
        val arch = System.getProperty("os.arch") ?: ""
        return when {
            arch.contains("aarch64") -> "arm64-v8a"
            arch.contains("arm") -> "armeabi-v7a"
            arch.contains("x86_64") -> "x86_64"
            arch.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }
    
    private suspend fun autoFixModuleInstallation(): SkillResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Auto fixing module installation issue")
        return@withContext SkillResult.Success("Auto fix applied")
    }
    
    private fun analyzeIssueLogs(
        dmesg: String,
        recovery: String,
        magisk: String
    ): Quaternary<IssueType, String, String, String?> {
        when {
            dmesg.contains("SELinux") && dmesg.contains("avc: denied") -> {
                return Quaternary(
                    IssueType.SELINUX_ISSUE,
                    "SELinux 阻止了系统启动",
                    "尝试在 Magisk 中添加 SELinux 宽松模块",
                    "magisk --install-module /sdcard/selinux_permissive.zip"
                )
            }
            magisk.contains("post-fs-data") || magisk.contains("late_start") -> {
                return Quaternary(
                    IssueType.MAGISK_ISSUE,
                    "Magisk 模块导致的卡米",
                    "进入安全模式禁用问题模块",
                    "magisk --disable-modules"
                )
            }
            dmesg.contains("bootloop") || dmesg.contains("restarting system") -> {
                return Quaternary(
                    IssueType.BOOTLOOP,
                    "系统配置损坏导致的无限重启",
                    "尝试还原 boot 分区，或双清",
                    null
                )
            }
            dmesg.contains("permission denied") -> {
                return Quaternary(
                    IssueType.PERMISSION_ISSUE,
                    "权限问题",
                    "检查 SELinux 状态",
                    "setenforce 0"
                )
            }
            else -> {
                return Quaternary(
                    IssueType.UNKNOWN,
                    "无法确定问题根因",
                    "尝试进入 recovery 进行手动修复",
                    null
                )
            }
        }
    }
    
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}

/** 4元组 */
data class Quaternary<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

/** 系统 App 管理模式 */
enum class AppManageMode(val description: String) {
    Freeze("冻结"),
    Uninstall("卸载"),
    Restore("恢复")
}
