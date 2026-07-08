package com.apex.agent.integration.market

import kotlinx.serialization.Serializable
import com.apex.agent.integration.api.IntegrationCategory

/**
 * 集成项状态。
 */
enum class IntegrationItemState {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    UPDATING,
    DISABLED,
    ERROR
}

/**
 * 集成项来源类型。
 */
enum class IntegrationSourceType {
    OFFICIAL_MARKET,
    THIRD_PARTY_MARKET,
    LOCAL_FILE,
    URL,
    GIT_REPOSITORY,
    MANUAL_IMPORT
}

/**
 * 市场中的可安装项。
 *
 * 这是市场中展示的一个集成项（技能/MCP/插件/模型），包含元信息和安装状态。
 *
 * @property id 唯一标识（通常为 "market_id:item_id"）
 * @property name 显示名称
 * @property description 描述
 * @property author 作者
 * @property version 版本号
 * @property category 所属分类
 * @property marketId 所属市场 ID
 * @property sourceType 来源类型
 * @property sourceUrl 来源 URL（可选）
 * @property iconUrl 图标 URL（可选）
 * @property downloadUrl 下载 URL
 * @property downloadSizeBytes 下载大小（字节）
 * @property tags 标签列表
 * @property rating 评分（0..5）
 * @property downloadCount 下载次数
 * @property verified 是否已验证（官方/可信）
 * @property metadata 附加元数据
 * @property state 当前状态
 * @property installedVersion 已安装版本（如果已安装）
 */
@Serializable
data class MarketItem(
    val id: String,
    val name: String,
    val description: String,
    val author: String = "",
    val version: String,
    val category: IntegrationCategory,
    val marketId: String,
    val sourceType: IntegrationSourceType = IntegrationSourceType.OFFICIAL_MARKET,
    val sourceUrl: String? = null,
    val iconUrl: String? = null,
    val downloadUrl: String,
    val downloadSizeBytes: Long = 0,
    val tags: List<String> = emptyList(),
    val rating: Double = 0.0,
    val downloadCount: Long = 0,
    val verified: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
    val state: IntegrationItemState = IntegrationItemState.NOT_INSTALLED,
    val installedVersion: String? = null
) {
    /**
     * 是否已安装。
     */
    val isInstalled: Boolean
        get() = state == IntegrationItemState.INSTALLED || installedVersion != null

    /**
     * 是否有更新。
     */
    val hasUpdate: Boolean
        get() = installedVersion != null && installedVersion != version

    /**
     * 简要描述（截断到 100 字符）。
     */
    val shortDescription: String
        get() = if (description.length > 100) description.take(97) + "..." else description
}

/**
 * 市场搜索结果。
 *
 * @property items 搜索结果列表
 * @property totalCount 总数（可能大于 items.size，用于分页）
 * @property page 当前页码（从 1 开始）
 * @property pageSize 每页大小
 * @property hasMore 是否还有更多
 */
data class MarketSearchResult(
    val items: List<MarketItem>,
    val totalCount: Int,
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasMore: Boolean = false
) {
    companion object {
        val EMPTY = MarketSearchResult(
            items = emptyList(),
            totalCount = 0,
            page = 1,
            pageSize = 20,
            hasMore = false
        )
    }
}

/**
 * 市场搜索过滤条件。
 *
 * @property query 搜索关键词
 * @property tags 标签过滤（任一匹配）
 * @property verifiedOnly 仅显示已验证
 * @property minRating 最低评分
 * @property sortBy 排序方式
 * @property page 页码
 * @property pageSize 每页大小
 */
data class MarketSearchFilter(
    val query: String = "",
    val tags: List<String> = emptyList(),
    val verifiedOnly: Boolean = false,
    val minRating: Double = 0.0,
    val sortBy: SortBy = SortBy.POPULARITY,
    val page: Int = 1,
    val pageSize: Int = 20
)

/**
 * 排序方式。
 */
enum class SortBy {
    POPULARITY,
    RATING,
    NEWEST,
    NAME,
    DOWNLOAD_SIZE
}
