package com.apex.apk.rage

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.sdk.bridge.ApkBridgeStubAdapter
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog

class RageHostService : Service() {

    private val bridgeImpl = object : IApkBridgeInternal {
        override fun invoke(method: String, argsJson: String): String {
            ApexLog.d("rage", "[HostService] invoke: $method")
            return "{\"status\":\"ok\",\"method\":\"" + method + "\"}"
        }

        override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
            onProgress(100, "done")
            return invoke(method, argsJson)
        }

        override fun openStream(channelName: String): String = channelName
        override fun closeStream(channelName: String) { /* no-op */ }
    }

    private val binder = ApkBridgeStubAdapter(bridgeImpl)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ApexLog.i("rage", "[HostService] onCreate")
    }
}
