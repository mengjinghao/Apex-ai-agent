package com.apex.lib.workingfiles

import java.io.File

/**
 * 工作文件夹绑定。
 *
 * 用户可自选一个本地文件夹作为 Agent 的工作区。
 * 三种 Agent 模式（普通 / 多 Agent / 狂暴）共享同一工作区，
 * Agent 在执行过程中产生的文件、代码、日志都会写入此目录。
 *
 * [WorkingFilesWatcher] 实时监听文件变更，让 UI 端“实时跟随”Agent 工作。
 */
data class WorkingFolder(
    val id: String,
    val displayName: String,
    val path: String,
    val assignedAgentMode: AgentMode = AgentMode.ALL
)

enum class AgentMode {
    NORMAL,        // 普通 Agent
    MULTI_AGENT,   // 多 Agent 协作
    BURST,         // 狂暴模式
    ALL            // 所有模式共享
}

class WorkingFolderManager {

    private val folders = mutableMapOf<String, WorkingFolder>()
    private val watchers = mutableMapOf<String, WorkingFilesWatcher>()

    fun bindFolder(folder: WorkingFolder) {
        folders[folder.id] = folder
        // 启动文件监听
        val watcher = WorkingFilesWatcher(File(folder.path))
        watcher.start()
        watchers[folder.id] = watcher
    }

    fun unbindFolder(folderId: String) {
        watchers.remove(folderId)?.stop()
        folders.remove(folderId)
    }

    fun listFolders(): List<WorkingFolder> = folders.values.toList()

    fun getFolder(folderId: String): WorkingFolder? = folders[folderId]

    /**
     * 列出某 Agent 模式下所有可用的工作文件夹。
     */
    fun foldersFor(mode: AgentMode): List<WorkingFolder> {
        return folders.values.filter { it.assignedAgentMode == AgentMode.ALL || it.assignedAgentMode == mode }
    }

    fun watcher(folderId: String): WorkingFilesWatcher? = watchers[folderId]
}
