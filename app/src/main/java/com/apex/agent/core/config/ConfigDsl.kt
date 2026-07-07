package com.apex.agent.core.config

/**
 * Kotlin DSL 用于声明式配置定义
 *
 * 使用方式：
 * ```
 * val myConfig = config {
 *     group("api") {
 *         key("baseUrl", "http://localhost:8080") {
 *             description = "API 基础地址"
 *             type = ConfigType.STRING
 *             required = true
 *             validator = UrlValidator()
 *         }
 *         key("timeout", "30s") {
 *             description = "请求超时时间"
 *             type = ConfigType.DURATION
 *         }
 *     }
 * }
 * ```
 */

/**
 * DSL 构建上下文，保存组前缀和配置键列表
 */
class ConfigDslContext {
    internal val keys = mutableListOf<ConfigKey>()
    private var groupPrefix: String = ""

    /**
     * 定义一个配置组，组内所有键共享该前缀
     */
    fun group(prefix: String, block: ConfigGroupContext.() -> Unit) {
        val previousPrefix = groupPrefix
        groupPrefix = if (groupPrefix.isBlank()) prefix else "$groupPrefix.$prefix"
        val groupContext = ConfigGroupContext(groupPrefix)
        groupContext.block()
        keys.addAll(groupContext.keys)
        groupPrefix = previousPrefix
    }

    /**
     * 在当前组（或根级别）定义一个配置键
     */
    fun key(path: String, default: String? = null, block: ConfigKeyDslContext.() -> Unit = {}) {
        val fullPath = if (groupPrefix.isBlank()) path else "$groupPrefix.$path"
        val dslContext = ConfigKeyDslContext(fullPath, default)
        dslContext.block()
        keys.add(dslContext.toConfigKey())
    }
}

/**
 * 配置组 DSL 上下文
 */
class ConfigGroupContext(internal val prefix: String) {
    internal val keys = mutableListOf<ConfigKey>()

    fun key(path: String, default: String? = null, block: ConfigKeyDslContext.() -> Unit = {}) {
        val fullPath = "$prefix.$path"
        val dslContext = ConfigKeyDslContext(fullPath, default)
        dslContext.block()
        keys.add(dslContext.toConfigKey())
    }
}

/**
 * 配置键 DSL 上下文，用于链式设置属性
 */
class ConfigKeyDslContext(
    private val path: String,
    private val default: String?
) {
    var description: String = ""
    var type: ConfigType = ConfigType.STRING
    var required: Boolean = false
    var secret: Boolean = false
    var validator: ((String) -> Boolean)? = null

    internal fun toConfigKey(): ConfigKey {
        return ConfigKey(
            path = path,
            defaultValue = default,
            description = description,
            type = type,
            required = required,
            secret = secret,
            validator = validator
        )
    }
}

/**
 * DSL 入口函数，创建一组配置定义
 *
 * @param block DSL 构建块
 * @return 配置键列表
 */
fun config(block: ConfigDslContext.() -> Unit): List<ConfigKey> {
    val context = ConfigDslContext()
    context.block()
    return context.keys.toList()
}

/**
 * 使用 DSL 注册配置到 [ConfigManager]
 *
 * @param manager 配置管理器
 * @param block DSL 构建块
 */
fun ConfigManager.dsl(block: ConfigDslContext.() -> Unit) {
    val keys = config(block)
    for (key in keys) {
        registerKey(key)
    }
}
