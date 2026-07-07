package com.apex.util

import java.util.Base64
import java.util.regex.Pattern

/**
 * 验证工具类，提供各种输入数据的验证方法
 */
object ValidationUtils {

    /**
     * 验证结果数据类
     *
     * @property isValid 是否通过验证
     * @property message 验证结果描述信息
     */
    data class ValidationResult(val isValid: Boolean, val message: String = "")

    private val EMAIL_REGEX = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    )

    private val PHONE_REGEX = Pattern.compile(
        "^1[3-9]\\d{9}$"
    )

    private val URL_REGEX = Pattern.compile(
        "^(https?://)?([\\w-]+\\.)+[\\w-]+(/[\\w-./?%&=]*)?$"
    )

    private val IPV4_REGEX = Pattern.compile(
        "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    )

    private val HEX_COLOR_REGEX = Pattern.compile(
        "^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$"
    )

    private val BASE64_REGEX = Pattern.compile(
        "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$"
    )

    /**
     * 验证电子邮件地址格式（RFC 标准）
     *
     * @param email 电子邮件地址
     * @return 如果格式有效则返回 true
     */
    fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false
        if (email.length > 254) return false
        return EMAIL_REGEX.matcher(email).matches()
    }

    /**
     * 验证中国大陆手机号码格式
     *
     * @param phone 手机号码
     * @return 如果格式有效则返回 true
     */
    fun isValidPhone(phone: String): Boolean {
        if (phone.isBlank()) return false
        return PHONE_REGEX.matcher(phone).matches()
    }

    /**
     * 验证 URL 格式
     *
     * @param url URL 字符串
     * @return 如果格式有效则返回 true
     */
    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return URL_REGEX.matcher(url).matches()
    }

    /**
     * 验证 IPv4 地址格式
     *
     * @param ip IP 地址字符串
     * @return 如果格式有效则返回 true
     */
    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        return IPV4_REGEX.matcher(ip).matches()
    }

    /**
     * 验证端口号是否有效（1-65535）
     *
     * @param port 端口号
     * @return 如果在有效范围内则返回 true
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    /**
     * 使用 Luhn 算法验证信用卡号
     *
     * @param cardNumber 信用卡号
     * @return 如果通过 Luhn 验证则返回 true
     */
    fun isValidCreditCard(cardNumber: String): Boolean {
        if (cardNumber.isBlank()) return false
        val digits = cardNumber.filter { it.isDigit() }
        if (digits.length < 13 || digits.length > 19) return false

        var sum = 0
        var alternate = false
        for (i in digits.indices.reversed()) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) {
                    n = n % 10 + 1
                }
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /**
     * 验证中国大陆身份证号码（18 位，基础格式和校验码验证）
     *
     * @param idCard 身份证号码
     * @return 如果格式有效则返回 true
     */
    fun isValidChineseIdCard(idCard: String): Boolean {
        if (idCard.isBlank()) return false

        val trimmed = idCard.trim()

        // 支持 15 位旧版身份证
        val idCard15Regex = Pattern.compile("^[1-9]\\d{7}(?:0\\d|1[0-2])(?:[0-2]\\d|3[01])\\d{3}$")
        if (idCard15Regex.matcher(trimmed).matches()) return true

        // 18 位身份证
        val idCard18Regex = Pattern.compile("^[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$")
        if (!idCard18Regex.matcher(trimmed).matches()) return false

        // 加权因子
        val weightFactors = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        // 校验码对应表
        val checkCodes = charArrayOf('1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2')

        var sum = 0
        for (i in 0..16) {
            val digit = trimmed[i] - '0'
            if (digit < 0 || digit > 9) return false
            sum += digit * weightFactors[i]
        }

        val expectedCheckCode = checkCodes[sum % 11]
        val actualCheckCode = trimmed[17].uppercaseChar()

        return actualCheckCode == expectedCheckCode
    }

    /**
     * 验证密码强度
     *
     * @param password 密码
     * @param minLength 最小长度，默认为 8
     * @return 验证结果，包含是否通过及强度描述
     */
    fun isValidPassword(password: String, minLength: Int = 8): ValidationResult {
        if (password.isBlank()) {
            return ValidationResult(false, "密码不能为空")
        }
        if (password.length < minLength) {
            return ValidationResult(false, "密码长度不能少于 $minLength 个字符")
        }

        var strengthScore = 0
        val issues = mutableListOf<String>()

        if (password.any { it.isUpperCase() }) strengthScore++
        else issues.add("缺少大写字母")

        if (password.any { it.isLowerCase() }) strengthScore++
        else issues.add("缺少小写字母")

        if (password.any { it.isDigit() }) strengthScore++
        else issues.add("缺少数字")

        if (password.any { !it.isLetterOrDigit() }) strengthScore++
        else issues.add("缺少特殊字符")

        if (password.length >= 12) strengthScore++
        if (password.length >= 16) strengthScore++

        return when {
            strengthScore >= 5 -> ValidationResult(true, "强密码")
            strengthScore >= 3 -> ValidationResult(true, "中等强度密码，建议：${issues.joinToString("、")}")
            strengthScore >= 2 -> ValidationResult(false, "弱密码，缺少：${issues.joinToString("、")}")
            else -> ValidationResult(false, "密码强度不足，需要包含大写字母、小写字母、数字和特殊字符")
        }
    }

    /**
     * 验证十六进制颜色代码格式
     *
     * @param color 颜色代码字符串（如 #RGB, #RRGGBB, #RRGGBBAA）
     * @return 如果格式有效则返回 true
     */
    fun isValidHexColor(color: String): Boolean {
        if (color.isBlank()) return false
        return HEX_COLOR_REGEX.matcher(color).matches()
    }

    /**
     * 验证字符串是否为有效的 JSON
     *
     * @param json JSON 字符串
     * @return 如果是有效的 JSON 则返回 true
     */
    fun isValidJson(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("{")) {
                org.json.JSONObject(trimmed)
            } else if (trimmed.startsWith("[")) {
                org.json.JSONArray(trimmed)
            } else {
                return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 验证字符串是否为有效的 Base64 编码
     *
     * @param input 输入字符串
     * @return 如果是有效的 Base64 编码则返回 true
     */
    fun isValidBase64(input: String): Boolean {
        if (input.isBlank()) return false
        if (!BASE64_REGEX.matcher(input).matches()) return false
        return try {
            Base64.getDecoder().decode(input)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 验证字符串不能为空
     *
     * @param value 待验证的字符串
     * @param fieldName 字段名称，用于错误信息
     * @return 验证结果
     */
    fun validateNotEmpty(value: String?, fieldName: String): ValidationResult {
        if (value == null) {
            return ValidationResult(false, "$fieldName 不能为 null")
        }
        if (value.isBlank()) {
            return ValidationResult(false, "$fieldName 不能为空")
        }
        return ValidationResult(true)
    }

    /**
     * 验证整数值是否在指定范围内
     *
     * @param value 待验证的整数值
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @param fieldName 字段名称，用于错误信息
     * @return 验证结果
     */
    fun validateRange(value: Int, min: Int, max: Int, fieldName: String): ValidationResult {
        if (value < min) {
            return ValidationResult(false, "$fieldName 不能小于 $min，当前值为 $value")
        }
        if (value > max) {
            return ValidationResult(false, "$fieldName 不能大于 $max，当前值为 $value")
        }
        return ValidationResult(true)
    }

    /**
     * 验证字符串长度是否在指定范围内
     *
     * @param value 待验证的字符串
     * @param min 最小长度（包含）
     * @param max 最大长度（包含）
     * @param fieldName 字段名称，用于错误信息
     * @return 验证结果
     */
    fun validateLength(value: String, min: Int, max: Int, fieldName: String): ValidationResult {
        if (value.length < min) {
            return ValidationResult(false, "$fieldName 长度不能少于 $min 个字符，当前长度为 ${value.length}")
        }
        if (value.length > max) {
            return ValidationResult(false, "$fieldName 长度不能超过 $max 个字符，当前长度为 ${value.length}")
        }
        return ValidationResult(true)
    }

    /**
     * 验证字符串是否匹配指定的正则表达式
     *
     * @param value 待验证的字符串
     * @param regex 正则表达式
     * @param fieldName 字段名称，用于错误信息
     * @return 验证结果
     */
    fun validatePattern(value: String, regex: Regex, fieldName: String): ValidationResult {
        return if (regex.matches(value)) {
            ValidationResult(true)
        } else {
            ValidationResult(false, "$fieldName 格式不匹配要求的模式")
        }
    }
}
