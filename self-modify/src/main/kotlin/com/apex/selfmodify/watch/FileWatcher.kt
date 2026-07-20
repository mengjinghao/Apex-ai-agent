package com.apex.selfmodify.watch

import kotlinx.coroutines.flow.SharedFlow
import java.io.File

interface FileWatcher {
    fun start(watchDir: File)
    fun stop()
    val events: SharedFlow<FileChangeEvent>
}

sealed class FileChangeEvent {
    data class Created(val path: String) : FileChangeEvent()
    data class Modified(val path: String) : FileChangeEvent()
    data class Deleted(val path: String) : FileChangeEvent()
    data class Moved(val from: String, val to: String) : FileChangeEvent()
}
