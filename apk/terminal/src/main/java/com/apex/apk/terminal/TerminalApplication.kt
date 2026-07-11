package com.apex.apk.terminal

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class TerminalApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.TERMINAL)
    private lateinit var facade: TerminalServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.TERMINAL,
                packageName = packageName,
                displayName = "Apex Terminal",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        facade = TerminalServiceFacade(this)
        // 立即初始化（终端是基础服务，启动时即应就绪）
        facade.initialize()
        TypedServiceRegistry.register<TerminalServiceFacade>(facade)

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.TERMINAL, "[Application] bridge bound = $connected")
        }
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        facade.shutdown()
        Watchdog.stop()
        super.onTerminate()
    }
}
