package com.apex.apk.diagnostics

import android.app.Application
import com.apex.sdk.bridge.BridgeConnection
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.ApkIdentity
import com.apex.sdk.common.ApkIdentityRegistry
import com.apex.sdk.watchdog.HeartbeatReporter
import com.apex.sdk.watchdog.Watchdog

class DiagnosticsApplication : Application() {

    private val heartbeat = HeartbeatReporter(ApexSuite.ApkId.DIAGNOSTICS)
    private lateinit var facade: DiagnosticsServiceFacade

    override fun onCreate() {
        super.onCreate()
        ApkIdentityRegistry.register(
            ApkIdentity(
                id = ApexSuite.ApkId.DIAGNOSTICS,
                packageName = packageName,
                displayName = "Apex Diagnostics",
                defaultProcess = ApexSuite.MAIN_PROCESS,
                hostsForegroundService = false
            )
        )
        ApexLog.i(ApexSuite.ApkId.DIAGNOSTICS, "[Application] onCreate (pid=${android.os.Process.myPid()})")

        facade = DiagnosticsServiceFacade(this)
        TypedServiceRegistry.register<DiagnosticsServiceFacade>(facade)

        // 设置全局未捕获异常处理器，把崩溃堆栈写入文件
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                facade.reportCrash(thread, throwable)
            } catch (_: Throwable) {}
            previousHandler?.uncaughtException(thread, throwable)
        }

        heartbeat.start()
        Watchdog.start()

        BridgeConnection.bindToRegistry(this) { connected ->
            ApexLog.i(ApexSuite.ApkId.DIAGNOSTICS, "[Application] bridge bound = $connected")
        }
    }

    override fun onTerminate() {
        heartbeat.stop()
        BridgeConnection.unbind(this)
        Watchdog.stop()
        super.onTerminate()
    }
}
