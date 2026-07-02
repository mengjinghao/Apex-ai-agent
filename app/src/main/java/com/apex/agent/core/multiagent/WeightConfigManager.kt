package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap

class WeightConfigManager {

    data class WeightConfig(
        val name: String,
        val description: String,
        val weights: Map<String, Double>,
        val isDefault: Boolean = false
    )

    data class UserWeightConfig(
        val userId: String,
        val configName: String,
        val weights: Map<String, Double>,
        val lastUpdated: Long
    )

    private val defaultWeights = mapOf(
        "capability" to 0.4,
        "performance" to 0.3,
        "resource" to 0.2,
        "skill" to 0.1
    )

    private val predefinedConfigs = ConcurrentHashMap<String, WeightConfig>()
    private val userConfigs = ConcurrentHashMap<String, UserWeightConfig>()

    init {
        // 初始化预定义配置
        initPredefinedConfigs()
    }

    private fun initPredefinedConfigs() {
        predefinedConfigs["balanced"] = WeightConfig(
            name = "balanced",
            description = "平衡配置，综合考虑各因�?
            weights = defaultWeights,
            isDefault = true
        )

        predefinedConfigs["performance-focused"] = WeightConfig(
            name = "performance-focused",
            description = "性能优先，重视历史执行表�?
            weights = mapOf(
                "capability" to 0.3,
                "performance" to 0.5,
                "resource" to 0.1,
                "skill" to 0.1
            )
        )

        predefinedConfigs["capability-focused"] = WeightConfig(
            name = "capability-focused",
            description = "能力优先，重视Agent能力匹配",
            weights = mapOf(
                "capability" to 0.6,
                "performance" to 0.2,
                "resource" to 0.1,
                "skill" to 0.1
            )
        )

        predefinedConfigs["resource-optimized"] = WeightConfig(
            name = "resource-optimized",
            description = "资源优化，重视资源利用效�?
            weights = mapOf(
                "capability" to 0.2,
                "performance" to 0.3,
                "resource" to 0.4,
                "skill" to 0.1
            )
        )

        predefinedConfigs["skill-matched"] = WeightConfig(
            name = "skill-matched",
            description = "技能匹配，重视技能覆盖度",
            weights = mapOf(
                "capability" to 0.3,
                "performance" to 0.2,
                "resource" to 0.2,
                "skill" to 0.3
            )
        )
    }

    fun getPredefinedConfigs(): List<WeightConfig> {
        return predefinedConfigs.values.toList()
    }

    fun getPredefinedConfig(name: String): WeightConfig? {
        return predefinedConfigs[name]
    }

    fun createUserConfig(userId: String, configName: String, weights: Map<String, Double>): UserWeightConfig {
        val config = UserWeightConfig(
            userId = userId,
            configName = configName,
            weights = normalizeWeights(weights),
            lastUpdated = System.currentTimeMillis()
        )
        userConfigs["${userId}:${configName}"] = config
        return config
    }

    fun getUserConfig(userId: String, configName: String): UserWeightConfig? {
        return userConfigs["${userId}:${configName}"]
    }

    fun getUserConfigs(userId: String): List<UserWeightConfig> {
        return userConfigs.values.filter { it.userId == userId }
    }

    fun updateUserConfig(userId: String, configName: String, weights: Map<String, Double>): Boolean {
        val key = "${userId}:${configName}"
        val existingConfig = userConfigs[key]
        if (existingConfig != null) {
            val updatedConfig = existingConfig.copy(
                weights = normalizeWeights(weights),
                lastUpdated = System.currentTimeMillis()
            )
            userConfigs[key] = updatedConfig
            return true
        }
        return false
    }

    fun deleteUserConfig(userId: String, configName: String): Boolean {
        return userConfigs.remove("${userId}:${configName}") != null
    }

    fun getDefaultWeights(): Map<String, Double> {
        return defaultWeights
    }

    fun getWeightsForUser(userId: String, configName: String? = null): Map<String, Double> {
        if (configName != null) {
            val userConfig = getUserConfig(userId, configName)
            if (userConfig != null) {
                return userConfig.weights
            }
        }
        
        // 如果用户配置不存在，返回默认配置
        return defaultWeights
    }

    private fun normalizeWeights(weights: Map<String, Double>): Map<String, Double> {
        val total = weights.values.sum()
        if (total == 0.0) {
            return defaultWeights
        }
        return weights.mapValues { (_, value) -> value / total }
    }

    fun validateWeights(weights: Map<String, Double>): Boolean {
        val requiredKeys = setOf("capability", "performance", "resource", "skill")
        val hasAllKeys = requiredKeys.all { weights.containsKey(it) }
        val allPositive = weights.values.all { it >= 0 }
        return hasAllKeys && allPositive
    }

    fun exportConfig(config: WeightConfig): String {
        val sb = StringBuilder()
        sb.appendLine("配置名称: ${config.name}")
        sb.appendLine("描述: ${config.description}")
        sb.appendLine("是否默认: ${config.isDefault}")
        sb.appendLine("权重:")
        config.weights.forEach { (key, value) ->
            sb.appendLine("  ${key}: ${String.format("%.2f", value)}")
        }
        return sb.toString()
    }

    fun importConfig(name: String, description: String, weights: Map<String, Double>): WeightConfig? {
        if (!validateWeights(weights)) {
            return null
        }
        
        val config = WeightConfig(
            name = name,
            description = description,
            weights = normalizeWeights(weights)
        )
        predefinedConfigs[name] = config
        return config
    }

    fun resetToDefaults() {
        predefinedConfigs.clear()
        userConfigs.clear()
        initPredefinedConfigs()
    }

    fun getConfigCount(): Pair<Int, Int> {
        return Pair(predefinedConfigs.size, userConfigs.size)
    }

    // 为特定任务类型推荐权重配�?   fun recommendConfigForTaskType(taskType: String): String {
        return when (taskType) {
            "coding", "development" -> "capability-focused"
            "writing", "content" -> "skill-matched"
            "data", "analysis" -> "resource-optimized"
            "testing", "qa" -> "performance-focused"
            else -> "balanced"
        }
    }
}
