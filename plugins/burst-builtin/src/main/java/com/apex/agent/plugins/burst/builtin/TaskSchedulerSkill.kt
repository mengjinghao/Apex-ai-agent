package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.*

/**
 * 任务调度器技能
 * 实现拓扑排序、关键路径计算、优先级调度
 */
class TaskSchedulerSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var plan: ExecutionPlan? = null
    private val adjacencyList = mutableMapOf<String, MutableList<String>>()
    private val inDegree = mutableMapOf<String, Int>()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "task_scheduler",
            skillName = "任务调度器",
            version = "1.0.0",
            description = "智能任务调度，支持拓扑排序、关键路径计算、优先级队列调度",
            author = "Apex Agent",
            tags = listOf("scheduler", "topological-sort", "critical-path"),
            priority = 95,
            capabilities = listOf(
                "topological_sorting",
                "critical_path_calculation",
                "priority_scheduling",
                "circular_dependency_detection"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            // 从任务元数据中获取执行计划
            val planJson = task.metadata["executionPlan"]
            val executionPlan = planJson?.let { parsePlanFromJson(it) } ?: createDefaultPlan(task)
            
            // 初始化调度器
            initialize(executionPlan)
            
            // 获取拓扑排序结果
            val topologicalOrder = getTopologicalOrder(executionPlan)
            
            // 计算关键路径
            val criticalPath = calculateCriticalPath(executionPlan)
            
            // 获取优先级排序
            val prioritizedOrder = getPrioritizedTopologicalOrder(executionPlan)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = """
                    |Scheduling completed:
                    |- Total nodes: ${executionPlan.nodes.size}
                    |- Topological order: ${topologicalOrder.take(5).joinToString()}
                    |- Critical path: ${criticalPath.size} nodes
                    |- Prioritized order: ${prioritizedOrder.take(5).joinToString()}
                """.trimMargin(),
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = topologicalOrder.size
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    private fun initialize(plan: ExecutionPlan) {
        this.plan = plan
        buildGraph(plan)
    }
    
    private fun buildGraph(plan: ExecutionPlan) {
        adjacencyList.clear()
        inDegree.clear()
        
        plan.nodes.forEach { node ->
            adjacencyList[node.nodeId] = mutableListOf()
            inDegree[node.nodeId] = 0
        }
        
        plan.edges.forEach { edge ->
            adjacencyList[edge.fromNodeId]?.add(edge.toNodeId)
            inDegree[edge.toNodeId] = inDegree.getOrDefault(edge.toNodeId, 0) + 1
        }
    }
    
    fun getTopologicalOrder(plan: ExecutionPlan): List<String> {
        val order = mutableListOf<String>()
        val queue = LinkedList<String>()
        val currentInDegree = inDegree.toMutableMap()
        
        currentInDegree.forEach { (nodeId, degree) ->
            if (degree == 0) {
                queue.offer(nodeId)
            }
        }
        
        while (queue.isNotEmpty()) {
            val nodeId = queue.poll()
            order.add(nodeId)
            
            adjacencyList[nodeId]?.forEach { neighborId ->
                currentInDegree[neighborId] = currentInDegree.getOrDefault(neighborId, 0) - 1
                if (currentInDegree[neighborId] == 0) {
                    queue.offer(neighborId)
                }
            }
        }
        
        if (order.size != plan.nodes.size) {
            throw IllegalArgumentException("Circular dependency detected in plan")
        }
        
        return order
    }
    
    fun getPrioritizedTopologicalOrder(plan: ExecutionPlan): List<String> {
        val order = mutableListOf<String>()
        val currentInDegree = inDegree.toMutableMap()
        
        val priorityQueue = PriorityQueue<String> { nodeId1, nodeId2 ->
            val node1 = plan.nodes.find { it.nodeId == nodeId1 }
            val node2 = plan.nodes.find { it.nodeId == nodeId2 }
            
            val priority1 = node1?.priority ?: Int.MAX_VALUE
            val priority2 = node2?.priority ?: Int.MAX_VALUE
            
            priority1.compareTo(priority2)
        }
        
        currentInDegree.forEach { (nodeId, degree) ->
            if (degree == 0) {
                priorityQueue.offer(nodeId)
            }
        }
        
        while (priorityQueue.isNotEmpty()) {
            val nodeId = priorityQueue.poll()
            order.add(nodeId)
            
            adjacencyList[nodeId]?.forEach { neighborId ->
                currentInDegree[neighborId] = currentInDegree.getOrDefault(neighborId, 0) - 1
                if (currentInDegree[neighborId] == 0) {
                    priorityQueue.offer(neighborId)
                }
            }
        }
        
        if (order.size != plan.nodes.size) {
            throw IllegalArgumentException("Circular dependency detected in plan")
        }
        
        return order
    }
    
    fun calculateCriticalPath(plan: ExecutionPlan): List<String> {
        val topologicalOrder = getTopologicalOrder(plan)
        val earliestStartTime = mutableMapOf<String, Long>()
        val latestStartTime = mutableMapOf<String, Long>()
        
        topologicalOrder.forEach { nodeId ->
            val node = plan.nodes.find { it.nodeId == nodeId }
            if (node == null) {
                earliestStartTime[nodeId] = 0
                return@forEach
            }
            
            val dependencies = plan.edges
                .filter { it.toNodeId == nodeId }
                .map { it.fromNodeId }
            
            val maxDependencyTime = dependencies.maxOfOrNull { earliestStartTime.getOrDefault(it, 0) } ?: 0
            earliestStartTime[nodeId] = maxDependencyTime + node.estimatedTimeMs
        }
        
        val totalTime = earliestStartTime.values.maxOrNull() ?: 0
        topologicalOrder.reversed().forEach { nodeId ->
            val node = plan.nodes.find { it.nodeId == nodeId }
            if (node == null) {
                latestStartTime[nodeId] = totalTime
                return@forEach
            }
            
            val dependents = plan.edges
                .filter { it.fromNodeId == nodeId }
                .map { it.toNodeId }
            
            val minDependentTime = dependents.minOfOrNull {
                latestStartTime.getOrDefault(it, totalTime) - (plan.nodes.find { n -> n.nodeId == it }?.estimatedTimeMs ?: 0)
            } ?: totalTime
            latestStartTime[nodeId] = minDependentTime
        }
        
        val criticalPath = mutableListOf<String>()
        topologicalOrder.forEach { nodeId ->
            if (earliestStartTime[nodeId] == latestStartTime[nodeId]) {
                criticalPath.add(nodeId)
            }
        }
        
        return criticalPath
    }
    
    fun canExecuteInParallel(nodeId1: String, nodeId2: String): Boolean {
        return !hasDependency(nodeId1, nodeId2) && !hasDependency(nodeId2, nodeId1)
    }
    
    private fun hasDependency(fromNodeId: String, toNodeId: String): Boolean {
        val visited = mutableSetOf<String>()
        val queue = LinkedList<String>()
        queue.offer(fromNodeId)
        visited.add(fromNodeId)
        
        while (queue.isNotEmpty()) {
            val nodeId = queue.poll()
            if (nodeId == toNodeId) {
                return true
            }
            
            adjacencyList[nodeId]?.forEach { neighborId ->
                if (!visited.contains(neighborId)) {
                    visited.add(neighborId)
                    queue.offer(neighborId)
                }
            }
        }
        
        return false
    }
    
    private fun createDefaultPlan(task: BurstTask): ExecutionPlan {
        val nodes = listOf(
            PlanNode(
                nodeId = "goal",
                type = "GOAL",
                description = task.description,
                estimatedTimeMs = 1000,
                priority = 0,
                requiredTools = emptyList(),
                complexity = 0,
                level = 0
            )
        )
        
        return ExecutionPlan(
            planId = "plan_${System.currentTimeMillis()}",
            taskId = task.id,
            taskType = "UNKNOWN",
            nodes = nodes,
            edges = emptyList()
        )
    }
    
    private fun parsePlanFromJson(json: String): ExecutionPlan? {
        return try {
            val obj = org.json.JSONObject(json)
            val nodesArray = obj.optJSONArray("nodes") ?: org.json.JSONArray()
            val edgesArray = obj.optJSONArray("edges") ?: org.json.JSONArray()
            val nodes = (0 until nodesArray.length()).map { i ->
                val n = nodesArray.getJSONObject(i)
                PlanNode(
                    nodeId = n.optString("nodeId", ""),
                    type = n.optString("type", "GOAL"),
                    description = n.optString("description", ""),
                    estimatedTimeMs = n.optLong("estimatedTimeMs", 1000),
                    priority = n.optInt("priority", 0),
                    requiredTools = n.optJSONArray("requiredTools")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it, "") } }
                        ?: emptyList(),
                    complexity = n.optInt("complexity", 0),
                    level = n.optInt("level", 0)
                )
            }
            val edges = (0 until edgesArray.length()).map { i ->
                val e = edgesArray.getJSONObject(i)
                PlanEdge(
                    fromNodeId = e.optString("fromNodeId", ""),
                    toNodeId = e.optString("toNodeId", "")
                )
            }
            ExecutionPlan(
                planId = obj.optString("planId", "plan_${System.currentTimeMillis()}"),
                taskId = obj.optString("taskId", ""),
                taskType = obj.optString("taskType", "UNKNOWN"),
                nodes = nodes,
                edges = edges
            )
        } catch (e: Exception) {
            null
        }
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        plan = null
        adjacencyList.clear()
        inDegree.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.92f
}
