package com.apex.sdk.bridge

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨 APK 强类型服务接口注册中心。
 *
 * **解决的问题**：[InProcessRegistry] 是 `Any` 类型，调用方需要强转；
 * 本注册中心要求每个服务声明其 Kotlin 接口类型 [T]，调用方拿到时已是正确类型。
 *
 * **使用方式（服务端 APK）**：
 *   ```kotlin
 *   // Engine APK 在 Application.onCreate 中
 *   TypedServiceRegistry.register(IEngineService::class, EngineServiceImpl())
 *   ```
 *
 * **使用方式（调用方 APK）**：
 *   ```kotlin
 *   // 主 APK 调用 Engine
 *   val engine = TypedServiceRegistry.get(IEngineService::class)
 *   engine?.executeShell("ls")
 *   ```
 *
 * **同进程时**：直接返回注册实例（零延迟 JVM 调用）
 * **跨进程时**：返回 null，调用方走 AIDL 降级路径
 */
object TypedServiceRegistry {

    private const val TAG_SUB = "TypedRegistry"

    private val services = ConcurrentHashMap<Class<*>, Any>()
    private val factories = ConcurrentHashMap<Class<*>, () -> Any>()

    /** 注册一个服务实例。 */
    fun <T : Any> register(interfaceClass: Class<T>, instance: T) {
        services[interfaceClass] = instance
        ApexLog.d(ApexSuite.ApkId.MAIN, "[$TAG_SUB] registered typed service: ${interfaceClass.simpleName}")
    }

    /** 注册一个延迟工厂。 */
    fun <T : Any> registerLazy(interfaceClass: Class<T>, factory: () -> T) {
        factories[interfaceClass] = factory
    }

    fun <T : Any> unregister(interfaceClass: Class<T>) {
        services.remove(interfaceClass)
        factories.remove(interfaceClass)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(interfaceClass: Class<T>): T? {
        services[interfaceClass]?.let { return it as T }
        factories[interfaceClass]?.let { factory ->
            synchronized(this) {
                services[interfaceClass]?.let { return it as T }
                val instance = factory()
                services[interfaceClass] = instance
                factories.remove(interfaceClass)
                ApexLog.d(ApexSuite.ApkId.MAIN, "[$TAG_SUB] lazily created typed service: ${interfaceClass.simpleName}")
                return instance as T
            }
        }
        return null
    }

    /** 使用 reified 简化调用方代码。 */
    inline fun <reified T : Any> get(): T? = get(T::class.java)

    inline fun <reified T : Any> register(instance: T) = register(T::class.java, instance)

    inline fun <reified T : Any> registerLazy(noinline factory: () -> T) =
        registerLazy(T::class.java, factory)

    inline fun <reified T : Any> unregister() = unregister(T::class.java)

    fun isAvailable(interfaceClass: Class<*>): Boolean =
        services.containsKey(interfaceClass) || factories.containsKey(interfaceClass)

    inline fun <reified T : Any> isAvailable(): Boolean = isAvailable(T::class.java)

    fun listServices(): List<String> =
        services.keys.map { it.simpleName } + factories.keys.map { it.simpleName + " (lazy)" }

    fun clear() {
        services.clear()
        factories.clear()
    }
}
