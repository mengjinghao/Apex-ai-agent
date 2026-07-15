package com.apex.agent.core.config

import java.util.concurrent.ConcurrentHashMap

/**
 * 配置档案数据类，定义一组命名的配置覆盖集合
 *
 * @param name 档案名称
 * @param description 档案描述
 * @param overrides 对基础配置的覆盖值
 */
data class ConfigProfile(
    val name: String,
    val description: String = "",
    val overrides: Map<String, String> = emptyMap()
) {
    /**
     * 检查此档案是否为默认档案
     */
    val isDefault: Boolean get() = name == DEFAULT_PROFILE_NAME

    companion object {
        const val DEFAULT_PROFILE_NAME = "default"
    }
}

/**
 * 配置档案管理器，负责创建、激活、导入导出配置档案
 */
class ConfigProfileManager(
    private val configManager: ConfigManager
) {
    private val profiles = ConcurrentHashMap<String, ConfigProfile>()
        private val activeProfileName = java.util.concurrent.atomic.AtomicReference<String?>(null)

    init {
        registerPredefinedProfiles()
    }

    /**
     * 创建新配置档案
     * @param name 档案名称
     * @param description 描述
     * @return 创建的档案
     * @throws IllegalArgumentException 如果名称已存在
     */
    fun createProfile(name: String, description: String = ""): ConfigProfile {
        if (profiles.containsKey(name)) {
            throw IllegalArgumentException("配置档案已存在: $name")
        }
        val profile = ConfigProfile(name = name, description = description)
        profiles[name] = profile
        return profile
    }

    /**
     * 激活指定名称的配置档案
     *
     * 激活会将档案中的所有覆盖值应用到配置管理器。
     * 先前的活跃档案会被停用。
     */
    fun activateProfile(name: String) {
        val profile = profiles[name] ?: throw IllegalArgumentException("配置档案不存在: $name")
        val previous = activeProfileName.getAndSet(name)
        if (previous != null && previous != name) {
            deactivateProfileInternal(previous)
        }
        for ((path, value) in profile.overrides) {
            val key = findConfigKey(path)
        if (key != null) {
                configManager.set(key, value, "profile:$name")
            }
        }
    }

    /**
     * 停用当前活跃的配置档案
     */
    fun deactivateProfile() {
        val name = activeProfileName.getAndSet(null) ?: return
        deactivateProfileInternal(name)
    }
        private fun deactivateProfileInternal(name: String) {
        val profile = profiles[name] ?: return
        for (path in profile.overrides.keys) {
            val key = findConfigKey(path)
        if (key != null) {
                configManager.reset(key)
            }
        }
    }

    /**
     * 列出所有已注册的配置档案
     */
    fun listProfiles(): List<ConfigProfile> = profiles.values.toList()

    /**
     * 删除指定配置档案
     * @throws IllegalArgumentException 如果档案不存在或正在激活中
     */
    fun deleteProfile(name: String) {
        if (name == ConfigProfile.DEFAULT_PROFILE_NAME) {
            throw IllegalArgumentException("不能删除默认档案")
        }
        if (activeProfileName.get() == name) {
            deactivateProfile()
        }
        profiles.remove(name) ?: throw IllegalArgumentException("配置档案不存在: $name")
    }

    /**
     * 导出配置档案为可序列化的字符串
     * @param name 档案名称
     * @param format 导出格式 (properties 或 json)
     */
    fun exportProfile(name: String, format: String = "properties"): String {
        val profile = profiles[name] ?: throw IllegalArgumentException("配置档案不存在: $name")
        val serializer: ConfigSerializer = when (format.lowercase()) {
            "properties" -> PropertiesSerializer()
            "json" -> JsonConfigSerializer()
            "yaml" -> YamlConfigSerializer()
            "flat" -> FlatConfigSerializer()
            else -> throw IllegalArgumentException("不支持的导出格式: $format")
        }
        val header = "# 配置档案: ${profile.name}\n# ${profile.description}\n"
        return header + serializer.serialize(profile.overrides)
    }

    /**
     * 从字符串导入配置档案
     * @param content 配置内容
     * @param format 格式
     * @param name 档案名称（如果为 null 则自动生成）
     * @return 导入的档案
     */
    fun importProfile(content: String, format: String = "properties", name: String? = null): ConfigProfile {
        val serializer: ConfigSerializer = when (format.lowercase()) {
            "properties" -> PropertiesSerializer()
            "json" -> JsonConfigSerializer()
            "yaml" -> YamlConfigSerializer()
            "flat" -> FlatConfigSerializer()
            else -> throw IllegalArgumentException("不支持的导入格式: $format")
        }
        val overrides = serializer.deserialize(content)
        val profileName = name ?: "imported_${java.util.UUID.randomUUID().toString().take(8)}"
        val profile = ConfigProfile(name = profileName, description = "从 $format 格式导入", overrides = overrides)
        profiles[profileName] = profile
        return profile
    }

    /**
     * 获取当前活跃的配置档案
     */
    fun getActiveProfile(): ConfigProfile? {
        val name = activeProfileName.get() ?: return null
        return profiles[name]
    }

    /**
     * 获取两个配置档案之间的差异
     *
     * @return 键到 (原值, 新值) 的映射
     */
    fun getDifference(fromProfile: String, toProfile: String): Map<String, Pair<String?, String?>> {
        val from = profiles[fromProfile] ?: throw IllegalArgumentException("源档案不存在: $fromProfile")
        val to = profiles[toProfile] ?: throw IllegalArgumentException("目标档案不存在: $toProfile")
        val allKeys = (from.overrides.keys + to.overrides.keys).toSet()
        val diff = mutableMapOf<String, Pair<String?, String?>>()
        for (key in allKeys) {
            val fromVal = from.overrides[key]
            val toVal = to.overrides[key]
            if (fromVal != toVal) {
                diff[key] = Pair(fromVal, toVal)
            }
        }
        return diff
    }

    /**
     * 将配置档案转换为配置快照
     */
    fun profileToSnapshot(profile: ConfigProfile): Map<String, String?> {
        return profile.overrides.mapValues { (_, v) -> v as String? }
    }
        private fun findConfigKey(path: String): ConfigKey? {
        return ConfigConstants.allKeysByPath[path] ?: AppConfigKeys.allKeysByPath[path]
    }
        private fun registerPredefinedProfiles() {
        profiles[ConfigProfile.DEFAULT_PROFILE_NAME] = ConfigProfile(
            name = ConfigProfile.DEFAULT_PROFILE_NAME,
            description = "默认配置档案 - 使用所有配置项的默认值"
        )
        profiles[DefaultProfile.name] = DefaultProfile
        profiles[DevelopmentProfile.name] = DevelopmentProfile
        profiles[ProductionProfile.name] = ProductionProfile
        profiles[TestingProfile.name] = TestingProfile
        profiles[PerformanceProfile.name] = PerformanceProfile
    }

    /**
     * 默认档案 - 等同于 Default
     */
    val DefaultProfile = ConfigProfile(
        name = "default",
        description = "默认配置，使用各配置项的内置默认值"
    )

    /**
     * 开发档案 - 适用于本地开发环境
     */
    val DevelopmentProfile = ConfigProfile(
        name = "development",
        description = "开发环境配置 - 启用调试、详细日志、本地服务地址",
        overrides = mapOf(
            "logging.level" to "DEBUG",
            "features.debug" to "true",
            "features.experimental" to "true",
            "api.baseUrl" to "http://localhost:8080",
            "cache.enabled" to "false"
        )
    )

    /**
     * 生产档案 - 适用于生产环境
     */
    val ProductionProfile = ConfigProfile(
        name = "production",
        description = "生产环境配置 - 生产地址、安全增强、性能优化",
        overrides = mapOf(
            "logging.level" to "WARN",
            "features.debug" to "false",
            "features.experimental" to "false",
            "cache.memorySize" to "500MB",
            "cache.diskSize" to "5GB",
            "network.maxConnections" to "200"
        )
    )

    /**
     * 测试档案 - 适用于单元测试和集成测试
     */
    val TestingProfile = ConfigProfile(
        name = "testing",
        description = "测试环境配置 - 模拟服务、快速超时、禁用缓存",
        overrides = mapOf(
            "api.baseUrl" to "http://test.local",
            "api.timeout" to "5s",
            "cache.enabled" to "false",
            "logging.level" to "ERROR",
            "features.debug" to "true",
            "network.maxConnections" to "10"
        )
    )

    /**
     * 性能档案 - 适用于性能测试和基准测试
     */
    val PerformanceProfile = ConfigProfile(
        name = "performance",
        description = "性能测试配置 - 大缓存、高并发、最小日志",
        overrides = mapOf(
            "cache.memorySize" to "1GB",
            "cache.diskSize" to "10GB",
            "network.maxConnections" to "500",
            "logging.level" to "ERROR",
            "logging.remote" to "false",
            "features.telemetry" to "false",
            "reasoning.parallelPaths" to "5"
        )
    )
}
