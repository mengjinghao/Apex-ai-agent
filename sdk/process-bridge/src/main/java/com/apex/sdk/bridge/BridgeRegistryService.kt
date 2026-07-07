package com.apex.sdk.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import java.util.concurrent.ConcurrentHashMap

/**
 * 套件级 Bridge Registry Service。
 *
 * 每个 APK 启动时 bindService 到本服务，把自己的 [IApkBridge] 注册进来；
 * 其他 APK 通过 [IBridgeRegistry.lookup] 拿到。
 *
 * **关键**：当所有 APK 共享同一个进程（SharedUserId + android:process）时，
 * 本 Service 的实例在进程内是单例，所有 APK 直接共享同一个 Registry，
 * 无需 Binder！—— 这是“零延迟”的真正实现路径。
 *
 * 当 APK 处于不同进程时（如 Terminal 独立进程），Binder 自动生效，
 * 走标准 AIDL 路径，毫秒级延迟。
 *
 * **本类只承载 Registry 的 Binder 接口**；
 * 真正的“进程内零延迟”由 [InProcessRegistry] 提供（同进程时直接 JVM 调用）。
 */
class BridgeRegistryService : Service() {

    private val binder = object : IBridgeRegistry.Stub() {

        private val services = ConcurrentHashMap<String, IApkBridge>()

        override fun register(serviceName: String?, bridge: IApkBridge?): Boolean {
            if (serviceName == null || bridge == null) return false
            services[serviceName] = bridge
            // 同步注册到 BinderConnectionManager，让进程内调用方也能查到
            BinderConnectionManager.register(serviceName, bridge)
            ApexLog.i(
                ApexSuite.ApkId.MAIN,
                "[BridgeRegistry] service registered: $serviceName (pid=${Process.myPid()})"
            )
            return true
        }

        override fun unregister(serviceName: String?) {
            if (serviceName == null) return
            services.remove(serviceName)
            BinderConnectionManager.unregister(serviceName)
        }

        override fun isRegistered(serviceName: String?): Boolean {
            return serviceName != null && services.containsKey(serviceName)
        }

        override fun lookup(serviceName: String?): IApkBridge? {
            return serviceName?.let { services[it] }
        }

        override fun listServices(): List<String> {
            return services.keys.toList()
        }

        override fun heartbeat(): Long {
            return System.currentTimeMillis()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ApexLog.i(ApexSuite.ApkId.MAIN, "[BridgeRegistry] service onCreate (pid=${Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ApexLog.w(ApexSuite.ApkId.MAIN, "[BridgeRegistry] service onDestroy")
        super.onDestroy()
    }

    companion object {

        /** 主 APK 中本 Service 的 ComponentName，其他 APK bindService 用。 */
        const val ACTION_BIND = "com.apex.sdk.bridge.BIND_REGISTRY"

        /**
         * 启动本 Service（仅主 APK 调用，其他 APK bindService 即可）。
         * 必须由 Application 调用，让 Service 跑在前台或后台。
         */
        fun startInMainApp(context: Context) {
            val intent = Intent(context, BridgeRegistryService::class.java).apply {
                action = ACTION_BIND
            }
            try {
                context.startService(intent)
                ApexLog.i(ApexSuite.ApkId.MAIN, "[BridgeRegistry] startInMainApp ok")
            } catch (t: Throwable) {
                ApexLog.e(ApexSuite.ApkId.MAIN, "[BridgeRegistry] startInMainApp failed", t)
            }
        }
    }
}
