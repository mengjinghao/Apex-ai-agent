package com.apex.agent.core.userprofile

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.agent.data.repository.MemoryRepository
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户画像管理服务
 * 负责用户画像的创建、更新、查询和管理
 */
class UserProfileManager private constructor(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val profileBuilder: UserProfileBuilder
) {
    private val TAG = "UserProfileManager"
    
    // 缓存用户画像
    private val profileCache = ConcurrentHashMap<String, HonzonUserProfile>()
    
    companion object {
        @Volatile private var INSTANCE: UserProfileManager? = null
        
        fun getInstance(context: Context, memoryRepository: MemoryRepository): UserProfileManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserProfileManager(
                    context.applicationContext,
                    memoryRepository,
                    UserProfileBuilder(context.applicationContext, memoryRepository)
                ).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * 获取用户画像
     */
    suspend fun getUserProfile(userId: String): HonzonUserProfile = withContext(Dispatchers.IO) {
        // 先从缓存获取
        profileCache[userId]?.let { return@withContext it }
        
        // 从存储获�?      val profile = memoryRepository.getHonzonProfile(userId)
        profileCache[userId] = profile
        profile
    }
    
    /**
     * 从对话历史构建用户画�?    */
    suspend fun buildProfileFromChatHistory(userId: String, chatMessages: List<ChatMessage>): HonzonUserProfile = withContext(Dispatchers.IO) {
        val profile = profileBuilder.buildProfileFromChatHistory(userId, chatMessages)
        profileCache[userId] = profile
        profile
    }
    
    /**
     * 从对话历史更新用户画�?    */
    suspend fun updateProfileFromChatHistory(userId: String, chatMessages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        val success = profileBuilder.updateProfileFromChatHistory(userId, chatMessages)
        if (success) {
            // 清除缓存，下次获取时重新加载
            profileCache.remove(userId)
        }
        success
    }
    
    /**
     * 更新用户画像指定维度
     */
    suspend fun updateProfileDimension(userId: String, dimension: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val success = memoryRepository.updateHonzonProfile(userId, dimension, value)
        if (success) {
            // 更新缓存
            val profile = profileCache[userId]
            if (profile != null) {
                profile.updateDimension(dimension, value)
            }
        }
        success
    }
    
    /**
     * 获取用户画像的有效维�?    */
    suspend fun getNonEmptyDimensions(userId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val profile = getUserProfile(userId)
        profile.getNonEmptyDimensions()
    }
    
    /**
     * 生成个性化策略提显�?    */
    suspend fun generatePersonalizedStrategyPrompt(userId: String, taskType: String): String = withContext(Dispatchers.IO) {
        val profile = getUserProfile(userId)
        memoryRepository.generatePersonalizedStrategyPrompt(profile, taskType)
    }
    
    /**
     * 检查用户画像是否有有效数据
     */
    suspend fun hasValidProfile(userId: String): Boolean = withContext(Dispatchers.IO) {
        val profile = getUserProfile(userId)
        profile.hasValidDimensions()
    }
    
    /**
     * 清除用户画像缓存
     */
    fun clearCache(userId: String? = null) {
        if (userId != null) {
            profileCache.remove(userId)
        } else {
            profileCache.clear()
        }
        AppLogger.d(TAG, "用户画像缓存已清理）
    }
    
    /**
     * 获取所有用户画像维�?    */
    fun getProfileDimensions(): List<String> {
        return HonzonUserProfile.USER_DIMENSIONS
    }
}