package com.ai.assistance.`Apex agent`.ui.main.multiagent

import org.junit.Test
import org.junit.Assert.*

class IntelligentTaskAllocatorTest {

    private val allocator = IntelligentTaskAllocator()
    private val quantifier = TaskComplexityQuantifier()

    @Test
    fun `test allocateTask should return valid AllocationResult`() {
        // 初始化Agent
        val agents = SanxingAgentSystem.createStandardAgents()
        allocator.initializeAgentProfiles(agents)

        // 创建任务请求
        val taskDescription = "开发一个Android应用"
        val taskFeature = quantifier.quantifyTask(taskDescription)
        val request = IntelligentTaskAllocator.AllocationRequest(
            taskId = "test_task_1",
            taskDescription = taskDescription,
            taskFeature = taskFeature,
            requiredSkills = taskFeature.requiredSkills
        )

        val result = allocator.allocateTask(request)

        assertNotNull(result)
        assertEquals("test_task_1", result.taskId)
        assertNotNull(result.optimalAgent)
        assertTrue(result.backupAgents.size <= 2)
        assertNotNull(result.decisionReport)
        assertTrue(result.executionTime >= 0)
    }

    @Test
    fun `test allocateTask with no agents should return fallback`() {
        // 不初始化Agent，测试兜底逻辑
        val taskDescription = "开发一个Android应用"
        val taskFeature = quantifier.quantifyTask(taskDescription)
        val request = IntelligentTaskAllocator.AllocationRequest(
            taskId = "test_task_2",
            taskDescription = taskDescription,
            taskFeature = taskFeature,
            requiredSkills = taskFeature.requiredSkills
        )

        val result = allocator.allocateTask(request)

        assertNotNull(result)
        assertEquals("test_task_2", result.taskId)
        assertEquals("sanxing_libu_hr", result.optimalAgent.agentId)
        assertTrue(result.decisionReport.contains("智能分配失败"))
    }

    @Test
    fun `test updateAgentProfile should update profiles`() {
        val agent = SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.GONGBU_TECH)
        allocator.initializeAgentProfiles(listOf(agent))

        val update = AgentCapabilityProfile.CapabilityUpdate(
            taskId = "test_update",
            taskDescription = "编写代码",
            taskCategory = "coding",
            difficulty = 6,
            completionTime = 45000,
            qualityScore = 0.9,
            success = true,
            requiredSkills = listOf("编程")
        )

        allocator.updateAgentProfile(agent.agent.id, update)

        // 验证更新后能够正常分配
        val taskFeature = quantifier.quantifyTask("开发一个应用")
        val request = IntelligentTaskAllocator.AllocationRequest(
            taskId = "test_task_3",
            taskDescription = "开发一个应用",
            taskFeature = taskFeature,
            requiredSkills = taskFeature.requiredSkills
        )

        val result = allocator.allocateTask(request)
        assertNotNull(result)
    }

    @Test
    fun `test clearCache should clear allocations`() {
        val agents = SanxingAgentSystem.createStandardAgents()
        allocator.initializeAgentProfiles(agents)

        val taskDescription = "开发一个应用"
        val taskFeature = quantifier.quantifyTask(taskDescription)
        val request = IntelligentTaskAllocator.AllocationRequest(
            taskId = "test_task_4",
            taskDescription = taskDescription,
            taskFeature = taskFeature,
            requiredSkills = taskFeature.requiredSkills
        )

        // 第一次分配
        val result1 = allocator.allocateTask(request)
        assertNotNull(result1)
        assertEquals(1, allocator.getCacheSize())

        // 清除缓存
        allocator.clearCache()
        assertEquals(0, allocator.getCacheSize())

        // 第二次分配
        val result2 = allocator.allocateTask(request)
        assertNotNull(result2)
        assertEquals(1, allocator.getCacheSize())
    }

    @Test
    fun `test initializeAgentProfiles should initialize all agents`() {
        val agents = SanxingAgentSystem.createStandardAgents()
        allocator.initializeAgentProfiles(agents)

        // 验证能够正常分配任务
        val taskDescription = "开发一个应用"
        val taskFeature = quantifier.quantifyTask(taskDescription)
        val request = IntelligentTaskAllocator.AllocationRequest(
            taskId = "test_task_5",
            taskDescription = taskDescription,
            taskFeature = taskFeature,
            requiredSkills = taskFeature.requiredSkills
        )

        val result = allocator.allocateTask(request)
        assertNotNull(result)
        assertNotNull(result.optimalAgent)
    }
}

