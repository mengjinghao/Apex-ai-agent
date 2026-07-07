package com.apex.apk.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.sdk.bridge.ApkBridgeStubAdapter
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite

/**
 * Engine APK 的对外 Service 入口。
 *
 * 当其他 APK 与本 APK 同进程时，根本不会走 bindService，
 * 而是直接通过 [TypedServiceRegistry.get] 拿到 [EngineServiceFacade] —— 零延迟。
 *
 * 当其他 APK 与本 APK 不同进程时，通过 bindService 拿到本类的 [IApkBridgeInternal] Stub。
 */
class EngineHostService : Service() {

    override fun onCreate() {
        super.onCreate()
        ApexLog.i(ApexSuite.ApkId.ENGINE, "[HostService] onCreate")
    }

    override fun onBind(intent: Intent?): IBinder {
        val facade = TypedServiceRegistry.get<EngineServiceFacade>()
        val impl = if (facade != null) {
            EngineBridgeImpl(facade)
        } else {
            // fallback: facade 未初始化时返回空实现
            object : IApkBridgeInternal {
                override fun invoke(method: String, argsJson: String): String =
                    """{"success":false,"errorMessage":"Engine facade not initialized"}"""
                override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String =
                    invoke(method, argsJson)
                override fun openStream(channelName: String): String = channelName
                override fun closeStream(channelName: String) {}
            }
        }
        return ApkBridgeStubAdapter(impl)
    }

    override fun onDestroy() {
        ApexLog.w(ApexSuite.ApkId.ENGINE, "[HostService] onDestroy")
        super.onDestroy()
    }
}
