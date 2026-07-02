package com.apex.agent.infrastructure.eventbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件指标报告
 */
data class MetricsReport(
    val eventType: String,
    val publishedCount: Long,
    val deliveredCount: Long,
    val failedCount: Long,
    val droppedCount: Long,
    val subscriberCount: Int,
    val avgProcessingTimeMs: Double,
    val p50ProcessingTimeMs: Double,
    val p99ProcessingTimeMs: Double,
    val throughputPerSecond: Double
)

/**
 * 事件指标收集器
 */
class EventMetricsCollector {

    private val publishedCount = ConcurrentHashMap<Class<*>, AtomicLong>()
    private val deliveredCount = ConcurrentHashMap<Class<*>, AtomicLong>()
    private val failedCount = ConcurrentHashMap<Class<*>, AtomicLong>()
    private val droppedCount = ConcurrentHashMap<Class<*>, AtomicLong>()
    private val subscriberCount = ConcurrentHashMap<Class<*>, AtomicInteger>()
    private val processingTimes = ConcurrentHashMap<Class<*>, MutableList<Long>>()
    private val startTime = ConcurrentHashMap<Class<*>, Long>()
    private val globalStartTime = System.currentTimeMillis()

    fun recordPublished(eventClass: Class<*>) {
        publishedCount.getOrPut(eventClass) { AtomicLong(0) }.incrementAndGet()
        startTime.putIfAbsent(eventClass, System.currentTimeMillis())
    }

    fun recordDelivered(eventClass: Class<*>, durationMs: Long) {
        deliveredCount.getOrPut(eventClass) { AtomicLong(0) }.incrementAndGet()
        recordProcessingTime(eventClass, durationMs)
    }

    fun recordFailed(eventClass: Class<*>) {
        failedCount.getOrPut(eventClass) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordDropped(eventClass: Class<*>) {
        droppedCount.getOrPut(eventClass) { AtomicLong(0) }.incrementAndGet()
    }

    fun registerSubscriber(eventClass: Class<*>) {
        subscriberCount.getOrPut(eventClass) { AtomicInteger(0) }.incrementAndGet()
    }

    fun unregisterSubscriber(eventClass: Class<*>) {
        subscriberCount[eventClass]?.updateAndGet { maxOf(0, it - 1) }
    }

    private fun recordProcessingTime(eventClass: Class<*>, durationMs: Long) {
        processingTimes.getOrPut(eventClass) { mutableListOf() }.add(durationMs)
    }

    fun getReport(eventClass: Class<*>): MetricsReport? {
        val published = publishedCount[eventClass]?.get() ?: return null
        val times = processingTimes[eventClass] ?: emptyList()
        val sorted = times.sorted()
        val avg = if (times.isNotEmpty()) times.average() else 0.0
        val p50 = if (sorted.isNotEmpty()) sorted[sorted.size / 2].toDouble() else 0.0
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)].toDouble() else 0.0
        val elapsed = (System.currentTimeMillis() - (startTime[eventClass] ?: globalStartTime)) / 1000.0
        val throughput = if (elapsed > 0) published / elapsed else 0.0

        return MetricsReport(
            eventType = eventClass.simpleName,
            publishedCount = published,
            deliveredCount = deliveredCount[eventClass]?.get() ?: 0,
            failedCount = failedCount[eventClass]?.get() ?: 0,
            droppedCount = droppedCount[eventClass]?.get() ?: 0,
            subscriberCount = subscriberCount[eventClass]?.get() ?: 0,
            avgProcessingTimeMs = avg,
            p50ProcessingTimeMs = p50,
            p99ProcessingTimeMs = p99,
            throughputPerSecond = throughput
        )
    }

    fun getAllReports(): List<MetricsReport> {
        return publishedCount.keys().asSequence().mapNotNull { getReport(it) }.toList()
    }

    fun reset() {
        publishedCount.clear()
        deliveredCount.clear()
        failedCount.clear()
        droppedCount.clear()
        subscriberCount.clear()
        processingTimes.clear()
        startTime.clear()
    }
}

/**
 * 事件总线报告器 - 周期上报指标
 */
class EventBusReporter(
    private val collector: EventMetricsCollector,
    private val intervalMs: Long = 60000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val reporter: (List<MetricsReport>) -> Unit = { reports ->
        reports.forEach { report ->
            println("[EventBus] ${report.eventType}: published=${report.publishedCount}, " +
                "delivered=${report.deliveredCount}, failed=${report.failedCount}, " +
                "dropped=${report.droppedCount}, subscribers=${report.subscriberCount}, " +
                "avg=${"%.2f".format(report.avgProcessingTimeMs)}ms, " +
                "throughput=${"%.2f".format(report.throughputPerSecond)}/s")
        }
    }
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    val reports = collector.getAllReports()
                    if (reports.isNotEmpty()) {
                        reporter(reports)
                    }
                } catch (_: Exception) {
                    // 忽略报告错误
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun restart() {
        stop()
        start()
    }
}
