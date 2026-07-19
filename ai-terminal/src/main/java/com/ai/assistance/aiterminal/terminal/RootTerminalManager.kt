package com.ai.assistance.aiterminal.terminal

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root/Non-Root 双模终端管理器
 * 支持在普通 Shell 和 Root Shell 之间切换
 */
class RootTerminalManager {

    companion object {
        const val MODE_NORMAL = 0
        const val MODE_ROOT = 1

        init {
            System.loadLibrary("ai_terminal_jni")
        }
    }

    // 终端尺寸数据类
    data class TerminalSize(val rows: Int, val cols: Int)

    // 当前运行模式
    private var currentMode = MODE_NORMAL

    /**
     * 检查环境是否支持 Root
     */
    fun checkRootAccess(): Boolean {
        return try {
            // 方法1: 检查 su 是否存在
            val suExists = checkSuBinary()
            
            // 方法2: 尝试执行简单的命令
            val canExecuteRoot = try {
                val process = Runtime.getRuntime().exec("su -c id")
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(10, TimeUnit.SECONDS)
                output.contains("uid=0")
            } catch (e: Exception) {
                false
            }

            suExists || canExecuteRoot
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 su 二进制文件是否存在
     */
    private fun checkSuBinary(): Boolean {
        val suPaths = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
            "/vendor/bin/su"
        )

        return suPaths.any { path ->
            try {
                val file = File(path)
                file.exists() && file.canExecute()
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 获取当前运行模式
     */
    fun getCurrentMode(): Int = currentMode

    /**
     * 创建并启动终端会话（自动检测模式）
     *
     * Security (A-2/A-3): generates a sessionId (UUID) before the JNI call
     * so the native session map can track the {child PID, master FD} pair.
     * The caller can later invoke [closeSession] with the same sessionId to
     * ensure the shell is killed + reaped + fd closed (rather than just
     * closing the master FD, which leaves the shell as an orphan).
     */
    suspend fun createSession(
        size: TerminalSize,
        env: Map<String, String> = emptyMap(),
        preferRoot: Boolean = false,
        sessionId: String = java.util.UUID.randomUUID().toString()
    ): TerminalSession = withContext(Dispatchers.IO) {
        // 决定使用什么模式
        val useRoot = preferRoot && checkRootAccess()
        currentMode = if (useRoot) MODE_ROOT else MODE_NORMAL

        // 1. 准备环境变量
        val envList = buildEnvList(env, useRoot)

        // 2. 调用 JNI 创建 PTY 并启动 Shell (pass sessionId so nativeCloseSession
        //    can later kill the child shell + reap + close the master fd).
        val fileDescriptor = nativeCreatePtyRoot(
            sessionId,
            size.cols,
            size.rows,
            envList.toTypedArray(),
            useRoot
        )

        if (fileDescriptor == -1) {
            throw RuntimeException("Failed to create PTY")
        }

        // 3. 包装成 AIDL 可传输的对象
        val pfd = ParcelFileDescriptor.adoptFd(fileDescriptor)
        TerminalSession(pfd, currentMode, sessionId)
    }

    /**
     * 创建并启动普通终端会话
     */
    suspend fun createNormalSession(
        size: TerminalSize,
        env: Map<String, String> = emptyMap()
    ): TerminalSession {
        currentMode = MODE_NORMAL
        return createSession(size, env, false)
    }

    /**
     * 创建并启动 Root 终端会话
     */
    suspend fun createRootSession(
        size: TerminalSize,
        env: Map<String, String> = emptyMap()
    ): TerminalSession {
        currentMode = MODE_ROOT
        return createSession(size, env, true)
    }

    /**
     * 切换现有会话的模式（销毁并重建）
     *
     * Security (A-2/A-3): now calls [nativeCloseSession] with the old
     * sessionId BEFORE closing the ParcelFileDescriptor — this ensures the
     * child shell is SIGKILL'd + reaped, rather than being left as an
     * orphan zombie (the previous code only closed the master FD, which
     * does NOT kill the shell).
     */
    suspend fun switchSessionMode(
        oldSession: TerminalSession,
        size: TerminalSize,
        env: Map<String, String> = emptyMap()
    ): TerminalSession {
        // 关闭旧会话: kill shell + reap + close fd at the native level.
        nativeCloseSession(oldSession.sessionId)
        // Also close the Java-side ParcelFileDescriptor (idempotent — if the
        // native side already closed the fd, this returns EBADF which PFD
        // swallows).
        try {
            oldSession.fileDescriptor.close()
        } catch (e: Exception) {
            // Best-effort; the fd may already be closed by nativeCloseSession.
        }

        // 创建新会话，使用相反的模式
        val newMode = if (oldSession.mode == MODE_NORMAL) MODE_ROOT else MODE_NORMAL
        currentMode = newMode

        return if (newMode == MODE_ROOT) {
            createRootSession(size, env)
        } else {
            createNormalSession(size, env)
        }
    }

    /**
     * 关闭终端会话
     *
     * Security (A-2/A-3): tears down the native session — SIGKILLs the
     * child shell, reaps it (waitpid), and closes the master fd. Returns
     * true if the session was found in the native map, false otherwise
     * (e.g. already closed or never created via this manager).
     *
     * The caller should ALSO close the [TerminalSession.fileDescriptor]
     * ParcelFileDescriptor — that close is idempotent with the native close.
     */
    fun closeSession(sessionId: String): Boolean {
        return nativeCloseSession(sessionId)
    }

    /**
     * 获取会话对应的子 Shell 进程 PID (用于 Kotlin 层做进程跟踪 / 优雅退出).
     * Returns -1 if the session is not registered in the native map.
     */
    fun getSessionPid(sessionId: String): Int {
        return nativeGetSessionPid(sessionId)
    }

    private fun buildEnvList(customEnv: Map<String, String>, useRoot: Boolean): List<String> {
        val baseEnv = if (useRoot) {
            // Root 环境
            mutableListOf(
                "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin",
                "HOME=/",
                "SHELL=/system/bin/sh",
                "TERM=xterm-256color"
            )
        } else {
            // 普通环境
            mutableListOf(
                "PATH=/system/bin:/system/xbin",
                "HOME=/data/data/com.ai.assistance.aiterminal/files",
                "SHELL=/system/bin/sh",
                "TERM=xterm-256color"
            )
        }

        customEnv.forEach { (k, v) -> baseEnv.add("$k=$v") }
        return baseEnv
    }

    // 会话数据类 - 使用独立的 TerminalSession

    // --- Native 方法声明 ---
    //
    // Security (A-2/A-3): nativeCreatePtyRoot now takes a sessionId; the
    // {child PID, master FD} pair is stored in a native session-keyed map
    // so nativeCloseSession can later kill+reap+close. nativeCloseSession
    // and nativeGetSessionPid are new (A-2/A-3).
    private external fun nativeCreatePtyRoot(
        sessionId: String,
        cols: Int,
        rows: Int,
        env: Array<String>,
        useRoot: Boolean
    ): Int

    /**
     * 关闭一个 root PTY 会话: kill shell + reap + close master fd.
     * Returns true if the session was found and torn down.
     */
    external fun nativeCloseSession(sessionId: String): Boolean

    /**
     * 返回会话对应的 child shell PID (或 -1 if 未注册).
     */
    external fun nativeGetSessionPid(sessionId: String): Int
}
