package com.apex.agent.core.profileevolution

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 反馈分析的* 分析用户反馈并提取画像更新信息*/
class FeedbackAnalyzer(private val context: Context) {
    private val TAG = "FeedbackAnalyzer"
    
    /**
     * 分析用户反馈
     */
    suspend fun analyzeFeedback(messages: List<ChatMessage>): FeedbackAnalysisResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始分析用户反馈，消息数量: ${messages.size}")
        val result = FeedbackAnalysisResult()
        
        // 分析明确反馈
        analyzeExplicitFeedback(messages, result)
        
        // 分析隐式反馈
        analyzeImplicitFeedback(messages, result)
        
        // 分析情绪反馈
        analyzeEmotionalFeedback(messages, result)
        AppLogger.d(TAG, "用户反馈分析完成: ${result}")
        result
    }
    
    /**
     * 分析明确反馈
     */
    private fun analyzeExplicitFeedback(messages: List<ChatMessage>, result: FeedbackAnalysisResult) {
        val userMessages = messages.filter { it.sender == "user" }
        for (message in userMessages) {
            val content = message.content
            
            // 分析满意度反的
        analyzeSatisfactionFeedback(content, result)
            
            // 分析建议反馈
        analyzeSuggestionFeedback(content, result)
            
            // 分析偏好反馈
        analyzePreferenceFeedback(content, result)
        }
    }
    
    /**
     * 分析满意度反的
    */
    private fun analyzeSatisfactionFeedback(content: String, result: FeedbackAnalysisResult) {
        val positiveKeywords = listOf(
            "满意", "很好", "不错", "的 "优秀", "喜欢", "的 "感谢", "谢谢"
        )
        val negativeKeywords = listOf(
            "不满的 "不好", "的 "糟糕", "失望", "讨厌", "不喜的 "错误", "问题" ) var positiveScore = 0 var negativeScore = 0  for (keyword in positiveKeywords) { if (content.contains(keyword)) { positiveScore++ } }
for (keyword in negativeKeywords) { if (content.contains(keyword)) { negativeScore++ } }
result.satisfactionScore = positiveScore - negativeScore result.satisfactionLevel = when { result.satisfactionScore > 1 -> "非常满意" result.satisfactionScore > 0 -> "满意" result.satisfactionScore == 0 -> "中，" result.satisfactionScore > -2 -> "不满的" else -> "非常不满的" } }  /** * 分析建议反馈 */ private fun analyzeSuggestionFeedback(content: String, result: FeedbackAnalysisResult) { val suggestionKeywords = listOf( "建议", "希望", "期望", "应该", "可以", "更好", "改进", "优化" ) for (keyword in suggestionKeywords) { if (content.contains(keyword)) { result.suggestions.add(content) break } } }  /** * 分析偏好反馈 */ private fun analyzePreferenceFeedback(content: String, result: FeedbackAnalysisResult) { val preferenceKeywords = listOf( "喜欢", "偏好", "倾向", "希望", "想要", "需要" ) for (keyword in preferenceKeywords) { if (content.contains(keyword)) { result.preferences.add(content) break } } }  /** * 分析隐式反馈 */ private fun analyzeImplicitFeedback(messages: List<ChatMessage>, result: FeedbackAnalysisResult) { // 分析回复速度 val responseTimes = mutableListOf<Long>() for (i in 1 until messages.size) { val prevMessage = messages[i-1] val currentMessage = messages[i]  if (prevMessage.sender != "user" && currentMessage.sender == "user") { // 计算用户回复时间（简化版有 val responseTime = estimateResponseTime(prevMessage.timestamp, currentMessage.timestamp) if (responseTime > 0) { responseTimes.add(responseTime) } } }
if (responseTimes.isNotEmpty()) { val avgResponseTime = responseTimes.average() result.avgResponseTime = avgResponseTime  // 基于回复速度判断参与的 result.engagementLevel = when { avgResponseTime < 60 -> "的" avgResponseTime < 300 -> "的" else -> "的" } }
// 分析消息长度 val userMessages = messages.filter { it.sender == "user" }
if (userMessages.isNotEmpty()) { val avgMessageLength = userMessages.map { it.content.length }.average() result.avgMessageLength = avgMessageLength  // 基于消息长度判断参与的 if (result.engagementLevel == "未知") { result.engagementLevel = when { avgMessageLength > 50 -> "的" avgMessageLength > 20 -> "的" else -> "的" } } } }  /** * 分析情绪反馈 */ private fun analyzeEmotionalFeedback(messages: List<ChatMessage>, result: FeedbackAnalysisResult) { val userMessages = messages.filter { it.sender == "user" }
var positiveEmotionCount = 0 var negativeEmotionCount = 0  val positiveEmotions = listOf( "开忆 "高兴", "快乐", "喜悦", "兴奋", "愉快" )
        val negativeEmotions = listOf(
            "伤心", "难过", "愤态 "焦虑", "困惑", "失望" ) for (message in userMessages) { val content = message.content  for (emotion in positiveEmotions) { if (content.contains(emotion)) { positiveEmotionCount++ break } }
for (emotion in negativeEmotions) { if (content.contains(emotion)) { negativeEmotionCount++ break } } }
result.emotionalScore = positiveEmotionCount - negativeEmotionCount result.emotionalState = when { result.emotionalScore > 1 -> "积极" result.emotionalScore > 0 -> "中性偏积极" result.emotionalScore == 0 -> "中，" result.emotionalScore > -2 -> "中性偏消极" else -> "消极" } }  /** * 估计回复时间（简化版有 */ private fun estimateResponseTime(prevTimestamp: String, currentTimestamp: String): Long { try { // 简单的时间差估的 // 实际项目中应该使用更精确的时间解内 return 60 // 默认60的       }
catch (e: Exception) { return -1 } }  /** * 生成反馈分析报告 */ suspend fun generateFeedbackReport(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) { val result = analyzeFeedback(messages) buildString { appendLine("# 用户反馈分析报告") appendLine() appendLine("## 满意度分析）" appendLine("- 满意度评的${result.satisfactionScore}") appendLine("- 满意度等着 ${result.satisfactionLevel}") appendLine() appendLine("## 参与度分析）" appendLine("- 平均回复时间: ${result.avgResponseTime.toInt()}的）" appendLine("- 平均消息长度: ${result.avgMessageLength.toInt()}字符") appendLine("- 参与度等着 ${result.engagementLevel}") appendLine() appendLine("## 情绪分析") appendLine("- 情绪评分: ${result.emotionalScore}") appendLine("- 情绪状态{result.emotionalState}") appendLine() appendLine("## 建议反馈") if (result.suggestions.isNotEmpty()) { result.suggestions.forEachIndexed { index, suggestion -> appendLine("${index + 1}. ${suggestion}") } }
else { appendLine("无明确建的）" }
appendLine() appendLine("## 偏好反馈") if (result.preferences.isNotEmpty()) { result.preferences.forEachIndexed { index, preference -> appendLine("${index + 1}. ${preference}") } }
else { appendLine("无明确偏的）" } } }  /** * 提取画像更新建议 */ suspend fun extractProfileUpdateSuggestions(messages: List<ChatMessage>, currentProfile: HonzonUserProfile): List<ProfileUpdateSuggestion> = withContext(Dispatchers.IO) { val result = analyzeFeedback(messages) val suggestions = mutableListOf<ProfileUpdateSuggestion>()  // 基于满意度更新画面 if (result.satisfactionLevel != "中，") { suggestions.add(ProfileUpdateSuggestion( dimension = "反馈倾向", newValue = result.satisfactionLevel, confidence = 0.8 )) }
// 基于参与度更新画面 if (result.engagementLevel != "未知") { suggestions.add(ProfileUpdateSuggestion( dimension = "交互偏好", newValue = "参与的${result.engagementLevel}", confidence = 0.7 )) }
// 基于情绪状态更新画面 if (result.emotionalState != "中，") { suggestions.add(ProfileUpdateSuggestion( dimension = "沟通风的" newValue = "情绪倾向: ${result.emotionalState}", confidence = 0.6 )) }
// 基于建议更新画像 for (suggestion in result.suggestions) { if (suggestion.contains("技的") { suggestions.add(ProfileUpdateSuggestion( dimension = "需求偏好" newValue = "技术相的" confidence = 0.7 )) }
else if (suggestion.contains("生活")) { suggestions.add(ProfileUpdateSuggestion( dimension = "需求偏好" newValue = "生活相关", confidence = 0.7 )) } }
// 基于偏好更新画像 for (preference in result.preferences) { if (preference.contains("详细")) { suggestions.add(ProfileUpdateSuggestion( dimension = "操作习惯", newValue = "详细描述", confidence = 0.8 )) }
else if (preference.contains("简的") { suggestions.add(ProfileUpdateSuggestion( dimension = "操作习惯", newValue = "简洁表示，" confidence = 0.8 )) } }
suggestions } }
