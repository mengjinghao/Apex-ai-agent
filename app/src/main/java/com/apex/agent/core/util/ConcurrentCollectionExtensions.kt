package com.apex.agent.core.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ConcurrentHashSet<E> {
    private val map = ConcurrentHashMap<E, Unit>()
        fun add(element: E): Boolean = map.put(element, Unit) == null
    fun remove(element: E): Boolean = map.remove(element) != null
    fun contains(element: E): Boolean = map.containsKey(element)
        fun size(): Int = map.size
    fun isEmpty(): Boolean = map.isEmpty()
        fun clear() { map.clear() }
        fun toSet(): Set<E> = map.keys.toSet()
        fun addAll(elements: Collection<E>) { elements.forEach { add(it) } }
        fun removeAll(elements: Collection<E>) { elements.forEach { remove(it) } }
        fun retainAll(elements: Collection<E>) {
        val toRemove = map.keys.filter { it !in elements }
        toRemove.forEach { map.remove(it) }
    }
}

class ConcurrentArrayList<E> {
    private val list = CopyOnWriteArrayList<E>()
        private val addCount = AtomicLong(0)
        private val removeCount = AtomicLong(0)
        fun add(element: E) { list.add(element); addCount.incrementAndGet() }
        fun addAll(elements: Collection<E>) { list.addAll(elements); addCount.addAndGet(elements.size.toLong()) }
        fun remove(element: E): Boolean { val r = list.remove(element); if (r) removeCount.incrementAndGet(); return r }
        fun removeAt(index: Int): E { val r = list.removeAt(index); removeCount.incrementAndGet(); return r }
        fun get(index: Int): E = list[index]
    fun size(): Int = list.size
    fun isEmpty(): Boolean = list.isEmpty()
        fun clear() { list.clear() }
        fun toList(): List<E> = list.toList()
        fun firstOrNull(): E? = list.firstOrNull()
        fun lastOrNull(): E? = list.lastOrNull()
}

class ConcurrentBoundedQueue<E>(private val maxCapacity: Int) {
    private val queue = ConcurrentLinkedQueue<E>()
        private val sizeCounter = AtomicInteger(0)
        private val addedCount = AtomicLong(0)
        private val removedCount = AtomicLong(0)
        private val rejectedCount = AtomicLong(0)
        fun offer(element: E): Boolean {
        if (sizeCounter.get() >= maxCapacity) {
            rejectedCount.incrementAndGet()
        return false
        }
        if (queue.offer(element)) {
            sizeCounter.incrementAndGet()
            addedCount.incrementAndGet()
        return true
        }
        return false
    }
        fun poll(): E? {
        val element = queue.poll()
        if (element != null) {
            sizeCounter.decrementAndGet()
            removedCount.incrementAndGet()
        }
        return element
    }
        fun peek(): E? = queue.peek()
        fun size(): Int = sizeCounter.get()
        fun capacity(): Int = maxCapacity
    fun isEmpty(): Boolean = queue.isEmpty()
        fun isFull(): Boolean = sizeCounter.get() >= maxCapacity
    fun clear() { queue.clear(); sizeCounter.set(0) }
        fun drainTo(list: MutableList<E>, maxElements: Int = Int.MAX_VALUE): Int {
        var count = 0
        while (count < maxElements) {
            val element = poll() ?: break
            list.add(element)
            count++
        }
        return count
    }
        fun toList(): List<E> = queue.toList()
}

class ConcurrentRingBuffer<E>(private val capacity: Int) {
    private val buffer = arrayOfNulls<Any>(capacity) as Array<E>
    private val count = AtomicInteger(0)
        private val head = AtomicInteger(0)
        private val tail = AtomicInteger(0)
        fun offer(element: E): Boolean {
        var current: Int
        var next: Int
        do {
            current = count.get()
        if (current >= capacity) return false
            next = current + 1
        } while (!count.compareAndSet(current, next))
        val idx: Int
        var t: Int
        do {
            t = tail.get()
            idx = t % capacity
        } while (!tail.compareAndSet(t, t + 1))

        buffer[idx] = element
        return true
    }
        fun poll(): E? {
        var current: Int
        var next: Int
        do {
            current = count.get()
        if (current <= 0) return null
            next = current - 1
        } while (!count.compareAndSet(current, next))
        val idx: Int
        var h: Int
        do {
            h = head.get()
            idx = h % capacity
        } while (!head.compareAndSet(h, h + 1))
        return buffer[idx]
    }
        fun peek(): E? {
        val idx = head.get() % capacity
        return if (count.get() > 0) buffer[idx] else null
    }
        fun size(): Int = count.get()
        fun isEmpty(): Boolean = count.get() <= 0
    fun isFull(): Boolean = count.get() >= capacity
}

class ConcurrentSlidingWindow(private val windowSizeMs: Long) {
    private val events = ConcurrentLinkedQueue<Long>()
        private val count = AtomicInteger(0)
        fun record() {
        val now = System.currentTimeMillis()
        events.add(now)
        count.incrementAndGet()
        cleanup(now)
    }
        fun record(count: Int) {
        val now = System.currentTimeMillis()
        repeat(count) { events.add(now) }
        this.count.addAndGet(count)
        cleanup(now)
    }
        fun getCount(): Int {
        cleanup(System.currentTimeMillis())
        return count.get()
    }
        fun getRate(): Double {
        val now = System.currentTimeMillis()
        cleanup(now)
        val windowStart = now - windowSizeMs
        val validEvents = events.count { it >= windowStart }
        return validEvents.toDouble() / (windowSizeMs / 1000.0)
    }
        fun reset() {
        events.clear()
        count.set(0)
    }
        private fun cleanup(now: Long) {
        val cutoff = now - windowSizeMs
        while (true) {
            val event = events.peek() ?: break
            if (event < cutoff) {
                events.poll()
                count.decrementAndGet()
            } else break
        }
    }
}

class ConcurrentRateTracker(private val windowSizeMs: Long = 1000L) {
    private val timestamps = ConcurrentLinkedQueue<Long>()
        private val totalCount = AtomicLong(0)
        private val peakRate = AtomicLong(0)
        fun record(count: Int = 1) {
        val now = System.nanoTime()
        repeat(count) { timestamps.add(now) }
        totalCount.addAndGet(count.toLong())
        cleanup()
    }
        fun getCurrentRate(): Double {
        cleanup()
        val cutoff = System.nanoTime() - windowSizeMs * 1_000_000
        val count = timestamps.count { it >= cutoff }
        return count.toDouble() / (windowSizeMs / 1000.0)
    }
        fun getPeakRate(): Double {
        return peakRate.get().toDouble() / (windowSizeMs / 1000.0)
    }
        fun getTotalCount(): Long = totalCount.get()
        private fun cleanup() {
        val cutoff = System.nanoTime() - windowSizeMs * 2 * 1_000_000
        while (true) {
            val ts = timestamps.peek() ?: break
            if (ts < cutoff) { timestamps.poll() } else break
        }
        val current = timestamps.size
        var peak = peakRate.get()
        while (current > peak && !peakRate.compareAndSet(peak, current)) {
            peak = peakRate.get()
        }
    }
}

class ConcurrentReferenceCounter(private val initialValue: Long = 0L) {
    private val value = AtomicLong(initialValue)
        fun increment(): Long = value.incrementAndGet()
        fun decrement(): Long = value.decrementAndGet()
        fun add(delta: Long): Long = value.addAndGet(delta)
        fun get(): Long = value.get()
        fun set(newValue: Long) { value.set(newValue) }
        fun compareAndSet(expected: Long, newValue: Long): Boolean = value.compareAndSet(expected, newValue)
        fun reset() { value.set(0) }
}

class ConcurrentAccumulator(private val name: String = "accumulator") {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
        private val timers = ConcurrentHashMap<String, AtomicLong>()
        private val timerCounts = ConcurrentHashMap<String, AtomicInteger>()
        fun increment(counter: String, delta: Long = 1) {
        counters.computeIfAbsent(counter) { AtomicLong(0) }.addAndGet(delta)
    }
        fun recordTime(timer: String, durationNs: Long) {
        timers.computeIfAbsent(timer) { AtomicLong(0) }.addAndGet(durationNs)
        timerCounts.computeIfAbsent(timer) { AtomicInteger(0) }.incrementAndGet()
    }
        fun getCounter(name: String): Long = counters[name]?.get() ?: 0
    fun getTotalTime(name: String): Long = timers[name]?.get() ?: 0
    fun getAverageTime(name: String): Double {
        val total = timers[name]?.get() ?: return 0.0
        val count = timerCounts[name]?.get() ?: return 0.0
        return if (count > 0) total.toDouble() / count / 1_000_000.0 else 0.0
    }
        fun getAllCounters(): Map<String, Long> = counters.mapValues { it.value.get() }
        fun getAllTimers(): Map<String, Double> = timers.keys.associateWith { getAverageTime(it) }
        fun reset() { counters.clear(); timers.clear(); timerCounts.clear() }
}

class ConcurrentFlag(private val initial: Boolean = false) {
    private val flag = java.util.concurrent.atomic.AtomicBoolean(initial)
        fun set() { flag.set(true) }
        fun clear() { flag.set(false) }
        fun get(): Boolean = flag.get()
        fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean = flag.compareAndSet(expected, newValue)
        fun toggle(): Boolean { val v = !flag.get(); flag.set(v); return v }
}

class ConcurrentVersion(private val initial: Long = 0L) {
    private val version = AtomicLong(initial)
        fun increment(): Long = version.incrementAndGet()
        fun get(): Long = version.get()
        fun compareAndSet(expected: Long, newValue: Long): Boolean = version.compareAndSet(expected, newValue)
        fun isOlderThan(other: Long): Boolean = version.get() < other
    fun isNewerThan(other: Long): Boolean = version.get() > other
}

class ConcurrentThrottle(private val maxRate: Int, private val perSeconds: Int = 1) {
    private val permits = java.util.concurrent.Semaphore(maxRate, true)
        private val lastRefill = AtomicLong(System.nanoTime())
        fun tryAcquire(): Boolean {
        refill()
        return permits.tryAcquire()
    }
        fun acquire() { refill(); permits.acquireUninterruptibly() }
        private fun refill() {
        val now = System.nanoTime()
        val last = lastRefill.get()
        val elapsed = now - last
        if (elapsed >= 1_000_000_000L / perSeconds && lastRefill.compareAndSet(last, now)) {
            val toRelease = (elapsed * maxRate / (1_000_000_000L / perSeconds)).toInt().coerceAtLeast(1)
            permits.release(toRelease.coerceAtMost(maxRate - permits.availablePermits()))
        }
    }
}

class ConcurrentPriorityQueue<E : Comparable<E>> {
    private val heap = mutableListOf<E>()
        private val lock = java.util.concurrent.locks.ReentrantLock()
        fun offer(element: E) {
        lock.lock()
        try {
            heap.add(element)
            siftUp(heap.size - 1)
        } finally { lock.unlock() }
    }
        fun poll(): E? {
        lock.lock()
        try {
            if (heap.isEmpty()) return null
            val result = heap[0]
            val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) { heap[0] = last; siftDown(0) }
        return result
        } finally { lock.unlock() }
    }
        fun peek(): E? = lock.withLock { heap.firstOrNull() }
        val size: Int get() = lock.withLock { heap.size }
        fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = !isEmpty()
        fun clear() { lock.withLock { heap.clear() } }
        private fun siftUp(index: Int) {
        var child = index
        while (child > 0) {
            val parent = (child - 1) / 2
            if (heap[parent] <= heap[child]) break
            swap(child, parent)
            child = parent
        }
    }
        private fun siftDown(index: Int) {
        var parent = index
        val half = heap.size / 2
        while (parent < half) {
            var child = 2 * parent + 1
            if (child + 1 < heap.size && heap[child + 1] < heap[child]) child++
            if (heap[parent] <= heap[child]) break
            swap(parent, child)
            parent = child
        }
    }
        private fun swap(i: Int, j: Int) { val temp = heap[i]; heap[i] = heap[j]; heap[j] = temp }
        fun toSortedList(): List<E> = lock.withLock { heap.sorted() }
        fun toArray(): Array<E> = lock.withLock { @Suppress("UNCHECKED_CAST") heap.toTypedArray() as Array<E> }
        fun remove(element: E): Boolean = lock.withLock { heap.remove(element) }
}

class ConcurrentBatchAccumulator<E>(private val batchSize: Int, private val processor: (List<E>) -> Unit) {
    private val buffer = java.util.concurrent.CopyOnWriteArrayList<E>()
        private val count = AtomicInteger(0)
        private val lock = Any()
        private val batchesProcessed = AtomicLong(0)
        private val itemsProcessed = AtomicLong(0)
        fun add(element: E) {
        buffer.add(element)
        if (count.incrementAndGet() >= batchSize) flush()
    }
        fun addAll(elements: Collection<E>) {
        buffer.addAll(elements)
        count.addAndGet(elements.size)
        if (count.get() >= batchSize) flush()
    }
        fun flush() {
        val batch: List<E>
        synchronized(lock) {
            if (buffer.isEmpty()) return
            batch = ArrayList(buffer)
            buffer.clear()
            count.set(0)
        }
        processor(batch)
        batchesProcessed.incrementAndGet()
        itemsProcessed.addAndGet(batch.size.toLong())
    }
        fun getStats(): Pair<Long, Long> = batchesProcessed.get() to itemsProcessed.get()
}

class ConcurrentCircuitBreaker(private val name: String, private val failureThreshold: Int = 5) {
    private val failures = AtomicInteger(0)
        private val state = java.util.concurrent.atomic.AtomicReference("CLOSED")
        private val lastFailureTime = AtomicLong(0)
        private val openTimeoutMs = 30000L

    fun <T> call(block: () -> T): T? {
        return when (state.get()) {
            "OPEN" -> {
                if (System.currentTimeMillis() - lastFailureTime.get() > openTimeoutMs) {
                    state.compareAndSet("OPEN", "HALF_OPEN")
                } else null
            }
            "HALF_OPEN" -> {
                try {
                    val result = block()
                    state.set("CLOSED"); failures.set(0); result
                } catch (e: Exception) {
                    state.set("OPEN"); lastFailureTime.set(System.currentTimeMillis()); null
                }
            }
            else -> {
                try {
                    block().also { failures.set(0) }
                } catch (e: Exception) {
                    if (failures.incrementAndGet() >= failureThreshold) {
                        state.set("OPEN"); lastFailureTime.set(System.currentTimeMillis())
                    }
                    null
                }
            }
        }
    }
        fun getState(): String = state.get()
        fun getFailureCount(): Int = failures.get()
        fun reset() { state.set("CLOSED"); failures.set(0) }
}

class ConcurrentScoredQueue<E>(private val maxSize: Int = 100) {
    private data class ScoredEntry<E>(val element: E, val score: Double, val id: Long = System.nanoTime())
        private val queue = java.util.concurrent.PriorityBlockingQueue<ScoredEntry<E>>(
        11, compareByDescending<ScoredEntry<E>> { it.score }.thenBy { it.id }
    )
        fun offer(element: E, score: Double): Boolean {
        if (queue.size >= maxSize) {
            val lowest = queue.peek() ?: return queue.offer(ScoredEntry(element, score))
        if (score <= lowest.score) return false
            queue.poll()
        }
        return queue.offer(ScoredEntry(element, score))
    }
        fun poll(): E? = queue.poll()?.element
    fun peek(): E? = queue.peek()?.element
    val size: Int get() = queue.size
    fun isEmpty(): Boolean = queue.isEmpty()
        fun clear() { queue.clear() }
        fun toList(): List<E> = queue.map { it.element }
}

class ConcurrentTokenBucket(private val capacity: Int, private val refillRate: Int, private val refillIntervalMs: Long = 1000L) {
    private val tokens = AtomicLong(capacity.toLong())
        private val lastRefillTime = AtomicLong(System.currentTimeMillis())
        private val totalTokensConsumed = AtomicLong(0)
        private val totalTokensRefilled = AtomicLong(0)
        fun tryConsume(count: Int = 1): Boolean {
        refill()
        while (true) {
            val current = tokens.get()
        if (current < count) return false
            if (tokens.compareAndSet(current, current - count)) {
                totalTokensConsumed.addAndGet(count.toLong())
        return true
            }
        }
    }
        fun getAvailableTokens(): Long = tokens.get()
        fun getTotalConsumed(): Long = totalTokensConsumed.get()
        fun getTotalRefilled(): Long = totalTokensRefilled.get()
        private fun refill() {
        val now = System.currentTimeMillis()
        val last = lastRefillTime.get()
        val elapsed = now - last
        if (elapsed >= refillIntervalMs && lastRefillTime.compareAndSet(last, now)) {
            val toAdd = (elapsed / refillIntervalMs * refillRate).toLong().coerceAtLeast(1)
        val newValue = (tokens.get() + toAdd).coerceAtMost(capacity.toLong())
            tokens.set(newValue)
            totalTokensRefilled.addAndGet(toAdd)
        }
    }
}
