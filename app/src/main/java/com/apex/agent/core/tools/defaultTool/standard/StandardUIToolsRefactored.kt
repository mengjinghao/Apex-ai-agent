package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.agent.core.tools.AppListData
import com.apex.agent.core.tools.StringResultData
import com.apex.agent.core.tools.base.BaseUITools
import com.apex.agent.core.tools.config.UIToolsConfig
import com.apex.agent.core.tools.result.UIToolsErrorCode
import com.apex.agent.core.tools.result.UIToolsResult
import com.apex.data.model.AITool
import com.apex.data.model.ToolResult
import com.apex.util.AppLogger
import com.apex.agent.core.tools.defaultTool.standard.name

/**
 * 标准UI工具类（重构版）
 * 
 * 提供基础的UI查询功能，不支持UI操作
 * 继承自BaseUITools，使用统一的架构和配置
 */
open class StandardUIToolsRefactored(context: Context) : BaseUITools(context) {

    companion object {
        private const val TAG = "StandardUITools"
    }

    // ==================== 核心功能 ====================

    /**
     * 点击坐标（标准版不支持）
     */
    override suspend fun tap(tool: AITool): ToolResult {
        return UIToolsResult.Error(
            errorCode = UIToolsErrorCode.OPERATION_FAILED,
            message = "Standard version does not support tap operation. Please use Accessibility or higher permission level."
        ).toToolResult(tool.name)
    }

    /**
     * 长按坐标（标准版不支持）
     */
    override suspend fun longPress(tool: AITool): ToolResult {
        return UIToolsResult.Error(
            errorCode = UIToolsErrorCode.OPERATION_FAILED,
            message = "Standard version does not support longPress operation. Please use Accessibility or higher permission level."
        ).toToolResult(tool.name)
    }

    /**
     * 滑动屏幕（标准版不支持）
     */
    override suspend fun swipe(tool: AITool): ToolResult {
        return UIToolsResult.Error(
            errorCode = UIToolsErrorCode.OPERATION_FAILED,
            message = "Standard version does not support swipe operation. Please use Accessibility or higher permission level."
        ).toToolResult(tool.name)
    }

    /**
     * 输入文本（标准版不支持）
     */
    override suspend fun setInputText(tool: AITool): ToolResult {
        return UIToolsResult.Error(
            errorCode = UIToolsErrorCode.OPERATION_FAILED,
            message = "Standard version does not support setInputText operation. Please use Accessibility or higher permission level."
        ).toToolResult(tool.name)
    }

    /**
     * 按键事件（标准版不支持）
     */
    override suspend fun pressKey(tool: AITool): ToolResult {
        return UIToolsResult.Error(
            errorCode = UIToolsErrorCode.OPERATION_FAILED,
            message = "Standard version does not support pressKey operation. Please use Accessibility or higher permission level."
        ).toToolResult(tool.name)
    }

    /**
     * 获取已安装应用列�?     */
    override suspend fun getAppList(tool: AITool): ToolResult {
        return executeWithCatch("getAppList", tool) {
            // 1. 获取参数
    val filter = getParameter(tool, "filter", null)
            val limit = getParameter(tool, "limit", "100").toIntOrNull() ?: 100

            // 2. 扫描已安装应�?
    val appList = UIToolsConfig.scanInstalledApps(context)

            // 3. 过滤（如果指定）
    val filteredList = if (filter != null) {
                appList.filter { 
                    it.name.contains(filter, ignoreCase = true) || 
                    it.packageName.contains(filter, ignoreCase = true)
                }
            } else {
                appList
            }

            // 4. 限制数量
    val limitedList = filteredList.take(limit)

            // 5. 构建结果
    val resultData = AppListData(
                apps = limitedList.map { app ->
                    AppListData.AppInfo(
                        name = app.name,
                        packageName = app.packageName,
                        versionName = app.versionName ?: "",
                        versionCode = app.versionCode,
                        isSystemApp = app.isSystemApp
                    )
                },
                totalCount = filteredList.size,
                returnedCount = limitedList.size
            )

            UIToolsResult.Success(resultData).toToolResult(tool.name)
        }
    }

    /**
     * 根据名称或包名查找应�?     */
    suspend fun findApp(tool: AITool): ToolResult {
        return executeWithCatch("findApp", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("query"),
                optionalParams = emptyList()
            )

            // 2. 获取查询参数
    val query = getRequiredParameter(tool, "query")

            // 3. 查找应用
    val appInfo = UIToolsConfig.findAppByNameOrPackage(query)

            if (appInfo == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.APP_NOT_FOUND,
                    message = "Application not found: ${query}"
                ).toToolResult(tool.name)
            }

            // 4. 构建结果
    val resultData = mapOf(
                "name" to appInfo.name,
                "packageName" to appInfo.packageName,
                "versionName" to (appInfo.versionName ?: ""),
                "versionCode" to appInfo.versionCode,
                "isSystemApp" to appInfo.isSystemApp
            )

            UIToolsResult.Success(resultData).toToolResult(tool.name)
        }
    }

    /**
     * 启动应用
     */
    suspend fun launchApp(tool: AITool): ToolResult {
        return executeWithCatch("launchApp", tool) {
            // 1. 验证参数
            validateParameters(
                tool,
                requiredParams = listOf("app"),
                optionalParams = listOf("activity")
            )

            // 2. 获取参数
    val appName = getRequiredParameter(tool, "app")
            val activity = getParameter(tool, "activity", null)

            // 3. 查找应用包名
    val packageInfo = UIToolsConfig.findAppByNameOrPackage(appName)
            
            if (packageInfo == null) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.APP_NOT_FOUND,
                    message = "Application not found: ${appName}"
                ).toToolResult(tool.name)
            }

            // 4. 启动应用
    val success = launchApplication(packageInfo.packageName, activity)

            if (!success) {
                return@executeWithCatch UIToolsResult.Error(
                    errorCode = UIToolsErrorCode.ACTION_FAILED,
                    message = "Failed to launch application: ${packageInfo.packageName}"
                ).toToolResult(tool.name)
            }

            // 5. 构建结果
    val resultData = mapOf(
                "action" to "launch",
                "packageName" to packageInfo.packageName,
                "activity" to (activity ?: "default"),
                "appName" to packageInfo.name
            )

            UIToolsResult.Success(resultData).toToolResult(tool.name)
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 启动应用程序
     */
    private fun launchApplication(packageName: String, activity: String? = null): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            
            if (intent == null) {
                AppLogger.e(TAG, "无法获取启动Intent: ${packageName}")
                return false
            }

            // 如果指定了activity，设置组�?
    if (activity != null) {
                intent.setClassName(packageName, activity)
            }

            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            
            AppLogger.d(TAG, "成功启动应用: ${packageName}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动应用失败: ${packageName}", e)
            false
        }
    }

    /**
     * 获取应用信息
     */
    private fun getAppInfo(packageName: String): Map<String, Any>? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            
            mapOf(
                "packageName" to packageName,
                "appName" to context.packageManager.getApplicationLabel(applicationInfo).toString(),
                "versionName" to (packageInfo.versionName ?: ""),
                "versionCode" to packageInfo.versionCode,
                "isSystemApp" to ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取应用信息失败: ${packageName}", e)
            null
        }
    }
}
