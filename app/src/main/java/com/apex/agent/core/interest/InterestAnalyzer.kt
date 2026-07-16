package com.apex.agent.core.interest

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 兴趣分析�?* 分析用户的兴趣偏�?*/
class InterestAnalyzer(private val context: Context) {
    private val TAG = "InterestAnalyzer"
    
    /**
     * 分析用户兴趣
     */
    suspend fun analyzeInterests(messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): InterestProfile = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始分析用户兴趣，消息数量: ${messages.size}")
        
        val interestProfile = InterestProfile()
        
        // 分析兴趣主题
        analyzeInterestTopics(messages, interestProfile)
        
        // 分析兴趣强度
        analyzeInterestIntensity(messages, interestProfile)
        
        // 分析兴趣趋势
        analyzeInterestTrend(messages, interestProfile)
        
        // 从用户画像中提取兴趣
        if (userProfile != null) {
            extractInterestsFromProfile(userProfile, interestProfile)
        }
        
        AppLogger.d(TAG, "用户兴趣分析完成: ${interestProfile}")
        interestProfile
    }
    
    /**
     * 分析兴趣主题
     */
    private fun analyzeInterestTopics(messages: List<ChatMessage>, profile: InterestProfile) {
        val interestScores = mutableMapOf<String, Int>()
        
        // 兴趣主题关键�?      val interestKeywords = mapOf(
            "技�?to listOf(
                "技�? "编程", "软件", "硬件", "开�? "代码", "算法", "数据库， "网络", "安全",
                "python", "java", "kotlin", "javascript", "c++", "go", "rust", "swift", "php", "ruby"
            ),
            "科技" to listOf(
                "科技", "人工智能", "AI", "机器学习", "深度学习", "大数据， "云计�? "区块�? "元宇�? "虚拟现实"
            ),
            "娱乐" to listOf(
                "娱乐", "电影", "音乐", "游戏", "体育", "旅游", "美食", "购物", "时尚", "艺术"
            ),
            "学习" to listOf(
                "学习", "教育", "知识", "课程", "考试", "培训", "读书", "研究", "论文", "学位"
            ),
            "工作" to listOf(
                "工作", "职场", "业务", "项目", "任务", "会议", "报告", "绩效", "晋升", "薪资"
            ),
            "生活" to listOf(
                "生活", "日常", "家庭", "朋友", "健康", "运动", "理财", "家居", "宠物", "育儿"
            ),
            "新闻" to listOf(
                "新闻", "时事", "政治", "经济", "社会", "国际", "国内", "政策", "事件", "趋势"
            ),
            "创意" to listOf(
                "创意", "设计", "艺术", "写作", "音乐", "绘画", "摄影", "视频", "动画", "游戏设计"
            )
        )
        
        // 分析用户消息
        val userMessages = messages.filter { it.sender == "user" }
        for (message in userMessages) {
            val content = message.content.lowercase()
            
            for ((interest, keywords) in interestKeywords) {
                for (keyword in keywords) {
                    if (content.contains(keyword.lowercase())) {
                        interestScores[interest] = interestScores.getOrDefault(interest, 0) + 1
                        break
                    }
                }
            }
        }
        
        if (interestScores.isNotEmpty()) {
            val topInterests = interestScores.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            profile.topInterests = topInterests
            profile.interestScores = interestScores
            profile.primaryInterest = topInterests.firstOrNull()
        }
    }
    
    /**
     * 分析兴趣强度
     */
    private fun analyzeInterestIntensity(messages: List<ChatMessage>, profile: InterestProfile) {
        val userMessages = messages.filter { it.sender == "user" }
        
        for ((interest, score) in profile.interestScores) {
            val intensity = when {
                score > 10 -> "�?
                score > 5 -> "�?
                else -> "�?
            }
            profile.interestIntensities[interest] = intensity
        }
        
        // 计算总体兴趣强度
        val totalScore = profile.interestScores.values.sum()
        profile.overallInterestLevel = when {
            totalScore > 30 -> "�?
            totalScore > 15 -> "�?
            else -> "�?
        }
    }
    
    /**
     * 分析兴趣趋势
     */
    private fun analyzeInterestTrend(messages: List<ChatMessage>, profile: InterestProfile) {
        if (messages.size < 10) return
        
        // 按时间分割消�?       val midPoint = messages.size / 2
        val earlyMessages = messages.subList(0, midPoint)
        val recentMessages = messages.subList(midPoint, messages.size)
        
        // 分析早期兴趣
        val earlyInterests = analyzeInterestTopics(earlyMessages)
        
        // 分析近期兴趣
        val recentInterests = analyzeInterestTopics(recentMessages)
        
        // 计算兴趣变化
        for (interest in profile.topInterests) {
            val earlyScore = earlyInterests.getOrDefault(interest, 0)
            val recentScore = recentInterests.getOrDefault(interest, 0)
            
            val trend = when {
                recentScore > earlyScore * 1.5 -> "上升"
                recentScore < earlyScore * 0.5 -> "下降"
                else -> "稳定"
            }
            
            profile.interestTrends[interest] = trend
        }
    }
    
    /**
     * 分析兴趣主题（辅助方法）
     */
    private fun analyzeInterestTopics(messages: List<ChatMessage>): Map<String, Int> {
        val interestScores = mutableMapOf<String, Int>()
        
        val interestKeywords = mapOf(
            "技�?to listOf("技�? "编程", "软件", "硬件", "开�? "代码"),
            "科技" to listOf("科技", "人工智能", "AI", "机器学习"),
            "娱乐" to listOf("娱乐", "电影", "音乐", "游戏"),
            "学习" to listOf("学习", "教育", "知识", "课程"),
            "工作" to listOf("工作", "职场", "业务", "项目"),
            "生活" to listOf("生活", "日常", "家庭", "朋友"),
            "新闻" to listOf("新闻", "时事", "政治", "经济"),
            "创意" to listOf("创意", "设计", "艺术", "写作")
        )
        
        val userMessages = messages.filter { it.sender == "user" }
        for (message in userMessages) {
            val content = message.content.lowercase()
            
            for ((interest, keywords) in interestKeywords) {
                for (keyword in keywords) {
                    if (content.contains(keyword.lowercase())) {
                        interestScores[interest] = interestScores.getOrDefault(interest, 0) + 1
                        break
                    }
                }
            }
        }
        
        return interestScores
    }
    
    /**
     * 从用户画像中提取兴趣
     */
    private fun extractInterestsFromProfile(profile: HonzonUserProfile, interestProfile: InterestProfile) {
        // 从需求偏好中提取兴趣
        profile.getDimension("需求偏的）?.let { preference ->
            val interests = preference.split("的）
            for (interest in interests) {
                if (interest.isNotBlank()) {
                    interestProfile.interestScores[interest] = interestProfile.interestScores.getOrDefault(interest, 0) + 5
                }
            }
        }
        
        // 从职业场景中提取兴趣
        profile.getDimension("职业场景")?.let { occupation ->
            val occupationInterests = mapOf(
                "程顺序to listOf("技�? "科技"),
                "设计�?to listOf("创意", "设计"),
                "教师" to listOf("学习", "教育"),
                "医生" to listOf("健康", "医学"),
                "学生" to listOf("学习", "教育")
            )
            
            for ((job, interests) in occupationInterests) {
                if (occupation.contains(job)) {
                    for (interest in interests) {
                        interestProfile.interestScores[interest] = interestProfile.interestScores.getOrDefault(interest, 0) + 3
                    }
                }
            }
        }
        
        // 更新top interests
        if (interestProfile.interestScores.isNotEmpty()) {
            val topInterests = interestProfile.interestScores.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            interestProfile.topInterests = topInterests
            interestProfile.primaryInterest = topInterests.firstOrNull()
        }
    }
    
    /**
     * 生成兴趣分析报告
     */
    suspend fun generateInterestReport(messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        val profile = analyzeInterests(messages, userProfile)
        
        buildString {
            appendLine("# 用户兴趣分析报告")
            appendLine()
            appendLine("## 兴趣概览")
            appendLine("- 主要兴趣: ${profile.primaryInterest ?: "未知"}")
            appendLine("- 兴趣水平: ${profile.overallInterestLevel}")
            appendLine("- 兴趣分布: ${profile.topInterests.joinToString("的）}")
            appendLine()
            
            appendLine("## 兴趣强度")
            profile.interestIntensities.forEach { (interest, intensity) ->
                appendLine("- ${interest}: ${intensity}")
            }
            appendLine()
            
            appendLine("## 兴趣趋势")
            profile.interestTrends.forEach { (interest, trend) ->
                appendLine("- ${interest}: ${trend}")
            }
        }
    }
}