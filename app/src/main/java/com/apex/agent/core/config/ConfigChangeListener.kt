package com.apex.agent.core.config

import java.time.Instant

/**
 * 配置变更事件，包含完整的变更元数据
 *
 * @param key 发生变更的配置键
 * @param oldValue 变更前的值
 * @param newValue 变更后的值
 * @param source 变更来源描述
 * @param timestamp 变更发生的时间戳
 * @param changedBy 变更发起者标识
 */
data class ConfigChangeEvent(
    val key: ConfigKey,
    val oldValue: String?,
    val newValue: String?,
    val source: String,
    val timestamp: Instant = Instant.now(),
    val changedBy: String = "system"
)

/**
 * 配置变更监听器接口
 *
 * 当配置项发生变化时，注册的监听器将被通知。
 * 监听器可以通过 [ConfigManager.subscribe] 进行注册。
 */
fun interface ConfigChangeListener {

    /**
     * 配置项变更时的回调方法
     *
     * @param key 发生变更的配置键
     * @param oldValue 变更前的值，首次设置时为 null
     * @param newValue 变更后的值，被重置为默认值时可能为 null
     * @param source 变更来源描述（如 "runtime", "file", "env" 等）
     */
    fun onConfigChanged(key: ConfigKey, oldValue: String?, newValue: String?, source: String)

    /**
     * 接收完整事件对象的回调，默认实现委托给 [onConfigChanged]
     */
    fun onEvent(event: ConfigChangeEvent) {
        onConfigChanged(event.key, event.oldValue, event.newValue, event.source)
    }
}
