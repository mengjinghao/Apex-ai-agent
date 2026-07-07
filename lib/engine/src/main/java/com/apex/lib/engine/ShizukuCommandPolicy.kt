package com.apex.lib.engine

/**
 * Shizuku 可用性分级。
 *
 * 编排器依据本枚举决定是否启用 Shizuku 通道、是否触发降级。
 */
enum class ShizukuAvailability(val displayName: String) {
    /** Shizuku 已安装、已授权、版本满足要求，可立即使用。 */
    AVAILABLE_AND_AUTHORIZED("可用且已授权"),

    /** Shizuku 已安装但未授权。 */
    AVAILABLE_NOT_AUTHORIZED("已安装未授权"),

    /** Shizuku 未安装或版本过低。 */
    NOT_AVAILABLE("不可用"),

    /** 状态未知（通常底层未初始化）。 */
    UNKNOWN("未知")
}

/**
 * Shizuku 命令执行策略模型（纯数据 + 决策方法，无 Android 依赖）。
 *
 * 由 [EngineOrchestrator] 通过 [EngineGateway] 拼装而成，
 * 决定：
 *   1. 当前是否应该用 Shizuku 通道（[shouldUseShizuku]）
 *   2. 当前不可用时应不应该回退到普通 shell（[shouldFallback]）
 *
 * @property isAvailable Shizuku 是否已安装/激活
 * @property isAuthorized 权限是否已授予
 * @property version Shizuku 版本号
 * @property minVersionRequired 最低要求的 Shizuku 版本（默认 11）
 * @property fallbackOnUnavailable 当 Shizuku 不可用且上层 preferShizuku=true 时，是否回退到普通 shell
 */
data class ShizukuCommandPolicy(
    val isAvailable: Boolean,
    val isAuthorized: Boolean,
    val version: Int,
    val minVersionRequired: Int = DEFAULT_MIN_VERSION,
    val fallbackOnUnavailable: Boolean = true
) {
    /** 当前可用性分级。 */
    fun availability(): ShizukuAvailability = when {
        !isAvailable -> ShizukuAvailability.NOT_AVAILABLE
        !isAuthorized -> ShizukuAvailability.AVAILABLE_NOT_AUTHORIZED
        version < minVersionRequired -> ShizukuAvailability.NOT_AVAILABLE
        else -> ShizukuAvailability.AVAILABLE_AND_AUTHORIZED
    }

    /**
     * 是否应该使用 Shizuku 通道。
     *
     * @param preferShizuku 上层是否偏好 Shizuku
     */
    fun shouldUseShizuku(preferShizuku: Boolean): Boolean =
        preferShizuku && availability() == ShizukuAvailability.AVAILABLE_AND_AUTHORIZED

    /**
     * 是否应该回退到普通 shell。
     *
     * 仅当 [shouldUseShizuku] 为 false 且 [fallbackOnUnavailable] 为 true 时回退。
     */
    fun shouldFallback(preferShizuku: Boolean): Boolean =
        preferShizuku && !shouldUseShizuku(preferShizuku) && fallbackOnUnavailable

    /**
     * 简短的人类可读描述，用于日志。
     */
    fun describe(): String =
        "ShizukuPolicy(availability=${availability().name}, version=$version, minRequired=$minVersionRequired, fallback=$fallbackOnUnavailable)"

    companion object {
        /** 默认最低 Shizuku 版本（v11 起稳定支持现代 API）。 */
        const val DEFAULT_MIN_VERSION: Int = 11

        /** 构造一个完全不可用的策略（便于测试 / 初始化占位）。 */
        fun unavailable(): ShizukuCommandPolicy = ShizukuCommandPolicy(
            isAvailable = false,
            isAuthorized = false,
            version = 0
        )
    }
}
