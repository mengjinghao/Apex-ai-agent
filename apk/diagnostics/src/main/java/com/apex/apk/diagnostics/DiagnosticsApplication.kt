package com.apex.apk.diagnostics

import android.app.Application
import com.apex.sdk.auth.ApkIdentity
import com.apex.sdk.auth.ApkIdentityRegistry
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class DiagnosticsApplication : Application() {

    private val heartbeat = HeartbeatReporter("diagnostics")

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = "diagnostics",
                packageName = packageName,
                displayName = "Diagnostics",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = false
            )
        )
        ApexLog.i("diagnostics", "[Application] onCreate (pid=${android.os.Process.myPid()})")
        heartbeat.start()
        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i("diagnostics", "[Application] bridge bound = $connected")
        }
        Watchdog.start()
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        super.onTerminate()
    }
}
