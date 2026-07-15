package com.apex.agent.core.workflow.enhanced.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Span 状态
 */
enum class SpanStatus { UNSET, OK, ERROR }

/**
 * Trace Span - 代表一个节点执行的追踪单元
 *
 * 参照 OpenTelemetry 的 Span 模型，但保持轻量、无外部依赖
 */
interface Span : AutoCloseable {
    val spanId: String
    val parentId: String?
    val name: String
    val threadId: String
    val nodeId: String?
    val startTimeMs: Long

    fun setAttribute(key: String, value: Any): Span
    fun addEvent(name: String, attributes: Map<String, Any> = emptyMap()): Span
    fun recordException(t: Throwable): Span
    fun end(status: SpanStatus = SpanStatus.OK)

    /** 默认 close 实现：以 ERROR 结束未结束的 span */
    override fun close() {
        end(SpanStatus.ERROR)
    }
}

/**
 * Span 记录（不可变快照）
 */
data class SpanRecord(
    val spanId: String,
    val parentId: String?,
    val name: String,
    val threadId: String,
    val nodeId: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val status: SpanStatus,
    val attributes: Map<String, Any>,
    val events: List<SpanEvent>,
    val exception: Throwable?
)

data class SpanEvent(
    val name: String,
    val timestampMs: Long,
    val attributes: Map<String, Any>
)

/**
 * 工作流追踪器接口
 */
interface WorkflowTracer {
    /**
     * 开始一个新 span
     * @param name span 名称（如 "node:SendMessage"）
     * @param threadId 工作流执行线程 ID
     * @param nodeId 关联的节点 ID（可空）
     * @param parentSpanId 父 span ID（可空，空则根 span）
     * @param attributes 初始属性
     */
    fun startSpan(
        name: String,
        threadId: String,
        nodeId: String? = null,
        parentSpanId: String? = null,
        attributes: Map<String, Any> = emptyMap()
    ): Span

    /** 获取某次执行的所有 span */
    fun snapshot(threadId: String): List<SpanRecord>

    /** 获取所有活跃线程 ID */
    fun activeThreads(): Set<String>

    /** 清除某线程的 span 记录 */
    fun clear(threadId: String)

    /** 清除所有 */
    fun clearAll()
}

/**
 * 内存追踪器 - 开箱即用，无需 OTel collector
 *
 * 适合 Android 端本地调试与 UI 火焰图展示
 */
class InMemoryTracer(
    private val maxSpansPerThread: Int = 10_000
) : WorkflowTracer {

    private val spansByThread = ConcurrentHashMap<String, ConcurrentLinkedQueue<SpanRecord>>()
        private val activeSpans = ConcurrentHashMap<String, ActiveSpan>()

    override fun startSpan(
        name: String,
        threadId: String,
        nodeId: String?,
        parentSpanId: String?,
        attributes: Map<String, Any>
    ): Span {
        val spanId = "span_${spanCounter.incrementAndGet()}"
        val span = ActiveSpan(
            spanId = spanId,
            parentId = parentSpanId,
            name = name,
            threadId = threadId,
            nodeId = nodeId,
            startTimeMs = System.currentTimeMillis(),
            attributesRef = ConcurrentHashMap(attributes),
            eventsRef = ConcurrentLinkedQueue(),
            statusRef = AtomicReference(SpanStatus.UNSET),
            endedRef = AtomicReference(false)
        )
        activeSpans[spanId] = span
        return span
    }

    override fun snapshot(threadId: String): List<SpanRecord> {
        val queue = spansByThread[threadId] ?: return emptyList()
        return queue.toList().sortedBy { it.startTimeMs }
    }

    override fun activeThreads(): Set<String> = spansByThread.keys.toSet()

    override fun clear(threadId: String) {
        spansByThread.remove(threadId)
    }

    override fun clearAll() {
        spansByThread.clear()
        activeSpans.clear()
    }
        private fun commitSpan(span: ActiveSpan, status: SpanStatus) {
        if (!span.endedRef.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        val record = SpanRecord(
            spanId = span.spanId,
            parentId = span.parentId,
            name = span.name,
            threadId = span.threadId,
            nodeId = span.nodeId,
            startTimeMs = span.startTimeMs,
            endTimeMs = now,
            durationMs = now - span.startTimeMs,
            status = status,
            attributes = span.attributesRef.toMap(),
            events = span.eventsRef.toList(),
            exception = span.attributesRef["exception"] as? Throwable
        )
        val queue = spansByThread.computeIfAbsent(span.threadId) { ConcurrentLinkedQueue() }
        queue.add(record)
        // 限制大小，FIFO 淘汰
        while (queue.size > maxSpansPerThread) queue.poll()
        activeSpans.remove(span.spanId)
    }
        private inner class ActiveSpan(
        override val spanId: String,
        override val parentId: String?,
        override val name: String,
        override val threadId: String,
        override val nodeId: String?,
        override val startTimeMs: Long,
        private val attributesRef: ConcurrentHashMap<String, Any>,
        private val eventsRef: ConcurrentLinkedQueue<SpanEvent>,
        private val statusRef: AtomicReference<SpanStatus>,
        private val endedRef: AtomicReference<Boolean>
    ) : Span {

        override fun setAttribute(key: String, value: Any): Span {
            attributesRef[key] = value
            return this
        }

        override fun addEvent(name: String, attributes: Map<String, Any>): Span {
            eventsRef.add(SpanEvent(name, System.currentTimeMillis(), attributes))
        return this
        }

        override fun recordException(t: Throwable): Span {
            attributesRef["exception"] = t
            attributesRef["exception.message"] = t.message ?: ""
            attributesRef["exception.type"] = t::class.qualifiedName ?: ""
            statusRef.set(SpanStatus.ERROR)
        return this
        }

        override fun end(status: SpanStatus) {
            val finalStatus = if (statusRef.get() == SpanStatus.ERROR) SpanStatus.ERROR else status
            commitSpan(this, finalStatus)
        }

        override fun close() {
            if (!endedRef.get()) end(SpanStatus.ERROR)
        }
    }

    companion object {
        private val spanCounter = AtomicLong(0)
    }
}

/**
 * Noop 追踪器 - 不记录任何内容，零开销
 */
object NoopTracer : WorkflowTracer {
    override fun startSpan(
        name: String,
        threadId: String,
        nodeId: String?,
        parentSpanId: String?,
        attributes: Map<String, Any>
    ): Span = NoopSpan

    override fun snapshot(threadId: String): List<SpanRecord> = emptyList()
    override fun activeThreads(): Set<String> = emptySet()
    override fun clear(threadId: String) {}
    override fun clearAll() {}
        private object NoopSpan : Span {
        override val spanId: String = "noop"
        override val parentId: String? = null
        override val name: String = "noop"
        override val threadId: String = ""
        override val nodeId: String? = null
        override val startTimeMs: Long = 0
        override fun setAttribute(key: String, value: Any) = this
        override fun addEvent(name: String, attributes: Map<String, Any>) = this
        override fun recordException(t: Throwable) = this
        override fun end(status: SpanStatus) {}
        override fun close() {}
    }
}

/**
 * 追踪器持有者 - 全局单例，可替换实现
 */
object TracerHolder {
    @Volatile
    private var instance: WorkflowTracer = InMemoryTracer()
        fun get(): WorkflowTracer = instance

    fun set(tracer: WorkflowTracer) {
        instance = tracer
    }

    /** 切换到 Noop（零开销模式） */
    fun disable() { instance = NoopTracer }

    /** 切换到内存追踪器 */
    fun enableInMemory(maxSpans: Int = 10_000) {
        instance = InMemoryTracer(maxSpans)
    }
}
