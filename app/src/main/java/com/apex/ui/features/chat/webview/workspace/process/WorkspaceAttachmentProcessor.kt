package com.apex.ui.features.chat.webview.workspace.process

import android.content.Context

/**
 * 工作区附件处理器 — Stub。
 * 原用于在 AI 上下文中附加工作区文件内容，UI 移除后保留静态方法 API。
 * 业务代码（SystemPromptConfig / AIMessageManager）调用这些方法获取工作区内容。
 */
object WorkspaceAttachmentProcessor {

    @JvmStatic
    fun readWorkspaceRootRuleFile(context: Context, workspaceId: String? = null): String? {
        // Stub: 原 UI 实现会读取工作区根目录的规则文件，已移除
        return null
    }

    @JvmStatic
    fun generateWorkspaceAttachment(
        context: Context,
        workspaceId: String? = null,
        maxTokens: Int = 4000
    ): String {
        // Stub: 原 UI 实现会收集工作区所有文件内容并格式化为附件
        return ""
    }

    @JvmStatic
    fun hasActiveWorkspace(context: Context): Boolean = false
}
