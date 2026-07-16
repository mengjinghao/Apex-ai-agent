package com.apex.agent.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 协程工具类，提供常用的协程调度器访问、安全启动、重试、防抖、节流、耗时测量等功能。
 */

    /** IO 密集型任务调度器 */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** CPU 密集型任务调度器 */
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** UI 主线程调度器 */
    val mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    /**
     * 创建一个命名的新单线程调度器。
     * 适用于需要保证顺序执行的场景。
     *
     * @param name 线程名称
     * @return 单线程调度器，使用完毕记得调用 [close][java.util.concurrent.ExecutorService.close]
     */
    fun newSingleThreadContext(name: String): kotlinx.coroutines.ExecutorCoroutineDispatcher {
        return Executors.newSingleThreadExecutor { r ->
            Thread(r, name).also { it.isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * 检查 [Job] 是否处于活跃状态。
     *
     * @param job 待检查的 Job
     * @return 如果 job 不为 null 且处于活跃状态返回 true
     */
    fun isActive(job: Job?): Boolean = job != null && job.isActive

    /**
     * 安全启动协程，自动捕获异常并通过 [onError] 回调处理。
     *
     * @param scope   协程作用域
     * @param onError 异常处理回调，为 null 时静默吞掉异常
     * @param block   协程体
     * @return 启动的 [Job]
     */
    fun safeLaunch(
        scope: CoroutineScope,
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return scope.launch {
            try {
                block()
            } catch (e: Exception) {
                onError?.invoke(e)
            }
        }
    }

    /**
     * 带超时的安全调用，超时或异常时返回 null。
     *
     * @param timeMillis 超时时间（毫秒）
     * @param block      协程体
     * @return 正常返回结果，超时或异常返回 null
     */
    suspend fun <T> withTimeoutOrNullSafe(
        timeMillis: Long,
        block: suspend CoroutineScope.() -> T
    ): T? {
        return try {
            withTimeoutOrNull(timeMillis) { block() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 带指数退避重试机制的协程执行。
     * 每次重试的延迟时间 = currentDelay * factor，最大不超过 [maxDelay]。
     *
     * @param times        最大重试次数（包含首次执行），必须大于 0
     * @param initialDelay 首次重试前的初始延迟（毫秒），默认 100ms
     * @param maxDelay     最大延迟（毫秒），默认 5000ms
     * @param factor       延迟增长因子，默认 2.0（指数退避）
     * @param block        待执行的重试块
     * @return 执行结果
     * @throws Throwable 所有重试均失败时抛出最后一次的异常
     */
    suspend fun <T> retry(
        times: Int,
        initialDelay: Long = 100,
        maxDelay: Long = 5000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        require(times > 0) { "重试次数必须大于 0" }
        var currentDelay = initialDelay
        repeat(times - 1) {
            try {
                return block()
            } catch (_: Exception) {
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }
        return block()
    }

    /**
     * 创建一个防抖函数，在 [delayMs] 时间内连续调用仅最后一次生效。
     * 常用于搜索输入框等需要避免频繁请求的场景。
     *
     * @param delayMs 防抖延迟时间（毫秒）
     * @param scope   协程作用域
     * @param action  实际要执行的操作
     * @return 防抖后的函数
     */
    fun debounce(
        delayMs: Long,
        scope: CoroutineScope,
        action: () -> Unit
    ): () -> Unit {
        var job: Job? = null
        return {
            job?.cancel()
            job = scope.launch {
                delay(delayMs)
                action()
            }
        }
    }

    /**
     * 创建一个节流函数，在 [thresholdMs] 时间内首次调用立即执行，
     * 之后在时间窗口内的调用被忽略。
     * 常用于按钮点击等需要避免重复操作的场景。
     *
     * @param thresholdMs 节流时间窗口（毫秒）
     * @param scope       协程作用域
     * @param action      实际要执行的操作
     * @return 节流后的函数
     */
    fun throttleFirst(
        thresholdMs: Long,
        scope: CoroutineScope,
        action: () -> Unit
    ): () -> Unit {
        var lastTime = 0L
        return {
            val now = System.currentTimeMillis()
            if (now - lastTime >= thresholdMs) {
                lastTime = now
                scope.launch { action() }
            }
        }
    }

    /**
     * 测量协程块的执行耗时。
     *
     * @param block 待测量的协程块
     * @return [Pair]，包含执行结果和耗时（毫秒）
     */
    suspend fun <T> measureTime(block: suspend () -> T): Pair<T, Long> {
        val start = System.currentTimeMillis()
        val result = block()
        val elapsed = System.currentTimeMillis() - start
        return result to elapsed
    }

    /**
     * 在主线程上执行一个代码块。
     *
     * @param block 待执行的代码块
     */
    suspend fun runOnMain(block: () -> Unit) {
        withContext(Dispatchers.Main) { block() }
    }

    /**
     * 在 IO 线程上异步执行一个代码块。
     *
     * @param block 待执行的协程块
     * @param scope 协程作用域，默认 GlobalScope
     * @return [Deferred]，可通过 await() 获取结果
     */
    fun <T> runOnIO(
        block: suspend () -> T,
        scope: CoroutineScope = GlobalScope
    ): Deferred<T> {
        return scope.async(Dispatchers.IO) { block() }
    }

    /**
     * 协程统计信息数据类，用于监控协程运行状态。
     *
     * @property activeJobs   当前活跃的协程数
     * @property completedJobs 已完成的协程总数
     */
    data class CoroutineStats(
        val activeJobs: Int = 0,
        val completedJobs: Long = 0L
    )

    /**
     * 协程统计收集器，可用于跟踪协程的启动和完成情况。
     */
    class CoroutineStatsCollector {
        private val _active = AtomicInteger(0)
        private val _completed = AtomicLong(0)

        internal fun onStart() { _active.incrementAndGet() }

        internal fun onComplete() {
            _active.decrementAndGet()
            _completed.incrementAndGet()
        }

        /** 获取当前统计快照 */
        fun snapshot(): CoroutineStats = CoroutineStats(
            activeJobs = _active.get(),
            completedJobs = _completed.get()
        )

        /** 重置所有计数 */
        fun reset() {
            _active.set(0)
            _completed.set(0)
        }
    }
}
