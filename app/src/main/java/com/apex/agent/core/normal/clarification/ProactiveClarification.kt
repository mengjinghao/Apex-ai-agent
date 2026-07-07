package com.apex.agent.core.normal.clarification

/**
 * F11: 主动澄清机制
 *
 * 检测到用户问题模糊（代词指代不清/术语歧义/操作目标不明）时，
 * 主动反问而非假设。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的澄清是 Agent 间
 * - 狂暴不澄清（直接 brute force）
 * - 本功能是**对用户的主动澄清**，体现单 Agent 的交互智能
 */

/**
 * 澄清需求类型
 */
enum class ClarificationType {
    /** 代词指代不清（"它"、"这个"） */
    PRONOUN_AMBIGUITY,
    /** 术语歧义（"苹果"是水果还是公司） */
    TERM_AMBIGUITY,
    /** 操作目标不明（"删除文件"哪个文件） */
    TARGET_UNCLEAR,
    /** 范围模糊（"一些"、"几个"） */
    SCOPE_VAGUE,
    /** 意图模糊（到底想要什么） */
    INTENT_VAGUE,
    /** 上下文缺失 */
    CONTEXT_MISSING,
    /** 多重可能解读 */
    MULTI_INTERPRETATION
}

/**
 * 澄清需求
 */
data class ClarificationNeed(
    val type: ClarificationType,
    val ambiguousPart: String,      // 模糊的部分
    val possibleInterpretations: List<String>,  // 可能的解读
    val suggestedQuestion: String,  // 建议的反问
    val confidence: Float,          // 检测置信度
    val options: List<String> = emptyList()  // 选项（如有）
)

/**
 * 澄清结果
 */
data class ClarificationResult(
    val needed: Boolean,
    val needs: List<ClarificationNeed>,
    val combinedQuestion: String
)

/**
 * 主动澄清检测器
 */
class ProactiveClarification {

    /**
     * 检测是否需要澄清
     */
    fun detect(userMessage: String, context: Map<String, Any> = emptyMap()): ClarificationResult {
        val needs = mutableListOf<ClarificationNeed>()

        // 1. 代词指代检测
        needs.addAll(detectPronounAmbiguity(userMessage, context))

        // 2. 术语歧义检测
        needs.addAll(detectTermAmbiguity(userMessage))

        // 3. 操作目标不明检测
        needs.addAll(detectUnclearTarget(userMessage))

        // 4. 范围模糊检测
        needs.addAll(detectVagueScope(userMessage))

        // 5. 意图模糊检测
        needs.addAll(detectVagueIntent(userMessage))

        // 6. 上下文缺失检测
        needs.addAll(detectMissingContext(userMessage, context))

        // 按置信度排序，取前 3 个
        val topNeeds = needs.sortedByDescending { it.confidence }.take(3)

        val combinedQuestion = if (topNeeds.isNotEmpty()) {
            buildCombinedQuestion(topNeeds)
        } else ""

        return ClarificationResult(
            needed = topNeeds.isNotEmpty(),
            needs = topNeeds,
            combinedQuestion = combinedQuestion
        )
    }

    /**
     * 生成澄清提示注入
     */
    fun generateClarificationPrompt(result: ClarificationResult): String {
        if (!result.needed) return ""
        val sb = StringBuilder()
        sb.appendLine("[澄清提示：检测到以下模糊之处，建议在回答前先澄清]")
        result.needs.forEach { need ->
            sb.appendLine("- ${need.type}: ${need.suggestedQuestion}")
        }
        return sb.toString()
    }

    // ============ 检测方法 ============

    private fun detectPronounAmbiguity(message: String, context: Map<String, Any>): List<ClarificationNeed> {
        val needs = mutableListOf<ClarificationNeed>()
        val pronouns = mapOf(
            "它" to "指代对象",
            "它们" to "指代对象",
            "这个" to "指代对象",
            "那个" to "指代对象",
            "这些" to "指代对象",
            "那些" to "指代对象",
            "这" to "指代对象",
            "那" to "指代对象",
            "it" to "referent",
            "this" to "referent",
            "that" to "referent",
            "these" to "referent",
            "those" to "referent"
        )

        // 如果上下文中没有明确的指代对象，标记为模糊
        val hasRecentEntity = context.containsKey("last_entity") || context.containsKey("last_subject")

        for ((pronoun, desc) in pronouns) {
            if (message.contains(pronoun, ignoreCase = true) && !hasRecentEntity) {
                needs.add(ClarificationNeed(
                    type = ClarificationType.PRONOUN_AMBIGUITY,
                    ambiguousPart = pronoun,
                    possibleInterpretations = listOf("上一次提到的对象", "当前可见的对象", "其他对象"),
                    suggestedQuestion = "你提到的「$pronoun」具体指什么？",
                    confidence = 0.8f
                ))
                break  // 只报告一个代词模糊
            }
        }
        return needs
    }

    private fun detectTermAmbiguity(message: String): List<ClarificationNeed> {
        val needs = mutableListOf<ClarificationNeed>()
        val ambiguousTerms = mapOf(
            "苹果" to listOf("水果", "公司"),
            "Java" to listOf("编程语言", "岛屿", "咖啡"),
            "Python" to listOf("编程语言", "蛇"),
            "小米" to listOf("公司", "粮食"),
            "华为" to listOf("公司", "品牌"),
            "bug" to listOf("程序错误", "昆虫"),
            " mouse" to listOf("鼠标", "老鼠")
        )

        for ((term, interpretations) in ambiguousTerms) {
            if (message.contains(term, ignoreCase = true)) {
                needs.add(ClarificationNeed(
                    type = ClarificationType.TERM_AMBIGUITY,
                    ambiguousPart = term,
                    possibleInterpretations = interpretations,
                    suggestedQuestion = "你提到的「$term」是指${interpretations.joinToString("还是")}？",
                    confidence = 0.7f,
                    options = interpretations
                ))
            }
        }
        return needs
    }

    private fun detectUnclearTarget(message: String): List<ClarificationNeed> {
        val needs = mutableListOf<ClarificationNeed>()
        val actionPatterns = mapOf(
            "删除" to "删除哪个文件/目录？",
            "打开" to "打开哪个文件/应用？",
            "发送" to "发送给谁？发送什么内容？",
            "修改" to "修改哪个文件/配置项？",
            "运行" to "运行哪个程序/脚本？",
            "delete" to "Which file to delete?",
            "open" to "Which file/app to open?",
            "send" to "Send to whom? What content?"
        )

        for ((action, question) in actionPatterns) {
            if (message.contains(action, ignoreCase = true)) {
                // 检查是否有明确的目标
                val hasTarget = message.contains(Regex("(文件|目录|应用|程序|配置)[\\s]*[\"「『]([^\"」』]+)[\"」』]")) ||
                               message.contains(Regex("/\\S+"))  // 路径
                if (!hasTarget) {
                    needs.add(ClarificationNeed(
                        type = ClarificationType.TARGET_UNCLEAR,
                        ambiguousPart = action,
                        possibleInterpretations = emptyList(),
                        suggestedQuestion = question,
                        confidence = 0.75f
                    ))
                }
            }
        }
        return needs
    }

    private fun detectVagueScope(message: String): List<ClarificationNeed> {
        val needs = mutableListOf<ClarificationNeed>()
        val vagueWords = mapOf(
            "一些" to "具体多少？",
            "几个" to "具体几个？",
            "很多" to "大约多少？",
            "少量" to "具体多少？",
            "大部分" to "大概百分之多少？",
            "some" to "how many exactly?",
            "few" to "how many exactly?",
            "many" to "approximately how many?",
            "several" to "how many exactly?"
        )

        for ((word, question) in vagueWords) {
            if (message.contains(word, ignoreCase = true)) {
                needs.add(ClarificationNeed(
                    type = ClarificationType.SCOPE_VAGUE,
                    ambiguousPart = word,
                    possibleInterpretations = listOf("1-3", "3-10", "10-50", "50+"),
                    suggestedQuestion = "你说的「$word」$question",
                    confidence = 0.6f
                ))
                break
            }
        }
        return needs
    }

    private fun detectVagueIntent(message: String): List<ClarificationNeed> {
        val needs = mutableListOf<ClarificationNeed>()
        // 过短的消息可能意图模糊
        if (message.trim().length < 5 && !message.matches(Regex("^(你好|hi|hello|谢谢).*", RegexOption.IGNORE_CASE))) {
            needs.add(ClarificationNeed(
                type = ClarificationType.INTENT_VAGUE,
                ambiguousPart = message,
                possibleInterpretations = listOf("查询信息", "执行操作", "闲聊"),
                suggestedQuestion = "你能详细说明一下想要做什么吗？",
                confidence = 0.65f
            ))
        }
        return needs
    }

    private fun detectMissingContext(message: String, context: Map<String, Any>): List<ClarificationNeed> {
        val needs = mutableListOf<ClarificationNeed>()
        // 检测代码相关但无上下文
        if (message.contains("这段代码") || message.contains("这个错误") || message.contains("this code") || message.contains("this error")) {
            if (!context.containsKey("code") && !context.containsKey("error") && !message.contains("```")) {
                needs.add(ClarificationNeed(
                    type = ClarificationType.CONTEXT_MISSING,
                    ambiguousPart = "代码/错误信息",
                    possibleInterpretations = emptyList(),
                    suggestedQuestion = "请提供相关的代码或错误信息，这样我才能帮你分析。",
                    confidence = 0.85f
                ))
            }
        }
        return needs
    }

    private fun buildCombinedQuestion(needs: List<ClarificationNeed>): String {
        if (needs.size == 1) return needs[0].suggestedQuestion

        val sb = StringBuilder()
        sb.appendLine("在回答之前，我需要确认几个问题：")
        needs.forEachIndexed { i, need ->
            sb.appendLine("${i + 1}. ${need.suggestedQuestion}")
        }
        sb.appendLine()
        sb.append("请先回答以上问题，我再给出准确回答。")
        return sb.toString()
    }
}
