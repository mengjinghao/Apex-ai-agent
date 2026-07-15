package com.apex.agent.core.performance

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CircuitBreaker private constructor(
    private val name: String,
    private val failureThreshold: Int = 5,
    private val successThreshold: Int = 3,
    private val halfOpenMaxCalls: Int = 1,
    private val openTimeoutMs: Long = 30000L,
    private val halfOpenTimeoutMs: Long = 5000L,
    private val rollingWindowMs: Long = 60000L,
    private val excludedExceptions: Set<Class<out Throwable>> = emptySet()
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    data class CircuitBreakerMetrics(
        val state: State,
        val failureCount: Int,
        val successCount: Int,
        val totalCalls: Long,
        val totalFailures: Long,
        val totalSuccesses: Long,
        val totalTimeouts: Long,
        val totalRejected: Long,
        val lastFailureTimeMs: Long,
        val lastSuccessTimeMs: Long,
        val openCount: Long,
        val halfOpenCount: Long
    )
        private val logger = LoggerFactory.getLogger("CircuitBreaker-$name")
        private val state = AtomicReference(State.CLOSED)
        private val failureCount = AtomicInteger(0)
        private val successCount = AtomicInteger(0)
        private val totalCalls = AtomicLong(0)
        private val totalFailures = AtomicLong(0)
        private val totalSuccesses = AtomicLong(0)
        private val totalTimeouts = AtomicLong(0)
        private val totalRejected = AtomicLong(0)
        private val openCount = AtomicLong(0)
        private val halfOpenCount = AtomicLong(0)
        private val lastFailureTime = AtomicLong(0)
        private val lastSuccessTime = AtomicLong(0)
        private val lastStateChangeTime = AtomicLong(System.currentTimeMillis())
        private val failureTimestamps = ConcurrentLinkedQueue<Long>()
        private val listeners = ConcurrentLinkedQueue<(State, State) -> Unit>()
        fun <T> protect(block: () -> T): T {
        totalCalls.incrementAndGet()
        when (state.get()) {
            State.OPEN -> {
                val elapsed = System.currentTimeMillis() - lastStateChangeTime.get()
        if (elapsed >= openTimeoutMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenCount.incrementAndGet()
                        lastStateChangeTime.set(System.currentTimeMillis())
                        logger.info("Circuit $name transitioned OPEN -> HALF_OPEN")
                        listeners.forEach { it(State.OPEN, State.HALF_OPEN) }
                    }
                } else {
                    totalRejected.incrementAndGet()
        throw CircuitBreakerOpenException("Circuit $name is OPEN (elapsed=${elapsed}ms, timeout=${openTimeoutMs}ms)")
                }
            }
            State.HALF_OPEN -> {
                if (totalCalls.get() % (halfOpenMaxCalls + 1) != 0L) {
                    totalRejected.incrementAndGet()
        throw CircuitBreakerOpenException("Circuit $name is HALF_OPEN, limited calls allowed")
                }
            }
            State.CLOSED -> {}
        }
        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            if (excludedExceptions.none { it.isInstance(e) }) {
                onFailure(e)
            }
        throw e
        }
    }

    suspend fun <T> protectSuspend(block: suspend () -> T): T {
        totalCalls.incrementAndGet()
        when (state.get()) {
            State.OPEN -> {
                val elapsed = System.currentTimeMillis() - lastStateChangeTime.get()
        if (elapsed >= openTimeoutMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenCount.incrementAndGet()
                        lastStateChangeTime.set(System.currentTimeMillis())
                        logger.info("Circuit $name transitioned OPEN -> HALF_OPEN")
                        listeners.forEach { it(State.OPEN, State.HALF_OPEN) }
                    }
                } else {
                    totalRejected.incrementAndGet()
        throw CircuitBreakerOpenException("Circuit $name is OPEN")
                }
            }
            State.HALF_OPEN -> {
                if (totalCalls.get() % (halfOpenMaxCalls + 1) != 0L) {
                    totalRejected.incrementAndGet()
        throw CircuitBreakerOpenException("Circuit $name is HALF_OPEN")
                }
            }
            State.CLOSED -> {}
        }
        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            if (excludedExceptions.none { it.isInstance(e) }) {
                onFailure(e)
            }
        throw e
        }
    }
        fun <T> protectWithFallback(block: () -> T, fallback: (Exception) -> T): T {
        return try {
            protect(block)
        } catch (e: Exception) {
            if (e is CircuitBreakerOpenException) {
                fallback(e)
            } else {
                throw e
            }
        }
    }

    suspend fun <T> protectWithFallbackSuspend(
        block: suspend () -> T,
        fallback: suspend (Exception) -> T
    ): T {
        return try {
            protectSuspend(block)
        } catch (e: Exception) {
            if (e is CircuitBreakerOpenException) {
                fallback(e)
            } else {
                throw e
            }
        }
    }
        fun getMetrics(): CircuitBreakerMetrics {
        return CircuitBreakerMetrics(
            state = state.get(),
            failureCount = failureCount.get(),
            successCount = successCount.get(),
            totalCalls = totalCalls.get(),
            totalFailures = totalFailures.get(),
            totalSuccesses = totalSuccesses.get(),
            totalTimeouts = totalTimeouts.get(),
            totalRejected = totalRejected.get(),
            lastFailureTimeMs = lastFailureTime.get(),
            lastSuccessTimeMs = lastSuccessTime.get(),
            openCount = openCount.get(),
            halfOpenCount = halfOpenCount.get()
        )
    }
        fun addListener(listener: (State, State) -> Unit) {
        listeners.add(listener)
    }
        fun reset() {
        state.set(State.CLOSED)
        failureCount.set(0)
        successCount.set(0)
        failureTimestamps.clear()
        lastStateChangeTime.set(System.currentTimeMillis())
        logger.info("Circuit $name manually reset to CLOSED")
    }
        fun forceOpen() {
        state.set(State.OPEN)
        lastStateChangeTime.set(System.currentTimeMillis())
        logger.info("Circuit $name manually forced to OPEN")
    }
        private fun onSuccess() {
        totalSuccesses.incrementAndGet()
        lastSuccessTime.set(System.currentTimeMillis())
        when (state.get()) {
            State.HALF_OPEN -> {
                val current = successCount.incrementAndGet()
        if (current >= successThreshold) {
                    if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                        failureCount.set(0)
                        successCount.set(0)
                        failureTimestamps.clear()
                        lastStateChangeTime.set(System.currentTimeMillis())
                        logger.info("Circuit $name transitioned HALF_OPEN -> CLOSED")
                        listeners.forEach { it(State.HALF_OPEN, State.CLOSED) }
                    }
                }
            }
            State.CLOSED -> {
                successCount.incrementAndGet()
            }
            State.OPEN -> {}
        }
    }
        private fun onFailure(e: Exception) {
        totalFailures.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        failureTimestamps.add(System.currentTimeMillis())
        if (e is kotlinx.coroutines.TimeoutCancellationException) {
            totalTimeouts.incrementAndGet()
        }
        val now = System.currentTimeMillis()
        val windowStart = now - rollingWindowMs
        while (failureTimestamps.peek() != null && failureTimestamps.peek() < windowStart) {
            failureTimestamps.poll()
        }
        when (state.get()) {
            State.CLOSED -> {
                if (failureTimestamps.size >= failureThreshold) {
                    if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                        openCount.incrementAndGet()
                        failureCount.set(0)
                        successCount.set(0)
                        lastStateChangeTime.set(System.currentTimeMillis())
                        logger.warn("Circuit $name transitioned CLOSED -> OPEN ({}/{} failures in window)",
                            failureTimestamps.size, failureThreshold)
                        listeners.forEach { it(State.CLOSED, State.OPEN) }
                    }
                }
            }
            State.HALF_OPEN -> {
                if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                    openCount.incrementAndGet()
                    failureCount.set(0)
                    successCount.set(0)
                    lastStateChangeTime.set(System.currentTimeMillis())
                    logger.warn("Circuit $name transitioned HALF_OPEN -> OPEN")
                    listeners.forEach { it(State.HALF_OPEN, State.OPEN) }
                }
            }
            State.OPEN -> {}
        }
    }
        class CircuitBreakerOpenException(message: String) : RuntimeException(message)

    companion object {
        fun builder(name: String): Builder = Builder(name)
        class Builder(val name: String) {
            private var failureThreshold: Int = 5
            private var successThreshold: Int = 3
            private var halfOpenMaxCalls: Int = 1
            private var openTimeoutMs: Long = 30000L
            private var halfOpenTimeoutMs: Long = 5000L
            private var rollingWindowMs: Long = 60000L
            private var excludedExceptions: Set<Class<out Throwable>> = emptySet()
        fun failureThreshold(count: Int) = apply { this.failureThreshold = count }
        fun successThreshold(count: Int) = apply { this.successThreshold = count }
        fun halfOpenMaxCalls(count: Int) = apply { this.halfOpenMaxCalls = count }
        fun openTimeout(ms: Long) = apply { this.openTimeoutMs = ms }
        fun halfOpenTimeout(ms: Long) = apply { this.halfOpenTimeoutMs = ms }
        fun rollingWindow(ms: Long) = apply { this.rollingWindowMs = ms }
        fun excludeExceptions(exceptions: Set<Class<out Throwable>>) = apply { this.excludedExceptions = exceptions }
        fun build(): CircuitBreaker = CircuitBreaker(
                name, failureThreshold, successThreshold, halfOpenMaxCalls,
                openTimeoutMs, halfOpenTimeoutMs, rollingWindowMs, excludedExceptions
            )
        }
        private val registry = ConcurrentHashMap<String, CircuitBreaker>()
        fun getOrCreate(name: String, config: Builder.() -> Unit = {}): CircuitBreaker {
            return registry.getOrPut(name) {
                val builder = builder(name)
                builder.config()
                builder.build()
            }
        }
        fun getAllMetrics(): Map<String, CircuitBreakerMetrics> {
            return registry.mapValues { it.value.getMetrics() }
        }
        fun resetAll() { registry.values.forEach { it.reset() } }
    }
}

class RateLimiter(
    private val name: String,
    private val maxRate: Int = 100,
    private val timeWindowMs: Long = 1000L,
    private val maxBurst: Int = 200
) {
    data class RateLimiterMetrics(
        val totalRequests: Long,
        val totalAllowed: Long,
        val totalRejected: Long,
        val currentRate: Double,
        val maxRate: Int,
        val peakRate: Double
    )
        private val logger = LoggerFactory.getLogger("RateLimiter-$name")
        private val timestamps = ConcurrentLinkedQueue<Long>()
        private val totalRequests = AtomicLong(0)
        private val totalAllowed = AtomicLong(0)
        private val totalRejected = AtomicLong(0)
        private val peakRate = AtomicLong(0)
        fun tryAcquire(): Boolean {
        totalRequests.incrementAndGet()
        val now = System.currentTimeMillis()
        val windowStart = now - timeWindowMs

        while (timestamps.peek() != null && timestamps.peek() < windowStart) {
            timestamps.poll()
        }
        if (timestamps.size < maxRate) {
            timestamps.add(now)
            totalAllowed.incrementAndGet()
        val current = timestamps.size
            var peak = peakRate.get()
            while (current > peak && !peakRate.compareAndSet(peak, current)) {
                peak = peakRate.get()
            }
        return true
        }
        if (timestamps.size < maxBurst) {
            timestamps.add(now)
            totalAllowed.incrementAndGet()
        return true
        }

        totalRejected.incrementAndGet()
        return false
    }
        fun acquire() {
        while (!tryAcquire()) {
            Thread.sleep(timeWindowMs / maxRate)
        }
    }

    suspend fun acquireSuspend() {
        while (!tryAcquire()) {
            kotlinx.coroutines.delay(timeWindowMs / maxRate)
        }
    }
        fun <T> limit(block: () -> T): T {
        if (!tryAcquire()) {
            throw RateLimitExceededException("Rate limit exceeded for $name: $maxRate req/$timeWindowMs ms")
        }
        return block()
    }

    suspend fun <T> limitSuspend(block: suspend () -> T): T {
        if (!tryAcquire()) {
            throw RateLimitExceededException("Rate limit exceeded for $name")
        }
        return block()
    }
        fun getMetrics(): RateLimiterMetrics {
        val now = System.currentTimeMillis()
        val windowStart = now - timeWindowMs
        while (timestamps.peek() != null && timestamps.peek() < windowStart) {
            timestamps.poll()
        }
        return RateLimiterMetrics(
            totalRequests = totalRequests.get(),
            totalAllowed = totalAllowed.get(),
            totalRejected = totalRejected.get(),
            currentRate = timestamps.size.toDouble() / (timeWindowMs / 1000.0),
            maxRate = maxRate,
            peakRate = peakRate.get().toDouble() / (timeWindowMs / 1000.0)
        )
    }
        class RateLimitExceededException(message: String) : RuntimeException(message)

    companion object {
        private val registry = ConcurrentHashMap<String, RateLimiter>()
        fun getOrCreate(name: String, maxRate: Int = 100, windowMs: Long = 1000L): RateLimiter {
            return registry.getOrPut(name) { RateLimiter(name, maxRate, windowMs) }
        }
        fun getAllMetrics(): Map<String, RateLimiterMetrics> {
            return registry.mapValues { it.value.getMetrics() }
        }
    }
}

class ConcurrencyLimiter(
    private val name: String,
    private val maxConcurrency: Int = 10
) {
    private val semaphore = java.util.concurrent.Semaphore(maxConcurrency, true)
        private val activeCount = AtomicInteger(0)
        private val peakActive = AtomicInteger(0)
        private val totalQueued = AtomicLong(0)
        private val totalProcessed = AtomicLong(0)
        private val totalRejected = AtomicLong(0)
        private val waitTimeNs = AtomicLong(0)
        fun <T> call(block: () -> T): T {
        val start = System.nanoTime()
        if (!semaphore.tryAcquire()) {
            totalRejected.incrementAndGet()
        throw ConcurrencyLimitExceededException("Concurrency limit exceeded for $name: $maxConcurrency")
        }
        try {
            val active = activeCount.incrementAndGet()
            updatePeak(active)
            totalQueued.incrementAndGet()
            waitTimeNs.addAndGet(System.nanoTime() - start)
        return block()
        } finally {
            activeCount.decrementAndGet()
            semaphore.release()
            totalProcessed.incrementAndGet()
        }
    }
        fun <T> callWithQueue(block: () -> T): T {
        val start = System.nanoTime()
        semaphore.acquireUninterruptibly()
        try {
            val active = activeCount.incrementAndGet()
            updatePeak(active)
            totalQueued.incrementAndGet()
            waitTimeNs.addAndGet(System.nanoTime() - start)
        return block()
        } finally {
            activeCount.decrementAndGet()
            semaphore.release()
            totalProcessed.incrementAndGet()
        }
    }

    suspend fun <T> callSuspend(block: suspend () -> T): T {
        if (!semaphore.tryAcquire()) {
            totalRejected.incrementAndGet()
        throw ConcurrencyLimitExceededException("Concurrency limit exceeded for $name")
        }
        try {
            activeCount.incrementAndGet()
            totalQueued.incrementAndGet()
        return block()
        } finally {
            activeCount.decrementAndGet()
            semaphore.release()
            totalProcessed.incrementAndGet()
        }
    }
        fun getActiveCount(): Int = activeCount.get()
        fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "maxConcurrency" to maxConcurrency,
        "active" to activeCount.get(),
        "peakActive" to peakActive.get(),
        "totalQueued" to totalQueued.get(),
        "totalProcessed" to totalProcessed.get(),
        "totalRejected" to totalRejected.get()
    )
        private fun updatePeak(current: Int) {
        var peak = peakActive.get()
        while (current > peak && !peakActive.compareAndSet(peak, current)) {
            peak = peakActive.get()
        }
    }
        class ConcurrencyLimitExceededException(message: String) : RuntimeException(message)
}

class TimeoutHandler(
    private val defaultTimeoutMs: Long = 5000L
) {
    fun <T> withTimeout(timeoutMs: Long = defaultTimeoutMs, block: () -> T): T {
        val future = java.util.concurrent.Executors.newSingleThreadExecutor().submit(Callable { block() })
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
        throw TimeoutException("Operation timed out after ${timeoutMs}ms")
        } finally {
            future.cancel(true)
        }
    }
        class TimeoutException(message: String) : RuntimeException(message)
}

class RetryHandler(
    private val name: String = "retry",
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 100L,
    private val maxDelayMs: Long = 5000L,
    private val backoffMultiplier: Double = 2.0,
    private val retryableExceptions: Set<Class<out Throwable>> = setOf(Exception::class.java)
) {
    private val logger = LoggerFactory.getLogger("RetryHandler-$name")
        private val totalRetries = AtomicLong(0)
        private val totalSuccesses = AtomicLong(0)
        private val totalFailures = AtomicLong(0)

    data class RetryMetrics(
        val totalRetries: Long,
        val totalSuccesses: Long,
        val totalFailures: Long,
        val currentRetryCount: Long
    )
        fun <T> execute(block: () -> T): T {
        var lastException: Exception? = null
        var delay = baseDelayMs

        repeat(maxRetries + 1) { attempt ->
            try {
                val result = block()
        if (attempt > 0) totalRetries.incrementAndGet()
                totalSuccesses.incrementAndGet()
        return result
            } catch (e: Exception) {
                lastException = e
                if (!retryableExceptions.any { it.isInstance(e) } || attempt == maxRetries) {
                    totalFailures.incrementAndGet()
        throw e
                }
                logger.debug("Retry {}/{} for $name failed: {}", attempt + 1, maxRetries, e.message)
                Thread.sleep(delay.coerceAtMost(maxDelayMs))
                delay = (delay * backoffMultiplier).toLong().coerceAtMost(maxDelayMs)
            }
        }
        totalFailures.incrementAndGet()
        throw lastException ?: RuntimeException("Retry failed")
    }
        fun getMetrics(): RetryMetrics = RetryMetrics(
        totalRetries = totalRetries.get(),
        totalSuccesses = totalSuccesses.get(),
        totalFailures = totalFailures.get(),
        currentRetryCount = totalRetries.get()
    )
}
