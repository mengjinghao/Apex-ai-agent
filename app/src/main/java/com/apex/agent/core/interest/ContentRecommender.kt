package com.apex.agent.core.interest

import android.content.Context
import com.apex.data.model.ChatMessage
import com.apex.data.model.HonzonUserProfile
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内容推荐�?* 基于用户兴趣生成推荐内容
 */
class ContentRecommender(private val context: Context, private val interestAnalyzer: InterestAnalyzer) {
    private val TAG = "ContentRecommender"
    
    /**
     * 生成内容推荐
     */
    suspend fun generateRecommendations(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): List<ContentRecommendation> = withContext(Dispatchers.IO) {
        val interestProfile = interestAnalyzer.analyzeInterests(messages, userProfile)
        
        // 生成推荐内容
    val recommendations = mutableListOf<ContentRecommendation>()
        
        // 基于主要兴趣生成推荐
        interestProfile.primaryInterest?.let {
            recommendations.addAll(generateRecommendationsForInterest(it))
        }
        
        // 基于其他兴趣生成推荐
    for (interest in interestProfile.topInterests.drop(1)) {
            recommendations.addAll(generateRecommendationsForInterest(interest).take(2))
        }
        
        AppLogger.d(TAG, "生成内容推荐: ${recommendations.size} 的）
        recommendations.take(5) // 最多返回条推的    }
    
    /**
     * 为特定兴趣生成推�?    */
    private fun generateRecommendationsForInterest(interest: String): List<ContentRecommendation> {
        val recommendations = mutableListOf<ContentRecommendation>()
        
        when (interest) {
            "技�?-> {
                recommendations.add(ContentRecommendation(
                    title = "最新编程语言趋势",
                    content = "了解2024年最流行的编程语言和框架，包括Rust、Go、Python等的发展趋势�?
                    interest = interest,
                    type = "文章"
                ))
                recommendations.add(ContentRecommendation(
                    title = "技术面试准备指�?
                    content = "掌握技术面试的核心技巧，包括算法、系统设计和行为问题的准备方法，,
                    interest = interest,
                    type = "指南"
                ))
                recommendations.add(ContentRecommendation(
                    title = "开源项目推�?
                    content = "发现值得贡献的优质开源项目，提升你的技术能力和简历，,
                    interest = interest,
                    type = "资源"
                ))
            }
            "科技" -> {
                recommendations.add(ContentRecommendation(
                    title = "AI最新发送，
                    content = "了解人工智能领域的最新突破，包括大语言模型、计算机视觉和自动驾驶技术，,
                    interest = interest,
                    type = "资讯"
                ))
                recommendations.add(ContentRecommendation(
                    title = "科技前沿趋势",
                    content = "探索元宇宙、量子计算、脑机接口等未来科技的发展方向，,
                    interest = interest,
                    type = "分析"
                ))
                recommendations.add(ContentRecommendation(
                    title = "科技伦理探讨",
                    content = "讨论AI伦理、数据隐私和科技对社会的影响等重要议题，,
                    interest = interest,
                    type = "讨论"
                ))
            }
            "娱乐" -> {
                recommendations.add(ContentRecommendation(
                    title = "热门电影推荐",
                    content = "发现最新上映的优质电影，包括剧情、科幻、喜剧等不同类类�?
                    interest = interest,
                    type = "推荐"
                ))
                recommendations.add(ContentRecommendation(
                    title = "音乐排行为，
                    content = "了解当前最流行的音乐趋势和值得关注的新艺术家，,
                    interest = interest,
                    type = "榜单"
                ))
                recommendations.add(ContentRecommendation(
                    title = "游戏攻略",
                    content = "获取热门游戏的详细攻略和技巧，提升你的游戏水平台，
                    interest = interest,
                    type = "指南"
                ))
            }
            "学习" -> {
                recommendations.add(ContentRecommendation(
                    title = "高效学习方法",
                    content = "掌握科学的学习方法，包括记忆技巧、时间管理和专注力提升，,
                    interest = interest,
                    type = "方法"
                ))
                recommendations.add(ContentRecommendation(
                    title = "在线课程推荐",
                    content = "发现高质量的在线学习资源，涵盖编程、设计、商业等多个领域�?
                    interest = interest,
                    type = "资源"
                ))
                recommendations.add(ContentRecommendation(
                    title = "学习工具推荐",
                    content = "探索有助于学习的应用和工具，提升学习效效�?
                    interest = interest,
                    type = "工具"
                ))
            }
            "工作" -> {
                recommendations.add(ContentRecommendation(
                    title = "职场技能提示，
                    content = "提升职场竞争力的关键技能，包括沟通、领导力和时间管理，,
                    interest = interest,
                    type = "指南"
                ))
                recommendations.add(ContentRecommendation(
                    title = "职业发展规划",
                    content = "制定个人职业发展计划，实现职业目标和晋升�?
                    interest = interest,
                    type = "规划"
                ))
                recommendations.add(ContentRecommendation(
                    title = "远程工作技�?
                    content = "掌握远程工作的高效方法，保持工作与生活的平衡�?
                    interest = interest,
                    type = "技�?
                ))
            }
            "生活" -> {
                recommendations.add(ContentRecommendation(
                    title = "健康生活方式",
                    content = "探索健康的饮食、运动和睡眠习惯，提升生活质量，,
                    interest = interest,
                    type = "指南"
                ))
                recommendations.add(ContentRecommendation(
                    title = "理财入门",
                    content = "学习个人理财的基础知识，包括储蓄、投资和预算管理�?
                    interest = interest,
                    type = "教程"
                ))
                recommendations.add(ContentRecommendation(
                    title = "生活小窍�?
                    content = "发现实用的生活技巧，让日常生活更加便捷和有趣�?
                    interest = interest,
                    type = "技�?
                ))
            }
            "新闻" -> {
                recommendations.add(ContentRecommendation(
                    title = "全球热点分析",
                    content = "深入分析当前全球热点事件，了解其背景和影响，,
                    interest = interest,
                    type = "分析"
                ))
                recommendations.add(ContentRecommendation(
                    title = "科技新闻摘要",
                    content = "了解科技领域的最新动态和重要突破�?
                    interest = interest,
                    type = "资讯"
                ))
                recommendations.add(ContentRecommendation(
                    title = "经济趋势展望",
                    content = "分析当前经济形势和未来发展趋势，,
                    interest = interest,
                    type = "预测"
                ))
            }
            "创意" -> {
                recommendations.add(ContentRecommendation(
                    title = "创意写作技�?
                    content = "提升写作创意和表达能力的实用技巧，,
                    interest = interest,
                    type = "指南"
                ))
                recommendations.add(ContentRecommendation(
                    title = "设计灵感来源",
                    content = "发现设计灵感的来源和培养创意思维的方法，,
                    interest = interest,
                    type = "灵感"
                ))
                recommendations.add(ContentRecommendation(
                    title = "创意工具推荐",
                    content = "探索有助于创意表达的应用和工具，,
                    interest = interest,
                    type = "工具"
                ))
            }
            else -> {
                // 默认推荐
                recommendations.add(ContentRecommendation(
                    title = "发现新兴�?
                    content = "探索不同领域的知识和技能，拓展你的兴趣范围�?
                    interest = interest,
                    type = "探索"
                ))
                recommendations.add(ContentRecommendation(
                    title = "学习资源推荐",
                    content = "发现与你兴趣相关的优质学习资源和资料�?
                    interest = interest,
                    type = "资源"
                ))
            }
        }
        
        return recommendations
    }
    
    /**
     * 生成推荐摘要
     */
    suspend fun generateRecommendationSummary(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        val recommendations = generateRecommendations(userId, messages, userProfile)
        
        buildString {
            appendLine("# 为你推荐")
            appendLine()
            
            if (recommendations.isEmpty()) {
                appendLine("暂时没有推荐内容，继续与我交流以获取个性化推荐的）
            } else {
                recommendations.forEachIndexed { index, recommendation ->
                    appendLine("${index + 1}. ${recommendation.title}")
                    appendLine("   ${recommendation.content}")
                    appendLine("   类型: ${recommendation.type}")
                    appendLine()
                }
            }
        }
    }
    
    /**
     * 生成兴趣对话开场白
     */
    suspend fun generateInterestOpening(userId: String, messages: List<ChatMessage>, userProfile: HonzonUserProfile? = null): String = withContext(Dispatchers.IO) {
        val interestProfile = interestAnalyzer.analyzeInterests(messages, userProfile)
        
        val primaryInterest = interestProfile.primaryInterest
        
        if (primaryInterest != null) {
            val openings = mapOf(
                "技�?to listOf(
                    "我注意到你对技术很感兴趣，最近有什么技术问题想讨论吗？",
                    "作为技术爱好者，你最近在学习或使用什么技术栈�?
                    "技术领域发展很快，你对哪方面的技术最感兴趣？"
                ),
                "科技" to listOf(
                    "科技前沿总是令人兴奋，你最近关注哪些科技领域的发展？",
                    "作为科技爱好者，你对AI的未来发展有什么看法？",
                    "科技正在改变我们的生活，你觉得最有潜力的科技方向是什么？"
                ),
                "娱乐" to listOf(
                    "娱乐是生活的重要组成部分，你最近喜欢什么电影或音乐�?
                    "作为娱乐爱好者，你有什么推荐的电影或游戏吗�?
                    "娱乐方式多种多样，你最喜欢哪种放松方方�?
                ),
                "学习" to listOf(
                    "学习是终身的事业，你最近在学习什么新知识�?
                    "作为学习者，你有什么高效的学习方法可以分享吗？",
                    "学习过程中遇到过什么挑战，需要我帮忙吗？"
                ),
                "工作" to listOf(
                    "工作占据了我们生活的重要部分，你对目前的工作满意吗？",
                    "作为职场人士，你在工作中遇到过什么挑战？",
                    "职业发展是每个人都关心的话题，你对未来的职业规划是什么？"
                ),
                "生活" to listOf(
                    "生活需要平衡，你平时喜欢做什么来放松自己�?
                    "作为生活达人，你有什么生活小窍门可以分享吗？",
                    "生活中总会有各种挑战，你最近遇到过什么开心的事情�?
                ),
                "新闻" to listOf(
                    "关注新闻可以让我们了解世界，你最近最关心的新闻话题是什么？",
                    "作为新闻关注者，你对当前的国际形势有什么看法？",
                    "新闻总是在不断更新，你觉得哪些新闻对你的生活影响最大？"
                ),
                "创意" to listOf(
                    "创意是推动世界发展的动力，你最近有什么创意想法吗�?
                    "作为创意人士，你通常从哪里获取灵感？",
                    "创意表达有很多形式，你最喜欢哪种创意表达方方�?
                )
            )
            
            val openingList = openings[primaryInterest] ?: openings["生活"]!!
            openingList.random()
        } else {
            "你好！很高兴能和你交流。最近有什么想聊的话题吗？"
        }
    }
}