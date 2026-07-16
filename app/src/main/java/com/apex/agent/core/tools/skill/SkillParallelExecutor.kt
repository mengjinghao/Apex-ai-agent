package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class SkillParallelExecutor private constructor(
    private val corePoolSize: Int,
    private val maxPoolSize: Int,
    private val keepAliveTimeMs: Long,
    private val maxQueueSize: Int
) {

    companion object {
        private const val TAG = "SkillParallelExecutor"
        private const val DEFAULT_CORE_POOL_SIZE = 4
        private const val DEFAULT_MAX_POOL_SIZE = 16
        private const val DEFAULT_KEEP_ALIVE_MS = 60000L
        private const val DEFAULT_MAX_QUEUE_SIZE = 100

        @Volatile private var INSTANCE: SkillParallelExecutor? = null

        fun getInstance(): SkillParallelExecutor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillParallelExecutor(
                    DEFAULT_CORE_POOL_SIZE,
                    DEFAULT_MAX_POOL_SIZE,
                    DEFAULT_KEEP_ALIVE_MS,
                    DEFAULT_MAX_QUEUE_SIZE
                ).also { INSTANCE = it }
            }
        }

        fun getInstance(corePoolSize: Int, maxPoolSize: Int, keepAliveMs: Long, maxQueueSize: Int): SkillParallelExecutor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillParallelExecutor(corePoolSize, maxPoolSize, keepAliveMs, maxQueueSize).also { INSTANCE = it }
            }
        }
    }

    interface TaskExecutor {
        suspend fun execute(task: SkillTaskQueue.SkillTask): Any?
    }

    data class ExecutorStats(
        val totalTasksSubmitted: Long,
        val totalTasksCompleted: Long,
        val totalTasksFailed: Long,
        val activeThreads: Int,
        val poolSize: Int,
        val queueSize: Int,
        val largestPoolSize: Int,
        val totalExecutionTimeMs: Long,
        val averageExecutionTimeMs: Long,
        val peakConcurrency: Int,
        val throughput: Float,
        val cpuUtilization: Float
    )

    private val threadPool = ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        keepAliveTimeMs,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        SkillThreadFactory()
    )

    private val taskQueue = SkillTaskQueue(maxQueueSize, usePriorityQueue = true)
    private val activeTasks = ConcurrentHashMap<String, Thread>()
    private val completedTasks = ConcurrentHashMap<String, SkillTaskQueue.TaskResult>()
    private val taskExecutors = ConcurrentHashMap<String, TaskExecutor>()

    private val submittedCount = AtomicLong(0)
    private val completedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    private val totalExecutionTime = AtomicLong(0)
    private val peakConcurrency = AtomicInteger(0)
    private val currentConcurrency = AtomicInteger(0)
    private val totalCpuTime = AtomicLong(0)
    private val activeCpuTime = AtomicLong(0)

    private var isRunning = false
    private var dispatcherThread: Thread? = null

    private val listeners = CopyOnWriteArrayList<ExecutionListener>()

    interface ExecutionListener {
        fun onTaskStarted(taskId: String, skillName: String)
        fun onTaskCompleted(taskId: String, result: Any)
        fun onTaskFailed(taskId: String, error: String)
        fun onParallelismChanged(newLevel: Int)
    }

    fun addListener(listener: ExecutionListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ExecutionListener) {
        listeners.remove(listener)
    }

    fun registerExecutor(skillName: String, executor: TaskExecutor) {
        taskExecutors[skillName] = executor
    }

    fun unregisterExecutor(skillName: String) {
        taskExecutors.remove(skillName)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        dispatcherThread = Thread { dispatchLoop() }.apply {
            name = "SkillParallelExecutor-Dispatcher"
            start()
        }
        AppLogger.d(TAG, "SkillParallelExecutor started with corePoolSize=${corePoolSize}, maxPoolSize=${maxPoolSize}")
    }

    fun stop() {
        isRunning = false
        dispatcherThread?.interrupt()
        dispatcherThread = null

        threadPool.shutdown()
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            threadPool.shutdownNow()
            Thread.currentThread().interrupt()
        }

        taskQueue.clear()
        activeTasks.clear()
        completedTasks.clear()

        AppLogger.d(TAG, "SkillParallelExecutor stopped")
    }

    fun submit(task: SkillTaskQueue.SkillTask): Boolean {
        if (!isRunning) {
            AppLogger.w(TAG, "Executor not running, task ${task}.id rejected")
            return false
        }

        val submitted = taskQueue.enqueue(task)
        if (submitted) {
            submittedCount.incrementAndGet()
            AppLogger.d(TAG, "Task ${task.id} submitted to queue")
        } else {
            AppLogger.w(TAG, "Task ${task.id} rejected - queue full")
        }
        return submitted
    }

    fun submitAndExecute(task: SkillTaskQueue.SkillTask): SkillTaskQueue.TaskResult {
        val submitted = submit(task)
        if (!submitted) {
            return SkillTaskQueue.TaskResult(
                taskId = task.id,
                skillName = task.skillName,
                success = false,
                error = "Queue full"
            )
        }

        while (true) {
            val result = completedTasks[task.id]
            if (result != null) {
                completedTasks.remove(task.id)
                return result
            }
            Thread.sleep(10)
        }
    }

    suspend fun submitAndAwait(task: SkillTaskQueue.SkillTask): SkillTaskQueue.TaskResult {
        submit(task)

        while (taskQueue.getTaskState(task.id) != SkillTaskQueue.TaskState.COMPLETED &&
               taskQueue.getTaskState(task.id) != SkillTaskQueue.TaskState.FAILED) {
            kotlinx.coroutines.delay(10)
        }

        return taskQueue.getTaskResult(task.id)
            ?: SkillTaskQueue.TaskResult(
                taskId = task.id,
                skillName = task.skillName,
                success = false,
                error = "Task result not found"
            )
    }

    fun submitAll(tasks: List<SkillTaskQueue.SkillTask>): Int {
        return taskQueue.enqueueAll(tasks)
    }

    fun cancelTask(taskId: String): Boolean {
        val cancelled = taskQueue.cancelTask(taskId)
        if (cancelled) {
            activeTasks[taskId]?.interrupt()
            activeTasks.remove(taskId)
            currentConcurrency.decrementAndGet()
        }
        return cancelled
    }

    fun getTaskState(taskId: String): SkillTaskQueue.TaskState? {
        return taskQueue.getTaskState(taskId)
    }

    fun getActiveTaskCount(): Int {
        return currentConcurrency.get()
    }

    fun getQueueSize(): Int {
        return taskQueue.size()
    }

    fun getStats(): ExecutorStats {
        val completed = completedCount.get()
        val totalTime = totalExecutionTime.get()
        val avgExecTime = if (completed > 0) totalTime / completed else 0L

        val elapsedSeconds = max(1L, System.currentTimeMillis() / 1000)
        val throughput = completed.toFloat() / elapsedSeconds

        val cpuUtil = if (totalCpuTime.get() > 0) {
            (totalCpuTime.get().toFloat() / (System.currentTimeMillis() * Runtime.getRuntime().availableProcessors())).coerceIn(0f, 1f)
        } else 0f

        return ExecutorStats(
            totalTasksSubmitted = submittedCount.get(),
            totalTasksCompleted = completedCount.get(),
            totalTasksFailed = failedCount.get(),
            activeThreads = currentConcurrency.get(),
            poolSize = threadPool.poolSize,
            queueSize = taskQueue.size(),
            largestPoolSize = threadPool.largestPoolSize,
            totalExecutionTimeMs = totalTime,
            averageExecutionTimeMs = avgExecTime,
            peakConcurrency = peakConcurrency.get(),
            throughput = throughput,
            cpuUtilization = cpuUtil
        )
    }

    fun resetStats() {
        submittedCount.set(0)
        completedCount.set(0)
        failedCount.set(0)
        totalExecutionTime.set(0)
        peakConcurrency.set(0)
        taskQueue.resetStats()
    }

    fun adjustPoolSize(newCoreSize: Int, newMaxSize: Int) {
        threadPool.corePoolSize = newCoreSize
        threadPool.maximumPoolSize = newMaxSize
        AppLogger.d(TAG, "Pool size adjusted: core=${newCoreSize}, max=${newMaxSize}")
    }

    fun getDynamicConcurrencyLevel(): Int {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val memoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val baseConcurrency = min(cpuCores * 2, maxPoolSize)
        val memoryBasedConcurrency = (memoryMb / 256).toInt()
        return max(2, min(baseConcurrency, memoryBasedConcurrency))
    }

    private fun dispatchLoop() {
        while (isRunning) {
            try {
                val availableSlots = getDynamicConcurrencyLevel() - currentConcurrency.get()
                if (availableSlots > 0 && !taskQueue.isEmpty()) {
                    repeat(min(availableSlots, taskQueue.size())) {
                        val task = taskQueue.dequeue() ?: return@repeat
                        executeTask(task)
                    }
                }
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in dispatch loop", e)
            }
        }
    }

    private fun executeTask(task: SkillTaskQueue.SkillTask) {
        val executor = taskExecutors[task.skillName] ?: return

        val thread = Thread {
            val startTime = System.currentTimeMillis()
            taskQueue.setTaskRunning(task.id)
            currentConcurrency.incrementAndGet()

            val current = currentConcurrency.get()
            while (current > peakConcurrency.get()) {
                peakConcurrency.compareAndSet(peakConcurrency.get(), current)
            }

            listeners.forEach { it.onTaskStarted(task.id, task.skillName) }

            try {
                val result = kotlinx.coroutines.runBlocking {
                    executor.execute(task)
                }

                val duration = System.currentTimeMillis() - startTime
                totalExecutionTime.addAndGet(duration)
                completedCount.incrementAndGet()

                taskQueue.setTaskCompleted(task.id, result)
                completedTasks[task.id] = taskQueue.getTaskResult(task.id) ?: SkillTaskQueue.TaskResult(
                    taskId = task.id,
                    skillName = task.skillName,
                    success = true,
                    result = result
                )

                listeners.forEach { it.onTaskCompleted(task.id, result) }

                AppLogger.d(TAG, "Task ${task.id} completed in ${duration}ms")
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                totalExecutionTime.addAndGet(duration)
                failedCount.incrementAndGet()

                val errorMsg = e.message ?: e.javaClass.simpleName
                taskQueue.setTaskFailed(task.id, errorMsg)
                completedTasks[task.id] = taskQueue.getTaskResult(task.id) ?: SkillTaskQueue.TaskResult(
                    taskId = task.id,
                    skillName = task.skillName,
                    success = false,
                    error = errorMsg
                )

                listeners.forEach { it.onTaskFailed(task.id, errorMsg) }

                AppLogger.e(TAG, "Task ${task.id} failed: ${errorMsg}", e)
            } finally {
                currentConcurrency.decrementAndGet()
                activeTasks.remove(task.id)
            }
        }.apply {
            name = "SkillExecutor-${task.id}"
            start()
        }

        activeTasks[task.id] = thread
    }

    fun getQueueStats(): SkillTaskQueue.QueueStats {
        return taskQueue.getStats()
    }

    fun getQueueMonitoringInfo(): SkillTaskQueue.MonitoringInfo {
        return taskQueue.getMonitoringInfo()
    }

    fun shutdownNow() {
        stop()
        INSTANCE = null
    }

    class SkillThreadFactory : java.util.concurrent.ThreadFactory {
        private val poolNumber = AtomicInteger(1)
        private val threadNumber = AtomicInteger(1)
        private val group = ThreadGroup("SkillExecutorPool-${poolNumber.getAndIncrement()}")

        override fun newThread(runnable: Runnable): Thread {
            return Thread(group, runnable, "SkillExecutor-${threadNumber.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = false
            }
        }
    }
}