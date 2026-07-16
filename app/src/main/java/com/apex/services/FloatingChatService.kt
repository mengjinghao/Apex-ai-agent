package com.apex.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** FloatingChatService — 占位实现。 */
class FloatingChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
