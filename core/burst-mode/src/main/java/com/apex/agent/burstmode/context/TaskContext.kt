package com.apex.agent.burstmode.context

import java.util.concurrent.ConcurrentHashMap

/**
 * 任务执行上下文。
 *
 * 在任务执行过程中共享的"流程变量"容器，支持：
 * - 跨任务/跨技能的变量传递
 * - 类型安全的读写
 * - 作用域层级（父子上下文继承）
 * - 变量变更监听
 *
 * # 使用场景
 *
 * - **多步骤任务**：步骤 1 产出 → 步骤 2 消费
 * - **技能链**：前一个技能的结果存入上下文，后一个技能读取
 * - **依赖图**：上游任务的结果传递给下游任务
 * - **并行任务汇总**：多个并行任务的结果写入同一上下文，最后汇总
 *
 * # 使用示例
 *
 * ```
 * val context = TaskContext()
 *
 * // 写入
 * context.set("user_input", "分析这段代码")
 * context.set("step_count", 3)
 *
 * // 读取
 * val input: String = context.get("user_input") ?: ""
 * val count: Int = context.get("step_count") ?: 0
 *
 * // 类型安全的 getOrNull
 * val result = context.getOrNull<String>("result")
 *
 * // 增量计数
 * context.increment("execution_count")
 *
 * // 父子继承
 * val childContext = TaskContext(parent = context)
 * childContext.get("user_input")  // 从父级继承
 * childContext.set("local_var", "child only")
 * ```
 */
class TaskContext(
    private val parent: TaskContext? = null
) {

    private val variables = ConcurrentHashMap<String, Any?>()

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String, Any?, Any?) -> Unit>()

    /**
     * 设置变量。
     *
     * @param key 变量名
     * @param value 变量值（null 表示删除）
     * @return 旧值
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> set(key: String, value: T?): T? {
        val oldValue = if (value == null) {
            variables.remove(key)
        } else {
            variables.put(key, value)
        }
        notifyListeners(key, oldValue, value)
        return oldValue as T?
    }

    /**
     * 获取变量。
     *
     * 如果当前上下文没有，会向上查找父级。
     *
     * @param key 变量名
     * @return 变量值，不存在返回 null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val value = variables[key]
        if (value != null) return value as T
        return parent?.get<T>(key)
    }

    /**
     * 获取变量，带默认值。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrDefault(key: String, defaultValue: T): T {
        return (variables[key] as? T) ?: parent?.get<T>(key) ?: defaultValue
    }

    /**
     * 类型安全获取。
     *
     * @param key 变量名
     * @return 类型匹配的值，不匹配返回 null
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T> getOrNull(key: String): T? {
        val value = variables[key] ?: return parent?.getOrNull<T>(key)
        return if (value is T) value else null
    }

    /**
     * 检查变量是否存在（包括父级）。
     */
    fun contains(key: String): Boolean {
        return variables.containsKey(key) || (parent?.contains(key) ?: false)
    }

    /**
     * 移除变量（仅当前层级）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> remove(key: String): T? {
        return variables.remove(key) as T?
    }

    /**
     * 获取当前层级的所有变量（不含父级）。
     */
    fun localEntries(): Map<String, Any?> = variables.toMap()

    /**
     * 获取所有变量（含父级，当前层级覆盖父级）。
     */
    fun allEntries(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        parent?.allEntries()?.let { result.putAll(it) }
        result.putAll(variables)
        return result
    }

    /**
     * 增量计数。
     *
     * @param key 变量名
     * @param delta 增量
     * @return 增量后的值
     */
    fun increment(key: String, delta: Long = 1): Long {
        var newValue: Long
        do {
            val current = get<Long>(key) ?: 0L
            newValue = current + delta
        } while (!variables.replace(key, current, newValue))
        notifyListeners(key, get(key) - delta, newValue)
        return newValue
    }

    /**
     * 追加到列表变量。
     *
     * @param key 变量名
     * @param item 要追加的元素
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> appendToList(key: String, item: T) {
        val list = variables.getOrPut(key) { mutableListOf<T>() } as MutableList<T>
        synchronized(list) {
            list.add(item)
        }
        notifyListeners(key, null, list)
    }

    /**
     * 添加变量变更监听器。
     *
     * @param listener (key, oldValue, newValue) -> Unit
     */
    fun addListener(listener: (String, Any?, Any?) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 移除监听器。
     */
    fun removeListener(listener: (String, Any?, Any?) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 清空当前层级所有变量。
     */
    fun clear() {
        variables.clear()
    }

    /**
     * 当前层级的变量数。
     */
    fun localSize(): Int = variables.size

    /**
     * 所有层级变量总数（含父级）。
     */
    fun totalSize(): Int = allEntries().size

    /**
     * 创建子上下文。
     *
     * 子上下文可以读取父级变量，但写入只影响自己。
     */
    fun createChild(): TaskContext = TaskContext(parent = this)

    private fun notifyListeners(key: String, oldValue: Any?, newValue: Any?) {
        for (listener in listeners) {
            try {
                listener(key, oldValue, newValue)
            } catch (_: Exception) {
                // 监听器异常不影响主流程
            }
        }
    }
}

/**
 * 上下文作用域。
 *
 * 用于管理多个上下文的生命周期和查找。
 * 例如：会话级上下文、任务级上下文、步骤级上下文。
 */
class ContextScope {

    private val scopes = ConcurrentHashMap<String, TaskContext>()

    /**
     * 获取或创建指定作用域的上下文。
     */
    fun getOrCreate(scopeName: String, parent: TaskContext? = null): TaskContext {
        return scopes.computeIfAbsent(scopeName) { TaskContext(parent = parent) }
    }

    /**
     * 获取指定作用域的上下文。
     */
    fun get(scopeName: String): TaskContext? = scopes[scopeName]

    /**
     * 移除指定作用域。
     */
    fun remove(scopeName: String): TaskContext? = scopes.remove(scopeName)

    /**
     * 清空所有作用域。
     */
    fun clear() {
        scopes.clear()
    }

    /**
     * 列出所有作用域名。
     */
    fun listScopes(): Set<String> = scopes.keys.toSet()
}
