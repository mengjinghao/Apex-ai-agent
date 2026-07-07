package com.apex.agent.core.normal.thinking

/**
 * F10: 思考链注解与可视化
 *
 * 在 <think> 块中提取推理步骤，每步标注"前提/推理/结论"，
 * UI 端可折叠展示。支持用户点击某步追问"为什么这一步？"
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 思考是 Agent 内部
 * - 狂暴用 CoT/ToT 做执行（不展示给用户）
 * - 本功能是**给用户看的可解释性**，是单 Agent 透明度的核心
 */

/**
 * 思考步骤
 */
data class ThinkingStep(
    val id: String,
    val index: Int,
    val type: StepType,
    val premise: String,        // 前提
    val reasoning: String,      // 推理过程
    val conclusion: String,     // 结论
    val confidence: Float = 1.0f,
    val evidence: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val subQuestions: List<String> = emptyList()
)

enum class StepType {
    OBSERVATION,    // 观察
    HYPOTHESIS,     // 假设
    DEDUCTION,      // 演绎
    INDUCTION,      // 归纳
    ABDUCTION,      // 溯因
    EVALUATION,     // 评估
    DECISION,       // 决策
    VERIFICATION    // 验证
}

/**
 * 思考链
 */
data class ThinkingChain(
    val id: String,
    val topic: String,
    val steps: List<ThinkingStep>,
    val finalConclusion: String,
    val overallConfidence: Float,
    val totalDurationMs: Long,
    val parsedAt: Long = System.currentTimeMillis()
) {
    /**
     * 生成可读的思考链文本
     */
    fun toReadableText(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ 思考链：$topic ═══")
        steps.forEach { step ->
            sb.appendLine()
            sb.appendLine("【步骤 ${step.index + 1}: ${step.type}】")
            if (step.premise.isNotBlank()) sb.appendLine("  前提: ${step.premise}")
            sb.appendLine("  推理: ${step.reasoning}")
            sb.appendLine("  结论: ${step.conclusion}")
            if (step.confidence < 1.0f) {
                sb.appendLine("  置信度: ${(step.confidence * 100).toInt()}%")
            }
            if (step.evidence.isNotEmpty()) {
                sb.appendLine("  证据: ${step.evidence.joinToString("; ")}")
            }
            if (step.assumptions.isNotEmpty()) {
                sb.appendLine("  假设: ${step.assumptions.joinToString("; ")}")
            }
            if (step.subQuestions.isNotEmpty()) {
                sb.appendLine("  待解问题: ${step.subQuestions.joinToString("; ")}")
            }
        }
        sb.appendLine()
        sb.appendLine("═══ 最终结论 ═══")
        sb.appendLine(finalConclusion)
        sb.appendLine("整体置信度: ${(overallConfidence * 100).toInt()}%")
        return sb.toString()
    }

    /**
     * 生成 Markdown 格式
     */
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("## 思考链：$topic\n")
        steps.forEach { step ->
            sb.appendLine("### 步骤 ${step.index + 1}: ${step.type}")
            sb.appendLine("- **前提**: ${step.premise}")
            sb.appendLine("- **推理**: ${step.reasoning}")
            sb.appendLine("- **结论**: ${step.conclusion}")
            if (step.confidence < 1.0f) {
                sb.appendLine("- **置信度**: ${(step.confidence * 100).toInt()}%")
            }
            sb.appendLine()
        }
        sb.appendLine("### 最终结论")
        sb.appendLine(finalConclusion)
        return sb.toString()
    }
}

/**
 * 思考链解析器
 */
class ThinkingChainParser {

    /**
     * 从 <think> 块解析思考链
     */
    fun parse(thinkBlock: String, topic: String = ""): ThinkingChain {
        val start = System.currentTimeMillis()
        val content = thinkBlock
            .removePrefix("<think>").removePrefix("<thinking>")
            .removeSuffix("</think>").removeSuffix("</thinking>")
            .trim()

        val steps = mutableListOf<ThinkingStep>()

        // 按步骤分隔符切分
        val stepPattern = Regex("(?m)^(?:步骤\\s*\\d+[：:.]?|Step\\s*\\d+[:.]?|#\\s*\\d+|\\d+[.、)）])\\s*(.*)")
        val chunks = splitIntoChunks(content, stepPattern)

        chunks.forEachIndexed { index, chunk ->
            val step = parseStep(chunk, index)
            steps.add(step)
        }

        // 如果没有识别出步骤，把整个内容作为单步
        if (steps.isEmpty() && content.isNotBlank()) {
            steps.add(ThinkingStep(
                id = "step_0",
                index = 0,
                type = StepType.OBSERVATION,
                premise = "",
                reasoning = content,
                conclusion = content.take(100)
            ))
        }

        val finalConclusion = extractFinalConclusion(content, steps)
        val overallConfidence = steps.map { it.confidence }.average().toFloat()

        return ThinkingChain(
            id = "chain_${System.currentTimeMillis()}",
            topic = topic.ifBlank { extractTopic(content) },
            steps = steps,
            finalConclusion = finalConclusion,
            overallConfidence = overallConfidence,
            totalDurationMs = System.currentTimeMillis() - start
        )
    }

    /**
     * 从完整响应中提取思考链
     */
    fun extractFromResponse(response: String, topic: String = ""): ThinkingChain? {
        val thinkRegex = Regex("<(?:think|thinking)>(.*?)</(?:think|thinking)>", RegexOption.DOT_MATCHES_ALL)
        val match = thinkRegex.find(response) ?: return null
        return parse(match.groupValues[1], topic)
    }

    // ============ 内部方法 ============

    private fun splitIntoChunks(content: String, stepPattern: Regex): List<String> {
        val lines = content.lines()
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (line in lines) {
            if (stepPattern.containsMatchIn(line) && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current = StringBuilder()
            }
            current.appendLine(line)
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())

        return chunks
    }

    private fun parseStep(chunk: String, index: Int): ThinkingStep {
        val type = detectStepType(chunk)
        val premise = extractSection(chunk, listOf("前提", "premise", "given", "已知"))
        val reasoning = extractSection(chunk, listOf("推理", "reasoning", "process", "分析", "思考"))
            .ifBlank { chunk.take(200) }
        val conclusion = extractSection(chunk, listOf("结论", "conclusion", "因此", "所以", "thus", "therefore"))
            .ifBlank { chunk.lines().lastOrNull()?.take(100) ?: "" }
        val confidence = extractConfidence(chunk)
        val evidence = extractList(chunk, listOf("证据", "evidence"))
        val assumptions = extractList(chunk, listOf("假设", "assumption"))
        val subQuestions = extractList(chunk, listOf("待解", "疑问", "question"))

        return ThinkingStep(
            id = "step_$index",
            index = index,
            type = type,
            premise = premise,
            reasoning = reasoning,
            conclusion = conclusion,
            confidence = confidence,
            evidence = evidence,
            assumptions = assumptions,
            subQuestions = subQuestions
        )
    }

    private fun detectStepType(text: String): StepType {
        val t = text.lowercase()
        return when {
            t.containsAny("观察", "observe", "看到", "注意到") -> StepType.OBSERVATION
            t.containsAny("假设", "hypothesis", "假设", "猜测") -> StepType.HYPOTHESIS
            t.containsAny("演绎", "deduce", "推导", "因此") -> StepType.DEDUCTION
            t.containsAny("归纳", "induce", "总结", "综上") -> StepType.INDUCTION
            t.containsAny("溯因", "abduce", "可能是因为") -> StepType.ABDUCTION
            t.containsAny("评估", "evaluate", "衡量", "比较") -> StepType.EVALUATION
            t.containsAny("决定", "decide", "选择", "决策") -> StepType.DECISION
            t.containsAny("验证", "verify", "确认", "检查") -> StepType.VERIFICATION
            else -> StepType.DEDUCTION
        }
    }

    private fun extractSection(text: String, keywords: List<String>): String {
        for (kw in keywords) {
            val pattern = Regex("$kw[：:]\\s*(.*?)(?=\\n[\\u4e00-\\u9fa5a-zA-Z]+[：:]|$)", RegexOption.DOT_MATCHES_ALL)
            pattern.find(text)?.let { return it.groupValues[1].trim() }
        }
        return ""
    }

    private fun extractConfidence(text: String): Float {
        val patterns = listOf(
            Regex("(\\d+)%\\s*(?:置信|确信|confidence|sure)"),
            Regex("(?:置信|确信|confidence|sure)(?:度)?[：:]?\\s*(\\d+)%?"),
            Regex("(\\d+\\.\\d+)\\s*/\\s*1\\.0")
        )
        for (pattern in patterns) {
            pattern.find(text)?.let { m ->
                val v = m.groupValues[1].toFloatOrNull() ?: return@let
                return if (v > 1) v / 100f else v
            }
        }
        return 1.0f
    }

    private fun extractList(text: String, keywords: List<String>): List<String> {
        for (kw in keywords) {
            val pattern = Regex("$kw[：:]\\s*(.*?)(?=\\n[\\u4e00-\\u9fa5a-zA-Z]+[：:]|$)", RegexOption.DOT_MATCHES_ALL)
            pattern.find(text)?.let { m ->
                return m.groupValues[1].split(Regex("[;；\\n]|\\d+[.、)]"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    private fun extractFinalConclusion(content: String, steps: List<ThinkingStep>): String {
        // 尝试找"最终结论"标记
        val patterns = listOf("最终结论", "final conclusion", "结论", "conclusion")
        for (kw in patterns) {
            val pattern = Regex("$kw[：:]\\s*(.*?)$", RegexOption.DOT_MATCHES_ALL)
            pattern.find(content)?.let { return it.groupValues[1].trim().take(300) }
        }
        // 否则取最后一步的结论
        return steps.lastOrNull()?.conclusion ?: ""
    }

    private fun extractTopic(content: String): String {
        val firstLine = content.lines().firstOrNull()?.take(50) ?: ""
        return firstLine.replace(Regex("^(步骤\\s*\\d+[：:.]?|Step\\s*\\d+[:.]?|#\\s*\\d+|\\d+[.、)）])\\s*"), "")
            .ifBlank { "分析" }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }
}

/**
 * 思考链交互查询
 *
 * 支持用户对某个步骤追问"为什么"
 */
class ThinkingChainInquirer {

    /**
     * 生成对某步骤的追问 prompt
     */
    fun generateWhyPrompt(chain: ThinkingChain, stepIndex: Int): String {
        val step = chain.steps.getOrNull(stepIndex) ?: return ""
        return buildString {
            appendLine("关于以下思考步骤的追问：")
            appendLine("步骤 ${step.index + 1} (${step.type})")
            appendLine("前提: ${step.premise}")
            appendLine("推理: ${step.reasoning}")
            appendLine("结论: ${step.conclusion}")
            appendLine()
            appendLine("请解释：为什么这一步的推理是合理的？有没有其他可能性？")
        }
    }

    /**
     * 生成对整条链的批判性追问
     */
    fun generateCritiquePrompt(chain: ThinkingChain): String {
        return buildString {
            appendLine("请批判性审视以下思考链，指出可能的逻辑漏洞或未考虑的因素：")
            appendLine()
            appendLine(chain.toReadableText())
            appendLine()
            appendLine("请从以下角度分析：")
            appendLine("1. 前提是否成立？")
            appendLine("2. 推理过程是否有跳跃？")
            appendLine("3. 是否有遗漏的证据？")
            appendLine("4. 结论是否过度泛化？")
            appendLine("5. 有无替代解释？")
        }
    }
}
