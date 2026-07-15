package com.apex.agent.core.tools.defaultTool.debugger

import android.content.Context
import com.apex.agent.core.tools.StringResultData
import com.apex.agent.core.tools.UIActionResultData
import com.apex.agent.core.tools.base.BaseUITools
import com.apex.agent.core.tools.result.UIToolsErrorCode
import com.apex.agent.core.tools.result.UIToolsResult
import com.apex.agent.core.tools.system.ShellIdentity
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult
import com.apex.agent.data.repository.UIHierarchyManager
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.apex.agent.core.tools.defaultTool.standard.name
import com.apex.agent.core.tools.system.AndroidShellExecutor

/**
 * 调试级别的UI工具类（重构版）
 * 
 * 智能降级策略�? * 1. 如果指定了display参数或无障碍服务未启动的使用Shell命令
 * 2. 否则 的使用无障碍服的
 * 
 * 继承自AccessibilityUITools，提供灵活的执行策略
 */
open class DebuggerUIToolsRefactored(context: Context) : com.apex.agent.core.tools.defaultTool.accessbility.AccessibilityUIToolsRefactored(context) {

    companion object {
        private const val TAG = "DebuggerUITools"
    }

    /** Shell身份标识（可选） */
    protected open val uiShellIdentity: ShellIdentity? = null

    // ==================== 核心功能（带智能降级�?==================

    /**
     * 点击坐标（智能降级）
     */
    override suspend fun tap(tool: AITool): ToolResult {
        // 如果没有display参数且无障碍服务启用，使用无障碍点击
    if (!hasDisplayParam(tool) && UIHierarchyManager.isAccessibilityServiceEnabled(context)) {
            AppLogger.d(TAG, "无障碍服务已启用，使用无障碍点击")
        return super.tap(tool)
        }

        // 否则使用Shell命令点击
    return executeWithCatch("tap", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("x", "y"),
                optionalParams = listOf("display")
            )

            // 2. 获取参数
    val x = getRequiredParameter(tool, "x").toIntOrNull()
        val y = getRequiredParameter(tool, "y").toIntOrNull()
        if (x == null || y == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示点击效果
            showTapOverlay(x, y)

            // 4. 执行Shell点击命令
    val displayArg = getDisplayArg(tool)
        val command = "input ${displayArg}tap ${x} ${y}"
        val result = executeUiShellCommand(command)
        if (!result.success) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to tap at (${x}, ${y}): ${result.stderr ?: "Unknown error"}"
                ).toToolResult(tool.name)
            }

            // 5. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                UIActionResultData(
                    actionType = "tap",
                    actionDescription = "Successfully tapped at coordinates (${x}, ${y}) via shell command",
                    coordinates = Pair(x, y)
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 长按坐标（智能降级）
     */
    override suspend fun longPress(tool: AITool): ToolResult {
        // 如果没有display参数且无障碍服务启用，使用无障碍长按
    if (!hasDisplayParam(tool) && UIHierarchyManager.isAccessibilityServiceEnabled(context)) {
            AppLogger.d(TAG, "无障碍服务已启用，使用无障碍长按")
        return super.longPress(tool)
        }

        // 否则使用Shell命令长按
    return executeWithCatch("longPress", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("x", "y"),
                optionalParams = listOf("duration", "display")
            )

            // 2. 获取参数
    val x = getRequiredParameter(tool, "x").toIntOrNull()
        val y = getRequiredParameter(tool, "y").toIntOrNull()
        if (x == null || y == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示长按效果
            showTapOverlay(x, y)

            // 4. 执行Shell长按命令（使用swipe模模�?
    val displayArg = getDisplayArg(tool)
        val command = "input ${displayArg}swipe ${x} ${y} ${x} ${y} 800"
        val result = executeUiShellCommand(command)
        if (!result.success) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to long press at (${x}, ${y}): ${result.stderr ?: "Unknown error"}"
                ).toToolResult(tool.name)
            }

            // 5. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                UIActionResultData(
                    actionType = "long_press",
                    actionDescription = "Successfully long pressed at (${x}, ${y}) via shell command",
                    coordinates = Pair(x, y)
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 滑动屏幕（智能降级）
     */
    override suspend fun swipe(tool: AITool): ToolResult {
        // 如果没有display参数且无障碍服务启用，使用无障碍滑动
    if (!hasDisplayParam(tool) && UIHierarchyManager.isAccessibilityServiceEnabled(context)) {
            AppLogger.d(TAG, "无障碍服务已启用，使用无障碍滑动")
        return super.swipe(tool)
        }

        // 否则使用Shell命令滑动
    return executeWithCatch("swipe", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("start_x", "start_y", "end_x", "end_y"),
                optionalParams = listOf("duration", "display")
            )

            // 2. 获取参数
    val startX = getRequiredParameter(tool, "start_x").toIntOrNull()
        val startY = getRequiredParameter(tool, "start_y").toIntOrNull()
        val endX = getRequiredParameter(tool, "end_x").toIntOrNull()
        val endY = getRequiredParameter(tool, "end_y").toIntOrNull()
        val duration = getParameter(tool, "duration", "300").toIntOrNull() ?: 300

            if (startX == null || startY == null || endX == null || endY == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Missing or invalid coordinates. 'start_x', 'start_y', 'end_x', and 'end_y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示滑动效果
            showSwipeOverlay(startX, startY, endX, endY)

            // 4. 执行Shell滑动命令
    val displayArg = getDisplayArg(tool)
        val command = "input ${displayArg}swipe ${startX} ${startY} ${endX} ${endY} ${duration}"
        val result = executeUiShellCommand(command)
        if (!result.success) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to perform swipe: ${result.stderr ?: "Unknown error"}"
                ).toToolResult(tool.name)
            }

            // 5. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                UIActionResultData(
                    actionType = "swipe",
                    actionDescription = "Successfully swiped from (${startX}, ${startY}) to (${endX}, ${endY})"
                )
            ).toToolResult(tool.name)
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查是否包含display参数
     */
    private fun hasDisplayParam(tool: AITool): Boolean {
        return tool.parameters.any { param ->
            param.name.equals("display", ignoreCase = true)
        }
    }

    /**
     * 获取display参数
     */
    private fun getDisplayArg(tool: AITool): String {
        val display = tool.parameters.find { it.name.equals("display", ignoreCase = true) }?.value?.trim()
        return if (!display.isNullOrEmpty()) "-d ${display} " else ""
    }

    /**
     * 执行UI shell命令
     */
    protected suspend fun executeUiShellCommand(command: String): com.apex.agent.core.tools.system.AndroidShellExecutor.CommandResult {
        return com.apex.agent.core.tools.system.AndroidShellExecutor.executeShellCommand(command, uiShellIdentity)
    }

    /**
     * 显示点击overlay
     */
    private suspend fun showTapOverlay(x: Int, y: Int) {
        withContext(Dispatchers.Main) {
            operationOverlay.showTap(x, y)
        }
    }

    /**
     * 显示滑动overlay
     */
    private suspend fun showSwipeOverlay(startX: Int, startY: Int, endX: Int, endY: Int) {
        withContext(Dispatchers.Main) {
            operationOverlay.showSwipe(startX, startY, endX, endY)
        }
    }

    /**
     * 隐藏overlay
     */
    private suspend fun hideOverlay() {
        withContext(Dispatchers.Main) {
            operationOverlay.hide()
        }
    }
}
