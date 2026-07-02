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
     */
    suspend fun createSession(
        size: TerminalSize,
        env: Map<String, String> = emptyMap(),
        preferRoot: Boolean = false
    ): TerminalSession = withContext(Dispatchers.IO) {
        // 决定使用什么模式
        val useRoot = preferRoot && checkRootAccess()
        currentMode = if (useRoot) MODE_ROOT else MODE_NORMAL

        // 1. 准备环境变量
        val envList = buildEnvList(env, useRoot)
        
        // 2. 调用 JNI 创建 PTY 并启动 Shell
        val fileDescriptor = nativeCreatePtyRoot(
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
        TerminalSession(pfd, currentMode)
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
     */
    suspend fun switchSessionMode(
        oldSession: TerminalSession,
        size: TerminalSize,
        env: Map<String, String> = emptyMap()
    ): TerminalSession {
        // 关闭旧会话
        oldSession.fileDescriptor.close()

        // 创建新会话，使用相反的模式
        val newMode = if (oldSession.mode == MODE_NORMAL) MODE_ROOT else MODE_NORMAL
        currentMode = newMode

        return if (newMode == MODE_ROOT) {
            createRootSession(size, env)
        } else {
            createNormalSession(size, env)
        }
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
    private external fun nativeCreatePtyRoot(
        cols: Int,
        rows: Int,
        env: Array<String>,
        useRoot: Boolean
    ): Int
}
