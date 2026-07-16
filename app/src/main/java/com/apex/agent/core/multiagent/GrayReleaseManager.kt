package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class GrayReleaseManager {

    data class GrayReleaseConfig(
        val name: String,
        val description: String,
        val percentage: Int,
        val startDate: Long,
        val endDate: Long,
        val features: List<String>,
        val isActive: Boolean
    )

    data class UserFeedback(
        val userId: String,
        val sessionId: String,
        val feedback: String,
        val rating: Int, // 1-5
        val timestamp: Long
    )

    private val grayConfigs = ConcurrentHashMap<String, GrayReleaseConfig>()
    private val userFeedback = ConcurrentHashMap<String, UserFeedback>()
    private val monitoringDashboard = MonitoringDashboard()
    private val grayTestConfig = GrayTestConfig()
    private val feedbackCollector = FeedbackCollector()

    fun createGrayRelease(name: String, description: String, percentage: Int, features: List<String>): String {
        val config = GrayReleaseConfig(
            name = name,
            description = description,
            percentage = percentage,
            startDate = System.currentTimeMillis(),
            endDate = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000, // 默认7??            features = features,
            isActive = true
        )
        grayConfigs[name] = config
        grayTestConfig.setGrayPercentage(percentage)
        grayTestConfig.enableGrayTest()
        
        // 启用相关功能
        features.forEach {
            grayTestConfig.toggleFeature(it, true)
        }
        
        return name
    }

    fun getGrayRelease(name: String): GrayReleaseConfig? {
        return grayConfigs[name]
    }

    fun getAllGrayReleases(): List<GrayReleaseConfig> {
        return grayConfigs.values.toList()
    }

    fun updateGrayRelease(name: String, percentage: Int? = null, isActive: Boolean? = null) {
        val config = grayConfigs[name]
        if (config != null) {
            val updatedConfig = config.copy(
                percentage = percentage ?: config.percentage,
                isActive = isActive ?: config.isActive
            )
            grayConfigs[name] = updatedConfig
            
            if (percentage != null) {
                grayTestConfig.setGrayPercentage(percentage)
            }
            
            if (isActive != null) {
                if (isActive) {
                    grayTestConfig.enableGrayTest()
                } else {
                    grayTestConfig.disableGrayTest()
                }
            }
        }
    }

    fun stopGrayRelease(name: String) {
        val config = grayConfigs[name]
        if (config != null) {
            val updatedConfig = config.copy(isActive = false)
            grayConfigs[name] = updatedConfig
            grayTestConfig.disableGrayTest()
        }
    }

    fun deleteGrayRelease(name: String) {
        grayConfigs.remove(name)
        if (grayConfigs.isEmpty()) {
            grayTestConfig.disableGrayTest()
        }
    }

    fun shouldUserBeInGray(userId: String): Boolean {
        return grayTestConfig.shouldUserEnterGrayTest(userId)
    }

    fun isFeatureEnabled(featureName: String, userId: String): Boolean {
        if (!shouldUserBeInGray(userId)) {
            return false
        }
        return grayTestConfig.isFeatureEnabled(featureName)
    }

    fun recordUserFeedback(userId: String, sessionId: String, feedback: String, rating: Int) {
        val userFeedback = UserFeedback(
            userId = userId,
            sessionId = sessionId,
            feedback = feedback,
            rating = rating.coerceIn(1, 5),
            timestamp = System.currentTimeMillis()
        )
        this.userFeedback["${userId}:${sessionId}"] = userFeedback
    }

    fun getuserFeedback(userId: String): List<UserFeedback> {
        return userFeedback.values.filter { it.userId == userId }
    }

    fun getAllUserFeedback(): List<UserFeedback> {
        return userFeedback.values.toList()
    }

    fun getMonitoringDashboard(): MonitoringDashboard {
        return monitoringDashboard
    }

    fun getFeedbackCollector(): FeedbackCollector {
        return feedbackCollector
    }

    fun getGrayTestConfig(): GrayTestConfig {
        return grayTestConfig
    }

    fun exportReleaseReport(name: String): String {
        val config = grayConfigs[name]
        if (config == null) {
            return "灰度发布不存�?
        }

        val metrics = monitoringDashboard.getMetrics()
        val feedback = userFeedback.values.filter { it.timestamp >= config.startDate && it.timestamp <= config.endDate }
        val averageRating = if (feedback.isNotEmpty()) {
            feedback.map { it.rating }.average()
        } else {
            0.0
        }

        val sb = StringBuilder()
        sb.appendLine("===== 灰度发布报告 =====")
        sb.appendLine("发布名称: ${config.name}")
        sb.appendLine("描述: ${config.description}")
        sb.appendLine("灰度比例: ${config.percentage}%")
        sb.appendLine("开始时�?${config.startDate}")
        sb.appendLine("结束时间: ${config.endDate}")
        sb.appendLine("状？ ${if (config.isActive) "活跃" else "已停？}")
        sb.appendLine("功能: ${config.features.joinToString(", ")}")
        sb.appendLine()
        sb.appendLine("===== 监控指标 =====")
        sb.appendLine("总请求数: ${metrics.totalRequests}")
        sb.appendLine("成功请求�?${metrics.successfulRequests}")
        sb.appendLine("失败请求�?${metrics.failedRequests}")
        sb.appendLine("平均响应时间: ${metrics.averageResponseTime}ms")
        sb.appendLine("错误�?${String.format("%.2f%%", metrics.errorRate * 100)}")
        sb.appendLine("匹配准确�?${String.format("%.2f%%", metrics.matchingAccuracy * 100)}")
        sb.appendLine()
        sb.appendLine("===== 用户反馈 =====")
        sb.appendLine("反馈数量: ${feedback.size}")
        sb.appendLine("平均评分: ${String.format("%.1f", averageRating)}")
        sb.appendLine()
        feedback.forEachIndexed { index, item ->
            sb.appendLine("${index + 1}. 用户: ${item.userId}")
            sb.appendLine("   评分: ${item.rating}/5")
            sb.appendLine("   反馈: ${item.feedback}")
            sb.appendLine()
        }
        return sb.toString()
    }

    fun cleanUp() {
        grayConfigs.clear()
        userFeedback.clear()
        monitoringDashboard.resetMetrics()
        grayTestConfig.reset()
        feedbackCollector.clearAllFeedbacks()
    }
}
