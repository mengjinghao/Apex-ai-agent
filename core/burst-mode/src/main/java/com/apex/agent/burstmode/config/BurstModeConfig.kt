package com.apex.agent.burstmode.config

import kotlinx.serialization.Serializable

/**
 * 狂暴模式配置。
 *
 * 包含所有可调参数。通过 [BurstModeConfig.Builder] 创建，或从 [BurstPreset] 获取预设。
 *
 * @property maxConcurrency 最大并发任务数
 * @property defaultTimeoutMs 默认超时（毫秒）
 * @property enableAdaptiveOptimization 是否启用自适应优化（GA 演化调参）
 * @property enableMetricsCollection 是否启用指标收集
 * @property enableHealthCheck 是否启用健康检查
 * @property healthCheckIntervalMs 健康检查间隔（毫秒）
 * @property memoryBudgetMb 内存预算（MB）
 * @property maxRetries 任务失败最大重试次数
 * @property retryDelayMs 重试间隔（毫秒）
 * @property enableStreaming 是否启用流式输出
 * @property maxContextWindowTokens LLM 上下文窗口大小（token 数）
 * @property enableCheckpointing 是否启用断点续传
 * @property checkpointIntervalMs 检查点保存间隔（毫秒）
 * @property llmProvider LLM 服务提供方
 * @property llmModelName LLM 模型名称
 * @property llmApiKey LLM API Key（远程服务时使用）
 * @property llmEndpoint LLM 自定义端点 URL
 * @property custom 自定义配置项（业务侧扩展用）
 */
@Serializable
data class BurstModeConfig(
    val maxConcurrency: Int,
    val defaultTimeoutMs: Long,
    val enableAdaptiveOptimization: Boolean,
    val enableMetricsCollection: Boolean,
    val enableHealthCheck: Boolean,
    val healthCheckIntervalMs: Long,
    val memoryBudgetMb: Int,
    val maxRetries: Int,
    val retryDelayMs: Long,
    val enableStreaming: Boolean,
    val maxContextWindowTokens: Int,
    val enableCheckpointing: Boolean,
    val checkpointIntervalMs: Long,
    val llmProvider: LlmProvider,
    val llmModelName: String,
    val llmApiKey: String?,
    val llmEndpoint: String?,
    val custom: Map<String, String> = emptyMap()
) {

    /**
     * 配置构建器。
     */
    class Builder {
        private var maxConcurrency: Int = 8
        private var defaultTimeoutMs: Long = 120_000L
        private var enableAdaptiveOptimization: Boolean = true
        private var enableMetricsCollection: Boolean = true
        private var enableHealthCheck: Boolean = true
        private var healthCheckIntervalMs: Long = 30_000L
        private var memoryBudgetMb: Int = 256
        private var maxRetries: Int = 2
        private var retryDelayMs: Long = 1_000L
        private var enableStreaming: Boolean = true
        private var maxContextWindowTokens: Int = 8192
        private var enableCheckpointing: Boolean = true
        private var checkpointIntervalMs: Long = 10_000L
        private var llmProvider: LlmProvider = LlmProvider.DEEPSEEK
        private var llmModelName: String = "deepseek-chat"
        private var llmApiKey: String? = null
        private var llmEndpoint: String? = null
        private val custom: MutableMap<String, String> = mutableMapOf()

        fun maxConcurrency(value: Int) = apply { maxConcurrency = value.coerceAtLeast(1) }
        fun defaultTimeoutMs(value: Long) = apply { defaultTimeoutMs = value.coerceAtLeast(1_000L) }
        fun enableAdaptiveOptimization(value: Boolean) = apply { enableAdaptiveOptimization = value }
        fun enableMetricsCollection(value: Boolean) = apply { enableMetricsCollection = value }
        fun enableHealthCheck(value: Boolean) = apply { enableHealthCheck = value }
        fun healthCheckIntervalMs(value: Long) = apply { healthCheckIntervalMs = value.coerceAtLeast(5_000L) }
        fun memoryBudgetMb(value: Int) = apply { memoryBudgetMb = value.coerceAtLeast(32) }
        fun maxRetries(value: Int) = apply { maxRetries = value.coerceAtLeast(0) }
        fun retryDelayMs(value: Long) = apply { retryDelayMs = value.coerceAtLeast(100L) }
        fun enableStreaming(value: Boolean) = apply { enableStreaming = value }
        fun maxContextWindowTokens(value: Int) = apply { maxContextWindowTokens = value.coerceAtLeast(1024) }
        fun enableCheckpointing(value: Boolean) = apply { enableCheckpointing = value }
        fun checkpointIntervalMs(value: Long) = apply { checkpointIntervalMs = value.coerceAtLeast(1_000L) }
        fun llmProvider(value: LlmProvider) = apply { llmProvider = value }
        fun llmModelName(value: String) = apply { llmModelName = value }
        fun llmApiKey(value: String?) = apply { llmApiKey = value }
        fun llmEndpoint(value: String?) = apply { llmEndpoint = value }

        fun custom(key: String, value: String) = apply { custom[key] = value }
        fun customAll(values: Map<String, String>) = apply { custom.putAll(values) }

        fun build(): BurstModeConfig = BurstModeConfig(
            maxConcurrency = maxConcurrency,
            defaultTimeoutMs = defaultTimeoutMs,
            enableAdaptiveOptimization = enableAdaptiveOptimization,
            enableMetricsCollection = enableMetricsCollection,
            enableHealthCheck = enableHealthCheck,
            healthCheckIntervalMs = healthCheckIntervalMs,
            memoryBudgetMb = memoryBudgetMb,
            maxRetries = maxRetries,
            retryDelayMs = retryDelayMs,
            enableStreaming = enableStreaming,
            maxContextWindowTokens = maxContextWindowTokens,
            enableCheckpointing = enableCheckpointing,
            checkpointIntervalMs = checkpointIntervalMs,
            llmProvider = llmProvider,
            llmModelName = llmModelName,
            llmApiKey = llmApiKey,
            llmEndpoint = llmEndpoint,
            custom = custom.toMap()
        )

        companion object {
            /** 从现有配置创建构建器（用于增量修改）。 */
            fun from(config: BurstModeConfig): Builder = Builder()
                .maxConcurrency(config.maxConcurrency)
                .defaultTimeoutMs(config.defaultTimeoutMs)
                .enableAdaptiveOptimization(config.enableAdaptiveOptimization)
                .enableMetricsCollection(config.enableMetricsCollection)
                .enableHealthCheck(config.enableHealthCheck)
                .healthCheckIntervalMs(config.healthCheckIntervalMs)
                .memoryBudgetMb(config.memoryBudgetMb)
                .maxRetries(config.maxRetries)
                .retryDelayMs(config.retryDelayMs)
                .enableStreaming(config.enableStreaming)
                .maxContextWindowTokens(config.maxContextWindowTokens)
                .enableCheckpointing(config.enableCheckpointing)
                .checkpointIntervalMs(config.checkpointIntervalMs)
                .llmProvider(config.llmProvider)
                .llmModelName(config.llmModelName)
                .llmApiKey(config.llmApiKey)
                .llmEndpoint(config.llmEndpoint)
                .customAll(config.custom)
        }
    }

    companion object {
        /** 默认配置（等同于 [BurstProfile.BALANCED]）。 */
        val DEFAULT: BurstModeConfig = Builder().build()

        /**
         * 根据性能档位创建配置。
         */
        fun fromProfile(profile: BurstProfile): BurstModeConfig = Builder()
            .maxConcurrency(profile.maxConcurrency)
            .defaultTimeoutMs(profile.defaultTimeoutMs)
            .enableAdaptiveOptimization(profile.enableAdaptiveOptimization)
            .enableMetricsCollection(profile.enableMetricsCollection)
            .memoryBudgetMb(profile.memoryBudgetMb)
            .build()
    }
}

/**
 * LLM 服务提供方。
 */
@Serializable
enum class LlmProvider(val displayName: String) {
    /** DeepSeek API（云端） */
    DEEPSEEK("DeepSeek"),
    /** Claude API（云端） */
    CLAUDE("Claude"),
    /** OpenAI API（云端） */
    OPENAI("OpenAI"),
    /** 本地 LLaMA（离线） */
    LOCAL_LLAMA("Local LLaMA"),
    /** 自定义端点（兼容 OpenAI 协议） */
    CUSTOM("Custom"),
    /** 无 LLM（仅工具执行，无推理） */
    NONE("No LLM")
}
