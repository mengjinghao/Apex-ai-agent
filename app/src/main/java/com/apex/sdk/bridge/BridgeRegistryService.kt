package com.apex.sdk.bridge

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** BridgeRegistryService — 占位实现。 */
class BridgeRegistryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
