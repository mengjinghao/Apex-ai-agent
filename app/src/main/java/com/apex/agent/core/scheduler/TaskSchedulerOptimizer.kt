package com.apex.agent.core.scheduler

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TaskSchedulerOptimizer(
    private val name: String = "scheduler-optimizer",
    private val maxConcurrency: Int = 8,
    private val queueCapacity: Int = 500,
    private val defaultTimeoutMs: Long = 30000L
) {
    data class Task(
        val id: String,
        val priority: Int = 0,
        val groupId: String? = null,
        val timeoutMs: Long = defaultTimeoutMs,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class ScheduledTask(
        val task: Task,
        val action: suspend () -> Result<Any>
    )

    data class TaskResult(
        val taskId: String,
        val success: Boolean,
        val result: Any? = null,
        val error: String? = null,
        val executionTimeMs: Long,
        val retryCount: Int = 0
    )

    data class SchedulerStats(
        val totalScheduled: Long,
        val totalCompleted: Long,
        val totalFailed: Long,
        val totalTimedOut: Long,
        val totalRetried: Long,
        val queueSize: Int,
        val activeCount: Int,
        val averageQueueWaitMs: Double,
        val averageExecutionMs: Double,
        val throughputPerMinute: Double
    )
        private val logger = LoggerFactory.getLogger("TaskSchedulerOptimizer-$name")
        private val taskQueue = PriorityBlockingQueue<ScheduledTask>(queueCapacity) { a, b ->
        b.task.priority.compareTo(a.task.priority)
    }
        private val activeTasks = ConcurrentHashMap<String, Job>()
        private val taskResults = ConcurrentHashMap<String, TaskResult>()
        private val taskDependencies = ConcurrentHashMap<String, List<String>>()
        private val completedDependencies = ConcurrentHashMap<String, MutableSet<String>>()
        private val groupCounters = ConcurrentHashMap<String, AtomicInteger>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val isRunning = AtomicBoolean(false)
        private val _activeCount = MutableStateFlow(0)
        val activeCount: StateFlow<Int> = _activeCount.asStateFlow()
        private val totalScheduled = AtomicLong(0)
        private val totalCompleted = AtomicLong(0)
        private val totalFailed = AtomicLong(0)
        private val totalTimedOut = AtomicLong(0)
        private val totalRetried = AtomicLong(0)
        private val totalQueueWaitNs = AtomicLong(0)
        private val totalExecutionNs = AtomicLong(0)
        private val waitSamples = AtomicInteger(0)
        private val execSamples = AtomicInteger(0)
        private val throughputCounter = AtomicLong(0)
        private val throughputTimer = AtomicLong(System.nanoTime())
        private val taskListeners = CopyOnWriteArrayList<(TaskResult) -> Unit>()
        fun addListener(listener: (TaskResult) -> Unit) {
        taskListeners.add(listener)
    }
        fun removeListener(listener: (TaskResult) -> Unit) {
        taskListeners.remove(listener)
    }
        fun schedule(task: Task, action: suspend () -> Result<Any>): Boolean {
        if (taskQueue.size >= queueCapacity) return false
        taskQueue.offer(ScheduledTask(task, action))
        totalScheduled.incrementAndGet()
        notifyQueue()
        return true
    }
        fun scheduleWithDependencies(
        task: Task,
        dependencies: List<String>,
        action: suspend () -> Result<Any>
    ): Boolean {
        taskDependencies[task.id] = dependencies
        completedDependencies[task.id] = mutableSetOf()
        if (dependencies.isEmpty()) {
            return schedule(task, action)
        }
        return true
    }
        fun scheduleBatch(tasks: List<Pair<Task, suspend () -> Result<Any>>>): Int {
        var scheduled = 0
        for ((task, action) in tasks) {
            if (schedule(task, action)) scheduled++
        }
        return scheduled
    }
        fun scheduleGroup(groupId: String, tasks: List<Pair<Task, suspend () -> Result<Any>>>): Int {
        val groupTasks = tasks.map { (task, action) ->
            task.copy(groupId = groupId) to action
        }
        return scheduleBatch(groupTasks)
    }
        fun cancel(taskId: String): Boolean {
        activeTasks[taskId]?.cancel()
        val removed = activeTasks.remove(taskId)
        taskQueue.removeIf { it.task.id == taskId }
        return removed != null
    }
        fun cancelGroup(groupId: String): Int {
        var count = 0
        activeTasks.entries.removeAll { (_, job) ->
            val task = taskQueue.find { it.task.groupId == groupId }
        if (task != null) { job.cancel(); count++ }
            task != null
        }
        taskQueue.removeIf { it.task.groupId == groupId }.also { count += it.size }
        return count
    }
        fun getResult(taskId: String): TaskResult? = taskResults[taskId]

    fun getGroupResults(groupId: String): List<TaskResult> {
        return taskResults.values.filter { it.taskId.startsWith(groupId) }
    }
        fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        scope.launch { processLoop() }
        scope.launch { throughputTracker() }
        logger.info("Scheduler optimizer started: $name")
    }
        fun stop() {
        isRunning.set(false)
        scope.cancel()
        logger.info("Scheduler optimizer stopped: $name")
    }
        fun shutdown() {
        stop()
        taskQueue.clear()
        activeTasks.clear()
        taskResults.clear()
        taskDependencies.clear()
        completedDependencies.clear()
    }
        fun getStats(): SchedulerStats {
        val completed = totalCompleted.get()
        val failed = totalFailed.get()
        val total = completed + failed
        val execSamp = execSamples.get()
        val waitSamp = waitSamples.get()
        val now = System.nanoTime()
        val windowSec = (now - throughputTimer.get()).toDouble() / 1_000_000_000.0
        return SchedulerStats(
            totalScheduled = totalScheduled.get(),
            totalCompleted = completed,
            totalFailed = failed,
            totalTimedOut = totalTimedOut.get(),
            totalRetried = totalRetried.get(),
            queueSize = taskQueue.size,
            activeCount = activeTasks.size,
            averageQueueWaitMs = if (waitSamp > 0) totalQueueWaitNs.get().toDouble() / waitSamp / 1_000_000.0 else 0.0,
            averageExecutionMs = if (execSamp > 0) totalExecutionNs.get().toDouble() / execSamp / 1_000_000.0 else 0.0,
            throughputPerMinute = if (windowSec > 0) throughputCounter.get().toDouble() / windowSec * 60.0 else 0.0
        )
    }
        private fun notifyQueue() {
        _activeCount.value = activeTasks.size + taskQueue.size
    }
        private suspend fun processLoop() {
        while (isRunning.get()) {
            try {
                if (activeTasks.size < maxConcurrency) {
                    val entry = taskQueue.poll(500, TimeUnit.MILLISECONDS)
        if (entry != null) {
                        if (checkDependencies(entry.task)) {
                            launchTask(entry)
                        } else {
                            taskQueue.offer(entry)
                        }
                    }
                } else {
                    delay(100)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) break
                logger.warn("Process loop error", e)
            }
        }
    }
        private fun checkDependencies(task: Task): Boolean {
        val deps = taskDependencies[task.id] ?: return true
        val completed = completedDependencies[task.id] ?: return false
        return deps.all { it in completed }
    }
        private fun launchTask(entry: ScheduledTask) {
        val job = scope.launch {
            val startWait = System.nanoTime()
        val startExec = System.nanoTime()
            _activeCount.value = activeTasks.size

            try {
                val waitTime = startExec - startWait
                totalQueueWaitNs.addAndGet(waitTime)
                waitSamples.incrementAndGet()
        val result = withTimeout(entry.task.timeoutMs) {
                    entry.action()
                }
        val execTime = System.nanoTime() - startExec
                totalExecutionNs.addAndGet(execTime)
                execSamples.incrementAndGet()
                throughputCounter.incrementAndGet()

                result.fold(
                    onSuccess = { value ->
                        val taskResult = TaskResult(
                            taskId = entry.task.id,
                            success = true,
                            result = value,
                            executionTimeMs = execTime / 1_000_000
                        )
                        taskResults[entry.task.id] = taskResult
                        totalCompleted.incrementAndGet()
                        completeDependencies(entry.task.id)
                        notifyListeners(taskResult)
                    },
                    onFailure = { error ->
                        handleTaskFailure(entry, error, execTime)
                    }
                )
            } catch (e: TimeoutCancellationException) {
                totalTimedOut.incrementAndGet()
                handleTaskFailure(entry, e, System.nanoTime() - startExec)
            } catch (e: Exception) {
                handleTaskFailure(entry, e, System.nanoTime() - startExec)
            } finally {
                activeTasks.remove(entry.task.id)
                _activeCount.value = activeTasks.size + taskQueue.size
            }
        }
        activeTasks[entry.task.id] = job
    }
        private suspend fun handleTaskFailure(entry: ScheduledTask, error: Throwable, execTime: Long) {
        val taskResult = TaskResult(
            taskId = entry.task.id,
            success = false,
            error = error.message,
            executionTimeMs = execTime / 1_000_000
        )
        taskResults[entry.task.id] = taskResult
        totalFailed.incrementAndGet()
        completeDependencies(entry.task.id)
        notifyListeners(taskResult)
        logger.debug("Task failed: {} - {}", entry.task.id, error.message)
    }
        private fun completeDependencies(taskId: String) {
        completedDependencies.entries.forEach { (_, completed) ->
            completed.add(taskId)
        }
    }
        private fun notifyListeners(result: TaskResult) {
        taskListeners.forEach { listener ->
            try { listener(result) } catch (e: Exception) { logger.warn("Listener failed", e) }
        }
    }
        private suspend fun throughputTracker() {
        while (isRunning.get()) {
            delay(60000)
            throughputCounter.set(0)
            throughputTimer.set(System.nanoTime())
        }
    }
}

class DependencyGraph(private val name: String = "dep-graph") {
    data class GraphNode(
        val id: String,
        val dependencies: Set<String>,
        val dependents: Set<String>
    )
        private val nodes = ConcurrentHashMap<String, GraphNode>()
        private val executionOrder = mutableListOf<String>()
        fun addNode(id: String, dependencies: List<String> = emptyList()) {
        val node = GraphNode(id, dependencies.toSet(), emptySet())
        nodes[id] = node
        for (dep in dependencies) {
            nodes.computeIfPresent(dep) { _, existing ->
                existing.copy(dependents = existing.dependents + id)
            }
        }
    }
        fun removeNode(id: String) {
        val node = nodes.remove(id)
        if (node != null) {
            for (dep in node.dependents) {
                nodes.computeIfPresent(dep) { _, existing ->
                    existing.copy(dependencies = existing.dependencies - id)
                }
            }
        }
    }
        fun getExecutionOrder(): List<String> {
        val visited = mutableSetOf<String>()
        val order = mutableListOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(nodeId: String) {
            if (nodeId in visited) return
            if (nodeId in visiting) throw CyclicDependencyException("Cycle detected at $nodeId")
            visiting.add(nodeId)
        val node = nodes[nodeId]
            if (node != null) {
                for (dep in node.dependencies) {
                    visit(dep)
                }
            }
            visiting.remove(nodeId)
            visited.add(nodeId)
            order.add(nodeId)
        }
        val allNodes = nodes.keys.toList()
        for (id in allNodes) {
            if (id !in visited) visit(id)
        }
        return order
    }
        fun getParallelLevels(): List<List<String>> {
        val order = getExecutionOrder()
        val levels = mutableListOf<MutableList<String>>()
        val nodeLevels = mutableMapOf<String, Int>()
        for (nodeId in order) {
            val node = nodes[nodeId] ?: continue
            val level = if (node.dependencies.isEmpty()) 0
            else (node.dependencies.mapNotNull { nodeLevels[it] }.maxOrNull() ?: 0) + 1
            nodeLevels[nodeId] = level
            while (levels.size <= level) levels.add(mutableListOf())
            levels[level].add(nodeId)
        }
        return levels
    }
        fun hasCycle(): Boolean {
        return try {
            getExecutionOrder()
            false
        } catch (e: CyclicDependencyException) {
            true
        }
    }
        fun getRootNodes(): List<String> = nodes.filter { it.value.dependencies.isEmpty() }.keys.toList()
        fun getLeafNodes(): List<String> = nodes.filter { it.value.dependents.isEmpty() }.keys.toList()
        fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "nodeCount" to nodes.size,
        "hasCycle" to hasCycle(),
        "maxDepth" to getParallelLevels().size
    )
        class CyclicDependencyException(message: String) : RuntimeException(message)
}

class WorkStealingExecutor(
    private val name: String = "work-stealing",
    private val workerCount: Int = Runtime.getRuntime().availableProcessors()
) {
    private data class WorkItem(
        val id: String,
        val action: () -> Any?,
        val createdAt: Long = System.nanoTime()
    )
        private val logger = LoggerFactory.getLogger("WorkStealingExecutor-$name")
        private val queues = Array(workerCount) { ConcurrentLinkedQueue<WorkItem>() }
        private val workers = mutableListOf<Thread>()
        private val isRunning = AtomicBoolean(false)
        private val completedCount = AtomicLong(0)
        private val stolenCount = AtomicLong(0)
        private val rejectedCount = AtomicLong(0)
        fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        for (i in 0 until workerCount) {
            val workerIndex = i
            val thread = Thread({
                while (isRunning.get()) {
                    processWorker(workerIndex)
                }
            }, "work-stealing-$name-$i").apply { isDaemon = true }
            workers.add(thread)
            thread.start()
        }
        logger.info("Work stealing executor started with $workerCount workers")
    }
        fun submit(id: String, action: () -> Any?): Boolean {
        val targetQueue = (id.hashCode() and Int.MAX_VALUE) % workerCount
        if (!queues[targetQueue].offer(WorkItem(id, action))) {
            rejectedCount.incrementAndGet()
        return false
        }
        return true
    }
        fun submitAll(items: List<Pair<String, () -> Any?>>): Int {
        var submitted = 0
        for ((id, action) in items) {
            if (submit(id, action)) submitted++
        }
        return submitted
    }
        fun stop() {
        isRunning.set(false)
        workers.forEach { it.interrupt() }
        workers.clear()
    }
        fun getMetrics(): Map<String, Any> = mapOf(
        "name" to name,
        "workerCount" to workerCount,
        "completed" to completedCount.get(),
        "stolen" to stolenCount.get(),
        "rejected" to rejectedCount.get()
    )
        private fun processWorker(index: Int) {
        var item = queues[index].poll()
        if (item != null) {
            executeItem(item)
        return
        }
        item = stealWork(index)
        if (item != null) {
            stolenCount.incrementAndGet()
            executeItem(item)
        return
        }
        try { Thread.sleep(1) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }
        private fun executeItem(item: WorkItem) {
        try {
            item.action()
            completedCount.incrementAndGet()
        } catch (e: Exception) {
            logger.warn("Work item {} failed", item.id, e)
        }
    }
        private fun stealWork(myIndex: Int): WorkItem? {
        for (i in 1 until workerCount) {
            val targetIndex = (myIndex + i) % workerCount
            val item = queues[targetIndex].poll()
        if (item != null) return item
        }
        return null
    }
}

class TaskBatcher<T, R>(
    private val name: String = "task-batcher",
    private val batchSize: Int = 50,
    private val flushIntervalMs: Long = 200L,
    private val processor: suspend (List<T>) -> List<R>
) {
    private val pending = ConcurrentLinkedQueue<Pair<String, T>>()
        private val deferredResults = ConcurrentHashMap<String, CompletableDeferred<R>>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val processed = AtomicLong(0)
        private val batches = AtomicLong(0)
        private val processingTimeNs = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(flushIntervalMs)
                processBatch()
            }
        }
    }

    suspend fun submit(key: String, item: T): R {
        val deferred = CompletableDeferred<R>()
        deferredResults[key] = deferred
        pending.add(key to item)
        if (pending.size >= batchSize) {
            processBatch()
        }
        return deferred.await()
    }

    suspend fun submitAll(items: List<Pair<String, T>>): List<R> {
        val deferreds = items.map { (key, _) ->
            val deferred = CompletableDeferred<R>()
            deferredResults[key] = deferred
            key to deferred
        }
        pending.addAll(items)
        if (pending.size >= batchSize) {
            processBatch()
        }
        return deferreds.map { it.second.await() }
    }
        fun shutdown() { scope.cancel() }
        private suspend fun processBatch() {
        if (pending.isEmpty()) return
        val batch = mutableListOf<Pair<String, T>>()
        while (batch.size < batchSize) {
            pending.poll()?.let { batch.add(it) } ?: break
        }
        if (batch.isEmpty()) return

        batches.incrementAndGet()
        val start = System.nanoTime()
        try {
            val results = processor(batch.map { it.second })
        for ((i, result) in results.withIndex()) {
                if (i < batch.size) {
                    deferredResults.remove(batch[i].first)?.complete(result)
                }
            }
            processed.addAndGet(batch.size.toLong())
        } catch (e: Exception) {
            for ((key, _) in batch) {
                deferredResults.remove(key)?.completeExceptionally(e)
            }
        }
        processingTimeNs.addAndGet(System.nanoTime() - start)
    }
}

class PriorityQueue<T : Comparable<T>>(
    private val initialCapacity: Int = 11,
    private val reverse: Boolean = false
) {
    private val heap = mutableListOf<T>()
        fun offer(element: T): Boolean {
        heap.add(element)
        siftUp(heap.size - 1)
        return true
    }
        fun poll(): T? {
        if (heap.isEmpty()) return null
        val result = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            siftDown(0)
        }
        return result
    }
        fun peek(): T? = heap.firstOrNull()
        val size: Int get() = heap.size
    fun isEmpty(): Boolean = heap.isEmpty()
        fun isNotEmpty(): Boolean = heap.isNotEmpty()
        fun clear() { heap.clear() }
        fun toList(): List<T> = heap.toList()
        private fun siftUp(index: Int) {
        var child = index
        while (child > 0) {
            val parent = (child - 1) / 2
            val compare = if (reverse) heap[parent] < heap[child] else heap[child] < heap[parent]
            if (compare) break
            swap(child, parent)
            child = parent
        }
    }
        private fun siftDown(index: Int) {
        var parent = index
        val half = heap.size / 2
        while (parent < half) {
            var child = 2 * parent + 1
            var right = child + 1
            if (right < heap.size) {
                val compare = if (reverse) heap[right] < heap[child] else heap[child] < heap[right]
                if (compare) child = right
            }
        val compare = if (reverse) heap[parent] < heap[child] else heap[child] < heap[parent]
            if (compare) break
            swap(parent, child)
            parent = child
        }
    }
        private fun swap(i: Int, j: Int) {
        val temp = heap[i]
        heap[i] = heap[j]
        heap[j] = temp
    }
}

class TimerWheel(
    private val name: String = "timer-wheel",
    private val tickDurationMs: Long = 100L,
    private val ticksPerWheel: Int = 512
) {
    data class TimerTask(
        val id: String,
        val delayMs: Long,
        val action: () -> Unit,
        var remainingTicks: Int = 0
    )
        private val logger = LoggerFactory.getLogger("TimerWheel-$name")
        private val wheel = Array(ticksPerWheel) { ConcurrentLinkedQueue<TimerTask>() }
        private val currentTick = AtomicInteger(0)
        private val isRunning = AtomicBoolean(false)
        private val thread = Thread({ run() }, "timer-wheel-$name").apply { isDaemon = true }
        private val scheduledCount = AtomicLong(0)
        private val firedCount = AtomicLong(0)
        private val cancelledCount = AtomicLong(0)
        fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        thread.start()
    }
        fun schedule(task: TimerTask): Boolean {
        val ticks = (task.delayMs / tickDurationMs).toInt()
        val targetTick = (currentTick.get() + ticks) % ticksPerWheel
        task.remainingTicks = ticks
        wheel[targetTick].add(task)
        scheduledCount.incrementAndGet()
        return true
    }
        fun cancel(taskId: String): Boolean {
        for (queue in wheel) {
            val removed = queue.removeIf { it.id == taskId }
        if (removed) { cancelledCount.incrementAndGet(); return true }
        }
        return false
    }
        fun stop() {
        isRunning.set(false)
        thread.interrupt()
    }
        fun getStats(): Map<String, Any> = mapOf(
        "name" to name,
        "scheduled" to scheduledCount.get(),
        "fired" to firedCount.get(),
        "cancelled" to cancelledCount.get(),
        "currentTick" to currentTick.get()
    )
        private fun run() {
        while (isRunning.get()) {
            try {
                val tick = currentTick.get()
        val queue = wheel[tick]
                val tasks = mutableListOf<TimerTask>()
                while (true) { queue.poll()?.let { tasks.add(it) } ?: break }
        for (task in tasks) {
                    task.remainingTicks--
                    if (task.remainingTicks <= 0) {
                        try { task.action(); firedCount.incrementAndGet() }
                        catch (e: Exception) { logger.warn("Timer task {} failed", task.id, e) }
                    } else {
                        val newTick = (tick + task.remainingTicks) % ticksPerWheel
                        wheel[newTick].add(task)
                    }
                }
                currentTick.set((tick + 1) % ticksPerWheel)
                Thread.sleep(tickDurationMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
