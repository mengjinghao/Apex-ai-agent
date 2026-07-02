package com.apex.apk.rage

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class RageApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.RAGE)
    private lateinit var facade: RageServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.RAGE,
                packageName = packageName,
                displayName = "Apex Rage Mode",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(ApexSuite.ApkId.RAGE, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        // 创建 facade（懒加载 BurstKernel，首次执行任务时才初始化）
        facade = RageServiceFacade(this)
        TypedServiceRegistry.register<RageServiceFacade>(facade)

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.RAGE, "[Application] bridge bound = $connected")
        }
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        Watchdog.stop()
        super.onTerminate()
    }
}
