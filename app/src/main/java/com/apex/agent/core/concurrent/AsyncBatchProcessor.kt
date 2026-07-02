package com.apex.agent.core.concurrent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AsyncBatchProcessor<T, R>(
    private val name: String,
    private val batchSize: Int = 100,
    private val flushIntervalMs: Long = 1000L,
    private val maxConcurrency: Int = 4,
    private val processor: suspend (List<T>) -> List<R>,
    private val errorHandler: (suspend (List<T>, Exception) -> Unit)? = null
) {
    data class BatchMetrics(
        val totalSubmitted: Long,
        val totalProcessed: Long,
        val totalBatches: Long,
        val totalFailed: Long,
        val currentQueueSize: Int,
        val averageBatchSize: Double,
        val averageProcessingTimeMs: Double,
        val throughputPerSecond: Double
    )

    private val logger = LoggerFactory.getLogger("BatchProcessor-$name")
    private val queue = ConcurrentLinkedQueue<T>()
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<R>>()
    private val itemKeys = ConcurrentLinkedQueue<String>()
    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flushTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val isRunning = AtomicBoolean(true)
    private val flushJob: Job

    private val submitted = AtomicLong(0)
    private val processed = AtomicLong(0)
    private val batches = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val totalProcessingTimeNs = AtomicLong(0)
    private val totalBatchItems = AtomicLong(0)
    private val throughputCounter = AtomicLong(0)
    private val throughputWindowNs = AtomicLong(System.nanoTime())

    init {
        flushJob = batchScope.launch {
            while (isRunning.get()) {
                flushTrigger.emit(Unit)
                delay(flushIntervalMs)
            }
        }

        batchScope.launch {
            flushTrigger.collect {
                if (queue.size >= batchSize || !isRunning.get()) {
                    processBatch()
                }
            }
        }

        batchScope.launch {
            while (isRunning.get()) {
                delay(flushIntervalMs)
                if (queue.isNotEmpty()) {
                    processBatch()
                }
            }
        }
    }

    suspend fun submit(item: T, key: String = "$item"): R {
        val deferred = CompletableDeferred<R>()
        pendingResults[key] = deferred
        itemKeys.add(key)
        queue.add(item)
        submitted.incrementAndGet()
        if (queue.size >= batchSize) {
            flushTrigger.tryEmit(Unit)
        }
        return deferred.await()
    }

    suspend fun submitAll(items: List<Pair<String, T>>): List<R> {
        val deferreds = mutableListOf<Pair<String, CompletableDeferred<R>>>()
        for ((key, item) in items) {
            val deferred = CompletableDeferred<R>()
            pendingResults[key] = deferred
            itemKeys.add(key)
            queue.add(item)
            deferreds.add(key to deferred)
            submitted.incrementAndGet()
        }
        if (queue.size >= batchSize) {
            flushTrigger.tryEmit(Unit)
        }
        return deferreds.map { it.second.await() }
    }

    fun trySubmit(item: T, key: String = "$item"): R? {
        val deferred = CompletableDeferred<R>()
        pendingResults[key] = deferred
        itemKeys.add(key)
        queue.add(item)
        submitted.incrementAndGet()
        return try {
            runBlocking { deferred.await() }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun flush() {
        while (queue.isNotEmpty()) {
            processBatch()
        }
    }

    fun getMetrics(): BatchMetrics {
        val totalBatches = batches.get()
        val totalSubmitted = submitted.get()
        val totalItems = totalBatchItems.get()
        val avgBatchSize = if (totalBatches > 0) totalItems.toDouble() / totalBatches else 0.0
        val avgTimeMs = if (totalBatches > 0)
            totalProcessingTimeNs.get().toDouble() / totalBatches / 1_000_000.0 else 0.0
        val now = System.nanoTime()
        val windowDuration = (now - throughputWindowNs.get()).coerceAtLeast(1)
        val tps = throughputCounter.get().toDouble() / windowDuration * 1_000_000_000.0
        return BatchMetrics(
            totalSubmitted = totalSubmitted,
            totalProcessed = processed.get(),
            totalBatches = totalBatches,
            totalFailed = failed.get(),
            currentQueueSize = queue.size,
            averageBatchSize = avgBatchSize,
            averageProcessingTimeMs = avgTimeMs,
            throughputPerSecond = tps
        )
    }

    fun shutdown() {
        isRunning.set(false)
        runBlocking {
            flush()
        }
        batchScope.cancel()
    }

    private suspend fun processBatch() {
        val items = mutableListOf<T>()
        val keys = mutableListOf<String>()
        while (items.size < batchSize) {
            val item = queue.poll() ?: break
            items.add(item)
            keys.add(itemKeys.poll() ?: continue)
        }
        if (items.isEmpty()) return

        val batchStart = System.nanoTime()
        batches.incrementAndGet()
        totalBatchItems.addAndGet(items.size)

        try {
            val concurrency = maxConcurrency.coerceAtMost(items.size)
            val chunkSize = (items.size + concurrency - 1) / concurrency
            val chunks = items.chunked(chunkSize)
            val keyChunks = keys.chunked(chunkSize)

            val deferreds = chunks.mapIndexed { index, chunk ->
                batchScope.async {
                    processor(chunk)
                }
            }

            val allResults = deferreds.awaitAll().flatten()
            val resultIter = allResults.iterator()

            var idx = 0
            for (key in keys) {
                if (resultIter.hasNext()) {
                    val result = resultIter.next()
                    pendingResults.remove(key)?.complete(result)
                    processed.incrementAndGet()
                }
                idx++
            }

            val elapsed = System.nanoTime() - batchStart
            totalProcessingTimeNs.addAndGet(elapsed)
            throughputCounter.addAndGet(items.size)

            logger.debug("Batch processed: {} items in {} ms", items.size, elapsed / 1_000_000)
        } catch (e: Exception) {
            failed.addAndGet(items.size.toLong())
            logger.warn("Batch processing failed for {} items", items.size, e)
            errorHandler?.invoke(items, e)
            for (key in keys) {
                pendingResults.remove(key)?.completeExceptionally(e)
            }
        }
    }
}

class AsyncCollector<T>(
    private val name: String = "collector",
    private val maxBufferSize: Int = 1000,
    private val drainIntervalMs: Long = 100L
) {
    private val buffer = ConcurrentLinkedQueue<T>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _flow = MutableSharedFlow<List<T>>(replay = 0, extraBufferCapacity = 64)
    val flow: SharedFlow<List<T>> = _flow.asSharedFlow()

    private val collected = AtomicLong(0)
    private val drained = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(drainIntervalMs)
                drain()
            }
        }
    }

    fun add(item: T) {
        buffer.add(item)
        collected.incrementAndGet()
        if (buffer.size >= maxBufferSize) {
            drain()
        }
    }

    fun addAll(items: Collection<T>) {
        buffer.addAll(items)
        collected.addAndGet(items.size.toLong())
        if (buffer.size >= maxBufferSize) {
            drain()
        }
    }

    fun drain() {
        if (buffer.isEmpty()) return
        val batch = mutableListOf<T>()
        while (batch.size < maxBufferSize) {
            buffer.poll()?.let { batch.add(it) } ?: break
        }
        if (batch.isNotEmpty()) {
            drained.addAndGet(batch.size.toLong())
            _flow.tryEmit(batch)
        }
    }

    fun getMetrics(): Pair<Long, Long> = collected.get() to drained.get()

    fun shutdown() { scope.cancel() }
}

class AsyncDebouncer<T>(
    private val intervalMs: Long = 300L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _flow = MutableSharedFlow<T>(replay = 0, extraBufferCapacity = 64)
    val flow: SharedFlow<T> = _flow.asSharedFlow()
    private var pendingValue: T? = null
    private var lastEmitTime = 0L
    private val mutex = Any()

    fun emit(value: T) {
        synchronized(mutex) {
            pendingValue = value
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val elapsed = now - lastEmitTime
            if (elapsed >= intervalMs) {
                synchronized(mutex) {
                    pendingValue?.let {
                        _flow.tryEmit(it)
                        pendingValue = null
                        lastEmitTime = System.currentTimeMillis()
                    }
                }
            } else {
                delay(intervalMs - elapsed)
                synchronized(mutex) {
                    pendingValue?.let {
                        _flow.tryEmit(it)
                        pendingValue = null
                        lastEmitTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }
}

class AsyncThrottler(
    private val maxCallsPerSecond: Int = 10,
    private val maxBurst: Int = 20
) {
    private val tokenBucket = TokenBucket(maxBurst, maxCallsPerSecond)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun <T> throttle(block: suspend () -> T): T {
        while (!tokenBucket.tryConsume()) {
            delay(1000L / maxCallsPerSecond)
        }
        return block()
    }

    fun tryThrottle(block: () -> Unit): Boolean {
        if (tokenBucket.tryConsume()) {
            block()
            return true
        }
        return false
    }

    private class TokenBucket(
        private val maxTokens: Int,
        private val refillRate: Int
    ) {
        private val tokens = AtomicInteger(maxTokens)
        private val lastRefillNs = AtomicLong(System.nanoTime())

        fun tryConsume(): Boolean {
            refill()
            val current = tokens.get()
            return current > 0 && tokens.compareAndSet(current, current - 1)
        }

        private fun refill() {
            val now = System.nanoTime()
            val last = lastRefillNs.get()
            val elapsedSec = (now - last) / 1_000_000_000.0
            if (elapsedSec >= 1.0 && lastRefillNs.compareAndSet(last, now)) {
                val toAdd = (elapsedSec * refillRate).toInt().coerceAtLeast(1)
                tokens.updateAndGet { current -> (current + toAdd).coerceAtMost(maxTokens) }
            }
        }
    }
}

object CoroutineScopeFactory {
    private val scopeCounter = AtomicInteger(0)

    fun create(name: String = "scope"): CoroutineScope {
        val id = scopeCounter.incrementAndGet()
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("$name-$id"))
    }

    fun createIO(name: String = "scope"): CoroutineScope {
        val id = scopeCounter.incrementAndGet()
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("$name-io-$id"))
    }
}

object CoroutineUtils {
    suspend fun <T> withTimeoutOrNullSafe(timeoutMs: Long, block: suspend CoroutineScope.() -> T): T? {
        return try {
            withTimeoutOrNull(timeoutMs) { block() }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun <T> retry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 100L,
        backoffFactor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var delay = initialDelayMs
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(delay)
                    delay = (delay * backoffFactor).toLong()
                }
            }
        }
        throw lastException ?: RuntimeException("Retry failed")
    }

    suspend fun <T> retryWithExponentialBackoff(
        maxRetries: Int = 3,
        baseDelayMs: Long = 100L,
        maxDelayMs: Long = 10000L,
        predicate: (Exception) -> Boolean = { true },
        block: suspend () -> T
    ): T {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (!predicate(e) || attempt == maxRetries - 1) throw e
                val delayMs = (baseDelayMs * (1 shl attempt)).coerceAtMost(maxDelayMs)
                delay(delayMs)
            }
        }
        throw lastError ?: RuntimeException("Retry failed")
    }

    suspend fun <T> withTimeoutWithFallback(
        timeoutMs: Long,
        fallback: T,
        block: suspend CoroutineScope.() -> T
    ): T {
        return withTimeoutOrNull(timeoutMs) { block() } ?: fallback
    }

    suspend fun <T> runParallel(
        concurrency: Int = 4,
        tasks: List<suspend () -> T>
    ): List<T> {
        return coroutineScope {
            tasks.chunked(concurrency).flatMap { chunk ->
                chunk.map { async { it() } }.awaitAll()
            }
        }
    }

    suspend fun <T> runParallelBatched(
        items: List<T>,
        concurrency: Int = 4,
        block: suspend (T) -> Unit
    ) {
        coroutineScope {
            items.chunked(concurrency).forEach { chunk ->
                chunk.map { async { block(it) } }.awaitAll()
            }
        }
    }

    fun <T> Iterable<T>.forEachParallel(
        pool: AdaptiveThreadPool,
        action: (T) -> Unit
    ) {
        val futures = map { pool.submitTask { action(it) } }
        futures.forEach { try { it.get() } catch (_: Exception) {} }
    }
}
