package com.ai.assistance.aiterminal.terminal.agent.task

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "TaskWakeLockManager"

/**
 * 任务 WakeLock 管理器 - 专门负责任务执行时的设备唤醒管理
 * 
 * 职责：
 * 1. 获取和释放多种类型的 WakeLock
 * 2. 防止 WakeLock 泄漏
 * 3. 提供自动释放机制和状态监听
 * 4. 支持 WakeLock 计数和超时管理
 */
class TaskWakeLockManager(
    private val context: Context
) {
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    
    private var currentWakeLock: PowerManager.WakeLock? = null
    private var wakeLockType: WakeLockType = WakeLockType.PARTIAL
    private var wakeLockCount = 0
    private val mutex = Mutex()
    
    private val listeners = mutableListOf<WakeLockListener>()
    
    /**
     * WakeLock 类型枚举
     */
    enum class WakeLockType {
        PARTIAL,
        SCREEN_DIM,
        SCREEN_BRIGHT,
        FULL,
        PROXIMITY_SCREEN_OFF
    }
    
    /**
     * WakeLock 状态枚举
     */
    enum class WakeLockState {
        RELEASED,
        ACQUIRED,
        TIMED_OUT,
        ERROR
    }
    
    /**
     * 获取当前 WakeLock 状态
     */
    val currentState: WakeLockState
        get() {
            return if (currentWakeLock?.isHeld == true) {
                WakeLockState.ACQUIRED
            } else {
                WakeLockState.RELEASED
            }
        }
    
    /**
     * 获取当前 WakeLock 类型
     */
    val currentType: WakeLockType
        get() = wakeLockType
    
    /**
     * 获取 WakeLock 计数
     */
    val holdCount: Int
        get() = wakeLockCount
    
    /**
     * 获取指定类型的 WakeLock
     */
    fun acquireWakeLock(
        type: WakeLockType = WakeLockType.PARTIAL,
        timeoutMs: Long = 10 * 60 * 1000L
    ): Boolean {
        return try {
            releaseWakeLock()
            
            val flags = getWakeLockFlags(type)
            val wakeLock = powerManager.newWakeLock(flags, getWakeLockTag(type)).apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
            
            currentWakeLock = wakeLock
            wakeLockType = type
            wakeLockCount++
            
            Log.i(TAG, "WakeLock acquired: $type, timeout: $timeoutMs ms")
            notifyListeners(WakeLockState.ACQUIRED)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: $type", e)
            notifyListeners(WakeLockState.ERROR)
            false
        }
    }
    
    /**
     * 获取多个 WakeLock（递增计数）
     */
    fun acquireWakeLockIncremental(
        type: WakeLockType = WakeLockType.PARTIAL,
        timeoutMs: Long = 10 * 60 * 1000L
    ): Boolean {
        if (currentWakeLock?.isHeld == true) {
            wakeLockCount++
            Log.i(TAG, "WakeLock count incremented: $wakeLockCount")
            return true
        }
        
        return acquireWakeLock(type, timeoutMs)
    }
    
    /**
     * 释放单个 WakeLock（递减计数）
     */
    fun releaseWakeLockDecremental(): Boolean {
        if (wakeLockCount > 1) {
            wakeLockCount--
            Log.i(TAG, "WakeLock count decremented: $wakeLockCount")
            return true
        }
        
        return releaseWakeLock()
    }
    
    /**
     * 释放 WakeLock
     */
    fun releaseWakeLock(): Boolean {
        return try {
            currentWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WakeLock released: $wakeLockType")
                    notifyListeners(WakeLockState.RELEASED)
                }
            }
            currentWakeLock = null
            wakeLockCount = 0
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
            notifyListeners(WakeLockState.ERROR)
            false
        }
    }
    
    /**
     * 使用 WakeLock 执行任务（带超时）
     */
    suspend fun <T> withWakeLock(
        type: WakeLockType = WakeLockType.PARTIAL,
        timeoutMs: Long = 10 * 60 * 1000L,
        block: suspend () -> T
    ): T {
        return mutex.withLock {
            try {
                acquireWakeLock(type, timeoutMs)
                return@withLock block()
            } finally {
                releaseWakeLock()
            }
        }
    }
    
    /**
     * 使用 WakeLock 执行任务（自动超时）
     */
    suspend fun <T> withAutoWakeLock(
        type: WakeLockType = WakeLockType.PARTIAL,
        timeoutMs: Long = 5 * 60 * 1000L,
        block: suspend () -> T
    ): T {
        return withWakeLock(type, timeoutMs, block)
    }
    
    /**
     * 检查设备是否处于唤醒状态
     */
    fun isDeviceAwake(): Boolean {
        return powerManager.isInteractive
    }
    
    /**
     * 检查是否持有 WakeLock
     */
    fun isWakeLockHeld(): Boolean {
        return currentWakeLock?.isHeld ?: false
    }
    
    /**
     * 添加 WakeLock 监听器
     */
    fun addListener(listener: WakeLockListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }
    
    /**
     * 移除 WakeLock 监听器
     */
    fun removeListener(listener: WakeLockListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    /**
     * 清空所有监听器
     */
    fun clearListeners() {
        synchronized(listeners) {
            listeners.clear()
        }
    }
    
    /**
     * 通知所有监听器
     */
    private fun notifyListeners(state: WakeLockState) {
        synchronized(listeners) {
            listeners.forEach {
                try {
                    it.onWakeLockStateChanged(state, wakeLockType)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener callback failed", e)
                }
            }
        }
    }
    
    /**
     * 获取 WakeLock 标志位
     */
    private fun getWakeLockFlags(type: WakeLockType): Int {
        return when (type) {
            WakeLockType.PARTIAL -> PowerManager.PARTIAL_WAKE_LOCK
            WakeLockType.SCREEN_DIM -> PowerManager.SCREEN_DIM_WAKE_LOCK
            WakeLockType.SCREEN_BRIGHT -> PowerManager.SCREEN_BRIGHT_WAKE_LOCK
            WakeLockType.FULL -> PowerManager.FULL_WAKE_LOCK
            WakeLockType.PROXIMITY_SCREEN_OFF -> PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
        }
    }
    
    /**
     * 获取 WakeLock 标签
     */
    private fun getWakeLockTag(type: WakeLockType): String {
        return "AIAssistant:${type.name}WakeLock"
    }
    
    /**
     * WakeLock 状态监听器接口
     */
    interface WakeLockListener {
        fun onWakeLockStateChanged(state: WakeLockState, type: WakeLockType)
    }
}