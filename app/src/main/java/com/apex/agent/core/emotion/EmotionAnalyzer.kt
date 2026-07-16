package com.apex.agent.core.emotion

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 情感分析�?* 识别用户情绪状态和强度
 */
class EmotionAnalyzer(private val context: Context) {
    private val TAG = "EmotionAnalyzer"
    
    /**
     * 分析用户情绪
     */
    suspend fun analyzeEmotion(messages: List<ChatMessage>): EmotionProfile = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始分析用户情绪，消息数量: ${messages.size}")
        
        val emotionProfile = EmotionProfile()
        
        // 过滤用户消息
        val userMessages = messages.filter { it.sender == "user" }
        if (userMessages.isEmpty()) return@withContext emotionProfile
        
        // 分析情绪
        analyzeEmotions(userMessages, emotionProfile)
        
        // 分析情绪强度
        analyzeEmotionIntensity(userMessages, emotionProfile)
        
        // 分析情绪趋势
        analyzeEmotionTrend(userMessages, emotionProfile)
        
        // 分析情绪触发因素
        analyzeEmotionTriggers(userMessages, emotionProfile)
        
        AppLogger.d(TAG, "用户情绪分析完成: ${emotionProfile}")
        emotionProfile
    }
    
    /**
     * 分析情绪类型
     */
    private fun analyzeEmotions(messages: List<ChatMessage>, profile: EmotionProfile) {
        val emotionScores = mutableMapOf<String, Int>()
        
        // 情绪关键�?      val emotionKeywords = mapOf(
            "开�?to listOf("开�? "高兴", "快乐", "喜悦", "兴奋", "愉快", "欢乐", "欣喜"),
            "伤心" to listOf("伤心", "难过", "悲伤", "痛苦", "沮丧", "失落", "绝望", "悲痛"),
            "愤�?to listOf("愤�? "生气", "恼火", "愤�? "气愤", "暴，, "恼，),
            "焦虑" to listOf("焦虑", "担心", "忧虑", "紧张", "不安", "恐慌", "害，),
            "困惑" to listOf("困惑", "迷茫", "不解", "疑惑", "迷茫", "不确认）,
            "满意" to listOf("满意", "满足", "高兴", "喜悦", "愉快", "开的）,
            "失望" to listOf("失望", "沮丧", "灰心", "失落", "扫兴"),
            "惊讶" to listOf("惊讶", "吃惊", "震惊", "意外", "出乎意料")
        )
        
        for (message in messages) {
            val content = message.content
            
            for ((emotion, keywords) in emotionKeywords) {
                for (keyword in keywords) {
                    if (content.contains(keyword)) {
                        emotionScores[emotion] = emotionScores.getOrDefault(emotion, 0) + 1
                        break
                    }
                }
            }
        }
        
        if (emotionScores.isNotEmpty()) {
            val dominantEmotion = emotionScores.maxByOrNull { it.value }?.key
            if (dominantEmotion != null) {
                profile.dominantEmotion = dominantEmotion
                profile.emotionScores = emotionScores
            }
        }
    }
    
    /**
     * 分析情绪强度
     */
    private fun analyzeEmotionIntensity(messages: List<ChatMessage>, profile: EmotionProfile) {
        var totalIntensity = 0
        var intensityCount = 0
        
        for (message in messages) {
            val content = message.content
            val intensity = calculateEmotionIntensity(content)
            
            if (intensity > 0) {
                totalIntensity += intensity
                intensityCount++
            }
        }
        
        if (intensityCount > 0) {
            profile.avgEmotionIntensity = totalIntensity.toDouble() / intensityCount
            profile.maxEmotionIntensity = messages.maxOfOrNull { calculateEmotionIntensity(it.content) } ?: 0
        }
    }
    
    /**
     * 计算单条消息的情绪强�?    */
    private fun calculateEmotionIntensity(content: String): Int {
        var intensity = 0
        
        // 强度关键�?      val intensityKeywords = listOf(
            "非常", "特别", "�? "超级", "极其", "十分", "相当", "特别", "极其"
        )
        
        // 感叹号和问号
        val exclamationCount = content.count { it == '!' }
        val questionCount = content.count { it == '?' }
        
        // 计算强度
        for (keyword in intensityKeywords) {
            if (content.contains(keyword)) {
                intensity += 2
            }
        }
        
        intensity += exclamationCount * 2
        intensity += questionCount
        
        return intensity.coerceAtMost(10)
    }
    
    /**
     * 分析情绪趋势
     */
    private fun analyzeEmotionTrend(messages: List<ChatMessage>, profile: EmotionProfile) {
        if (messages.size < 3) return
        
        val emotions = mutableListOf<String>()
        
        for (message in messages) {
            val content = message.content
            val emotion = detectEmotion(content)
            if (emotion != "中，) {
                emotions.add(emotion)
            }
        }
        
        if (emotions.size >= 3) {
            val recentEmotions = emotions.takeLast(3)
            val firstHalf = recentEmotions.take(2)
            val secondHalf = recentEmotions.takeLast(2)
            
            val firstHalfPositive = firstHalf.count { isPositiveEmotion(it) }
            val secondHalfPositive = secondHalf.count { isPositiveEmotion(it) }
            
            profile.emotionTrend = when {
                secondHalfPositive > firstHalfPositive -> "上升"
                secondHalfPositive < firstHalfPositive -> "下降"
                else -> "稳定"
            }
        }
    }
    
    /**
     * 检测单条消息的情绪
     */
    private fun detectEmotion(content: String): String {
        val emotionKeywords = mapOf(
            "开�?to listOf("开�? "高兴", "快乐", "喜悦", "兴奋", "愉快"),
            "伤心" to listOf("伤心", "难过", "悲伤", "痛苦", "沮丧"),
            "愤�?to listOf("愤�? "生气", "恼火", "气愤"),
            "焦虑" to listOf("焦虑", "担心", "忧虑", "紧张"),
            "困惑" to listOf("困惑", "迷茫", "不解", "疑惑"),
            "满意" to listOf("满意", "满足", "高兴"),
            "失望" to listOf("失望", "沮丧", "灰心"),
            "惊讶" to listOf("惊讶", "吃惊", "震惊")
        )
        
        for ((emotion, keywords) in emotionKeywords) {
            for (keyword in keywords) {
                if (content.contains(keyword)) {
                    return emotion
                }
            }
        }
        
        return "中，
    }
    
    /**
     * 分析情绪触发因素
     */
    private fun analyzeEmotionTriggers(messages: List<ChatMessage>, profile: EmotionProfile) {
        val triggers = mutableMapOf<String, Int>()
        
        val triggerKeywords = mapOf(
            "工作" to listOf("工作", "职场", "业务", "项目", "任务"),
            "学习" to listOf("学习", "教育", "知识", "课程", "考试"),
            "生活" to listOf("生活", "日常", "家庭", "朋友", "娱乐"),
            "技�?to listOf("技�? "编程", "软件", "硬件", "开的）,
            "关系" to listOf("朋友", "家人", "同事", "关系", "感情")
        )
        
        for (message in messages) {
            val content = message.content
            val emotion = detectEmotion(content)
            
            if (emotion != "中，) {
                for ((trigger, keywords) in triggerKeywords) {
                    for (keyword in keywords) {
                        if (content.contains(keyword)) {
                            triggers[trigger] = triggers.getOrDefault(trigger, 0) + 1
                            break
                        }
                    }
                }
            }
        }
        
        if (triggers.isNotEmpty()) {
            val primaryTrigger = triggers.maxByOrNull { it.value }?.key
            if (primaryTrigger != null) {
                profile.primaryEmotionTrigger = primaryTrigger
                profile.emotionTriggers = triggers
            }
        }
    }
    
    /**
     * 判断是否为积极情�?    */
    private fun isPositiveEmotion(emotion: String): Boolean {
        val positiveEmotions = listOf("开�? "满意", "惊讶")
        return positiveEmotions.contains(emotion)
    }
    
    /**
     * 生成情绪分析报告
     */
    suspend fun generateEmotionReport(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeEmotion(messages)
        
        buildString {
            appendLine("# 用户情绪分析报告")
            appendLine()
            appendLine("## 情绪状态）
            appendLine("- 主导情绪: ${profile.dominantEmotion}")
            appendLine("- 平均情绪强度: ${profile.avgEmotionIntensity.toInt()}/10")
            appendLine("- 最大情绪强�?${profile.maxEmotionIntensity}/10")
            appendLine("- 情绪趋势: ${profile.emotionTrend}")
            appendLine()
            
            appendLine("## 情绪分布")
            profile.emotionScores.forEach { (emotion, score) ->
                appendLine("- ${emotion}: ${score}")
            }
            appendLine()
            
            appendLine("## 情绪触发因素")
            appendLine("- 主要触发因素: ${profile.primaryEmotionTrigger ?: "未知"}")
            profile.emotionTriggers.forEach { (trigger, count) ->
                appendLine("- ${trigger}: ${count}")
            }
        }
    }
    
    /**
     * 检测紧急情�?    */
    suspend fun detectUrgentEmotion(messages: List<ChatMessage>): Boolean = withContext(Dispatchers.IO) {
        val profile = analyzeEmotion(messages)
        
        // 紧急情绪：高强度的负面情绪
        val urgentEmotions = listOf("伤心", "愤�? "焦虑")
        urgentEmotions.contains(profile.dominantEmotion) && profile.avgEmotionIntensity > 6
    }
}