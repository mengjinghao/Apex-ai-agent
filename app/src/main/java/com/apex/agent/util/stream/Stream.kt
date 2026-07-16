package com.apex.util.stream

import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

object StreamLogger {
    private const val TAG = "StreamFramework"
    private var enabled = true
    private var verboseEnabled = false

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun setVerboseEnabled(enabled: Boolean) {
        this.verboseEnabled = enabled
    }

    fun d(component: String, message: String) {
        if (enabled) {
            AppLogger.d(TAG, "[${component}] ${message}")
        }
    }

    fun i(component: String, message: String) {
        if (enabled) {
            AppLogger.i(TAG, "[${component}] ${message}")
        }
    }

    fun v(component: String, message: String) {
        if (enabled && verboseEnabled) {
            AppLogger.v(TAG, "[${component}] ${message}")
        }
    }

    fun w(component: String, message: String) {
        if (enabled) {
            AppLogger.w(TAG, "[${component}] ${message}")
        }
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            if (throwable != null) {
                AppLogger.e(TAG, "[${component}] ${message}", throwable)
            } else {
                AppLogger.e(TAG, "[${component}] ${message}")
            }
        }
    }
}

enum class OverflowPolicy {
    SUSPEND,
    DROP_OLDEST,
    DROP_NEWEST,
    THROW
}

interface Stream<T> {
    val isLocked: Boolean
    val bufferedCount: Int
    suspend fun lock()
    suspend fun unlock()
    fun clearBuffer()
    suspend fun collect(collector: StreamCollector<T>)
    suspend fun collect(onEach: suspend (T) -> Unit) {
        collect(
            object : StreamCollector<T> {
                override suspend fun emit(value: T) {
                    StreamLogger.v("Stream", "收集到元�?${value}")
                    onEach(value)
                }
            }
        )
    }
}

interface StreamCollector<in T> {
    suspend fun emit(value: T)
}

data class StreamStats(
    val emittedCount: Long = 0,
    val bufferedCount: Long = 0,
    val droppedCount: Long = 0,
    val errorCount: Long = 0,
    val lockedCount: Long = 0,
    val unlockedCount: Long = 0
)

interface StreamLockListener {
    suspend fun onLock()
    suspend fun onUnlock(bufferedSize: Int)
    suspend fun onBufferOverflow(policy: OverflowPolicy, droppedValue: Any)
}

abstract class AbstractStream<T> : Stream<T> {
    private val mutex = Mutex()
    private val isLockedFlag = AtomicBoolean(false)
    private val isClosedFlag = AtomicBoolean(false)
    private val buffer = ConcurrentLinkedQueue<T>()
    private val listeners = ConcurrentLinkedQueue<StreamLockListener>()
    
    private val emittedCount = AtomicInteger(0)
    private val droppedCount = AtomicInteger(0)
    private val lockedCount = AtomicInteger(0)
    private val unlockedCount = AtomicInteger(0)

    override val isLocked: Boolean get() = isLockedFlag.get()
    override val bufferedCount: Int get() = buffer.size
    
    protected val stats: StreamStats
        get() = StreamStats(
            emittedCount = emittedCount.get().toLong(),
            bufferedCount = buffer.size.toLong(),
            droppedCount = droppedCount.get().toLong(),
            lockedCount = lockedCount.get().toLong(),
            unlockedCount = unlockedCount.get().toLong()
        )
    
    fun addLockListener(listener: StreamLockListener) {
        listeners.add(listener)
    }
    
    fun removeLockListener(listener: StreamLockListener) {
        listeners.remove(listener)
    }
    
    protected suspend fun notifyLock() {
        for (listener in listeners) {
            try {
                listener.onLock()
            } catch (e: Exception) {
                StreamLogger.w("AbstractStream", "Lock listener error: ${e.message}")
            }
        }
    }
    
    protected suspend fun notifyUnlock(bufferedSize: Int) {
        for (listener in listeners) {
            try {
                listener.onUnlock(bufferedSize)
            } catch (e: Exception) {
                StreamLogger.w("AbstractStream", "Unlock listener error: ${e.message}")
            }
        }
    }
    
    protected suspend fun notifyBufferOverflow(policy: OverflowPolicy, droppedValue: Any) {
        for (listener in listeners) {
            try {
                listener.onBufferOverflow(policy, droppedValue)
            } catch (e: Exception) {
                StreamLogger.w("AbstractStream", "Buffer overflow listener error: ${e.message}")
            }
        }
    }
    
    override suspend fun lock() {
        mutex.withLock {
            if (!isLockedFlag.get() && !isClosedFlag.get()) {
                isLockedFlag.set(true)
                lockedCount.incrementAndGet()
                StreamLogger.d("Stream", "流已锁定")
                notifyLock()
            }
        }
    }
    
    suspend fun lock(timeout: Duration): Boolean {
        return withTimeoutOrNull(timeout.inWholeMilliseconds) {
            lock()
            true
        } ?: false
    }
    
    fun tryLock(): Boolean {
        if (mutex.tryLock()) {
            try {
                if (!isLockedFlag.get() && !isClosedFlag.get()) {
                    isLockedFlag.set(true)
                    lockedCount.incrementAndGet()
                    StreamLogger.d("Stream", "流已锁定(try)")
                    return true
                }
            } finally {
                mutex.unlock()
            }
        }
        return false
    }
    
    override suspend fun unlock() {
        mutex.withLock {
            if (isLockedFlag.compareAndSet(true, false)) {
                val bufferSize = buffer.size
                unlockedCount.incrementAndGet()
                StreamLogger.d("Stream", "流已解锁，发送缓存数�?�?{bufferSize项}�?)
                notifyUnlock(bufferSize)
                
                val tempList = ArrayList<T>(buffer)
                buffer.clear()
                
                for (item in tempList) {
                    try {
                        emitBufferedItem(item)
                    } catch (e: Exception) {
                        StreamLogger.w("Stream", "处理缓存项时发生异常: ${e.message}")
                        throw e
                    }
                }
            }
        }
    }
    
    suspend fun unlock(timeout: Duration): Boolean {
        return withTimeoutOrNull(timeout.inWholeMilliseconds) {
            unlock()
            true
        } ?: false
    }
    
    override fun clearBuffer() {
        val size = buffer.size
        buffer.clear()
        StreamLogger.d("Stream", "已清空缓冲区 (�?{size项}�?)
    }
    
    protected suspend fun tryBuffer(value: T, policy: OverflowPolicy = OverflowPolicy.SUSPEND): Boolean {
        if (isLockedFlag.get() && !isClosedFlag.get()) {
            when (policy) {
                OverflowPolicy.SUSPEND -> {
                    buffer.offer(value)
                    StreamLogger.v("Stream", "锁定中，值已缓存")
                    return true
                }
                OverflowPolicy.DROP_OLDEST -> {
                    buffer.poll()
                    buffer.offer(value)
                    droppedCount.incrementAndGet()
                    StreamLogger.v("Stream", "锁定中，DROP_OLDEST策略")
                    notifyBufferOverflow(policy, value)
                    return true
                }
                OverflowPolicy.DROP_NEWEST -> {
                    buffer.offer(value)
                    buffer.poll()
                    droppedCount.incrementAndGet()
                    StreamLogger.v("Stream", "锁定中，DROP_NEWEST策略")
                    notifyBufferOverflow(policy, value)
                    return true
                }
                OverflowPolicy.THROW -> {
                    notifyBufferOverflow(policy, value)
                    throw BufferOverflowException("Buffer overflow with THROW policy")
                }
            }
        }
        return false
    }
    
    protected fun incrementEmitted() {
        emittedCount.incrementAndGet()
    }
    
    protected abstract suspend fun emitBufferedItem(item: T)
    
    protected fun markClosed() {
        isClosedFlag.set(true)
    }
    
    protected fun isClosed(): Boolean = isClosedFlag.get()
}

class BufferOverflowException(message: String) : Exception(message)

class FlowAsStream<T>(
    private val flow: Flow<T>,
    private val bufferPolicy: OverflowPolicy = OverflowPolicy.SUSPEND
) : AbstractStream<T>() {
    private var activeCollector: StreamCollector<T>? = null

    override suspend fun collect(collector: StreamCollector<T>) {
        activeCollector = collector
        
        try {
            flow.collect { value ->
                incrementEmitted()
                if (!isClosed() && !tryBuffer(value, bufferPolicy)) {
                    collector.emit(value)
                }
            }
        } finally {
            markClosed()
            
            if (isLocked) {
                StreamLogger.i("FlowAsStream", "流关闭时处于锁定状态，尝试解锁处理缓冲数据")
                try {
                    unlock()
                } catch (e: Exception) {
                    StreamLogger.w("FlowAsStream", "流关闭时解锁失败: ${e.message}")
                }
            }
        }
    }
    
    override suspend fun emitBufferedItem(item: T) {
        activeCollector?.emit(item)
    }
}

class StreamAsFlow<T>(private val stream: Stream<T>) : Flow<T> {
    override suspend fun collect(collector: FlowCollector<T>) {
        stream.collect { value ->
            collector.emit(value)
        }
    }
}

class FlowAsStreamWithBackpressure<T>(
    private val flow: Flow<T>,
    private val bufferSize: Int = 64,
    private val overflow: BufferOverflow = BufferOverflow.SUSPEND
) : AbstractStream<T>() {
    private var activeCollector: StreamCollector<T>? = null
    private val backpressureBuffer = ArrayDeque<T>()
    private val processedCount = AtomicInteger(0)
    private val needsMore = AtomicBoolean(true)

    override suspend fun collect(collector: StreamCollector<T>) {
        activeCollector = collector
        
        try {
            flow.collect { value ->
                incrementEmitted()
                if (isLocked) {
                    if (backpressureBuffer.size >= bufferSize) {
                        when (overflow) {
                            BufferOverflow.SUSPEND -> {
                                while (backpressureBuffer.isNotEmpty() && isLocked) {
                                    kotlinx.coroutines.delay(10)
                                }
                                if (!isLocked) {
                                    collector.emit(value)
                                    return@collect
                                }
                            }
                            BufferOverflow.DROP_OLDEST -> {
                                backpressureBuffer.removeFirst()
                                backpressureBuffer.addLast(value)
                                droppedCount.incrementAndGet()
                                return@collect
                            }
                            BufferOverflow.DROP_NEWEST -> {
                                backpressureBuffer.removeLast()
                                backpressureBuffer.addLast(value)
                                droppedCount.incrementAndGet()
                                return@collect
                            }
                            BufferOverflow.ONBufferOverflow -> {
                                throw BufferOverflowException("Buffer overflow")
                            }
                        }
                    }
                    backpressureBuffer.addLast(value)
                } else {
                    collector.emit(value)
                    processedCount.incrementAndGet()
                }
            }
        } finally {
            markClosed()
            
            while (backpressureBuffer.isNotEmpty() && !isLocked) {
                collector.emit(backpressureBuffer.removeFirst())
                processedCount.incrementAndGet()
            }
            
            if (isLocked && backpressureBuffer.isEmpty()) {
                unlock()
            }
        }
    }
    
    override suspend fun emitBufferedItem(item: T) {
        activeCollector?.emit(item)
        processedCount.incrementAndGet()
    }
    
    fun requestMore() {
        needsMore.set(true)
    }
    
    fun pauseEmission() {
        needsMore.set(false)
    }
    
    fun getProcessedCount(): Int = processedCount.get()
    
    fun getPendingCount(): Int = backpressureBuffer.size
}

class BatchStreamCollector<T>(
    private val upstream: StreamCollector<T>,
    private val batchSize: Int = 10,
    private val flushTimeout: Duration = Duration.parse("100ms")
) : StreamCollector<T> {
    private val batch = ArrayList<T>(batchSize)
    private var lastFlushTime = System.currentTimeMillis()
    private val lock = Any()
    
    override suspend fun emit(value: T) {
        synchronized(lock) {
            batch.add(value)
            val shouldFlush = batch.size >= batchSize || 
                (System.currentTimeMillis() - lastFlushTime) >= flushTimeout.inWholeMilliseconds
            
            if (shouldFlush && batch.isNotEmpty()) {
                val toEmit = ArrayList(batch)
                batch.clear()
                lastFlushTime = System.currentTimeMillis()
                
                for (item in toEmit) {
                    upstream.emit(item)
                }
            }
        }
    }
    
    suspend fun flush() {
        synchronized(lock) {
            if (batch.isNotEmpty()) {
                val toEmit = ArrayList(batch)
                batch.clear()
                for (item in toEmit) {
                    upstream.emit(item)
                }
            }
        }
    }
}

class ConditionalLockStream<T>(
    private val upstream: Stream<T>,
    private val shouldLock: suspend (T) -> Boolean = { true }
) : Stream<T> by upstream {
    private var pendingValues = ArrayDeque<T>()
    private val conditionMutex = Mutex()
    
    override val isLocked: Boolean get() = upstream.isLocked
    override val bufferedCount: Int get() = upstream.bufferedCount + pendingValues.size
    
    override suspend fun lock() {
        upstream.lock()
    }
    
    override suspend fun unlock() {
        for (value in pendingValues) {
            try {
                if (shouldLock(value)) {
                    pendingValues.remove(value)
                }
            } catch (e: Exception) {
                StreamLogger.w("ConditionalLockStream", "Condition check error: ${e.message}")
            }
        }
        upstream.unlock()
    }
    
    override fun clearBuffer() {
        pendingValues.clear()
        upstream.clearBuffer()
    }
    
    override suspend fun collect(collector: StreamCollector<T>) {
        upstream.collect(object : StreamCollector<T> {
            override suspend fun emit(value: T) {
                if (isLocked) {
                    if (shouldLock(value)) {
                        pendingValues.addLast(value)
                    } else {
                        collector.emit(value)
                    }
                } else {
                    collector.emit(value)
                }
            }
        })
    }
}

fun <T> Flow<T>.asStream(policy: OverflowPolicy = OverflowPolicy.SUSPEND): Stream<T> = 
    FlowAsStream(this, policy)

fun <T> Flow<T>.asStreamWithBackpressure(
    bufferSize: Int = 64,
    overflow: BufferOverflow = BufferOverflow.SUSPEND
): Stream<T> = FlowAsStreamWithBackpressure(this, bufferSize, overflow)

fun <T> Stream<T>.asFlow(): Flow<T> = StreamAsFlow(this)

fun <T> Stream<T>.launchIn(scope: CoroutineScope, onEach: suspend (T) -> Unit = {}): Job {
    StreamLogger.d("Stream.launchIn", "在协程作用域中启动Stream收集")
    return scope.launch {
        collect { value ->
            StreamLogger.v("Stream.launchIn", "收集到元�?${value}")
            onEach(value)
        }
    }
}

fun <T> Stream<T>.withBatchCollection(
    batchSize: Int = 10,
    flushTimeout: Duration = Duration.parse("100ms")
): Stream<T> {
    return object : Stream<T> {
        override val isLocked: Boolean get() = this@withBatchCollection.isLocked
        override val bufferedCount: Int get() = this@withBatchCollection.bufferedCount
        
        override suspend fun lock() = this@withBatchCollection.lock()
        override suspend fun unlock() = this@withBatchCollection.unlock()
        override fun clearBuffer() = this@withBatchCollection.clearBuffer()
        
        override suspend fun collect(collector: StreamCollector<T>) {
            val batchCollector = BatchStreamCollector(collector, batchSize, flushTimeout)
            this@withBatchCollection.collect(batchCollector)
            batchCollector.flush()
        }
    }
}

fun <T> Stream<T>.withConditionalLock(shouldLock: suspend (T) -> Boolean): Stream<T> =
    ConditionalLockStream(this, shouldLock)

fun <T> Stream<T>.withTimeoutLock(timeout: Duration): Stream<T> {
    return object : Stream<T> {
        override val isLocked: Boolean get() = this@withTimeoutLock.isLocked
        override val bufferedCount: Int get() = this@withTimeoutLock.bufferedCount
        
        override suspend fun lock() {
            if (!lock(timeout)) {
                throw LockTimeoutException("Failed to acquire lock within ${timeout}")
            }
        }
        
        override suspend fun unlock() {
            if (!unlock(timeout)) {
                throw LockTimeoutException("Failed to release lock within ${timeout}")
            }
        }
        
        override fun clearBuffer() = this@withTimeoutLock.clearBuffer()
        
        override suspend fun collect(collector: StreamCollector<T>) {
            this@withTimeoutLock.collect(collector)
        }
    }
}

class LockTimeoutException(message: String) : Exception(message)

abstract class AbstractBufferedStream<T>(
    initialCapacity: Int = 16,
    private val overflowPolicy: OverflowPolicy = OverflowPolicy.SUSPEND
) : AbstractStream<T>() {
    private val buffer: ArrayDeque<T> = ArrayDeque(initialCapacity)
    private val capacity: Int
    
    init {
        this.capacity = if (initialCapacity <= 0) Int.MAX_VALUE else initialCapacity
    }
    
    override val bufferedCount: Int get() = buffer.size
    
    protected fun isBufferFull(): Boolean = buffer.size >= capacity
    
    protected fun canBuffer(): Boolean = capacity > 0
    
    protected suspend fun addToBuffer(value: T): Boolean {
        if (!canBuffer()) return false
        
        if (isBufferFull()) {
            return when (overflowPolicy) {
                OverflowPolicy.SUSPEND -> {
                    while (isBufferFull() && !isClosed()) {
                        kotlinx.coroutines.delay(10)
                    }
                    if (!isClosed()) {
                        buffer.addLast(value)
                        true
                    } else {
                        false
                    }
                }
                OverflowPolicy.DROP_OLDEST -> {
                    buffer.removeFirst()
                    buffer.addLast(value)
                    true
                }
                OverflowPolicy.DROP_NEWEST -> {
                    buffer.removeLast()
                    buffer.addLast(value)
                    true
                }
                OverflowPolicy.THROW -> {
                    throw BufferOverflowException("Buffer overflow in AbstractBufferedStream")
                }
            }
        } else {
            buffer.addLast(value)
            return true
        }
    }
    
    protected fun pollFromBuffer(): T? = buffer.pollFirst()
    
    protected fun peekBuffer(): T? = buffer.peekFirst()
    
    protected fun getBufferContent(): List<T> = buffer.toList()
    
    override fun clearBuffer() {
        buffer.clear()
    }
}

interface StreamProcessor<T> {
    suspend fun process(value: T): T
    suspend fun onStart() {}
    suspend fun onEnd() {}
}

fun <T, R> Stream<T>.transform(transformer: StreamProcessor<T>): Stream<R> where R : T {
    return object : Stream<R> {
        override val isLocked: Boolean get() = this@transform.isLocked
        override val bufferedCount: Int get() = this@transform.bufferedCount
        
        override suspend fun lock() = this@transform.lock()
        override suspend fun unlock() = this@transform.unlock()
        override fun clearBuffer() = this@transform.clearBuffer()
        
        override suspend fun collect(collector: StreamCollector<R>) {
            transformer.onStart()
            try {
                this@transform.collect(object : StreamCollector<T> {
                    override suspend fun emit(value: T) {
                        val transformed = transformer.process(value)
                        @Suppress("UNCHECKED_CAST")
                        collector.emit(transformed as R)
                    }
                })
            } finally {
                transformer.onEnd()
            }
        }
    }
}

fun <T> Stream<T>.observeLockState(
    onLock: suspend () -> Unit = {},
    onUnlock: suspend (bufferedSize: Int) -> Unit = {},
    onOverflow: suspend (policy: OverflowPolicy, droppedValue: Any) -> Unit = { _, _ -> }
): Stream<T> {
    val listener = object : StreamLockListener {
        override suspend fun onLock() = onLock()
        override suspend fun onUnlock(bufferedSize: Int) = onUnlock(bufferedSize)
        override suspend fun onBufferOverflow(policy: OverflowPolicy, droppedValue: Any) = onOverflow(policy, droppedValue)
    }
    
    addLockListener(listener)
    
    return object : Stream<T> by this {}
}

fun <T> Stream<T>.withBufferCapacity(
    capacity: Int,
    overflowPolicy: OverflowPolicy = OverflowPolicy.SUSPEND
): Stream<T> {
    return object : AbstractBufferedStream<T>(capacity, overflowPolicy) {
        private var upstreamCollector: StreamCollector<T>? = null
        
        override suspend fun collect(collector: StreamCollector<T>) {
            upstreamCollector = collector
            this@withBufferCapacity.collect(object : StreamCollector<T> {
                override suspend fun emit(value: T) {
                    if (isLocked) {
                        if (!addToBuffer(value)) {
                            return
                        }
                    } else {
                        collector.emit(value)
                    }
                }
            })
            
            while (peekBuffer() != null && !isLocked) {
                pollFromBuffer()?.let { collector.emit(it) }
            }
        }
        
        override suspend fun emitBufferedItem(item: T) {
            upstreamCollector?.emit(item)
        }
    }
}
