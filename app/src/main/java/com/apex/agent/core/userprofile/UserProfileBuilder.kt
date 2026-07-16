package com.apex.agent.core.userprofile

import android.content.Context
import com.apex.agent.ui.screens.chat.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.agent.data.repository.MemoryRepository
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * 用户画像自动构建系统
 * 从对话历史中自动提取用户特征，构建和更新用户画像
 */
class UserProfileBuilder(
    private val context: Context,
    private val memoryRepository: MemoryRepository
) {
    private val TAG = "UserProfileBuilder"
    
    /**
     * 从对话历史构建用户画?    */
    suspend fun buildProfileFromChatHistory(userId: String, chatMessages: List<ChatMessage>): HonzonUserProfile = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始从对话历史构建用户画像: ${userId}, 消息数量: ${chatMessages.size}")
        
        // 获取现有用户画像
        val profile = memoryRepository.getHonzonProfile(userId)
        
        // 分析对话内容
        analyzeChatContent(chatMessages, profile)
        
        // 分析对话模式
        analyzeChatPatterns(chatMessages, profile)
        
        // 分析用户偏好
        analyzeUserPreferences(chatMessages, profile)
        
        AppLogger.d(TAG, "用户画像构建完成，有效维?${profile.getNonEmptyDimensions().size}")
        profile
    }
    
    /**
     * 分析对话内容，提取用户特?    */
    private fun analyzeChatContent(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        // 过滤用户消息
        val userMessages = messages.filter { it.sender == "user" }
        if (userMessages.isEmpty()) return
        
        // 提取职业信息
        extractOccupation(userMessages, profile)
        
        // 提取兴趣爱好
        extractInterests(userMessages, profile)
        
        // 提取沟通风?       extractCommunicationStyle(userMessages, profile)
        
        // 提取需求偏?       extractNeeds(userMessages, profile)
    }
    
    /**
     * 提取职业信息
     */
    private fun extractOccupation(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        val occupationKeywords = listOf(
            "程顺? "工程? "设计? "教师", "医生", "学生", "律师",
            "程顺? "开? "编程", "设计", "教育", "医疗", "学习", "法律"
        )
        
        val occupationPatterns = listOf(
            "我是(.*)\s*(程序员|工程师|设计师|教师|医生|学生|律师?,
            "我在(.*)工作",
            "我的职业?*)",
            "我从?*)行业"
        )
        
        for (message in messages) {
            val content = message.content
            
            // 检查关键词
            for (keyword in occupationKeywords) {
                if (content.contains(keyword)) {
                    profile.updateDimension("职业场景", keyword)
                    return
                }
            }
            
            // 检查模?          for (pattern in occupationPatterns) {
                val matcher = Pattern.compile(pattern).matcher(content)
                if (matcher.find()) {
                    val occupation = matcher.group(1)?.trim() ?: matcher.group(2)?.trim()
                    if (occupation != null) {
                        profile.updateDimension("职业场景", occupation)
                        return
                    }
                }
            }
        }
    }
    
    /**
     * 提取兴趣爱好
     */
    private fun extractInterests(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        val interestKeywords = listOf(
            "喜欢", "爱好", "兴趣", "喜欢? "爱好? "感兴?
        )
        
        val interestPatterns = listOf(
            "我喜?*)",
            "我的爱好?*)",
            "我对(.*)感兴?
            "我喜欢做(.*)"
        )
        
        val interests = mutableListOf<String>()
        
        for (message in messages) {
            val content = message.content
            
            for (pattern in interestPatterns) {
                val matcher = Pattern.compile(pattern).matcher(content)
                if (matcher.find()) {
                    val interest = matcher.group(1)?.trim()
                    if (interest != null && interest.isNotBlank()) {
                        interests.add(interest)
                    }
                }
            }
        }
        
        if (interests.isNotEmpty()) {
            profile.updateDimension("需求偏? interests.joinToString("?)
        }
    }
    
    /**
     * 提取沟通风?    */
    private fun extractCommunicationStyle(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        var formalCount = 0
        var casualCount = 0
        var conciseCount = 0
        var detailedCount = 0
        
        for (message in messages) {
            val content = message.content
            
            // 分析消息长度
            if (content.length < 20) {
                conciseCount++
            } else if (content.length > 100) {
                detailedCount++
            }
            
            // 分析语言风格
            val formalKeywords = listOf("? "谢谢", "您好", "请问", "麻烦的）
            val casualKeywords = listOf("? "? "? "呀", "? "的）
            
            for (keyword in formalKeywords) {
                if (content.contains(keyword)) {
                    formalCount++
                    break
                }
            }
            
            for (keyword in casualKeywords) {
                if (content.contains(keyword)) {
                    casualCount++
                    break
                }
            }
        }
        
        val style = when {
            formalCount > casualCount -> "正式"
            casualCount > formalCount -> "随意"
            else -> "中，
        }
        
        val detailLevel = when {
            detailedCount > conciseCount -> "详细"
            conciseCount > detailedCount -> "简?
            else -> "适中"
        }
        
        profile.updateDimension("沟通风? "${style}, ${detailLevel}")
    }
    
    /**
     * 提取需求偏?    */
    private fun extractNeeds(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        val needs = mutableMapOf<String, Int>()
        
        val needKeywords = mapOf(
            "技术支?to listOf("技? "问题", "故障", "bug", "修复"),
            "信息查询" to listOf("查询", "搜索", "信息", "数据", "资料"),
            "创意生成" to listOf("创意", "想法", "灵感", "设计", "方案"),
            "学习教育" to listOf("学习", "教育", "知识", "教程", "课程"),
            "生活助手" to listOf("生活", "日常", "助手", "帮助", "建议")
        )
        
        for (message in messages) {
            val content = message.content
            
            for ((needType, keywords) in needKeywords) {
                for (keyword in keywords) {
                    if (content.contains(keyword)) {
                        needs[needType] = needs.getOrDefault(needType, 0) + 1
                        break
                    }
                }
            }
        }
        
        if (needs.isNotEmpty()) {
            val topNeeds = needs.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString("的） { it.key }
            
            profile.updateDimension("需求偏? topNeeds)
        }
    }
    
    /**
     * 分析对话模式
     */
    private fun analyzeChatPatterns(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        // 分析对话频率
        val messageCount = messages.size
        val avgLength = messages.map { it.content.length }.average()
        
        // 分析回复速度（简化版?      val responsePattern = when {
            messageCount > 50 -> "高频"
            messageCount > 20 -> "中频"
            else -> "低频"
        }
        
        val lengthPattern = when {
            avgLength > 50 -> "长消?
            avgLength > 20 -> "中等长度"
            else -> "短消?
        }
        
        profile.updateDimension("交互偏好", "${responsePattern}, ${lengthPattern}")
        profile.updateDimension("操作习惯", if (avgLength > 30) "详细描述" else "简洁表示）
    }
    
    /**
     * 分析用户偏好
     */
    private fun analyzeUserPreferences(messages: List<ChatMessage>, profile: HonzonUserProfile) {
        // 分析时间偏好
        val hourDistribution = mutableMapOf<Int, Int>()
        for (message in messages) {
            val hour = message.timestamp.substring(11, 13).toIntOrNull() ?: 0
            hourDistribution[hour] = hourDistribution.getOrDefault(hour, 0) + 1
        }
        
        if (hourDistribution.isNotEmpty()) {
            val peakHour = hourDistribution.maxByOrNull { it.value }?.key
            if (peakHour != null) {
                val timePreference = when (peakHour) {
                    in 6..12 -> "上午"
                    in 13..18 -> "下午"
                    in 19..23 -> "晚上"
                    else -> "凌晨"
                }
                profile.updateDimension("操作习惯", "活跃时间: ${timePreference}")
            }
        }
        
        // 分析话题偏好
        val topicKeywords = mapOf(
            "技?to listOf("技? "编程", "软件", "硬件", "开的）,
            "生活" to listOf("生活", "日常", "家庭", "朋友", "娱乐"),
            "工作" to listOf("工作", "职场", "业务", "项目", "任务"),
            "学习" to listOf("学习", "教育", "知识", "课程", "考试")
        )
        
        val topicCounts = mutableMapOf<String, Int>()
        for (message in messages) {
            val content = message.content
            for ((topic, keywords) in topicKeywords) {
                for (keyword in keywords) {
                    if (content.contains(keyword)) {
                        topicCounts[topic] = topicCounts.getOrDefault(topic, 0) + 1
                        break
                    }
                }
            }
        }
        
        if (topicCounts.isNotEmpty()) {
            val topTopic = topicCounts.maxByOrNull { it.value }?.key
            if (topTopic != null) {
                profile.updateDimension("需求偏? "话题偏好: ${topTopic}")
            }
        }
    }
    
    /**
     * 保存用户画像到存?    */
    suspend fun saveProfile(userId: String, profile: HonzonUserProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            // 保存每个维度
            for ((dimension, value) in profile.getNonEmptyDimensions()) {
                memoryRepository.updateHonzonProfile(userId, dimension, value)
            }
            AppLogger.d(TAG, "用户画像保存成功: ${userId}")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存用户画像失败", e)
            false
        }
    }
    
    /**
     * 从对话历史更新用户画?    */
    suspend fun updateProfileFromChatHistory(userId: String, chatMessages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = buildProfileFromChatHistory(userId, chatMessages)
            saveProfile(userId, profile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新用户画像失败", e)
            false
        }
    }
}