package com.apex.agent.burstmode.preset

import com.apex.agent.burstmode.config.BurstModeConfig
import com.apex.agent.burstmode.config.BurstProfile
import com.apex.agent.burstmode.config.LlmProvider

/**
 * 狂暴模式预设。
 *
 * 封装了常见使用场景的完整配置，业务侧无需手动调参，直接选择预设即可。
 *
 * 每个预设包含：
 * - 一个 [BurstModeConfig] 配置实例
 * - 可选的 [BurstProfile] 性能档位
 * - 适用场景描述
 *
 * 自定义预设：如果预设不满足需求，使用 [BurstModeConfig.Builder] 自定义配置后，
 * 系统会自动将预设标记为 [CUSTOM]。
 */
enum class BurstPreset(
    val displayName: String,
    val description: String,
    val profile: BurstProfile?,
    private val configFactory: () -> BurstModeConfig
) {
    /**
     * 平衡预设（推荐）。
     * 适中并发、合理超时、启用所有功能。适用于大多数日常场景。
     */
    BALANCED(
        displayName = "平衡模式",
        description = "适中并发，合理资源使用，适合大多数场景",
        profile = BurstProfile.BALANCED,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(8)
                .defaultTimeoutMs(120_000L)
                .enableAdaptiveOptimization(true)
                .build()
        }
    ),

    /**
     * 性能预设。
     * 高并发、长超时、最大资源使用。适用于需要高吞吐量的批量任务。
     */
    PERFORMANCE(
        displayName = "性能模式",
        description = "最大并发，最长超时，适合批量高吞吐任务",
        profile = BurstProfile.AGGRESSIVE,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(16)
                .defaultTimeoutMs(300_000L)
                .enableAdaptiveOptimization(true)
                .memoryBudgetMb(512)
                .build()
        }
    ),

    /**
     * 省电预设。
     * 低并发、短超时、最少资源。适用于后台运行或电池敏感场景。
     */
    POWER_SAVER(
        displayName = "省电模式",
        description = "最低并发，严格超时，适合后台运行",
        profile = BurstProfile.POWER_SAVER,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(2)
                .defaultTimeoutMs(30_000L)
                .enableAdaptiveOptimization(false)
                .enableMetricsCollection(false)
                .memoryBudgetMb(64)
                .build()
        }
    ),

    /**
     * 本地推理预设。
     * 使用本地 LLaMA，无需网络。适用于离线场景。
     */
    LOCAL_INFERENCE(
        displayName = "本地推理",
        description = "使用本地 LLaMA，离线运行，保护隐私",
        profile = BurstProfile.CONSERVATIVE,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(4)
                .defaultTimeoutMs(180_000L)
                .llmProvider(LlmProvider.LOCAL_LLAMA)
                .llmModelName("llama-3.2-3b")
                .maxContextWindowTokens(4096)
                .build()
        }
    ),

    /**
     * 云端推理预设。
     * 使用 DeepSeek API，高质量推理。适用于需要最强模型能力的场景。
     */
    CLOUD_INFERENCE(
        displayName = "云端推理",
        description = "使用 DeepSeek API 云端推理，最高质量",
        profile = BurstProfile.BALANCED,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(8)
                .defaultTimeoutMs(60_000L)
                .llmProvider(LlmProvider.DEEPSEEK)
                .llmModelName("deepseek-chat")
                .maxContextWindowTokens(8192)
                .build()
        }
    ),

    /**
     * 流式处理预设。
     * 专为流式输出场景优化，支持超大文本增量处理。
     */
    STREAMING(
        displayName = "流式处理",
        description = "流式输出，支持超大文本增量处理",
        profile = BurstProfile.BALANCED,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(6)
                .defaultTimeoutMs(600_000L)
                .enableStreaming(true)
                .enableCheckpointing(true)
                .checkpointIntervalMs(5_000L)
                .maxContextWindowTokens(8192)
                .build()
        }
    ),

    /**
     * 测试预设。
     * 无 LLM、无网络、最快响应。适用于单元测试和集成测试。
     */
    TEST(
        displayName = "测试模式",
        description = "无 LLM，纯工具执行，用于测试",
        profile = BurstProfile.CONSERVATIVE,
        configFactory = {
            BurstModeConfig.Builder()
                .maxConcurrency(2)
                .defaultTimeoutMs(5_000L)
                .llmProvider(LlmProvider.NONE)
                .enableAdaptiveOptimization(false)
                .enableHealthCheck(false)
                .enableCheckpointing(false)
                .build()
        }
    ),

    /**
     * 自定义预设。
     * 当用户手动修改配置后，预设自动变为 CUSTOM。
     */
    CUSTOM(
        displayName = "自定义",
        description = "用户自定义配置",
        profile = null,
        configFactory = { BurstModeConfig.DEFAULT }
    );

    /**
     * 获取此预设对应的配置。
     */
    fun toConfig(): BurstModeConfig = configFactory()

    companion object {
        /** 默认预设。 */
        val DEFAULT = BALANCED
    }
}
