package com.apex.agent.core.security

import com.apex.util.AppLogger
import java.util.regex.Pattern
import com.apex.agent.core.normal.toolpreview.RiskLevel

/**
 * 提示注入检测器
 *
 * 通过规则引擎匹配已知的提示注入模式（中英文），检测试图改?AI 角色? * 绕过安全限制等恶意输入? */
class PromptInjectionDetector {

    companion object {
        private const val TAG = "PromptInjectionDetector"

        // 已知注入模式（不区分大小写）
        private val INJECTION_PATTERNS: List<InjectionPattern> = buildList {
            // ===== 英文模式 =====
            add(InjectionPattern(
                pattern = Pattern.compile("ignore\\s+(all\\s+)?previous\\s+instructions", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("disregard\\s+(all\\s+)?previous\\s+(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("forget\\s+(all\\s+)?previous\\s+(instructions|rules|context)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_ASSIGNMENT,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("act\\s+as\\s+(?:a\\s+)?(?:new|different)\\s+", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_ASSIGNMENT,
                baseConfidence = 0.7f,
                severity = RiskLevel.MEDIUM
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("pretend\\s+(?:to\\s+be|you\\s+are)\\s+", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_HIJACK,
                baseConfidence = 0.8f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("(?:^|\\n)\\s*system\\s*:", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_MARKER,
                baseConfidence = 0.95f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("(?:^|\\n)\\s*assistant\\s*:", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_MARKER,
                baseConfidence = 0.8f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("(?:^|\\n)\\s*user\\s*:", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_MARKER,
                baseConfidence = 0.7f,
                severity = RiskLevel.MEDIUM
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("new\\s+instructions\\s*:", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("override\\s+(previous|prior|all|your)\\s+(instructions|rules|settings|guidelines)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("(?:do|make)\\s+not\\s+follow\\s+(your\\s+)?(previous|prior|original)\\s+(instructions|rules)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.85f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("bypass\\s+(your\\s+)?(safety|security|content)\\s+(filters?|restrictions?|guidelines)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.SAFETY_BYPASS,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("DAN\\s+mode|do\\s+anything\\s+now", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.JAILBREAK,
                baseConfidence = 0.95f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("enter\\s+(developer|admin|root|god)\\s+mode", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.JAILBREAK,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("respond\\s+as\\s+(?:a\\s+)?(?:system|admin|root)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_HIJACK,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))

            // ===== 中文模式 =====
            add(InjectionPattern(
                pattern = Pattern.compile("忽略之前所有）?(指令|指示|规则|设定?),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("无视之前所有）?(指令|指示|规则|设定?),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("忘记之前所有）?(指令|指示|规则|设定|内容?),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("你现在是"),
                type = InjectionPatternType.ROLE_ASSIGNMENT,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("请你扮演"),
                type = InjectionPatternType.ROLE_ASSIGNMENT,
                baseConfidence = 0.7f,
                severity = RiskLevel.MEDIUM
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("假设你是"),
                type = InjectionPatternType.ROLE_ASSIGNMENT,
                baseConfidence = 0.7f,
                severity = RiskLevel.MEDIUM
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("从现在开始[?]?是一个|将成为|扮演?),
                type = InjectionPatternType.ROLE_ASSIGNMENT,
                baseConfidence = 0.8f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("新的指令[?]"),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("覆盖(之前|原有|你的所有）?(指令|规则|设定?),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("不要遵守(之前|你的|原来的）(任何?(指令|规则|设定|限制?),
                type = InjectionPatternType.INSTRUCTION_OVERRIDE,
                baseConfidence = 0.85f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("绕过(你的|安全?(限制|防护|过滤|检查）"),
                type = InjectionPatternType.SAFETY_BYPASS,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("进入(开发者|管理员|根|上帝模式"),
                type = InjectionPatternType.JAILBREAK,
                baseConfidence = 0.85f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("解除(所有）?(限制|约束|安全限制?),
                type = InjectionPatternType.SAFETY_BYPASS,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("以系统身份回答|以管理员身份回答"),
                type = InjectionPatternType.ROLE_HIJACK,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
        }

        // 角色劫持专用模式（更宽泛的匹配）
        private val ROLE_HIJACK_PATTERNS: List<InjectionPattern> = buildList {
            add(InjectionPattern(
                pattern = Pattern.compile("from\\s+now\\s+on\\s*,?\\s*(?:you\\s+are|act\\s+as|be|become)\\s+", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_HIJACK,
                baseConfidence = 0.8f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("你的新角色是|你的新身份是"),
                type = InjectionPatternType.ROLE_HIJACK,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("不再??:任何?(?:限制|约束?),
                type = InjectionPatternType.SAFETY_BYPASS,
                baseConfidence = 0.85f,
                severity = RiskLevel.HIGH
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("you\\s+have\\s+no\\s+(restrictions?|limitations?|rules)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.SAFETY_BYPASS,
                baseConfidence = 0.9f,
                severity = RiskLevel.CRITICAL
            ))
            add(InjectionPattern(
                pattern = Pattern.compile("switch\\s+to\\s+(?:a\\s+)?(?:new\\s+)?(?:role|persona|identity)", Pattern.CASE_INSENSITIVE),
                type = InjectionPatternType.ROLE_HIJACK,
                baseConfidence = 0.8f,
                severity = RiskLevel.HIGH
            ))
        }
    }

    /**
     * 检测已知注入模?     *
     * @param input 待检测的文本
     * @return 发现的注入模式列?     */
    fun detectInjectionPatterns(input: String): List<InjectionFinding> {
        if (input.isEmpty()) return emptyList()

        val findings = mutableListOf<InjectionFinding>()

        INJECTION_PATTERNS.forEach { injectionPattern ->
            val matcher = injectionPattern.pattern.matcher(input)
            while (matcher.find()) {
                findings.add(
                    InjectionFinding(
                        pattern = matcher.group(),
                        confidence = injectionPattern.baseConfidence,
                        position = matcher.start(),
                        severity = injectionPattern.severity,
                        type = injectionPattern.type
                    )
                )
                // 每种模式只记录首次匹配，避免重复
                break
            }
        }

        return findings
    }

    /**
     * 检测角色劫持模?     *
     * @param input 待检测的文本
     * @return 发现的角色劫持发现列?     */
    fun detectRoleHijacking(input: String): List<InjectionFinding> {
        if (input.isEmpty()) return emptyList()

        val findings = mutableListOf<InjectionFinding>()

        ROLE_HIJACK_PATTERNS.forEach { hijackPattern ->
            val matcher = hijackPattern.pattern.matcher(input)
            while (matcher.find()) {
                findings.add(
                    InjectionFinding(
                        pattern = matcher.group(),
                        confidence = hijackPattern.baseConfidence,
                        position = matcher.start(),
                        severity = hijackPattern.severity,
                        type = hijackPattern.type
                    )
                )
                break
            }
        }

        return findings
    }

    /**
     * 综合检测：注入模式 + 角色劫持
     *
     * @param input 待检测的文本
     * @return 所有发现的注入/劫持列表
     */
    fun detectAll(input: String): List<InjectionFinding> {
        val patternFindings = detectInjectionPatterns(input)
        val hijackFindings = detectRoleHijacking(input)

        val allFindings = patternFindings + hijackFindings

        if (allFindings.isNotEmpty()) {
            AppLogger.w(TAG, "检测到 ${allFindings.size} 个提示注角色劫持模式")
        }

        return allFindings
    }
}

/** 注入模式类型 */
enum class InjectionPatternType {
    /** 指令覆盖：试图让 AI 忽略之前的指?*/
    INSTRUCTION_OVERRIDE,
    /** 角色分配：试图给 AI 分配新角?*/
    ROLE_ASSIGNMENT,
    /** 角色劫持：试图改?AI 的身份和行为 */
    ROLE_HIJACK,
    /** 角色标记：伪装系助手/用户角色标记 */
    ROLE_MARKER,
    /** 安全绕过：试图绕过安全限?*/
    SAFETY_BYPASS,
    /** 越狱：已知的越狱攻击模式 */
    JAILBREAK
}

/** 注入模式定义 */
data class InjectionPattern(
    val pattern: Pattern,
    val type: InjectionPatternType,
    val baseConfidence: Float,
    val severity: RiskLevel
)

/** 注入发现记录 */
data class InjectionFinding(
    val pattern: String,
    val confidence: Float,
    val position: Int,
    val severity: RiskLevel,
    val type: InjectionPatternType
)
