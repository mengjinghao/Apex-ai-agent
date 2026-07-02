package com.ai.assistance.`Apex agent`.ui.main.multiagent

import org.junit.Test
import org.junit.Assert.*

class AgentCapabilityProfileTest {

    private val profileManager = AgentCapabilityProfile()

    @Test
    fun `test initializeProfile should create valid profile`() {
        val agent = SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.GONGBU_TECH)
        profileManager.initializeProfile(agent)

        val profile = profileManager.getProfile(agent.agent.id)
        assertNotNull(profile)
        assertEquals(agent.agent.id, profile?.agentId)
        assertEquals(agent.agent.name, profile?.agentName)
        assertFalse(profile?.capabilityScores?.isEmpty() ?: true)
        assertFalse(profile?.skillTags?.isEmpty() ?: true)
    }

    @Test
    fun `test updateProfile should update metrics`() {
        val agent = SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.LIBU_CONTENT)
        profileManager.initializeProfile(agent)

        val update = AgentCapabilityProfile.CapabilityUpdate(
            taskId = "test_task_1",
            taskDescription = "撰写产品文案",
            taskCategory = "writing",
            difficulty = 5,
            completionTime = 30000, // 5分钟
            qualityScore = 0.85,
            success = true,
            requiredSkills = listOf("写作", "文案")
        )

        profileManager.updateProfile(agent.agent.id, update)

        val profile = profileManager.getProfile(agent.agent.id)
        assertNotNull(profile)
        assertEquals(1, profile?.performanceMetrics?.totalTasks)
        assertEquals(1, profile?.performanceMetrics?.completedTasks)
        assertEquals(1.0, profile?.performanceMetrics?.successRate, 0.01)
        assertTrue(profile?.skillTags?.contains("写作") ?: false)
        assertTrue(profile?.skillTags?.contains("文案") ?: false)
    }

    @Test
    fun `test getAgentsByCapability should return sorted agents`() {
        // 初始化多个Agent
        val agents = listOf(
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.GONGBU_TECH),
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.HUBU_DATA),
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.LIBU_CONTENT)
        )

        agents.forEach { profileManager.initializeProfile(it) }

        // 更新能力评分
        val codingUpdate = AgentCapabilityProfile.CapabilityUpdate(
            taskId = "test_coding",
            taskDescription = "编写代码",
            taskCategory = "coding",
            difficulty = 7,
            completionTime = 60000,
            qualityScore = 0.9,
            success = true,
            requiredSkills = listOf("编程")
        )

        profileManager.updateProfile(agents[0].agent.id, codingUpdate)

        val codingAgents = profileManager.getAgentsByCapability("coding")
        assertFalse(codingAgents.isEmpty())
        // 工部应该排在前面，因为它的coding能力最强
        assertEquals(agents[0].agent.id, codingAgents[0].agentId)
    }

    @Test
    fun `test getAgentsBySkill should return agents with specific skill`() {
        val agent = SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.LIBU_CONTENT)
        profileManager.initializeProfile(agent)

        val update = AgentCapabilityProfile.CapabilityUpdate(
            taskId = "test_writing",
            taskDescription = "撰写文章",
            taskCategory = "writing",
            difficulty = 4,
            completionTime = 20000,
            qualityScore = 0.8,
            success = true,
            requiredSkills = listOf("写作", "文案")
        )

        profileManager.updateProfile(agent.agent.id, update)

        val writingAgents = profileManager.getAgentsBySkill("写作")
        assertFalse(writingAgents.isEmpty())
        assertEquals(agent.agent.id, writingAgents[0].agentId)
    }

    @Test
    fun `test getTopAgentsForTask should return best matched agents`() {
        // 初始化多个Agent
        val agents = listOf(
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.GONGBU_TECH),
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.HUBU_DATA),
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.LIBU_CONTENT)
        )

        agents.forEach { profileManager.initializeProfile(it) }

        // 更新能力评分
        val codingUpdate = AgentCapabilityProfile.CapabilityUpdate(
            taskId = "test_coding",
            taskDescription = "编写代码",
            taskCategory = "coding",
            difficulty = 6,
            completionTime = 45000,
            qualityScore = 0.85,
            success = true,
            requiredSkills = listOf("编程", "算法")
        )

        profileManager.updateProfile(agents[0].agent.id, codingUpdate)

        val topAgents = profileManager.getTopAgentsForTask("coding", listOf("编程", "算法"), 2)
        assertEquals(2, topAgents.size)
        assertEquals(agents[0].agent.id, topAgents[0].agentId)
    }

    @Test
    fun `test updateAgentPerformance should update metrics`() {
        val agent = SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.MENXIA_AUDIT)
        profileManager.initializeProfile(agent)

        val update = AgentCapabilityProfile.CapabilityUpdate(
            taskId = "test_audit",
            taskDescription = "审核方案",
            taskCategory = "testing",
            difficulty = 5,
            completionTime = 30000,
            qualityScore = 0.75,
            success = true,
            requiredSkills = listOf("审核")
        )

        profileManager.updateProfile(agent.agent.id, update)

        // 更新性能
        profileManager.updateAgentPerformance(agent.agent.id, "test_audit", true, 0.9, 25000)

        val profile = profileManager.getProfile(agent.agent.id)
        assertNotNull(profile)
        assertEquals(0.9, profile?.performanceMetrics?.averageQualityScore, 0.01)
        assertEquals(25000.0, profile?.performanceMetrics?.averageResponseTime, 0.01)
    }

    @Test
    fun `test getAllProfiles should return all profiles`() {
        val agents = listOf(
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.ZHONGSHU_DECISION),
            SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.MENXIA_AUDIT)
        )

        agents.forEach { profileManager.initializeProfile(it) }

        val profiles = profileManager.getAllProfiles()
        assertEquals(2, profiles.size)
    }

    @Test
    fun `test clearProfiles should remove all profiles`() {
        val agent = SanxingAgentSystem.createAgent(SanxingAgentSystem.AgentRole.SHANGSHU_EXECUTION)
        profileManager.initializeProfile(agent)

        var profiles = profileManager.getAllProfiles()
        assertEquals(1, profiles.size)

        profileManager.clearProfiles()

        profiles = profileManager.getAllProfiles()
        assertEquals(0, profiles.size)
    }
}

