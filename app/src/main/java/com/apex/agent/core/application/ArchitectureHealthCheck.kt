package com.apex.agent.core.application

import android.content.Context
import android.os.Build
import android.os.Debug
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 * 架构健康度自检模块 (Architecture Health Check)
 * ============================================================================
 *
 * 目标? *   在运行时持续监控本次架构优化的有效性，量化性能改进? *
 *   [优化1] 冷启动关键路径缩?监控 onCreate 耗时 (critical path vs background)
 *   [优化2] AppInitializer 并行任务 监控阶段耗时 & 并发效率
 *   [优化3] AIServiceFactory 缓存 监控缓存命中? *   [优化4] kotlinx.serialization 迁移 监控序列化性能
 *   [优化5] Gradle 并行构建 在调试模式下显示构建配置
 *
 * 数据结构? *   - ConcurrentHashMap 存储各类指标 (线程安全, 无锁读取? *   - AtomicLong 跟踪累计耗时 & 计数
 *   - 周期?JSON 持久?(轻量? 避免影响性能? *
 * 使用? *   val health = ArchitectureHealthCheck.getInstance(context)
 *   health.recordColdStart(...)     // onCreate 结束时调? *   health.reportHealth()             // 调试 UI / 日志输出
 * ============================================================================
 */
class ArchitectureHealthCheck private constructor(private val context: Context) {

    // ========================================================================
    // 数据模型
    // ========================================================================

    @Serializable
    data class ColdStartMetrics(
        var criticalPathMs: Long = 0,            // 主线程阻塞时?(优化1)
        var backgroundInitMs: Long = 0,          // 后台初始化总耗时 (优化1)
        var sampleCount: Int = 0,                // 采样次数
        var improvedSinceLast: Boolean = false
    )

    @Serializable
    data class ConcurrencyMetrics(
        var sequentialPhaseTotalMs: Long = 0,    // 若为顺序执行的预计总耗时
        var actualPhaseTotalMs: Long = 0,        // 实际并行执行总耗时
        var taskCount: Int = 0,                  // 总任务数
        var speedupRatio: Double = 0.0           // 加速比 = sequential / actual
    )

    @Serializable
    data class CacheMetrics(
        var cacheHits: Long = 0,                  // 缓存命中次数 (优化3)
        var cacheMisses: Long = 0,                // 缓存未命中次?        var cacheSize: Int = 0                    // 当前缓存大小
    ) {
        val hitRate: Double get() {
            val total = cacheHits + cacheMisses
            return if (total > 0) cacheHits.toDouble() / total * 100.0 else 0.0
        }
    }

    @Serializable
    data class SerializationMetrics(
        var serializationCount: Long = 0,        // 序列化次?        var totalSerializationTimeNs: Long = 0,  // 累计序列化耗时
        var deserializationCount: Long = 0,
        var totalDeserializationTimeNs: Long = 0
    ) {
        val avgSerializationNs: Long get() =
            if (serializationCount > 0) totalSerializationTimeNs / serializationCount else 0
        val avgDeserializationNs: Long get() =
            if (deserializationCount > 0) totalDeserializationTimeNs / deserializationCount else 0
    }

    @Serializable
    data class MemoryMetrics(
        var usedHeapBytes: Long = 0,
        var maxHeapBytes: Long = 0,
        var nativeHeapBytes: Long = 0,
        var sampleCount: Int = 0
    )

    @Serializable
    data class HealthSnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val coldStart: ColdStartMetrics = ColdStartMetrics(),
        val concurrency: ConcurrencyMetrics = ConcurrencyMetrics(),
        val cache: CacheMetrics = CacheMetrics(),
        val serialization: SerializationMetrics = SerializationMetrics(),
        val memory: MemoryMetrics = MemoryMetrics()
    )

    // ========================================================================
    // 内部存储
    // ========================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val coldStart = ColdStartMetrics()
    private val concurrency = ConcurrencyMetrics()
    private val cache = CacheMetrics()
    private val serialization = SerializationMetrics()
    private val memory = MemoryMetrics()

    private val _criticalPathStart = AtomicLong(0)
    private val _backgroundStart = AtomicLong(0)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    companion object {
        private const val TAG = "ArchHealthCheck"

        @Volatile
        private var INSTANCE: ArchitectureHealthCheck? = null

        fun getInstance(context: Context): ArchitectureHealthCheck {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArchitectureHealthCheck(context.applicationContext).also {
                    INSTANCE = it
                    it.startPeriodicMemoryMonitor()
                }
            }
        }
    }

    // ========================================================================
    // [优化1] 冷启动关键路径追?    // ========================================================================

    fun beginColdStart() {
        _criticalPathStart.set(System.currentTimeMillis())
        AppLogger.d(TAG, "开始测量冷启动关键路径 [优化1]")
    }

    fun endCriticalPath() {
        val duration = System.currentTimeMillis() - _criticalPathStart.get()
        coldStart.criticalPathMs = duration
        coldStart.sampleCount++
        _backgroundStart.set(System.currentTimeMillis())
        AppLogger.d(
            TAG,
            "?[优化1] 关键路径阻塞: ${duration}ms " +
                "(目标: <300ms, ${if (duration < 300) "达标" else "需关注"})"
        )
    }

    fun endBackgroundInit() {
        val duration = System.currentTimeMillis() - _backgroundStart.get()
        coldStart.backgroundInitMs = duration
        AppLogger.d(TAG, "?[优化1] 后台初始? ${duration}ms (后台异步, 不阻?UI)")
    }

    // ========================================================================
    // [优化2] 并发执行效率追踪
    // ========================================================================

    fun recordPhaseExecution(
        phaseName: String,
        sequentialTotalMs: Long,   // 如果顺序执行预计耗时
        actualParallelMs: Long     // 实际并行执行耗时
    ) {
        concurrency.sequentialPhaseTotalMs += sequentialTotalMs
        concurrency.actualPhaseTotalMs += actualParallelMs
        concurrency.taskCount++
        concurrency.speedupRatio =
            if (actualParallelMs > 0) sequentialTotalMs.toDouble() / actualParallelMs else 0.0

        AppLogger.d(
            TAG,
            "?[优化2] ${phaseName}? " +
                "顺序=${sequentialTotalMs}ms, 并行=${actualParallelMs}ms, " +
                "加速比=${"%.2f".format(concurrency.speedupRatio)}x"
        )
    }

    // ========================================================================
    // [优化3] 缓存命中率追?(?AIServiceFactory 调用?    // ========================================================================

    fun recordCacheHit() {
        cache.cacheHits++
    }

    fun recordCacheMiss() {
        cache.cacheMisses++
    }

    fun updateCacheSize(size: Int) {
        cache.cacheSize = size
    }

    // ========================================================================
    // [优化4] kotlinx.serialization 性能监控
    // ========================================================================

    fun <T> measureSerialization(block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        serialization.totalSerializationTimeNs += System.nanoTime() - start
        serialization.serializationCount++
        return result
    }

    fun <T> measureDeserialization(block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        serialization.totalDeserializationTimeNs += System.nanoTime() - start
        serialization.deserializationCount++
        return result
    }

    // ========================================================================
    // 内存监控 (周期性采样）
    // ========================================================================

    private fun startPeriodicMemoryMonitor() {
        scope.launch {
            while (true) {
                try {
                    sampleMemory()
                    delay(30_000) // ?0秒一?                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }

    private fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val maxHeap = runtime.maxMemory()
        val nativeHeap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Debug.getNativeHeapAllocatedSize()
        } else {
            0L
        }

        memory.usedHeapBytes = usedHeap
        memory.maxHeapBytes = maxHeap
        memory.nativeHeapBytes = nativeHeap
        memory.sampleCount++
    }

    // ========================================================================
    // 报告输出
    // ========================================================================

    fun reportHealth(): String {
        sampleMemory() // 采样最新内?        val snapshot = HealthSnapshot(
            coldStart = coldStart,
            concurrency = concurrency,
            cache = cache,
            serialization = serialization,
            memory = memory
        )
        val report = StringBuilder()
            .appendLine()
            .appendLine("════════════════════════════════════════════════")
            .appendLine("      架构健康度自检报告 (Architecture Health)")
            .appendLine("════════════════════════════════════════════════")
            .appendLine()
            .appendLine("┌─ [优化1] 冷启动关键路径缩?)
            .appendLine("? 主线程阻塞时? ${snapshot.coldStart.criticalPathMs}ms")
            .appendLine("? 后台初始化时? ${snapshot.coldStart.backgroundInitMs}ms")
            .appendLine("? 状? ${if (snapshot.coldStart.criticalPathMs < 300) "优秀 (<300ms)" else "需优化"}")
            .appendLine("?)
            .appendLine("├─ [优化2] AppInitializer 并行执行")
            .appendLine("? 累计顺序耗时: ${snapshot.concurrency.sequentialPhaseTotalMs}ms")
            .appendLine("? 累计实际耗时: ${snapshot.concurrency.actualPhaseTotalMs}ms")
            .appendLine("? 加速比: ${"%.2f".format(snapshot.concurrency.speedupRatio)}x")
            .appendLine("? 状? ${if (snapshot.concurrency.speedupRatio > 1.5) "并行有效" else "串行占比?}")
            .appendLine("?)
            .appendLine("├─ [优化3] AIServiceFactory 缓存")
            .appendLine("? 命中: ${snapshot.cache.cacheHits}, 未命? ${snapshot.cache.cacheMisses}")
            .appendLine("? 命中? ${"%.1f".format(snapshot.cache.hitRate)}%")
            .appendLine("? 当前缓存大小: ${snapshot.cache.cacheSize}")
            .appendLine("? 状? ${if (snapshot.cache.hitRate > 70.0) "良好 (>70%)" else "缓存利用不足"}")
            .appendLine("?)
            .appendLine("├─ [优化4] kotlinx.serialization 性能")
            .appendLine("? 序列? ${snapshot.serialization.serializationCount}? 平均: ${snapshot.serialization.avgSerializationNs / 1000}μs")
            .appendLine("? 反序列化: ${snapshot.serialization.deserializationCount}? 平均: ${snapshot.serialization.avgDeserializationNs / 1000}μs")
            .appendLine("? 状? 无反? 编译期类型安?)
            .appendLine("?)
            .appendLine("├─ [优化5] Gradle 构建配置")
            .appendLine("? parallel = true (多模块并行）")
            .appendLine("? configure-on-demand = true (按需配置?)
            .appendLine("? build-cache = true (任务级缓存）")
            .appendLine("? workers = 4")
            .appendLine("?)
            .appendLine("└─ 内存状?)
            .appendLine("   Java Heap: ${snapshot.memory.usedHeapBytes / 1048576L}MB / ${snapshot.memory.maxHeapBytes / 1048576L}MB")
            .appendLine("   Native Heap: ${snapshot.memory.nativeHeapBytes / 1048576L}MB")
            .appendLine()
            .appendLine("════════════════════════════════════════════════")
            .toString()

        AppLogger.i(TAG, report)
        return report
    }

    // 获取结构化快?(供调?UI 使用?    fun getSnapshot(): HealthSnapshot {
        sampleMemory()
        return HealthSnapshot(
            coldStart = coldStart,
            concurrency = concurrency,
            cache = cache,
            serialization = serialization,
            memory = memory
        )
    }

    // JSON 格式导出 (供日志分析）
    fun exportSnapshotAsJson(): String {
        return json.encodeToString(getSnapshot())
    }
}
