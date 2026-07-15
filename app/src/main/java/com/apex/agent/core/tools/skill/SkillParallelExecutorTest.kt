package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SkillParallelExecutorTest {

    companion object {
        private const val TAG = "SkillParallelExecutorTest"
    }

    data class TestResult(
        val testName: String,
        val passed: Boolean,
        val durationMs: Long,
        val message: String,
        val details: Map<String, Any> = emptyMap()
    )
        fun runAllTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        results.add(testParallelExecution())
        results.add(testPriorityQueue())
        results.add(testResourceAllocation())
        results.add(testContextIsolation())
        results.add(testPerformanceImprovement())
        return results
    }
        fun testParallelExecution(): TestResult {
        val startTime = System.currentTimeMillis()
        var passed = false
        var message = ""
        val executor = SkillParallelExecutor.getInstance(
            corePoolSize = 4,
            maxPoolSize = 8,
            keepAliveMs = 60000,
            maxQueueSize = 100
        )
        val testLatch = CountDownLatch(5)
        val executedCount = AtomicInteger(0)

        executor.registerExecutor("test-skill", object : SkillParallelExecutor.TaskExecutor {
            override suspend fun execute(task: SkillTaskQueue.SkillTask): Any? {
                delay(100)
                executedCount.incrementAndGet()
        return "Result for ${task.id}"
            }
        })

        executor.start()

        try {
            val tasks = (1..5).map { i ->
                SkillTaskQueue.SkillTask(
                    id = "parallel-test-${i}",
                    skillName = "test-skill",
                    parameters = mapOf("index" to i)
                )
            }

            executor.submitAll(tasks)
        val completed = testLatch.await(10, TimeUnit.SECONDS)
        val stats = executor.getStats()
            passed = completed && executedCount.get() == 5 && stats.totalTasksCompleted >= 5
            message = if (passed) {
                "Parallel execution test passed: ${executedCount.get()} tasks executed"
            } else {
                "Parallel execution test failed: expected 5 tasks, got ${executedCount.get()}"
            }
        } catch (e: Exception) {
            message = "Test failed with exception: ${e.message}"
        } finally {
            executor.stop()
        }
        return TestResult(
            testName = "testParallelExecution",
            passed = passed,
            durationMs = System.currentTimeMillis() - startTime,
            message = message
        )
    }
        fun testPriorityQueue(): TestResult {
        val startTime = System.currentTimeMillis()
        var passed = false
        var message = ""
        val queue = SkillTaskQueue(maxQueueSize = 20, usePriorityQueue = true)
        val lowPriorityTask = SkillTaskQueue.SkillTask(
            id = "low-priority",
            skillName = "test",
            priority = SkillTaskQueue.Priority.LOW
        )
        val highPriorityTask = SkillTaskQueue.SkillTask(
            id = "high-priority",
            skillName = "test",
            priority = SkillTaskQueue.Priority.HIGH
        )
        val normalPriorityTask = SkillTaskQueue.SkillTask(
            id = "normal-priority",
            skillName = "test",
            priority = SkillTaskQueue.Priority.NORMAL
        )

        queue.enqueue(lowPriorityTask)
        queue.enqueue(highPriorityTask)
        queue.enqueue(normalPriorityTask)
        val first = queue.dequeue()
        passed = first?.id == "high-priority"

        message = if (passed) {
            "Priority queue test passed: high priority task dequeued first"
        } else {
            "Priority queue test failed: expected high-priority, got ${first?.id}"
        }
        return TestResult(
            testName = "testPriorityQueue",
            passed = passed,
            durationMs = System.currentTimeMillis() - startTime,
            message = message
        )
    }
        fun testResourceAllocation(): TestResult {
        val startTime = System.currentTimeMillis()
        var passed = false
        var message = ""
        val controller = SkillResourceController.getInstance()
        controller.startMonitoring()

        try {
            val allocated1 = controller.allocateResources("task-1", SkillResourceController.TaskComplexity.MEDIUM)
        val allocated2 = controller.allocateResources("task-2", SkillResourceController.TaskComplexity.HIGH)

            passed = allocated1 && allocated2

            val usage = controller.getCurrentResourceUsage()
        val allocations = controller.getTaskAllocation("task-1")

            passed = passed && allocations != null && allocations.isNotEmpty()

            controller.releaseResources("task-1")
            controller.releaseResources("task-2")

            message = if (passed) {
                "Resource allocation test passed: ${allocations?.size} resources allocated for task-1"
            } else {
                "Resource allocation test failed"
            }
        } catch (e: Exception) {
            message = "Test failed with exception: ${e.message}"
        } finally {
            controller.stopMonitoring()
        }
        return TestResult(
            testName = "testResourceAllocation",
            passed = passed,
            durationMs = System.currentTimeMillis() - startTime,
            message = message
        )
    }
        fun testContextIsolation(): TestResult {
        val startTime = System.currentTimeMillis()
        var passed = false
        var message = ""
        val context1 = SkillExecutionContext.builder()
            .setConfig(SkillExecutionContext.ContextConfig(maxConcurrentTasks = 2))
            .build()
        val context2 = SkillExecutionContext.builder()
            .setConfig(SkillExecutionContext.ContextConfig(maxConcurrentTasks = 4))
            .build()

        context1.initialize()
        context2.initialize()

        context1.start()
        context2.start()

        context1.putSharedData("key1", "value1")
        context2.putSharedData("key2", "value2")

        passed = context1.getSharedData("key1") == "value1" &&
                context2.getSharedData("key2") == "value2" &&
                context1.getSharedData("key2") == null &&
                context2.getSharedData("key1") == null

        context1.recordTaskStart("task-1")
        context2.recordTaskStart("task-2")

        context1.recordTaskCompletion("task-1", true, "result1")
        context2.recordTaskCompletion("task-2", true, "result2")

        passed = passed &&
                context1.getTaskMetrics("task-1")?.state == SkillExecutionContext.TaskState.COMPLETED &&
                context2.getTaskMetrics("task-2")?.state == SkillExecutionContext.TaskState.COMPLETED

        context1.complete()
        context2.complete()

        message = if (passed) {
            "Context isolation test passed: contexts properly isolated"
        } else {
            "Context isolation test failed"
        }
        return TestResult(
            testName = "testContextIsolation",
            passed = passed,
            durationMs = System.currentTimeMillis() - startTime,
            message = message
        )
    }
        fun testPerformanceImprovement(): TestResult {
        val startTime = System.currentTimeMillis()
        var passed = false
        var message = ""
        val executor = SkillParallelExecutor.getInstance(
            corePoolSize = 4,
            maxPoolSize = 8,
            keepAliveMs = 60000,
            maxQueueSize = 100
        )

        executor.registerExecutor("perf-test", object : SkillParallelExecutor.TaskExecutor {
            override suspend fun execute(task: SkillTaskQueue.SkillTask): Any? {
                delay(50)
        return "Done"
            }
        })

        executor.start()

        try {
            val taskCount = 10
            val tasks = (1..taskCount).map { i ->
                SkillTaskQueue.SkillTask(
                    id = "perf-task-${i}",
                    skillName = "perf-test"
                )
            }
        val sequentialStart = System.currentTimeMillis()
            tasks.forEach { task ->
                delay(50)
            }
        val sequentialDuration = System.currentTimeMillis() - sequentialStart

            executor.resetStats()
        val parallelStart = System.currentTimeMillis()
            executor.submitAll(tasks)

            Thread.sleep(2000)
        val stats = executor.getStats()
        val parallelDuration = System.currentTimeMillis() - parallelStart

            val speedup = sequentialDuration.toFloat() / parallelDuration.toFloat()

            passed = speedup > 1.5f

            message = if (passed) {
                "Performance test passed: ${String.format("%.2f", speedup)}x speedup achieved (sequential: ${sequentialDuration}ms, parallel: ${parallelDuration}ms)"
            } else {
                "Performance test: ${String.format("%.2f", speedup)}x speedup (threshold: 1.5x)"
            }

            AppLogger.d(TAG, message)
        } catch (e: Exception) {
            message = "Test failed with exception: ${e.message}"
        } finally {
            executor.stop()
        }
        return TestResult(
            testName = "testPerformanceImprovement",
            passed = passed,
            durationMs = System.currentTimeMillis() - startTime,
            message = message
        )
    }
        fun printTestResults(results: List<TestResult>) {
        AppLogger.d(TAG, "=== Skill Parallel Execution Test Results ===")
        results.forEach { result ->
            val status = if (result.passed) "PASS" else "FAIL"
            AppLogger.d(TAG, "[${status}] ${result.testName}: ${result.message} (${result.durationMs}ms)")
        }
        val passedCount = results.count { it.passed }
        AppLogger.d(TAG, "=== Total: ${passedCount}/${results.size} tests passed ===")
    }
}