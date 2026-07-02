package com.apex.apk.multiagent

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class MultiAgentApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.MULTI_AGENT)
    private lateinit var facade: MultiAgentServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.MULTI_AGENT,
                packageName = packageName,
                displayName = "Apex Multi-Agent",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        facade = MultiAgentServiceFacade(this)
        TypedServiceRegistry.register<MultiAgentServiceFacade>(facade)

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.MULTI_AGENT, "[Application] bridge bound = $connected")
        }
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        Watchdog.stop()
        super.onTerminate()
    }
}
