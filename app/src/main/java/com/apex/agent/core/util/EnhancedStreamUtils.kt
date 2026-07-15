package com.apex.agent.core.util

import org.slf4j.LoggerFactory
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class EnhancedStreamUtils {
    companion object {
        private const val DEFAULT_BUFFER_SIZE = 65536
        private const val MAX_POOL_SIZE = 64

        fun fastCopy(input: InputStream, output: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
            val buffer = ByteArray(bufferSize)
        var total = 0L
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
        return total
        }
        fun fastCopyChannel(input: ReadableByteChannel, output: WritableByteChannel, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
            val buffer = ByteBuffer.allocateDirect(bufferSize)
        var total = 0L
            while (input.read(buffer) != -1) {
                buffer.flip()
                output.write(buffer)
                buffer.compact()
                total += buffer.position()
            }
        return total
        }
        fun fastFileCopy(source: Path, target: Path, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
            return FileChannel.open(source, StandardOpenOption.READ).use { input ->
                FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { output ->
                    fastCopyChannel(input, output, bufferSize)
                }
            }
        }
        fun bufferedReader(path: Path, charset: Charset = Charsets.UTF_8, bufferSize: Int = DEFAULT_BUFFER_SIZE): BufferedReader {
            return Files.newBufferedReader(path, charset).also {
                if (it is BufferedReader) it else BufferedReader(it, bufferSize)
            }
        }
        fun bufferedWriter(path: Path, charset: Charset = Charsets.UTF_8, bufferSize: Int = DEFAULT_BUFFER_SIZE): BufferedWriter {
            return Files.newBufferedWriter(path, charset).also {
                if (it is BufferedWriter) it else BufferedWriter(it, bufferSize)
            }
        }
        fun readLines(path: Path, charset: Charset = Charsets.UTF_8): List<String> {
            return Files.readAllLines(path, charset)
        }
        fun writeLines(path: Path, lines: Iterable<CharSequence>, charset: Charset = Charsets.UTF_8) {
            Files.write(path, lines, charset)
        }
        fun compressGzip(data: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream(data.size)
            GZIPOutputStream(bos, 8192).use { it.write(data) }
        return bos.toByteArray()
        }
        fun decompressGzip(data: ByteArray): ByteArray {
            return GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }
        fun createTempFile(prefix: String, suffix: String = ".tmp"): Path {
            return Files.createTempFile(prefix, suffix)
        }
        fun deleteRecursively(path: Path) {
            if (Files.isDirectory(path)) {
                Files.walk(path).sorted(Comparator.reverseOrder()).forEach {
                    try { Files.deleteIfExists(it) } catch (e: IOException) {}
                }
            } else {
                Files.deleteIfExists(path)
            }
        }
        fun getFileSize(path: Path): Long {
            return try { Files.size(path) } catch (e: IOException) { -1L }
        }
        fun ensureDirectoryExists(path: Path): Path {
            return Files.createDirectories(path)
        }
    }
}

class StreamingBuffer(private val initialCapacity: Int = 8192) {
    private var buffer = ByteArray(initialCapacity)
        private var writePosition = 0
    private var readPosition = 0

    val availableForRead: Int get() = writePosition - readPosition
    val availableForWrite: Int get() = buffer.size - writePosition
    val capacity: Int get() = buffer.size
    val position: Int get() = writePosition

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        ensureCapacity(length)
        data.copyInto(buffer, writePosition, offset, offset + length)
        writePosition += length
    }
        fun write(byte: Int) {
        ensureCapacity(1)
        buffer[writePosition++] = byte.toByte()
    }
        fun read(target: ByteArray, offset: Int = 0, length: Int = target.size - offset): Int {
        val readable = availableForRead.coerceAtMost(length)
        if (readable <= 0) return -1
        buffer.copyInto(target, offset, readPosition, readPosition + readable)
        readPosition += readable
        if (readPosition == writePosition) {
            readPosition = 0
            writePosition = 0
        }
        return readable
    }
        fun readByte(): Int {
        if (availableForRead <= 0) return -1
        return buffer[readPosition++].toInt() and 0xFF
    }
        fun peek(): Int {
        if (availableForRead <= 0) return -1
        return buffer[readPosition].toInt() and 0xFF
    }
        fun skip(n: Int) {
        readPosition = (readPosition + n).coerceAtMost(writePosition)
    }
        fun reset() {
        readPosition = 0
        writePosition = 0
    }
        fun compact() {
        if (readPosition > 0) {
            val remaining = availableForRead
            buffer.copyInto(buffer, 0, readPosition, writePosition)
            readPosition = 0
            writePosition = remaining
        }
    }
        fun toByteArray(): ByteArray {
        return buffer.copyOfRange(readPosition, writePosition)
    }
        fun inputStream(): InputStream = object : InputStream() {
        override fun read(): Int = this@StreamingBuffer.readByte()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return this@StreamingBuffer.read(b, off, len)
        }
        override fun available(): Int = availableForRead
    }
        private fun ensureCapacity(needed: Int) {
        if (availableForWrite < needed) {
            compact()
        if (availableForWrite < needed) {
                val newSize = maxOf(buffer.size * 2, buffer.size + needed + 4096)
                buffer = buffer.copyOf(newSize)
            }
        }
    }
}

class RingBuffer<T>(private val capacity: Int) {
    private val buffer = arrayOfNulls<Any>(capacity) as Array<T>
    private var head = 0
    private var tail = 0
    private var count = 0

    fun offer(element: T): Boolean {
        if (count >= capacity) return false
        buffer[tail] = element
        tail = (tail + 1) % capacity
        count++
        return true
    }
        fun poll(): T? {
        if (count <= 0) return null
        val element = buffer[head]
        head = (head + 1) % capacity
        count--
        return element
    }
        fun peek(): T? = if (count > 0) buffer[head] else null

    fun size(): Int = count
    fun isEmpty(): Boolean = count == 0
    fun isFull(): Boolean = count >= capacity
    fun capacity(): Int = capacity

    fun clear() {
        head = 0
        tail = 0
        count = 0
    }
        fun toList(): List<T> {
        val result = mutableListOf<T>()
        var idx = head
        repeat(count) {
            result.add(buffer[idx])
            idx = (idx + 1) % capacity
        }
        return result
    }
        fun drainTo(list: MutableList<T>, maxCount: Int = capacity) {
        val drainCount = minOf(count, maxCount)
        repeat(drainCount) {
            poll()?.let { list.add(it) }
        }
    }
}

class BatchBuffer<T>(private val batchSize: Int, private val processor: (List<T>) -> Unit) {
    private val buffer = mutableListOf<T>()
        private val lock = Any()
        private val totalBatches = AtomicInteger(0)
        private val totalItems = AtomicLong(0)
        fun add(item: T) {
        val shouldFlush: Boolean
        synchronized(lock) {
            buffer.add(item)
            shouldFlush = buffer.size >= batchSize
        }
        if (shouldFlush) flush()
    }
        fun addAll(items: Collection<T>) {
        val chunks = items.chunked(batchSize)
        for (chunk in chunks) {
            synchronized(lock) {
                buffer.addAll(chunk)
            }
        if (buffer.size >= batchSize) flush()
        }
    }
        fun flush() {
        val batch: List<T>
        synchronized(lock) {
            batch = buffer.toList()
            buffer.clear()
        }
        if (batch.isNotEmpty()) {
            processor(batch)
            totalBatches.incrementAndGet()
            totalItems.addAndGet(batch.size.toLong())
        }
    }
        fun getStats(): Pair<Int, Long> = totalBatches.get() to totalItems.get()
}

class ObjectSerializer {
    companion object {
        fun <T> serialize(obj: T): ByteArray {
            ByteArrayOutputStream().use { bos ->
                ObjectOutputStream(bos).use { oos ->
                    oos.writeObject(obj)
                }
        return bos.toByteArray()
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> deserialize(data: ByteArray): T {
            ByteArrayInputStream(data).use { bis ->
                ObjectInputStream(bis).use { ois ->
                    return ois.readObject() as T
                }
            }
        }
        fun serializeToFile(obj: Any, path: Path) {
            ObjectOutputStream(Files.newOutputStream(path)).use { it.writeObject(obj) }
        }
        fun deserializeFromFile(path: Path): Any {
            return ObjectInputStream(Files.newInputStream(path)).use { it.readObject() }
        }
    }
}

class MemoryMappedFile(private val path: Path, private val mode: FileChannel.MapMode = FileChannel.MapMode.READ_ONLY) {
    private val channel: FileChannel = FileChannel.open(path, StandardOpenOption.READ)
        private val mappedBuffer: ByteBuffer = channel.map(mode, 0, channel.size())
        private val totalBytes = AtomicLong(channel.size())
        private val readCount = AtomicLong(0)
        fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        mappedBuffer.position(position.toInt())
        mappedBuffer.get(buffer, 0, length)
        readCount.incrementAndGet()
        return buffer
    }
        fun readString(position: Long, length: Int, charset: Charset = Charsets.UTF_8): String {
        return String(read(position, length), charset)
    }
        fun readLine(position: Long): Pair<String, Long> {
        val sb = StringBuilder()
        var pos = position
        while (pos < totalBytes.get()) {
            val b = read(pos, 1)
        if (b[0] == '\n'.code.toByte()) break
            if (b[0] != '\r'.code.toByte()) sb.append(b[0].toInt().toChar())
            pos++
        }
        return sb.toString() to (pos + 1)
    }
        fun readAll(): ByteArray {
        val buffer = ByteArray(totalBytes.get().toInt())
        mappedBuffer.position(0)
        mappedBuffer.get(buffer)
        return buffer
    }
        fun size(): Long = totalBytes.get()
        fun close() {
        channel.close()
    }
        fun getMetrics(): Map<String, Any> = mapOf(
        "path" to path.toString(),
        "size" to totalBytes.get(),
        "reads" to readCount.get()
    )
}

class ConcurrentStreamAccumulator<T> {
    private val accumulated = ConcurrentHashMap.newKeySet<T>()
        private val totalAdded = AtomicLong(0)
        fun add(element: T): Boolean {
        val added = accumulated.add(element)
        if (added) totalAdded.incrementAndGet()
        return added
    }
        fun addAll(elements: Collection<T>): Int {
        var count = 0
        for (e in elements) {
            if (add(e)) count++
        }
        return count
    }
        fun remove(element: T): Boolean = accumulated.remove(element)
        fun contains(element: T): Boolean = accumulated.contains(element)
        fun size(): Int = accumulated.size
    fun clear() { accumulated.clear() }
        fun snapshot(): Set<T> = accumulated.toSet()
        fun drainTo(set: MutableSet<T>) {
        set.addAll(accumulated)
        accumulated.clear()
    }
        fun getStats(): Map<String, Any> = mapOf(
        "size" to size(),
        "totalAdded" to totalAdded.get()
    )
}

class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    private val count = AtomicLong(0)

    override fun write(b: Int) {
        delegate.write(b)
        count.incrementAndGet()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count.addAndGet(len.toLong())
    }

    override fun flush() { delegate.flush() }
    override fun close() { delegate.close() }
        fun getByteCount(): Long = count.get()
}

class BoundedInputStream(private val delegate: InputStream, private val maxBytes: Long) : InputStream() {
    private val bytesRead = AtomicLong(0)

    override fun read(): Int {
        if (bytesRead.get() >= maxBytes) return -1
        val b = delegate.read()
        if (b != -1) bytesRead.incrementAndGet()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead.get() >= maxBytes) return -1
        val maxLen = (maxBytes - bytesRead.get()).toInt().coerceAtMost(len)
        val read = delegate.read(b, off, maxLen)
        if (read > 0) bytesRead.addAndGet(read.toLong())
        return read
    }

    override fun available(): Int = delegate.available()
    override fun close() { delegate.close() }
}

class TeeInputStream(private val a: InputStream, private val b: OutputStream) : InputStream() {
    private val totalCopied = AtomicLong(0)

    override fun read(): Int {
        val byte = a.read()
        if (byte != -1) {
            b.write(byte)
            totalCopied.incrementAndGet()
        }
        return byte
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val read = a.read(buf, off, len)
        if (read > 0) {
            b.write(buf, off, read)
            totalCopied.addAndGet(read.toLong())
        }
        return read
    }

    override fun close() {
        a.close()
        b.close()
    }
        fun getBytesCopied(): Long = totalCopied.get()
}

class BlockingBuffer(private val capacity: Int = 1024) {
    private val buffer = ByteArray(capacity)
        private var writePos = 0
    private var readPos = 0
    private val lock = Object()
        private val notEmpty = lock.newCondition()
        private val notFull = lock.newCondition()
        private var closed = false

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        synchronized(lock) {
            var written = 0
            while (written < length) {
                while (availableForWrite == 0 && !closed) {
                    notFull.await()
                }
        if (closed) throw IOException("Buffer closed")
        val chunk = minOf(length - written, availableForWrite)
                data.copyInto(buffer, writePos, offset + written, offset + written + chunk)
                writePos = (writePos + chunk) % capacity
                written += chunk
                notEmpty.signal()
            }
        }
    }
        fun read(target: ByteArray, offset: Int = 0, length: Int = target.size - offset): Int {
        synchronized(lock) {
            while (availableForRead == 0 && !closed) {
                notEmpty.await()
            }
        if (closed && availableForRead == 0) return -1
            val chunk = minOf(length, availableForRead)
            buffer.copyInto(target, offset, readPos, readPos + chunk)
            readPos = (readPos + chunk) % capacity
            notFull.signal()
        return chunk
        }
    }
        fun close() {
        synchronized(lock) {
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }
        private val availableForRead: Int get() = (writePos - readPos + capacity) % capacity
    private val availableForWrite: Int get() = capacity - availableForRead - 1
}

class IOException(message: String) : java.io.IOException(message)
