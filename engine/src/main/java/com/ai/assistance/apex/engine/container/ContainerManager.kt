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

    private var shellSession: ShellSession? = null
    private var outputListener: ((String) -> Unit)? = null
    private var statusListener: ((Int) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

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

        currentStatus.set(STATUS_STARTING)
        statusListener?.invoke(currentStatus.get())

        try {
            if (!prepareRootfs()) {
                currentStatus.set(STATUS_ERROR)
                statusListener?.invoke(currentStatus.get())
                return false
            }

            isRunning.set(true)

            executor.submit {
                shellSession = ShellSession(context, getRootfsPath())
                shellSession?.setOutputListener { output ->
                    outputListener?.invoke(output)
                }
                shellSession?.setErrorListener { error ->
                    errorListener?.invoke(error)
                }

                if (shellSession?.start() == true) {
                    currentStatus.set(STATUS_RUNNING)
                    statusListener?.invoke(currentStatus.get())
                    try { Thread.sleep(500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    executeCommand("source /init.sh 2>/dev/null || true")
                } else {
                    currentStatus.set(STATUS_ERROR)
                    statusListener?.invoke(currentStatus.get())
                    isRunning.set(false)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "start() failed", e)
            errorListener?.invoke(e.message ?: "Unknown error")
            currentStatus.set(STATUS_ERROR)
            statusListener?.invoke(currentStatus.get())
            isRunning.set(false)
            return false
        }
    }

    fun stop(): Boolean {
        return try {
            isRunning.set(false)
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
        stop()
        try { Thread.sleep(1000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        return start()
    }

    fun executeCommand(command: String): ExecutionResult {
        if (!isRunning.get() || shellSession == null) {
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