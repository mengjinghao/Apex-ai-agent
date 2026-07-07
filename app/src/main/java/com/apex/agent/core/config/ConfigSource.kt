package com.apex.agent.core.config

/**
 * 配置源接口，代表一个配置的存储来源
 *
 * 配置管理器使用分层架构，多个配置源按优先级叠加以实现配置覆盖：
 * DEFAULT(0) → SYSTEM_PROPERTIES(100) → ENVIRONMENT(200) → CONFIG_FILE(300) → RUNTIME_OVERRIDE(400)
 * 高优先级的配置源会覆盖低优先级源的相同键值
 */
interface ConfigSource : AutoCloseable {

    /**
     * 配置源名称，用于日志和追踪
     */
    val name: String

    /**
     * 配置源优先级，数值越大优先级越高
     */
    val priority: Int

    /**
     * 当前配置源是否支持写入操作
     */
    val isWritable: Boolean

    /**
     * 获取指定配置键对应的值
     * @param key 配置键
     * @return 配置值，若不存在则返回 null
     */
    fun get(key: ConfigKey): String?

    /**
     * 获取配置源中的所有配置项
     * @return 键值对映射表
     */
    fun getAll(): Map<String, String>

    /**
     * 设置指定配置键对应的值
     * @param key 配置键
     * @param value 配置值
     * @throws UnsupportedOperationException 若不支持写入
     */
    fun set(key: ConfigKey, value: String) {
        throw UnsupportedOperationException("$name 配置源不支持写入操作")
    }

    /**
     * 重新加载配置源中的数据
     */
    fun reload() {
        // 默认空实现
    }

    /**
     * 释放配置源持有的资源
     */
    override fun close() {
        // 默认空实现
    }

    /**
     * 检查当前源中是否包含指定键
     */
    fun contains(key: ConfigKey): Boolean = get(key) != null

    /**
     * 获取当前源中指定前缀的所有配置项
     */
    fun getByPrefix(prefix: String): Map<String, String> {
        return getAll().filterKeys { it.startsWith(prefix) }
    }

    /**
     * 当前配置源中的配置项总数
     */
    val size: Int get() = getAll().size

    /**
     * 当前配置源是否为空
     */
    val isEmpty: Boolean get() = size == 0
}
