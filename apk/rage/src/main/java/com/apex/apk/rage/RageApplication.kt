package com.apex.apk.rage

import android.app.Application
import com.apex.apk.rage.agent.RageAgentArchitect
import com.apex.apk.rage.agent.RageTaskStore
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

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.RAGE,
                packageName = packageName,
                displayName = "Apex 狂暴模式",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = true
            )
        )
        ApexLog.i(ApexSuite.ApkId.RAGE, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        // 注册 4 Agent 架构师 + 任务存储到 TypedServiceRegistry
        val architect = RageAgentArchitect()
        val taskStore = RageTaskStore(this)
        TypedServiceRegistry.register<RageAgentArchitect>(architect)
        TypedServiceRegistry.register<RageTaskStore>(taskStore)

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
