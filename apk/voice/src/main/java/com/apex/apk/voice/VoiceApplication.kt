package com.apex.apk.voice

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class VoiceApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.VOICE)
    private lateinit var facade: VoiceServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.VOICE,
                packageName = packageName,
                displayName = "Apex Voice",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = false
            )
        )
        ApexLog.i(ApexSuite.ApkId.VOICE, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        facade = VoiceServiceFacade(this)
        TypedServiceRegistry.register<VoiceServiceFacade>(facade)

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.VOICE, "[Application] bridge bound = $connected")
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
