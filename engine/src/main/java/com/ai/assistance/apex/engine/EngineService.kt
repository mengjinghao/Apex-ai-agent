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
import com.apex.agent.database.AppDatabase
import com.apex.agent.database.DatabaseRepository
import kotlinx.coroutines.runBlocking

class EngineService : Service() {

    companion object {
        private const val TAG = "EngineService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "engine_service_channel"

        // RBAC — 默认调用方为 super_admin 用户（userId=1，由 AppDatabase seed 创建）。
        // 未来接入真实认证后，应从 IPC callerUid / SecurityIdentity 解析实际 userId。
        private const val DEFAULT_ADMIN_USER_ID = 1L
        private const val SHELL_PERMISSION = "engine:shell:execute"

        // PERF-28: 固定大小线程池处理 async 调用，替代每次调用裸 `Thread{}.start()`。
        // 4 线程足够覆盖典型并发 IPC 调用（同时执行的工具/命令数有限），
        // 同时为 daemon 线程，避免阻止 JVM 退出；任务排队由 LinkedBlockingQueue 默认无界承载。
        private val asyncExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
            Thread(r, "EngineService-async").apply { isDaemon = true }
        }

        // PERF-36: 默认 admin 的 shell 权限在运行期不会变,首次查询后内存缓存,
        // 避免每条 shell 命令都 runBlocking 查 Room。若未来 assignRole/revokeRole
        // 对 userId=1 生效,需在那些路径里把 permissionCached 置 null 失效缓存。
        @Volatile
        private var permissionCached: Boolean? = null
    }

    private lateinit var containerManager: ContainerManager
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var permissionManager: PermissionManager

    // RBAC 仓储 — 在 onCreate 中由 AppDatabase 派生。
    // :engine 不引入 Hilt（保持纯 Service），直接用 AppDatabase.getDatabase() 单例。
    @Volatile
    @Volatile
    private var databaseRepository: DatabaseRepository? = null

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
            // RBAC: 校验调用方拥有 engine:shell:execute 权限。
            // 当前阶段没有真实认证，使用默认 super_admin 用户（userId=1）。
            // 若 seed 未执行或权限未授予，直接拒绝执行 shell 命令。
            val repo = databaseRepository
            if (repo == null) {
                return ExecutionResult().apply {
                    exitCode = -1
                    error = "Permission denied: database not initialized"
                    success = false
                }
            }
            val allowed = permissionCached ?: try {
                // PERF-36: 首次查询后缓存,后续命令直接走内存。默认 admin 权限运行期不变。
                val r = runBlocking { repo.hasPermission(DEFAULT_ADMIN_USER_ID, SHELL_PERMISSION) }
                permissionCached = r
                r
            } catch (e: Exception) {
                Log.e(TAG, "RBAC check failed: ${e.message}", e)
                false
            }
            if (!allowed) {
                return ExecutionResult().apply {
                    exitCode = -1
                    error = "Permission denied: user $DEFAULT_ADMIN_USER_ID lacks '$SHELL_PERMISSION'"
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

            // PERF-28: 复用固定线程池，避免每次调用都创建/销毁裸 Thread。
            asyncExecutor.execute {
                val result = containerManager.executeCommand(command)
                try {
                    callback?.onResult(result)
                } catch (e: RemoteException) {
                    Log.e(TAG, "executeCommandAsync: onResult error", e)
                }
            }
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

            // PERF-28: 复用固定线程池，避免每次调用都创建/销毁裸 Thread。
            asyncExecutor.execute {
                val result = toolExecutor.execute(toolName, args ?: "")
                try {
                    callback?.onResult(result)
                } catch (e: RemoteException) {
                    Log.e(TAG, "executeToolAsync: onResult error", e)
                }
            }
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

        // 初始化 RBAC 仓储 — 直接复用 :database 的 AppDatabase 单例
        val db = AppDatabase.getDatabase(this)
        databaseRepository = DatabaseRepository(
            userDao = db.userDao(),
            taskDao = db.taskDao(),
            userWithTasksDao = db.userWithTasksDao(),
            permissionDao = db.permissionDao(),
            roleDao = db.roleDao(),
            userRoleDao = db.userRoleDao(),
            rolePermissionDao = db.rolePermissionDao()
        )

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
        // PERF-28: 关闭 async 线程池，释放线程资源。
        asyncExecutor.shutdown()
        try {
            if (!asyncExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            asyncExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
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