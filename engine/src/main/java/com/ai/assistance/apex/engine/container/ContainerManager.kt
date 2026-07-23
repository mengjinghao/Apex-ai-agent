package com.ai.assistance.apex.engine.container

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ai.assistance.apex.engine.model.ContainerStatus
import com.ai.assistance.apex.engine.model.ExecutionResult
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class ContainerManager(private val context: Context) {

    companion object {
        private const val TAG = "ContainerManager"
        const val STATUS_STOPPED = 0
        const val STATUS_STARTING = 1
        const val STATUS_RUNNING = 2
        const val STATUS_ERROR = -1

        private const val ROOTFS_DIR = "rootfs"
        private const val INIT_SCRIPT = "init.sh"
        private const val ROOTFS_ASSET = "rootfs.tar.xz"
        private const val COMMAND_TIMEOUT_MS = 30000L
    }

    @Volatile private var shellSession: ShellSession? = null
    @Volatile private var outputListener: ((String) -> Unit)? = null
    @Volatile private var statusListener: ((Int) -> Unit)? = null
    @Volatile private var errorListener: ((String) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    // PERF-29: 容器就绪信号 —— start() 提交后台任务后立即返回；后台任务完成
    // prepareRootfs() + shellSession.start() 后 complete(true)。executeCommand()
    // 在执行前 await 该信号，避免在调用线程上阻塞 5 分钟的 tar -xf。
    // 初始实例预 complete(false)：保证 start() 从未调用时 executeCommand() 的
    // await 立即返回 false，不会永久阻塞。
    @Volatile
    private var containerReady: CompletableDeferred<Boolean> = CompletableDeferred<Boolean>().apply { complete(false) }

    private val currentStatus = AtomicInteger(STATUS_STOPPED)

    fun setOutputListener(listener: (String) -> Unit) {
        outputListener = listener
    }

    fun setStatusListener(listener: (Int) -> Unit) {
        statusListener = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
    }

    /**
     * 挂起等待容器就绪（PERF-29）。start() 提交的后台任务完成 prepareRootfs +
     * shell 启动后 resolve。返回 false 表示容器未就绪（start 未调用 / 失败 / 已 stop）。
     */
    suspend fun awaitReady(): Boolean = containerReady.await()

    fun getStatus(): ContainerStatus {
        return ContainerStatus().apply {
            statusCode = currentStatus.get()
            statusMessage = when (currentStatus.get()) {
                STATUS_STOPPED -> "Stopped"
                STATUS_STARTING -> "Starting"
                STATUS_RUNNING -> "Running"
                STATUS_ERROR -> "Error"
                else -> "Unknown"
            }
            pid = shellSession?.pid ?: -1
            startTime = shellSession?.startTime ?: 0
            rootfsPath = getRootfsPath()
        }
    }

    fun start(): Boolean {
        if (isRunning.get()) {
            return true
        }

        // PERF-29: 重置就绪信号 —— 每次 start 都用全新的 deferred，避免上一次
        // stop() complete(false) 后旧信号残留。
        containerReady = CompletableDeferred()

        currentStatus.set(STATUS_STARTING)
        statusListener?.invoke(currentStatus.get())

        try {
            // PERF-29: prepareRootfs()（首次启动会执行 tar -xf，最长 5 分钟）
            // 移入后台线程，start() 立即返回 true。就绪状态通过 containerReady
            // 信号 + statusListener 异步通知调用方，不再阻塞 binder 线程。
            executor.submit {
                try {
                    if (!prepareRootfs()) {
                        currentStatus.set(STATUS_ERROR)
                        statusListener?.invoke(currentStatus.get())
                        isRunning.set(false)
                        containerReady.complete(false)
                        return@submit
                    }

                    shellSession = ShellSession(context, getRootfsPath())
                    shellSession?.setOutputListener { output ->
                        outputListener?.invoke(output)
                    }
                    shellSession?.setErrorListener { error ->
                        errorListener?.invoke(error)
                    }

                    if (shellSession?.start() == true) {
                        // PERF-30: 用 sentinel 探测替代旧的 Thread.sleep(500)。
                        // shellSession.execute 内部追加 `echo <sentinel>:$?` 并轮询
                        // outputBuffer 直到 sentinel 出现才返回 —— 真正确认 shell 进程
                        // 已就绪可接收命令，比固定 500ms 更准且通常更快。
                        shellSession?.execute("echo __APEX_READY__", 2000)
                        // Only mark running AFTER the shell session is actually ready.
                        isRunning.set(true)
                        currentStatus.set(STATUS_RUNNING)
                        statusListener?.invoke(currentStatus.get())
                        // PERF-29: 通知所有等待 executeCommand 的调用方 —— 容器就绪。
                        // 必须在调用 executeCommand 之前 complete，否则 executeCommand
                        // 内部 await 会死锁（等待自己完成的信号）。
                        containerReady.complete(true)
                        // 此时 executeCommand 不会阻塞（deferred 已 complete(true)）。
                        executeCommand("source /init.sh 2>/dev/null || true")
                    } else {
                        currentStatus.set(STATUS_ERROR)
                        statusListener?.invoke(currentStatus.get())
                        isRunning.set(false)
                        containerReady.complete(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "start() executor task failed", e)
                    errorListener?.invoke(e.message ?: "Unknown error")
                    currentStatus.set(STATUS_ERROR)
                    statusListener?.invoke(currentStatus.get())
                    isRunning.set(false)
                    containerReady.complete(false)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "start() failed", e)
            errorListener?.invoke(e.message ?: "Unknown error")
            currentStatus.set(STATUS_ERROR)
            statusListener?.invoke(currentStatus.get())
            isRunning.set(false)
            containerReady.complete(false)
            return false
        }
    }

    fun stop(): Boolean {
        return try {
            isRunning.set(false)
            // PERF-29: 解除任何在 executeCommand 中 await 的调用方，让它们立即收到
            // "Container not running"。start() 会在下次调用时替换为新 deferred。
            containerReady.complete(false)
            shellSession?.stop()
            shellSession = null
            currentStatus.set(STATUS_STOPPED)
            statusListener?.invoke(currentStatus.get())
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
            errorListener?.invoke(e.message ?: "Stop error")
            false
        }
    }

    fun restart(): Boolean {
        // PERF-30: 去除旧的 Thread.sleep(1000) —— stop() 同步关闭 shellSession，
        // start() 内部用 sentinel 探测等 shell 就绪，固定 sleep 既慢又不可靠。
        stop()
        return start()
    }

    fun executeCommand(command: String): ExecutionResult {
        // PERF-29: 等待容器就绪（prepareRootfs + shell 启动完成）。
        // - start() 从未调用：containerReady 是预 complete(false) 的默认实例，
        //   await 立即返回 false -> 走 "Container not running" 分支。
        // - start() 已提交后台任务但未完成：本调用阻塞等待，直到后台任务 complete。
        // - stop() 后：containerReady 已被 complete(false)，await 立即返回 false。
        val ready = containerReady.let { runBlocking { it.await() } }
        if (!ready || !isRunning.get() || shellSession == null) {
            return ExecutionResult().apply {
                exitCode = -1
                error = "Container not running"
                success = false
            }
        }

        return shellSession?.execute(command, COMMAND_TIMEOUT_MS) ?: ExecutionResult().apply {
            exitCode = -1
            error = "Shell session is null"
            success = false
        }
    }

    fun getOutput(): String {
        return shellSession?.getOutput() ?: ""
    }

    fun isContainerRunning(): Boolean {
        return isRunning.get() && currentStatus.get() == STATUS_RUNNING
    }

    private fun prepareRootfs(): Boolean {
        val rootfsDir = File(getRootfsPath())

        if (!rootfsDir.exists()) {
            rootfsDir.mkdirs()
        }

        val prootFile = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!prootFile.exists()) {
            errorListener?.invoke("proot library not found at ${prootFile.absolutePath}")
            return false
        }

        if (!rootfsDir.exists() || rootfsDir.listFiles()?.isEmpty() != false) {
            try {
                extractRootfs(rootfsDir)
            } catch (e: Exception) {
                Log.e(TAG, "prepareRootfs: extractRootfs failed", e)
                errorListener?.invoke("Failed to extract rootfs: ${e.message}")
                return false
            }
        }

        val initFile = File(rootfsDir, INIT_SCRIPT)
        if (!initFile.exists()) {
            try {
                context.assets.open(INIT_SCRIPT).use { input ->
                    initFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                initFile.setExecutable(true)
            } catch (e: IOException) {
                Log.e(TAG, "prepareRootfs: init script extraction failed", e)
            }
        }

        return true
    }

    private fun extractRootfs(destDir: File) {
        try {
            context.assets.open(ROOTFS_ASSET).use { input ->
                val tempFile = File(context.cacheDir, "rootfs.tar.xz")
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }

                val process = Runtime.getRuntime().exec(arrayOf("tar", "-xf", tempFile.absolutePath, "-C", destDir.absolutePath))
                val exitCode = process.waitFor(5, TimeUnit.MINUTES)

                if (exitCode) {
                    tempFile.delete()
                }
            }
        } catch (e: IOException) {
            errorListener?.invoke("rootfs.tar.xz not found in assets. Please add it.")
        }
    }

    private fun getRootfsPath(): String {
        return File(context.getExternalFilesDir(null), ROOTFS_DIR).absolutePath
    }
}