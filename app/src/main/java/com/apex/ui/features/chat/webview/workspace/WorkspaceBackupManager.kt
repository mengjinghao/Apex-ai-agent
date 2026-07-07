package com.apex.ui.features.chat.webview.workspace

import android.content.Context

/**
 * 工作区备份管理器 — Stub。
 * 原用于在 WebView 工作区中备份/恢复编辑器内容，UI 移除后保留 getInstance API。
 */
class WorkspaceBackupManager private constructor(private val context: Context) {

    interface WorkspaceToolHookSession {
        fun start() {}
        fun stop() {}
        fun snapshot(): String? = null
    }

    companion object {
        @Volatile private var instance: WorkspaceBackupManager? = null

        @JvmStatic
        fun getInstance(context: Context): WorkspaceBackupManager {
            return instance ?: synchronized(this) {
                instance ?: WorkspaceBackupManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun createHookSession(): WorkspaceToolHookSession = object : WorkspaceToolHookSession {}
    fun backup(workspaceId: String): Boolean = false
    fun restore(workspaceId: String): Boolean = false
}
