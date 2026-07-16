package com.apex.agent.core.tools.defaultTool

import android.content.Context
import com.apex.agent.core.tools.defaultTool.accessbility.AccessibilityUIToolsRefactored
import com.apex.agent.core.tools.defaultTool.admin.AdminUIToolsRefactored
import com.apex.agent.core.tools.defaultTool.debugger.DebuggerUIToolsRefactored
import com.apex.agent.core.tools.defaultTool.root.RootUIToolsRefactored
import com.apex.agent.core.tools.defaultTool.standard.StandardUIToolsRefactored
import com.apex.agent.core.tools.system.AndroidPermissionLevel
import com.apex.agent.data.preferences.androidPermissionPreferences

/**
 * 工具获取器（重构版）
 * 
 * 根据首选权限级别获取对应的工具实现
 * 如果特定权限级别下没有对应工具实现，则回退到标准权限级别的工具
 */
object ToolGetterRefactored {

    /**
     * 获取UI工具（重构版�?
     * @param context 应用上下�?
     * @return 根据首选权限级别的UI工具实现
     */
    fun getUITools(context: Context): StandardUIToolsRefactored {
        return when (androidPermissionPreferences.getPreferredPermissionLevel()) {
            AndroidPermissionLevel.ROOT -> RootUIToolsRefactored(context)
            AndroidPermissionLevel.ADMIN -> AdminUIToolsRefactored(context)
            AndroidPermissionLevel.DEBUGGER -> DebuggerUIToolsRefactored(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityUIToolsRefactored(context)
            AndroidPermissionLevel.STANDARD -> StandardUIToolsRefactored(context)
            null -> StandardUIToolsRefactored(context) // 默认使用标准权限级别
        }
    }

    /**
     * 获取文件系统工具（保留原版，待后续重构）
     */
    fun getFileSystemTools(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardFileSystemTools {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getFileSystemTools(context)
    }

    /**
     * 获取Shell工具执行器（保留原版�?
     */
    fun getShellToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardShellToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getShellToolExecutor(context)
    }

    /**
     * 获取系统操作工具（保留原版，待后续重构）
     */
    fun getSystemOperationTools(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardSystemOperationTools {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getSystemOperationTools(context)
    }

    /**
     * 获取设备信息工具执行器（保留原版，待后续重构�?
     */
    fun getDeviceInfoToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardDeviceInfoToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getDeviceInfoToolExecutor(context)
    }

    /**
     * 获取HTTP工具（保留原版）
     */
    fun getHttpTools(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardHttpTools {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getHttpTools(context)
    }

    /**
     * 获取Web访问工具（保留原版）
     */
    fun getWebVisitTool(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardWebVisitTool {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getWebVisitTool(context)
    }

    /**
     * 获取会话/Web工具（保留原版）
     */
    fun getBrowserSessionTools(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardBrowserSessionTools {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getBrowserSessionTools(context)
    }

    /**
     * 获取Intent工具执行器（保留原版�?
     */
    fun getIntentToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardIntentToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getIntentToolExecutor(context)
    }

    /**
     * 获取发送广播工具执行器（保留原版）
     */
    fun getSendBroadcastToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardSendBroadcastToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getSendBroadcastToolExecutor(context)
    }

    /**
     * 获取终端命令执行器（保留原版�?
     */
    fun getTerminalCommandExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardTerminalCommandExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getTerminalCommandExecutor(context)
    }

    /**
     * 获取内存查询工具执行器（保留原版�?
     */
    fun getMemoryQueryToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.MemoryQueryToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getMemoryQueryToolExecutor(context)
    }

    /**
     * 获取FFmpeg工具执行器（保留原版�?
     */
    fun getFFmpegToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardFFmpegToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getFFmpegToolExecutor(context)
    }

    /**
     * 获取FFmpeg信息工具执行器（保留原版�?
     */
    fun getFFmpegInfoToolExecutor(): com.apex.agent.core.tools.defaultTool.standard.StandardFFmpegInfoToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getFFmpegInfoToolExecutor()
    }

    /**
     * 获取FFmpeg转换工具执行器（保留原版�?
     */
    fun getFFmpegConvertToolExecutor(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardFFmpegConvertToolExecutor {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getFFmpegConvertToolExecutor(context)
    }

    /**
     * 获取计算器（保留原版�?
     */
    fun getCalculator() = com.apex.agent.core.tools.defaultTool.ToolGetter.getCalculator()

    /**
     * 获取工作流工具（保留原版�?
     */
    fun getWorkflowTools(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardWorkflowTools {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getWorkflowTools(context)
    }

    /**
     * 获取对话管理工具（保留原版）
     */
    fun getChatManagerTool(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardChatManagerTool {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getChatManagerTool(context)
    }

    /**
     * 获取软件设置修改工具（保留原版）
     */
    fun getSoftwareSettingsModifyTools(context: Context): com.apex.agent.core.tools.defaultTool.standard.StandardSoftwareSettingsModifyTools {
        return com.apex.agent.core.tools.defaultTool.ToolGetter.getSoftwareSettingsModifyTools(context)
    }
}
