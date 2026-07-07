package com.apex.apk.engine

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

/**
 * Engine APK Application。
 *
 * 启动时：
 *   1. 注册本 APK 身份
 *   2. 创建 [EngineServiceFacade] 并绑定到 :engine 模块的 EngineService
 *   3. 注册到 [TypedServiceRegistry]（同进程零延迟调用）
 *   4. 启动心跳
 *   5. 启动看门狗
 *   6. 绑定到主 APK 的 Bridge Registry
 */
class EngineApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.ENGINE)
    private lateinit var facade: EngineServiceFacade

    override fun onCreate() {
        super.onCreate()

        // 注册身份
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.ENGINE,
                packageName = packageName,
                displayName = "Apex Engine",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(ApexSuite.ApkId.ENGINE, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        // 创建并绑定 EngineServiceFacade
        facade = EngineServiceFacade(this).also { it.bind() }

        // 注册到 TypedServiceRegistry，让其他 APK 同进程时直接拿到
        TypedServiceRegistry.register<EngineServiceFacade>(facade)

        // 启动心跳 + 看门狗
        heartbeat.start()
        Watchdog.start()

        // 绑定到主 APK 的 Bridge Registry
        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.ENGINE, "[Application] bridge bound = $connected")
        }
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        facade.unbind()
        Watchdog.stop()
        super.onTerminate()
    }
}
