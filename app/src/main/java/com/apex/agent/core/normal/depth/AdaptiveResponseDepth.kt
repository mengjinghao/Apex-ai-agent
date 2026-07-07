package com.apex.agent.core.normal.depth

import com.apex.agent.core.normal.ResponseDepth

/**
 * F2: 回答深度自适应
 *
 * 根据问题类型（事实型/解释型/操作型/创意型）+ 用户历史偏好
 * 自动调节回答深度，注入 <depth> 提示词。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 各 Agent 独立深度
 * - 狂暴不关心深度（只关心执行）
 * - 本功能专注**单 Agent 对话体验**的深度调节
 */

/**
 * 问题类型
 */
enum class QuestionType {
    /** 事实型：问"是什么" - 适合 BRIEF/STANDARD */
    FACTUAL,
    /** 解释型：问"为什么/怎么回事" - 适合 STANDARD/DETAILED */
    EXPLANATORY,
    /** 操作型：问"怎么做/如何" - 适合 DETAILED/COMPREHENSIVE */
    OPERATIONAL,
    /** 创意型：创作/头脑风暴 - 适合 DETAILED/COMPREHENSIVE */
    CREATIVE,
    /** 对比型：A vs B - 适合 DETAILED */
    COMPARATIVE,
    /** 闲聊型：打招呼/感谢 - 适合 BRIEF */
    CHITCHAT,
    /** 调试型：报错/问题排查 - 适合 DETAILED/COMPREHENSIVE */
    DEBUGGING
}

/**
 * 深度决策上下文
 */
data class DepthContext(
    val questionType: QuestionType,
    val questionLength: Int,
    val hasCode: Boolean,
    val hasTechnicalTerms: Boolean,
    val userHistoryPreference: ResponseDepth?,
    val conversationRound: Int,
    val isFollowUp: Boolean
)

/**
 * 回答深度解析器
 */
class AdaptiveResponseDepth {

    /**
     * 分析问题类型
     */
    fun analyzeQuestionType(question: String): QuestionType {
        val q = question.trim().lowercase()

        // 闲聊型
        if (q.length < 10 && q.matches(Regex("^(你好|hi|hello|hey|嗨|谢谢|thanks|ok|好的|再见).*"))) {
            return QuestionType.CHITCHAT
        }

        // 创意型
        if (q.containsAny("写", "创作", "编", "生成", "想象", "头脑风暴", "brainstorm", "write", "create", "compose", "imagine")) {
            return QuestionType.CREATIVE
        }

        // 调试型
        if (q.containsAny("报错", "错误", "bug", "异常", "失败", "不工作", "error", "exception", "fail", "crash", "debug")) {
            return QuestionType.DEBUGGING
        }

        // 操作型
        if (q.containsAny("怎么", "如何", "怎样", "步骤", "教程", "how", "how to", "steps", "tutorial", "guide")) {
            return QuestionType.OPERATIONAL
        }

        // 对比型
        if (q.containsAny("对比", "比较", "区别", "vs", "versus", "compare", "difference", "better")) {
            return QuestionType.COMPARATIVE
        }

        // 解释型
        if (q.containsAny("为什么", "原因", "原理", "机制", "怎么回事", "为什么", "why", "reason", "principle", "mechanism", "explain")) {
            return QuestionType.EXPLANATORY
        }

        // 事实型（默认）
        return QuestionType.FACTUAL
    }

    /**
     * 解析回答深度
     */
    fun resolve(question: String, userPreference: ResponseDepth? = null, isFollowUp: Boolean = false): ResponseDepth {
        val qType = analyzeQuestionType(question)
        val ctx = DepthContext(
            questionType = qType,
            questionLength = question.length,
            hasCode = question.contains("```") || question.contains(Regex("<code>|</code>")),
            hasTechnicalTerms = hasTechnicalTerms(question),
            userHistoryPreference = userPreference,
            conversationRound = 0,
            isFollowUp = isFollowUp
        )
        return resolveWithContext(ctx)
    }

    /**
     * 带上下文解析
     */
    fun resolveWithContext(ctx: DepthContext): ResponseDepth {
        // 用户历史偏好优先（除非问题类型强烈建议其他深度）
        val typeBasedDepth = when (ctx.questionType) {
            QuestionType.CHITCHAT -> ResponseDepth.BRIEF
            QuestionType.FACTUAL -> if (ctx.questionLength > 50) ResponseDepth.STANDARD else ResponseDepth.BRIEF
            QuestionType.EXPLANATORY -> ResponseDepth.DETAILED
            QuestionType.OPERATIONAL -> if (ctx.hasCode) ResponseDepth.COMPREHENSIVE else ResponseDepth.DETAILED
            QuestionType.CREATIVE -> ResponseDepth.DETAILED
            QuestionType.COMPARATIVE -> ResponseDepth.DETAILED
            QuestionType.DEBUGGING -> ResponseDepth.COMPREHENSIVE
        }

        // 追问场景降级
        val adjusted = if (ctx.isFollowUp && typeBasedDepth == ResponseDepth.COMPREHENSIVE) {
            ResponseDepth.DETAILED
        } else typeBasedDepth

        // 用户偏好微调
        return ctx.userHistoryPreference?.let { pref ->
            // 偏好与类型建议取折中
            when {
                pref.ordinal < adjusted.ordinal -> adjusted
                pref.ordinal > adjusted.ordinal && ctx.questionType != QuestionType.CHITCHAT -> pref
                else -> adjusted
            }
        } ?: adjusted
    }

    /**
     * 生成深度提示词
     */
    fun generateDepthPrompt(depth: ResponseDepth): String {
        return when (depth) {
            ResponseDepth.BRIEF -> "[深度提示：请用一句话简洁回答，不超过30字]"
            ResponseDepth.STANDARD -> "[深度提示：请用1-2个段落回答，要点清晰]"
            ResponseDepth.DETAILED -> "[深度提示：请分点详细回答，包含示例和解释]"
            ResponseDepth.COMPREHENSIVE -> "[深度提示：请深度分析，包含背景、原理、步骤、示例、注意事项、最佳实践]"
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }

    private fun hasTechnicalTerms(text: String): Boolean {
        val techPatterns = listOf(
            Regex("\\b(api|http|json|sql|python|java|kotlin|android|ios|react|vue|docker|k8s)\\b", RegexOption.IGNORE_CASE),
            Regex("(函数|变量|类|接口|协议|算法|数据库|服务器|客户端)")
        )
        return techPatterns.any { it.containsMatchIn(text) }
    }
}
