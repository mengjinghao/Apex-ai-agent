package com.apex.agent.core.config

/**
 * 应用配置键集中注册表
 *
 * 所有应用程序配置键在此统一管理和注册，确保：
 * - 配置路径全局唯一
 * - 默认值集中管理
 * - 类型和校验规则统一维护
 */
object ConfigConstants {

    // ==================== API 相关配置 ====================
    object ApiKeys {
        val baseUrl = ConfigKey(
            path = "api.baseUrl",
            defaultValue = "http://localhost:8080",
            description = "API 基础地址",
            type = ConfigType.STRING,
            required = true
        )
        val timeout = ConfigKey(
            path = "api.timeout",
            defaultValue = "30s",
            description = "API 请求超时时间",
            type = ConfigType.DURATION
        )
        val retryCount = ConfigKey(
            path = "api.retryCount",
            defaultValue = "3",
            description = "API 请求重试次数",
            type = ConfigType.INT
        )
        val apiVersion = ConfigKey(
            path = "api.version",
            defaultValue = "v1",
            description = "API 版本号",
            type = ConfigType.STRING
        )
        val websocketUrl = ConfigKey(
            path = "api.websocketUrl",
            defaultValue = "ws://localhost:8080/ws",
            description = "WebSocket 连接地址",
            type = ConfigType.STRING
        )
        val sslEnabled = ConfigKey(
            path = "api.sslEnabled",
            defaultValue = "false",
            description = "是否启用 SSL/TLS",
            type = ConfigType.BOOLEAN
        )
    }

    // ==================== 模型相关配置 ====================
    object ModelKeys {
        val defaultModel = ConfigKey(
            path = "model.defaultModel",
            defaultValue = "gpt-4",
            description = "默认使用的模型名称",
            type = ConfigType.STRING
        )
        val temperature = ConfigKey(
            path = "model.temperature",
            defaultValue = "0.7",
            description = "模型温度参数，控制输出的随机性 (0.0-2.0)",
            type = ConfigType.DOUBLE
        )
        val maxTokens = ConfigKey(
            path = "model.maxTokens",
            defaultValue = "2048",
            description = "模型单次生成的最大 Token 数",
            type = ConfigType.INT
        )
        val topP = ConfigKey(
            path = "model.topP",
            defaultValue = "1.0",
            description = "核采样参数 Top-P",
            type = ConfigType.DOUBLE
        )
        val frequencyPenalty = ConfigKey(
            path = "model.frequencyPenalty",
            defaultValue = "0.0",
            description = "频率惩罚系数 (-2.0 到 2.0)",
            type = ConfigType.DOUBLE
        )
        val presencePenalty = ConfigKey(
            path = "model.presencePenalty",
            defaultValue = "0.0",
            description = "存在惩罚系数 (-2.0 到 2.0)",
            type = ConfigType.DOUBLE
        )
    }

    // ==================== 缓存相关配置 ====================
    object CacheKeys {
        val memoryCacheSize = ConfigKey(
            path = "cache.memorySize",
            defaultValue = "100MB",
            description = "内存缓存大小上限",
            type = ConfigType.BYTES
        )
        val diskCacheSize = ConfigKey(
            path = "cache.diskSize",
            defaultValue = "1GB",
            description = "磁盘缓存大小上限",
            type = ConfigType.BYTES
        )
        val defaultTtl = ConfigKey(
            path = "cache.defaultTtl",
            defaultValue = "5m",
            description = "缓存默认过期时间",
            type = ConfigType.DURATION
        )
        val cacheEnabled = ConfigKey(
            path = "cache.enabled",
            defaultValue = "true",
            description = "是否启用缓存功能",
            type = ConfigType.BOOLEAN
        )
        val cacheDir = ConfigKey(
            path = "cache.directory",
            defaultValue = "./cache",
            description = "缓存文件存储目录",
            type = ConfigType.STRING
        )
    }

    // ==================== 推理相关配置 ====================
    object ReasoningKeys {
        val defaultStrategy = ConfigKey(
            path = "reasoning.strategy",
            defaultValue = "auto",
            description = "默认推理策略 (auto, chainOfThought, treeOfThought, simulation)",
            type = ConfigType.STRING
        )
        val maxIterations = ConfigKey(
            path = "reasoning.maxIterations",
            defaultValue = "10",
            description = "推理最大迭代次数",
            type = ConfigType.INT
        )
        val complexityThreshold = ConfigKey(
            path = "reasoning.complexityThreshold",
            defaultValue = "0.5",
            description = "问题复杂度阈值，超过该值启用高级推理 (0.0-1.0)",
            type = ConfigType.DOUBLE
        )
        val parallelPaths = ConfigKey(
            path = "reasoning.parallelPaths",
            defaultValue = "3",
            description = "并行推理路径数",
            type = ConfigType.INT
        )
    }

    // ==================== 记忆相关配置 ====================
    object MemoryKeys {
        val vectorDimension = ConfigKey(
            path = "memory.vectorDimension",
            defaultValue = "1536",
            description = "向量嵌入维度",
            type = ConfigType.INT
        )
        val similarityThreshold = ConfigKey(
            path = "memory.similarityThreshold",
            defaultValue = "0.75",
            description = "记忆检索相似度阈值 (0.0-1.0)",
            type = ConfigType.DOUBLE
        )
        val maxEntries = ConfigKey(
            path = "memory.maxEntries",
            defaultValue = "10000",
            description = "记忆库最大条目数",
            type = ConfigType.INT
        )
        val persistenceEnabled = ConfigKey(
            path = "memory.persistence",
            defaultValue = "true",
            description = "是否启用记忆持久化",
            type = ConfigType.BOOLEAN
        )
    }

    // ==================== 网络相关配置 ====================
    object NetworkKeys {
        val connectTimeout = ConfigKey(
            path = "network.connectTimeout",
            defaultValue = "10s",
            description = "连接超时时间",
            type = ConfigType.DURATION
        )
        val readTimeout = ConfigKey(
            path = "network.readTimeout",
            defaultValue = "30s",
            description = "读取超时时间",
            type = ConfigType.DURATION
        )
        val writeTimeout = ConfigKey(
            path = "network.writeTimeout",
            defaultValue = "30s",
            description = "写入超时时间",
            type = ConfigType.DURATION
        )
        val maxConnections = ConfigKey(
            path = "network.maxConnections",
            defaultValue = "100",
            description = "最大连接数",
            type = ConfigType.INT
        )
    }

    // ==================== 日志相关配置 ====================
    object LoggingKeys {
        val logLevel = ConfigKey(
            path = "logging.level",
            defaultValue = "INFO",
            description = "日志级别 (DEBUG, INFO, WARN, ERROR)",
            type = ConfigType.STRING
        )
        val logDir = ConfigKey(
            path = "logging.directory",
            defaultValue = "./logs",
            description = "日志文件输出目录",
            type = ConfigType.STRING
        )
        val maxLogFiles = ConfigKey(
            path = "logging.maxFiles",
            defaultValue = "10",
            description = "日志文件最大保留个数",
            type = ConfigType.INT
        )
        val logFormat = ConfigKey(
            path = "logging.format",
            defaultValue = "text",
            description = "日志输出格式 (text, json)",
            type = ConfigType.STRING
        )
        val remoteLogging = ConfigKey(
            path = "logging.remote",
            defaultValue = "false",
            description = "是否启用远程日志",
            type = ConfigType.BOOLEAN
        )
    }

    // ==================== 安全相关配置 ====================
    object SecurityKeys {
        val encryptionEnabled = ConfigKey(
            path = "security.encryption",
            defaultValue = "false",
            description = "是否启用数据加密",
            type = ConfigType.BOOLEAN,
            secret = true
        )
        val keyStorePath = ConfigKey(
            path = "security.keystore",
            defaultValue = "./keystore.jks",
            description = "密钥库文件路径",
            type = ConfigType.STRING,
            secret = true
        )
        val tokenExpiry = ConfigKey(
            path = "security.tokenExpiry",
            defaultValue = "24h",
            description = "Token 过期时间",
            type = ConfigType.DURATION
        )
        val maxLoginAttempts = ConfigKey(
            path = "security.maxLoginAttempts",
            defaultValue = "5",
            description = "最大登录尝试次数",
            type = ConfigType.INT
        )
    }

    // ==================== 特性开关配置 ====================
    object FeatureKeys {
        val experimentalFeatures = ConfigKey(
            path = "features.experimental",
            defaultValue = "false",
            description = "是否启用实验性功能",
            type = ConfigType.BOOLEAN
        )
        val debugMode = ConfigKey(
            path = "features.debug",
            defaultValue = "false",
            description = "是否启用调试模式",
            type = ConfigType.BOOLEAN
        )
        val telemetryEnabled = ConfigKey(
            path = "features.telemetry",
            defaultValue = "true",
            description = "是否启用遥测数据收集",
            type = ConfigType.BOOLEAN
        )
        val maintenanceMode = ConfigKey(
            path = "features.maintenance",
            defaultValue = "false",
            description = "是否启用维护模式",
            type = ConfigType.BOOLEAN
        )
    }

    /**
     * 获取所有已注册的配置键列表
     */
    fun allKeys(): List<ConfigKey> {
        return listOf(
            ApiKeys.baseUrl, ApiKeys.timeout, ApiKeys.retryCount, ApiKeys.apiVersion,
            ApiKeys.websocketUrl, ApiKeys.sslEnabled,
            ModelKeys.defaultModel, ModelKeys.temperature, ModelKeys.maxTokens, ModelKeys.topP,
            ModelKeys.frequencyPenalty, ModelKeys.presencePenalty,
            CacheKeys.memoryCacheSize, CacheKeys.diskCacheSize, CacheKeys.defaultTtl,
            CacheKeys.cacheEnabled, CacheKeys.cacheDir,
            ReasoningKeys.defaultStrategy, ReasoningKeys.maxIterations,
            ReasoningKeys.complexityThreshold, ReasoningKeys.parallelPaths,
            MemoryKeys.vectorDimension, MemoryKeys.similarityThreshold, MemoryKeys.maxEntries,
            MemoryKeys.persistenceEnabled,
            NetworkKeys.connectTimeout, NetworkKeys.readTimeout, NetworkKeys.writeTimeout,
            NetworkKeys.maxConnections,
            LoggingKeys.logLevel, LoggingKeys.logDir, LoggingKeys.maxLogFiles,
            LoggingKeys.logFormat, LoggingKeys.remoteLogging,
            SecurityKeys.encryptionEnabled, SecurityKeys.keyStorePath, SecurityKeys.tokenExpiry,
            SecurityKeys.maxLoginAttempts,
            FeatureKeys.experimentalFeatures, FeatureKeys.debugMode, FeatureKeys.telemetryEnabled,
            FeatureKeys.maintenanceMode
        )
    }

    val allKeysByPath: Map<String, ConfigKey> by lazy {
        allKeys().associateBy { it.path }
    }
}
