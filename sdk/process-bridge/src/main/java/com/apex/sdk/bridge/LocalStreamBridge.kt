package com.apex.sdk.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于 Android [LocalSocket]（Linux AF_UNIX abstract namespace）的流通道。
 *
 * **为什么用 LocalSocket 而不是 Binder？**
 *   - Binder 单事务上限 1MB，终端 PTY 输出一次可能几 MB，会触发 TransactionTooLargeException
 *   - Binder 每次调用都有 ~50us 序列化开销；LocalSocket 是流式 push，单次写入只受 socket buffer 影响
 *   - LocalSocket 不经过 TCP/IP 协议栈，是内核内存拷贝，延迟 < 10us
 *
 * **何时使用？**
 *   - 终端 PTY 输入输出流
 *   - 文件 watch 事件流
 *   - LLM Token 流式输出
 *
 * **使用方式**：
 *   ```kotlin
 *   // 服务端（终端 APK）
 *   val server = LocalStreamServer("terminal.pty.123")
 *   val input: ReceiveChannel<ByteArray> = server.input
 *   val output: SendChannel<ByteArray> = server.output
 *   server.start()
 *
 *   // 客户端（主 APK）
 *   val client = LocalStreamClient("terminal.pty.123")
 *   client.connect()
 *   client.send("ls\n".toByteArray())
 *   for (chunk in client.receiveFlow()) { renderTerminal(chunk) }
 *   ```
 *
 * 即使两个 APK 在同一进程，也推荐用 LocalSocket 处理流式数据，
 * 因为 Flow + Channel 已经是 Kotlin 侧最自然的 API。
 */
class LocalStreamServer(
    val channelName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val namespace = "${ApexSuite.LOCAL_SOCKET_NAMESPACE}.$channelName"

    private var serverSocket: LocalServerSocket? = null
    private var acceptJob: Job? = null

    private val _input = Channel<ByteArray>(Channel.BUFFERED)
    private val _output = Channel<ByteArray>(Channel.BUFFERED)

    /** 接收来自客户端的字节流（如终端按键输入）。 */
    val input: ReceiveChannel<ByteArray> = _input

    /** 向客户端发送的字节流（如终端 PTY 输出）。 */
    val output: SendChannel<ByteArray> = _output

    fun start() {
        if (serverSocket != null) return
        try {
            serverSocket = LocalServerSocket(namespace)
            ApexLog.d(ApexSuite.ApkId.MAIN, "[LocalStream] server started: $namespace")
        } catch (e: IOException) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[LocalStream] failed to start server: $namespace", e)
            throw e
        }

        acceptJob = scope.launch {
            try {
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: IOException) {
                if (acceptJob?.isActive == true) {
                    ApexLog.e(ApexSuite.ApkId.MAIN, "[LocalStream] accept loop error", e)
                }
            }
        }
    }

    private fun handleClient(socket: LocalSocket) {
        scope.launch {
            try {
                val input = socket.inputStream
                val output = socket.outputStream

                // 读循环：把客户端发来的字节推到 _input
                val readBuf = ByteArray(8192)
                while (true) {
                    val n = input.read(readBuf)
                    if (n < 0) break
                    if (n > 0) {
                        _input.send(readBuf.copyOf(n))
                    }
                }
            } catch (e: IOException) {
                // client disconnected
            } finally {
                try { socket.close() } catch (_: Throwable) {}
            }
        }

        scope.launch {
            try {
                val output = socket.outputStream
                // 写循环：把 _output 中的字节写给客户端
                while (true) {
                    val chunk = _output.receive()
                    output.write(chunk)
                    output.flush()
                }
            } catch (e: IOException) {
                // client gone
            } finally {
                try { socket.close() } catch (_: Throwable) {}
            }
        }
    }

    fun close() {
        acceptJob?.cancel()
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        _input.close()
        _output.close()
        ApexLog.d(ApexSuite.ApkId.MAIN, "[LocalStream] server closed: $namespace")
    }

    companion object {
        private val counter = AtomicLong(0)

        /** 生成一个唯一的通道名。 */
        fun nextChannelName(prefix: String = "ch"): String {
            return "$prefix.${counter.incrementAndGet()}"
        }
    }
}

/**
 * LocalSocket 客户端 — 连接到 [LocalStreamServer] 并提供流式 API。
 */
class LocalStreamClient(
    val channelName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val namespace = "${ApexSuite.LOCAL_SOCKET_NAMESPACE}.$channelName"

    private var socket: LocalSocket? = null
    private var readJob: Job? = null

    private val _received = Channel<ByteArray>(Channel.BUFFERED)

    /** 收到的字节流。 */
    val received: ReceiveChannel<ByteArray> = _received

    /** 暴露为 Flow，方便 Compose collectAsState。 */
    fun receiveFlow(): Flow<ByteArray> = flow {
        for (chunk in _received) emit(chunk)
    }

    fun connect(timeoutMs: Int = 5000): Boolean {
        val sock = LocalSocket(LocalSocket.SOCKET_SEQPACKET)
        return try {
            sock.connect(LocalSocketAddress(namespace, LocalSocketAddress.Namespace.ABSTRACT), timeoutMs)
            socket = sock
            ApexLog.d(ApexSuite.ApkId.MAIN, "[LocalStream] client connected: $namespace")
            startReadLoop(sock)
            true
        } catch (e: IOException) {
            ApexLog.e(ApexSuite.ApkId.MAIN, "[LocalStream] connect failed: $namespace", e)
            try { sock.close() } catch (_: Throwable) {}
            false
        }
    }

    private fun startReadLoop(sock: LocalSocket) {
        readJob = scope.launch {
            try {
                val input = sock.inputStream
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) _received.send(buf.copyOf(n))
                }
            } catch (e: IOException) {
                // disconnected
            } finally {
                _received.close()
            }
        }
    }

    suspend fun send(bytes: ByteArray): Boolean {
        val sock = socket ?: return false
        return withContext(Dispatchers.IO) {
            try {
                sock.outputStream.write(bytes)
                sock.outputStream.flush()
                true
            } catch (e: IOException) {
                ApexLog.e(ApexSuite.ApkId.MAIN, "[LocalStream] send failed: $namespace", e)
                false
            }
        }
    }

    fun close() {
        readJob?.cancel()
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        _received.close()
    }
}

/**
 * 流通道注册表 — 服务端用来管理所有活跃的通道。
 */
object StreamChannelRegistry {

    private val servers = ConcurrentHashMap<String, LocalStreamServer>()

    fun open(channelName: String): LocalStreamServer {
        val existing = servers[channelName]
        if (existing != null) return existing
        val server = LocalStreamServer(channelName)
        server.start()
        servers[channelName] = server
        return server
    }

    fun close(channelName: String) {
        servers.remove(channelName)?.close()
    }

    fun listChannels(): List<String> = servers.keys.toList()

    fun closeAll() {
        servers.values.forEach { it.close() }
        servers.clear()
    }
}
