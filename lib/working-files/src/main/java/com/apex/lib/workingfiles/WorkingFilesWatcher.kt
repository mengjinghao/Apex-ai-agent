package com.apex.lib.workingfiles

import com.apex.sdk.common.ApexLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService

/**
 * 文件变更监听器 — 实时跟随 Agent 工作。
 *
 * 使用 java.nio.file.WatchService（基于 Linux inotify），
 * 文件变更毫秒级推送到 [events] Flow，UI 端可实时刷新。
 *
 * 适用于：
 *   - 实时预览 Agent 生成的代码文件
 *   - 监控 Agent 写入的日志/产物
 *   - 显示 Agent 当前工作目录树
 */
class WorkingFilesWatcher(private val root: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null
    private var watchService: WatchService? = null

    private val _events = MutableSharedFlow<FileEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<FileEvent> = _events.asSharedFlow()

    fun start() {
        if (watchJob?.isActive == true) return
        if (!root.exists()) {
            root.mkdirs()
        }
        watchJob = scope.launch {
            try {
                watchService = FileSystems.getDefault().newWatchService()
                val rootPath = root.toPath()
                registerRecursively(rootPath)

                while (true) {
                    val key = watchService?.take() ?: break
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        val path = (event.context() as? java.nio.file.Path)?.let { rootPath.resolve(it) }
                        val absPath = path?.toString() ?: continue

                        when (kind.name()) {
                            "ENTRY_CREATE" -> _events.tryEmit(FileEvent.Created(absPath))
                            "ENTRY_MODIFY" -> _events.tryEmit(FileEvent.Modified(absPath))
                            "ENTRY_DELETE" -> _events.tryEmit(FileEvent.Deleted(absPath))
                        }

                        // 如果是新建目录，递归注册
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            val newDir = File(absPath)
                            if (newDir.isDirectory) {
                                registerRecursively(newDir.toPath())
                            }
                        }
                    }
                    if (!key.reset()) break
                }
            } catch (e: InterruptedException) {
                // stopped
            } catch (t: Throwable) {
                ApexLog.e("working-files", "[Watcher] error", t)
            }
        }
        ApexLog.d("working-files", "[Watcher] started for ${root.absolutePath}")
    }

    private fun registerRecursively(path: java.nio.file.Path) {
        try {
            path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            File(path.toUri()).listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    registerRecursively(child.toPath())
                }
            }
        } catch (t: Throwable) {
            ApexLog.w("working-files", "[Watcher] failed to register ${path}: ${t.message}")
        }
    }

    fun stop() {
        watchJob?.cancel()
        try { watchService?.close() } catch (_: Throwable) {}
        watchService = null
        ApexLog.d("working-files", "[Watcher] stopped for ${root.absolutePath}")
    }
}

sealed class FileEvent {
    abstract val path: String
    data class Created(override val path: String) : FileEvent()
    data class Modified(override val path: String) : FileEvent()
    data class Deleted(override val path: String) : FileEvent()
}
