package com.apex.sdk.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkDependencyManager

/**
 * 监听套件中 APK 的安装/卸载/更新事件。
 *
 * 当用户安装/卸载/更新任意一个 APK 时，自动：
 *   1. 刷新 [ApkDependencyManager] 的安装状态快照
 *   2. 发布 [SuiteEventTypes.APK_LAUNCHED] / [SuiteEventTypes.APK_CRASHED] / [SuiteEventTypes.APK_RECOVERED] 事件
 *   3. 让业务侧 UI 自动刷新（如 "缺失组件" 提示消失）
 *
 * **使用方式**（在 Application.onCreate 中）：
 *   ```kotlin
 *   ApkPackageMonitor.register(applicationContext)
 *   ```
 */
object ApkPackageMonitor {

    private var registered = false
    private var receiver: BroadcastReceiver? = null

    /**
     * 注册监听器。应在 Application.onCreate 中调用。
     */
    @Synchronized
    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                handlePackageChange(ctx, intent)
            }
        }
        try {
            context.applicationContext.registerReceiver(receiver, filter)
            registered = true
            ApexLog.i(ApexSuite.ApkId.MAIN, "[PkgMonitor] registered")
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[PkgMonitor] register failed", t)
        }
    }

    /**
     * 注销监听器。在 Application.onTerminate 中调用。
     */
    @Synchronized
    fun unregister(context: Context) {
        if (!registered) return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: Throwable) {}
        registered = false
        receiver = null
    }

    private fun handlePackageChange(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val data = intent.data ?: return
        val packageName = data.encodedSchemeSpecificPart ?: return
        val action = intent.action ?: return

        // 只关心套件内的 APK
        val desc = com.apex.sdk.common.ApkDescriptors.byPackage(packageName) ?: return

        ApexLog.i(ApexSuite.ApkId.MAIN, "[PkgMonitor] package $action: $packageName (${desc.apkId})")

        // 刷新安装状态
        ApkDependencyManager.refreshInstallState(context)

        // 发布事件
        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                SuiteEventBus.publish(
                    type = "apk.installed",
                    payload = mapOf(
                        "apkId" to desc.apkId,
                        "packageName" to packageName,
                        "displayName" to desc.displayName
                    ),
                    sourceApk = ApexSuite.ApkId.MAIN
                )
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                SuiteEventBus.publish(
                    type = "apk.uninstalled",
                    payload = mapOf(
                        "apkId" to desc.apkId,
                        "packageName" to packageName,
                        "displayName" to desc.displayName
                    ),
                    sourceApk = ApexSuite.ApkId.MAIN
                )
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                SuiteEventBus.publish(
                    type = "apk.updated",
                    payload = mapOf(
                        "apkId" to desc.apkId,
                        "packageName" to packageName,
                        "displayName" to desc.displayName
                    ),
                    sourceApk = ApexSuite.ApkId.MAIN
                )
            }
        }
    }
}
