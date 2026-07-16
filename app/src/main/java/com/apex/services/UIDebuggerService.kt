package com.apex.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** UIDebuggerService — 占位实现。 */
class UIDebuggerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
