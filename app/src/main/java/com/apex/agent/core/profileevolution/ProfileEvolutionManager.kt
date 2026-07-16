package com.apex.agent.core.profileevolution

import android.content.Context
import com.apex.agent.core.normal.profile.UserProfileManager
import com.apex.agent.ui.screens.chat.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.agent.data.repository.MemoryRepository
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 画像演化管理?* 基于用户反馈自动更新用户画像
 */
class ProfileEvolutionManager private constructor(
    private val context: Context,
    private val feedbackAnalyzer: FeedbackAnalyzer,
    private val userProfileManager: UserProfileManager,
    private val memoryRepository: MemoryRepository
) {
    private val TAG = "ProfileEvolutionManager"
    
    companion object {
        @Volatile private var INSTANCE: ProfileEvolutionManager? = null
        
        fun getInstance(context: Context, memoryRepository: MemoryRepository): ProfileEvolutionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfileEvolutionManager(
                    context.applicationContext,
                    FeedbackAnalyzer(context.applicationContext),
                    UserProfileManager.getInstance(context.applicationContext, memoryRepository),
                    memoryRepository
                ).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 演化用户画像
     */
    suspend fun evolveUserProfile(userId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始演化用户画?${userId}")
        
        try {
            // 获取当前用户画像
            val currentProfile = userProfileManager.getUserProfile(userId)
            
            // 分析用户反馈
            val suggestions = feedbackAnalyzer.extractProfileUpdateSuggestions(messages, currentProfile)
            
            if (suggestions.isEmpty()) {
                AppLogger.d(TAG, "没有画像更新建议")
                return@withContext false
            }
            
            // 应用更新建议
            var updated = false
            for (suggestion in suggestions) {
                if (suggestion.confidence > 0.6) { // 只应用置信度高于0.6的创?                   val success = userProfileManager.updateProfileDimension(
                        userId = userId,
                        dimension = suggestion.dimension,
                        value = suggestion.newValue
                    )
                    if (success) {
                        updated = true
                        AppLogger.d(TAG, "更新用户画像维度: ${suggestion.dimension} = ${suggestion.newValue}")
                    }
                }
            }
            
            if (updated) {
                AppLogger.d(TAG, "用户画像演化完成")
            }
            
            updated
        } catch (e: Exception) {
            AppLogger.e(TAG, "演化用户画像失败", e)
            false
        }
    }
    
    /**
     * 生成画像演化报告
     */
    suspend fun generateEvolutionReport(userId: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val currentProfile = userProfileManager.getUserProfile(userId)
        val suggestions = feedbackAnalyzer.extractProfileUpdateSuggestions(messages, currentProfile)
        val feedbackReport = feedbackAnalyzer.generateFeedbackReport(messages)
        
        buildString {
            appendLine("# 用户画像演化报告")
            appendLine()
            appendLine("## 当前用户画像")
            currentProfile.getNonEmptyDimensions().forEach { (dimension, value) ->
                appendLine("- ${dimension}: ${value}")
            }
            appendLine()
            appendLine("## 反馈分析")
            appendLine(feedbackReport)
            appendLine()
            appendLine("## 画像更新建议")
            if (suggestions.isNotEmpty()) {
                suggestions.forEachIndexed { index, suggestion ->
                    appendLine("${index + 1}. ${suggestion.dimension}: ${suggestion.newValue} (置信?${(suggestion.confidence * 100).toInt()}%)")
                }
            } else {
                appendLine("无更新建的）
            }
        }
    }
    
    /**
     * 批量演化用户画像
     */
    suspend fun batchEvolveProfiles(userIds: List<String>, messagesMap: Map<String, List<ChatMessage>>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Boolean>()
        
        for (userId in userIds) {
            val messages = messagesMap[userId]
            if (messages != null) {
                val success = evolveUserProfile(userId, messages)
                results[userId] = success
            } else {
                results[userId] = false
            }
        }
        
        results
    }
    
    /**
     * 检查画像是否需要演?    */
    suspend fun needsEvolution(userId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        val suggestions = feedbackAnalyzer.extractProfileUpdateSuggestions(messages, userProfileManager.getUserProfile(userId))
        suggestions.isNotEmpty() && suggestions.any { it.confidence > 0.6 }
    }
    
    /**
     * 获取画像演化建议
     */
    suspend fun getEvolutionSuggestions(userId: String, messages: List<ChatMessage>): List<ProfileUpdateSuggestion> = withContext(Dispatchers.IO) {
        val currentProfile = userProfileManager.getUserProfile(userId)
        feedbackAnalyzer.extractProfileUpdateSuggestions(messages, currentProfile)
    }
    
    /**
     * 手动触发画像演化
     */
    suspend fun triggerEvolution(userId: String, messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        evolveUserProfile(userId, messages)
    }
    
    /**
     * 分析画像演化趋势
     */
    suspend fun analyzeEvolutionTrend(userId: String, historicalMessages: List<List<ChatMessage>>): String = withContext(Dispatchers.IO) {
        val trends = mutableListOf<String>()
        
        for (i in 1 until historicalMessages.size) {
            val previousMessages = historicalMessages[i-1]
            val currentMessages = historicalMessages[i]
            
            val previousSuggestions = feedbackAnalyzer.extractProfileUpdateSuggestions(
                previousMessages,
                userProfileManager.getUserProfile(userId)
            )
            
            val currentSuggestions = feedbackAnalyzer.extractProfileUpdateSuggestions(
                currentMessages,
                userProfileManager.getUserProfile(userId)
            )
            
            // 分析趋势
            if (currentSuggestions.size > previousSuggestions.size) {
                trends.add("画像更新频率增加")
            } else if (currentSuggestions.size < previousSuggestions.size) {
                trends.add("画像更新频率减少")
            } else {
                trends.add("画像更新频率稳定")
            }
        }
        
        if (trends.isEmpty()) {
            "无法分析演化趋势"
        } else {
            val mostCommonTrend = trends.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            "演化趋势: ${mostCommonTrend}"
        }
    }
}