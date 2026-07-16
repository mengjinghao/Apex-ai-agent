package com.apex.base

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.StrictMode

/**
 * Application 基类，提供应用级初始化、全局异常捕获和调试工具。
 *
 * 使用示例：
 * ```
 * class MyApplication : BaseApplication() {
 *     override fun initDependencyInjection() {
 *         // 初始化 Hilt / Koin 等
 *     }
 * }
 * ```
 */
abstract class BaseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (isDebug) {
            enableStrictMode()
            enableLeakDetection()
        }
        setupUncaughtExceptionHandler()
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        initDependencyInjection()
    }

    /** 当前应用是否处于调试模式 */
    private val isDebug: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Activity 生命周期回调，子类可覆写以跟踪 Activity 状态。
     */
    protected open val activityLifecycleCallbacks: ActivityLifecycleCallbacks =
        object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }

    /**
     * 设置全局未捕获异常处理器。
     * 默认行为是将异常委托给系统默认处理器，子类可覆写以自定义崩溃报告逻辑。
     */
    protected open fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 初始化依赖注入框架（Hilt、Koin 等）。
     * 子类必须在此方法中配置依赖注入的入口点。
     */
    protected abstract fun initDependencyInjection()

    /**
     * 在调试模式下启用 StrictMode，用于检测主线程上的磁盘 I/O 和网络操作。
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }

    /**
     * 启用内存泄漏检测（需要集成 LeakCanary 等第三方库）。
     * 默认实现为空，子类可覆写以添加 LeakCanary 等工具的初始化代码。
     */
    protected open fun enableLeakDetection() {
        // 子类可在此初始化 LeakCanary 或其他内存泄漏检测工具
    }
}
