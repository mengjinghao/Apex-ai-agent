package com.apex.agent.kernel.burst.engine.version

/**
 * E15: 内核版本管理
 *
 * 内核版本 + 兼容性：
 * - 语义化版本
 * - 兼容性检查
 * - 功能开关（feature flags）
 * - 版本历史
 */
class KernelVersionManager {

    data class KernelVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val build: Int = 0,
        val suffix: String = ""  // e.g. "beta", "rc1"
    ) : Comparable<KernelVersion> {
        override fun compareTo(other: KernelVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            return build.compareTo(other.build)
        }

        override fun toString() = "$major.$minor.$patch" +
            (if (build > 0) ".$build" else "") +
            (if (suffix.isNotBlank()) "-$suffix" else "")

        fun isStable() = suffix.isBlank() || suffix.startsWith("release")
        fun isBeta() = suffix.contains("beta", ignoreCase = true)
        fun isRC() = suffix.contains("rc", ignoreCase = true)
    }

    data class FeatureFlag(
        val name: String,
        val enabled: Boolean,
        val minVersion: KernelVersion?,
        val description: String,
        val rolloutPercentage: Int = 100
    )

    data class CompatibilityMatrix(
        val version: KernelVersion,
        val minAndroidVersion: Int,
        val minSdkVersion: Int,
        val supportedProviders: Set<String>,
        val deprecatedFeatures: Set<String>,
        val newFeatures: Set<String>
    )

    private val currentVersion = KernelVersion(2, 0, 0, 0, "release")
    private val featureFlags = mutableMapOf<String, FeatureFlag>()
    private val compatibilityHistory = mutableListOf<CompatibilityMatrix>()
    private val currentMatrix = CompatibilityMatrix(
        version = currentVersion,
        minAndroidVersion = 26,  // Android 8.0
        minSdkVersion = 26,
        supportedProviders = setOf("deepseek", "openai", "claude", "gemini", "ollama"),
        deprecatedFeatures = setOf("local_llama", "mnn", "ncnn", "sherpa"),
        newFeatures = setOf("enhanced_burst_mode", "pipeline_engine", "rage_meter", "parallel_universe")
    )

    init {
        // 注册 feature flags
        registerFeatureFlag(FeatureFlag("enhanced_burst_mode", true, KernelVersion(2, 0, 0), "增强狂暴模式"))
        registerFeatureFlag(FeatureFlag("pipeline_engine", true, KernelVersion(2, 0, 0), "流水线引擎"))
        registerFeatureFlag(FeatureFlag("rage_meter", true, KernelVersion(2, 0, 0), "暴怒值系统"))
        registerFeatureFlag(FeatureFlag("parallel_universe", false, KernelVersion(2, 1, 0), "并行宇宙探索"))
        registerFeatureFlag(FeatureFlag("evolution_engine", false, KernelVersion(2, 1, 0), "进化引擎"))
        registerFeatureFlag(FeatureFlag("time_travel", false, KernelVersion(2, 2, 0), "时间旅行调试"))
        registerFeatureFlag(FeatureFlag("auto_recovery", true, KernelVersion(2, 0, 0), "自动恢复"))
        registerFeatureFlag(FeatureFlag("predictive_loading", false, KernelVersion(2, 1, 0), "预测性加载", 50))
    }

    fun getCurrentVersion(): KernelVersion = currentVersion

    fun isFeatureEnabled(name: String): Boolean {
        val flag = featureFlags[name] ?: return false
        if (!flag.enabled) return false
        if (flag.minVersion != null && currentVersion < flag.minVersion) return false
        if (flag.rolloutPercentage < 100) {
            return (hashOfCurrentSession() % 100) < flag.rolloutPercentage
        }
        return true
    }

    fun registerFeatureFlag(flag: FeatureFlag) {
        featureFlags[flag.name] = flag
    }

    fun setFeatureEnabled(name: String, enabled: Boolean) {
        featureFlags[name]?.let { featureFlags[name] = it.copy(enabled = enabled) }
    }

    fun getAllFeatureFlags(): List<FeatureFlag> = featureFlags.values.toList()

    fun checkCompatibility(androidVersion: Int, sdkVersion: Int, provider: String): CompatibilityResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (androidVersion < currentMatrix.minAndroidVersion) {
            issues.add("Android 版本过低: 需要 ${currentMatrix.minAndroidVersion}, 实际 $androidVersion")
        }
        if (sdkVersion < currentMatrix.minSdkVersion) {
            issues.add("SDK 版本过低: 需要 ${currentMatrix.minSdkVersion}, 实际 $sdkVersion")
        }
        if (provider !in currentMatrix.supportedProviders) {
            issues.add("不支持的 Provider: $provider")
        }

        return CompatibilityResult(issues.isEmpty(), issues, warnings, currentMatrix)
    }

    data class CompatibilityResult(
        val compatible: Boolean,
        val issues: List<String>,
        val warnings: List<String>,
        val matrix: CompatibilityMatrix
    )

    fun getDeprecatedFeatures(): Set<String> = currentMatrix.deprecatedFeatures
    fun getNewFeatures(): Set<String> = currentMatrix.newFeatures
    fun getSupportedProviders(): Set<String> = currentMatrix.supportedProviders

    fun generateVersionInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 内核版本信息 ═══")
        sb.appendLine("版本: $currentVersion")
        sb.appendLine("Android 最低: ${currentMatrix.minAndroidVersion}")
        sb.appendLine("SDK 最低: ${currentMatrix.minSdkVersion}")
        sb.appendLine("支持 Provider: ${currentMatrix.supportedProviders}")
        sb.appendLine()
        sb.appendLine("Feature Flags:")
        featureFlags.values.forEach { flag ->
            val enabled = isFeatureEnabled(flag.name)
            val icon = if (enabled) "✓" else "✗"
            sb.appendLine("  $icon ${flag.name} (v${flag.minVersion ?: "any"}) ${flag.description}")
        }
        sb.appendLine()
        sb.appendLine("已弃用: ${currentMatrix.deprecatedFeatures}")
        sb.appendLine("新功能: ${currentMatrix.newFeatures}")
        sb.appendLine("═══════════════════")
        return sb.toString()
    }

    private fun hashOfCurrentSession(): Int {
        return (System.currentTimeMillis() % 100).toInt()
    }
}
