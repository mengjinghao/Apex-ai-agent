package com.apex.agent.core.security

import com.apex.util.AppLogger
import java.util.regex.Pattern

/**
 * 隐藏字符扫描�? *
 * 检测并清除输入文本中的零宽字符、双向覆盖字符（BiDi override�? * 及其他不可见 Unicode 控制字符，防止通过隐藏内容绕过安全检查�? */
class HiddenCharScanner {

    companion object {
        private const val TAG = "HiddenCharScanner"

        // 零宽字符：Zero Width Space, Non-Joiner, Joiner, Left-to-Right Mark, Right-to-Left Mark
        private val ZERO_WIDTH_PATTERN: Pattern = Pattern.compile(
            "[\\u200B-\\u200F]"
        )

        // 双向覆盖字符：Left-to-Right Override, Right-to-Left Override, Left-to-Right Embedding,
        // Right-to-Left Embedding, Pop Directional Formatting
        private val BIDI_OVERRIDE_PATTERN: Pattern = Pattern.compile(
            "[\\u202A-\\u202E]"
        )

        // 其他不可见控制字符：Byte Order Mark, 不可见分隔符, 不可见加�?
        // 单词连接�? 行分隔符, 段分隔符, 从左到右标记, 对象替换字符
        private val INVISIBLE_CONTROL_PATTERN: Pattern = Pattern.compile(
            "[\\uFEFF\\u2060-\\u2064\\u206A-\\u206F\\uFFF9-\\uFFFB\\u00AD\\u061C\\u180E]"
        )

        // 变体选择符（可使字符不可见）
        private val VARIATION_SELECTOR_PATTERN: Pattern = Pattern.compile(
            "[\\u180B-\\u180D\\uFE00-\\uFE0F\\uE0100-\\uE01EF]"
        )

        // 标签字符（Unicode Tag Characters，常用于隐藏信息�?        private val TAG_CHAR_PATTERN: Pattern = Pattern.compile(
            "[\\uE0001\\uE0020-\\uE007F]"
        )
    }

    /**
     * 扫描输入文本中的隐藏字符
     *
     * @param input 待扫描的文本
     * @return 发现的隐藏字符列�?     */
    fun scan(input: String): List<HiddenCharFinding> {
        if (input.isEmpty()) return emptyList()

        val findings = mutableListOf<HiddenCharFinding>()

        scanPattern(input, ZERO_WIDTH_PATTERN, HiddenCharType.ZERO_WIDTH, findings)
        scanPattern(input, BIDI_OVERRIDE_PATTERN, HiddenCharType.BIDI_OVERRIDE, findings)
        scanPattern(input, INVISIBLE_CONTROL_PATTERN, HiddenCharType.INVISIBLE_CONTROL, findings)
        scanPattern(input, VARIATION_SELECTOR_PATTERN, HiddenCharType.VARIATION_SELECTOR, findings)
        scanPattern(input, TAG_CHAR_PATTERN, HiddenCharType.TAG_CHAR, findings)

        if (findings.isNotEmpty()) {
            AppLogger.d(TAG, "发现 ${findings.size} 个隐藏字�?)
        }

        return findings
    }

    /**
     * 清除输入文本中的所有隐藏字�?     *
     * @param input 原始文本
     * @return 清除隐藏字符后的文本
     */
    fun stripHiddenChars(input: String): String {
        var result = input
        result = ZERO_WIDTH_PATTERN.matcher(result).replaceAll("")
        result = BIDI_OVERRIDE_PATTERN.matcher(result).replaceAll("")
        result = INVISIBLE_CONTROL_PATTERN.matcher(result).replaceAll("")
        result = VARIATION_SELECTOR_PATTERN.matcher(result).replaceAll("")
        result = TAG_CHAR_PATTERN.matcher(result).replaceAll("")
        return result
    }

    /**
     * 检查文本是否包含隐藏字�?     */
    fun containsHiddenChars(input: String): Boolean {
        return ZERO_WIDTH_PATTERN.matcher(input).find()
            || BIDI_OVERRIDE_PATTERN.matcher(input).find()
            || INVISIBLE_CONTROL_PATTERN.matcher(input).find()
            || VARIATION_SELECTOR_PATTERN.matcher(input).find()
            || TAG_CHAR_PATTERN.matcher(input).find()
    }

    private fun scanPattern(
        input: String,
        pattern: Pattern,
        type: HiddenCharType,
        findings: MutableList<HiddenCharFinding>
    ) {
        val matcher = pattern.matcher(input)
        while (matcher.find()) {
            findings.add(
                HiddenCharFinding(
                    type = type,
                    confidence = 1.0f,
                    position = matcher.start(),
                    matchedText = matcher.group(),
                    description = type.description
                )
            )
        }
    }
}

/** 隐藏字符类型 */
enum class HiddenCharType(val description: String) {
    ZERO_WIDTH("零宽字符（Zero-Width Characters�?),
    BIDI_OVERRIDE("双向覆盖字符（BiDi Override�?),
    INVISIBLE_CONTROL("不可见控制字符（Invisible Control Characters�?),
    VARIATION_SELECTOR("变体选择符（Variation Selectors�?),
    TAG_CHAR("标签字符（Unicode Tag Characters�?)
}

/** 隐藏字符发现记录 */
data class HiddenCharFinding(
    val type: HiddenCharType,
    val confidence: Float,
    val position: Int,
    val matchedText: String,
    val description: String
)
