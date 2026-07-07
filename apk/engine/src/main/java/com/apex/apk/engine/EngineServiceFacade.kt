package com.apex.apk.engine

import android.content.Context
import android.os.Build
import com.apex.lib.engine.ContainerAction
import com.apex.lib.engine.ContainerStatusInfo
import com.apex.lib.engine.DeviceInfo
import com.apex.lib.engine.EngineGateway
import com.apex.lib.engine.EngineOrchestrator
import com.apex.lib.engine.ShellResult
import com.apex.lib.engine.ShizukuCommandResult
import com.apex.lib.engine.ToolDescriptor
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun

/**
 * Engine APK 的核心服务实现。
 *
 * **职责**：
 *   - 作为 [EngineGateway] 的实现，向 `:lib:engine` 提供底层 `:engine` 模块能力
 *   - 持有 [EngineOrchestrator]，把高层编排（命令路由 / 状态机 / 工具校验 / Shizuku 降级）委托给 lib
 *   - 保留 Android 侧句柄：bindService / AIDL / ShizukuManager / PermissionManager / 无障碍服务
 *   - 对其他 APK 暴露统一的 Kotlin API（通过 TypedServiceRegistry 注册），同进程零延迟
 *
 * **能力清单**：
 *   1. Shell 执行（普通 + Shizuku 高权限，含自动降级）
 *   2. 工具调用（5 个内置工具：file/network/system/process/code）
 *   3. 容器管理（proot 沙箱启动/停止/重启 + 生命周期状态机）
 *   4. 无障碍服务（点击/滑动/截图/UI 树）
 *   5. 权限检查 / Shizuku 状态查询
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
 *
 * **数据模型**：[ShellResult] / [ToolDescriptor] / [ContainerStatusInfo] / [DeviceInfo]
 *   已迁移到 `:lib:engine`（com.apex.lib.engine.*），本文件不再持有。
 */
class EngineServiceFacade(private val context: Context) : EngineGateway {

    private val TAG_SUB = "EngineFacade"

    private var engineService: com.ai.assistance.apex.engine.IEngineService? = null
    private var serviceConnection: android.content.ServiceConnection? = null
    @Volatile private var bound = false

    private val shizukuManager = com.ai.assistance.apex.engine.shizuku.ShizukuManager.getInstance(context)
    private val permissionManager = com.ai.assistance.apex.engine.permissions.PermissionManager(context)

    /** 输出监听器列表，跨 APK 共享。 */
    private val outputListeners = mutableListOf<(String) -> Unit>()
    private val statusListeners = mutableListOf<(Int) -> Unit>()

    /**
     * 高层编排器。`by lazy` 避免在构造函数中泄漏未完成的 `this`。
     * 调用任何高层 API 时会自动初始化。
     */
    val orchestrator: EngineOrchestrator by lazy { EngineOrchestrator(this) }

    // ============================================================
    // EngineGateway 实现：底层能力（供 orchestrator 调用）
    // ============================================================

    override fun isBound(): Boolean = bound

    override fun getEngineVersion(): String = try {
        engineService?.engineVersion ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    override suspend fun rawExecuteShell(command: String): BridgeResult<ShellResult> = bridgeRun {
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

    override suspend fun rawExecuteShellViaShizuku(command: String): BridgeResult<ShizukuCommandResult> = bridgeRun {
        if (!shizukuManager.isAvailable()) {
            throw IllegalStateException("Shizuku not available")
        }
        if (!shizukuManager.isPermissionGranted()) {
            throw IllegalStateException("Shizuku permission not granted")
        }
        val r = shizukuManager.executeCommand(command)
        ShizukuCommandResult(
            exitCode = r.exitCode,
            output = r.output,
            error = r.error
        )
    }

    override suspend fun rawExecuteTool(toolName: String, args: String): BridgeResult<ShellResult> = bridgeRun {
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

    override suspend fun rawListTools(): BridgeResult<List<ToolDescriptor>> = bridgeRun {
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

    override suspend fun rawStartContainer(): BridgeResult<Boolean> = bridgeRun {
        engineService?.startContainer() ?: false
    }

    override suspend fun rawStopContainer(): BridgeResult<Boolean> = bridgeRun {
        engineService?.stopContainer() ?: false
    }

    override suspend fun rawRestartContainer(): BridgeResult<Boolean> = bridgeRun {
        engineService?.restartContainer() ?: false
    }

    override suspend fun rawGetContainerStatus(): BridgeResult<ContainerStatusInfo?> = bridgeRun {
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

    override suspend fun rawGetContainerOutput(): BridgeResult<String> = bridgeRun {
        engineService?.containerOutput ?: ""
    }

    override fun isShizukuAvailable(): Boolean = shizukuManager.isAvailable()

    override fun isShizukuPermissionGranted(): Boolean = shizukuManager.isPermissionGranted()

    override fun getShizukuVersion(): Int = shizukuManager.getVersion()

    override fun requestShizukuPermission(requestCode: Int) {
        shizukuManager.requestPermission(requestCode)
    }

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo(
        brand = Build.BRAND,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        manufacturer = Build.MANUFACTURER,
        abis = Build.SUPPORTED_ABIS.toList()
    )

    // ============================================================
    // 绑定 / 解绑
    // ============================================================

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
    // 高层 API（委托给 orchestrator）
    // ============================================================

    /**
     * 执行 shell 命令（容器内 proot 沙箱）。
     * 等价于 `orchestrator.executeCommand(cmd, preferShizuku=false)`。
     */
    suspend fun executeShell(command: String): BridgeResult<ShellResult> =
        orchestrator.executeCommand(command, preferShizuku = false)

    /**
     * 通过 Shizuku 执行 shell 命令（高权限，无需 root）。
     * 不可用时直接返回 Failure，不降级。
     */
    suspend fun executeShellViaShizuku(command: String): BridgeResult<ShizukuCommandResult> =
        orchestrator.executeShellViaShizuku(command)

    /** 执行内置工具（带工具名校验）。 */
    suspend fun executeTool(toolName: String, args: String): BridgeResult<ShellResult> =
        orchestrator.executeToolSafe(toolName, args)

    /** 列出所有可用工具。 */
    suspend fun listTools(): BridgeResult<List<ToolDescriptor>> =
        orchestrator.listTools()

    // ============================================================
    // 容器管理（委托给 orchestrator + lifecycle）
    // ============================================================

    suspend fun startContainer(): BridgeResult<Boolean> =
        orchestrator.manageContainer(ContainerAction.START)

    suspend fun stopContainer(): BridgeResult<Boolean> =
        orchestrator.manageContainer(ContainerAction.STOP)

    suspend fun restartContainer(): BridgeResult<Boolean> =
        orchestrator.manageContainer(ContainerAction.RESTART)

    suspend fun getContainerStatus(): BridgeResult<ContainerStatusInfo?> =
        orchestrator.queryContainerStatus()

    suspend fun getContainerOutput(): BridgeResult<String> =
        orchestrator.getContainerOutput()

    // ============================================================
    // 无障碍服务（保留在 APK，未下沉到 lib）
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
    // 权限（保留在 APK）
    // ============================================================

    fun checkPermission(permission: String): Boolean = permissionManager.checkPermission(permission)

    fun getRequiredPermissions(): List<String> = permissionManager.requiredPermissions

    fun getDangerousPermissions(): List<String> = permissionManager.dangerousPermissions

    /** 释放资源。 */
    fun shutdown() {
        try {
            engineService?.shutdown()
        } catch (_: Throwable) {}
        unbind()
    }
}
