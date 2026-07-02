package com.apex.agent.kernel.burst

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Build

class LifecycleManager(private val application: Application) {
    private var isRunning = false
    
    fun start() {
        isRunning = true
        // 启动 BurstKernelService
        val intent = Intent(application, BurstKernelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
    }
    
    fun stop() {
        isRunning = false
    }
}