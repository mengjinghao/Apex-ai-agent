package com.apex.sdk.watchdog

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 心跳上报器 — 每个 APK 在 Application.onCreate 中启动一个，
 * 定期向 [Watchdog] 上报自身存活状态。
 *
 * **进程内**：直接调用 [Watchdog.heartbeat]
 * **跨进程**：通过 AIDL 调用主 APK 的 Watchdog Service
 *
 * 因为大多数 APK 共享主进程，所以心跳本质上就是 JVM 内方法调用，零延迟。
 */
class HeartbeatReporter(
    private val apkId: String,
    private val intervalMs: Long = ApexSuite.WATCHDOG_HEARTBEAT_INTERVAL_MS / 2
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Watchdog.watch(apkId)
            while (true) {
                try {
                    Watchdog.heartbeat(apkId)
                } catch (t: Throwable) {
                    ApexLog.w(apkId, "[Heartbeat] report failed: ${t.message}")
                }
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        ApexLog.d(apkId, "[Heartbeat] started, interval=${intervalMs}ms")
    }

    fun stop() {
        job?.cancel()
        job = null
        Watchdog.unwatch(apkId)
    }
}
