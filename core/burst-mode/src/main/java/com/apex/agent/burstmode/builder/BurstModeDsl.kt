package com.apex.agent.burstmode.builder

import com.apex.agent.burstmode.config.BurstModeConfig
import com.apex.agent.burstmode.config.BurstProfile
import com.apex.agent.burstmode.config.LlmProvider
import com.apex.agent.burstmode.preset.BurstPreset

/**
 * 狂暴模式 DSL 构建器。
 *
 * 提供 Kotlin DSL 风格的配置语法，比链式 Builder 更简洁、可读性更强。
 *
 * # 使用示例
 *
 * ```
 * val burstMode = burstMode(context) {
 *     preset = BurstPreset.BALANCED
 *     profile = BurstProfile.AGGRESSIVE
 *
 *     config {
 *         maxConcurrency = 12
 *         timeoutMs = 60_000
 *         enableAdaptiveOptimization = true
 *
 *         llm {
 *             provider = LlmProvider.DEEPSEEK
 *             modelName = "deepseek-chat"
 *             apiKey = "sk-xxx"
 *         }
 *     }
 *
 *     // 功能开关
 *     autoStart = true
 *     enableMetrics = true
 *     healthCheck {
 *         enabled = true
 *         intervalMs = 30_000
 *     }
 * }
 * ```
 */
@DslMarker
annotation class BurstModeDsl

/**
 * DSL 顶层入口。
 *
 * @param context Android Context
 * @param init 配置块
 * @return 已初始化的 [com.apex.agent.burstmode.api.BurstMode]
 */
fun burstMode(
    context: android.content.Context,
    init: BurstModeDslBuilder.() -> Unit
): com.apex.agent.burstmode.api.BurstMode {
    val builder = BurstModeDslBuilder().apply(init)
    return builder.build(context)
}

/**
 * DSL 构建器。
 */
@BurstModeDsl
class BurstModeDslBuilder {

    /** 预设。设置后会覆盖 config 和 profile（如果预设包含）。 */
    var preset: BurstPreset? = null

    /** 性能档位。 */
    var profile: BurstProfile? = null

    /** 是否自动启动（默认 true）。 */
    var autoStart: Boolean = true

    /** 是否启用指标收集（默认 true）。 */
    var enableMetrics: Boolean = true

    /** 自定义配置块。 */
    private var configBuilder: BurstModeConfig.Builder = BurstModeConfig.Builder()
    private var configCustomized = false

    /** 健康检查配置。 */
    private var healthCheckEnabled = true
    private var healthCheckIntervalMs = 30_000L

    /**
     * 配置块。
     */
    fun config(init: ConfigDsl.() -> Unit) {
        configCustomized = true
        ConfigDsl(configBuilder).apply(init)
    }

    /**
     * 健康检查配置块。
     */
    fun healthCheck(init: HealthCheckDsl.() -> Unit) {
        HealthCheckDsl(this).apply(init)
    }

    /**
     * 构建并初始化 BurstMode。
     */
    internal fun build(context: android.content.Context): com.apex.agent.burstmode.api.BurstMode {
        val builder = com.apex.agent.burstmode.api.BurstModeBuilder(context)

        // 应用预设
        val effectivePreset = preset ?: BurstPreset.DEFAULT
        builder.withPreset(effectivePreset)

        // 应用性能档位
        profile?.let { builder.withProfile(it) }

        // 应用自定义配置
        if (configCustomized) {
            builder.withConfig(configBuilder.build())
        }

        // 功能开关
        builder.enableAutoStart(autoStart)
        builder.enableMetrics(enableMetrics)
        builder.enableHealthCheck(healthCheckEnabled)
        builder.healthCheckInterval(healthCheckIntervalMs)

        return builder.initialize()
    }

    internal fun setHealthCheckEnabled(enabled: Boolean) {
        healthCheckEnabled = enabled
    }

    internal fun setHealthCheckInterval(intervalMs: Long) {
        healthCheckIntervalMs = intervalMs
    }
}

/**
 * 配置 DSL。
 */
@BurstModeDsl
class ConfigDsl internal constructor(
    private val builder: BurstModeConfig.Builder
) {
    /** 最大并发数。 */
    var maxConcurrency: Int
        get() = 8
        set(value) { builder.maxConcurrency(value) }

    /** 默认超时（毫秒）。 */
    var timeoutMs: Long
        get() = 120_000L
        set(value) { builder.defaultTimeoutMs(value) }

    /** 是否启用自适应优化。 */
    var enableAdaptiveOptimization: Boolean
        get() = true
        set(value) { builder.enableAdaptiveOptimization(value) }

    /** 是否启用指标收集。 */
    var enableMetricsCollection: Boolean
        get() = true
        set(value) { builder.enableMetricsCollection(value) }

    /** 是否启用流式输出。 */
    var enableStreaming: Boolean
        get() = true
        set(value) { builder.enableStreaming(value) }

    /** 是否启用断点续传。 */
    var enableCheckpointing: Boolean
        get() = true
        set(value) { builder.enableCheckpointing(value) }

    /** 最大重试次数。 */
    var maxRetries: Int
        get() = 2
        set(value) { builder.maxRetries(value) }

    /** 内存预算（MB）。 */
    var memoryBudgetMb: Int
        get() = 256
        set(value) { builder.memoryBudgetMb(value) }

    /** LLM 上下文窗口（token 数）。 */
    var maxContextWindowTokens: Int
        get() = 8192
        set(value) { builder.maxContextWindowTokens(value) }

    /**
     * LLM 配置块。
     */
    fun llm(init: LlmDsl.() -> Unit) {
        LlmDsl(builder).apply(init)
    }

    /**
     * 自定义配置项。
     */
    fun custom(key: String, value: String) {
        builder.custom(key, value)
    }
}

/**
 * LLM 配置 DSL。
 */
@BurstModeDsl
class LlmDsl internal constructor(
    private val builder: BurstModeConfig.Builder
) {
    /** LLM 提供方。 */
    var provider: LlmProvider
        get() = LlmProvider.DEEPSEEK
        set(value) { builder.llmProvider(value) }

    /** 模型名称。 */
    var modelName: String
        get() = "deepseek-chat"
        set(value) { builder.llmModelName(value) }

    /** API Key（云端服务用）。 */
    var apiKey: String?
        get() = null
        set(value) { builder.llmApiKey(value) }

    /** 自定义端点 URL。 */
    var endpoint: String?
        get() = null
        set(value) { builder.llmEndpoint(value) }
}

/**
 * 健康检查 DSL。
 */
@BurstModeDsl
class HealthCheckDsl internal constructor(
    private val parent: BurstModeDslBuilder
) {
    /** 是否启用健康检查。 */
    var enabled: Boolean
        get() = true
        set(value) { parent.setHealthCheckEnabled(value) }

    /** 检查间隔（毫秒）。 */
    var intervalMs: Long
        get() = 30_000L
        set(value) { parent.setHealthCheckInterval(value) }
}
