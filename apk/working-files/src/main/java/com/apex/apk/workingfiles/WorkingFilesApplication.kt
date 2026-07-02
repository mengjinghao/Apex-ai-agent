package com.apex.apk.workingfiles

import android.app.Application
import com.apex.sdk.auth.ApkIdentity
import com.apex.sdk.auth.ApkIdentityRegistry
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class WorkingFilesApplication : Application() {

    private val heartbeat = HeartbeatReporter("working-files")

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = "working-files",
                packageName = packageName,
                displayName = "WorkingFiles",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = false
            )
        )
        ApexLog.i("working-files", "[Application] onCreate (pid=${android.os.Process.myPid()})")
        heartbeat.start()
        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i("working-files", "[Application] bridge bound = $connected")
        }
        Watchdog.start()
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        super.onTerminate()
    }
}
