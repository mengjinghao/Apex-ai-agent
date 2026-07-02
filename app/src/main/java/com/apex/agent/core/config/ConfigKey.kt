package com.apex.agent.core.config

/**
 * 配置类型枚举，定义配置项支持的数据类型
 */
enum class ConfigType {
    STRING, INT, LONG, DOUBLE, BOOLEAN, DURATION, BYTES, LIST, MAP, JSON
}

/**
 * 配置键数据类，描述一个配置项的完整元信息
 *
 * @param path 配置路径，使用点号分隔的层级路径 (如 "api.baseUrl")
 * @param defaultValue 默认值，可为 null
 * @param description 配置项的描述说明
 * @param type 配置值的数据类型
 * @param required 是否为必填项
 * @param secret 是否为敏感信息（输出时会自动脱敏）
 * @param validator 自定义校验函数，返回 true 表示通过
 */
data class ConfigKey(
    val path: String,
    val defaultValue: String? = null,
    val description: String = "",
    val type: ConfigType = ConfigType.STRING,
    val required: Boolean = false,
    val secret: Boolean = false,
    val validator: ((String) -> Boolean)? = null
)

/**
 * 配置路径工具类，提供点号路径的解析、层级关系判断等能力
 */
object ConfigPath {

    private const val SEPARATOR = "."

    /**
     * 将点号路径按层级分割
     * @param path 配置路径
     * @return 层级列表
     */
    fun segments(path: String): List<String> {
        return path.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /**
     * 获取父级路径
     * @param path 配置路径
     * @return 父级路径，若无父级则返回空串
     */
    fun parent(path: String): String {
        val segs = segments(path)
        return if (segs.size <= 1) "" else segs.dropLast(1).joinToString(SEPARATOR)
    }

    /**
     * 获取路径的最后一个段（键名）
     */
    fun leaf(path: String): String {
        val segs = segments(path)
        return segs.lastOrNull() ?: path
    }

    /**
     * 判断 child 是否为 parent 的直接或间接子路径
     */
    fun isChild(parent: String, child: String): Boolean {
        val parentSegs = segments(parent)
        val childSegs = segments(child)
        return childSegs.size > parentSegs.size && childSegs.take(parentSegs.size) == parentSegs
    }

    /**
     * 判断是否是直接子路径（仅相差一级）
     */
    fun isDirectChild(parent: String, child: String): Boolean {
        val parentSegs = segments(parent)
        val childSegs = segments(child)
        return childSegs.size == parentSegs.size + 1 && childSegs.take(parentSegs.size) == parentSegs
    }

    /**
     * 获取指定前缀下的所有子路径
     * @param keys 所有配置键集合
     * @param prefix 前缀
     * @return 匹配前缀的路径列表
     */
    fun children(keys: Collection<String>, prefix: String): List<String> {
        return keys.filter { isChild(prefix, it) }
    }

    /**
     * 构建子路径
     * @param parent 父路径
     * @param leaf 子节点名
     * @return 完整子路径
     */
    fun child(parent: String, leaf: String): String {
        return if (parent.isBlank()) leaf else "$parent$SEPARATOR$leaf"
    }

    /**
     * 获取路径的深度（层级数）
     */
    fun depth(path: String): Int {
        return segments(path).size
    }

    /**
     * 获取路径的根节点（第一段）
     */
    fun root(path: String): String {
        return segments(path).firstOrNull() ?: path
    }

    /**
     * 解析带引号的路径（支持 path.key."has.dots" 格式）
     */
    fun parseQuotedPath(path: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        for (ch in path) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch == '.' && !inQuote -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /**
     * 将路径标准化（去除首尾空白、多余的点号）
     */
    fun normalize(path: String): String {
        return segments(path).joinToString(SEPARATOR)
    }
}

/**
 * 预定义的应用配置键集合
 */
object AppConfigKeys {

    // ==================== API 配置 ====================
    val API_BASE_URL = ConfigKey(
        path = "api.baseUrl",
        defaultValue = "http://localhost:8080",
        description = "API 基础地址",
        type = ConfigType.STRING,
        required = true
    )

    val API_TIMEOUT = ConfigKey(
        path = "api.timeout",
        defaultValue = "30s",
        description = "API 请求超时时间",
        type = ConfigType.DURATION
    )

    val API_RETRY_COUNT = ConfigKey(
        path = "api.retryCount",
        defaultValue = "3",
        description = "API 请求重试次数",
        type = ConfigType.INT
    )

    // ==================== 模型配置 ====================
    val MODEL_TEMPERATURE = ConfigKey(
        path = "model.temperature",
        defaultValue = "0.7",
        description = "模型生成温度参数",
        type = ConfigType.DOUBLE,
        validator = { v -> v.toDoubleOrNull()?.let { it in 0.0..2.0 } ?: false }
    )

    val MAX_TOKENS = ConfigKey(
        path = "model.maxTokens",
        defaultValue = "2048",
        description = "模型最大生成 Token 数",
        type = ConfigType.INT
    )

    val MODEL_TOP_P = ConfigKey(
        path = "model.topP",
        defaultValue = "1.0",
        description = "模型 Top-P 采样参数",
        type = ConfigType.DOUBLE
    )

    // ==================== 日志配置 ====================
    val LOG_LEVEL = ConfigKey(
        path = "logging.level",
        defaultValue = "INFO",
        description = "日志级别 (DEBUG, INFO, WARN, ERROR)",
        type = ConfigType.STRING
    )

    val LOG_DIR = ConfigKey(
        path = "logging.dir",
        defaultValue = "./logs",
        description = "日志文件输出目录",
        type = ConfigType.STRING
    )

    // ==================== 缓存配置 ====================
    val CACHE_TTL = ConfigKey(
        path = "cache.defaultTtl",
        defaultValue = "5m",
        description = "缓存默认过期时间",
        type = ConfigType.DURATION
    )

    val CACHE_ENABLED = ConfigKey(
        path = "cache.enabled",
        defaultValue = "true",
        description = "是否启用缓存",
        type = ConfigType.BOOLEAN
    )

    val CACHE_MEMORY_SIZE = ConfigKey(
        path = "cache.memorySize",
        defaultValue = "100MB",
        description = "内存缓存大小限制",
        type = ConfigType.BYTES
    )

    // ==================== 安全配置 ====================
    val ENCRYPTION_ENABLED = ConfigKey(
        path = "security.encryption",
        defaultValue = "false",
        description = "是否启用加密",
        type = ConfigType.BOOLEAN,
        secret = true
    )

    val API_KEY = ConfigKey(
        path = "security.apiKey",
        defaultValue = null,
        description = "API 密钥",
        type = ConfigType.STRING,
        required = true,
        secret = true
    )

    // ==================== 网络配置 ====================
    val CONNECT_TIMEOUT = ConfigKey(
        path = "network.connectTimeout",
        defaultValue = "10s",
        description = "连接超时时间",
        type = ConfigType.DURATION
    )

    val MAX_CONNECTIONS = ConfigKey(
        path = "network.maxConnections",
        defaultValue = "100",
        description = "最大连接数",
        type = ConfigType.INT
    )

    // ==================== 特性配置 ====================
    val DEBUG_MODE = ConfigKey(
        path = "features.debug",
        defaultValue = "false",
        description = "调试模式开关",
        type = ConfigType.BOOLEAN
    )

    val EXPERIMENTAL_FEATURES = ConfigKey(
        path = "features.experimental",
        defaultValue = "false",
        description = "实验性功能开关",
        type = ConfigType.BOOLEAN
    )

    // ==================== ToM 推理配置 ====================
    val REASONING_STRATEGY = ConfigKey(
        path = "reasoning.strategy",
        defaultValue = "auto",
        description = "推理默认策略",
        type = ConfigType.STRING
    )

    val MAX_ITERATIONS = ConfigKey(
        path = "reasoning.maxIterations",
        defaultValue = "10",
        description = "推理最大迭代次数",
        type = ConfigType.INT
    )

    // ==================== 记忆配置 ====================
    val VECTOR_DIMENSION = ConfigKey(
        path = "memory.vectorDimension",
        defaultValue = "1536",
        description = "向量维度",
        type = ConfigType.INT
    )

    val SIMILARITY_THRESHOLD = ConfigKey(
        path = "memory.similarityThreshold",
        defaultValue = "0.75",
        description = "相似度阈值",
        type = ConfigType.DOUBLE
    )

    /**
     * 返回所有预定义键的列表
     */
    fun allKeys(): List<ConfigKey> = listOf(
        API_BASE_URL, API_TIMEOUT, API_RETRY_COUNT,
        MODEL_TEMPERATURE, MAX_TOKENS, MODEL_TOP_P,
        LOG_LEVEL, LOG_DIR,
        CACHE_TTL, CACHE_ENABLED, CACHE_MEMORY_SIZE,
        ENCRYPTION_ENABLED, API_KEY,
        CONNECT_TIMEOUT, MAX_CONNECTIONS,
        DEBUG_MODE, EXPERIMENTAL_FEATURES,
        REASONING_STRATEGY, MAX_ITERATIONS,
        VECTOR_DIMENSION, SIMILARITY_THRESHOLD
    )

    val allKeysByPath: Map<String, ConfigKey> by lazy {
        allKeys().associateBy { it.path }
    }
}
