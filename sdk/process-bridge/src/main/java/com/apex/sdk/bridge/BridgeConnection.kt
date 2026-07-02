package com.apex.sdk.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite

/**
 * 连接到主 APK 的 [BridgeRegistryService] 的辅助类。
 *
 * 各 APK 在 Application.onCreate 中：
 *   ```kotlin
 *   BridgeConnection.bindToRegistry(this) { connected ->
 *       if (connected) {
 *           // 把自己对外暴露的服务注册到 Registry
 *           BridgeConnection.registerService("engine", myEngineBridge)
 *       }
 *   }
 *   ```
 *
 * 当两个 APK 处于同一进程时，bindService 实际是 no-op（同进程直接拿 Binder），
 * 但调用方代码完全一致 —— 这是“多个 APK 像一个 APK 一样”的关键设计。
 */
object BridgeConnection {

    private const val TAG_SUB = "BridgeConn"

    private var registry: IBridgeRegistry? = null
    private var connection: ServiceConnection? = null
    private var bound = false

    /** 自身 APK 暴露的服务列表（绑定时一并注册）。 */
    private val pendingServices = linkedMapOf<String, IApkBridge>()

    /**
     * 绑定到主 APK 的 Bridge Registry。
     * 若主 APK 未启动，绑定会失败但不抛异常 —— 之后由 Watchdog 重试。
     */
    @Synchronized
    fun bindToRegistry(context: Context, onConnected: ((Boolean) -> Unit)? = null) {
        if (bound) {
            onConnected?.invoke(true)
            return
        }

        val intent = Intent().apply {
            action = BridgeRegistryService.ACTION_BIND
            // 主 APK 包名
            setPackage("com.apex.agent")
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                registry = IBridgeRegistry.Stub.asInterface(service)
                bound = true
                ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] connected to registry")
                // 把待注册的服务都注册上去
                pendingServices.forEach { (name, bridge) ->
                    try {
                        registry?.register(name, bridge)
                    } catch (t: Throwable) {
                        ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] register failed: $name", t)
                    }
                }
                onConnected?.invoke(true)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                registry = null
                bound = false
                ApexLog.w(ApexSuite.ApkId.MAIN, "[$TAG_SUB] disconnected from registry")
            }

            override fun onBindingDied(name: ComponentName?) {
                registry = null
                bound = false
                ApexLog.w(ApexSuite.ApkId.MAIN, "[$TAG_SUB] binding died: $name")
            }

            override fun onNullBinding(name: ComponentName?) {
                ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] null binding: $name")
                onConnected?.invoke(false)
            }
        }

        try {
            val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!ok) {
                ApexLog.w(ApexSuite.ApkId.MAIN, "[$TAG_SUB] bindService returned false; main APK may not be installed")
                onConnected?.invoke(false)
                return
            }
            connection = conn
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] bindService failed", t)
            onConnected?.invoke(false)
        }
    }

    /**
     * 把本 APK 的服务注册到 Registry。
     * 若还未绑定，会先暂存到 pending 列表，等绑定成功后批量注册。
     */
    @Synchronized
    fun registerService(name: String, bridge: IApkBridge) {
        pendingServices[name] = bridge
        try {
            registry?.register(name, bridge)
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[$TAG_SUB] registerService failed: $name", t)
        }
    }

    @Synchronized
    fun unregisterService(name: String) {
        pendingServices.remove(name)
        try {
            registry?.unregister(name)
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun unbind(context: Context) {
        if (!bound) return
        try {
            connection?.let { context.unbindService(it) }
        } catch (_: Throwable) {}
        bound = false
        connection = null
        registry = null
    }

    fun isBound(): Boolean = bound

    fun registry(): IBridgeRegistry? = registry
}
