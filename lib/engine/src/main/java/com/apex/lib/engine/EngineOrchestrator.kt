package com.apex.lib.engine

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun

/**
 * 引擎状态快照。
 *
 * [EngineOrchestrator.queryStatus] 一次性聚合当前所有关键状态，
 * 便于 UI / 监控一次性展示。
 *
 * @property bound 底层 EngineService 是否已绑定
 * @property engineVersion 引擎版本
 * @property shizuku Shizuku 策略
 * @property containerState 容器当前状态
 * @property toolCount 工具目录中已缓存的工具数量
 */
data class EngineStatusSnapshot(
    val bound: Boolean,
    val engineVersion: String,
    val shizuku: ShizukuCommandPolicy,
    val containerState: ContainerState,
    val toolCount: Int
)

/**
 * 引擎编排器（lib 层的高层 API）。
 *
 * 持有 [EngineGateway]，提供面向业务的命令路由 + 结果包装 + 日志埋点 + 状态同步：
 *   - [executeCommand]：统一 Shell 入口（可选 Shizuku 偏好 + 自动降级）
 *   - [executeShellViaShizuku]：直接走 Shizuku 通道（不可用时抛错，不降级）
 *   - [executeToolSafe]：工具执行（先校验工具是否注册，再走网关）
 *   - [listTools]：拉取底层工具列表并刷新 [ToolCatalog]
 *   - [manageContainer]：容器生命周期管理（驱动 [ContainerLifecycle]）
 *   - [queryContainerStatus]：查询容器状态并同步生命周期
 *   - [queryStatus]：聚合状态快照
 *
 * 所有 suspend API 均返回 [BridgeResult]，异常会被 [bridgeRun] 捕获包装。
 */
class EngineOrchestrator(private val gateway: EngineGateway) {

    /** 工具目录（在 [listTools] 调用后自动刷新）。 */
    val toolCatalog: ToolCatalog = ToolCatalog()

    /** 容器生命周期状态机。 */
    val containerLifecycle: ContainerLifecycle = ContainerLifecycle()

    init {
        ApexLog.i(
            ApexSuite.ApkId.ENGINE,
            "[Orchestrator] init; bound=${gateway.isBound()} engineVersion=${gateway.getEngineVersion()}"
        )
    }

    // ============================================================
    // Shell
    // ============================================================

    /**
     * 统一 Shell 执行入口。
     *
     * @param command 待执行的 shell 命令
     * @param preferShizuku 是否偏好 Shizuku 通道（true 时优先用 Shizuku，不可用则按 [ShizukuCommandPolicy.fallbackOnUnavailable] 降级）
     */
    suspend fun executeCommand(
        command: String,
        preferShizuku: Boolean = false
    ): BridgeResult<ShellResult> = bridgeRun {
        val trace = Trace.newId("cmd")
        val policy = currentShizukuPolicy()
        val useShizuku = policy.shouldUseShizuku(preferShizuku)
        ApexLog.d(
            ApexSuite.ApkId.ENGINE,
            "[Orchestrator] executeCommand trace=$trace preferShizuku=$preferShizuku useShizuku=$useShizuku ${policy.describe()} cmd=${command.take(120)}"
        )
        when {
            useShizuku -> {
                val r = gateway.rawExecuteShellViaShizuku(command).getOrNull()
                    ?: throw IllegalStateException("Shizuku execution failed")
                ShellResult(
                    stdout = r.output,
                    stderr = r.error,
                    exitCode = r.exitCode,
                    success = r.isSuccess,
                    executionTimeMs = 0L
                )
            }
            else -> {
                if (policy.shouldFallback(preferShizuku)) {
                    ApexLog.w(
                        ApexSuite.ApkId.ENGINE,
                        "[Orchestrator] Shizuku unavailable (${policy.availability().name}), fallback to normal shell"
                    )
                }
                gateway.rawExecuteShell(command).getOrNull()
                    ?: throw IllegalStateException("Shell execution failed")
            }
        }
    }

    /**
     * 直接走 Shizuku 通道执行 shell。
     *
     * 与 [executeCommand] 不同：本方法不降级，Shizuku 不可用直接抛错。
     * 用于上层明确要求「必须用 Shizuku」的场景。
     */
    suspend fun executeShellViaShizuku(command: String): BridgeResult<ShizukuCommandResult> = bridgeRun {
        val policy = currentShizukuPolicy()
        if (policy.availability() != ShizukuAvailability.AVAILABLE_AND_AUTHORIZED) {
            throw IllegalStateException("Shizuku not available or not authorized: ${policy.availability().name}")
        }
        ApexLog.d(
            ApexSuite.ApkId.ENGINE,
            "[Orchestrator] executeShellViaShizuku cmd=${command.take(120)}"
        )
        gateway.rawExecuteShellViaShizuku(command).getOrNull()
            ?: throw IllegalStateException("Shizuku execution failed")
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 执行内置工具（带工具名校验）。
     *
     * 若 [ToolCatalog] 为空会先自动调用 [listTools] 拉取一次。
     */
    suspend fun executeToolSafe(toolName: String, args: String): BridgeResult<ShellResult> = bridgeRun {
        ensureToolCatalog()
        val tool = toolCatalog.get(toolName)
            ?: throw IllegalArgumentException("unknown tool: $toolName")
        ApexLog.d(
            ApexSuite.ApkId.ENGINE,
            "[Orchestrator] executeToolSafe name=$toolName category=${tool.category} requiresRoot=${tool.requiresRoot} args=${args.take(80)}"
        )
        gateway.rawExecuteTool(toolName, args).getOrNull()
            ?: throw IllegalStateException("tool execution failed: $toolName")
    }

    /** 拉取底层工具列表，并刷新 [toolCatalog]。 */
    suspend fun listTools(): BridgeResult<List<ToolDescriptor>> = bridgeRun {
        val list = gateway.rawListTools().getOrNull()
            ?: throw IllegalStateException("listTools failed")
        toolCatalog.refresh(list)
        ApexLog.i(ApexSuite.ApkId.ENGINE, "[Orchestrator] listed tools: ${list.size}")
        list
    }

    // ============================================================
    // 容器
    // ============================================================

    /**
     * 容器生命周期管理。
     *
     * 会同步驱动 [containerLifecycle] 状态迁移。
     */
    suspend fun manageContainer(action: ContainerAction): BridgeResult<Boolean> = bridgeRun {
        ApexLog.i(ApexSuite.ApkId.ENGINE, "[Orchestrator] manageContainer action=${action.name}")
        when (action) {
            ContainerAction.START -> {
                containerLifecycle.onActionStart()
                val ok = gateway.rawStartContainer().getOrNull() ?: false
                if (!ok) containerLifecycle.onActionError("start returned false")
                ok
            }
            ContainerAction.STOP -> {
                containerLifecycle.onActionStop()
                val ok = gateway.rawStopContainer().getOrNull() ?: false
                if (ok) containerLifecycle.onActionRecovered()
                else containerLifecycle.onActionError("stop returned false")
                ok
            }
            ContainerAction.RESTART -> {
                containerLifecycle.onActionRestart()
                val ok = gateway.rawRestartContainer().getOrNull() ?: false
                if (!ok) containerLifecycle.onActionError("restart returned false")
                ok
            }
        }
    }

    /** 查询容器状态，并同步 [containerLifecycle]。 */
    suspend fun queryContainerStatus(): BridgeResult<ContainerStatusInfo?> = bridgeRun {
        val info = gateway.rawGetContainerStatus().getOrNull()
        if (info != null) {
            containerLifecycle.syncFromStatusCode(info.statusCode)
        }
        info
    }

    /** 拉取容器累计输出。 */
    suspend fun getContainerOutput(): BridgeResult<String> = bridgeRun {
        gateway.rawGetContainerOutput().getOrNull() ?: ""
    }

    // ============================================================
    // 状态聚合
    // ============================================================

    /**
     * 一次性聚合当前所有关键状态。
     */
    suspend fun queryStatus(): BridgeResult<EngineStatusSnapshot> = bridgeRun {
        EngineStatusSnapshot(
            bound = gateway.isBound(),
            engineVersion = gateway.getEngineVersion(),
            shizuku = currentShizukuPolicy(),
            containerState = containerLifecycle.current(),
            toolCount = toolCatalog.size()
        )
    }

    // ============================================================
    // Shizuku 辅助
    // ============================================================

    /** 当前 Shizuku 策略（每次调用即时构造，反映最新底层状态）。 */
    fun currentShizukuPolicy(): ShizukuCommandPolicy = ShizukuCommandPolicy(
        isAvailable = gateway.isShizukuAvailable(),
        isAuthorized = gateway.isShizukuPermissionGranted(),
        version = gateway.getShizukuVersion()
    )

    /** 请求 Shizuku 权限（透传给网关）。 */
    fun requestShizukuPermission(requestCode: Int = 0) {
        ApexLog.i(ApexSuite.ApkId.ENGINE, "[Orchestrator] requestShizukuPermission requestCode=$requestCode")
        gateway.requestShizukuPermission(requestCode)
    }

    /** 设备信息（透传给网关）。 */
    fun getDeviceInfo(): DeviceInfo = gateway.getDeviceInfo()

    // ============================================================
    // 内部辅助
    // ============================================================

    /** 工具目录为空时先拉取一次，避免 [executeToolSafe] 误判工具不存在。 */
    private suspend fun ensureToolCatalog() {
        if (toolCatalog.size() == 0) {
            ApexLog.d(ApexSuite.ApkId.ENGINE, "[Orchestrator] toolCatalog empty, auto-refreshing")
            listTools()
        }
    }
}
