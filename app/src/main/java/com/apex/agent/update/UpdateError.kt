package com.apex.agent.update

/**
 * 热更新错误分类。
 *
 * 用于把网络错误、限流、解析错误、镜像失败、用户取消等场景区分开，
 * 让 UI 能给出更友好的提示与重试按钮。
 */
sealed class UpdateError(open val message: String) {
    /** 无网络连接 */
    data class NoNetwork(override val message: String = "当前无网络连接，请检查网络后重试") : UpdateError(message)
    /** 当前为移动网络且用户开启了"仅 Wi-Fi 下载" */
    data class WifiOnly(
        override val message: String = "当前为移动网络，已开启「仅 Wi-Fi 下载」。请切换到 Wi-Fi 或在设置中关闭该选项"
    ) : UpdateError(message)
    /** GitHub API 限流（403） */
    data class RateLimited(
        val resetEpochSec: Long?,
        override val message: String = "GitHub API 限流，请稍后再试"
    ) : UpdateError(message)
    /** 仓库无 Release 或 Release 无 APK 资源（404 / 空 assets） */
    data class NoRelease(override val message: String = "仓库尚未发布任何 Release，或最新 Release 未包含 APK") : UpdateError(message)
    /** 网络请求失败（DNS、超时、连接重置等） */
    data class NetworkError(val cause: Throwable, override val message: String = "网络请求失败：${cause.message ?: cause.javaClass.simpleName}") : UpdateError(message)
    /** JSON 解析失败 */
    data class ParseError(val cause: Throwable, override val message: String = "GitHub API 响应解析失败：${cause.message ?: cause.javaClass.simpleName}") : UpdateError(message)
    /** 所有镜像都失败 */
    data class AllMirrorsFailed(val triedCount: Int, override val message: String = "全部 $triedCount 个镜像均失败，请检查镜像设置或网络后重试") : UpdateError(message)
    /** 完整性校验失败 */
    data class IntegrityError(override val message: String) : UpdateError(message)
    /** 用户取消 */
    object Cancelled : UpdateError("已取消")
    /** 其他未分类错误 */
    data class Unknown(val cause: Throwable?, override val message: String = cause?.message ?: "未知错误") : UpdateError(message)

    /** 转为 [CheckResult.Failed]。 */
    fun toCheckFailed(): CheckResult.Failed = CheckResult.Failed(message, when (this) {
        is NetworkError -> cause
        is ParseError -> cause
        is Unknown -> cause
        else -> null
    })
}
