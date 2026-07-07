package com.apex.sdk.watchdog

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.Trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨 APK 看门狗。
 *
 * **解决什么问题？**
 *   多 APK 体系中，某个 APK crash 后，调用方不应卡死或反复报错，
 *   而是应该：感知死亡 → 重启连接 → 重新注册服务 → 恢复业务。
 *
 * **实现**：
 *   - 每个承载核心 Service 的 APK 定期向 Watchdog 发心跳（[heartbeat]）
 *   - Watchdog 监控所有注册的 APK 心跳
 *   - 超过 [ApexSuite.WATCHDOG_TIMEOUT_MS] 未收到心跳 → 触发 [WatchdogEvent.ApkUnresponsive]
 *   - Binder 提供 [IBinder.DeathRecipient]，APK 进程死亡时立即触发 [WatchdogEvent.ApkDied]
 *
 * **调用方应订阅 [events]**：
 *   ```kotlin
 *   Watchdog.events.onEach { event ->
 *       when (event) {
 *           is WatchdogEvent.ApkDied -> restartConnection(event.apkId)
 *           is WatchdogEvent.ApkUnresponsive -> reconnect(event.apkId)
 *           is WatchdogEvent.ApkRecovered -> resumeBusiness()
 *       }
 *   }.collect()
 *   ```
 */
object Watchdog {

    private const val TAG_SUB = "Watchdog"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val heartbeats = ConcurrentHashMap<String, HeartbeatRecord>()
    private val _events = MutableSharedFlow<WatchdogEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WatchdogEvent> = _events.asSharedFlow()

    private data class HeartbeatRecord(
        val apkId: String,
        var lastHeartbeatMs: Long,
        var deathRecipientRegistered: Boolean = false
    )

    /**
     * 注册一个 APK 到 Watchdog 监控。
     */
    fun watch(apkId: String) {
        heartbeats[apkId] = HeartbeatRecord(apkId, System.currentTimeMillis())
        ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] watching apk: $apkId")
    }

    /**
     * 取消监控。
     */
    fun unwatch(apkId: String) {
        heartbeats.remove(apkId)
    }

    /**
     * 接收来自某 APK 的心跳上报。
     */
    fun heartbeat(apkId: String) {
        val record = heartbeats[apkId]
        if (record != null) {
            record.lastHeartbeatMs = System.currentTimeMillis()
        } else {
            // 新 APK 首次心跳，自动加入监控
            heartbeats[apkId] = HeartbeatRecord(apkId, System.currentTimeMillis())
            _events.tryEmit(WatchdogEvent.ApkRegistered(apkId, Trace.newId()))
        }
    }

    /**
     * 主动上报某 APK 已死亡（通常由 DeathRecipient 触发）。
     */
    fun reportDeath(apkId: String, reason: String? = null) {
        ApexLog.w(ApexSuite.ApkId.MAIN, "[$TAG_SUB] apk died: $apkId, reason=$reason")
        _events.tryEmit(WatchdogEvent.ApkDied(apkId, reason, Trace.newId()))
    }

    /**
     * 主动上报某 APK 已恢复。
     */
    fun reportRecovered(apkId: String) {
        ApexLog.i(ApexSuite.ApkId.MAIN, "[$TAG_SUB] apk recovered: $apkId")
        heartbeats[apkId]?.lastHeartbeatMs = System.currentTimeMillis()
        _events.tryEmit(WatchdogEvent.ApkRecovered(apkId, Trace.newId()))
    }

    /**
     * 启动监控循环。
     */
    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (true) {
                delay(ApexSuite.WATCHDOG_HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val timeoutMs = ApexSuite.WATCHDOG_TIMEOUT_MS
                heartbeats.forEach { (apkId, record) ->
                    val elapsed = now - record.lastHeartbeatMs
                    if (elapsed > timeoutMs) {
                        ApexLog.w(ApexSuite.ApkId.MAIN, "[$TAG_SUB] apk unresponsive: $apkId (${elapsed}ms)")
                        _events.tryEmit(WatchdogEvent.ApkUnresponsive(apkId, elapsed, Trace.newId()))
                    }
                }
            }
        }
    }

    /**
     * 停止监控。
     */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * 当前所有监控状态快照（用于诊断 UI）。
     */
    fun snapshot(): List<ApkHealth> {
        val now = System.currentTimeMillis()
        return heartbeats.map { (apkId, record) ->
            val elapsed = now - record.lastHeartbeatMs
            ApkHealth(
                apkId = apkId,
                lastHeartbeatAgoMs = elapsed,
                healthy = elapsed < ApexSuite.WATCHDOG_TIMEOUT_MS
            )
        }
    }
}

data class ApkHealth(
    val apkId: String,
    val lastHeartbeatAgoMs: Long,
    val healthy: Boolean
)

sealed class WatchdogEvent(val traceId: String) {
    data class ApkRegistered(val apkId: String, val tid: String) : WatchdogEvent(tid)
    data class ApkDied(val apkId: String, val reason: String?, val tid: String) : WatchdogEvent(tid)
    data class ApkUnresponsive(val apkId: String, val elapsedMs: Long, val tid: String) : WatchdogEvent(tid)
    data class ApkRecovered(val apkId: String, val tid: String) : WatchdogEvent(tid)
}
