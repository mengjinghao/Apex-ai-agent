package com.apex.agent.core.config

import java.util.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 获取配置值，若不存在则返回给定的默认值（不注册到配置系统）
 */
fun ConfigManager.getWithDefault(key: ConfigKey, default: String): String {
    return getString(key) ?: default
}

/**
 * 观察指定配置键的变化，返回一个 Flow
 *
 * 每次值变更时发射新值，首次订阅时发射当前值
 */
fun ConfigManager.watch(key: ConfigKey): Flow<String?> = callbackFlow {
    trySend(getString(key))
    val listener = ConfigChangeListener { changedKey, _, newValue, _ ->
        if (changedKey.path == key.path) {
            trySend(newValue)
        }
    }
    subscribe(key, listener)
    awaitClose { unsubscribe(key, listener) }
}

/**
 * 观察指定配置键的带类型变化，返回指定类型的 Flow
 */
inline fun <reified T> ConfigManager.watchTyped(key: ConfigKey): Flow<T?> = callbackFlow {
    val currentValue = convertValue(getString(key), key.type)
    trySend(currentValue as? T)
    val listener = ConfigChangeListener { changedKey, _, newValue, _ ->
        if (changedKey.path == key.path) {
            val converted = convertValue(newValue, key.type)
            trySend(converted as? T)
        }
    }
    subscribe(key, listener)
    awaitClose { unsubscribe(key, listener) }
}

/**
 * 将当前配置导出为 [Properties] 对象
 */
fun ConfigManager.toProperties(): Properties {
    val props = Properties()
    val snapshot = snapshot()
    for ((key, value) in snapshot) {
        if (value != null) {
            props.setProperty(key, value)
        }
    }
    return props
}

/**
 * 将当前配置导出为普通 Map（排除 null 值）
 */
fun ConfigManager.toMap(): Map<String, String> {
    val snapshot = snapshot()
    return snapshot.filterValues { it != null }.mapValues { it.value!! }
}

/**
 * 返回配置快照的副本，其中所有敏感键的值被脱敏
 */
fun ConfigManager.maskSecrets(): Map<String, String?> {
    val snapshot = snapshot()
    val knownKeys = ConfigConstants.allKeysByPath
    return snapshot.mapValues { (path, value) ->
        val configKey = knownKeys[path]
        if (configKey?.secret == true && value != null) {
            if (value.length <= 4) "****" else "${value.take(2)}****${value.takeLast(2)}"
        } else {
            value
        }
    }
}

/**
 * 打印当前配置摘要到日志
 */
fun ConfigManager.logSummary(): String {
    val sb = StringBuilder()
    sb.appendLine("========== 配置摘要 ==========")
    val snapshot = snapshot()
    val knownKeys = ConfigConstants.allKeysByPath
    val sortedPaths = snapshot.keys.sorted()
    for (path in sortedPaths) {
        val rawValue = snapshot[path]
        val configKey = knownKeys[path]
        val displayValue = if (configKey?.secret == true && rawValue != null) {
            if (rawValue.length <= 4) "****" else "${rawValue.take(2)}****${rawValue.takeLast(2)}"
        } else {
            rawValue ?: "<null>"
        }
        sb.appendLine("  $path = $displayValue")
    }
    sb.appendLine("==============================")
    return sb.toString()
}

/**
 * 使用运行时上下文解析配置键路径中的占位符
 *
 * 支持 ${env.VAR_NAME} 语法引用环境变量
 * 支持 ${system.PROP_KEY} 语法引用系统属性
 */
fun ConfigKey.resolve(context: Map<String, String> = emptyMap()): String {
    var resolved = path
    val envVarPattern = Regex("\\$\\{env\\.([^}]+)}")
    resolved = envVarPattern.replace(resolved) { match ->
        System.getenv(match.groupValues[1]) ?: context[match.groupValues[1]] ?: match.value
    }
    val sysPropPattern = Regex("\\$\\{system\\.([^}]+)}")
    resolved = sysPropPattern.replace(resolved) { match ->
        System.getProperty(match.groupValues[1]) ?: match.value
    }
    val ctxPattern = Regex("\\$\\{([^}]+)}")
    resolved = ctxPattern.replace(resolved) { match ->
        context[match.groupValues[1]] ?: match.value
    }
    return resolved
}

/**
 * 将字符串转换为配置键，自动推断类型
 *
 * 推断规则：
 * - 以 "true"/"false" 开头（忽略大小写） -> BOOLEAN
 * - 包含 "." 或路径中有关键字暗示
 * - 默认类型为 STRING
 */
fun String.toConfigKey(
    description: String = "",
    defaultValue: String? = null,
    required: Boolean = false
): ConfigKey {
    val inferredType = when {
        this.endsWith("url", ignoreCase = true) ||
        this.endsWith("uri", ignoreCase = true) ||
        this.endsWith("host", ignoreCase = true) ||
        this.endsWith("path", ignoreCase = true) -> ConfigType.STRING
        this.endsWith("timeout", ignoreCase = true) ||
        this.endsWith("ttl", ignoreCase = true) ||
        this.endsWith("expiry", ignoreCase = true) ||
        this.endsWith("duration", ignoreCase = true) -> ConfigType.DURATION
        this.endsWith("count", ignoreCase = true) ||
        this.endsWith("port", ignoreCase = true) ||
        this.endsWith("size", ignoreCase = true) ||
        this.endsWith("max", ignoreCase = true) ||
        this.endsWith("attempts", ignoreCase = true) -> ConfigType.INT
        this.endsWith("enabled", ignoreCase = true) ||
        this.endsWith("active", ignoreCase = true) ||
        this.startsWith("is", ignoreCase = true) -> ConfigType.BOOLEAN
        this.endsWith("temperature", ignoreCase = true) ||
        this.endsWith("threshold", ignoreCase = true) ||
        this.endsWith("rate", ignoreCase = true) ||
        this.endsWith("factor", ignoreCase = true) -> ConfigType.DOUBLE
        this.endsWith("keys", ignoreCase = true) ||
        this.endsWith("list", ignoreCase = true) ||
        this.endsWith("items", ignoreCase = true) -> ConfigType.LIST
        else -> ConfigType.STRING
    }
    return ConfigKey(
        path = this,
        defaultValue = defaultValue,
        description = description,
        type = inferredType,
        required = required
    )
}

/**
 * 根据配置类型将字符串值转换为对应的 Kotlin 类型
 */
internal fun convertValue(value: String?, type: ConfigType): Any? {
    if (value == null) return null
    return try {
        when (type) {
            ConfigType.STRING -> value
            ConfigType.INT -> value.toInt()
            ConfigType.LONG -> value.toLong()
            ConfigType.DOUBLE -> value.toDouble()
            ConfigType.BOOLEAN -> value.toBooleanStrictOrNull() ?: value.equals("1", ignoreCase = true) || value.equals("yes", ignoreCase = true)
            ConfigType.DURATION -> parseDuration(value)
            ConfigType.BYTES -> parseBytes(value)
            ConfigType.LIST -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            ConfigType.MAP -> parseMap(value)
            ConfigType.JSON -> value // 原样返回 JSON 字符串
        }
    } catch (e: Exception) {
        value // 转换失败返回原始字符串
    }
}

private fun parseDuration(value: String): Long {
    val regex = Regex("^(\\d+)(ns|us|ms|s|m|h|d)$")
    val match = regex.find(value.trim()) ?: throw IllegalArgumentException("无效的持续时间格式: $value")
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "ns" -> amount
        "us" -> amount * 1000
        "ms" -> amount * 1_000_000
        "s" -> amount * 1_000_000_000
        "m" -> amount * 60_000_000_000
        "h" -> amount * 3_600_000_000_000
        "d" -> amount * 86_400_000_000_000
        else -> throw IllegalArgumentException("未知的时间单位: ${match.groupValues[2]}")
    }
}

private fun parseBytes(value: String): Long {
    val regex = Regex("^(\\d+)(B|KB|MB|GB|TB)$", RegexOption.IGNORE_CASE)
    val match = regex.find(value.trim()) ?: throw IllegalArgumentException("无效的字节大小格式: $value")
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2].uppercase()) {
        "B" -> amount
        "KB" -> amount * 1024
        "MB" -> amount * 1024 * 1024
        "GB" -> amount * 1024 * 1024 * 1024
        "TB" -> amount * 1024L * 1024 * 1024 * 1024
        else -> throw IllegalArgumentException("未知的字节单位: ${match.groupValues[2]}")
    }
}

private fun parseMap(value: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val pairs = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    for (pair in pairs) {
        val eqIdx = pair.indexOf('=')
        if (eqIdx > 0) {
            result[pair.substring(0, eqIdx).trim()] = pair.substring(eqIdx + 1).trim()
        }
    }
    return result
}
