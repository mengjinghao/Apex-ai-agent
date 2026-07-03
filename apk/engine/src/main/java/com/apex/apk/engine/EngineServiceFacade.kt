package com.apex.apk.engine

import android.content.Context
import android.os.Build
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun

/**
 * Engine APK 的核心服务实现。
 *
 * **职责**：
 *   - 包装 `:engine` 模块的 [EngineService][com.ai.assistance.apex.engine.EngineService]
 *   - 对其他 APK 暴露统一的 Kotlin API（通过 TypedServiceRegistry 注册）
 *   - 当其他 APK 与本 APK 同进程时，调用方直接拿到本实例，零延迟
 *
 * **能力清单**：
 *   1. Shell 执行（普通 + Shizuku 高权限）
 *   2. 工具调用（5 个内置工具：file/network/system/process/code）
 *   3. 容器管理（proot 沙箱启动/停止/重启）
 *   4. 无障碍服务（点击/滑动/截图/UI 树）
 *   5. 权限检查
 *
 * **使用方式**（其他 APK）：
 *   ```kotlin
 *   val engine = TypedServiceRegistry.get<EngineServiceFacade>()
 *       ?: error("engine not available")
 *   val result = engine.executeShell("ls /sdcard")
 *   ```
 *
 * **关于 EngineService 绑定**：
 *   `:engine` 模块的 EngineService 跑在独立进程 `:engine_process`，
 *   调用方需要 bindService + AIDL 才能拿到 IEngineService。
 *   本 Facade 内部封装了 bindService 的细节，对外只暴露 Kotlin 方法。
 */
class EngineServiceFacade(private val context: Context) {

    private const val TAG_SUB = "EngineFacade"

    private var engineService: com.ai.assistance.apex.engine.IEngineService? = null
    private var serviceConnection: android.content.ServiceConnection? = null
    @Volatile private var bound = false

    private val shizukuManager = com.ai.assistance.apex.engine.shizuku.ShizukuManager.getInstance(context)
    private val permissionManager = com.ai.assistance.apex.engine.permissions.PermissionManager(context)

    /** 输出监听器列表，跨 APK 共享。 */
    private val outputListeners = mutableListOf<(String) -> Unit>()
    private val statusListeners = mutableListOf<(Int) -> Unit>()

    /**
     * 绑定到 `:engine` 模块的 EngineService。
     * 应在 Application.onCreate 中调用。
     */
    fun bind() {
        if (bound) return
        val intent = android.content.Intent().apply {
            action = "com.ai.assistance.apex.IEngineService"
            setPackage("com.apex.agent")
        }
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                engineService = com.ai.assistance.apex.engine.IEngineService.Stub.asInterface(service)
                bound = true
                ApexLog.i(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] connected to EngineService")
                // 注册容器输出回调
                try {
                    engineService?.setContainerOutputCallback(object : com.ai.assistance.apex.engine.IContainerCallback.Stub() {
                        override fun onOutput(output: String?) {
                            if (output != null) {
                                synchronized(outputListeners) {
                                    outputListeners.toList().forEach { it(output) }
                                }
                            }
                        }
                        override fun onStatusChanged(status: Int) {
                            synchronized(statusListeners) {
                                statusListeners.toList().forEach { it(status) }
                            }
                        }
                        override fun onError(error: String?) {
                            ApexLog.w(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] container error: $error")
                        }
                    })
                } catch (t: Throwable) {
                    ApexLog.e(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] setContainerOutputCallback failed", t)
                }
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                engineService = null
                bound = false
                ApexLog.w(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] disconnected from EngineService")
            }
            override fun onBindingDied(name: android.content.ComponentName?) {
                engineService = null
                bound = false
            }
            override fun onNullBinding(name: android.content.ComponentName?) {
                ApexLog.e(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] null binding from EngineService")
            }
        }
        try {
            val ok = context.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
            if (ok) {
                serviceConnection = conn
            } else {
                ApexLog.w(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] bindService returned false; engine may not be installed")
            }
        } catch (t: Throwable) {
            ApexLog.e(ApexSuite.ApkId.ENGINE, "[$TAG_SUB] bindService failed", t)
        }
    }

    fun unbind() {
        if (!bound) return
        try {
            serviceConnection?.let { context.unbindService(it) }
        } catch (_: Throwable) {}
        bound = false
        serviceConnection = null
        engineService = null
    }

    fun isBound(): Boolean = bound

    /** 添加容器输出监听。 */
    fun addOutputListener(listener: (String) -> Unit) {
        synchronized(outputListeners) { outputListeners.add(listener) }
    }

    fun removeOutputListener(listener: (String) -> Unit) {
        synchronized(outputListeners) { outputListeners.remove(listener) }
    }

    fun addStatusListener(listener: (Int) -> Unit) {
        synchronized(statusListeners) { statusListeners.add(listener) }
    }

    fun removeStatusListener(listener: (Int) -> Unit) {
        synchronized(statusListeners) { statusListeners.remove(listener) }
    }

    // ============================================================
    // Shell 执行
    // ============================================================

    /**
     * 执行 shell 命令（容器内 proot 沙箱）。
     * @return stdout + exitCode
     */
    suspend fun executeShell(command: String): BridgeResult<ShellResult> = bridgeRun {
        val svc = engineService ?: throw IllegalStateException("EngineService not bound")
        val result = svc.executeCommand(command)
        ShellResult(
            stdout = result.output,
            stderr = result.error,
            exitCode = result.exitCode,
            success = result.success,
            executionTimeMs = result.executionTime
        )
    }

    /**
     * 通过 Shizuku 执行 shell 命令（高权限，无需 root）。
     * @return ShizukuManager.CommandResult
     */
    suspend fun executeShellViaShizuku(command: String): BridgeResult<com.ai.assistance.apex.engine.shizuku.ShizukuManager.CommandResult> = bridgeRun {
        if (!shizukuManager.isAvailable()) {
            throw IllegalStateException("Shizuku not available")
        }
        if (!shizukuManager.isPermissionGranted()) {
            throw IllegalStateException("Shizuku permission not granted")
        }
        shizukuManager.executeCommand(command)
    }

    // ============================================================
    // 工具调用
    // ============================================================

    suspend fun executeTool(toolName: String, args: String): BridgeResult<ShellResult> = bridgeRun {
        val svc = engineService ?: throw IllegalStateException("EngineService not bound")
        val result = svc.executeTool(toolName, args)
        ShellResult(
            stdout = result.output,
            stderr = result.error,
            exitCode = result.exitCode,
            success = result.success,
            executionTimeMs = result.executionTime
        )
    }

    /** 列出所有可用工具。 */
    suspend fun listTools(): BridgeResult<List<ToolDescriptor>> = bridgeRun {
        val svc = engineService ?: throw IllegalStateException("EngineService not bound")
        svc.availableTools.map { t ->
            ToolDescriptor(
                name = t.name,
                description = t.description,
                category = t.category,
                parameters = t.parameters.toList(),
                requiresRoot = t.requiresRoot
            )
        }
    }

    // ============================================================
    // 容器管理
    // ============================================================

    suspend fun startContainer(): BridgeResult<Boolean> = bridgeRun {
        engineService?.startContainer() ?: false
    }

    suspend fun stopContainer(): BridgeResult<Boolean> = bridgeRun {
        engineService?.stopContainer() ?: false
    }

    suspend fun restartContainer(): BridgeResult<Boolean> = bridgeRun {
        engineService?.restartContainer() ?: false
    }

    suspend fun getContainerStatus(): BridgeResult<ContainerStatusInfo?> = bridgeRun {
        engineService?.containerStatus?.let { s ->
            ContainerStatusInfo(
                statusCode = s.statusCode,
                statusMessage = s.statusMessage,
                pid = s.pid,
                startTime = s.startTime,
                rootfsPath = s.rootfsPath
            )
        }
    }

    suspend fun getContainerOutput(): BridgeResult<String> = bridgeRun {
        engineService?.containerOutput ?: ""
    }

    // ============================================================
    // 无障碍服务
    // ============================================================

    /** 检查无障碍服务是否已启用。 */
    fun isAccessibilityEnabled(): Boolean =
        com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.isServiceEnabled()

    /**
     * 执行点击操作（需要无障碍服务已启用）。
     * @return 是否成功
     */
    fun performClick(x: Int, y: Int): Boolean {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return false
        return svc.performClick(x, y)
    }

    fun performLongPress(x: Int, y: Int): Boolean {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return false
        return svc.performLongPress(x, y)
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return false
        return svc.performSwipe(startX, startY, endX, endY, durationMs)
    }

    fun triggerGlobalAction(actionId: Int): Boolean {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return false
        return svc.triggerGlobalAction(actionId)
    }

    fun getUiHierarchy(): String? {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return null
        return svc.uiHierarchy
    }

    fun getCurrentActivityName(): String? {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return null
        return svc.currentActivityName
    }

    fun takeScreenshot(path: String, format: String = "png"): Boolean {
        val svc = com.ai.assistance.apex.engine.accessibility.EngineAccessibilityService.getInstance()
            ?: return false
        return svc.takeScreenshot(path, format)
    }

    // ============================================================
    // 权限
    // ============================================================

    fun checkPermission(permission: String): Boolean = permissionManager.checkPermission(permission)

    fun getRequiredPermissions(): List<String> = permissionManager.requiredPermissions

    fun getDangerousPermissions(): List<String> = permissionManager.dangerousPermissions

    /** Shizuku 是否可用。 */
    fun isShizukuAvailable(): Boolean = shizukuManager.isAvailable()

    /** Shizuku 版本。 */
    fun getShizukuVersion(): Int = shizukuManager.getVersion()

    /** Shizuku 权限是否已授予。 */
    fun isShizukuPermissionGranted(): Boolean = shizukuManager.isPermissionGranted()

    /** 请求 Shizuku 权限。 */
    fun requestShizukuPermission(requestCode: Int = 0) {
        shizukuManager.requestPermission(requestCode)
    }

    /** 引擎版本。 */
    fun getEngineVersion(): String = try {
        engineService?.engineVersion ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    /** 设备信息（补充）。 */
    fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        brand = Build.BRAND,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER,
        abis = Build.SUPPORTED_ABIS.toList()
    )

    /** 释放资源。 */
    fun shutdown() {
        try {
            engineService?.shutdown()
        } catch (_: Throwable) {}
        unbind()
    }
}

/** Shell 执行结果。 */
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val success: Boolean,
    val executionTimeMs: Long
)

/** 工具描述。 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val category: String,
    val parameters: List<String>,
    val requiresRoot: Boolean
)

/** 容器状态。 */
data class ContainerStatusInfo(
    val statusCode: Int,
    val statusMessage: String,
    val pid: Int,
    val startTime: Long,
    val rootfsPath: String
) {
    val isRunning: Boolean get() = statusCode == 2
    val isStarting: Boolean get() = statusCode == 1
    val isStopped: Boolean get() = statusCode == 0
    val isError: Boolean get() = statusCode == -1
}

/** 设备信息。 */
data class DeviceInfo(
    val brand: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val abis: List<String>
)
