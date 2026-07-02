package com.apex.agent.core.multiagent

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.lang.System.nanoTime

object PerformanceTools {

    private val handler = Handler(Looper.getMainLooper())
    private val timings = mutableMapOf<String, TimingResult>()
    private val memorySnapshots = mutableListOf<MemorySnapshot>()

    data class TimingResult(
        val key: String,
        val startNanos: Long,
        val endNanos: Long = -1,
        val durationMs: Float = -1f,
        val count: Int = 0
    )

    data class MemorySnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val javaHeapSizeMB: Long,
        val javaHeapAllocatedMB: Long,
        val nativeHeapSizeMB: Long,
        val nativeHeapAllocatedMB: Long
    )

    private val startTimes = mutableMapOf<String, Long>()

    fun startTiming(key: String) { startTimes[key] = nanoTime() }

    fun endTiming(key: String): Float {
        val startTime = startTimes.remove(key) ?: return -1f
        val endTime = nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000f

        val existing = timings[key]
        if (existing != null) {
            timings[key] = existing.copy(endNanos = endTime, durationMs = durationMs, count = existing.count + 1)
        } else {
            timings[key] = TimingResult(key = key, startNanos = startTime, endNanos = endTime, durationMs = durationMs, count = 1)
        }
        return durationMs
    }

    fun measure(block: () -> Unit): Long {
        val start = nanoTime()
        block()
        return (nanoTime() - start) / 1_000_000L
    }

    suspend fun measureSuspend(block: suspend () -> Unit): Long {
        val start = nanoTime()
        block()
        return (nanoTime() - start) / 1_000_000L
    }

    fun takeMemorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val snapshot = MemorySnapshot(javaHeapSizeMB = runtime.maxMemory() / 1024 / 1024, javaHeapAllocatedMB = runtime.totalMemory() / 1024 / 1024, nativeHeapSizeMB = Debug.getNativeHeapSize() / 1024 / 1024, nativeHeapAllocatedMB = Debug.getNativeHeapAllocatedSize() / 1024 / 1024)
        memorySnapshots.add(snapshot)
        if (memorySnapshots.size > 100) { memorySnapshots.removeAt(0) }
        return snapshot
    }

    fun getAverageTiming(key: String): Float? {
        val results = timings.values.filter { it.key == key }
        return if (results.isNotEmpty()) results.map { it.durationMs }.average().toFloat() else null
    }

    fun getTimingStats(): Map<String, Float> = timings.mapValues { it.value.durationMs }
    fun clearTimings() { timings.clear() }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> "${"%.2f".format(bytes / (1024 * 1024 * 1024.0))} GB"
        bytes >= 1024 * 1024 -> "${"%.2f".format(bytes / (1024 * 1024.0))} MB"
        bytes >= 1024 -> "${"%.2f".format(bytes / 1024.0)} KB"
        else -> "${bytes} Bytes"
    }

    fun formatDuration(millis: Long): String = when {
        millis >= 60000 -> "${"%.1f".format(millis / 60000.0)} min"
        millis >= 1000 -> "${"%.1f".format(millis / 1000.0)} sec"
        else -> "${millis} ms"
    }

    fun getCurrentThreadInfo(): String = "Thread: ${Thread.currentThread().name} (${Thread.currentThread().id})"
    fun getProcessInfo(): String = "PID: ${Process.myPid()}, TID: ${Process.myTid()}"
}
