package com.apex.agent.core.tools.defaultTool.accessbility

import android.content.Context
import com.apex.agent.core.tools.SimplifiedUINode
import com.apex.agent.core.tools.StringResultData
import com.apex.agent.core.tools.UIPageResultData
import com.apex.agent.core.tools.base.BaseUITools
import com.apex.agent.core.tools.config.UIToolsConfig
import com.apex.agent.core.tools.parser.XmlLayoutParser
import com.apex.agent.core.tools.result.UIToolsErrorCode
import com.apex.agent.core.tools.result.UIToolsResult
import com.apex.agent.core.tools.selector.ElementSelector
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult
import com.apex.agent.data.repository.UIHierarchyManager
import com.apex.util.AppLogger
import kotlinx.coroutines.delay

/**
 * 无障碍级别的UI工具类（重构版）
 * 
 * 使用Android无障碍服务API实现UI操作
 * 继承自BaseUITools，使用统一的架构和配置
 */
open class AccessibilityUITools(context: Context) : BaseUITools(context) {

    companion object {
        private const val TAG = "AccessibilityUITools"
    }

    /** XML布局解析�?/
    private val xmlParser = XmlLayoutParser()

    // ==================== 核心功能 ====================

    /**
     * 获取当前页面信息
     */
    override suspend fun getPageInfo(tool: AITool): ToolResult {
        return executeWithCatch("getPageInfo", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = emptyList(),
                optionalParams = listOf("format", "detail")
            )

            // 2. 检查无障碍服务
            checkAccessibilityService()

            // 3. 获取参数
            val format = getParameter(tool, "format", "xml")
            val detail = getParameter(tool, "detail", "summary")

            // 4. 验证format参数
            if (format !in listOf("xml", "json")) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Invalid format specified. Must be 'xml' or 'json'."
                ).toToolResult(tool.name)
            }

            // 5. 获取UI层次结构（带重测�?            val uiXml = executeWithRetry(
                operation = { UIHierarchyManager.getUIHierarchy(context) },
                maxRetries = UIToolsConfig.MAX_RETRY_COUNT,
                delayMs = UIToolsConfig.RETRY_DELAY_MS,
                errorMessage = "获取UI层次结构失败"
            )

            if (uiXml.isEmpty()) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.SERVICE_UNAVAILABLE,
                    message = "Failed to retrieve UI data via accessibility service."
                ).toToolResult(tool.name)
            }

            // 6. 提取窗口信息
            val focusInfo = extractFocusInfo(uiXml)

            // 7. 简化布局
            val simplifiedLayout = xmlParser.parseAndSimplify(uiXml)

            // 8. 构建结果
            val resultData = UIPageResultData(
                packageName = focusInfo.packageName ?: "Unknown",
                activityName = focusInfo.activityName ?: "Unknown",
                uiElements = simplifiedLayout
            )

            UIToolsResult.Success(resultData).toToolResult(tool.name)
        }
    }

    /**
     * 点击UI元素
     */
    override suspend fun clickElement(tool: AITool): ToolResult {
        return executeWithCatch("clickElement", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = emptyList(),
                optionalParams = listOf("resourceId", "className", "contentDesc", "bounds", "index")
            )

            // 2. 检查无障碍服务
            checkAccessibilityService()

            // 3. 获取参数
            val resourceId = getParameter(tool, "resourceId", null)
            val className = getParameter(tool, "className", null)
            val contentDesc = getParameter(tool, "contentDesc", null)
            val bounds = getParameter(tool, "bounds", null)
            val index = getParameter(tool, "index", "0").toIntOrNull() ?: 0

            // 4. 至少需要一个选择条件
            if (resourceId == null && className == null && bounds == null && contentDesc == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.MISSING_PARAMETER,
                    message = "Missing element identifier. Provide at least one of 'resourceId', 'className', 'contentDesc', or 'bounds'."
                ).toToolResult(tool.name)
            }

            // 5. 如果提供了bounds，直接点�?            if (bounds != null) {
                return@executeWithCatch handleClickByBounds(bounds).toToolResult(tool.name)
            }

            // 6. 获取UI层次结构
            val uiXml = executeWithRetry(
                operation = { UIHierarchyManager.getUIHierarchy(context) },
                maxRetries = UIToolsConfig.MAX_RETRY_COUNT,
                delayMs = UIToolsConfig.RETRY_DELAY_MS,
                errorMessage = "获取UI层次结构失败"
            )

            if (uiXml.isEmpty()) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.SERVICE_UNAVAILABLE,
                    message = "Unable to get UI hierarchy."
                ).toToolResult(tool.name)
            }

            // 7. 查找匹配的元�?            val selector = ElementSelector(
                resourceId = resourceId,
                className = className,
                contentDesc = contentDesc,
                index = index
            )

            val matchedNodes = xmlParser.findNodes(uiXml, selector)

            if (matchedNodes.isEmpty()) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ELEMENT_NOT_FOUND,
                    message = "No matching element found."
                ).toToolResult(tool.name)
            }

            // 8. 检查索引范�?            if (index < 0 || index >= matchedNodes.size) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Index out of range. Found ${matchedNodes.size} elements, but requested index ${index}."
                ).toToolResult(tool.name)
            }

            // 9. 获取目标节点的bounds并点�?            val targetNodeBounds = matchedNodes[index].bounds
            if (targetNodeBounds == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ELEMENT_INVALID,
                    message = "Target element has no bounds."
                ).toToolResult(tool.name)
            }

            handleClickByBounds(targetNodeBounds).toToolResult(tool.name)
        }
    }

    /**
     * 点击坐标
     */
    override suspend fun tap(tool: AITool): ToolResult {
        return executeWithCatch("tap", tool) {
            // 1. 检查无障碍服务
            checkAccessibilityService()

            // 2. 获取参数
            val x = getRequiredParameter(tool, "x").toIntOrNull()
            val y = getRequiredParameter(tool, "y").toIntOrNull()

            if (x == null || y == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_COORDINATES,
                    message = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示点击反馈
            showTapOverlay(x, y)

            // 4. 执行无障碍点�?            val result = performAccessibilityClick(x, y)

            if (!result) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.OPERATION_FAILED,
                    message = "Failed to tap at coordinates via accessibility service."
                ).toToolResult(tool.name)
            }

            // 5. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                com.apex.agent.core.tools.UIActionResultData(
                    actionType = "tap",
                    actionDescription = "Successfully tapped at coordinates (${x}, ${y}) via accessibility service",
                    coordinates = Pair(x, y)
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 长按坐标
     */
    override suspend fun longPress(tool: AITool): ToolResult {
        return executeWithCatch("longPress", tool) {
            // 1. 检查无障碍服务
            checkAccessibilityService()

            // 2. 获取参数
            val x = getRequiredParameter(tool, "x").toIntOrNull()
            val y = getRequiredParameter(tool, "y").toIntOrNull()

            if (x == null || y == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_COORDINATES,
                    message = "Missing or invalid coordinates. Both 'x' and 'y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示长按反馈
            showTapOverlay(x, y)

            // 4. 执行无障碍长�?            val result = performAccessibilityLongPress(x, y)

            if (!result) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.OPERATION_FAILED,
                    message = "Failed to long press at coordinates via accessibility service."
                ).toToolResult(tool.name)
            }

            // 5. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                com.apex.agent.core.tools.UIActionResultData(
                    actionType = "long_press",
                    actionDescription = "Successfully long pressed at coordinates (${x}, ${y}) via accessibility service",
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
            // 1. 检查无障碍服务
            checkAccessibilityService()

            // 2. 获取参数
            val startX = getRequiredParameter(tool, "start_x").toIntOrNull()
            val startY = getRequiredParameter(tool, "start_y").toIntOrNull()
            val endX = getRequiredParameter(tool, "end_x").toIntOrNull()
            val endY = getRequiredParameter(tool, "end_y").toIntOrNull()
            val duration = getParameter(tool, "duration", "300")?.toIntOrNull() ?: 300

            if (startX == null || startY == null || endX == null || endY == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_COORDINATES,
                    message = "Missing or invalid coordinates. 'start_x', 'start_y', 'end_x', and 'end_y' must be valid integers."
                ).toToolResult(tool.name)
            }

            // 3. 显示滑动反馈
            showSwipeOverlay(startX, startY, endX, endY)

            // 4. 执行无障碍滑�?            val result = performAccessibilitySwipe(startX, startY, endX, endY, duration)

            if (!result) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.SWIPE_FAILED,
                    message = "Failed to perform swipe via accessibility service."
                ).toToolResult(tool.name)
            }

            // 5. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                com.apex.agent.core.tools.UIActionResultData(
                    actionType = "swipe",
                    actionDescription = "Successfully performed swipe from (${startX}, ${startY}) to (${endX}, ${endY}) via accessibility service"
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 输入文本
     */
    override suspend fun setInputText(tool: AITool): ToolResult {
        return executeWithCatch("setInputText", tool) {
            // 1. 检查无障碍服务
            checkAccessibilityService()

            // 2. 获取参数
            val text = getRequiredParameter(tool, "text")

            // 3. 查找焦点节点
            val focusedNodeId = UIHierarchyManager.findFocusedNodeId(context)
            if (focusedNodeId.isNullOrEmpty()) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ELEMENT_NOT_FOUND,
                    message = "No focused editable field found."
                ).toToolResult(tool.name)
            }

            // 4. 显示输入反馈
            val rect = parseBounds(focusedNodeId)
            if (rect != null) {
                showTextInputOverlay(rect.first, rect.second, text)
            }

            // 5. 设置文本
            val result = UIHierarchyManager.setTextOnNode(context, focusedNodeId, text)

            if (!result) {
                hideOverlay()
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INPUT_FAILED,
                    message = "Failed to set text via accessibility service."
                ).toToolResult(tool.name)
            }

            // 6. 隐藏overlay并返回结�?            hideOverlay()
            
            UIToolsResult.Success(
                com.apex.agent.core.tools.UIActionResultData(
                    actionType = "textInput",
                    actionDescription = "Successfully set input text via accessibility service"
                )
            ).toToolResult(tool.name)
        }
    }

    /**
     * 按键事件
     */
    override suspend fun pressKey(tool: AITool): ToolResult {
        return executeWithCatch("pressKey", tool) {
            // 1. 检查无障碍服务
            checkAccessibilityService()

            // 2. 获取参数
            val keyCode = getRequiredParameter(tool, "key_code")

            // 3. 转换为无障碍服务常量
            val keyAction = when (keyCode) {
                "KEYCODE_BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "KEYCODE_HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "KEYCODE_RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "KEYCODE_NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "KEYCODE_QUICK_SETTINGS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "KEYCODE_POWER_DIALOG" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                else -> null
            }

            if (keyAction == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Key: ${keyCode} is not supported via accessibility service. Only system keys like BACK, HOME, etc. are supported."
                ).toToolResult(tool.name)
            }

            // 4. 执行全局操作
            val success = UIHierarchyManager.performGlobalAction(context, keyAction)

            if (!success) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.OPERATION_FAILED,
                    message = "Failed to press key: ${keyCode} via accessibility service. Not all keys are supported."
                ).toToolResult(tool.name)
            }

            UIToolsResult.Success(
                com.apex.agent.core.tools.UIActionResultData(
                    actionType = "keyPress",
                    actionDescription = "Successfully pressed key: ${keyCode} via accessibility service"
                )
            ).toToolResult(tool.name)
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查无障碍服务是否启用
     */
    private suspend fun checkAccessibilityService() {
        if (!UIHierarchyManager.isAccessibilityServiceEnabled(context)) {
            throw IllegalStateException(
                "Accessibility Service is not enabled. Please enable it in system settings to use this feature."
            )
        }
    }

    /**
     * 提取窗口焦点信息
     */
    private suspend fun extractFocusInfo(uiXml: String): FocusInfo {
        return try {
            // 1. 从XML中解析包�?            val (packageName, _) = UIHierarchyManager.extractWindowInfo(uiXml)

            // 2. 从服务中获取Activity名称
            val activityName = UIHierarchyManager.getCurrentActivityName(context)

            FocusInfo(
                packageName = packageName ?: "android",
                activityName = activityName ?: "ForegroundActivity"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "从XML解析焦点信息时出�? e)
            FocusInfo(
                packageName = "android",
                activityName = "ForegroundActivity"
            )
        }
    }

    /**
     * 通过bounds坐标点击
     */
    private suspend fun handleClickByBounds(bounds: String): UIToolsResult {
        return try {
            // 解析bounds格式: [left,top][right,bottom]
            val coords = parseBounds(bounds)
            if (coords == null) {
                return UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.INVALID_PARAMETER,
                    message = "Invalid bounds format: ${bounds}"
                )
            }

            val (left, top, right, bottom) = coords
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2

            // 调用 UIHierarchyManager 通过无障碍服务执行点击
            AppLogger.d(TAG, "点击坐标: ($centerX, $centerY)")
            val clicked = UIHierarchyManager.performClick(context, centerX, centerY)
            if (!clicked) {
                return UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to perform click at ($centerX, $centerY); accessibility service may not be bound"
                )
            }

            UIToolsResult.Success(mapOf(
                "action" to "click",
                "x" to centerX,
                "y" to centerY,
                "bounds" to bounds,
                "executed" to true
            ))
        } catch (e: Exception) {
            AppLogger.e(TAG, "点击元素失败", e)
            UIToolsResult.Error(
                errorCode = UIToolsErrorCode.ACTION_FAILED,
                message = "Failed to click element: ${e.message}"
            )
        }
    }

    /**
     * 解析bounds字符�?     * @return (left, top, right, bottom) 的null（解析失败）
     */
    private fun parseBounds(bounds: String): Quadruple<Int>? {
        return try {
            // 格式: [left,top][right,bottom]
            val regex = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
            val matchResult = regex.find(bounds)
            
            if (matchResult != null) {
                val (left, top, right, bottom) = matchResult.destructured
                Quadruple(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析bounds失败: ${bounds}", e)
            null
        }
    }

    // ==================== 无障碍操作辅助方�?===================

    /**
     * 执行无障碍点�?     */
    private suspend fun performAccessibilityClick(x: Int, y: Int): Boolean {
        return try {
            UIHierarchyManager.performClick(context, x, y)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing accessibility click", e)
            false
        }
    }

    /**
     * 执行无障碍长�?     */
    private suspend fun performAccessibilityLongPress(x: Int, y: Int): Boolean {
        return try {
            UIHierarchyManager.performLongPress(context, x, y)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing accessibility long press", e)
            false
        }
    }

    /**
     * 执行无障碍滑�?     */
    private suspend fun performAccessibilitySwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Int
    ): Boolean {
        return try {
            UIHierarchyManager.performSwipe(context, startX, startY, endX, endY, duration.toLong())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing accessibility swipe", e)
            false
        }
    }

    /**
     * 显示点击overlay
     */
    private suspend fun showTapOverlay(x: Int, y: Int) {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            operationOverlay.showTap(x, y)
        }
    }

    /**
     * 显示滑动overlay
     */
    private suspend fun showSwipeOverlay(startX: Int, startY: Int, endX: Int, endY: Int) {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            operationOverlay.showSwipe(startX, startY, endX, endY)
        }
    }

    /**
     * 显示文本输入overlay
     */
    private suspend fun showTextInputOverlay(x: Int, y: Int, text: String) {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            operationOverlay.showTextInput(x, y, text)
        }
    }

    /**
     * 隐藏overlay
     */
    private suspend fun hideOverlay() {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            operationOverlay.hide()
        }
    }

    /**
     * 解析bounds字符串，返回中心坐标
     */
    private fun parseBoundsCenter(bounds: String): Pair<Int, Int>? {
        return try {
            val regex = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
            val matchResult = regex.find(bounds)
            
            if (matchResult != null) {
                val (left, top, right, bottom) = matchResult.destructured
                val centerX = (left.toInt() + right.toInt()) / 2
                val centerY = (top.toInt() + bottom.toInt()) / 2
                Pair(centerX, centerY)
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析bounds失败: ${bounds}", e)
            null
        }
    }

    // ==================== 数据�?===================

    /**
     * 焦点信息
     */
    data class FocusInfo(
        val packageName: String? = null,
        val activityName: String? = null
    )

    /**
     * 四元�?     */
    data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
