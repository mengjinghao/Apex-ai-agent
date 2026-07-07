package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DynamicProfileUpdater {

    data class ProfileUpdateConfig(
        val agentId: String,
        val updateInterval: Long = 60, // 默认60??        val minSamples: Int = 5, // 最小样本数
        val learningRate: Double = 0.1, // 学习�?       val enabled: Boolean = true
    )

    data class TaskExecutionData(
        val taskId: String,
        val taskCategory: String,
        val difficulty: Int,
        val completionTime: Long,
        val qualityScore: Double,
        val success: Boolean,
        val timestamp: Long,
        val userFeedback: Int? = null // 1-5
    )

    private val profileManager = AgentCapabilityProfile()
    private val updateConfigs = ConcurrentHashMap<String, ProfileUpdateConfig>()
    private val executionData = ConcurrentHashMap<String, MutableList<TaskExecutionData>>()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(4)

    fun registerAgent(agentId: String, config: ProfileUpdateConfig = ProfileUpdateConfig(agentId)) {
        updateConfigs[agentId] = config
        executionData[agentId] = mutableListOf()
    }

    fun unregisterAgent(agentId: String) {
        updateConfigs.remove(agentId)
        executionData.remove(agentId)
    }

    fun recordTaskExecution(agentId: String, data: TaskExecutionData) {
        val dataList = executionData.getOrPut(agentId) { mutableListOf() }
        dataList.add(data)
        
        // 限制数据量，只保留最�?0??        if (dataList.size > 100) {
            executionData[agentId] = dataList.takeLast(100).toMutableList()
        }
    }

    fun startAutoUpdate() {
        scheduler.scheduleAtFixedRate({ autoUpdateProfiles() }, 0, 60, TimeUnit.SECONDS)
    }

    fun stopAutoUpdate() {
        scheduler.shutdown()
    }

    private fun autoUpdateProfiles() {
        updateConfigs.forEach { (agentId, config) ->
            if (config.enabled) {
                updateAgentProfile(agentId, config)
            }
        }
    }

    private fun updateAgentProfile(agentId: String, config: ProfileUpdateConfig) {
        val dataList = executionData[agentId]
        if (dataList == null || dataList.size < config.minSamples) {
            return
        }

        // 按任务类别分�?       val dataByCategory = dataList.groupBy { it.taskCategory }

        dataByCategory.forEach { (category, data) ->
            // 计算该类别的统计数据
            val successRate = data.count { it.success }.toDouble() / data.size
            val avgQualityScore = data.map { it.qualityScore }.average()
            val avgCompletionTime = data.map { it.completionTime }.average()
            val avgUserFeedback = data.filter { it.userFeedback != null }.map { it.userFeedback!! }.average()

            // 计算综合得分
            val综合得分 = successRate * 0.4 + avgQualityScore * 0.3 + 
                           (1 - avgCompletionTime / 60000) * 0.2 + // 60秒为基准
                           (if (avgUserFeedback > 0) avgUserFeedback / 5 * 0.1 else 0.05)

            // 更新能力评分
            val profile = profileManager.getProfile(agentId)
            if (profile != null) {
                val currentScore = profile.capabilityScores.getOrDefault(category, 1.0)
                val newScore = currentScore * (1 - config.learningRate) + 综合得分 * config.learningRate
                profile.capabilityScores[category] = newScore.coerceIn(0.1, 2.0)

                // 更新技能标�?               if (综合得分 > 0.7) {
                    // 添加相关技能标�?                   val skills = getSkillsForCategory(category)
                    skills.forEach { profile.skillTags.add(it) }
                }
            }
        }

        // 清理旧数�?       executionData[agentId] = dataList.takeLast(config.minSamples * 2).toMutableList()
    }

    private fun getSkillsForCategory(category: String): List<String> {
        return when (category) {
            "coding" -> listOf("编程", "算法", "调试")
            "writing" -> listOf("写作", "文案", "编辑")
            "research" -> listOf("研究", "分析", "调查")
            "design" -> listOf("设计", "创意", "用户体验")
            "data" -> listOf("数据分析", "统计", "数据可视�?
            "communication" -> listOf("沟？, "协调", "表达")
            "planning" -> listOf("计划", "组织", "项目管理")
            "testing" -> listOf("测试", "质量保证", "问题定位")
            "documentation" -> listOf("文档编写", "技术写�?
            else -> emptyList()
        }
    }

    fun getAgentPerformanceTrend(agentId: String, category: String, days: Int = 7): List<Double> {
        val dataList = executionData[agentId]
        if (dataList == null || dataList.isEmpty()) {
            return emptyList()
        }

        val cutoffTime = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
        val recentData = dataList.filter { it.timestamp >= cutoffTime && it.taskCategory == category }

        if (recentData.isEmpty()) {
            return emptyList()
        }

        // 按天分组
        val dataByDay = recentData.groupBy { it.timestamp / (24 * 60 * 60 * 1000) }

        return dataByDay.map { (_, dayData) ->
            dayData.map { it.qualityScore }.average()
        }
    }

    fun getOverallPerformance(agentId: String): Map<String, Double> {
        val dataList = executionData[agentId]
        if (dataList == null || dataList.isEmpty()) {
            return emptyMap()
        }

        val dataByCategory = dataList.groupBy { it.taskCategory }
        val performance = mutableMapOf<String, Double>()

        dataByCategory.forEach { (category, data) ->
            val successRate = data.count { it.success }.toDouble() / data.size
            val avgQualityScore = data.map { it.qualityScore }.average()
            performance[category] = successRate * 0.5 + avgQualityScore * 0.5
        }

        return performance
    }

    fun exportProfileData(agentId: String): String {
        val profile = profileManager.getProfile(agentId)
        val dataList = executionData[agentId]

        val sb = StringBuilder()
        sb.appendLine("===== Agent 能力画像数据 =====")
        if (profile != null) {
            sb.appendLine("Agent ID: ${profile.agentId}")
            sb.appendLine("Agent Name: ${profile.agentName}")
            sb.appendLine("Role: ${profile.role}")
            sb.appendLine()
            sb.appendLine("能力评分:")
            profile.capabilityScores.forEach { (category, score) ->
                sb.appendLine("  ${category}: ${String.format("%.2f", score)}")
            }
            sb.appendLine()
            sb.appendLine("技能标�?)
            sb.appendLine("  ${profile.skillTags.joinToString(", ")}")
            sb.appendLine()
            sb.appendLine("性能指标:")
            sb.appendLine("  总任务数: ${profile.performanceMetrics.totalTasks}")
            sb.appendLine("  成功任务�?${profile.performanceMetrics.completedTasks}")
            sb.appendLine("  成功�?${String.format("%.2f%%", profile.performanceMetrics.successRate * 100)}")
            sb.appendLine("  平均响应时间: ${profile.performanceMetrics.averageResponseTime}ms")
            sb.appendLine("  平均质量评分: ${String.format("%.2f", profile.performanceMetrics.averageQualityScore)}")
        }
        
        if (dataList != null && dataList.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("最近执行数�?)
            dataList.takeLast(5).forEachIndexed { index, data ->
                sb.appendLine("${index + 1}. 任务: ${data.taskCategory}, 难度: ${data.difficulty}")
                sb.appendLine("   状？ ${if (data.success) "成功" else "失败"}, 质量: ${String.format("%.2f", data.qualityScore)}")
                sb.appendLine("   时间: ${data.completionTime}ms, 反馈: ${data.userFeedback ?: "??}")
            }
        }
        
        return sb.toString()
    }

    fun cleanup() {
        stopAutoUpdate()
        updateConfigs.clear()
        executionData.clear()
    }
}
