package com.apex.lib.engine

import com.apex.sdk.common.BridgeResult

/**
 * 引擎网关契约。
 *
 * 定义 `:apk:engine` 需要向 lib 提供的底层能力，
 * 让 [EngineOrchestrator] 的编排逻辑可以脱离 Android / AIDL / 顶层 :engine 模块单测。
 *
 * 实现方（通常是 [com.apex.apk.engine.EngineServiceFacade]）负责：
 *   - 持有 `:engine` 模块的 [com.ai.assistance.apex.engine.IEngineService] 绑定
 *   - 持有 ShizukuManager / PermissionManager 等 Android 侧句柄
 *   - 把底层异常 / AIDL 调用包装为 [BridgeResult]
 *
 * 所有 `raw*` 方法均为 suspend，由编排器在协程上下文中调用。
 * 非 suspend 方法（如 [isBound] / [isShizukuAvailable]）用于即时查询，必须快返回。
 */
interface EngineGateway {

    // ============================================================
    // 绑定 / 版本
    // ============================================================

    /** 底层 EngineService 是否已绑定。 */
    fun isBound(): Boolean

    /** 引擎版本字符串。 */
    fun getEngineVersion(): String

    // ============================================================
    // Shell 执行
    // ============================================================

    /** 通过容器内 proot 沙箱执行 shell。 */
    suspend fun rawExecuteShell(command: String): BridgeResult<ShellResult>

    /** 通过 Shizuku 高权限通道执行 shell。 */
    suspend fun rawExecuteShellViaShizuku(command: String): BridgeResult<ShizukuCommandResult>

    // ============================================================
    // 工具
    // ============================================================

    /** 执行内置工具。 */
    suspend fun rawExecuteTool(toolName: String, args: String): BridgeResult<ShellResult>

    /** 列出底层所有可用工具。 */
    suspend fun rawListTools(): BridgeResult<List<ToolDescriptor>>

    // ============================================================
    // 容器
    // ============================================================

    /** 启动容器。 */
    suspend fun rawStartContainer(): BridgeResult<Boolean>

    /** 停止容器。 */
    suspend fun rawStopContainer(): BridgeResult<Boolean>

    /** 重启容器。 */
    suspend fun rawRestartContainer(): BridgeResult<Boolean>

    /** 查询容器状态（未绑定时返回 null）。 */
    suspend fun rawGetContainerStatus(): BridgeResult<ContainerStatusInfo?>

    /** 拉取容器累计输出。 */
    suspend fun rawGetContainerOutput(): BridgeResult<String>

    // ============================================================
    // Shizuku
    // ============================================================

    /** Shizuku 是否可用。 */
    fun isShizukuAvailable(): Boolean

    /** Shizuku 权限是否已授予。 */
    fun isShizukuPermissionGranted(): Boolean

    /** Shizuku 版本号（不可用时为 0）。 */
    fun getShizukuVersion(): Int

    /** 请求 Shizuku 权限（异步触发系统对话框）。 */
    fun requestShizukuPermission(requestCode: Int)

    // ============================================================
    // 设备
    // ============================================================

    /** 设备信息。 */
    fun getDeviceInfo(): DeviceInfo
}
