package com.apex.agent.core.behavior

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 行为分析引擎
 * 分析用户对话模式和使用习�?*/
class BehaviorAnalyzer(private val context: Context) {
    private val TAG = "BehaviorAnalyzer"
    
    /**
     * 分析用户行为
     */
    suspend fun analyzeUserBehavior(messages: List<ChatMessage>): UserBehaviorProfile = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始分析用户行为，消息数量: ${messages.size}")
        
        val behaviorProfile = UserBehaviorProfile()
        
        // 分析对话模式
        analyzeChatPatterns(messages, behaviorProfile)
        
        // 分析使用时间
        analyzeUsageTime(messages, behaviorProfile)
        
        // 分析消息特征
        analyzeMessageFeatures(messages, behaviorProfile)
        
        // 分析交互模式
        analyzeInteractionPatterns(messages, behaviorProfile)
        
        // 分析使用场景
        analyzeUsageScenarios(messages, behaviorProfile)
        
        AppLogger.d(TAG, "用户行为分析完成: ${behaviorProfile}")
        behaviorProfile
    }
    
    /**
     * 分析对话模式
     */
    private fun analyzeChatPatterns(messages: List<ChatMessage>, profile: UserBehaviorProfile) {
        if (messages.isEmpty()) return
        
        // 计算对话频率
        val userMessages = messages.filter { it.sender == "user" }
        val aiMessages = messages.filter { it.sender == "ai" || it.sender == "assistant" }
        
        profile.messageCount = messages.size
        profile.userMessageCount = userMessages.size
        profile.aiMessageCount = aiMessages.size
        
        // 计算平均消息长度
        profile.avgMessageLength = messages.map { it.content.length }.average()
        profile.avgUserMessageLength = userMessages.map { it.content.length }.average()
        profile.avgAiMessageLength = aiMessages.map { it.content.length }.average()
        
        // 分析对话密度
        if (messages.size > 1) {
            val timeDifferences = mutableListOf<Long>()
            for (i in 1 until messages.size) {
                val time1 = parseTimestamp(messages[i-1].timestamp)
                val time2 = parseTimestamp(messages[i].timestamp)
                if (time1 != null && time2 != null) {
                    val diff = abs(time2.time - time1.time) / 1000 // 转换为秒
                    if (diff < 3600) { // 只考虑1小时内的消息
                        timeDifferences.add(diff)
                    }
                }
            }
            
            if (timeDifferences.isNotEmpty()) {
                profile.avgResponseTime = timeDifferences.average()
                profile.responseTimeVariance = calculateVariance(timeDifferences)
            }
        }
    }
    
    /**
     * 分析使用时间
     */
    private fun analyzeUsageTime(messages: List<ChatMessage>, profile: UserBehaviorProfile) {
        val hourDistribution = mutableMapOf<Int, Int>()
        val dayDistribution = mutableMapOf<Int, Int>()
        
        for (message in messages) {
            val timestamp = parseTimestamp(message.timestamp)
            if (timestamp != null) {
                val hour = timestamp.hours
                val day = timestamp.day
                
                hourDistribution[hour] = hourDistribution.getOrDefault(hour, 0) + 1
                dayDistribution[day] = dayDistribution.getOrDefault(day, 0) + 1
            }
        }
        
        // 分析活跃时间
        if (hourDistribution.isNotEmpty()) {
            val peakHour = hourDistribution.maxByOrNull { it.value }?.key
            if (peakHour != null) {
                profile.peakUsageHour = peakHour
                profile.usageTimePattern = when (peakHour) {
                    in 6..12 -> "上午"
                    in 13..18 -> "下午"
                    in 19..23 -> "晚上"
                    else -> "凌晨"
                }
            }
        }
        
        // 分析活跃日期
        if (dayDistribution.isNotEmpty()) {
            val peakDay = dayDistribution.maxByOrNull { it.value }?.key
            if (peakDay != null) {
                profile.peakUsageDay = peakDay
                profile.usageDayPattern = when (peakDay) {
                    1 -> "周一"
                    2 -> "周二"
                    3 -> "周三"
                    4 -> "周四"
                    5 -> "周五"
                    6 -> "周六"
                    0 -> "周日"
                    else -> "未知"
                }
            }
        }
    }
    
    /**
     * 分析消息特征
     */
    private fun analyzeMessageFeatures(messages: List<ChatMessage>, profile: UserBehaviorProfile) {
        val userMessages = messages.filter { it.sender == "user" }
        
        // 分析消息类型
        var questionCount = 0
        var statementCount = 0
        var commandCount = 0
        
        for (message in userMessages) {
            val content = message.content
            when {
                content.contains('?') -> questionCount++
                content.startsWith("的） || content.startsWith("帮我") || content.startsWith("给我") -> commandCount++
                else -> statementCount++
            }
        }
        
        profile.questionCount = questionCount
        profile.statementCount = statementCount
        profile.commandCount = commandCount
        
        // 分析语言风格
        var formalCount = 0
        var casualCount = 0
        
        val formalWords = listOf("�? "谢谢", "您好", "请问", "麻烦�? "不好意，)
        val casualWords = listOf("�? "�? "�? "呀", "�? "�? "�? "的）
        
        for (message in userMessages) {
            val content = message.content
            var isFormal = false
            var isCasual = false
            
            for (word in formalWords) {
                if (content.contains(word)) {
                    isFormal = true
                    break
                }
            }
            
            for (word in casualWords) {
                if (content.contains(word)) {
                    isCasual = true
                    break
                }
            }
            
            if (isFormal) formalCount++
            if (isCasual) casualCount++
        }
        
        profile.formalMessageCount = formalCount
        profile.casualMessageCount = casualCount
        
        // 确定主导风格
        profile.dominantStyle = when {
            formalCount > casualCount -> "正式"
            casualCount > formalCount -> "随意"
            else -> "中，
        }
    }
    
    /**
     * 分析交互模式
     */
    private fun analyzeInteractionPatterns(messages: List<ChatMessage>, profile: UserBehaviorProfile) {
        // 分析轮次长度
        var currentTurn = 0
        val turnLengths = mutableListOf<Int>()
        
        for (i in messages.indices) {
            currentTurn++
            
            // 轮次结束条件：用户消息后跟着AI消息，或者是最后一条消�?           if (i == messages.size - 1 || 
                (messages[i].sender == "user" && 
                 (messages[i+1].sender == "ai" || messages[i+1].sender == "assistant"))
            ) {
                turnLengths.add(currentTurn)
                currentTurn = 0
            }
        }
        
        if (turnLengths.isNotEmpty()) {
            profile.avgTurnLength = turnLengths.average()
            profile.maxTurnLength = turnLengths.maxOrNull() ?: 0
            profile.minTurnLength = turnLengths.minOrNull() ?: 0
        }
        
        // 分析回复模式
        val responsePatterns = mutableMapOf<String, Int>()
        
        for (i in 1 until messages.size) {
            val prevSender = messages[i-1].sender
            val currentSender = messages[i].sender
            
            if (prevSender == "user" && (currentSender == "ai" || currentSender == "assistant")) {
                responsePatterns["user->ai"] = responsePatterns.getOrDefault("user->ai", 0) + 1
            } else if ((prevSender == "ai" || prevSender == "assistant") && currentSender == "user") {
                responsePatterns["ai->user"] = responsePatterns.getOrDefault("ai->user", 0) + 1
            }
        }
        
        profile.responsePatterns = responsePatterns
    }
    
    /**
     * 分析使用场景
     */
    private fun analyzeUsageScenarios(messages: List<ChatMessage>, profile: UserBehaviorProfile) {
        val userMessages = messages.filter { it.sender == "user" }
        
        // 分析场景关键�?      val scenarios = mutableMapOf<String, Int>()
        
        val scenarioKeywords = mapOf(
            "工作" to listOf("工作", "职场", "业务", "项目", "任务", "会议", "报告"),
            "学习" to listOf("学习", "教育", "知识", "课程", "考试", "作业", "研究"),
            "生活" to listOf("生活", "日常", "家庭", "朋友", "娱乐", "购物", "旅游"),
            "技�?to listOf("技�? "编程", "软件", "硬件", "开�? "代码", "问题"),
            "创意" to listOf("创意", "想法", "灵感", "设计", "方案", "构，, "创作")
        )
        
        for (message in userMessages) {
            val content = message.content
            for ((scenario, keywords) in scenarioKeywords) {
                for (keyword in keywords) {
                    if (content.contains(keyword)) {
                        scenarios[scenario] = scenarios.getOrDefault(scenario, 0) + 1
                        break
                    }
                }
            }
        }
        
        if (scenarios.isNotEmpty()) {
            val topScenarios = scenarios.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
            
            profile.usageScenarios = topScenarios
            profile.primaryUsageScenario = topScenarios.firstOrNull()
        }
    }
    
    /**
     * 解析时时�?    */
    private fun parseTimestamp(timestamp: String): Date? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.parse(timestamp)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 计算方差
     */
    private fun calculateVariance(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return variance
    }
    
    /**
     * 生成行为分析报告
     */
    suspend fun generateBehaviorReport(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val profile = analyzeUserBehavior(messages)
        
        buildString {
            appendLine("# 用户行为分析报告")
            appendLine()
            appendLine("## 对话模式")
            appendLine("- 总消息数: ${profile.messageCount}")
            appendLine("- 用户消息�?${profile.userMessageCount}")
            appendLine("- AI消息�?${profile.aiMessageCount}")
            appendLine("- 平均消息长度: ${profile.avgMessageLength.toInt()} 字符")
            appendLine("- 平均用户消息长度: ${profile.avgUserMessageLength.toInt()} 字符")
            appendLine("- 平均AI消息长度: ${profile.avgAiMessageLength.toInt()} 字符")
            appendLine()
            
            appendLine("## 使用时间")
            appendLine("- 活跃时间: ${profile.usageTimePattern}")
            appendLine("- 活跃小时: ${profile.peakUsageHour}")
            appendLine("- 活跃日期: ${profile.usageDayPattern}")
            appendLine()
            
            appendLine("## 消息特征")
            appendLine("- 问题�?${profile.questionCount}")
            appendLine("- 陈述�?${profile.statementCount}")
            appendLine("- 命令�?${profile.commandCount}")
            appendLine("- 正式消息�?${profile.formalMessageCount}")
            appendLine("- 随意消息�?${profile.casualMessageCount}")
            appendLine("- 主导风格: ${profile.dominantStyle}")
            appendLine()
            
            appendLine("## 交互模式")
            appendLine("- 平均轮次长度: ${profile.avgTurnLength.toInt()} 消息")
            appendLine("- 最大轮次长�?${profile.maxTurnLength} 消息")
            appendLine("- 最小轮次长�?${profile.minTurnLength} 消息")
            appendLine("- 平均响应时间: ${profile.avgResponseTime.toInt()} 的）
            appendLine()
            
            appendLine("## 使用场景")
            appendLine("- 主要场景: ${profile.primaryUsageScenario ?: "未知"}")
            appendLine("- 场景分布: ${profile.usageScenarios.joinToString("的）}")
        }
    }
}