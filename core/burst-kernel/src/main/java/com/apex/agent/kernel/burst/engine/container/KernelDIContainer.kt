package com.apex.agent.kernel.burst.engine.container

import java.util.concurrent.ConcurrentHashMap

/**
 * E14: 内核依赖注入容器
 *
 * 轻量级 DI 容器，管理内核组件：
 * - 单例注册/获取
 * - 工厂注册
 * - 懒加载
 * - 生命周期管理
 */
class KernelDIContainer {

    data class ComponentInfo(
        val name: String,
        val type: String,
        val scope: ComponentScope,
        val createdAt: Long = System.currentTimeMillis(),
        val instance: Any? = null,
        val factory: (() -> Any)? = null
    )

    enum class ComponentScope { SINGLETON, PROTOTYPE, LAZY }

    private val components = ConcurrentHashMap<String, ComponentInfo>()
    private val lazyInstances = ConcurrentHashMap<String, Any>()
    private val initializationOrder = mutableListOf<String>()

    /**
     * 注册单例
     */
    fun <T> registerSingleton(name: String, instance: T) {
        components[name] = ComponentInfo(name, instance!!::class.java.name, ComponentScope.SINGLETON, instance = instance)
        initializationOrder.add(name)
    }

    /**
     * 注册工厂
     */
    fun <T> registerFactory(name: String, scope: ComponentScope, factory: () -> T) {
        components[name] = ComponentInfo(name, "factory", scope, factory = { factory() })
    }

    /**
     * 注册懒加载
     */
    fun <T> registerLazy(name: String, factory: () -> T) {
        components[name] = ComponentInfo(name, "lazy", ComponentScope.LAZY, factory = { factory() })
    }

    /**
     * 获取组件
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(name: String): T? {
        val info = components[name] ?: return null
        return when (info.scope) {
            ComponentScope.SINGLETON -> info.instance as? T
            ComponentScope.PROTOTYPE -> info.factory?.invoke() as? T
            ComponentScope.LAZY -> {
                if (name !in lazyInstances) {
                    info.factory?.invoke()?.let { lazyInstances[name] = it }
                }
                lazyInstances[name] as? T
            }
        }
    }

    /**
     * 获取或默认
     */
    fun <T> getOrDefault(name: String, default: T): T = get<T>(name) ?: default

    /**
     * 按类型获取
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getByType(type: Class<T>): T? {
        return components.values.find { type.isInstance(it.instance) }?.instance as? T
    }

    /**
     * 注销组件
     */
    fun unregister(name: String): Boolean {
        lazyInstances.remove(name)
        return components.remove(name) != null
    }

    /**
     * 检查是否注册
     */
    fun contains(name: String): Boolean = components.containsKey(name)

    /**
     * 获取所有组件
     */
    fun getAll(): List<ComponentInfo> = components.values.toList()

    /**
     * 获取初始化顺序
     */
    fun getInitializationOrder(): List<String> = initializationOrder.toList()

    /**
     * 清空所有
     */
    fun clear() {
        components.clear()
        lazyInstances.clear()
        initializationOrder.clear()
    }

    /**
     * 获取统计
     */
    fun getStats(): ContainerStats {
        return ContainerStats(
            totalComponents = components.size,
            byScope = components.values.groupingBy { it.scope }.eachCount(),
            lazyLoaded = lazyInstances.size
        )
    }

    data class ContainerStats(
        val totalComponents: Int,
        val byScope: Map<ComponentScope, Int>,
        val lazyLoaded: Int
    )
}
