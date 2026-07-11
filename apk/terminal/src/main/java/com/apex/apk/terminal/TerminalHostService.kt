package com.apex.apk.terminal

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.sdk.bridge.ApkBridgeStubAdapter
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite

class TerminalHostService : Service() {

    override fun onCreate() {
        super.onCreate()
        ApexLog.i(ApexSuite.ApkId.TERMINAL, "[HostService] onCreate")
    }

    override fun onBind(intent: Intent?): IBinder {
        val facade = TypedServiceRegistry.get<TerminalServiceFacade>()
        val impl: IApkBridgeInternal = if (facade != null) {
            TerminalBridgeImpl(facade)
        } else {
            object : IApkBridgeInternal {
                override fun invoke(method: String, argsJson: String): String =
                    """{"success":false,"errorMessage":"Terminal facade not initialized"}"""
                override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String =
                    invoke(method, argsJson)
                override fun openStream(channelName: String): String = channelName
                override fun closeStream(channelName: String) {}
            }
        }
        return ApkBridgeStubAdapter(impl)
    }
}
