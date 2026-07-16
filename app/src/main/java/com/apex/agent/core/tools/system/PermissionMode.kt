package com.apex.agent.core.tools.system

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 权限模式枚举 - 定义系统支持的所有权限模?
 */
enum class PermissionMode(
    val id: String,
    val displayName: String,
    val description: String,
    val level: Int,
    val requiresRoot: Boolean = false,
    val requiresShizuku: Boolean = false,
    val requiresAccessibility: Boolean = false,
    val requiresAdmin: Boolean = false
) {
    STANDARD(
        id = "standard",
        displayName = "标准模式",
        description = "使用普通应用权限，无需特殊授权",
        level = 0
    ),
    
    ACCESSIBILITY(
        id = "accessibility",
        displayName = "无障碍模?,
        description = "使用无障碍服务权限，提供更强大的自动化能?,
        level = 1,
        requiresAccessibility = true
    ),
    
    DEBUGGER(
        id = "debugger",
        displayName = "调试模式",
        description = "使用调试桥接权限，适合开发和测试场景",
        level = 2
    ),
    
    ADMIN(
        id = "admin",
        displayName = "管理员模?,
        description = "使用设备管理员权限，提供系统级控制能?,
        level = 3,
        requiresAdmin = true
    ),
    
    SHIZUKU(
        id = "shizuku",
        displayName = "Shizuku模式",
        description = "使用 Shizuku/Sui 服务，无需 Root 即可获得系统级权?,
        level = 4,
        requiresShizuku = true
    ),
    
    ROOT(
        id = "root",
        displayName = "Root模式",
        description = "使用完全 Root 权限，提供最高级别的系统控制能力",
        level = 5,
        requiresRoot = true
    );

    companion object {
        fun fromId(id: String): PermissionMode =
            values().find { it.id == id } ?: STANDARD

        fun fromLevel(level: Int): PermissionMode =
            values().find { it.level == level } ?: STANDARD

        fun sortedByLevel(): List<PermissionMode> = values().sortedBy { it.level }

        fun sortedByLevelDesc(): List<PermissionMode> = values().sortedByDescending { it.level }
    }

    fun isHigherOrEqualThan(other: PermissionMode): Boolean = this.level >= other.level

    fun isHigherThan(other: PermissionMode): Boolean = this.level > other.level
}

/**
 * 权限模式状?- 包含模式的检测状?
 */
@Parcelize
data class PermissionModeState(
    val mode: PermissionMode,
    val isAvailable: Boolean = false,
    val isGranted: Boolean = false,
    val isPreferred: Boolean = false,
    val checkTimestamp: Long = 0L,
    val errorMessage: String? = null
) : Parcelable {
    val isUsable: Boolean get() = isAvailable && isGranted
}

/**
 * Root 执行模式 - 用于 Root 命令执行方式
 */
enum class RootExecutionMode(
    val id: String,
    val displayName: String,
    val description: String
) {
    AUTO(
        id = "auto",
        displayName = "自动模式",
        description = "自动选择最佳的 Root 执行方式"
    ),
    
    FORCE_LIBSU(
        id = "force_libsu",
        displayName = "Libsu模式",
        description = "强制使用 Libsu 库执?Root 命令"
    ),
    
    FORCE_EXEC(
        id = "force_exec",
        displayName = "Exec模式",
        description = "强制使用 Runtime.exec() 执行 Root 命令"
    ),
    
    FORCE_KERNELSU(
        id = "force_kernelsu",
        displayName = "KernelSU模式",
        description = "强制使用 KernelSU 方式执行命令"
    );

    companion object {
        fun fromId(id: String): RootExecutionMode =
            values().find { it.id == id } ?: AUTO

        fun fromLegacyMode(mode: com.apex.agent.data.preferences.RootCommandExecutionMode): RootExecutionMode =
            when (mode) {
                com.apex.agent.data.preferences.RootCommandExecutionMode.AUTO -> AUTO
                com.apex.agent.data.preferences.RootCommandExecutionMode.FORCE_LIBSU -> FORCE_LIBSU
                com.apex.agent.data.preferences.RootCommandExecutionMode.FORCE_EXEC -> FORCE_EXEC
            }
    }

    fun toLegacyMode(): com.apex.agent.data.preferences.RootCommandExecutionMode =
        when (this) {
            AUTO -> com.apex.agent.data.preferences.RootCommandExecutionMode.AUTO
            FORCE_LIBSU -> com.apex.agent.data.preferences.RootCommandExecutionMode.FORCE_LIBSU
            FORCE_EXEC -> com.apex.agent.data.preferences.RootCommandExecutionMode.FORCE_EXEC
            FORCE_KERNELSU -> com.apex.agent.data.preferences.RootCommandExecutionMode.FORCE_EXEC
        }
}

/**
 * Root 检测结?- 包含 Root 方案的详细信?
 */
@Parcelize
data class RootDetectionResult(
    val isRooted: Boolean = false,
    val hasRootAccess: Boolean = false,
    val rootScheme: RootScheme = RootScheme.UNKNOWN,
    val suPath: String? = null,
    val suVersion: String? = null,
    val seLinuxStatus: SELinuxStatus = SELinuxStatus.UNKNOWN,
    val detectionTimestamp: Long = 0L,
    val errorMessage: String? = null
) : Parcelable

/**
 * Root 方案类型
 */
enum class RootScheme(
    val displayName: String,
    val packageName: String? = null
) {
    UNKNOWN("未知方案"),
    MAGISK("Magisk", "com.topjohnwu.magisk"),
    KERNELSU("KernelSU", "me.weishu.kernelsu"),
    APATCH("APatch", "me.bmax.apatch"),
    SUPERSU("SuperSU", "eu.chainfire.supersu"),
    KINGROOT("KingRoot", "com.kingroot.kinguser"),
    KINGOUSER("KingoUser", "com.kingouser.com"),
    VROOT("Vroot", "com.mgyun.shua.su"),
    FRAMAROOT("Framaroot", "com.alephzain.framaroot"),
    BAIDU_ROOT("百度ROOT", "com.baidu.easyroot"),
    QIHOO_ROOT("360ROOT", "com.qihoo.permmgr"),
    IROOT("iRoot", "com.shuame.rootgenius"),
    GENIUS_ROOT("刷机精灵", "com.shuame.rootgenius"),
    Z4ROOT("Z4Root", "com.z4root.z4root"),
    TOWELROOT("TowelRoot", "geohot.towelroot"),
    CF_AUTO_ROOT("CF-Auto-Root"),
    LINEAGE_SU("LineageOS SU", "org.lineageos.su"),
    SUI("Sui (Shizuku)", "rikka.sui"),
    OTHER("其他Root方案");

    companion object {
        fun fromPackageName(packageName: String): RootScheme =
            values().find { it.packageName == packageName } ?: OTHER
    }
}

/**
 * SELinux 状?
 */
enum class SELinuxStatus(val displayName: String) {
    ENFORCING("强制模式"),
    PERMISSIVE("宽容模式"),
    DISABLED("已禁?),
    UNKNOWN("未知状?);

    companion object {
        fun fromString(status: String): SELinuxStatus =
            when (status?.lowercase()) {
                "enforcing" -> ENFORCING
                "permissive" -> PERMISSIVE
                "disabled" -> DISABLED
                else -> UNKNOWN
            }
    }
}

/**
 * Shizuku 检测结?
 */
@Parcelize
data class ShizukuDetectionResult(
    val isAvailable: Boolean = false,
    val isGranted: Boolean = false,
    val isSuiBackend: Boolean = false,
    val isShizukuInstalled: Boolean = false,
    val shizukuPackageName: String? = null,
    val shizukuVersion: String? = null,
    val uid: Int = -1,
    val detectionTimestamp: Long = 0L,
    val errorMessage: String? = null
) : Parcelable {
    val isUsable: Boolean get() = isAvailable && isGranted
}
