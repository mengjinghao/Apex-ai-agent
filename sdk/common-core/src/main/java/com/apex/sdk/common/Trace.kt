package com.apex.sdk.common

import java.util.concurrent.atomic.AtomicLong

/**
 * 跨 APK 调用的唯一 Trace ID，串联一次调用在多个 APK 间的完整链路，
 * 便于在 [ApkDiagnostics] 中做端到端追踪。
 */
object Trace {
    private val counter = AtomicLong(0)

    fun newId(prefix: String = "t"): String {
        val ts = System.currentTimeMillis().toString(36)
        val n = counter.incrementAndGet().toString(36)
        return "$prefix-$ts-$n"
    }
}
