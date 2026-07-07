package com.apex.agent.integration.exception

/**
 * 集成异常基类。
 */
sealed class IntegrationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** 市场未注册。 */
    class MarketNotFound(val marketId: String) :
        IntegrationException("Market not found: $marketId")

    /** 市场不可用。 */
    class MarketUnavailable(val marketId: String, reason: String) :
        IntegrationException("Market '$marketId' unavailable: $reason")

    /** 集成项未找到。 */
    class ItemNotFound(val itemId: String) :
        IntegrationException("Integration item not found: $itemId")

    /** 安装失败。 */
    class InstallationFailed(val itemId: String, reason: String, cause: Throwable? = null) :
        IntegrationException("Failed to install '$itemId': $reason", cause)

    /** 卸载失败。 */
    class UninstallationFailed(val itemId: String, reason: String) :
        IntegrationException("Failed to uninstall '$itemId': $reason")

    /** 导入失败。 */
    class ImportFailed(reason: String, cause: Throwable? = null) :
        IntegrationException("Import failed: $reason", cause)

    /** 分类不支持。 */
    class UnsupportedCategory(val category: String) :
        IntegrationException("Unsupported integration category: $category")

    /** 配置无效。 */
    class InvalidConfig(val field: String, reason: String) :
        IntegrationException("Invalid config '$field': $reason")

    /** 网络错误。 */
    class NetworkError(val url: String, cause: Throwable) :
        IntegrationException("Network error for '$url': ${cause.message}", cause)
}
