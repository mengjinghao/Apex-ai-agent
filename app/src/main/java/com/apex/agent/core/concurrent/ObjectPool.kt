package com.apex.agent.core.concurrent

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class ObjectPool<T>(
    private val name: String,
    private val minSize: Int = 4,
    private val maxSize: Int = 64,
    private val validationIntervalMs: Long = 30000L,
    private val idleTimeoutMs: Long = 60000L
) {
    data class PoolMetrics(
        val created: Long,
        val acquired: Long,
        val released: Long,
        val destroyed: Long,
        val currentSize: Int,
        val activeCount: Int,
        val peakActiveCount: Int,
        val waitCount: Long,
        val averageAcquireTimeMs: Double,
        val averageReleaseTimeMs: Double
    )

    private val logger = LoggerFactory.getLogger("ObjectPool-$name")
    private val pool = ConcurrentLinkedQueue<T>()
    private val active = ConcurrentLinkedQueue<T>()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    private val created = AtomicLong(0)
    private val acquired = AtomicLong(0)
    private val released = AtomicLong(0)
    private val destroyed = AtomicLong(0)
    private val waitCount = AtomicLong(0)
    private val peakActive = AtomicInteger(0)
    private val currentSize = AtomicInteger(0)
    private val totalAcquireTimeNs = AtomicLong(0)
    private val acquireSamples = AtomicInteger(0)
    private val totalReleaseTimeNs = AtomicLong(0)
    private val releaseSamples = AtomicInteger(0)

    private val validator = Runnable {
        try {
            validateAndCleanup()
        } catch (e: Exception) {
            logger.warn("Validation failed for pool $name", e)
        }
    }

    private val validationThread = Thread(validator, "pool-validator-$name").apply {
        isDaemon = true
        start()
    }

    protected abstract fun create(): T
    protected open fun validate(obj: T): Boolean = true
    protected open fun destroy(obj: T) {}

    fun acquire(timeoutMs: Long = 10000L): T {
        var obj: T? = pool.poll()
        if (obj != null) {
            val start = System.nanoTime()
            if (validate(obj)) {
                active.add(obj)
                acquired.incrementAndGet()
                val elapsed = System.nanoTime() - start
                totalAcquireTimeNs.addAndGet(elapsed)
                acquireSamples.incrementAndGet()
                updatePeakActive()
                return obj
            }
            destroyInternal(obj)
            obj = null
        }

        lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val startTime = System.nanoTime()
            while (obj == null) {
                val current = currentSize.get()
                if (current < maxSize) {
                    currentSize.incrementAndGet()
                    lock.unlock()
                    try {
                        val created = create()
                        this.created.incrementAndGet()
                        obj = created
                    } finally {
                        lock.lock()
                    }
                    break
                }
                if (remainingNanos <= 0) {
                    waitCount.incrementAndGet()
                    throw PoolExhaustedException("Pool $name exhausted: maxSize=$maxSize, active=${active.size}, pool=${pool.size}")
                }
                waitCount.incrementAndGet()
                remainingNanos = notEmpty.awaitNanos(remainingNanos)
                obj = pool.poll()
                if (obj != null) {
                    val elapsed = System.nanoTime() - startTime
                    totalAcquireTimeNs.addAndGet(elapsed)
                    acquireSamples.incrementAndGet()
                }
            }
        }

        if (obj != null) {
            active.add(obj)
            acquired.incrementAndGet()
            updatePeakActive()
        }
        return obj!!
    }

    fun release(obj: T) {
        val start = System.nanoTime()
        active.remove(obj)
        if (currentSize.get() > minSize && pool.size > minSize) {
            destroyInternal(obj)
        } else {
            pool.offer(obj)
            lock.withLock { notEmpty.signal() }
        }
        val elapsed = System.nanoTime() - start
        totalReleaseTimeNs.addAndGet(elapsed)
        releaseSamples.incrementAndGet()
        released.incrementAndGet()
    }

    fun warmUp(count: Int) {
        val toCreate = count.coerceAtMost(maxSize)
        val objects = mutableListOf<T>()
        for (i in 0 until toCreate) {
            try {
                val obj = create()
                objects.add(obj)
                created.incrementAndGet()
                currentSize.incrementAndGet()
            } catch (e: Exception) {
                logger.warn("Warmup failed for pool $name at index $i", e)
                break
            }
        }
        pool.addAll(objects)
        logger.info("Pool $name warmed up with ${objects.size} objects")
    }

    fun getMetrics(): PoolMetrics {
        val avgAcquireMs = if (acquireSamples.get() > 0)
            totalAcquireTimeNs.get().toDouble() / acquireSamples.get() / 1_000_000.0 else 0.0
        val avgReleaseMs = if (releaseSamples.get() > 0)
            totalReleaseTimeNs.get().toDouble() / releaseSamples.get() / 1_000_000.0 else 0.0
        return PoolMetrics(
            created = created.get(),
            acquired = acquired.get(),
            released = released.get(),
            destroyed = destroyed.get(),
            currentSize = currentSize.get(),
            activeCount = active.size,
            peakActiveCount = peakActive.get(),
            waitCount = waitCount.get(),
            averageAcquireTimeMs = avgAcquireMs,
            averageReleaseTimeMs = avgReleaseMs
        )
    }

    fun drain() {
        lock.withLock {
            pool.clear()
            active.clear()
        }
        currentSize.set(0)
    }

    fun shutdown() {
        validationThread.interrupt()
        drain()
    }

    private fun destroyInternal(obj: T) {
        try {
            destroy(obj)
        } catch (e: Exception) {
            logger.warn("Destroy failed for object in pool $name", e)
        }
        destroyed.incrementAndGet()
        currentSize.decrementAndGet()
    }

    private fun updatePeakActive() {
        val current = active.size
        var peak = peakActive.get()
        while (current > peak && !peakActive.compareAndSet(peak, current)) {
            peak = peakActive.get()
        }
    }

    private fun validateAndCleanup() {
        try {
            val now = System.currentTimeMillis()
            val toRemove = mutableListOf<T>()
            for (obj in pool) {
                if (!validate(obj)) {
                    toRemove.add(obj)
                }
            }
            for (obj in toRemove) {
                if (pool.remove(obj)) {
                    destroyInternal(obj)
                }
            }
            while (pool.size > minSize && currentSize.get() > minSize) {
                val oldest = pool.poll() ?: break
                destroyInternal(oldest)
            }
        } catch (e: Exception) {
            logger.warn("Validation cycle failed for pool $name", e)
        }
    }

    class PoolExhaustedException(message: String) : RuntimeException(message)
}

class StringBuilderPool(
    minSize: Int = 8,
    maxSize: Int = 128
) : ObjectPool<StringBuilder>("StringBuilder", minSize, maxSize) {
    override fun create(): StringBuilder = StringBuilder(256)
    override fun validate(obj: StringBuilder): Boolean = true
    override fun destroy(obj: StringBuilder) {
        obj.setLength(0)
    }
}

class ByteArrayPool(
    private val bufferSize: Int = 8192,
    minSize: Int = 4,
    maxSize: Int = 64
) : ObjectPool<ByteArray>("ByteArray-$bufferSize", minSize, maxSize) {
    override fun create(): ByteArray = ByteArray(bufferSize)
    override fun validate(obj: ByteArray): Boolean = obj.size == bufferSize
    override fun destroy(obj: ByteArray) { obj.fill(0) }
}

class IntArrayPool(
    private val arraySize: Int = 256,
    minSize: Int = 4,
    maxSize: Int = 64
) : ObjectPool<IntArray>("IntArray-$arraySize", minSize, maxSize) {
    override fun create(): IntArray = IntArray(arraySize)
    override fun validate(obj: IntArray): Boolean = obj.size == arraySize
    override fun destroy(obj: IntArray) { obj.fill(0) }
}

class MutableListPool<T>(
    private val initialCapacity: Int = 16,
    minSize: Int = 4,
    maxSize: Int = 64
) : ObjectPool<MutableList<T>>("MutableList", minSize, maxSize) {
    override fun create(): MutableList<T> = ArrayList(initialCapacity)
    override fun validate(obj: MutableList<T>): Boolean = true
    override fun destroy(obj: MutableList<T>) { obj.clear() }
}

object PoolRegistry {
    private val pools = ConcurrentLinkedQueue<ObjectPool<*>>()

    fun register(pool: ObjectPool<*>) { pools.add(pool) }

    fun drainAll() { pools.forEach { it.drain() } }

    fun shutdownAll() { pools.forEach { it.shutdown() } }

    fun warmUpAll() { pools.forEach { if (it is StringBuilderPool || it is ByteArrayPool) it.warmUp(8) } }

    fun getAllMetrics(): Map<String, ObjectPool<*>.PoolMetrics> {
        return pools.associate { it.name to it.getMetrics() }
    }
}
