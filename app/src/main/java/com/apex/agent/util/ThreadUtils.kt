package com.apex.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 线程工具类，提供线程创建、线程池管理、主线程检测等常用并发操作
 */
object ThreadUtils {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 线程信息数据类
     *
     * @property name 线程名称
     * @property id 线程 ID
     * @property priority 线程优先级
     * @property isDaemon 是否为守护线程
     * @property state 线程状态
     */
    data class ThreadInfo(
        val name: String,
        val id: Long,
        val priority: Int,
        val isDaemon: Boolean,
        val state: Thread.State
    )

    /**
     * 创建具有指定名称前缀的线程工厂
     *
     * @param namePrefix 线程名称前缀
     * @param daemon 是否为守护线程，默认为 false
     * @return 线程工厂
     */
    fun newNamedThreadFactory(namePrefix: String, daemon: Boolean = false): ThreadFactory {
        val threadNumber = AtomicInteger(1)
        return ThreadFactory { runnable ->
            Thread(runnable, "$namePrefix-${threadNumber.getAndIncrement()}").apply {
                isDaemon = daemon
                if (priority != Thread.NORM_PRIORITY) {
                    priority = Thread.NORM_PRIORITY
                }
            }
        }
    }

    /**
     * 创建具有固定线程数的命名线程池
     *
     * @param nThreads 线程池大小
     * @param namePrefix 线程名称前缀
     * @return 固定大小的线程池
     */
    fun newFixedThreadPool(nThreads: Int, namePrefix: String): ExecutorService {
        return Executors.newFixedThreadPool(nThreads, newNamedThreadFactory(namePrefix))
    }

    /**
     * 创建可缓存的命名线程池
     *
     * @param namePrefix 线程名称前缀
     * @return 可缓存线程池
     */
    fun newCachedThreadPool(namePrefix: String): ExecutorService {
        return Executors.newCachedThreadPool(newNamedThreadFactory(namePrefix))
    }

    /**
     * 创建单线程的命名线程池
     *
     * @param namePrefix 线程名称前缀
     * @return 单线程池
     */
    fun newSingleThreadExecutor(namePrefix: String): ExecutorService {
        return Executors.newSingleThreadExecutor(newNamedThreadFactory(namePrefix))
    }

    /**
     * 创建可调度的命名线程池
     *
     * @param corePoolSize 核心线程数
     * @param namePrefix 线程名称前缀
     * @return 可调度线程池
     */
    fun newScheduledThreadPool(corePoolSize: Int, namePrefix: String): ScheduledExecutorService {
        return Executors.newScheduledThreadPool(corePoolSize, newNamedThreadFactory(namePrefix))
    }

    private val cachedPool by lazy { newCachedThreadPool("Background") }

    /**
     * 在后台线程上执行 Runnable 任务（使用缓存的线程池）
     *
     * @param runnable 待执行的任务
     * @return Future 对象，可用于获取结果或取消任务
     */
    fun runOnBackgroundThread(runnable: Runnable): Future<*> {
        return cachedPool.submit(runnable)
    }

    /**
     * 在后台线程上执行 Lambda 任务
     *
     * @param runnable 待执行的 lambda
     * @return Future 对象
     */
    fun runOnBackgroundThread(runnable: () -> Unit): Future<*> {
        return cachedPool.submit(runnable)
    }

    /**
     * 在主线程上执行任务
     *
     * @param runnable 待执行的 lambda
     */
    fun runOnMainThread(runnable: () -> Unit) {
        if (isMainThread()) {
            runnable()
        } else {
            mainHandler.post(runnable)
        }
    }

    /**
     * 安静地休眠指定毫秒数（不抛出 InterruptedException）
     *
     * @param millis 休眠时间（毫秒）
     */
    fun sleepSafe(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 获取当前线程的名称
     *
     * @return 当前线程名称
     */
    fun getCurrentThreadName(): String {
        return Thread.currentThread().name
    }

    /**
     * 判断当前是否在主线程（UI 线程）
     *
     * @return 如果在主线程返回 true
     */
    fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    /**
     * 断言当前在主线程，否则抛出 IllegalStateException
     *
     * @throws IllegalStateException 如果不在主线程
     */
    fun assertMainThread() {
        check(isMainThread()) { "Expected to be on the main thread but was on thread: ${getCurrentThreadName()}" }
    }

    /**
     * 断言当前在后台线程，否则抛出 IllegalStateException
     *
     * @throws IllegalStateException 如果在主线程
     */
    fun assertBackgroundThread() {
        check(!isMainThread()) { "Expected to be on a background thread but was on the main thread" }
    }

    /**
     * 获取当前系统可用的处理器核心数量
     *
     * @return 可用处理器核心数
     */
    fun getAvailableProcessors(): Int {
        return Runtime.getRuntime().availableProcessors()
    }

    /**
     * 获取当前线程的完整信息
     *
     * @return ThreadInfo 对象
     */
    fun getCurrentThreadInfo(): ThreadInfo {
        val thread = Thread.currentThread()
        return ThreadInfo(
            name = thread.name,
            id = thread.id,
            priority = thread.priority,
            isDaemon = thread.isDaemon,
            state = thread.state
        )
    }

    /**
     * 获取所有线程的堆栈信息列表
     *
     * @return 所有线程信息的列表
     */
    fun getAllThreadInfos(): List<ThreadInfo> {
        val threadSet = Thread.getAllStackTraces().keys
        return threadSet.map { thread ->
            ThreadInfo(
                name = thread.name,
                id = thread.id,
                priority = thread.priority,
                isDaemon = thread.isDaemon,
                state = thread.state
            )
        }
    }
}
