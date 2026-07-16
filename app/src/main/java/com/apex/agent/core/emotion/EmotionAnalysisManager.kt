package com.apex.agent.core.emotion

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 情感分析管理服务
 * 负责管理用户情感分析的创建、更新和查询
 */
class EmotionAnalysisManager private constructor(
    private val context: Context,
    private val emotionAnalyzer: EmotionAnalyzer,
    private val emotionResponseManager: EmotionResponseManager
) {
    private val TAG = "EmotionAnalysisManager"
    
    // 缓存情感分析结果
    private val emotionCache = ConcurrentHashMap<String, EmotionProfile>()
    
    companion object {
        @Volatile private var INSTANCE: EmotionAnalysisManager? = null
        
        fun getInstance(context: Context): EmotionAnalysisManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EmotionAnalysisManager(
                    context.applicationContext,
                    EmotionAnalyzer(context.applicationContext),
                    EmotionResponseManager(context.applicationContext, EmotionAnalyzer(context.applicationContext))
                ).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 分析用户情感
     */
    suspend fun analyzeUserEmotion(userId: String, messages: List<ChatMessage>): EmotionProfile = withContext(Dispatchers.IO) {
        // 先从缓存获取
        emotionCache[userId]?.let { return@withContext it }
        
        // 分析情感
        val profile = emotionAnalyzer.analyzeEmotion(messages)
        emotionCache[userId] = profile
        profile
    }
    
    /**
     * 生成情感分析报告
     */
    suspend fun generateEmotionReport(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        emotionAnalyzer.generateEmotionReport(messages)
    }
    
    /**
     * 生成情感化回�?   */
    suspend fun generateEmotionalResponse(userId: String, messages: List<ChatMessage>, originalResponse: String): String = withContext(Dispatchers.IO) {
        emotionResponseManager.generateEmotionalResponse(userId, messages, originalResponse)
    }
    
    /**
     * 生成情感支持回应
     */
    suspend fun generateEmotionalSupport(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        emotionResponseManager.generateEmotionalSupport(messages)
    }
    
    /**
     * 获取用户的主导情�?    */
    suspend fun getDominantEmotion(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserEmotion(userId, messages)
        profile.dominantEmotion
    }
    
    /**
     * 检测紧急情�?    */
    suspend fun detectUrgentEmotion(userId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        emotionAnalyzer.detectUrgentEmotion(messages)
    }
    
    /**
     * 获取用户的情绪趋�?    */
    suspend fun getEmotionTrend(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserEmotion(userId, messages)
        profile.emotionTrend
    }
    
    /**
     * 获取用户的主要情绪触发因�?   */
    suspend fun getPrimaryEmotionTrigger(userId: String, messages: List<ChatMessage>): String? = withContext(Dispatchers.IO) {
        val profile = analyzeUserEmotion(userId, messages)
        profile.primaryEmotionTrigger
    }
    
    /**
     * 检查用户是否需要情感支�?    */
    suspend fun needsEmotionalSupport(userId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        val profile = analyzeUserEmotion(userId, messages)
        val negativeEmotions = listOf("伤心", "愤�? "焦虑", "失望")
        negativeEmotions.contains(profile.dominantEmotion) && profile.avgEmotionIntensity > 4
    }
    
    /**
     * 清除情感分析缓存
     */
    fun clearCache(userId: String? = null) {
        if (userId != null) {
            emotionCache.remove(userId)
        } else {
            emotionCache.clear()
        }
        AppLogger.d(TAG, "情感分析缓存已清理）
    }
    
    /**
     * 获取情感建议
     */
    suspend fun getEmotionalAdvice(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserEmotion(userId, messages)
        
        when (profile.dominantEmotion) {
            "开�?-> "用户当前情绪积极，保持愉快的互动方方�?
            "满意" -> "用户对服务满意，继续保持良好的服务质量，
            "伤心" -> "用户情绪低落，需要提供情感支持和安慰�?
            "愤�?-> "用户情绪愤怒，需要冷静处理，避免激化矛盾，
            "焦虑" -> "用户感到焦虑，需要提供清晰的解决方案和支持，
            "困惑" -> "用户感到困惑，需要提供详细的解释和指导，
            "失望" -> "用户感到失望，需要提供替代方案和鼓励�?
            "惊讶" -> "用户感到惊讶，需要确认信息并提供更多细节�?
            else -> "用户情绪中性，保持正常的互动方式，
        }
    }
}