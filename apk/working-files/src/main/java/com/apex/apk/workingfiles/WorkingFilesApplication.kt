package com.apex.apk.workingfiles

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class WorkingFilesApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.WORKING_FILES)
    private lateinit var facade: WorkingFilesServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.WORKING_FILES,
                packageName = packageName,
                displayName = "Apex Working Files",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = false
            )
        )
        ApexLog.i(ApexSuite.ApkId.WORKING_FILES, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        facade = WorkingFilesServiceFacade(this)
        TypedServiceRegistry.register<WorkingFilesServiceFacade>(facade)

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.WORKING_FILES, "[Application] bridge bound = $connected")
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
