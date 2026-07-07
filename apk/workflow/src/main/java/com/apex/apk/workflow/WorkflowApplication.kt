package com.apex.apk.workflow

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class WorkflowApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.WORKFLOW)
    private lateinit var facade: WorkflowServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.WORKFLOW,
                packageName = packageName,
                displayName = "Apex Workflow",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(ApexSuite.ApkId.WORKFLOW, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        facade = WorkflowServiceFacade(this)
        TypedServiceRegistry.register<WorkflowServiceFacade>(facade)

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.WORKFLOW, "[Application] bridge bound = $connected")
        }
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        Watchdog.stop()
        super.onTerminate()
    }
}
