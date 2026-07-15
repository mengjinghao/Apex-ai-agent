package com.apex.api.chat.enhance

import com.apex.util.AppLogger
import com.apex.api.chat.enhance.ConversationScenario

/**
 * 动态模型参数适配�?* 根据用户提问类型自动调整模型参数，提升回答质量和适配�?*/
object DynamicModelParamsAdapter {
    private const val TAG = "DynamicModelParams"

    /**
     * 对话场景枚举
     */
    enum class ConversationScenario {
        PROFESSIONAL, // 专业严谨场景
        CREATIVE,     // 创意创作场景
        BALANCED      // 通用平衡场景
    }

    /**
     * 模型参数数据�?    */
    data class ModelParams(
        val temperature: Double = 0.6,
        val top_p: Double = 0.9,
        val frequency_penalty: Double = 0.2,
        val presence_penalty: Double = 0.1
    )

    /**
     * 场景匹配规则
     */
    private data class ScenarioRule(
        val scenario: ConversationScenario,
        val keywords: List<String>,
        val params: ModelParams
    )

    /**
     * 预定义的场景匹配规则
     */
    private val scenarioRules = listOf(
        // 专业严谨场景：代码、编程、计算、数据、法律、医疗等
        ScenarioRule(
            scenario = ConversationScenario.PROFESSIONAL,
            keywords = listOf(
                "代码", "编程", "函数", "计算", "数据", "公式", "法律", "医疗",
                "公文", "报告", "事实", "是什�? "多少", "几号", "算法",
                "统计", "分析", "诊断", "合同", "条款", "学术", "论文"
            ),
            params = ModelParams(
                temperature = 0.3,
                top_p = 0.7,
                frequency_penalty = 0.1,
                presence_penalty = 0.1
            )
        ),
        // 创意创作场景：故事、小说、文案、广告、头脑风暴等
        ScenarioRule(
            scenario = ConversationScenario.CREATIVE,
            keywords = listOf(
                "写一�? "创作", "故事", "小说", "文案", "slogan", "广告�?
                "头脑风暴", "创意", "谐音", "段子", "笑话", "灵感", "想象",
                "虚构", "情节", "角色", "人物", "对话", "剧本"
            ),
            params = ModelParams(
                temperature = 0.9,
                top_p = 0.95,
                frequency_penalty = 0.3,
                presence_penalty = 0.2
            )
        )
    )

    /**
     * 通用平衡场景的默认参�?    */
    private val defaultParams = ModelParams(
        temperature = 0.6,
        top_p = 0.9,
        frequency_penalty = 0.2,
        presence_penalty = 0.1
    )

    /**
     * 根据用户输入获取适配的模型参�?    * @param userInput 用户输入文本
     * @return 适配后的模型参数
     */
    fun getDynamicModelParams(userInput: String): ModelParams {
        val inputLower = userInput.lowercase()
        
        // 检查每个规�?
    for (rule in scenarioRules) {
            if (matchesAnyKeyword(inputLower, rule.keywords)) {
                AppLogger.d(TAG, "匹配到场�?${rule.scenario.name}, 用户输入: ${userInput.take(50)}")
        return rule.params
            }
        }
        
        // 默认使用通用平衡场景
        AppLogger.d(TAG, "使用默认平衡场景参数")
        return defaultParams
    }

    /**
     * 检查输入是否包含任何关键词
     */
    private fun matchesAnyKeyword(input: String, keywords: List<String>): Boolean {
        return keywords.any { keyword ->
            input.contains(keyword.lowercase())
        }
    }

    /**
     * 获取场景描述，用于调试和日志
     */
    fun getScenarioDescription(userInput: String): String {
        val inputLower = userInput.lowercase()
        for (rule in scenarioRules) {
            if (matchesAnyKeyword(inputLower, rule.keywords)) {
                return when (rule.scenario) {
                    ConversationScenario.PROFESSIONAL -> "专业严谨"
                    ConversationScenario.CREATIVE -> "创意创作"
                    ConversationScenario.BALANCED -> "通用平衡"
                }
            }
        }
        return "通用平衡"
    }
}
