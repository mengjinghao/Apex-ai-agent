package com.apex.agent.core.behavior

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 行为分析管理服务
 * 负责管理用户行为分析的创建、更新和查询
 */
class BehaviorAnalysisManager private constructor(
    private val context: Context,
    private val behaviorAnalyzer: BehaviorAnalyzer
) {
    private val TAG = "BehaviorAnalysisManager"
    
    // 缓存行为分析结果
    private val behaviorCache = ConcurrentHashMap<String, UserBehaviorProfile>()
    
    companion object {
        @Volatile private var INSTANCE: BehaviorAnalysisManager? = null
        
        fun getInstance(context: Context): BehaviorAnalysisManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BehaviorAnalysisManager(
                    context.applicationContext,
                    BehaviorAnalyzer(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 分析用户行为
     */
    suspend fun analyzeUserBehavior(userId: String, messages: List<ChatMessage>): UserBehaviorProfile = withContext(Dispatchers.IO) {
        // 先从缓存获取
        behaviorCache[userId]?.let { return@withContext it }
        
        // 分析行为
        val profile = behaviorAnalyzer.analyzeUserBehavior(messages)
        behaviorCache[userId] = profile
        profile
    }
    
    /**
     * 生成行为分析报告
     */
    suspend fun generateBehaviorReport(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        behaviorAnalyzer.generateBehaviorReport(messages)
    }
    
    /**
     * 获取用户行为分析结果
     */
    suspend fun getUserBehaviorProfile(userId: String, messages: List<ChatMessage>? = null): UserBehaviorProfile = withContext(Dispatchers.IO) {
        if (messages != null) {
            analyzeUserBehavior(userId, messages)
        } else {
            behaviorCache[userId] ?: UserBehaviorProfile()
        }
    }
    
    /**
     * 获取用户的主要使用场�?    */
    suspend fun getPrimaryUsageScenario(userId: String, messages: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        profile.primaryUsageScenario
    }
    
    /**
     * 获取用户的主导沟通风�?    */
    suspend fun getDominantCommunicationStyle(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        profile.dominantStyle
    }
    
    /**
     * 获取用户的活跃时间模�?   */
    suspend fun getUsageTimePattern(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        profile.usageTimePattern
    }
    
    /**
     * 预测用户的下一次活跃时�?    */
    suspend fun predictNextActiveTime(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        
        when (profile.usageTimePattern) {
            "上午" -> "预计下次活跃时间：明天上�?
            "下午" -> "预计下次活跃时间：今天下�?
            "晚上" -> "预计下次活跃时间：今天晚�?
            "凌晨" -> "预计下次活跃时间：今晚或明天凌晨"
            else -> "无法预测活跃时间"
        }
    }
    
    /**
     * 分析用户的交互偏�?    */
    suspend fun getInteractionPreference(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        
        buildString {
            append("交互偏好分析的）
            append("\n- 主导风格式{profile.dominantStyle}")
            append("\n- 平均响应时时间{profile.avgResponseTime.toInt()}的）
            append("\n- 平均消息长度。{profile.avgUserMessageLength.toInt()}字符")
            append("\n- 主要使用场景的{profile.primaryUsageScenario ?: "未知"}")
        }
    }
    
    /**
     * 清除行为分析缓存
     */
    fun clearCache(userId: String? = null) {
        if (userId != null) {
            behaviorCache.remove(userId)
        } else {
            behaviorCache.clear()
        }
        AppLogger.d(TAG, "行为分析缓存已清理）
    }
    
    /**
     * 检查用户是否为新用�?   */
    suspend fun isNewUser(userId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        profile.messageCount < 10
    }
    
    /**
     * 获取用户活跃度等着     */
    suspend fun getActivityLevel(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(userId, messages)
        
        when {
            profile.messageCount > 100 -> "高活�?
            profile.messageCount > 50 -> "中等活跃"
            profile.messageCount > 10 -> "低活�?
            else -> "新用�?        }
    }
}