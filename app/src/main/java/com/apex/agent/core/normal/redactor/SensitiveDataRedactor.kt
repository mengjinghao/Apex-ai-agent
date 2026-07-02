package com.apex.agent.core.normal.redactor

import java.util.concurrent.ConcurrentHashMap

/**
 * F14: 敏感信息自动脱敏
 *
 * 在 prompt 注入历史/记忆前，自动识别并脱敏 API key/密码/身份证/手机号/邮箱，
 * 原始值暂存内存，响应输出时还原。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 所有模式都该有，但 NORMAL 因为"长期记忆"特性更需要
 * - 本功能聚焦 NORMAL 的记忆/总结场景，确保隐私不外泄
 */

/**
 * 敏感信息类型
 */
enum class SensitiveType {
    API_KEY,
    PASSWORD,
    ID_CARD,         // 身份证
    PHONE_NUMBER,    // 手机号
    EMAIL,
    BANK_CARD,       // 银行卡
    JWT_TOKEN,
    PRIVATE_KEY,     // 私钥
    OAUTH_TOKEN,
    SSN,             // 社会安全号
    IP_ADDRESS,
    CREDIT_CARD
}

/**
 * 脱敏匹配规则
 */
data class SensitivePattern(
    val type: SensitiveType,
    val name: String,
    val regex: Regex,
    val maskStrategy: MaskStrategy,
    val description: String
)

enum class MaskStrategy {
    /** 全部替换为 *** */
    FULL_MASK,
    /** 保留前4后4，中间 *** */
    PARTIAL_MASK,
    /** 仅保留后4位 */
    KEEP_LAST_4,
    /** 仅保留前2位 */
    KEEP_FIRST_2,
    /** 哈希替换 */
    HASH,
    /** 类型标记 + 序号 */
    TYPE_TAGGED
}

/**
 * 脱敏结果
 */
data class RedactedText(
    val original: String,
    val redacted: String,
    val mappings: Map<String, String>,  // placeholder -> original
    val detectedTypes: Set<SensitiveType>
)

/**
 * 敏感信息脱敏器
 */
class SensitiveDataRedactor {

    private val patterns = mutableListOf<SensitivePattern>()
    private val sessionMappings = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        registerBuiltinPatterns()
    }

    /**
     * 注册自定义模式
     */
    fun registerPattern(pattern: SensitivePattern) {
        patterns.add(pattern)
    }

    /**
     * 脱敏文本
     *
     * @param text 原始文本
     * @param sessionId 会话 ID（用于跨消息保持映射一致）
     */
    fun redact(text: String, sessionId: String? = null): RedactedText {
        val mappings = mutableMapOf<String, String>()
        val detectedTypes = mutableSetOf<SensitiveType>()
        var result = text

        for (pattern in patterns) {
            val matches = pattern.regex.findAll(result).toList()
            if (matches.isEmpty()) continue

            detectedTypes.add(pattern.type)

            // 从后向前替换，避免索引偏移
            for (match in matches.reversed()) {
                val original = match.value
                val placeholder = generatePlaceholder(pattern.type, original, sessionId, mappings)
                mappings[placeholder] = original
                result = result.substring(0, match.range.first) + placeholder + result.substring(match.range.last + 1)
            }
        }

        // 保存到会话映射
        if (sessionId != null && mappings.isNotEmpty()) {
            sessionMappings.computeIfAbsent(sessionId) { mutableMapOf() }.putAll(mappings)
        }

        return RedactedText(
            original = text,
            redacted = result,
            mappings = mappings,
            detectedTypes = detectedTypes
        )
    }

    /**
     * 还原脱敏文本
     */
    fun restore(text: String, mappings: Map<String, String>): String {
        var result = text
        mappings.forEach { (placeholder, original) ->
            result = result.replace(placeholder, original)
        }
        return result
    }

    /**
     * 还原（使用会话映射）
     */
    fun restoreWithSession(text: String, sessionId: String): String {
        val mappings = sessionMappings[sessionId] ?: return text
        return restore(text, mappings)
    }

    /**
     * 检测（不脱敏，仅报告）
     */
    fun detect(text: String): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        for (pattern in patterns) {
            for (match in pattern.regex.findAll(text)) {
                results.add(DetectionResult(
                    type = pattern.type,
                    name = pattern.name,
                    value = match.value,
                    range = match.range.first to match.range.last + 1,
                    description = pattern.description
                ))
            }
        }
        return results
    }

    /**
     * 清理会话映射
     */
    fun clearSession(sessionId: String) {
        sessionMappings.remove(sessionId)
    }

    /**
     * 清理所有
     */
    fun clearAll() {
        sessionMappings.clear()
    }

    // ============ 内部方法 ============

    private fun generatePlaceholder(
        type: SensitiveType,
        original: String,
        sessionId: String?,
        currentMappings: Map<String, String>
    ): String {
        // 检查是否已经映射过同一个值
        val existing = currentMappings.entries.find { it.value == original }?.key
        if (existing != null) return existing

        val seq = counter.incrementAndGet()
        return when (type) {
            SensitiveType.API_KEY -> "[API_KEY_$seq]"
            SensitiveType.PASSWORD -> "[PASSWORD_$seq]"
            SensitiveType.ID_CARD -> "[ID_CARD_$seq]"
            SensitiveType.PHONE_NUMBER -> "[PHONE_$seq]"
            SensitiveType.EMAIL -> {
                // 邮箱部分脱敏：保留首字符和域名
                val parts = original.split("@")
                if (parts.size == 2) {
                    val masked = parts[0].firstOrNull() + "***@" + parts[1]
                    return masked
                }
                "[EMAIL_$seq]"
            }
            SensitiveType.BANK_CARD -> "[BANK_CARD_$seq]"
            SensitiveType.JWT_TOKEN -> "[JWT_$seq]"
            SensitiveType.PRIVATE_KEY -> "[PRIVATE_KEY_$seq]"
            SensitiveType.OAUTH_TOKEN -> "[OAUTH_TOKEN_$seq]"
            SensitiveType.SSN -> "[SSN_$seq]"
            SensitiveType.IP_ADDRESS -> {
                // IP 部分脱敏：保留前两段
                val parts = original.split(".")
                if (parts.size == 4) "${parts[0]}.${parts[1]}.***.***"
                else "[IP_$seq]"
            }
            SensitiveType.CREDIT_CARD -> {
                // 保留后4位
                if (original.length >= 4) "****" + original.takeLast(4)
                else "[CARD_$seq]"
            }
        }
    }

    private fun registerBuiltinPatterns() {
        // API Key 模式（通用）
        patterns.add(SensitivePattern(
            type = SensitiveType.API_KEY,
            name = "OpenAI API Key",
            regex = Regex("sk-[a-zA-Z0-9]{48}"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "OpenAI API 密钥"
        ))
        patterns.add(SensitivePattern(
            type = SensitiveType.API_KEY,
            name = "Anthropic API Key",
            regex = Regex("sk-ant-[a-zA-Z0-9-_]{80,}"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "Anthropic API 密钥"
        ))
        patterns.add(SensitivePattern(
            type = SensitiveType.API_KEY,
            name = "GitHub Token",
            regex = Regex("gh[ps]_[A-Za-z0-9]{36}"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "GitHub Personal Access Token"
        ))
        patterns.add(SensitivePattern(
            type = SensitiveType.API_KEY,
            name = "Generic API Key",
            regex = Regex("(?i)(?:api[_-]?key|api[_-]?secret)[\"'\\s:=]+([a-zA-Z0-9]{32,})"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "通用 API 密钥"
        ))

        // JWT Token
        patterns.add(SensitivePattern(
            type = SensitiveType.JWT_TOKEN,
            name = "JWT Token",
            regex = Regex("eyJ[a-zA-Z0-9_-]+\\.eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "JWT 令牌"
        ))

        // 私钥
        patterns.add(SensitivePattern(
            type = SensitiveType.PRIVATE_KEY,
            name = "Private Key",
            regex = Regex("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "私钥"
        ))

        // 身份证（中国大陆）
        patterns.add(SensitivePattern(
            type = SensitiveType.ID_CARD,
            name = "Chinese ID Card",
            regex = Regex("\\b[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"),
            maskStrategy = MaskStrategy.PARTIAL_MASK,
            description = "中国大陆身份证号"
        ))

        // 手机号（中国大陆）
        patterns.add(SensitivePattern(
            type = SensitiveType.PHONE_NUMBER,
            name = "Chinese Phone",
            regex = Regex("\\b1[3-9]\\d{9}\\b"),
            maskStrategy = MaskStrategy.KEEP_LAST_4,
            description = "中国大陆手机号"
        ))

        // 邮箱
        patterns.add(SensitivePattern(
            type = SensitiveType.EMAIL,
            name = "Email",
            regex = Regex("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"),
            maskStrategy = MaskStrategy.PARTIAL_MASK,
            description = "电子邮箱"
        ))

        // 银行卡
        patterns.add(SensitivePattern(
            type = SensitiveType.BANK_CARD,
            name = "Bank Card",
            regex = Regex("\\b(?:62|4[0-9]|5[1-5]|3[47])\\d{14,17}\\b"),
            maskStrategy = MaskStrategy.KEEP_LAST_4,
            description = "银行卡号"
        ))

        // IP 地址
        patterns.add(SensitivePattern(
            type = SensitiveType.IP_ADDRESS,
            name = "IP Address",
            regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
            maskStrategy = MaskStrategy.PARTIAL_MASK,
            description = "IP 地址（会匹配所有 IP 格式，可能误报版本号）"
        ))

        // password= 模式
        patterns.add(SensitivePattern(
            type = SensitiveType.PASSWORD,
            name = "Password Assignment",
            regex = Regex("(?i)(?:password|passwd|pwd)[\"'\\s:=]+[\"']([^\"'\\s]{4,})[\"']"),
            maskStrategy = MaskStrategy.FULL_MASK,
            description = "密码赋值"
        ))
    }

    data class DetectionResult(
        val type: SensitiveType,
        val name: String,
        val value: String,
        val range: Pair<Int, Int>,
        val description: String
    )
}
