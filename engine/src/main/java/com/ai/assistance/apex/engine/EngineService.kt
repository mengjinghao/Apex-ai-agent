package com.ai.assistance.apex.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.ai.assistance.apex.engine.container.ContainerManager
import com.ai.assistance.apex.engine.model.ContainerStatus
import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.model.ToolInfo
import com.ai.assistance.apex.engine.permissions.PermissionManager
import com.ai.assistance.apex.engine.tools.ToolExecutor

class EngineService : Service() {

    companion object {
        private const val TAG = "EngineService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "engine_service_channel"
    }

    private lateinit var containerManager: ContainerManager
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var permissionManager: PermissionManager

    private val containerCallbacks = RemoteCallbackList<IContainerCallback>()
    private val binder = EngineBinder()

    inner class EngineBinder : IEngineService.Stub() {

        override fun executeCommand(command: String?): ExecutionResult {
            if (command.isNullOrEmpty()) {
                return ExecutionResult().apply {
                    exitCode = -1
                    error = "Command is null or empty"
                    success = false
                }
            }
            return containerManager.executeCommand(command)
        }

        override fun executeCommandAsync(command: String?, callback: IToolCallback?) {
            if (command.isNullOrEmpty()) {
                try {
                    callback?.onError(-1, "Command is null or empty")
                } catch (e: RemoteException) {
                    Log.e(TAG, "executeCommandAsync: callback error", e)
                }
                return
            }

            Thread {
                val result = containerManager.executeCommand(command)
                try {
                    callback?.onResult(result)
                } catch (e: RemoteException) {
                    Log.e(TAG, "executeCommandAsync: onResult error", e)
                }
            }.start()
        }

        override fun executeTool(toolName: String?, args: String?): ExecutionResult {
            if (toolName.isNullOrEmpty()) {
                return ExecutionResult().apply {
                    exitCode = -1
                    error = "Tool name is null or empty"
                    success = false
                }
            }
            return toolExecutor.execute(toolName, args ?: "")
        }

        override fun executeToolAsync(toolName: String?, args: String?, callback: IToolCallback?) {
            if (toolName.isNullOrEmpty()) {
                try {
                    callback?.onError(-1, "Tool name is null or empty")
                } catch (e: RemoteException) {
                    Log.e(TAG, "executeToolAsync: callback error", e)
                }
                return
            }

            Thread {
                val result = toolExecutor.execute(toolName, args ?: "")
                try {
                    callback?.onResult(result)
                } catch (e: RemoteException) {
                    Log.e(TAG, "executeToolAsync: onResult error", e)
                }
            }.start()
        }

        override fun getAvailableTools(): MutableList<ToolInfo> {
            return toolExecutor.getAvailableTools()
        }

        override fun getContainerStatus(): ContainerStatus {
            return containerManager.getStatus()
        }

        override fun startContainer(): Boolean {
            return containerManager.start()
        }

        override fun stopContainer(): Boolean {
            return containerManager.stop()
        }

        override fun restartContainer(): Boolean {
            return containerManager.restart()
        }

        override fun getContainerOutput(): String {
            return containerManager.getOutput()
        }

        override fun setContainerOutputCallback(callback: IContainerCallback?) {
            callback?.let { containerCallbacks.register(it) }
        }

        override fun requestPermission(permission: String?): Boolean {
            return permission?.let { permissionManager.requestPermission(it) } ?: false
        }

        override fun checkPermission(permission: String?): Boolean {
            return permission?.let { permissionManager.checkPermission(it) } ?: false
        }

        override fun getEngineVersion(): String {
            return "1.0.0"
        }

        override fun shutdown() {
            containerManager.stop()
            stopSelf()
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return try {
                super.onTransact(code, data, reply, flags)
            } catch (e: Exception) {
                Log.e(TAG, "onTransact error: code=$code", e)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        containerManager = ContainerManager(this)
        toolExecutor = ToolExecutor(this)
        permissionManager = PermissionManager(this)

        containerManager.setOutputListener { output ->
            val count = containerCallbacks.beginBroadcast()
            for (i in 0 until count) {
                try {
                    containerCallbacks.getBroadcastItem(i).onOutput(output)
                } catch (e: RemoteException) {
                    Log.e(TAG, "onOutput broadcast error", e)
                }
            }
            containerCallbacks.finishBroadcast()
        }

        containerManager.setStatusListener { status ->
            val count = containerCallbacks.beginBroadcast()
            for (i in 0 until count) {
                try {
                    containerCallbacks.getBroadcastItem(i).onStatusChanged(status)
                } catch (e: RemoteException) {
                    Log.e(TAG, "onStatusChanged broadcast error", e)
                }
            }
            containerCallbacks.finishBroadcast()
        }

        containerManager.setErrorListener { error ->
            val count = containerCallbacks.beginBroadcast()
            for (i in 0 until count) {
                try {
                    containerCallbacks.getBroadcastItem(i).onError(error)
                } catch (e: RemoteException) {
                    Log.e(TAG, "onError broadcast error", e)
                }
            }
            containerCallbacks.finishBroadcast()
        }

        containerManager.start()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        containerManager.stop()
        containerCallbacks.kill()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Engine Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Apex Engine Service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.engine_service_notification_title))
            .setContentText(getString(R.string.engine_service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}