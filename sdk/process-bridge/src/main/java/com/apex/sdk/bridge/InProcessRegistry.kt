package com.apex.sdk.bridge

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import java.util.concurrent.ConcurrentHashMap

/**
 * **进程内** Service Registry — “零延迟”路径的核心。
 *
 * 当多个 APK 通过 SharedUserId + android:process 共享同一个 Linux 进程时，
 * 它们的 Application / ClassLoader 共享同一个 JVM。
 * 因此任何 APK 注册到本 Registry 的 Kotlin 实例，其他 APK 可以直接拿到，
 * **完全跳过 Binder，调用就是普通的方法调用，延迟为 0**。
 *
 * 调用流程：
 *   1. APK A 启动 → Application.onCreate 中 `InProcessRegistry.register("engine", EngineServiceImpl())`
 *   2. APK B 想调用引擎 → `val engine = InProcessRegistry.lookup("engine") ?: fallbackToAidl()`
 *   3. 如果 engine != null，直接调用方法（零延迟）
 *   4. 如果 engine == null（说明 Engine APK 未启动或处于不同进程），降级走 AIDL
 *
 * **为什么能跨 APK 共享 JVM？**
 *   Android 的 SharedUserId + 同一 android:process 配置会让系统把多个 APK
 *   合并到同一个 ProcessRecord，使用同一个 ClassLoader（实际上是各 APK 的
 *   PathClassLoader 合并），ClassByName 查找可以跨 APK 找到。
 *   注意：跨 APK 调用 Kotlin 类时，需要保证类全名一致 + 接口在公共 SDK 中。
 */
object InProcessRegistry {

    private const val TAG_SUB = "InProcRegistry"

    private val services = ConcurrentHashMap<String, Any>()
    private val factories = ConcurrentHashMap<String, () -> Any>()

    /**
     * 注册一个服务实例到当前进程。
     * 若其他 APK 与本 APK 同进程，立刻可见。
     */
    fun <T : Any> register(name: String, instance: T) {
        services[name] = instance
        ApexLog.d(ApexSuite.ApkId.MAIN, "[$TAG_SUB] registered in-process service: $name")
    }

    /**
     * 注册一个延迟工厂。第一次 lookup 时才实例化。
     */
    fun <T : Any> registerLazy(name: String, factory: () -> T) {
        factories[name] = factory
    }

    fun unregister(name: String) {
        services.remove(name)
        factories.remove(name)
        ApexLog.d(ApexSuite.ApkId.MAIN, "[$TAG_SUB] unregistered in-process service: $name")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> lookup(name: String): T? {
        services[name]?.let { return it as T }
        factories[name]?.let { factory ->
            synchronized(this) {
                services[name]?.let { return it as T }
                val instance = factory()
                services[name] = instance
                factories.remove(name)
                ApexLog.d(ApexSuite.ApkId.MAIN, "[$TAG_SUB] lazily created service: $name")
                return instance as T
            }
        }
        return null
    }

    fun isAvailable(name: String): Boolean = services.containsKey(name) || factories.containsKey(name)

    fun listServices(): List<String> = services.keys.toList() + factories.keys.toList()

    fun clear() {
        services.clear()
        factories.clear()
    }
}
