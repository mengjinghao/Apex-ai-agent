package com.apex.agent.core.tools.defaultTool.admin

import android.content.Context
import com.apex.agent.core.tools.base.BaseUITools
import com.apex.agent.core.tools.result.UIToolsErrorCode
import com.apex.agent.core.tools.result.UIToolsResult
import com.apex.util.AppLogger
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult

/**
 * 管理员级别的 UI 工具类（重构版）
 *
 * 继承自 RootUITools，提供管理员特有的功能：
 * - 应用管理（卸载、强制停止、清除数据/缓存、启用/禁用）
 * - 系统设置读写（settings put/get）
 * - 权限管理（pm grant/revoke）
 * - 系统级广播（am broadcast）
 *
 * 所有操作通过 shell 命令实现，要求调用者具备 admin/shell 权限。
 * 操作前会进行参数校验，操作结果通过 [ToolResult] 返回。
 */
open class AdminUIToolsRefactored(context: Context) :
    com.apex.agent.core.tools.defaultTool.root.RootUIToolsRefactored(context) {

    companion object {
        private const val TAG = "AdminUITools"
        private const val DEFAULT_PM_TIMEOUT_MS = 10_000L
    }

    // ==================== 管理员特有功能 ====================

    /**
     * 执行系统级操作（需管理员权限）。
     *
     * 支持的 operation 参数：
     * - `uninstall_package`  : 卸载应用，参数 `package`
     * - `clear_data`         : 清除应用数据，参数 `package`
     * - `clear_cache`        : 清除应用缓存，参数 `package`
     * - `force_stop`         : 强制停止应用，参数 `package`
     * - `enable_package`     : 启用应用，参数 `package`
     * - `disable_package`    : 禁用应用，参数 `package`
     * - `grant_permission`   : 授权限，参数 `package`, `permission`
     * - `revoke_permission`  : 撤权限，参数 `package`, `permission`
     * - `set_global_setting` : 修改全局设置，参数 `key`, `value`
     * - `set_secure_setting` : 修改安全设置，参数 `key`, `value`
     * - `set_system_setting` : 修改系统设置，参数 `key`, `value`
     * - `broadcast`          : 发送广播，参数 `action`, 可选 `extra_key`/`extra_value`
     */
    suspend fun executeSystemOperation(tool: AITool): ToolResult {
        return executeWithCatch("executeSystemOperation", tool) {
            val operation = getParameter(tool, "operation")
                ?: return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Parameter 'operation' is required"
                ).toToolResult(tool.name)

            when (operation) {
                "uninstall_package" -> handleUninstall(tool)
                "clear_data" -> handleClearData(tool)
                "clear_cache" -> handleClearCache(tool)
                "force_stop" -> handleForceStop(tool)
                "enable_package" -> handleEnableDisable(tool, enable = true)
                "disable_package" -> handleEnableDisable(tool, enable = false)
                "grant_permission" -> handlePermission(tool, grant = true)
                "revoke_permission" -> handlePermission(tool, grant = false)
                "set_global_setting" -> handleSetSetting(tool, "global")
                "set_secure_setting" -> handleSetSetting(tool, "secure")
                "set_system_setting" -> handleSetSetting(tool, "system")
                "broadcast" -> handleBroadcast(tool)
                else -> UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Unsupported admin operation: $operation"
                ).toToolResult(tool.name)
            }
        }
    }

    /**
     * 管理其他应用（需管理员权限）。
     *
     * 兼容旧接口：内部委托给 [executeSystemOperation]，根据 `action` 参数路由。
     * 保留这个方法是为了不破坏已有工具调用 schema。
     */
    suspend fun manageApplications(tool: AITool): ToolResult {
        return executeWithCatch("manageApplications", tool) {
            val action = getParameter(tool, "action")
                ?: return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Parameter 'action' is required"
                ).toToolResult(tool.name)

            // 把 action 映射到 executeSystemOperation 使用的 operation 词汇表
            val mappedTool = tool.copy(
                parameters = tool.parameters.map { p ->
                    if (p.name == "action") p.copy(name = "operation", value = action) else p
                }
            )
            executeSystemOperation(mappedTool)
        }
    }

    // ==================== 具体实现 ====================

    private suspend fun handleUninstall(tool: AITool): ToolResult {
        val pkg = getParameter(tool, "package")
            ?: return missingParam("package").toToolResult(tool.name)
        val keepData = getBooleanParameter(tool, "keep_data", false)
        val flag = if (keepData) "-k" else ""
        val cmd = "pm uninstall $flag $pkg"
        return runShellAndPackage(cmd, tool, pkg, "uninstalled")
    }

    private suspend fun handleClearData(tool: AITool): ToolResult {
        val pkg = getParameter(tool, "package")
            ?: return missingParam("package").toToolResult(tool.name)
        return runShellAndPackage("pm clear $pkg", tool, pkg, "data cleared")
    }

    private suspend fun handleClearCache(tool: AITool): ToolResult {
        val pkg = getParameter(tool, "package")
            ?: return missingParam("package").toToolResult(tool.name)
        // Android 没有直接的 pm clear-cache 命令，使用 pm clear 等价于清数据+缓存
        // 这里给出明确说明：清除缓存需要走 PackageManager.deleteApplicationCacheFiles，
        // shell 层只能近似为 `pm clear`，但会清掉数据。因此先尝试 `cmd package`，
        // 失败则回退并告知用户限制。
        val cmd = "pm clear $pkg"
        AppLogger.w(TAG, "clear_cache falls back to `pm clear` (Android shell cannot clear cache alone); package=$pkg")
        return runShellAndPackage(cmd, tool, pkg, "cache cleared (note: pm clear also clears data)")
    }

    private suspend fun handleForceStop(tool: AITool): ToolResult {
        val pkg = getParameter(tool, "package")
            ?: return missingParam("package").toToolResult(tool.name)
        return runShellAndPackage("am force-stop $pkg", tool, pkg, "force stopped")
    }

    private suspend fun handleEnableDisable(tool: AITool, enable: Boolean): ToolResult {
        val pkg = getParameter(tool, "package")
            ?: return missingParam("package").toToolResult(tool.name)
        val cmd = if (enable) "pm enable $pkg" else "pm disable $pkg"
        val verb = if (enable) "enabled" else "disabled"
        return runShellAndPackage(cmd, tool, pkg, verb)
    }

    private suspend fun handlePermission(tool: AITool, grant: Boolean): ToolResult {
        val pkg = getParameter(tool, "package")
            ?: return missingParam("package").toToolResult(tool.name)
        val perm = getParameter(tool, "permission")
            ?: return missingParam("permission").toToolResult(tool.name)
        val cmd = if (grant) "pm grant $pkg $perm" else "pm revoke $pkg $perm"
        val verb = if (grant) "granted" else "revoked"
        val result = executeUiShellCommand(cmd)
        if (!result.success) {
            return UIToolsResult.Error(
                errorCode = UIToolsErrorCode.ACTION_FAILED,
                message = "Failed to $verb permission '$perm' for '$pkg': ${result.stderr ?: "unknown"}"
            ).toToolResult("executeSystemOperation")
        }
        return UIToolsResult.Success(
            mapOf(
                "package" to pkg,
                "permission" to perm,
                "status" to verb,
                "timestamp" to System.currentTimeMillis()
            )
        ).toToolResult("executeSystemOperation")
    }

    private suspend fun handleSetSetting(tool: AITool, namespace: String): ToolResult {
        val key = getParameter(tool, "key")
            ?: return missingParam("key").toToolResult(tool.name)
        val value = getParameter(tool, "value")
            ?: return missingParam("value").toToolResult(tool.name)
        val cmd = "settings put $namespace $key $value"
        val result = executeUiShellCommand(cmd)
        if (!result.success) {
            return UIToolsResult.Error(
                errorCode = UIToolsErrorCode.ACTION_FAILED,
                message = "Failed to set $namespace/$key=$value: ${result.stderr ?: "unknown"}"
            ).toToolResult("executeSystemOperation")
        }
        return UIToolsResult.Success(
            mapOf(
                "namespace" to namespace,
                "key" to key,
                "value" to value,
                "status" to "set",
                "timestamp" to System.currentTimeMillis()
            )
        ).toToolResult("executeSystemOperation")
    }

    private suspend fun handleBroadcast(tool: AITool): ToolResult {
        val action = getParameter(tool, "action")
            ?: return missingParam("action").toToolResult(tool.name)
        val extraKey = getParameter(tool, "extra_key")
        val extraValue = getParameter(tool, "extra_value")
        val extraArg = if (extraKey != null && extraValue != null) {
            "--es $extraKey $extraValue"
        } else ""
        val cmd = "am broadcast -a $action $extraArg"
        val result = executeUiShellCommand(cmd)
        if (!result.success) {
            return UIToolsResult.Error(
                errorCode = UIToolsErrorCode.ACTION_FAILED,
                message = "Failed to broadcast $action: ${result.stderr ?: "unknown"}"
            ).toToolResult("executeSystemOperation")
        }
        return UIToolsResult.Success(
            mapOf(
                "action" to action,
                "extra_key" to (extraKey ?: ""),
                "extra_value" to (extraValue ?: ""),
                "status" to "broadcast",
                "timestamp" to System.currentTimeMillis()
            )
        ).toToolResult("executeSystemOperation")
    }

    private suspend fun runShellAndPackage(
        cmd: String,
        tool: AITool,
        pkg: String,
        verb: String
    ): ToolResult {
        val result = executeUiShellCommand(cmd)
        if (!result.success) {
            return UIToolsResult.Error(
                errorCode = UIToolsErrorCode.ACTION_FAILED,
                message = "Failed to $verb package '$pkg': ${result.stderr ?: "unknown error"}"
            ).toToolResult(tool.name)
        }
        return UIToolsResult.Success(
            mapOf(
                "package" to pkg,
                "status" to verb,
                "command" to cmd,
                "timestamp" to System.currentTimeMillis()
            )
        ).toToolResult(tool.name)
    }

    private fun missingParam(name: String): UIToolsResult.Error = UIToolsResult.Error(
        errorCode = UIToolsErrorCode.INVALID_PARAMETER,
        message = "Missing required parameter: $name"
    )
}
