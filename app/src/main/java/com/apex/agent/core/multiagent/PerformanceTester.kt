package com.apex.agent.core.multiagent

import com.apex.util.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PerformanceTester {

    companion object {
        private const val TAG = "PerformanceTester"
    }

    data class PerformanceResult(
        val totalRequests: Int,
        val successfulRequests: Int,
        val failedRequests: Int,
        val averageResponseTime: Long,
        val throughput: Double,
        val errorRate: Double
    )

    fun testConcurrentAllocation(threadCount: Int, requestCount: Int): PerformanceResult {
        val allocator = IntelligentTaskAllocator()
        val quantifier = TaskComplexityQuantifier()
        
        // 初始化Agent
        val agents = SanxingAgentSystem.createStandardAgents()
        allocator.initializeAgentProfiles(agents)

        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(requestCount)
        val successful = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val totalTime = AtomicLong(0)

        val tasks = listOf(
            "开发一个Android应用",
            "撰写产品文档",
            "进行用户测试",
            "数据分析与可视化",
            "设计用户界面",
            "编写API接口",
            "优化数据库查。
            "实现安全认证",
            "部署应用到服务器",
            "编写测试用例"
        )

        val startTime = System.currentTimeMillis()

        for (i in 0 until requestCount) {
            executor.submit {
                try {
                    val taskDescription = tasks[i % tasks.size]
                    val taskFeature = quantifier.quantifyTask(taskDescription)
                    val request = IntelligentTaskAllocator.AllocationRequest(
                        taskId = "test_task_${i}",
                        taskDescription = taskDescription,
                        taskFeature = taskFeature,
                        requiredSkills = taskFeature.requiredSkills
                    )

                    val requestStartTime = System.currentTimeMillis()
                    val result = allocator.allocateTask(request)
                    val requestEndTime = System.currentTimeMillis()

                    totalTime.addAndGet(requestEndTime - requestStartTime)

                    if (result != null && result.optimalAgent != null) {
                        successful.incrementAndGet()
                    } else {
                        failed.incrementAndGet()
                    }
                } catch (e: Exception) {
                    failed.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            latch.await(60, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            AppLogger.e(TAG, "Performance test interrupted", e)
            Thread.currentThread().interrupt()
        }

        val endTime = System.currentTimeMillis()
        val totalDuration = endTime - startTime
        val avgResponseTime = if (successful.get() > 0) totalTime.get() / successful.get() else 0
        val throughput = (requestCount.toDouble() * 1000) / totalDuration
        val errorRate = failed.get().toDouble() / requestCount

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        return PerformanceResult(
            totalRequests = requestCount,
            successfulRequests = successful.get(),
            failedRequests = failed.get(),
            averageResponseTime = avgResponseTime,
            throughput = throughput,
            errorRate = errorRate
        )
    }

    fun testScalability(maxThreads: Int, requestCount: Int) {
        println("===== 可扩展性测===-")
        println("线程数\t成功数\t失败数\t平均响应时间(ms)\t吞吐？req/s)\t错误。
        println("-".repeat(80))

        for (threads in 1..maxThreads step 2) {
            val result = testConcurrentAllocation(threads, requestCount)
            println("${threads}\t${result.successfulRequests}\t${result.failedRequests}\t${result.averageResponseTime}\t${String.format("%.2f", result.throughput)}\t${String.format("%.2f%%", result.errorRate * 100)}")
        }
    }

    fun testLatencyDistribution(requestCount: Int) {
        val allocator = IntelligentTaskAllocator()
        val quantifier = TaskComplexityQuantifier()
        
        val agents = SanxingAgentSystem.createStandardAgents()
        allocator.initializeAgentProfiles(agents)

        val latencies = mutableListOf<Long>()
        val tasks = listOf(
            "开发一个Android应用",
            "撰写产品文档",
            "进行用户测试",
            "数据分析与可视化"
        )

        for (i in 0 until requestCount) {
            val taskDescription = tasks[i % tasks.size]
            val taskFeature = quantifier.quantifyTask(taskDescription)
            val request = IntelligentTaskAllocator.AllocationRequest(
                taskId = "test_task_${i}",
                taskDescription = taskDescription,
                taskFeature = taskFeature,
                requiredSkills = taskFeature.requiredSkills
            )

            val startTime = System.currentTimeMillis()
            allocator.allocateTask(request)
            val endTime = System.currentTimeMillis()
            latencies.add(endTime - startTime)
        }

        latencies.sort()
        val p50 = latencies[(requestCount * 0.5).toInt()]
        val p90 = latencies[(requestCount * 0.9).toInt()]
        val p99 = latencies[(requestCount * 0.99).toInt()]

        println("===== 延迟分布测试 ====-")
        println("请求${requestCount}")
        println("P50延迟: ${p50}ms")
        println("P90延迟: ${p90}ms")
        println("P99延迟: ${p99}ms")
        println("平均延迟: ${latencies.average()}ms")
        println("最大延${latencies.maxOrNull()}ms")
        println("最小延${latencies.minOrNull()}ms")
    }

    fun testCachingPerformance(requestCount: Int) {
        val allocator = IntelligentTaskAllocator()
        val quantifier = TaskComplexityQuantifier()
        
        val agents = SanxingAgentSystem.createStandardAgents()
        allocator.initializeAgentProfiles(agents)

        val taskDescription = "开发一个Android应用"
        val taskFeature = quantifier.quantifyTask(taskDescription)
        val request = IntelligentTaskAllocator.AllocationRequest(
            taskId = "test_task_cache",
            taskDescription = taskDescription,
            taskFeature = taskFeature,
            requiredSkills = taskFeature.requiredSkills
        )

        // 第一次请求（无缓存）
        val startTime1 = System.currentTimeMillis()
        allocator.allocateTask(request)
        val endTime1 = System.currentTimeMillis()
        val timeWithoutCache = endTime1 - startTime1

        // 第二次请求（有缓存）
        val startTime2 = System.currentTimeMillis()
        allocator.allocateTask(request)
        val endTime2 = System.currentTimeMillis()
        val timeWithCache = endTime2 - startTime2

        println("===== 缓存性能测试 ====-")
        println("无缓存时${timeWithoutCache}ms")
        println("有缓存时${timeWithCache}ms")
        println("缓存提升: ${String.format("%.2f%%", (1 - timeWithCache.toDouble() / timeWithoutCache) * 100)}")
    }
}
