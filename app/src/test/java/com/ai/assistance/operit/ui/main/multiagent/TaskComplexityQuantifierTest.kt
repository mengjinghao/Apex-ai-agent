package com.ai.assistance.`Apex agent`.ui.main.multiagent

import org.junit.Test
import org.junit.Assert.*

class TaskComplexityQuantifierTest {

    private val quantifier = TaskComplexityQuantifier()

    @Test
    fun `test quantifyTask should return valid TaskFeature`() {
        val taskDescription = "开发一个Android应用，包含登录、注册和数据展示功能"
        val feature = quantifier.quantifyTask(taskDescription)

        assertNotNull(feature)
        assertTrue(feature.difficulty in 1..10)
        assertTrue(feature.riskLevel in 1..5)
        assertTrue(feature.estimatedTime > 0)
        assertFalse(feature.requiredSkills.isEmpty())
    }

    @Test
    fun `test createSubTaskTickets should return valid tickets`() {
        val originalTask = "开发一个完整的应用"
        val subTasks = listOf(
            "开发登录功能",
            "开发注册功能",
            "开发数据展示功能"
        )

        val tickets = quantifier.createSubTaskTickets(originalTask, subTasks)

        assertEquals(3, tickets.size)
        tickets.forEach { ticket ->
            assertNotNull(ticket.id)
            assertNotNull(ticket.description)
            assertNotNull(ticket.features)
        }
    }

    @Test
    fun `test identifyCategory should return correct category`() {
        val codingTask = "编写Java代码实现排序算法"
        val writingTask = "撰写产品需求文档"
        val dataTask = "分析用户行为数据"

        val codingFeature = quantifier.quantifyTask(codingTask)
        val writingFeature = quantifier.quantifyTask(writingTask)
        val dataFeature = quantifier.quantifyTask(dataTask)

        assertEquals("coding", codingFeature.category)
        assertEquals("writing", writingFeature.category)
        assertEquals("data", dataFeature.category)
    }

    @Test
    fun `test calculateDifficulty should return appropriate difficulty`() {
        val simpleTask = "创建一个Hello World程序"
        val complexTask = "开发一个分布式系统，包含微服务架构、负载均衡和容错机制"

        val simpleFeature = quantifier.quantifyTask(simpleTask)
        val complexFeature = quantifier.quantifyTask(complexTask)

        assertTrue(simpleFeature.difficulty < complexFeature.difficulty)
        assertTrue(simpleFeature.difficulty <= 5)
        assertTrue(complexFeature.difficulty >= 6)
    }

    @Test
    fun `test assessRiskLevel should return appropriate risk`() {
        val lowRiskTask = "编写一个本地计算器应用"
        val highRiskTask = "开发一个处理用户敏感信息的金融应用"

        val lowRiskFeature = quantifier.quantifyTask(lowRiskTask)
        val highRiskFeature = quantifier.quantifyTask(highRiskTask)

        assertTrue(lowRiskFeature.riskLevel < highRiskFeature.riskLevel)
        assertTrue(lowRiskFeature.riskLevel <= 2)
        assertTrue(highRiskFeature.riskLevel >= 3)
    }

    @Test
    fun `test estimatedTime should be reasonable`() {
        val shortTask = "创建一个简单的HTML页面"
        val longTask = "开发一个完整的电商平台，包含前端、后端和数据库"

        val shortFeature = quantifier.quantifyTask(shortTask)
        val longFeature = quantifier.quantifyTask(longTask)

        assertTrue(shortFeature.estimatedTime < longFeature.estimatedTime)
        assertTrue(shortFeature.estimatedTime <= 60) // 1小时以内
        assertTrue(longFeature.estimatedTime >= 120) // 2小时以上
    }

    @Test
    fun `test requiredSkills should include relevant skills`() {
        val codingTask = "使用Python开发一个数据分析脚本"
        val designTask = "设计一个移动应用的用户界面"

        val codingFeature = quantifier.quantifyTask(codingTask)
        val designFeature = quantifier.quantifyTask(designTask)

        assertTrue(codingFeature.requiredSkills.contains("Python"))
        assertTrue(codingFeature.requiredSkills.contains("编程"))
        assertTrue(designFeature.requiredSkills.contains("设计"))
        assertTrue(designFeature.requiredSkills.contains("UI/UX设计"))
    }

    @Test
    fun `test exception handling should return fallback values`() {
        // 测试异常情况下的降级处理
        val feature = quantifier.quantifyTask("")
        
        assertNotNull(feature)
        assertEquals("other", feature.category)
        assertEquals(3, feature.difficulty)
        assertEquals(2, feature.riskLevel)
    }
}

