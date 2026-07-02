package com.apex.agent.core.tools.defaultTool.root

import android.content.Context
import com.apex.agent.core.tools.StringResultData
import com.apex.agent.core.tools.UIActionResultData
import com.apex.agent.core.tools.base.BaseUITools
import com.apex.agent.core.tools.config.UIToolsConfig
import com.apex.agent.core.tools.parser.XmlLayoutParser
import com.apex.agent.core.tools.result.UIToolsErrorCode
import com.apex.agent.core.tools.result.UIToolsResult
import com.apex.agent.core.tools.system.ShellIdentity
import com.apex.data.model.AITool
import com.apex.data.model.ToolParameter
import com.apex.data.model.ToolResult
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root级别的UI工具类（重构版）
 * 
 * 使用shell命令(uiautomator, input)实现强大的UI自动�? * 不依赖无障碍服务，直接通过系统shell执行操作
 */
open class RootUIToolsRefactored(context: Context) : BaseUITools(context) {

    companion object {
        private const val TAG = "RootUITools"
    }

    /** Shell身份标识 */
    override val uiShellIdentity: ShellIdentity = ShellIdentity.SHELL

    /** XML布局解析�?/
    private val xmlParser = XmlLayoutParser()

    // ==================== 核心功能 ====================

    /**
     * 点击指定坐标
     */
    override suspend fun tap(tool: AITool): ToolResult {
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

            // 4. 执行点击命令
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
                    actionDescription = "Successfully tapped at (${x}, ${y}) via shell command",
                    coordinates = Pair(x, y)
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 长按指定坐标
     */
    override suspend fun longPress(tool: AITool): ToolResult {
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
            val durationMs = getParameter(tool, "duration", "800").toIntOrNull() ?: 800

            if (x == null || y == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示长按效果
            showTapOverlay(x, y)

            // 4. 执行长按命令（使用swipe模拟长按�?            val displayArg = getDisplayArg(tool)
            val command = "input ${displayArg}swipe ${x} ${y} ${x} ${y} ${durationMs}"
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
     * 滑动屏幕
     */
    override suspend fun swipe(tool: AITool): ToolResult {
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

            // 4. 执行滑动命令
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

    /**
     * 输入文本
     */
    override suspend fun setInputText(tool: AITool): ToolResult {
        return executeWithCatch("setInputText", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("text"),
                optionalParams = listOf("display")
            )

            // 2. 获取参数
            val text = getRequiredParameter(tool, "text")

            // 3. 显示输入效果
            showTextInputOverlay(text)

            // 4. 清空输入�?            executeUiShellCommand("input ${getDisplayArg(tool)}keyevent KEYCODE_CLEAR")
            kotlinx.coroutines.delay(300)

            // 5. 如果文本为空，只清空
            if (text.isEmpty()) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Success(
                    UIActionResultData("textInput", "Successfully cleared input field")
                ).toToolResult(tool.name)
            }

            // 6. 设置剪贴板并粘贴
            setClipboardText(text)
            kotlinx.coroutines.delay(100)

            val pasteResult = executeUiShellCommand("input ${getDisplayArg(tool)}keyevent KEYCODE_PASTE")

            if (!pasteResult.success) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to paste text: ${pasteResult.stderr ?: "Unknown error"}"
                ).toToolResult(tool.name)
            }

            // 7. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                UIActionResultData(
                    "textInput",
                    "Successfully set input text to: ${text} via clipboard paste"
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 按键事件
     */
    override suspend fun pressKey(tool: AITool): ToolResult {
        return executeWithCatch("pressKey", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("key_code"),
                optionalParams = listOf("display")
            )

            // 2. 获取参数
            val keyCode = getRequiredParameter(tool, "key_code")

            // 3. 执行按键命令
            val result = executeUiShellCommand("input ${getDisplayArg(tool)}keyevent ${keyCode}")

            if (!result.success) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to press key: ${result.stderr ?: "Unknown error"}"
                ).toToolResult(tool.name)
            }

            UIToolsResult.Success(
                UIActionResultData("keyPress", "Successfully pressed key: ${keyCode}")
            ).toToolResult(tool.name)
        }
    }

    /**
     * 获取页面信息
     */
    override suspend fun getPageInfo(tool: AITool): ToolResult {
        return executeWithCatch("getPageInfo", tool) {
            // 1. 从shell获取UI数据
            val uiData = getUIDataFromShell(tool)
            
            if (uiData == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.SERVICE_UNAVAILABLE,
                    message = "Failed to retrieve UI data."
                ).toToolResult(tool.name)
            }

            // 2. 提取焦点信息
            val focusInfo = extractFocusInfoFromShell(uiData.windowInfo)

            // 3. 简化布局
            val simplifiedLayout = xmlParser.parseAndSimplify(uiData.uiXml)

            // 4. 构建结果
            val resultData = com.apex.agent.core.tools.UIPageResultData(
                packageName = focusInfo.packageName ?: "Unknown",
                activityName = focusInfo.activityName ?: "Unknown",
                uiElements = simplifiedLayout
            )

            UIToolsResult.Success(resultData).toToolResult(tool.name)
        }
    }

    // ==================== 辅助方法 ====================

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
     * 从shell获取UI数据
     */
    private suspend fun getUIDataFromShell(tool: AITool): UIData? {
        return try {
            AppLogger.d(TAG, "Getting UI data via ADB")

            val displayId = tool.parameters
                .find { it.name.equals("display", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            var dumpResult = if (displayId != null) {
                val cmd = "uiautomator dump --display-id ${displayId} /sdcard/window_dump.xml"
                AppLogger.d(TAG, "UI dump using explicit display-id=${displayId}")
                executeUiShellCommand(cmd)
            } else {
                executeUiShellCommand("uiautomator dump /sdcard/window_dump.xml")
            }

            if (!dumpResult.success && displayId != null) {
                AppLogger.w(TAG, "uiautomator dump with explicit display-id failed, falling back: ${dumpResult.stderr}")
                dumpResult = executeUiShellCommand("uiautomator dump /sdcard/window_dump.xml")
            }

            if (!dumpResult.success) {
                AppLogger.e(TAG, "uiautomator dump failed: ${dumpResult.stderr}")
                return null
            }

            val readResult = executeUiShellCommand("cat /sdcard/window_dump.xml")
            if (!readResult.success) {
                AppLogger.e(TAG, "Reading UI dump file failed: ${readResult.stderr}")
                return null
            }

            var windowInfo = getWindowInfoFromShell()
            if (windowInfo.isEmpty()) {
                AppLogger.w(TAG, "Failed to get window info, retrying after 500ms")
                kotlinx.coroutines.delay(500)
                windowInfo = getWindowInfoFromShell()
            }

            UIData(readResult.stdout, windowInfo)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting UI data", e)
            null
        }
    }

    /**
     * 从shell获取窗口信息
     */
    private suspend fun getWindowInfoFromShell(): String {
        val commands = listOf(
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'",
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
            "dumpsys activity activities | grep -E 'topResumedActivity|topActivity'"
        )
        
        for (command in commands) {
            try {
                val result = executeUiShellCommand(command)
                if (result.success && result.stdout.isNotBlank()) {
                    AppLogger.d(TAG, "Successfully got window info with: ${command}")
                    return result.stdout
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Command failed: '${command}'", e)
            }
        }
        
        AppLogger.e(TAG, "All attempts to get window info failed.")
        return ""
    }

    /**
     * 从shell提取焦点信息
     */
    private fun extractFocusInfoFromShell(windowInfo: String): FocusInfo {
        if (windowInfo.isBlank()) {
            AppLogger.w(TAG, "Window info is empty, cannot extract focus.")
            return FocusInfo()
        }

        val patterns = listOf(
            "mCurrentFocus=.*?\\s+([a-zA-Z0-9_.]+)/([^\\s}]+)".toRegex(),
            "mFocusedApp=.*?ActivityRecord\\{.*?\\s+([a-zA-Z0-9_.]+)/\\.?([^\\s}]+)".toRegex(),
            "topActivity=ComponentInfo\\{([a-zA-Z0-9_.]+)/\\.?([^}]+)\\}".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(windowInfo)
            if (match != null && match.groupValues.size >= 3) {
                val packageName = match.groupValues[1]
                val activityName = match.groupValues[2]
                AppLogger.d(TAG, "Extracted from pattern: ${packageName}/${activityName}")
                return FocusInfo(packageName, activityName)
            }
        }

        AppLogger.w(TAG, "Could not extract focus info from window data.")
        return FocusInfo()
    }

    /**
     * 设置剪贴板文�?     */
    private suspend fun setClipboardText(text: String) {
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("apex-agent_input", text))
        }
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
     * 显示文本输入overlay
     */
    private suspend fun showTextInputOverlay(text: String) {
        withContext(Dispatchers.Main) {
            val displayMetrics = context.resources.displayMetrics
            operationOverlay.showTextInput(displayMetrics.widthPixels / 2, displayMetrics.heightPixels / 2, text)
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

    // ==================== 数据�?===================

    /**
     * UI数据
     */
    private data class UIData(val uiXml: String, val windowInfo: String)

    /**
     * 焦点信息
     */
    private data class FocusInfo(
        val packageName: String? = null,
        val activityName: String? = null
    )
}
