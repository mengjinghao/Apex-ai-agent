package com.apex.apk.voice

import android.app.Application
import com.apex.sdk.auth.ApkIdentity
import com.apex.sdk.auth.ApkIdentityRegistry
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class VoiceApplication : Application() {

    private val heartbeat = HeartbeatReporter("voice")

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = "voice",
                packageName = packageName,
                displayName = "Voice",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = false
            )
        )
        ApexLog.i("voice", "[Application] onCreate (pid=${android.os.Process.myPid()})")
        heartbeat.start()
        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i("voice", "[Application] bridge bound = $connected")
        }
        Watchdog.start()
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        super.onTerminate()
    }
}
