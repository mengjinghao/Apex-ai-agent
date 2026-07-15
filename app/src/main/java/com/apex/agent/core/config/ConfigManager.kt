package com.apex.agent.core.config

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 配置管理器 - 核心配置管理类
 *
 * 采用分层架构管理配置，从高到低优先级：
 * RUNTIME_OVERRIDE(400) → CONFIG_FILE(300) → ENVIRONMENT(200) → SYSTEM_PROPERTIES(100) → DEFAULT(0)
 *
 * 特性：
 * - 类型安全的配置读取（自动类型转换）
 * - 分层配置源（默认 → 系统属性 → 环境变量 → 配置文件 → 运行时覆盖）
 * - 变更通知（支持精确键和 glob 模式订阅）
 * - 配置校验引擎
 * - 快照与恢复
 * - 差异计算
 * - 线程安全（ReadWriteLock）
 * - 变更通知合并（合并快速连续的变更）
 */
class ConfigManager(
    private val validationEngine: ValidationEngine = ValidationEngine()
) : AutoCloseable {

    // ==================== 内部状态 ====================

    /** 读写锁保证线程安全 */
    private val lock = ReentrantReadWriteLock()

    /** 已注册的配置键，按路径索引 */
    private val registeredKeys = ConcurrentHashMap<String, ConfigKey>()

    /** 分层配置源 */
    private val providers = sortedMapOf<Int, MutableList<ConfigSource>>(compareByDescending { it })

    /** 默认值源（优先级 0） */
    private val defaultProvider = MemoryConfigProvider().apply {
        // 在内部修改 priority 为 0
    }

    /** 变更监听器 */
    private val listeners = CopyOnWriteArrayList<ConfigChangeListener>()

    /** 模式监听器（使用 glob 模式匹配路径） */
    private val patternListeners = CopyOnWriteArrayList<Pair<Regex, ConfigChangeListener>>()

    /** 变更合并窗口（毫秒） */
    private var coalesceWindowMs: Long = 100

    /** 最近变更的时间戳 */
    private val lastChangeTimes = ConcurrentHashMap<String, Long>()

    /** 等待合并的变更 */
    private val pendingChanges = ConcurrentHashMap<String, PendingChange>()
        private data class PendingChange(
        val key: ConfigKey,
        val oldValue: String?,
        val newValue: String?,
        val source: String
    )

    init {
        defaultProvider.priority // 确保初始化
        addProvider(0, defaultProvider)
    }

    // ==================== 配置源管理 ====================

    /**
     * 添加配置源到指定优先级层级
     */
    fun addProvider(priority: Int, source: ConfigSource) {
        providers.getOrPut(priority) { mutableListOf() }.add(source)
    }

    /**
     * 注册配置键到管理器
     */
    fun registerKey(key: ConfigKey) {
        registeredKeys[key.path] = key
        if (key.defaultValue != null) {
            val defaultKey = ConfigKey(
                path = key.path,
                defaultValue = key.defaultValue,
                description = key.description,
                type = key.type,
                required = key.required,
                secret = key.secret,
                validator = key.validator
            )
            setWithSource(defaultKey, key.defaultValue, "default")
        }
    }

    /**
     * 批量注册配置键
     */
    fun registerKeys(keys: List<ConfigKey>) {
        for (key in keys) {
            registerKey(key)
        }
    }

    // ==================== 核心读取方法 ====================

    /**
     * 获取指定配置键的字符串值
     * 按 RUNTIME_OVERRIDE → CONFIG_FILE → ENVIRONMENT → SYSTEM_PROPERTIES → DEFAULT 顺序查找
     */
    fun getString(key: ConfigKey): String? {
        return getValue(key)
    }

    /**
     * 获取指定配置键的整数值
     * @throws NumberFormatException 如果值不是有效整数
     */
    fun getInt(key: ConfigKey): Int {
        val value = requireNotNull(getString(key)) { "配置项 [${key.path}] 未设置" }
        return value.toInt()
    }

    /**
     * 获取指定配置键的长整型值
     */
    fun getLong(key: ConfigKey): Long {
        val value = requireNotNull(getString(key)) { "配置项 [${key.path}] 未设置" }
        return value.toLong()
    }

    /**
     * 获取指定配置键的双精度浮点值
     */
    fun getDouble(key: ConfigKey): Double {
        val value = requireNotNull(getString(key)) { "配置项 [${key.path}] 未设置" }
        return value.toDouble()
    }

    /**
     * 获取指定配置键的布尔值
     */
    fun getBoolean(key: ConfigKey): Boolean {
        val value = getString(key) ?: return false
        return value.toBooleanStrictOrNull() ?: value.equals("1", ignoreCase = true) || value.equals("yes", ignoreCase = true)
    }

    /**
     * 获取指定配置键的持续时间值（纳秒）
     */
    fun getDuration(key: ConfigKey): Long {
        val value = requireNotNull(getString(key)) { "配置项 [${key.path}] 未设置" }
        return when {
            value.endsWith("ns") -> value.dropLast(2).toLong()
            value.endsWith("us") -> value.dropLast(2).toLong() * 1_000
            value.endsWith("ms") -> value.dropLast(2).toLong() * 1_000_000
            value.endsWith("s") -> value.dropLast(1).toLong() * 1_000_000_000
            value.endsWith("m") -> value.dropLast(1).toLong() * 60_000_000_000
            value.endsWith("h") -> value.dropLast(1).toLong() * 3_600_000_000_000
            value.endsWith("d") -> value.dropLast(1).toLong() * 86_400_000_000_000
            else -> throw IllegalArgumentException("无效的持续时间格式: $value")
        }
    }

    /**
     * 获取指定配置键的字节大小值
     */
    fun getBytes(key: ConfigKey): Long {
        val value = requireNotNull(getString(key)) { "配置项 [${key.path}] 未设置" }
        val upper = value.uppercase()
        return when {
            upper.endsWith("B") && !upper.endsWith("KB") && !upper.endsWith("MB") && !upper.endsWith("GB") && !upper.endsWith("TB") ->
                value.dropLast(1).toLong()
            upper.endsWith("KB") -> value.dropLast(2).toLong() * 1024
            upper.endsWith("MB") -> value.dropLast(2).toLong() * 1024 * 1024
            upper.endsWith("GB") -> value.dropLast(2).toLong() * 1024 * 1024 * 1024
            upper.endsWith("TB") -> value.dropLast(2).toLong() * 1024L * 1024 * 1024 * 1024
            else -> throw IllegalArgumentException("无效的字节大小格式: $value")
        }
    }

    /**
     * 泛型类型的配置读取（自动类型转换）
     */
    @Suppress("UNCHECKED_CAST")
        fun <T> get(key: ConfigKey, type: Class<T>): T? {
        val value = getString(key) ?: return null
        return when (type) {
            String::class.java -> value as T
            Int::class.java -> value.toInt() as T
            Long::class.java -> value.toLong() as T
            Double::class.java -> value.toDouble() as T
            Boolean::class.java -> getBoolean(key) as T
            else -> value as T
        }
    }

    /**
     * 尝试获取主键值，若不存在则尝试回退键
     */
    fun getWithFallback(key: ConfigKey, fallbackKey: ConfigKey): String? {
        return getString(key) ?: getString(fallbackKey)
    }

    /**
     * 获取指定前缀的所有配置项
     */
    fun getKeysByPrefix(prefix: String): Map<String, String> {
        lock.read {
            val result = mutableMapOf<String, String>()
        for ((priority, sourceList) in providers) {
                for (source in sourceList) {
                    val entries = source.getAll()
        for ((path, value) in entries) {
                        if (path.startsWith(prefix) && !result.containsKey(path)) {
                            result[path] = value
                        }
                    }
                }
            }
        return result
        }
    }

    // ==================== 写入方法 ====================

    /**
     * 设置配置值并记录变更来源
     */
    fun set(key: ConfigKey, value: String, source: String = "runtime") {
        val oldValue = getString(key)
        setWithSource(key, value, source)
        notifyChange(key, oldValue, value, source)
    }

    /**
     * 批量设置配置值
     */
    fun setAll(map: Map<String, String>, source: String = "runtime") {
        for ((path, value) in map) {
            val key = registeredKeys[path] ?: ConfigKey(path = path)
            set(key, value, source)
        }
    }

    // ==================== 导出/导入 ====================

    /**
     * 导出配置到指定格式
     */
    fun exportConfig(format: String = "properties", keys: Collection<String>? = null): String {
        val source = getAllConfigValues()
        val filtered = if (keys != null) {
            source.filterKeys { it in keys }
        } else source
        val serializer: ConfigSerializer = when (format.lowercase()) {
            "properties" -> PropertiesSerializer()
            "json" -> JsonConfigSerializer()
            "yaml" -> YamlConfigSerializer()
            "flat" -> FlatConfigSerializer()
            else -> throw IllegalArgumentException("不支持的导出格式: $format")
        }
        return serializer.serialize(filtered)
    }

    /**
     * 从字符串导入配置
     */
    fun importConfig(content: String, format: String = "properties", source: String = "import") {
        val serializer: ConfigSerializer = when (format.lowercase()) {
            "properties" -> PropertiesSerializer()
            "json" -> JsonConfigSerializer()
            "yaml" -> YamlConfigSerializer()
            "flat" -> FlatConfigSerializer()
            else -> throw IllegalArgumentException("不支持的导入格式: $format")
        }
        val parsed = serializer.deserialize(content)
        setAll(parsed, source)
    }

    // ==================== 重置 ====================

    /**
     * 重置指定配置键到默认值
     */
    fun reset(key: ConfigKey) {
        val oldValue = getString(key)
        val defaultValue = getDefaultValue(key)
        setWithSource(key, defaultValue, "reset")
        notifyChange(key, oldValue, defaultValue, "reset")
    }

    /**
     * 重置所有配置到默认值
     */
    fun resetAll() {
        val snapshot = snapshot()
        for (path in snapshot.keys) {
            val configKey = registeredKeys[path] ?: ConfigKey(path = path)
        val defaultValue = configKey.defaultValue
            setWithSource(configKey, defaultValue, "reset")
        }
        for ((path, _) in snapshot) {
            val configKey = registeredKeys[path] ?: ConfigKey(path = path)
            notifyChange(configKey, snapshot[path], configKey.defaultValue, "reset")
        }
    }

    // ==================== 重新加载 ====================

    /**
     * 重新加载所有配置源
     */
    fun reload() {
        lock.write {
            for ((_, sources) in providers) {
                for (source in sources) {
                    source.reload()
                }
            }
        }
    }

    // ==================== 变更订阅 ====================

    /**
     * 订阅指定配置键的变更
     */
    fun subscribe(key: ConfigKey, listener: ConfigChangeListener) {
        listeners.add(listener)
    }

    /**
     * 使用 glob 模式订阅变更（如 "api.*"）
     */
    fun subscribePattern(pattern: String, listener: ConfigChangeListener) {
        val regex = globToRegex(pattern)
        patternListeners.add(Pair(regex, listener))
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(key: ConfigKey, listener: ConfigChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 取消模式订阅
     */
    fun unsubscribePattern(pattern: String, listener: ConfigChangeListener) {
        val regex = globToRegex(pattern)
        patternListeners.remove(Pair(regex, listener))
    }

    // ==================== 校验 ====================

    /**
     * 校验所有已注册的配置键
     */
    fun validate(): Map<String, ValidationResult> {
        val results = mutableMapOf<String, ValidationResult>()
        for ((path, key) in registeredKeys) {
            val currentValue = getString(key) ?: key.defaultValue ?: ""
            results[path] = validationEngine.validate(key, currentValue)
        }
        return results
    }

    /**
     * 校验指定配置键
     */
    fun validate(key: ConfigKey): ValidationResult {
        val currentValue = getString(key) ?: key.defaultValue ?: ""
        return validationEngine.validate(key, currentValue)
    }

    // ==================== 快照与恢复 ====================

    /**
     * 获取当前配置的完整快照
     */
    fun snapshot(): Map<String, String?> {
        val allValues = getAllConfigValues()
        return allValues.mapValues { it.value as String? }
    }

    /**
     * 从快照恢复配置
     */
    fun restore(snapshot: Map<String, String?>, source: String = "restore") {
        for ((path, value) in snapshot) {
            val key = registeredKeys[path] ?: ConfigKey(path = path)
        if (value != null) {
                set(key, value, source)
            }
        }
    }

    /**
     * 计算当前配置与另一个快照的差异
     */
    fun diff(otherSnapshot: Map<String, String?>): Map<String, Pair<String?, String?>> {
        val current = snapshot()
        val allKeys = (current.keys + otherSnapshot.keys).toSet()
        val result = mutableMapOf<String, Pair<String?, String?>>()
        for (path in allKeys) {
            val currentVal = current[path]
            val otherVal = otherSnapshot[path]
            if (currentVal != otherVal) {
                result[path] = Pair(currentVal, otherVal)
            }
        }
        return result
    }

    /**
     * 设置变更合并窗口大小（毫秒）
     */
    fun setCoalesceWindow(ms: Long) {
        coalesceWindowMs = ms
    }

    override fun close() {
        listeners.clear()
        patternListeners.clear()
        registeredKeys.clear()
        for ((_, sources) in providers) {
            for (source in sources) {
                source.close()
            }
        }
        providers.clear()
    }

    // ==================== 内部实现 ====================
    private fun getValue(key: ConfigKey): String? {
        lock.read {
            for ((_, sourceList) in providers) {
                for (source in sourceList) {
                    val value = source.get(key)
        if (value != null) return value
                }
            }
        return key.defaultValue
        }
    }
        private fun setWithSource(key: ConfigKey, value: String?, source: String) {
        lock.write {
            if (value != null) {
                val runtimeProvider = providers.values.flatten()
                    .filterIsInstance<MemoryConfigProvider>()
                    .maxByOrNull { it.priority }
        if (runtimeProvider != null) {
                    runtimeProvider.set(key, value)
                }
            }
        }
    }
        private fun getDefaultValue(key: ConfigKey): String? {
        return key.defaultValue
    }
        private fun getAllConfigValues(): Map<String, String> {
        lock.read {
            val result = mutableMapOf<String, String>()
        for ((_, sourceList) in providers) {
                for (source in sourceList) {
                    for ((path, value) in source.getAll()) {
                        if (!result.containsKey(path)) {
                            result[path] = value
                        }
                    }
                }
            }
        return result
        }
    }
        private fun notifyChange(key: ConfigKey, oldValue: String?, newValue: String?, source: String) {
        val now = System.currentTimeMillis()
        val lastChange = lastChangeTimes[key.path] ?: 0
        if (now - lastChange < coalesceWindowMs) {
            pendingChanges[key.path] = PendingChange(key, oldValue, newValue, source)
        return
        }
        lastChangeTimes[key.path] = now
        flushPendingChanges()
        val event = ConfigChangeEvent(
            key = key,
            oldValue = oldValue,
            newValue = newValue,
            source = source
        )
        for (listener in listeners) {
            try {
                listener.onConfigChanged(key, oldValue, newValue, source)
                listener.onEvent(event)
            } catch (_: Exception) {
            }
        }
        for ((regex, listener) in patternListeners) {
            if (regex.matches(key.path)) {
                try {
                    listener.onConfigChanged(key, oldValue, newValue, source)
                    listener.onEvent(event)
                } catch (_: Exception) {
                }
            }
        }
    }
        private fun flushPendingChanges() {
        val changes = pendingChanges.toMap()
        pendingChanges.clear()
        for ((_, change) in changes) {
            val event = ConfigChangeEvent(
                key = change.key,
                oldValue = change.oldValue,
                newValue = change.newValue,
                source = change.source
            )
        for (listener in listeners) {
                try {
                    listener.onConfigChanged(change.key, change.oldValue, change.newValue, change.source)
                    listener.onEvent(event)
                } catch (_: Exception) {
                }
            }
        for ((regex, listener) in patternListeners) {
                if (regex.matches(change.key.path)) {
                    try {
                        listener.onConfigChanged(change.key, change.oldValue, change.newValue, change.source)
                        listener.onEvent(event)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
        private fun globToRegex(glob: String): Regex {
        val regexStr = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when (c) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        regexStr.append(".*")
                        i++
                    } else {
                        regexStr.append("[^.]*")
                    }
                }
                '?' -> regexStr.append("[^.]")
                '.' -> regexStr.append("\\.")
                '{' -> {
                    val end = glob.indexOf('}', i)
        if (end > i) {
                        regexStr.append('(')
        val parts = glob.substring(i + 1, end).split(",")
                        regexStr.append(parts.joinToString("|") { Regex.escape(it) })
                        regexStr.append(')')
                        i = end
                    } else {
                        regexStr.append(c)
                    }
                }
                else -> regexStr.append(Regex.escape(c.toString()))
            }
            i++
        }
        regexStr.append('$')
        return Regex(regexStr.toString())
    }
}

/**
 * 创建一个按值降序排序的有序 Map
 */
private fun <K, V> sortedMapOf(comparator: Comparator<K>, vararg pairs: Pair<K, V>): java.util.SortedMap<K, MutableList<V>> {
    val map = java.util.TreeMap<K, MutableList<V>>(comparator)
        for ((key, value) in pairs) {
        map.getOrPut(key) { mutableListOf() }.add(value)
    }
        return map
}
