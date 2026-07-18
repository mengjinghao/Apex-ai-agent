package com.apex.core.kernel

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * 轻量级服务定位器 — 替代 Hilt/Dagger，零编译开销。
 * 支持单例注册、工厂懒加载、作用域隔离。
 */
class ServiceLocator {
    @PublishedApi internal val instances = ConcurrentHashMap<KClass<*>, Any>()
    @PublishedApi internal val factories = ConcurrentHashMap<KClass<*>, () -> Any>()
    private val scopes = ConcurrentHashMap<String, ServiceLocator>()

    /** 注册单例 */
    inline fun <reified T : Any> singleton(instance: T) {
        instances[T::class] = instance
    }

    /** 注册工厂（首次 resolve 时创建） */
    inline fun <reified T : Any> factory(noinline factory: () -> T) {
        factories[T::class] = factory
    }

    /** 获取实例，找不到返回 null */
    inline fun <reified T : Any> resolve(): T? {
        val klass = T::class
        @Suppress("UNCHECKED_CAST")
        instances[klass]?.let { return it as T }
        @Suppress("UNCHECKED_CAST")
        factories[klass]?.let { factory ->
            val instance = factory()
            instances[klass] = instance
            return instance as T
        }
        return null
    }

    /** 获取或抛异常 */
    inline fun <reified T : Any> require(): T {
        return resolve() ?: throw ServiceNotRegistered(T::class.qualifiedName)
    }

    /** 是否已注册 */
    inline fun <reified T : Any> contains(): Boolean {
        return instances.containsKey(T::class) || factories.containsKey(T::class)
    }

    /** 创建命名作用域 */
    fun createScope(name: String): ServiceLocator {
        val scope = ServiceLocator()
        scopes[name] = scope
        return scope
    }

    /** 释放作用域 */
    fun releaseScope(name: String) {
        scopes.remove(name)?.clear()
    }

    fun clear() {
        instances.clear()
        factories.clear()
        scopes.clear()
    }
}

class ServiceNotRegistered(name: String?) : RuntimeException("Service not registered: $name")
