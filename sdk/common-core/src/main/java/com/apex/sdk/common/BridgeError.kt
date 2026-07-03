package com.apex.sdk.common

/**
 * 跨 APK 调用错误模型。
 *
 * 错误码分 3 段：
 *  - 1xx: 桥接层错误（APK 未安装 / 服务未连接 / 超时 / 序列化失败）
 *  - 2xx: 权限错误（无授权 / Shizuku 未授权 / 用户拒绝）
 *  - 3xx: 业务错误（被调 APK 内部执行失败）
 */
data class BridgeError(
    val code: Int,
    val message: String,
    val cause: String? = null,
    val sourceApk: String? = null
) {
    companion object {
        const val CODE_APK_NOT_INSTALLED = 101
        const val CODE_SERVICE_NOT_CONNECTED = 102
        const val CODE_CALL_TIMEOUT = 103
        const val CODE_SERIALIZATION_FAILED = 104
        const val CODE_PROCESS_DEAD = 105

        const val CODE_PERMISSION_DENIED = 201
        const val CODE_SHIZUKU_NOT_AUTHORIZED = 202
        const val CODE_USER_DENIED = 203

        const val CODE_BUSINESS_FAILURE = 301

        fun fromThrowable(t: Throwable, sourceApk: String? = null): BridgeError = BridgeError(
            code = CODE_BUSINESS_FAILURE,
            message = t.message ?: t.javaClass.simpleName,
            cause = t.stackTraceToString(),
            sourceApk = sourceApk
        )

        fun notInstalled(apkId: String) = BridgeError(
            code = CODE_APK_NOT_INSTALLED,
            message = "APK '$apkId' is not installed under shared UID",
            sourceApk = apkId
        )

        /**
         * 友好的未安装错误（带显示名和安装建议）。
         * 业务侧应优先使用本方法，UI 直接展示 message。
         */
        fun notInstalledFriendly(apkId: String): BridgeError {
            val desc = ApkDescriptors.byId(apkId)
            val necessityHint = when (desc?.necessity) {
                ApkNecessity.REQUIRED -> "（必要组件，必须安装）"
                ApkNecessity.OPTIONAL -> "（可选组件，按需安装）"
                ApkNecessity.DEBUG -> "（调试组件）"
                null -> ""
            }
            return BridgeError(
                code = CODE_APK_NOT_INSTALLED,
                message = "「${desc?.displayName ?: apkId}」未安装$necessityHint。" +
                    "请前往下载页安装后再使用此功能。",
                cause = "apkId=$apkId, package=${desc?.packageName}",
                sourceApk = apkId
            )
        }

        /**
         * 能力缺失错误（用于按 capability 路由的调用）。
         */
        fun capabilityMissing(capability: String): BridgeError {
            val providers = ApkDescriptors.byCapability(capability)
            val names = providers.joinToString(" / ") { it.displayName }
            return BridgeError(
                code = CODE_APK_NOT_INSTALLED,
                message = "能力 '$capability' 需要安装以下任一 APK：$names",
                cause = "capability=$capability",
                sourceApk = providers.firstOrNull()?.apkId
            )
        }

        fun notConnected(service: String) = BridgeError(
            code = CODE_SERVICE_NOT_CONNECTED,
            message = "Service '$service' is not connected"
        )

        fun timeout(service: String, timeoutMs: Long) = BridgeError(
            code = CODE_CALL_TIMEOUT,
            message = "Call to '$service' timed out after ${timeoutMs}ms"
        )
    }
}
