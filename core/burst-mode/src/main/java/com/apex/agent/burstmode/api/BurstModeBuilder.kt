package com.apex.agent.burstmode.api

import android.content.Context
import com.apex.agent.burstmode.config.BurstModeConfig
import com.apex.agent.burstmode.config.BurstProfile
import com.apex.agent.burstmode.preset.BurstPreset

/**
 * BurstMode 构建器。
 *
 * 提供链式 API 配置和创建 [BurstMode] 实例。
 *
 * 使用示例：
 * ```
 * val burstMode = BurstMode.create(context)
 *     .withPreset(BurstPreset.PERFORMANCE)
 *     .withCustomConfig { builder ->
 *         builder.maxConcurrency(16)
 *     }
 *     .withProfile(BurstProfile.AGGRESSIVE)
 *     .initialize()
 * ```
 */
class BurstModeBuilder(private val context: Context) {

    private var config: BurstModeConfig = BurstModeConfig.DEFAULT
    private var preset: BurstPreset = BurstPreset.BALANCED
    private var profile: BurstProfile = BurstProfile.DEFAULT
    private var enableAutoStart: Boolean = true
    private var enableMetrics: Boolean = true
    private var enableHealthCheck: Boolean = true
    private var healthCheckIntervalMs: Long = 30_000L

    /**
     * 使用预设。
     * 会覆盖之前设置的 config 和 profile（如果预设包含）。
     */
    fun withPreset(preset: BurstPreset): BurstModeBuilder {
        this.preset = preset
        this.config = preset.toConfig()
        if (preset.profile != null) {
            this.profile = preset.profile
        }
        return this
    }

    /**
     * 使用完整配置。
     * 会覆盖预设的配置。
     */
    fun withConfig(config: BurstModeConfig): BurstModeBuilder {
        this.config = config
        this.preset = BurstPreset.CUSTOM
        return this
    }

    /**
     * 在预设配置基础上自定义。
     */
    fun withCustomConfig(customizer: (BurstModeConfig.Builder) -> Unit): BurstModeBuilder {
        val builder = BurstModeConfig.Builder.from(config)
        customizer(builder)
        this.config = builder.build()
        this.preset = BurstPreset.CUSTOM
        return this
    }

    /**
     * 使用性能档位。
     * Profile 控制资源使用激进程度。
     */
    fun withProfile(profile: BurstProfile): BurstModeBuilder {
        this.profile = profile
        return this
    }

    /**
     * 是否自动启动内核（默认 true）。
     * false 时需要手动调用 [BurstMode.resume]。
     */
    fun enableAutoStart(enable: Boolean): BurstModeBuilder {
        this.enableAutoStart = enable
        return this
    }

    /**
     * 是否启用指标收集（默认 true）。
     */
    fun enableMetrics(enable: Boolean): BurstModeBuilder {
        this.enableMetrics = enable
        return this
    }

    /**
     * 是否启用健康检查（默认 true）。
     */
    fun enableHealthCheck(enable: Boolean): BurstModeBuilder {
        this.enableHealthCheck = enable
        return this
    }

    /**
     * 健康检查间隔（默认 30 秒）。
     */
    fun healthCheckInterval(intervalMs: Long): BurstModeBuilder {
        this.healthCheckIntervalMs = intervalMs
        return this
    }

    /**
     * 构建并初始化 [BurstMode] 实例。
     */
    fun initialize(): BurstMode {
        return BurstModeImpl(
            context = context,
            config = config,
            preset = preset,
            profile = profile,
            autoStart = enableAutoStart,
            metricsEnabled = enableMetrics,
            healthCheckEnabled = enableHealthCheck,
            healthCheckIntervalMs = healthCheckIntervalMs
        ).also { impl ->
            impl.initialize()
        }
    }
}
