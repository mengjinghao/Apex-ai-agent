package com.apex.lib.rage

/**
 * 狂暴模式策略预设。
 *
 * 4 种预设对应不同的激进度：
 * - [AGGRESSIVE]   — 激进：高并发、短超时、多重试、全功能开启
 * - [BALANCED]     — 平衡：默认参数
 * - [CONSERVATIVE] — 保守：低并发、长超时、少重试、关闭扩容
 * - [DEBUG]        — 调试：单线程、无重试、关闭沙盒
 */
enum class RageStrategyPreset {
    AGGRESSIVE,
    BALANCED,
    CONSERVATIVE,
    DEBUG
}

/**
 * 狂暴模式预设配置表。
 *
 * 用法：
 * ```
 * val config = RagePresets.forPreset(RageStrategyPreset.AGGRESSIVE)
 * engine.applyConfig(config)
 * // 或快捷：
 * engine.switchPreset(RageStrategyPreset.AGGRESSIVE)
 * ```
 */
object RagePresets {

    /** 激进：8 并发、30s 超时、5 次重试、全功能开启。 */
    val AGGRESSIVE = RageModeConfig(
        maxConcurrency = 8,
        defaultTimeoutMs = 30_000L,
        maxRetries = 5,
        enableAutoExpand = true,
        enableGitBranching = true,
        enableSandboxExec = true,
        enableGithubSearch = true,
        enableCodeRag = true
    )

    /** 平衡：4 并发、60s 超时、3 次重试（默认）。 */
    val BALANCED = RageModeConfig(
        maxConcurrency = 4,
        defaultTimeoutMs = 60_000L,
        maxRetries = 3,
        enableAutoExpand = true,
        enableGitBranching = true,
        enableSandboxExec = true,
        enableGithubSearch = false,
        enableCodeRag = true
    )

    /** 保守：2 并发、120s 超时、1 次重试、关闭扩容。 */
    val CONSERVATIVE = RageModeConfig(
        maxConcurrency = 2,
        defaultTimeoutMs = 120_000L,
        maxRetries = 1,
        enableAutoExpand = false,
        enableGitBranching = true,
        enableSandboxExec = true,
        enableGithubSearch = false,
        enableCodeRag = true
    )

    /** 调试：1 并发、300s 超时、0 次重试、关闭沙盒。 */
    val DEBUG = RageModeConfig(
        maxConcurrency = 1,
        defaultTimeoutMs = 300_000L,
        maxRetries = 0,
        enableAutoExpand = false,
        enableGitBranching = false,
        enableSandboxExec = false,
        enableGithubSearch = false,
        enableCodeRag = false
    )

    /** 按预设名获取配置。 */
    fun forPreset(preset: RageStrategyPreset): RageModeConfig = when (preset) {
        RageStrategyPreset.AGGRESSIVE   -> AGGRESSIVE
        RageStrategyPreset.BALANCED     -> BALANCED
        RageStrategyPreset.CONSERVATIVE -> CONSERVATIVE
        RageStrategyPreset.DEBUG        -> DEBUG
    }

    /** 按名称获取配置（不区分大小写，找不到返回 BALANCED）。 */
    fun forName(name: String): RageModeConfig =
        runCatching { forPreset(RageStrategyPreset.valueOf(name.uppercase())) }.getOrDefault(BALANCED)

    /** 全部预设名列表。 */
    val ALL: List<RageStrategyPreset> = RageStrategyPreset.values().toList()
}
