package com.apex.agent.core.concurrent

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

class StripedLock(
    private val stripes: Int = 16,
    private val useReadWrite: Boolean = false
) {
    private val locks: List<Lock> = if (useReadWrite) {
        List(stripes) { ReentrantReadWriteLock().readLock() }
    } else {
        List(stripes) { ReentrantLock() }
    }

    private val rwLocks: List<ReentrantReadWriteLock>? = if (useReadWrite) {
        List(stripes) { ReentrantReadWriteLock() }
    } else null

    fun getLock(key: String): Lock = locks[key.hashCode().let { (it and Int.MAX_VALUE) % stripes }]

    fun getLock(key: Long): Lock = locks[(key % stripes).toInt()]

    fun getLock(key: Int): Lock = locks[(key and Int.MAX_VALUE) % stripes]

    fun <T> withLock(key: String, action: () -> T): T {
        val lock = getLock(key)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withLock(key: Long, action: () -> T): T {
        val lock = getLock(key)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withReadLock(key: String, action: () -> T): T {
        val rw = rwLocks ?: throw UnsupportedOperationException("ReadWrite locks not enabled")
        val idx = key.hashCode().let { (it and Int.MAX_VALUE) % stripes }
        val readLock = rw[idx].readLock()
        readLock.lock()
        try {
            return action()
        } finally {
            readLock.unlock()
        }
    }

    fun <T> withWriteLock(key: String, action: () -> T): T {
        val rw = rwLocks ?: throw UnsupportedOperationException("ReadWrite locks not enabled")
        val idx = key.hashCode().let { (it and Int.MAX_VALUE) % stripes }
        val writeLock = rw[idx].writeLock()
        writeLock.lock()
        try {
            return action()
        } finally {
            writeLock.unlock()
        }
    }
}

class ReadWriteStripedLock(
    private val stripes: Int = 16
) {
    private val locks = List(stripes) { ReentrantReadWriteLock() }

    fun readLock(key: String) = locks[key.hashCode().let { (it and Int.MAX_VALUE) % stripes }].readLock()

    fun writeLock(key: String) = locks[key.hashCode().let { (it and Int.MAX_VALUE) % stripes }].writeLock()

    fun readLock(key: Long) = locks[(key % stripes).toInt()].readLock()

    fun writeLock(key: Long) = locks[(key % stripes).toInt()].writeLock()

    fun <T> withReadLock(key: String, action: () -> T): T {
        val lock = readLock(key)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withWriteLock(key: String, action: () -> T): T {
        val lock = writeLock(key)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withReadLock(key: Long, action: () -> T): T {
        val lock = readLock(key)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withWriteLock(key: Long, action: () -> T): T {
        val lock = writeLock(key)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}

class LockFreeSequence(private val start: Long = 0L) {
    private val value = java.util.concurrent.atomic.AtomicLong(start)
    fun next(): Long = value.getAndIncrement()
    fun current(): Long = value.get()
    fun reset() { value.set(start) }
}

class LockFreeCounter(private val initial: Long = 0L) {
    private val value = java.util.concurrent.atomic.AtomicLong(initial)
    fun increment(): Long = value.incrementAndGet()
    fun decrement(): Long = value.decrementAndGet()
    fun add(delta: Long): Long = value.addAndGet(delta)
    fun get(): Long = value.get()
    fun set(newValue: Long) { value.set(newValue) }
    fun reset() { value.set(0) }
}

class LockFreeReference<T>(initial: T) {
    private val ref = java.util.concurrent.atomic.AtomicReference(initial)
    fun get(): T = ref.get()
    fun set(value: T) { ref.set(value) }
    fun compareAndSet(expected: T, newValue: T): Boolean = ref.compareAndSet(expected, newValue)
    fun getAndSet(value: T): T = ref.getAndSet(value)
}

class CacheLinePaddedLong {
    @Volatile
    private var v: Long = 0L
    private var p1 = 0L
    private var p2 = 0L
    private var p3 = 0L
    private var p4 = 0L
    private var p5 = 0L
    private var p6 = 0L

    fun get(): Long = v
    fun set(value: Long) { v = value }
    fun increment(): Long { v++; return v }
    fun add(delta: Long): Long { v += delta; return v }
}
