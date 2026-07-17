package com.apex.core.kernel

import android.content.Context

/**
 * Apex 系统内核 — 应用启动时初始化，提供全局服务访问。
 *
 * 5 层架构的 Layer 1，零业务依赖。
 */
object ApexKernel {
    @Volatile
    private var booted = false

    lateinit var serviceLocator: ServiceLocator
        private set
    lateinit var eventBus: EventBus
        private set
    lateinit var configStore: ConfigStore
        private set
    lateinit var pluginRegistry: PluginRegistry
        private set

    /** 启动内核 */
    @Synchronized
    fun boot(context: Context) {
        if (booted) return

        val appContext = context.applicationContext
        serviceLocator = ServiceLocator()
        eventBus = EventBus()
        configStore = ConfigStore(appContext)
        pluginRegistry = PluginRegistry(serviceLocator, eventBus)

        // 自注册
        serviceLocator.singleton<ServiceLocator>(serviceLocator)
        serviceLocator.singleton<EventBus>(eventBus)
        serviceLocator.singleton<ConfigStore>(configStore)
        serviceLocator.singleton<PluginRegistry>(pluginRegistry)

        booted = true
    }

    /** 关闭内核 */
    @Synchronized
    fun shutdown() {
        if (!booted) return
        pluginRegistry.uninstallAll()
        serviceLocator.clear()
        booted = false
    }

    /** 是否已启动 */
    fun isBooted(): Boolean = booted
}
