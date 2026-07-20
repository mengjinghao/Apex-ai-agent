package com.apex.selfmodify.watch

import android.os.FileObserver
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

class AndroidFileWatcher : FileWatcher {
    private val _events = MutableSharedFlow<FileChangeEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<FileChangeEvent> = _events.asSharedFlow()
    private val observers = mutableListOf<FileObserver>()
    @Volatile private var running = false

    override fun start(watchDir: File) {
        if (running) return
        running = true
        watchDir.walkTopDown().filter { it.isDirectory }.forEach { dir ->
            val obs = object : FileObserver(dir, MODIFY or CREATE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    val fullPath = File(dir, path).path
                    val evt = when (event and 0xFFFF) {
                        CREATE, MOVED_TO -> FileChangeEvent.Created(fullPath)
                        MODIFY -> FileChangeEvent.Modified(fullPath)
                        DELETE, MOVED_FROM -> FileChangeEvent.Deleted(fullPath)
                        else -> null
                    }
                    evt?.let { _events.tryEmit(it) }
                }
            }
            obs.startWatching()
            observers.add(obs)
        }
        ApexLog.i(ApexSuite.ApkId.MAIN, "[FileWatcher] watching ${observers.size} dirs under ${watchDir.path}")
    }

    override fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        running = false
    }
}
