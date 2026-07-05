package com.apex.lib.market

import kotlinx.serialization.Serializable

/**
 * 市场数据模型集合（lib:market 内部统一使用的纯 Kotlin 模型）。
 *
 * 说明：APK 层的 [com.apex.agent.integration.market.MarketItem] / [com.apex.agent.integration.api.IntegrationCategory]
 * 是与 `core:integration` 耦合的"集成侧"模型；lib:market 不依赖该模块，
 * 因此在此定义一套独立的、与 Android / 网络 / 集成中心完全解耦的纯数据模型，
 * APK 的 ServiceFacade 负责在两类模型之间相互转换。
 */

// ============================================================
// 分类
// ============================================================

/**
 * 市场资产分类。
 *
 * 与 `IntegrationCategory` 对齐但收敛为 5 类：
 *   - SKILL：技能（ClawHub 风格的 Skill）
 *   - MCP：Model Context Protocol 服务器
 *   - PLUGIN：插件（运行于宿主进程的扩展）
 *   - MODEL：模型平台（云端 LLM Provider）
 *   - AGENT_ROLE：Agent 角色模板（多 Agent APK 复用）
 */
enum class MarketCategory {
    SKILL,
    MCP,
    PLUGIN,
    MODEL,
    AGENT_ROLE;

    companion object {
        /** 按字符串解析，匹配失败回退到 [SKILL]。 */
        fun fromString(name: String?): MarketCategory =
            runCatching { valueOf(name!!.uppercase().trim()) }.getOrDefault(SKILL)

        /** 兼容 IntegrationCategory 的名称（SKILLS/PLUGINS/MODEL_PLATFORMS）。 */
        fun fromIntegrationName(name: String?): MarketCategory = when (name?.uppercase()?.trim()) {
            "SKILLS" -> SKILL
            "MCP" -> MCP
            "PLUGINS" -> PLUGIN
            "MODEL_PLATFORMS", "MODEL" -> MODEL
            "AGENT_ROLE", "AGENT_ROLES" -> AGENT_ROLE
            else -> SKILL
        }
    }
}

// ============================================================
// 市场项
// ============================================================

/**
 * 市场中可被发现 / 安装 / 调用的一个条目。
 *
 * @property id 唯一 ID（通常为 "<marketId>:<itemKey>"）
 * @property name 显示名称
 * @property author 作者 / 来源
 * @property description 简介
 * @property version 当前版本
 * @property category 分类
 * @property sourceUrl 源地址（仓库 / 主页）
 * @property downloadUrl 下载地址
 * @property iconUrl 图标
 * @property marketId 所属市场 ID
 * @property installed 是否已安装
 * @property hasUpdate 是否有可更新版本
 * @property rating 评分（0-5）
 * @property downloadCount 下载次数
 * @property downloadSizeBytes 下载大小（字节）
 * @property tags 标签
 * @property metadata 额外元数据（业务侧自定义）
 */
@Serializable
data class MarketItem(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val version: String = "",
    val category: String,
    val sourceUrl: String? = null,
    val downloadUrl: String = "",
    val iconUrl: String? = null,
    val marketId: String = "",
    val installed: Boolean = false,
    val hasUpdate: Boolean = false,
    val rating: Double = 0.0,
    val downloadCount: Long = 0L,
    val downloadSizeBytes: Long = 0L,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    /** 转换为枚举分类的便捷访问器。 */
    val categoryEnum: MarketCategory get() = MarketCategory.fromIntegrationName(category)
}

// ============================================================
// 已安装项
// ============================================================

/**
 * 已安装资产的信息（与 lib 内部状态机对齐）。
 */
@Serializable
data class InstalledItem(
    val itemId: String,
    val name: String,
    val category: String,
    val installedVersion: String,
    val latestVersion: String? = null,
    val installedAt: Long = 0L,
    val enabled: Boolean = true,
    val installedPath: String? = null,
    val marketId: String = "",
    val hasUpdate: Boolean = false
)

// ============================================================
// 安装状态机
// ============================================================

/**
 * 安装流程状态。
 *
 * 状态机：
 *   NOT_INSTALLED → QUEUED → DOWNLOADING → INSTALLING → INSTALLED
 *                                                              ↘ FAILED
 *   INSTALLED → UPDATING → INSTALLED / FAILED
 *   任意状态 → CANCELED
 */
enum class InstallStatus {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    UPDATING,
    FAILED,
    CANCELED;

    /** 是否处于终态（不再变化）。 */
    val isTerminal: Boolean get() = this == INSTALLED || this == FAILED || this == CANCELED

    /** 是否处于进行中。 */
    val isInProgress: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == INSTALLING || this == UPDATING
}

// ============================================================
// 事件
// ============================================================

/**
 * 市场引擎对外广播的事件流。
 *
 * APK 的 UI / 跨 APK 通知可订阅 [MarketEngine.events] 获取实时变化。
 */
sealed class MarketEvent {

    /** 搜索完成。 */
    data class SearchCompleted(
        val category: MarketCategory,
        val query: String,
        val resultCount: Int,
        val fromCache: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) : MarketEvent()

    /** 收藏已添加。 */
    data class FavoriteAdded(val itemId: String, val timestamp: Long = System.currentTimeMillis()) : MarketEvent()

    /** 收藏已移除。 */
    data class FavoriteRemoved(val itemId: String, val timestamp: Long = System.currentTimeMillis()) : MarketEvent()

    /** 安装任务入队。 */
    data class InstallQueued(val taskId: String, val itemId: String, val timestamp: Long = System.currentTimeMillis()) : MarketEvent()

    /** 安装进度更新。 */
    data class InstallProgress(
        val taskId: String,
        val itemId: String,
        val progress: Int,         // 0-100
        val stage: String,         // "downloading" / "installing" / ...
        val timestamp: Long = System.currentTimeMillis()
    ) : MarketEvent()

    /** 安装完成。 */
    data class InstallCompleted(
        val taskId: String,
        val itemId: String,
        val installedPath: String?,
        val timestamp: Long = System.currentTimeMillis()
    ) : MarketEvent()

    /** 安装失败。 */
    data class InstallFailed(
        val taskId: String,
        val itemId: String,
        val error: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : MarketEvent()

    /** 资产被卸载。 */
    data class Uninstalled(val itemId: String, val timestamp: Long = System.currentTimeMillis()) : MarketEvent()

    /** 缓存被失效（手动或过期）。 */
    data class CacheInvalidated(val marketId: String?, val entryCount: Int, val timestamp: Long = System.currentTimeMillis()) : MarketEvent()

    /** 使用事件被记录（用于"最近使用"等 UI 刷新）。 */
    data class UsageRecorded(val itemId: String, val eventType: String, val timestamp: Long = System.currentTimeMillis()) : MarketEvent()
}
