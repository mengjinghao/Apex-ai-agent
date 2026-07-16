package com.apex.agent.core.security

import com.apex.util.AppLogger
import java.util.Base64
import java.util.regex.Pattern

/**
 * 外部数据消毒�? *
 * 对来自外部数据源（HTML、PDF、DOCX 等提取的文本）进行二次消毒，
 * 检�?HTML 注释负载、Base64 编码内容、脚本引用等潜在威胁�? */
class ExternalDataSanitizer {

    companion object {
        private const val TAG = "ExternalDataSanitizer"

        // HTML 注释模式
        private val HTML_COMMENT_PATTERN: Pattern = Pattern.compile(
            "<!--([\\s\\S]*)-->", Pattern.CASE_INSENSITIVE
        )

        // HTML 注释中的可疑内容
        private val COMMENT_PAYLOAD_PATTERNS: List<Pattern> = listOf(
            Pattern.compile("<script[\\s\\S]*?>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<iframe[\\s\\S]*?>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<object[\\s\\S]*?>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<embed[\\s\\S]*?>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("url\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("import\\s+['\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("vbscript\\s*:", Pattern.CASE_INSENSITIVE)
        )

        // Base64 编码内容检测（至少 20 字符的连�?Base64 字符串）
        private val BASE64_PATTERN: Pattern = Pattern.compile(
            "(?:[A-Za-z0-9+/]{20,}={0,2})"
        )

        // Base64 data URI 模式
        private val BASE64_DATA_URI_PATTERN: Pattern = Pattern.compile(
            "data:[^;]+;base64,([A-Za-z0-9+/]+=*)", Pattern.CASE_INSENSITIVE
        )

        // 脚本标签
        private val SCRIPT_TAG_PATTERN: Pattern = Pattern.compile(
            "<script[\\s\\S]*?>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE
        )

        // 脚本开始标签（无闭合）
        private val SCRIPT_OPEN_TAG_PATTERN: Pattern = Pattern.compile(
            "<script[^>]*>", Pattern.CASE_INSENSITIVE
        )

        // 事件处理器属�?        private val EVENT_HANDLER_PATTERN: Pattern = Pattern.compile(
            "\\bon\\w+\\s*=\\s*[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE
        )

        // javascript: 协议
        private val JAVASCRIPT_URI_PATTERN: Pattern = Pattern.compile(
            "javascript\\s*:[^\\s\"'<>]*", Pattern.CASE_INSENSITIVE
        )

        // 外部脚本 src 引用
        private val SCRIPT_SRC_PATTERN: Pattern = Pattern.compile(
            """<script[^>]+src\s*=\s*["'][^"']+["'][^>]*>""", Pattern.CASE_INSENSITIVE
        )

        // 可疑文件扩展名引�?        private val SUSPICIOUS_FILE_REF_PATTERN: Pattern = Pattern.compile(
            """(?:href|src|action)\s*=\s*["'][^"']*\.(?:js|vbs|php|asp|jsp|cgi|sh|bat|ps1|exe|dll)["']""",
            Pattern.CASE_INSENSITIVE
        )

        // 条件注释（IE 条件注释，可隐藏恶意代码�?        private val CONDITIONAL_COMMENT_PATTERN: Pattern = Pattern.compile(
            "<!--\\[if\\s+[\\s\\S]*?\\]>", Pattern.CASE_INSENSITIVE
        )

        // 最�?Base64 检测长�?        private const val MIN_BASE64_LENGTH = 20

        // Base64 解码后最小可审查长度
        private const val MIN_DECODED_REVIEW_LENGTH = 4
    }

    /**
     * 对外部提取的文本进行全面消毒
     *
     * @param input 从外部数据源（PDF/DOCX/HTML）提取的文本
     * @return 消毒结果
     */
    fun sanitize(input: String): ExternalSanitizeResult {
        if (input.isEmpty()) {
            return ExternalSanitizeResult(
                originalText = input,
                sanitizedText = input,
                findings = emptyList(),
                isClean = true,
                riskLevel = RiskLevel.CLEAN
            )
        }

        val findings = mutableListOf<ExternalFinding>()
        var sanitized = input

        // 1. HTML 注释负载检�?        val commentFindings = detectHtmlCommentPayloads(sanitized)
        findings.addAll(commentFindings)

        // 2. 脚本引用移除
        val scriptFindings = detectAndRemoveScriptReferences(sanitized)
        findings.addAll(scriptFindings.findings)
        sanitized = scriptFindings.sanitizedText

        // 3. Base64 编码内容检测与解码审查
        val base64Findings = detectBase64Content(sanitized)
        findings.addAll(base64Findings)

        // 4. 事件处理器移�?        val eventFindings = detectAndRemoveEventHandlers(sanitized)
        findings.addAll(eventFindings.findings)
        sanitized = eventFindings.sanitizedText

        // 5. 危险 URI 移除
        val uriFindings = detectAndRemoveDangerousUris(sanitized)
        findings.addAll(uriFindings.findings)
        sanitized = uriFindings.sanitizedText

        val riskLevel = determineRiskLevel(findings)
        val isClean = findings.isEmpty()

        if (!isClean) {
            AppLogger.w(TAG, "外部数据消毒发现 ${findings.size} 个潜在威�? 风险等级: ${riskLevel}")
        }

        return ExternalSanitizeResult(
            originalText = input,
            sanitizedText = sanitized,
            findings = findings,
            isClean = isClean,
            riskLevel = riskLevel
        )
    }

    /**
     * 检�?HTML 注释中的可疑内容
     */
    private fun detectHtmlCommentPayloads(input: String): List<ExternalFinding> {
        val findings = mutableListOf<ExternalFinding>()
        val commentMatcher = HTML_COMMENT_PATTERN.matcher(input)

        while (commentMatcher.find()) {
            val commentContent = commentMatcher.group(1) ?: continue

            COMMENT_PAYLOAD_PATTERNS.forEach { payloadPattern ->
                if (payloadPattern.matcher(commentContent).find()) {
                    findings.add(
                        ExternalFinding(
                            type = ExternalThreatType.HTML_COMMENT_PAYLOAD,
                            confidence = 0.85f,
                            position = commentMatcher.start(),
                            matchedText = commentMatcher.group().take(200),
                            description = "HTML 注释中包含可疑内�? ${payloadPattern.pattern()}"
                        )
                    )
                }
            }

            // 检测条件注�?            if (CONDITIONAL_COMMENT_PATTERN.matcher(commentMatcher.group()).find()) {
                findings.add(
                    ExternalFinding(
                        type = ExternalThreatType.HTML_COMMENT_PAYLOAD,
                        confidence = 0.6f,
                        position = commentMatcher.start(),
                        matchedText = commentMatcher.group().take(200),
                        description = "检测到 IE 条件注释，可能隐藏恶意代�?
                    )
                )
            }
        }

        return findings
    }

    /**
     * 检�?Base64 编码内容并解码审�?     */
    private fun detectBase64Content(input: String): List<ExternalFinding> {
        val findings = mutableListOf<ExternalFinding>()

        // 检�?data URI 中的 Base64
        val dataUriMatcher = BASE64_DATA_URI_PATTERN.matcher(input)
        while (dataUriMatcher.find()) {
            val base64Content = dataUriMatcher.group(1) ?: continue
            val decodedFinding = reviewDecodedBase64(base64Content, dataUriMatcher.start())
            if (decodedFinding != null) {
                findings.add(decodedFinding)
            }
        }

        // 检查独立的 Base64 字符�?        val base64Matcher = BASE64_PATTERN.matcher(input)
        while (base64Matcher.find()) {
            val base64Content = base64Matcher.group()
            if (base64Content.length >= MIN_BASE64_LENGTH) {
                val decodedFinding = reviewDecodedBase64(base64Content, base64Matcher.start())
                if (decodedFinding != null) {
                    findings.add(decodedFinding)
                }
            }
        }

        return findings
    }

    /**
     * 审查 Base64 解码后的内容
     */
    private fun reviewDecodedBase64(base64Content: String, position: Int): ExternalFinding? {
        return try {
            val decoded = Base64.getDecoder().decode(base64Content.trimEnd('='))
            val decodedText = String(decoded, Charsets.UTF_8)

            // 检查解码后的内容是否包含可疑模�?            if (decodedText.length >= MIN_DECODED_REVIEW_LENGTH && decodedText != base64Content) {
                val hasSuspiciousContent = COMMENT_PAYLOAD_PATTERNS.any { it.matcher(decodedText).find() }
                    || SCRIPT_OPEN_TAG_PATTERN.matcher(decodedText).find()

                if (hasSuspiciousContent) {
                    ExternalFinding(
                        type = ExternalThreatType.BASE64_ENCODED_CONTENT,
                        confidence = 0.9f,
                        position = position,
                        matchedText = "Base64 解码后包含可疑内�? ${decodedText.take(100)}",
                        description = "Base64 编码内容解码后发现脚本或危险标记"
                    )
                } else null
            } else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 检测并移除脚本引用
     */
    private fun detectAndRemoveScriptReferences(input: String): SanitizeStepResult {
        val findings = mutableListOf<ExternalFinding>()
        var sanitized = input

        // 移除完整 script 标签
        val scriptMatcher = SCRIPT_TAG_PATTERN.matcher(sanitized)
        while (scriptMatcher.find()) {
            findings.add(
                ExternalFinding(
                    type = ExternalThreatType.SCRIPT_REFERENCE,
                    confidence = 1.0f,
                    position = scriptMatcher.start(),
                    matchedText = scriptMatcher.group().take(200),
                    description = "检测到完整 script 标签并已移除"
                )
            )
        }
        sanitized = SCRIPT_TAG_PATTERN.matcher(sanitized).replaceAll("")

        // 检�?script src 引用
        val srcMatcher = SCRIPT_SRC_PATTERN.matcher(input)
        while (srcMatcher.find()) {
            findings.add(
                ExternalFinding(
                    type = ExternalThreatType.SCRIPT_REFERENCE,
                    confidence = 0.9f,
                    position = srcMatcher.start(),
                    matchedText = srcMatcher.group().take(200),
                    description = "检测到外部脚本引用"
                )
            )
        }
        sanitized = SCRIPT_SRC_PATTERN.matcher(sanitized).replaceAll("")

        // 检测可疑文件引�?        val fileRefMatcher = SUSPICIOUS_FILE_REF_PATTERN.matcher(sanitized)
        while (fileRefMatcher.find()) {
            findings.add(
                ExternalFinding(
                    type = ExternalThreatType.SCRIPT_REFERENCE,
                    confidence = 0.7f,
                    position = fileRefMatcher.start(),
                    matchedText = fileRefMatcher.group().take(200),
                    description = "检测到可疑脚本文件引用"
                )
            )
        }
        sanitized = SUSPICIOUS_FILE_REF_PATTERN.matcher(sanitized).replaceAll("")

        return SanitizeStepResult(sanitized, findings)
    }

    /**
     * 检测并移除事件处理�?     */
    private fun detectAndRemoveEventHandlers(input: String): SanitizeStepResult {
        val findings = mutableListOf<ExternalFinding>()
        val matcher = EVENT_HANDLER_PATTERN.matcher(input)

        while (matcher.find()) {
            findings.add(
                ExternalFinding(
                    type = ExternalThreatType.EVENT_HANDLER,
                    confidence = 0.9f,
                    position = matcher.start(),
                    matchedText = matcher.group().take(200),
                    description = "检测到事件处理器属性并已移�?
                )
            )
        }

        val sanitized = EVENT_HANDLER_PATTERN.matcher(input).replaceAll("")
        return SanitizeStepResult(sanitized, findings)
    }

    /**
     * 检测并移除危险 URI
     */
    private fun detectAndRemoveDangerousUris(input: String): SanitizeStepResult {
        val findings = mutableListOf<ExternalFinding>()

        val jsUriMatcher = JAVASCRIPT_URI_PATTERN.matcher(input)
        while (jsUriMatcher.find()) {
            findings.add(
                ExternalFinding(
                    type = ExternalThreatType.DANGEROUS_URI,
                    confidence = 0.95f,
                    position = jsUriMatcher.start(),
                    matchedText = jsUriMatcher.group().take(200),
                    description = "检测到 javascript: 协议 URI"
                )
            )
        }

        val sanitized = JAVASCRIPT_URI_PATTERN.matcher(input).replaceAll("")
        return SanitizeStepResult(sanitized, findings)
    }

    private fun determineRiskLevel(findings: List<ExternalFinding>): RiskLevel {
        if (findings.isEmpty()) return RiskLevel.CLEAN

        val maxSeverity = findings.maxOf { it.confidence }
        return when {
            findings.any { it.confidence >= 0.9f && it.type == ExternalThreatType.SCRIPT_REFERENCE } -> RiskLevel.CRITICAL
            maxSeverity >= 0.9f -> RiskLevel.HIGH
            maxSeverity >= 0.7f -> RiskLevel.MEDIUM
            maxSeverity >= 0.5f -> RiskLevel.LOW
            else -> RiskLevel.LOW
        }
    }

    /** 消毒步骤中间结果 */
    private data class SanitizeStepResult(
        val sanitizedText: String,
        val findings: List<ExternalFinding>
    )
}

/** 外部威胁类型 */
enum class ExternalThreatType {
    /** HTML 注释中的可疑负载 */
    HTML_COMMENT_PAYLOAD,
    /** Base64 编码的可疑内�?*/
    BASE64_ENCODED_CONTENT,
    /** 脚本引用（script 标签、外部脚本等�?*/
    SCRIPT_REFERENCE,
    /** 事件处理器（onclick, onerror 等） */
    EVENT_HANDLER,
    /** 危险 URI（javascript:, vbscript: 等） */
    DANGEROUS_URI
}

/** 外部数据消毒发现记录 */
data class ExternalFinding(
    val type: ExternalThreatType,
    val confidence: Float,
    val position: Int,
    val matchedText: String,
    val description: String
)

/** 外部数据消毒结果 */
data class ExternalSanitizeResult(
    val originalText: String,
    val sanitizedText: String,
    val findings: List<ExternalFinding>,
    val isClean: Boolean,
    val riskLevel: RiskLevel
)
