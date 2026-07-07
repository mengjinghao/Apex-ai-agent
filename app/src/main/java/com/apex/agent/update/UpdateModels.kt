package com.apex.agent.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 热更新模块的数据模型。
 *
 * 该模块通过 GitHub Releases API 检查新版本，并通过用户配置的镜像源下载 APK，
 * 校验 SHA-256 后调起系统 PackageInstaller 完成更新。
 */

/** GitHub Release API 返回的单个资源（asset） */
@Serializable
data class UpdateAsset(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    @SerialName("content_type")
    val contentType: String = ""
)

/** GitHub Release API 返回的发布信息 */
@Serializable
data class UpdateRelease(
    val id: Long = 0L,
    val name: String? = null,
    @SerialName("tag_name")
    val tagName: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<UpdateAsset> = emptyList(),
    val htmlUrl: String? = null
)

/** 检查更新的结果 */
sealed class CheckResult {
    /** 已是最新版本 */
    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String
    ) : CheckResult()

    /** 发现新版本 */
    data class UpdateAvailable(
        val currentVersion: String,
        val release: UpdateRelease,
        /** 从所有镜像中可下载的 APK 资源（已筛选） */
        val apkAsset: UpdateAsset,
        /** 释放日志（Markdown 原文） */
        val changelog: String,
        /** 估算大小（人类可读） */
        val sizeText: String,
        /** 期望的 SHA-256（小写 hex），null 表示未知 */
        val expectedSha256: String?
    ) : CheckResult()

    /** 检查失败（网络 / 解析 / 镜像全部不可达） */
    data class Failed(val reason: String, val cause: Throwable? = null) : CheckResult()
}

/** 下载进度 */
data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val percent: Int,
    /** 当前正在使用的镜像 id */
    val mirrorId: String,
    /** 每秒字节数（用于显示速度） */
    val speedBytesPerSec: Long
)

/** 镜像源 */
@Serializable
data class MirrorSource(
    /** 唯一 id（内置镜像使用稳定字符串，自定义镜像使用 uuid） */
    val id: String,
    /** 显示名称 */
    val name: String,
    /**
     * URL 模板，使用 `{url}` 占位符表示原始 GitHub 下载地址。
     * 例如 `https://ghproxy.com/{url}`。
     * 空字符串或 `{url}` 本身代表直连 GitHub。
     */
    val urlTemplate: String,
    /** 是否为内置镜像（用户不可删除） */
    val builtin: Boolean = false,
    /** 简短说明（地区/特点） */
    val description: String = "",
    /** 是否启用 */
    val enabled: Boolean = true
) {
    /**
     * 将原始 GitHub URL 包装成镜像 URL。
     * 若本镜像代表直连，则原样返回。
     */
    fun wrap(originalUrl: String): String {
        return if (urlTemplate.isBlank() || urlTemplate == "{url}") {
            originalUrl
        } else {
            urlTemplate.replace("{url}", originalUrl)
        }
    }
}

/** 镜像连通性测试结果 */
data class MirrorTestResult(
    val mirrorId: String,
    val success: Boolean,
    val latencyMs: Long,
    val message: String
)

/** 内部使用的 JSON 解析器（宽松模式，容错 GitHub API 偶尔新增字段） */
internal val updateJson by lazy {
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
}
