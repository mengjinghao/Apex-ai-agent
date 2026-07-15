package com.apex.agent.core.concurrent

import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AdaptiveThreadPool private constructor(
    private val name: String,
    private val corePoolSize: Int,
    private val maximumPoolSize: Int,
    private val keepAliveTime: Long,
    private val unit: TimeUnit,
    private val workQueue: BlockingQueue<Runnable>,
    private val threadFactory: ThreadFactory,
    private val rejectionHandler: RejectedExecutionHandler,
    private val adaptiveConfig: AdaptiveConfig
) : ThreadPoolExecutor(
    corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, rejectionHandler
) {
    data class AdaptiveConfig(
        val adjustmentIntervalMs: Long = 5000L,
        val utilizationTarget: Double = 0.75,
        val minCoreThreads: Int = 2,
        val maxCoreThreads: Int = 64,
        val scaleUpThreshold: Double = 0.8,
        val scaleDownThreshold: Double = 0.3,
        val scaleStep: Int = 2,
        val historySamples: Int = 10
    )

    data class ThreadPoolMetrics(
        val poolSize: Int,
        val activeCount: Int,
        val corePoolSize: Int,
        val maximumPoolSize: Int,
        val queueSize: Int,
        val completedTaskCount: Long,
        val taskCount: Long,
        val utilizationRatio: Double,
        val rejectedCount: Long,
        val averageQueueWaitTimeNs: Long,
        val totalIdleTimeMs: Long
    )
        private val logger = LoggerFactory.getLogger("AdaptiveThreadPool-$name")
        private val rejectedCounter = AtomicLong(0)
        private val totalQueueWaitTimeNs = AtomicLong(0)
        private val queueWaitSamples = AtomicInteger(0)
        private val totalIdleTimeMs = AtomicLong(0)
        private val historyUtilization = ConcurrentLinkedQueue<Double>()
        private val adjustmentLock = ReentrantLock()
        private val completionLatch = AtomicInteger(0)
        private val utilizationTracker = AtomicLong(0)
        private val trackerIntervalNs = TimeUnit.MILLISECONDS.toNanos(100)
        private val adjustmentScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "adaptive-adjuster-$name").apply { isDaemon = true }
    }

    init {
        adjustmentScheduler.scheduleAtFixedRate(
            { adjustPoolSize() },
            adaptiveConfig.adjustmentIntervalMs,
            adaptiveConfig.adjustmentIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    override fun beforeExecute(t: Thread, r: Runnable) {
        super.beforeExecute(t, r)
        if (r is FutureTask<*> || r is AdaptiveTask) {
            val task = if (r is AdaptiveTask) r else null
            val startTime = task?.enqueueTime ?: System.nanoTime()
        val waitTime = System.nanoTime() - startTime
            totalQueueWaitTimeNs.addAndGet(waitTime)
            queueWaitSamples.incrementAndGet()
        }
    }

    override fun afterExecute(r: Runnable?, t: Throwable?) {
        super.afterExecute(r, t)
        completionLatch.incrementAndGet()
    }

    override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
        rejectedCounter.incrementAndGet()
        super.rejectedExecution(r, executor)
    }
        fun submitTask(task: () -> Unit): Future<*> {
        val futureTask = AdaptiveTask(task, System.nanoTime())
        return submit(futureTask)
    }
        fun <T> submitTask(task: () -> T): Future<T> {
        val future = object : FutureTask<T>(Callable { task() }) {
            val enqueueTime = System.nanoTime()
        }
        execute(future)
        return future
    }
        fun getMetrics(): ThreadPoolMetrics {
        val poolSize = poolSize
        val active = activeCount
        val utilization = if (poolSize > 0) active.toDouble() / poolSize else 0.0
        val avgWaitNs = if (queueWaitSamples.get() > 0)
            totalQueueWaitTimeNs.get() / queueWaitSamples.get() else 0L
        val total = completedTaskCount
        val estimatedIdleMs = if (total > 0) totalIdleTimeMs.get() / max(1, threadCount) else 0L
        return ThreadPoolMetrics(
            poolSize = poolSize,
            activeCount = active,
            corePoolSize = corePoolSize,
            maximumPoolSize = maximumPoolSize,
            queueSize = queue.size,
            completedTaskCount = total,
            taskCount = taskCount,
            utilizationRatio = utilization,
            rejectedCount = rejectedCounter.get(),
            averageQueueWaitTimeNs = avgWaitNs,
            totalIdleTimeMs = estimatedIdleMs
        )
    }
        fun shutdownGracefully(timeoutMs: Long = 5000L) {
        adjustmentScheduler.shutdown()
        shutdown()
        try {
            if (!awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                shutdownNow()
            }
        } catch (e: InterruptedException) {
            shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
        private fun adjustPoolSize() {
        if (!adjustmentLock.tryLock()) return
        try {
            val poolSize = poolSize
            val active = activeCount
            val queueSize = queue.size
            val utilization = if (poolSize > 0) active.toDouble() / poolSize else 0.0

            historyUtilization.add(utilization)
        if (historyUtilization.size > adaptiveConfig.historySamples) {
                historyUtilization.poll()
            }
        val avgUtilization = historyUtilization.average()
        val config = adaptiveConfig

            when {
                avgUtilization > config.scaleUpThreshold && queueSize > 0 -> {
                    val newCore = min(corePoolSize + config.scaleStep, config.maxCoreThreads)
        val newMax = min(maximumPoolSize + config.scaleStep, config.maxCoreThreads * 2)
        if (newCore > corePoolSize || newMax > maximumPoolSize) {
                        setCorePoolSize(newCore)
                        setMaximumPoolSize(newMax)
                        logger.info("Scaled UP pool $name: core=$newCore max=$newMax (util={:.2f}, queue={})", avgUtilization, queueSize)
                    }
                }
                avgUtilization < config.scaleDownThreshold && queueSize == 0 -> {
                    val newCore = max(corePoolSize - config.scaleStep, config.minCoreThreads)
        val newMax = max(maximumPoolSize - config.scaleStep, newCore)
        if (newCore < corePoolSize || newMax < maximumPoolSize) {
                        setCorePoolSize(newCore)
                        setMaximumPoolSize(newMax)
                        logger.info("Scaled DOWN pool $name: core=$newCore max=$newMax (util={:.2f})", avgUtilization)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Adjustment failed for pool $name", e)
        } finally {
            adjustmentLock.unlock()
        }
    }
        private class AdaptiveTask(
        private val action: () -> Unit,
        val enqueueTime: Long
    ) : Runnable {
        override fun run() = action()
    }

    companion object {
        fun builder(name: String = "default"): Builder = Builder(name)
        class Builder(val name: String) {
            private var corePoolSize: Int = Runtime.getRuntime().availableProcessors()
        private var maxPoolSize: Int = corePoolSize * 4
            private var keepAliveTime: Long = 60L
            private var unit: TimeUnit = TimeUnit.SECONDS
            private var workQueue: BlockingQueue<Runnable> = LinkedBlockingQueue(1000)
        private var threadFactory: ThreadFactory = DefaultThreadFactory(name)
        private var rejectionHandler: RejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
        private var adaptiveConfig: AdaptiveConfig = AdaptiveConfig()
        fun corePoolSize(size: Int) = apply { this.corePoolSize = size }
        fun maxPoolSize(size: Int) = apply { this.maxPoolSize = size }
        fun keepAlive(time: Long, timeUnit: TimeUnit) = apply { this.keepAliveTime = time; this.unit = timeUnit }
        fun workQueue(queue: BlockingQueue<Runnable>) = apply { this.workQueue = queue }
        fun threadFactory(factory: ThreadFactory) = apply { this.threadFactory = factory }
        fun rejectionHandler(handler: RejectedExecutionHandler) = apply { this.rejectionHandler = handler }
        fun adaptiveConfig(config: AdaptiveConfig) = apply { this.adaptiveConfig = config }
        fun build(): AdaptiveThreadPool = AdaptiveThreadPool(
                name, corePoolSize, maxPoolSize, keepAliveTime, unit,
                workQueue, threadFactory, rejectionHandler, adaptiveConfig
            )
        }
        private class DefaultThreadFactory(private val name: String) : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread {
                return Thread(r, "adaptive-$name-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }
        }
        val threadCount: Int get() = Runtime.getRuntime().availableProcessors()
    }
}
