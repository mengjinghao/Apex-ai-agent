package com.apex.agent.core.interest

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 兴趣管理服务
 * 负责管理用户兴趣分析和内容推�?*/
class InterestManagementManager private constructor(
    private val context: Context,
    private val interestAnalyzer: InterestAnalyzer,
    private val contentRecommender: ContentRecommender
) {
    private val TAG = "InterestManagementManager"
    
    // 缓存兴趣分析结果
    private val interestCache = ConcurrentHashMap<String, InterestProfile>()
    
    companion object {
        @Volatile private var INSTANCE: InterestManagementManager? = null
        
        fun getInstance(context: Context): InterestManagementManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: InterestManagementManager(
                    context.applicationContext,
                    InterestAnalyzer(context.applicationContext),
                    ContentRecommender(context.applicationContext, InterestAnalyzer(context.applicationContext))
                ).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 分析用户兴趣
     */
    suspend fun analyzeUserInterests(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): InterestProfile = withContext(Dispatchers.IO) {
        // 先从缓存获取
        interestCache[userId]?.let { return@withContext it }
        
        // 分析兴趣
        val profile = interestAnalyzer.analyzeInterests(messages, userProfile)
        interestCache[userId] = profile
        profile
    }
    
    /**
     * 生成内容推荐
     */
    suspend fun generateContentRecommendations(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): List<ContentRecommendation> = withContext(Dispatchers.IO) {
        contentRecommender.generateRecommendations(userId, messages, userProfile)
    }
    
    /**
     * 生成推荐摘要
     */
    suspend fun generateRecommendationSummary(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        contentRecommender.generateRecommendationSummary(userId, messages, userProfile)
    }
    
    /**
     * 生成兴趣对话开场白
     */
    suspend fun generateInterestOpening(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        contentRecommender.generateInterestOpening(userId, messages, userProfile)
    }
    
    /**
     * 获取用户的主要兴�?    */
    suspend fun getPrimaryInterest(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String? = withContext(Dispatchers.IO) {
        val profile = analyzeUserInterests(userId, messages, userProfile)
        profile.primaryInterest
    }
    
    /**
     * 获取用户的兴趣列�?   */
    suspend fun getTopInterests(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): List<String> = withContext(Dispatchers.IO) {
        val profile = analyzeUserInterests(userId, messages, userProfile)
        profile.topInterests
    }
    
    /**
     * 生成兴趣分析报告
     */
    suspend fun generateInterestReport(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        interestAnalyzer.generateInterestReport(messages, userProfile)
    }
    
    /**
     * 检查用户是否有明确的兴�?    */
    suspend fun hasClearInterests(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): Boolean = withContext(Dispatchers.IO) {
        val profile = analyzeUserInterests(userId, messages, userProfile)
        profile.primaryInterest != null && profile.overallInterestLevel != "�?
    }
    
    /**
     * 清除兴趣分析缓存
     */
    fun clearCache(userId: String? = null) {
        if (userId != null) {
            interestCache.remove(userId)
        } else {
            interestCache.clear()
        }
        AppLogger.d(TAG, "兴趣分析缓存已清理）
    }
    
    /**
     * 获取兴趣建议
     */
    suspend fun getInterestAdvice(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserInterests(userId, messages, userProfile)
        
        if (profile.primaryInterest != null) {
            "用户的{profile.primaryInterest}有浓厚兴趣，可以围绕这个话题展开深入讨论�?
        } else {
            "用户兴趣尚不明确，可以通过更多交流来了解用户的兴趣偏好�?        }
    }
}