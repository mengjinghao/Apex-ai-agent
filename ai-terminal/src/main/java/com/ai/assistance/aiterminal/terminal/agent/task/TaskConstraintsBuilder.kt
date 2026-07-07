package com.ai.assistance.aiterminal.terminal.agent.task

import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * 任务约束条件构建器 - 专门负责构建 WorkManager 的约束条件
 * 
 * 职责：
 * 1. 根据任务配置构建约束条件
 * 2. 处理充电、空闲、网络等约束
 * 3. 支持多种网络类型和存储条件
 */
class TaskConstraintsBuilder {
    
    /**
     * 网络类型枚举（扩展 WorkManager 的 NetworkType）
     */
    enum class NetworkRequirement {
        NOT_REQUIRED,
        ANY,
        UNMETERED,
        NOT_ROAMING,
        METERED
    }
    
    /**
     * 存储空间要求枚举
     */
    enum class StorageRequirement {
        NOT_REQUIRED,
        LOW,
        NORMAL,
        HIGH
    }
    
    /**
     * 构建约束条件（简化版本，向后兼容）
     */
    fun buildConstraints(config: ScheduledTaskConfig): Constraints {
        return buildConstraints(
            requiresCharging = config.requiresCharging,
            requiresIdle = config.requiresIdle
        )
    }
    
    /**
     * 构建约束条件（完整版本）
     */
    fun buildConstraints(
        requiresCharging: Boolean = false,
        requiresIdle: Boolean = false,
        networkRequirement: NetworkRequirement = NetworkRequirement.NOT_REQUIRED,
        storageRequirement: StorageRequirement = StorageRequirement.NOT_REQUIRED,
        requiresBatteryNotLow: Boolean = false,
        requiresStorageNotLow: Boolean = false
    ): Constraints {
        val builder = Constraints.Builder()
        
        // 充电约束
        if (requiresCharging) {
            builder.setRequiresCharging(true)
        }
        
        // 设备空闲约束
        if (requiresIdle) {
            builder.setRequiresDeviceIdle(true)
        }
        
        // 网络约束
        when (networkRequirement) {
            NetworkRequirement.ANY -> builder.setRequiredNetworkType(NetworkType.CONNECTED)
            NetworkRequirement.UNMETERED -> builder.setRequiredNetworkType(NetworkType.UNMETERED)
            NetworkRequirement.NOT_ROAMING -> builder.setRequiredNetworkType(NetworkType.NOT_ROAMING)
            NetworkRequirement.METERED -> builder.setRequiredNetworkType(NetworkType.METERED)
            NetworkRequirement.NOT_REQUIRED -> {
                // 不需要网络约束，保持默认
            }
        }
        
        // 电量约束
        if (requiresBatteryNotLow) {
            builder.setRequiresBatteryNotLow(true)
        }
        
        // 存储空间约束
        if (requiresStorageNotLow) {
            builder.setRequiresStorageNotLow(true)
        }
        
        return builder.build()
    }
    
    /**
     * 构建仅充电约束
     */
    fun buildChargingOnlyConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresCharging(true)
            .build()
    }
    
    /**
     * 构建仅空闲约束
     */
    fun buildIdleOnlyConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .build()
    }
    
    /**
     * 构建需要网络的约束（任意网络）
     */
    fun buildNetworkRequiredConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
    
    /**
     * 构建需要非漫游网络的约束
     */
    fun buildNonRoamingNetworkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_ROAMING)
            .build()
    }
    
    /**
     * 构建需要无限流量网络的约束
     */
    fun buildUnmeteredNetworkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
    }
    
    /**
     * 构建严格约束（充电 + 空闲 + 电量充足 + 存储充足）
     */
    fun buildStrictConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
    
    /**
     * 构建宽松约束（无特殊要求）
     */
    fun buildRelaxedConstraints(): Constraints {
        return Constraints.Builder().build()
    }
    
    /**
     * 从任务配置构建完整约束
     */
    fun buildFromConfig(config: ScheduledTaskConfig): Constraints {
        return buildConstraints(
            requiresCharging = config.requiresCharging,
            requiresIdle = config.requiresIdle,
            networkRequirement = mapNetworkType(config.networkType),
            storageRequirement = StorageRequirement.NOT_REQUIRED,
            requiresBatteryNotLow = config.requiresBatteryNotLow,
            requiresStorageNotLow = config.requiresStorageNotLow
        )
    }
    
    /**
     * 将任务配置的网络类型映射为内部枚举
     */
    private fun mapNetworkType(networkType: String?): NetworkRequirement {
        return when (networkType?.uppercase()) {
            "ANY", "CONNECTED" -> NetworkRequirement.ANY
            "UNMETERED" -> NetworkRequirement.UNMETERED
            "NOT_ROAMING" -> NetworkRequirement.NOT_ROAMING
            "METERED" -> NetworkRequirement.METERED
            else -> NetworkRequirement.NOT_REQUIRED
        }
    }
}