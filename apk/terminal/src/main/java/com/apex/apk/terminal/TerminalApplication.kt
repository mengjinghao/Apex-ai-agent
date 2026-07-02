package com.apex.apk.terminal

import android.app.Application
import com.apex.sdk.auth.ApkIdentity
import com.apex.sdk.auth.ApkIdentityRegistry
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class TerminalApplication : Application() {

    private val heartbeat = HeartbeatReporter("terminal")

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = "terminal",
                packageName = packageName,
                displayName = "Terminal",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i("terminal", "[Application] onCreate (pid=${android.os.Process.myPid()})")
        heartbeat.start()
        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i("terminal", "[Application] bridge bound = $connected")
        }
        Watchdog.start()
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        super.onTerminate()
    }
}
