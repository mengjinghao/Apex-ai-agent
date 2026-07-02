package com.ai.assistance.aiterminal.terminal.agent.skills

import kotlinx.parcelize.Parcelize
import android.os.Parcelable
import com.ai.assistance.aiterminal.terminal.agent.RootScheme

// ==================== 技能包通用数据模型 ====================

/**
 * 技能包基类
 */
interface AgentSkill {
    val skillId: String
    val skillName: String
    val skillDescription: String
    val requiresRoot: Boolean
    val categories: List<String>
}

/**
 * 技能执行结果
 */
sealed class SkillResult {
    data class Success(
        val message: String,
        val details: Map<String, Any> = emptyMap()
    ) : SkillResult()
    
    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : SkillResult()
    
    data class PartialSuccess(
        val message: String,
        val succeededItems: List<String>,
        val failedItems: List<String>
    ) : SkillResult()
}

/**
 * 调优前后对比数据
 */
@Parcelize
data class TuningComparison(
    val before: TuningMetrics,
    val after: TuningMetrics,
    val improvements: Map<String, Double> // 各项提升百分比
) : Parcelable

@Parcelize
data class TuningMetrics(
    val cpuGovernor: String? = null,
    val cpuMaxFreq: Long? = null,
    val cpuMinFreq: Long? = null,
    val ioScheduler: String? = null,
    val activeWakeLocks: Int? = null,
    val memoryUsagePercent: Int? = null,
    val estimatedBatterySavingPercent: Int? = null,
    val animationScale: Float? = null,
    val backgroundProcessLimit: Int? = null
) : Parcelable

// ==================== 性能/续航调优技能 ====================

/**
 * 调优场景 preset
 */
sealed class TuningPreset(
    val presetId: String,
    val presetName: String,
    val description: String
) {
    /** 极致续航，日常使用 */
    object ExtremeBattery : TuningPreset(
        "extreme_battery",
        "极致续航",
        "大幅降低功耗，限制性能，适合日常刷微信抖音"
    )
    
    /** 平衡模式 */
    object Balanced : TuningPreset(
        "balanced",
        "平衡模式",
        "在性能与续航之间平衡"
    )
    
    /** 游戏满帧，不惜耗电 */
    object GamingPerformance : TuningPreset(
        "gaming_performance",
        "游戏模式",
        "全力释放性能，帧率拉满，不惜耗电"
    )
    
    /** 性能模式 */
    object Performance : TuningPreset(
        "performance",
        "性能模式",
        "优先保证系统流畅"
    )
}

/**
 * 调优配置
 */
data class TuningConfig(
    val preset: TuningPreset? = null,
    val customCpuGovernor: String? = null,
    val customCpuMaxFreq: Long? = null,
    val customCpuMinFreq: Long? = null,
    val customIoScheduler: String? = null,
    val customAnimationScale: Float? = null,
    val customBackgroundProcessLimit: Int? = null,
    val freezeWakeLockApps: Boolean = false,
    val disableUnnecessaryServices: Boolean = false,
    val tcpCongestionAlgorithm: String? = null
)

// ==================== 玩机/刷机技能 ====================

/**
 * 分区信息
 */
@Parcelize
data class PartitionInfo(
    val name: String,
    val path: String,
    val size: Long? = null,
    val isCritical: Boolean = false, // 是否关键分区
    val isMounted: Boolean = false
) : Parcelable

/**
 * 备份信息
 */
@Parcelize
data class BackupInfo(
    val backupId: String,
    val partitionName: String,
    val backupPath: String,
    val size: Long,
    val createdAt: Long,
    val checksum: String? = null,
    val verified: Boolean = false
) : Parcelable

/**
 * Magisk 模块信息
 */
@Parcelize
data class MagiskModuleInfo(
    val moduleId: String,
    val moduleName: String,
    val description: String? = null,
    val version: String? = null,
    val versionCode: Int? = null,
    val author: String? = null,
    val isEnabled: Boolean = false,
    val isSystem: Boolean = false,
    val minMagiskVersion: String? = null,
    val compatibleKernels: List<String>? = null,
    val compatibleAndroidVersions: List<String>? = null
) : Parcelable

/**
 * 系统预装 App 信息
 */
@Parcelize
data class SystemAppInfo(
    val packageName: String,
    val appName: String? = null,
    val isSystem: Boolean = true,
    val isCritical: Boolean = false, // 是否系统核心组件（删除会变砖）
    val isSafeToRemove: Boolean = false, // 是否安全删除
    val isDisabled: Boolean = false,
    val storageSize: Long? = null
) : Parcelable

/**
 * 刷机故障诊断结果
 */
@Parcelize
data class FlashTroubleshootingResult(
    val issueType: IssueType,
    val rootCause: String,
    val suggestedFix: String,
    val automaticFixCommand: String? = null,
    val logs: String? = null
) : Parcelable

enum class IssueType {
    BOOTLOOP,
    SOFTBRICK,
    MAGISK_ISSUE,
    PERMISSION_ISSUE,
    SELINUX_ISSUE,
    UNKNOWN
}

// ==================== 新手玩机配置技能 ====================

/**
 * 玩机配置偏好
 */
enum class ConfigurationPreference(val displayName: String) {
    BATTERY_OPTIMIZED("续航优先"),
    PERFORMANCE_FOCUSED("性能优先"),
    BALANCED("平衡模式"),
    CUSTOMIZED("自定义")
}

/**
 * 核心模块信息
 */
@Parcelize
data class EssentialModuleInfo(
    val moduleName: String,
    val moduleId: String,
    val description: String,
    val required: Boolean = true, // 是否必须
    val recommended: Boolean = true, // 是否推荐
    val downloadUrl: String? = null,
    val minMagiskVersion: String? = null,
    val compatibleAndroidVersions: List<String>? = null
) : Parcelable

/**
 * 系统优化项
 */
@Parcelize
data class OptimizationItem(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val requiresRoot: Boolean = true,
    val recommended: Boolean = true
) : Parcelable

/**
 * 环境检测结果
 */
@Parcelize
data class EnvironmentDetectionResult(
    val androidVersion: String? = null,
    val kernelVersion: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val rootScheme: RootScheme? = null,
    val magiskVersion: String? = null,
    val selinuxStatus: String? = null,
    val zygiskEnabled: Boolean? = null,
    val availableModulesDir: Boolean = false
) : Parcelable

/**
 * 配置报告
 */
@Parcelize
data class SetupReport(
    val timestamp: Long,
    val environment: EnvironmentDetectionResult,
    val installedModules: List<EssentialModuleInfo>,
    val performedOptimizations: List<OptimizationItem>,
    val securityConfig: SecurityConfig,
    val warnings: List<String>,
    val notes: List<String>
) : Parcelable

/**
 * 安全配置
 */
@Parcelize
data class SecurityConfig(
    var rootHidden: Boolean = false,
    var selinuxSet: Boolean = false,
    var safetynetBypassed: Boolean = false,
    var permissionsManaged: Boolean = false,
    val additionalMeasures: List<String> = emptyList()
) : Parcelable
